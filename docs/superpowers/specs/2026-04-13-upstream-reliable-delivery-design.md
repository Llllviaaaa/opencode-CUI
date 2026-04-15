# GW→SS 上行消息可靠投递设计

> 日期：2026-04-13
> 状态：Draft

## 1. 问题描述

当Agent给GW发送大量消息时，存在两个问题：

1. **SS读超时导致断连** — SS→GW的WebSocket连接中，SS处理上行消息是同步阻塞的（onMessage → route → handler → DB/Redis/HTTP），处理慢时WS读线程被阻塞，ping/pong无法及时响应，触发 `connectionLostTimeout(30s)` 断连
2. **消息丢失** — GW通过 `SkillRelayService.relayToSkill()` 三层路由转发上行消息给SS，当SS连接不可用时（断连、发包、故障），消息直接丢弃，无缓冲、无重试

### 影响范围

- 上行消息类型：`tool_event`、`tool_done`、`tool_error`、`session_created`、`permission_request`
- 下行路径（SS→GW→Agent）不在本次优化范围内

## 2. 设计目标

1. **消息不丢** — 上行消息在SS连接不可用期间不丢失，连接恢复后自动投递
2. **不断连** — SS处理慢时WS连接保持稳定，不因读超时断开
3. **保序** — 同一session（routingKey）的消息保持有序投递
4. **兼容Legacy** — 仅V2（Mesh）SS走新路径，Legacy SS保持现有逻辑不变

## 3. 整体架构

```
当前架构（Push模式）：
  Agent → GW → SkillRelayService.relayToSkill()
             → L1/L2/L3路由 → WS直推SS

新架构（Store-and-Forward + 分区有序）：
  Agent → GW → UpstreamPartitionWriter
             → hash(routingKey) % N → Redis Stream gw:upstream:{partition}
                                         ↓
                              PartitionConsumer（GW后台线程）
                                         ↓
                              hash ring路由 → WS推送 upstream_delivery
                                         ↓
                              SS接收 → inboundQueue → 消费线程处理
                                         ↓
                              upstream_ack → PartitionConsumer确认 → XDEL
```

### 核心思路

1. **GW侧**：上行消息不再直接WS推送，先写入Redis Stream分区缓冲，PartitionConsumer异步读取并投递
2. **SS侧**：WS消息接收与处理解耦，onMessage()只做入队，独立消费线程串行处理
3. **分区有序**：借鉴Kafka partition模式，`hash(routingKey) % N` 保证同session消息进同一partition，单consumer顺序消费

## 4. Redis Stream 分区设计

### 4.1 Stream分区

```
分区数量：N = 8（可配置，建议为GW Pod数量的2倍以上）

Stream key：
  gw:upstream:0
  gw:upstream:1
  ...
  gw:upstream:7

写入路由：
  partition = hash(routingKey) % N
  routingKey = welinkSessionId > toolSessionId（优先级同现有逻辑）
  routingKey为null时 → 用ak做hash
```

### 4.2 Partition分配管理

```
分配关系存储：
  gw:partition:assignment → Redis Hash
    field: "0" → "gw-pod-1"
    field: "1" → "gw-pod-1"
    field: "2" → "gw-pod-2"
    ...

成员注册：
  gw:partition:members → Redis Sorted Set
    member: "gw-pod-1", score: 心跳时间戳

GW Pod启动时：
  1. ZADD gw:partition:members {now} {podId}
  2. 触发rebalance — 按存活Pod数量均匀分配partition
  3. 启动分配到的partition的PartitionConsumer线程

Leader选举：
  score最小的Pod（最早注册）担任Leader

Leader职责（每30秒执行）：
  1. 检查 gw:partition:members 中超过90秒无心跳的Pod
  2. 标记死亡Pod，回收其partition
  3. 均匀分配给存活Pod
  4. 更新 gw:partition:assignment

心跳：
  每个Pod每30秒 ZADD gw:partition:members {now} {podId}
```

### 4.3 消费位置与清理

```
消费位置持久化：
  gw:upstream:cursor:{partition} → 最后消费的Stream entry ID
  Pod重启或partition重新分配时，从cursor位置继续消费

Stream清理（两层）：
  1. 消费确认后立即 XDEL（已ACK的消息）
  2. 兜底：定时 XTRIM MINID ~{1小时前}（防consumer故障导致堆积）
```

## 5. GW侧改动

### 5.1 新增组件

#### `UpstreamPartitionWriter`

负责将上行消息写入Redis Stream。

```
接口：
  boolean write(GatewayMessage message)

实现：
  routingKey = resolveRoutingKey(message)  // 复用现有逻辑
  if (routingKey == null) routingKey = message.getAk()
  partition = hash(routingKey) % N
  XADD gw:upstream:{partition} * routingKey {rk} payload {json}
```

#### `PartitionConsumer`

每个分区一个消费线程，负责从Stream读取消息并路由投递。

```
核心循环：
  while (running && isAssigned(partition)) {
    entries = XREAD BLOCK 100 STREAMS gw:upstream:{partition} $cursor
    for (entry : entries) {
      routingKey = entry.routingKey
      target = hashRing.getNode(routingKey)  // 投递时路由

      if (target != null && target.isOpen()) {
        发送 upstream_delivery { seq: entry.id, payload: entry.payload }
        等待 upstream_ack（超时30秒）
        收到ACK → XDEL entry，更新cursor
        ACK超时 → 检查连接状态，断开则暂停等待恢复
      } else {
        // 目标连接不可用，暂停等待
        sleep(1s) 后重试
      }
    }
  }

流控窗口：
  windowSize = 100（可配置）
  inflight >= windowSize 时暂停读取
  收到ACK后 inflight--，恢复读取
```

#### `PartitionManager`

负责心跳上报、Leader选举、故障检测、partition分配。

```
职责：
  - 每30秒心跳上报
  - Leader：每30秒检查成员存活，rebalance死亡Pod的partition
  - 分配变更时启动/停止对应的PartitionConsumer
```

### 5.2 `SkillRelayService` 改动

```java
// 改动前
public boolean relayToSkill(GatewayMessage message) {
    boolean v2Delivered = v2RelayToSkill(message);       // 三层路由 + WS直推
    boolean legacyDelivered = legacyStrategy.relayToSkill(message);
    return v2Delivered || legacyDelivered;
}

// 改动后
public boolean relayToSkill(GatewayMessage message) {
    boolean v2Written = upstreamPartitionWriter.write(message);  // 写入Redis Stream
    boolean legacyDelivered = legacyStrategy.relayToSkill(message);  // 不变
    return v2Written || legacyDelivered;
}
```

- `v2RelayToSkill()` 的L1/L2/L3路由逻辑移到 `PartitionConsumer` 中（投递时路由）
- `sendToSession()` 保留，被 `PartitionConsumer` 调用
- 跨GW中继（L2）保留，`PartitionConsumer` 路由时若目标在其他GW Pod，走L2
- Legacy路径完全不变

### 5.3 `SkillWebSocketHandler` 改动

```
handleTextMessage() 新增处理：
  case "upstream_ack":
    → 通知对应的PartitionConsumer该消息已确认（inflight--）
```

## 6. SS侧改动

### 6.1 WS接收与处理解耦（防断连）

```java
// GatewayWSClient.InternalWebSocketClient

// 新增：per-connection本地消息队列
private final BlockingQueue<String> inboundQueue = new LinkedBlockingQueue<>(10000);

// WS读线程：只做入队，立即返回
@Override
public void onMessage(String message) {
    inboundQueue.offer(message);
}

// 独立消费线程（per-connection，单线程串行，保证顺序）
private void consumeLoop() {
    while (running) {
        String msg = inboundQueue.poll(1, TimeUnit.SECONDS);
        if (msg != null) {
            processMessage(msg);
        }
    }
}
```

保序保证：同session → hash ring → 同connection → 同inboundQueue → 同消费线程 → 有序

### 6.2 处理 `upstream_delivery` 消息

```java
private void processMessage(String rawMessage) {
    JsonNode node = objectMapper.readTree(rawMessage);
    String type = node.path("type").asText("");

    if ("upstream_delivery".equals(type)) {
        String seq = node.path("seq").asText();

        // 去重：跳过已处理的消息
        if (seq.compareTo(lastProcessedSeq) <= 0) {
            sendAck(seq);  // 仍然发ACK让GW清理
            return;
        }

        // 提取原始payload，走现有处理逻辑
        JsonNode payload = node.path("payload");
        gatewayRelayService.handleGatewayMessage(payload.toString());

        // 更新处理位置，发送ACK
        lastProcessedSeq = seq;
        sendAck(seq);
    } else {
        // 其他类型（invoke, route_confirm, route_reject）走现有逻辑
        gatewayRelayService.handleGatewayMessage(rawMessage);
    }
}
```

### 6.3 `lastProcessedSeq` 管理

```
存储位置：PooledConnection内存字段

class PooledConnection {
    int slotIndex;
    volatile WebSocketClient client;
    AtomicInteger reconnectAttempts;
    volatile String lastProcessedSeq = "0-0";  // 新增，Redis Stream entry ID格式
}

场景分析：
  - 同connection重连：lastProcessedSeq保留，去重有效
  - SS重启：lastProcessedSeq归0，GW从cursor位置推送，可能少量重复，通过seq比较跳过
  - 发包（新instanceId）：GW hash ring更新，新连接的lastProcessedSeq=0，消息从当前cursor开始，不会重复
```

### 6.4 不改动的部分

- `GatewayMessageRouter.route()` 内部逻辑不变
- 所有handler（handleToolEvent, handleToolDone等）不变
- SS→GW的下行invoke发送（sendToGateway）不变
- 连接池管理、重连逻辑不变
- route_confirm / route_reject 处理不变

## 7. WS流控协议

### 7.1 新增消息类型

```json
// GW → SS：带seq的上行消息
{ "type": "upstream_delivery", "seq": "1713000000000-0", "payload": { /* GatewayMessage */ } }

// SS → GW：确认
{ "type": "upstream_ack", "ackSeq": "1713000000000-0" }
```

seq直接使用Redis Stream的entry ID，不需要额外维护计数器。

### 7.2 流控窗口

```
PartitionConsumer侧：
  windowSize = 100（可配置）
  inflight = 已发送但未收到ACK的消息数

  inflight >= windowSize → 暂停从Stream读取
  收到 upstream_ack → inflight--，恢复读取

效果：
  SS处理慢 → ACK慢 → inflight达上限 → GW暂停推送
  消息安全堆积在Redis Stream中
```

### 7.3 ACK超时

```
发送 upstream_delivery 后等待ACK：
  超时时间 = 30秒（可配置）

  超时未收到ACK：
    检查SS连接是否存活
    ├─ 连接存活：继续等待（SS可能处理慢）
    └─ 连接已断：暂停消费，等连接恢复后从该消息重新投递
```

## 8. 故障场景

| 场景 | 处理方式 | 结果 |
|------|---------|------|
| SS处理慢 | 流控窗口暂停推送，消息堆积在Stream | 不断连，不丢消息 |
| SS连接断开（网络抖动） | PartitionConsumer暂停，SS重连后hash ring更新，继续投递 | 不丢消息，少量延迟 |
| SS发包 | 旧SS下线→PartitionConsumer暂停，新SS上线→hash ring更新→继续从cursor投递 | 不丢消息 |
| GW Pod故障 | Leader检测心跳超时→partition重新分配→新Pod从cursor继续消费 | 不丢消息，切换期间有延迟 |
| Redis故障 | 降级到直接WS推送（现有逻辑兜底） | 退化为当前行为 |

## 9. 配置项

| 配置项 | 默认值 | 说明 |
|-------|--------|------|
| `gateway.upstream.partition-count` | 8 | Stream分区数量 |
| `gateway.upstream.window-size` | 100 | 流控窗口大小（最大未ACK消息数） |
| `gateway.upstream.ack-timeout-seconds` | 30 | ACK等待超时 |
| `gateway.upstream.heartbeat-interval-seconds` | 30 | Partition Manager心跳间隔 |
| `gateway.upstream.member-timeout-seconds` | 90 | Pod心跳超时阈值 |
| `gateway.upstream.stream-max-age-hours` | 1 | Stream兜底清理周期 |
| `skill.gateway.inbound-queue-capacity` | 10000 | SS侧per-connection消息队列容量 |

## 10. 改动范围总结

### GW侧（ai-gateway）

| 改动类型 | 文件/组件 | 说明 |
|---------|----------|------|
| 新增 | `UpstreamPartitionWriter` | 写入Redis Stream |
| 新增 | `PartitionConsumer` | 从Stream消费并路由投递 |
| 新增 | `PartitionManager` | 心跳、Leader选举、partition分配 |
| 修改 | `SkillRelayService.relayToSkill()` | V2路径从直推改为写Stream |
| 修改 | `SkillWebSocketHandler` | 新增 `upstream_ack` 消息处理 |
| 不变 | Legacy路径 | 完全不变 |
| 不变 | 下行路径（SS→GW→Agent） | 完全不变 |

### SS侧（skill-server）

| 改动类型 | 文件/组件 | 说明 |
|---------|----------|------|
| 修改 | `GatewayWSClient.InternalWebSocketClient` | onMessage()改为入队，新增消费线程 |
| 修改 | `GatewayWSClient.PooledConnection` | 新增lastProcessedSeq字段 |
| 新增 | `upstream_delivery` 处理逻辑 | 解包payload + 去重 + 发ACK |
| 不变 | `GatewayMessageRouter` | 不变 |
| 不变 | 所有handler | 不变 |
| 不变 | 下行invoke发送 | 不变 |
