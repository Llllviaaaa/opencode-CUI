# AI Gateway & Skill Server 变更特性差异清单

**对比基准**：`9422b1d`（0320 版本） → `route-redesign-0321` 分支 HEAD
**变更规模**：166 文件，+15,123 / -2,191 行
**对应链接**：
- 0320 版本：https://github.com/Llllviaaaa/opencode-CUI/tree/9422b1d2da6ebd0b5470a05ff45ccde9920f50e8
- 最新分支：https://github.com/Llllviaaaa/opencode-CUI/tree/route-redesign-0321

---

## 第一部分：AI Gateway 变更

---

### 特性 1：一致性哈希环（ConsistentHashRing）

#### 涉及文件
- `ai-gateway/src/main/java/com/opencode/cui/gateway/service/ConsistentHashRing.java`
- `ai-gateway/src/test/java/com/opencode/cui/gateway/service/ConsistentHashRingTest.java`

#### 设计方案

一致性哈希环是 Source 服务实例负载均衡的核心数据结构。

**数据结构：**
- 主环：`TreeMap<Long, T> ring` — 有序哈希环，键为哈希值，值为实际节点（WebSocketSession）
- 节点哈希映射：`Map<String, List<Long>> nodeHashes` — 反向索引，记录每个物理节点的虚拟位置列表
- 虚拟节点数：默认 150 个，可配置

**并发控制：**
- 使用 `ReentrantReadWriteLock`
- 读操作（`getNode`、`size`）持读锁，支持并发
- 写操作（`addNode`、`removeNode`）持写锁，全序列化

**关键方法：**

1. **`addNode(String nodeKey, T node)`**
   - 为单个物理节点添加 150 个虚拟位置到环上
   - 虚拟位置通过 `hash(nodeKey + "#" + i)` 生成，均匀分布
   - 若节点已存在，先清除旧位置再重新添加

2. **`getNode(String key)`**
   - 算法：`hash(key)` → `ring.ceilingEntry(position)` → 环上顺时针查找第一个节点
   - 若无法找到，wrap around 到环的起点（`ring.firstEntry()`）
   - 时间复杂度：O(log N)

3. **`removeNode(String nodeKey)`**
   - 根据 `nodeHashes` 查找所有虚拟位置，逐个从 `ring` 中删除
   - 影响范围：仅影响路由到该节点的键，其他键映射稳定

**哈希函数：**
- 算法：MD5（128 位）→ XOR 高低 64 位 → signed long
- 无外部依赖，均衡性好

---

### 特性 2：上行路由学习表（UpstreamRoutingTable）

#### 涉及文件
- `ai-gateway/src/main/java/com/opencode/cui/gateway/service/UpstreamRoutingTable.java`
- `ai-gateway/src/test/java/com/opencode/cui/gateway/service/UpstreamRoutingTableTest.java`

#### 设计方案

上行路由表实现了 Source 服务与 Agent 之间的自动路由发现（被动学习机制）。

**数据结构：**
- 底层：Caffeine 缓存，容量 100,000 条目，过期时间 30 分钟
- 键编码方案（无碰撞）：
  - `toolSessionId` 直接作为键（如 `"ts-abc123"`）
  - `welinkSessionId` 加前缀 `"w:"` 作为键（如 `"w:session-42"`）
- 值：Source 类型字符串（如 `"skill-server"`）

**学习路径（二元学习）：**

1. **从 invoke 消息学习**
   - 路径 1：顶层 `welinkSessionId` → `"w:" + welinkSessionId` → Source 类型
   - 路径 2：`payload.toolSessionId` → toolSessionId → Source 类型
   - 触发点：Skill Server 发来 invoke 消息时

2. **从中继消息学习**
   - `learnFromRelay()` — 批量学习一组路由键与 Source 的关联
   - 用途：GW-B 收到 GW-A 的中继消息时，将路由键注册到本地

**路由解析优先级：**
```
resolveSourceType(message):
  1. 查询 toolSessionId → 返回 sourceType
  2. 若无，查询 "w:" + welinkSessionId → 返回 sourceType
  3. 若无，返回 null（触发广播降级）
```

**并发特性：**
- Caffeine 内部线程安全（ConcurrentHashMap）
- 自动过期清理，无额外锁开销

---

### 特性 3：下行消息路由重设计（SkillRelayService V2）

#### 涉及文件
- `ai-gateway/src/main/java/com/opencode/cui/gateway/service/SkillRelayService.java`
- `ai-gateway/src/main/java/com/opencode/cui/gateway/service/SkillRelayStrategy.java`
- `ai-gateway/src/test/java/com/opencode/cui/gateway/service/SkillRelayServiceTest.java`
- `ai-gateway/src/test/java/com/opencode/cui/gateway/service/SkillRelayServiceV2Test.java`

#### 设计方案

核心路由服务，使用双策略分支处理 Mesh（新版）和 Legacy（旧版）两种 Source 服务。

**双策略架构：**

| 维度 | Mesh（新版） | Legacy（旧版） |
|------|------------|--------------|
| 客户端特征 | 握手时带 `instanceId` | 无 `instanceId` |
| 路由方式 | 一致性哈希 + 上行路由表 | Owner 心跳 + Rendezvous Hash |
| 连接管理 | `sourceTypeSessions` 按 source 分组 | `sourceSessions` 按 source 分组 |

**连接管理数据结构：**

```
sourceTypeSessions: Map<String, Map<String, WebSocketSession>>
  ├─ source_type_1:
  │  ├─ ss-instance-1 → WebSocketSession
  │  └─ ss-instance-2 → WebSocketSession
  └─ source_type_2: {...}

hashRings: Map<String, ConsistentHashRing<WebSocketSession>>
  ├─ source_type_1 → ConsistentHashRing(150 vnodes)
  └─ source_type_2 → ConsistentHashRing(150 vnodes)
```

**注册流程（registerSourceSession）：**
1. 判策略：若无 instanceId → Legacy，有则 → Mesh
2. [Mesh] 解析 sourceType 和 ssInstanceId
3. 加入连接池：`sourceTypeSessions[source][ssInstanceId] = session`
4. 更新哈希环：`hashRings[source].addNode(ssInstanceId, session)`

**下行 invoke 处理（Agent → Source）三层投递：**

```
handleInvokeFromSkill(session, message):
  1. 验证 source 一致性（绑定 vs 消息）、ak、userId
  2. 学习路由：UpstreamRoutingTable.learnRoute(message, source)
  3. 三层投递：
     ├─ 本地投递：eventRelayService.sendToLocalAgentIfPresent(ak, message)
     ├─ 远程中继：relayToRemoteGw(ak, message, source)
     │  └─ 查 gw:internal:agent:{ak} 找目标 GW 实例
     │  └─ 构建 RelayMessage（含 routingKeys、sourceType）
     │  └─ 发布到 gw:relay:{targetGwId}
     └─ 待机队列：enqueueToPending(ak, message)
        └─ gw:pending:{ak} Redis List，TTL 可配置
  4. 降级：若启用 legacy-relay，也发布到 agent pub/sub 频道
```

**上行消息路由（Source ← Agent）：**

```
v2RelayToSkill(message):
  1. 解析路由键：优先 welinkSessionId > toolSessionId
  2. 查路由表：sourceType = routingTable.resolveSourceType(message)

  [已知源分支]
  3a. 获取该 sourceType 的一致性哈希环
  3b. ring.getNode(routingKey) → 选中 SS 连接
  3c. 若连接开放 → 直发，计数 routingHitCount
       否则 → 广播到该 sourceType 所有连接

  [未知源分支]
  4. 若 message.source 存在 → 尝试同上流程
  5. 若都失败 → broadcastToAllGroups()
     └─ 遍历所有 sourceType，每个选一条连接发送
     └─ 计数 routingBroadcastCount

  降级回退：
  若 V2 失败且 legacy-relay.enabled → 委托 LegacySkillRelayStrategy
```

**指标收集（AtomicLong）：**
- `relayLocalCount` — 本地投递计数
- `relayPubsubCount` — 远程中继计数
- `relayPendingCount` — 待机队列计数
- `routingHitCount` — 路由表命中计数
- `routingBroadcastCount` — 广播降级计数

---

### 特性 4：旧版路由策略（LegacySkillRelayStrategy）

#### 涉及文件
- `ai-gateway/src/main/java/com/opencode/cui/gateway/service/LegacySkillRelayStrategy.java`
- `ai-gateway/src/test/java/com/opencode/cui/gateway/service/LegacySkillRelayStrategyTest.java`

#### 设计方案

为不带 instanceId 的旧版 Source 服务提供完整的路由支持。

**核心机制：Owner 心跳 + Rendezvous Hash**

**Redis Key 设计：**
```
gw:source:owner:{source}:{instanceId} → "alive"（TTL 30s）
gw:source:owners:{source} → Set<{source}:{instanceId}>
gw:agent:source:{ak} → source_type
```

**双向消息路由：**

1. **下行（Agent → Source）：**
```
relayToSkill(message):
  1. 解析 source：从 message.source 或 Redis 绑定或推断
  2. 选择 owner：selectOwner(source, key)
     └─ 遍历所有拥有该 source 的 GW 实例
     └─ Rendezvous Hash：score = hash(key + "|" + ownerKey)
     └─ 选最高分者作为 owner
  3. 若 owner 是本机 → sendViaDefaultLink(source, message)
  4. 若 owner 是远端 → publishToRelay(ownerId, message)
```

2. **上行（Source → Agent）：**
```
handleInvokeFromSkill(session, message):
  1. 验证 source 一致性
  2. 绑定：gw:agent:source:{ak} = source
  3. 投递：agent:{ak} 频道
```

**Owner 心跳：**
- 定时任务（每 10s）刷新 `gw:source:owner:{source}:{instanceId}` TTL=30s
- selectOwner 选择时过滤掉 TTL 已过期的条目

**Rendezvous Hash vs 一致性哈希：**
- 一致性哈希：固定键选节点，节点变化影响有限
- Rendezvous Hash：给每个节点计分选最高分，用于 Owner 选举
- 公式：`score(key, owner) = hash(key + "|" + owner)`，max score wins

---

### 特性 5：GW-to-GW 中继消息封装（RelayMessage）

#### 涉及文件
- `ai-gateway/src/main/java/com/opencode/cui/gateway/model/RelayMessage.java`
- `ai-gateway/src/test/java/com/opencode/cui/gateway/model/RelayMessageTest.java`

#### 设计方案

跨 GW 实例转发时的统一包装，支持路由学习传播。

**消息结构（Java record，不可变）：**
```java
public record RelayMessage(
    String type,              // 恒为 "relay"（区分 Legacy 原始 GatewayMessage）
    String sourceType,        // 源服务类型，如 "skill-server"
    List<String> routingKeys, // [toolSessionId, "w:welinkSessionId", ...]
    String originalMessage    // 原始 GatewayMessage JSON 字符串
)
```

**发送端流程：**
```
relayToRemoteGw(ak, message, source):
  targetInstanceId = getInternalAgentInstance(ak)
  routingKeys = [toolSessionId, "w:"+welinkSessionId, ...]
  relayMsg = RelayMessage.of(sourceType, routingKeys, json)
  publishToGwRelay(targetInstanceId, relayMsg JSON)
```

**接收端流程：**
```
处理 gw:relay:{本instanceId} 频道：
  若 json 包含 "type":"relay" → 按 RelayMessage 解析
    routingTable.learnFromRelay(routingKeys, sourceType)
    从 originalMessage 提取 ak 投递给本地 Agent
  否则 → 按 GatewayMessage 解析（Legacy 兼容）
```

---

### 特性 6：Redis 消息代理增强（RedisMessageBroker）

#### 涉及文件
- `ai-gateway/src/main/java/com/opencode/cui/gateway/service/RedisMessageBroker.java`
- `ai-gateway/src/test/java/com/opencode/cui/gateway/service/RedisMessageBrokerTest.java`
- `ai-gateway/src/test/java/com/opencode/cui/gateway/service/PendingQueueTest.java`

#### 设计方案

Redis 是多实例协调的中枢，维护分布式状态和事件通道。

**新增 Key 空间（v3）：**
```
conn:ak:{ak} → gatewayInstanceId（TTL 120s）
  用途：SS 查询 Agent 在哪个 GW 实例
  触发：Agent 注册成功、心跳刷新

gw:internal:agent:{ak} → instanceId（TTL 120s）
  用途：GW 内部中转路由
  关系：与 conn:ak 并行维护

gw:pending:{ak} → [message1, message2, ...] (Redis List)
  用途：下行消息缓冲队列（Agent 离线期间）
  TTL：可配置（默认 60s）
  原子操作：Lua 脚本 LRANGE + DEL
```

**保留（兼容）Key：**
```
agent:{ak} → pub/sub 频道（旧版 agent 消息分发）
gw:agent:user:{ak} → userId（ak → userId 映射）
auth:nonce:{nonce} → "used"（签名防重放）
```

**关键方法：**

1. **待机队列操作：**
   - `enqueuePending(ak, message, ttl)` — RPUSH + EXPIRE（每次 push 刷新 TTL）
   - `drainPending(ak)` — Lua 脚本 LRANGE+DEL 原子操作，Agent 重连时调用

2. **Agent 连接注册：**
   - `bindConnAk(ak, gwInstanceId, ttl)` — 设置 `conn:ak:{ak}`
   - `bindInternalAgent(ak, instanceId, ttl)` — 设置 `gw:internal:agent:{ak}`
   - `conditionalRemoveConnAk(ak, expectedInstanceId)` — Lua CAS 删除，防止误删已重连到其他 GW 的 Agent

3. **GW 中继 pub/sub：**
   - `subscribeToGwRelay(instanceId, handler)` — 订阅 `gw:relay:{instanceId}` 频道
   - `publishToGwRelay(targetInstanceId, relayMessageJson)` — 发布到目标 GW 频道

---

### 特性 7：GW 实例注册与发现

#### 涉及文件
- `ai-gateway/src/main/java/com/opencode/cui/gateway/service/GatewayInstanceRegistry.java`
- `ai-gateway/src/main/java/com/opencode/cui/gateway/controller/InternalInstanceController.java`
- `ai-gateway/src/test/java/com/opencode/cui/gateway/service/GatewayInstanceRegistryTest.java`
- `ai-gateway/src/test/java/com/opencode/cui/gateway/controller/InternalInstanceControllerTest.java`

#### 设计方案

**双键注册策略（兼容新旧 SS）：**

```
@PostConstruct register():
  [Legacy 路径] gw:instance:{instanceId} → registrationJson (TTL 30s)
    消费者：旧版 SS（通过 SCAN 扫描 Redis key）
  [新路径] gw:internal:instance:{instanceId} → registrationJson (TTL 30s)
    消费者：新版 SS（通过 HTTP 接口查询）

注册值 JSON：
  {"instanceId":"...", "wsUrl":"ws://...", "startedAt":"...", "lastHeartbeat":"..."}

心跳：定时任务每 10s 刷新两个 key 的 TTL
销毁：@PreDestroy 删除两个 key
```

**HTTP 实例发现接口：**
```
GET /internal/instances
返回：{"instances": [
  {"instanceId":"gw-1", "wsUrl":"ws://192.168.1.10:8081/ws/skill"},
  {"instanceId":"gw-2", "wsUrl":"ws://192.168.1.11:8081/ws/skill"}
]}
实现：扫描 gw:internal:instance:* keys → 解析 JSON → 组装返回
开关：gateway.internal-api.enabled（默认 true）
```

---

### 特性 8：AK/SK 认证双模式 + 三级缓存

#### 涉及文件
- `ai-gateway/src/main/java/com/opencode/cui/gateway/service/AkSkAuthService.java`
- `ai-gateway/src/main/java/com/opencode/cui/gateway/service/IdentityApiClient.java`
- `ai-gateway/src/main/java/com/opencode/cui/gateway/config/GatewayConfig.java`
- `ai-gateway/src/test/java/com/opencode/cui/gateway/service/AkSkAuthServiceTest.java`
- `ai-gateway/src/test/java/com/opencode/cui/gateway/service/IdentityApiClientTest.java`

#### 设计方案

**两种验签模式（`gateway.auth.mode`）：**

| 模式 | 验签方式 | 密钥来源 | 延迟 |
|------|---------|---------|------|
| `gateway`（默认） | 本地 HMAC-SHA256 | MySQL `ak_sk_credential` 表 | <5ms |
| `remote` | 外部身份 API + 三级缓存 | 外部服务维护 | L1 <1ms / L3 3-5ms |

**共用验证（两模式都执行）：**
- 时间窗口校验：`|now - timestamp| ≤ 300s`
- Nonce 防重放：Redis `SET NX gw:auth:nonce:{nonce}`（TTL 300s）

**HMAC-SHA256 签名算法（Gateway 模式）：**
```
待签名字符串 = ak + timestamp + nonce
签名计算 = Base64(HmacSHA256(SK, stringToSign))
校验使用 MessageDigest.isEqual()（常时间比较，防旁道攻击）
```

**Remote 模式三级缓存管线：**
```
1️⃣ L1 Caffeine 本地缓存
   ├─ 容量：max-size = 10000
   ├─ TTL：300s
   └─ 作用：毫秒级快速路径，无网络往返

2️⃣ L2 Redis 缓存（L1 未命中）
   ├─ key：auth:identity:{ak}
   ├─ TTL：3600s
   └─ 作用：分布式缓存，多 GW 实例共享

3️⃣ L3 外部身份 API（L1/L2 都未命中）
   ├─ 端点：POST /appstore/wecodeapi/open/identity/check
   ├─ 请求头：Authorization: Bearer {bearerToken}
   ├─ 超时：3s
   └─ 成功后回填 L1 + L2
```

**调用流程：**
```
verify(ak, timestamp, nonce, signature)
  ├─ [参数校验] ak, timestamp, nonce, signature 非空
  ├─ [时间窗口] |now - ts| > 300s → 拒绝
  ├─ [Nonce 防重放] Redis SET NX → false → 拒绝
  ├─ [模式分支]
  │  ├─ remote → resolveIdentity(ak, ts, nonce, sig)
  │  │  └─ L1 → L2 → L3 → 回填 → 返回 userId
  │  └─ gateway → verifyLocally(ak, ts, nonce, sig)
  │     └─ 查 DB → HMAC 计算 → 常时间比较 → 返回 userId
  └─ 返回 userId 或 null
```

**与 0320 差异：**
- 0320 仅支持 gateway 模式，无分级缓存
- 0321 新增 remote 模式、L1/L2/L3 缓存、IdentityApiClient

---

### 特性 9：设备绑定开关化设计

#### 涉及文件
- `ai-gateway/src/main/java/com/opencode/cui/gateway/service/DeviceBindingService.java`
- `ai-gateway/src/main/resources/application.yml`

#### 设计方案

**新增三个独立开关：**
```yaml
gateway:
  device-binding:
    enabled: false          # 总开关：false 时全部放行
    check-mac: true         # MAC 地址校验开关
    check-tool-type: true   # toolType 校验开关
```

**校验逻辑流程：**
```
validate(ak, macAddress, toolType)
  ├─ enabled == false → 放行
  ├─ 按 AK 查询最近连接：findLatestByAkId(ak)
  ├─ existing == null → 首次连接，放行
  ├─ existing != null：
  │  ├─ checkToolType == true?
  │  │  └─ boundToolType != toolType → 拒绝
  │  ├─ checkMac == true?
  │  │  └─ boundMac != macAddress → 拒绝
  │  └─ 通过
  └─ 异常 → fail-open（返回 true，宁错误放行不拒绝服务）
```

**与 0320 差异：**
- 0320：硬规则无开关
- 0321：三个独立开关，支持灵活控制（调试/迁移/灾备）

---

### 特性 10：Agent 多 toolType 在线状态

#### 涉及文件
- `ai-gateway/src/main/java/com/opencode/cui/gateway/service/AgentRegistryService.java`
- `ai-gateway/src/main/java/com/opencode/cui/gateway/controller/AgentController.java`
- `ai-gateway/src/main/java/com/opencode/cui/gateway/repository/AgentConnectionRepository.java`
- `ai-gateway/src/main/resources/mapper/AgentConnectionMapper.xml`
- `ai-gateway/src/test/java/com/opencode/cui/gateway/service/InternalAgentRegistryTest.java`

#### 设计方案

**问题：** 0320 版 `findOnlineByAkId()` 使用 `LIMIT 1` 返回单条，同一 AK 多 toolType 同时在线时会遗漏。

**解决方案：**
- `findOnlineByAkId` 去掉 `LIMIT 1`，返回列表
- 新增方法 `findOnlineByAk(ak)` → `List<AgentConnection>`
- `/api/gateway/agents?ak=` 改用列表查询

**新增 SQL：**
```xml
<select id="findOnlineByAkId" resultMap="AgentConnectionResultMap">
    SELECT * FROM agent_connection
    WHERE ak_id = #{akId} AND status = 'ONLINE'
</select>
```

**Agent 注册复用逻辑：**
```
register(userId, akId, deviceName, mac, os, toolType, toolVersion)
  ├─ effectiveToolType = toolType ?? "channel"
  ├─ 按 AK + toolType 查询：findByAkIdAndToolType(akId, toolType)
  ├─ existing != null → 复用（Re-registration），更新元数据
  └─ existing == null → 新建（First Registration），Snowflake ID
```

---

### 特性 11：Agent 离线消息缓冲队列

#### 涉及文件
- `ai-gateway/src/main/java/com/opencode/cui/gateway/service/RedisMessageBroker.java`（pending 相关方法）
- `ai-gateway/src/main/java/com/opencode/cui/gateway/ws/AgentWebSocketHandler.java`（drainPending 调用）
- `ai-gateway/src/test/java/com/opencode/cui/gateway/service/PendingQueueTest.java`

#### 设计方案

**Redis Key：** `gw:pending:{ak}` → Redis List

**入队（Agent 离线时）：**
```
enqueuePending(ak, message, ttl):
  RPUSH gw:pending:{ak} message
  EXPIRE gw:pending:{ak} ttl   // 每次 push 刷新 TTL
```

**出队（Agent 重连时）：**
```
drainPending(ak):
  Lua 脚本：
    local messages = redis.call('LRANGE', KEYS[1], 0, -1)
    redis.call('DEL', KEYS[1])
    return messages
  → 原子化 LRANGE+DEL，保证重连期间入队的消息不丢失
```

**配置：**
```yaml
gateway.relay.pending-ttl-seconds: 60
```

---

### 特性 12：协议增强（GatewayMessage）

#### 涉及文件
- `ai-gateway/src/main/java/com/opencode/cui/gateway/model/GatewayMessage.java`
- `ai-gateway/src/test/java/com/opencode/cui/gateway/model/GatewayMessageTest.java`

#### 设计方案

**新增字段：**
- `gatewayInstanceId` — GW 实例标识（路由内部用，下发 Agent 前剥离）

**新增消息类型：**
- `ROUTE_CONFIRM` — 路由确认（当 instanceId 匹配时）
- `ROUTE_REJECT` — 路由拒绝（协商失败）

**新增便捷方法（不可变转换）：**
- `withGatewayInstanceId(id)` → 新对象，附加实例标识
- `withoutRoutingContext()` → 新对象，清除 userId/source/gatewayInstanceId
- `ensureTraceId()` → 新对象，若缺失则生成 UUID

---

### 特性 13：WebSocket 握手认证增强

#### 涉及文件
- `ai-gateway/src/main/java/com/opencode/cui/gateway/ws/SkillWebSocketHandler.java`
- `ai-gateway/src/main/java/com/opencode/cui/gateway/ws/AgentWebSocketHandler.java`
- `ai-gateway/src/test/java/com/opencode/cui/gateway/ws/SkillWebSocketHandlerTest.java`

#### 设计方案

**SkillWebSocketHandler 握手流程：**
```
beforeHandshake:
  1. 从 Sec-WebSocket-Protocol 子协议提取认证
     格式：auth.{base64url({token, source, instanceId})}
  2. 验证 token 与 skill.gateway.internal-token 匹配
  3. 提取 source（强制）和 instanceId（可选）
  4. 决策：instanceId 存在 → Mesh 策略 / 否则 → Legacy 策略
  5. 保存到 session 属性
```

**AgentWebSocketHandler 增强：**
- 新增 Redis 分布式锁控制并发注册（同一 AK 并发注册）
  - key = `gw:register:lock:{akId}`，TTL=10s
  - Lua 脚本原子释放（仅释放自己持有的锁）
- Agent 重连时调用 `drainPending(ak)` 投递缓冲消息
- 断开时使用 `conditionalRemoveConnAk(ak, instanceId)` CAS 删除

---

### 特性 14：GW 配置变更总览

#### 涉及文件
- `ai-gateway/src/main/resources/application.yml`
- `ai-gateway/pom.xml`

#### 新增配置项

```yaml
gateway:
  instance-id: ${GATEWAY_INSTANCE_ID:gateway-local}

  relay:
    pending-ttl-seconds: 60

  legacy-relay:
    enabled: true

  upstream-routing:
    cache-max-size: 100000
    cache-expire-minutes: 30

  skill-relay:
    owner-heartbeat-interval-seconds: 10
    owner-ttl-seconds: 30

  auth:
    mode: ${AUTH_MODE:gateway}
    timestamp-tolerance-seconds: 300
    nonce-ttl-seconds: 300
    identity-api:
      base-url: ${IDENTITY_API_BASE_URL:}
      bearer-token: ${IDENTITY_API_BEARER_TOKEN:}
      timeout-ms: ${IDENTITY_API_TIMEOUT_MS:3000}
    identity-cache:
      l1-ttl-seconds: ${AUTH_CACHE_L1_TTL:300}
      l1-max-size: ${AUTH_CACHE_L1_MAX_SIZE:10000}
      l2-ttl-seconds: ${AUTH_CACHE_L2_TTL:3600}

  device-binding:
    enabled: false
    check-mac: true
    check-tool-type: true

  internal-api:
    enabled: true

skill:
  gateway:
    internal-token: changeme
```

#### 新增依赖

```xml
<!-- Caffeine（L1 本地缓存） -->
<dependency>
    <groupId>com.github.ben-manes.caffeine</groupId>
    <artifactId>caffeine</artifactId>
</dependency>
```

---

## 第二部分：Skill Server 变更

---

### 特性 1：纯 Redis 会话 Ownership 管理（SessionRouteService）

#### 涉及文件
- `skill-server/src/main/java/com/opencode/cui/skill/service/SessionRouteService.java`
- `skill-server/src/main/java/com/opencode/cui/skill/model/SessionRoute.java`
- `skill-server/src/main/java/com/opencode/cui/skill/repository/SessionRouteRepository.java`（标记为过时）
- `skill-server/src/main/resources/mapper/SessionRouteMapper.xml`
- `skill-server/src/test/java/com/opencode/cui/skill/service/SessionRouteServiceTest.java`
- `skill-server/src/test/java/com/opencode/cui/skill/service/SessionRouteServiceCacheTest.java`

#### 设计方案

从 MySQL 双层存储迁移到纯 Redis 缓存模式。

**Redis Key 设计：**
- 格式：`ss:internal:session:{welinkSessionId}`
- Value：owning instanceId（字符串）
- TTL：1800 秒（30 分钟），可配置 `skill.session.ownership-cache-ttl-seconds`

**核心方法：**

1. **createRoute（SETNX 原子创建）**
   - Redis `setIfAbsent`，第一个到达者成为 owner
   - 失败安全：异常捕获不中断流程

2. **isMySession（O(1) 查询）**
   - Redis GET → 比较 `instanceId.equals(owner)`
   - 异常降级：Redis 失败返回 true（乐观假设本实例是 owner）

3. **ensureRouteOwnership（SETNX + GET 两步）**
   - SETNX 成功 → 本实例声称 ownership
   - key 已存在 → GET 验证是否自己

4. **tryTakeover（Lua CAS 乐观锁接管）**
   - Lua 脚本：
     ```lua
     local current = redis.call('GET', KEYS[1])
     if current == ARGV[1] then
         redis.call('SET', KEYS[1], ARGV[2], 'EX', tonumber(ARGV[3]))
         return 1
     end
     return 0
     ```
   - 入参：welinkSessionId, deadInstanceId, newInstanceId, ttl
   - 返回：1 成功 / 0 冲突

**降级策略：**
- Redis 异常时所有方法返回乐观值（true）
- 理由：宁可多实例处理重复消息，不因 Redis 故障丢弃消息

**与 0320 差异：**

| 维度 | 0320 | 0321 |
|------|------|------|
| 存储介质 | MySQL + Redis 双层 | 纯 Redis |
| 查询复杂度 | MySQL JOIN | Redis GET O(1) |
| 过期清理 | 显式 DELETE + 定时任务 | TTL 自动过期 |
| 接管方式 | 启动时扫描 + 定时轮转 | 消息驱动懒接管 + Lua CAS |

---

### 特性 2：消息驱动路由与接管（GatewayMessageRouter）

#### 涉及文件
- `skill-server/src/main/java/com/opencode/cui/skill/service/GatewayMessageRouter.java`
- `skill-server/src/test/java/com/opencode/cui/skill/service/SsRelayAndTakeoverTest.java`

#### 设计方案

**四级路由决策树：**

```
消息到达 → type ∈ {tool_event, tool_done, tool_error, permission_request}?
  ├─ 否 → dispatchLocally()（agent_online, agent_offline, session_created 等）
  └─ 是 → sessionId = resolveSessionId()
          ownerInstance = sessionRouteService.getOwnerInstance(sessionId)

          Level 1: 本实例是 owner?
            └─ 是 → dispatchLocally()（routeLocalCount++）

          Level 2: 有远程 owner?
            └─ 是 → publishToSsRelay(ownerInstance)
                 subscribers > 0 → 成功（routeRelayCount++）
                 subscribers = 0 → owner 可能死亡，继续 Level 3

          Level 3: shouldTakeover(ownerInstance)?
            └─ 检查 skillInstanceRegistry.isInstanceAlive(ownerInstance)
               └─ 已死亡：
                  ├─ ownerInstance = null → ensureRouteOwnership()
                  └─ ownerInstance ≠ null → tryTakeover()
                     ├─ 成功 → dispatchLocally()（takeoverCount++）
                     └─ 失败 → 转发给获胜者（takeoverConflictCount++）

          Level 4: 无法确定 owner → 丢弃消息，日志 WARN
```

**SessionId 解析优先级：**
- 优先级 1：`welinkSessionId` → 直连路径（不发送 confirm）
- 优先级 2：`toolSessionId` → 反查数据库
  - 找到 → `sendRouteConfirm(toolSessionId, welinkSessionId)`
  - 未找到 → `sendRouteReject(toolSessionId)`

**指标（AtomicLong）：**
- `routeLocalCount`、`routeRelayCount`、`takeoverCount`、`takeoverConflictCount`、`ownerProbeDeadCount`

**与 0320 差异：**

| 维度 | 0320 | 0321 |
|------|------|------|
| 接管时机 | 主动（Agent 上线时） | 被动（消息到达时） |
| 接管范围 | ak 级别整体接管 | 按 sessionId 逐个接管 |
| 失联判定 | 配置超时 | Redis heartbeat key 不存在 |
| 冲突处理 | 分布式锁排序 | Lua CAS 乐观锁 |

---

### 特性 3：多级降级 GW 发现（GatewayDiscoveryService）

#### 涉及文件
- `skill-server/src/main/java/com/opencode/cui/skill/service/GatewayDiscoveryService.java`
- `skill-server/src/test/java/com/opencode/cui/skill/service/GatewayDiscoveryServiceTest.java`

#### 设计方案

**三级发现链：**

```
Level 1: HTTP discovery
  ├─ discoveryUrl 为空 → skip
  ├─ 连续失败 ≥ discoveryFailThreshold(3) → skip
  ├─ GET {discoveryUrl}（timeout 3000ms）
  └─ 成功 → 解析 {instances: [{instanceId, wsUrl}]}

Level 2: Redis scan（Level 1 失败时）
  ├─ 扫描 gw:instance:* key
  └─ 解析 JSON value 提取 wsUrl

Level 3: Keep existing（两级都失败时）
  └─ 保持现有连接不变，等待下一轮发现
```

**监听器模式：**
```java
interface Listener {
    void onGatewayAdded(String instanceId, String wsUrl);
    void onGatewayRemoved(String instanceId);
}
```

**变化通知：** 已知集合 vs 新发现集合 → 计算差集 → 通知监听者

**配置：**
```yaml
skill.gateway:
  discovery-url: http://localhost:8081/internal/instances
  discovery-timeout-ms: 3000
  discovery-fail-threshold: 3
  discovery-interval-ms: 10000
```

---

### 特性 4：SS 实例心跳注册（SkillInstanceRegistry）

#### 涉及文件
- `skill-server/src/main/java/com/opencode/cui/skill/service/SkillInstanceRegistry.java`
- `skill-server/src/test/java/com/opencode/cui/skill/service/SkillInstanceRegistryTest.java`

#### 设计方案

**Redis Key：** `ss:internal:instance:{instanceId}` → `"alive"`（TTL 30s）

**生命周期：**
```
@PostConstruct → Redis SET ... EX 30
@Scheduled(每 10s) → 刷新 TTL
@PreDestroy → Redis DEL
```

**失联探活：**
```
isInstanceAlive(targetInstanceId) → boolean
  存在（key 未过期） → true
  不存在（已过期） → false
  异常 → false（保守判断为离线）
```

**时间滑窗设计：**
- 刷新 10s，TTL 30s（3 倍余量），避免偶发网络抖动误判

---

### 特性 5：全连接网格 GatewayWSClient（v3）

#### 涉及文件
- `skill-server/src/main/java/com/opencode/cui/skill/ws/GatewayWSClient.java`
- `skill-server/src/test/java/com/opencode/cui/skill/ws/GatewayWSClientHashTest.java`
- `skill-server/src/test/java/com/opencode/cui/skill/ws/GatewayWSClientTest.java`

#### 设计方案

**架构升级：从单 GW 连接 → 全连接网格**

**连接管理数据结构：**
```java
gwConnections = ConcurrentHashMap<String, GwConnection>
  Key: gwInstanceId (e.g., "gw-1", "seed-10.0.1.5:8081")
  Value: GwConnection { gwInstanceId, wsUrl, WebSocketClient, AtomicInteger reconnectAttempts }

hashRing = ConsistentHashRing<GwConnection>()
  虚拟节点数：150（可配 skill.hash-ring.virtual-nodes）
  路由：hashKey(ak) → GwConnection
```

**种子连接提升（Seed Connection Promotion）：**
```
初始化：skill.gateway.ws-url → 启动 seed 连接 "seed-{host}:{port}"

Discovery 通知 onGatewayAdded(gwInstanceId, wsUrl)：
  ├─ 找到指向同一 URL 的 seed 连接?
  │  └─ seed 存活 → 仅重映射 key（保留 client），避免断开重连
  └─ 无 seed → 新建 gwInstanceId 连接
```

**发送路由（多级降级）：**
```
Level 1: sendViaHash(ak, message) — 一致性哈希选择 GW
Level 2: conn:ak 精确投递 — Redis 查询 Agent 所在 GW
Level 3: broadcastToAllGateways() — 广播到所有 GW
```

**重连逻辑（指数退避）：**
```
delay = min(initialDelay × 2^(attempts-1), maxDelay)
默认：初始 1s，最大 30s
成功后 reconnectAttempts 重置为 0
```

**与 0320 差异：**

| 维度 | 0320 | 0321 |
|------|------|------|
| 连接模式 | 单 GW 连接 | N:M 网格全连接 |
| 路由选择 | 随机或轮询 | 一致性哈希 + 降级链 |
| 发现方式 | 静态配置 | 动态 discovery + 监听者 |
| 重连策略 | 固定间隔 | 指数退避 |

---

### 特性 6：SS 侧一致性哈希环

#### 涉及文件
- `skill-server/src/main/java/com/opencode/cui/skill/service/ConsistentHashRing.java`
- `skill-server/src/test/java/com/opencode/cui/skill/service/ConsistentHashRingTest.java`

#### 设计方案

与 GW 侧实现完全一致（见 GW 特性 1），用于 GW 连接负载均衡。按 `welinkSessionId`（下行）或 `ak`（上行）哈希选择 GW 连接，确保同一会话消息路由到同一 GW 实例。

---

### 特性 7：上行路由中继（GatewayRelayService 增强）

#### 涉及文件
- `skill-server/src/main/java/com/opencode/cui/skill/service/GatewayRelayService.java`
- `skill-server/src/main/java/com/opencode/cui/skill/service/RedisMessageBroker.java`
- `skill-server/src/test/java/com/opencode/cui/skill/service/GatewayRelayServiceTest.java`

#### 设计方案

**新增职责：**
- AssistantId 动态注入（invoke 发送前调用 AssistantIdResolverService）
- 路由响应协议（`sendRouteConfirm` / `sendRouteReject`）

**RouteResponseSender 接口：**
```java
interface RouteResponseSender {
    void sendRouteConfirm(String toolSessionId, String welinkSessionId);
    void sendRouteReject(String toolSessionId);
}
```

**SS 间中继 Redis 频道：**
```
ss:relay:{targetInstanceId} — 跨实例消息转发
user-stream:{userId} — 前端广播
```

**关键 Redis Key：**
```
ss:internal:session:{welinkSessionId} → ownerInstanceId
ss:internal:instance:{instanceId} → "alive"
ss:internal:user-ws:{userId} (HASH) → {instanceId → count}
ss:stream-seq:{welinkSessionId} → 跨实例序号递增器
conn:ak:{ak} → gwInstanceId
```

---

### 特性 8：AssistantId 动态注入

#### 涉及文件
- `skill-server/src/main/java/com/opencode/cui/skill/service/AssistantIdResolverService.java`
- `skill-server/src/main/java/com/opencode/cui/skill/config/AssistantIdProperties.java`
- `skill-server/src/main/java/com/opencode/cui/skill/service/AssistantAccountResolverService.java`
- `skill-server/src/test/java/com/opencode/cui/skill/service/AssistantIdResolverServiceTest.java`

#### 设计方案

**三级缓存架构：**

```
L1 Caffeine（sessionId → assistantAccount）
  ├─ 容量：1000，TTL：5min
  └─ 占位符 __ABSENT__ 避免重复 DB 查询

L2 Caffeine（ak → toolType）
  ├─ 容量：500，TTL：5min
  └─ 调用 GatewayApiClient.getAgentByAk(ak)

L3 Redis（assistantAccount[:ak] → assistantId）
  ├─ Key：ss:assistant-id:{assistantAccount}[:ak]
  ├─ TTL：30min（可配置）
  └─ 未命中时调用 Persona API
```

**调用流程：**
```
resolve(ak, sessionId)
  ├─ 参数校验（enabled, sessionId, ak 非空）
  ├─ Step 1: getAssistantAccount(sessionId) — L1 缓存 → DB
  ├─ Step 2: isTargetToolType(ak) — L2 缓存 → Gateway API
  ├─ Step 3: Redis L3 缓存查询
  ├─ Step 4: fetchFromPersonaApi() — GET /persona-new?personaWelinkId=xxx
  └─ 任何异常 → 静默返回 null，不阻断消息发送
```

**注入时机：** `GatewayRelayService` 在 invoke 发送前自动注入 payload

**配置：**
```yaml
skill.assistant-id:
  enabled: true
  target-tool-type: assistant
  persona-base-url: ${SKILL_ASSISTANT_ID_PERSONA_BASE_URL:}
  cache-ttl-minutes: 30
  match-ak: false
```

---

### 特性 9：IM 入站消息处理增强

#### 涉及文件
- `skill-server/src/main/java/com/opencode/cui/skill/controller/ImInboundController.java`
- `skill-server/src/main/java/com/opencode/cui/skill/service/ImSessionManager.java`
- `skill-server/src/main/java/com/opencode/cui/skill/service/ImMessageService.java`
- `skill-server/src/main/java/com/opencode/cui/skill/service/ImOutboundService.java`
- `skill-server/src/main/java/com/opencode/cui/skill/model/ImMessageRequest.java`
- `skill-server/src/test/java/com/opencode/cui/skill/controller/ImInboundControllerTest.java`

#### 设计方案

**完整处理流程（6 步）：**

```
POST /api/inbound/messages

Step 1: 参数校验
  └─ 验证 businessDomain="im"、sessionType、content、assistantAccount

Step 2: 助手账号解析
  └─ AssistantAccountResolverService.resolve(assistantAccount) → (ak, ownerWelinkId)
  └─ Redis 两层缓存 + 远程解析接口 + Bearer Token 认证

Step 3: Agent 在线检查（开关控制）
  ├─ GatewayApiClient.getAgentByAk(ak) → 查询 Agent 元数据
  └─ 离线 → 发送"任务下发失败，请检查助理是否离线"提示

Step 4: 上下文注入（群聊场景）
  └─ ContextInjectionService.resolvePrompt()
  └─ 替换 {{chatHistory}} 和 {{currentMessage}}，保留最近 20 条

Step 5: 会话管理（三种情况）
  ├─ Session 不存在 → 异步创建 + Redis 分布式锁 + 缓存待发消息
  ├─ Session 缺少 toolSessionId → 请求 GW 重新创建
  └─ Session 就绪 → 直接转发

Step 6: 消息持久化与转发
  ├─ 单聊：结束上一轮 → 保存用户消息 → 标记待处理
  ├─ 群聊：不保存（AI Gateway 独立管理）
  └─ 构建 InvokeCommand 转发到 Gateway
```

**ImSessionManager 并发控制：**
```
createSessionAsync():
  lockKey = "skill:im-session:create:{domain}:{type}:{sessionId}:{ak}"
  Redis 分布式锁 TTL=15s
  获取成功 → 二次检查 + 创建/请求
  获取失败 → 其他实例正在创建，返回
```

---

### 特性 10：消息持久化与历史缓存

#### 涉及文件
- `skill-server/src/main/java/com/opencode/cui/skill/controller/SkillMessageController.java`
- `skill-server/src/main/java/com/opencode/cui/skill/service/SkillMessageService.java`
- `skill-server/src/main/java/com/opencode/cui/skill/service/MessagePersistenceService.java`
- `skill-server/src/main/java/com/opencode/cui/skill/service/MessageHistoryCacheService.java`
- `skill-server/src/main/java/com/opencode/cui/skill/config/AsyncConfig.java`
- `skill-server/src/main/java/com/opencode/cui/skill/model/MessageHistoryResult.java`
- `skill-server/src/test/java/com/opencode/cui/skill/service/SkillMessageServiceTest.java`

#### 设计方案

**两层消息存储模型：**
- `skill_message` 表 — 主消息记录（role, content, seq, meta）
- `skill_message_part` 表 — 消息分片（text, reasoning, tool, permission, file, step-finish 等）

**流式消息持久化分发：**
```
StreamMessage → persistIfFinal()
  ├─ TEXT_DONE → persistTextPart()
  ├─ THINKING_DONE → persistTextPart("reasoning")
  ├─ TOOL_UPDATE → persistToolPartIfFinal()
  ├─ QUESTION → persistToolPart()
  ├─ PERMISSION_ASK/REPLY → persistPermissionPart()
  ├─ FILE → persistFilePart()
  ├─ STEP_DONE → persistStepDone()（含 tokens 和 cost）
  └─ SESSION_STATUS(idle/completed) → handleSessionStatus()
```

**消息历史缓存（MessageHistoryCacheService）：**
```
Redis Key: skill:history:latest:{sessionId}:{size}
策略：
  - 预热大小（warm-sizes）：默认 50
  - TTL：30s
  - 消息保存后通过事务同步回调异步刷新
  - 线程池：core 2, max 4, queue 200（AsyncConfig）
```

**Agent 离线检查（SkillMessageController.sendMessage）：**
- 新增可配置开关
- 离线时返回错误提示而非静默丢弃

**权限自动推断：**
- 工具执行成功 → 自动推断为 "once" 权限
- 工具失败含 "rejected permission" → 推断为 "reject"

---

### 特性 11：会话服务增强

#### 涉及文件
- `skill-server/src/main/java/com/opencode/cui/skill/controller/SkillSessionController.java`
- `skill-server/src/main/java/com/opencode/cui/skill/service/SkillSessionService.java`
- `skill-server/src/main/java/com/opencode/cui/skill/model/SkillSession.java`
- `skill-server/src/test/java/com/opencode/cui/skill/service/SkillSessionServiceTest.java`
- `skill-server/src/test/java/com/opencode/cui/skill/controller/SkillSessionControllerTest.java`

#### 设计方案

**新增会话属性（IM 域支持）：**
- `businessSessionDomain` — 业务域（miniapp / im）
- `businessSessionType` — 会话类型（group / direct）
- `businessSessionId` — IM 平台会话 ID
- `assistantAccount` — 助手账号

**会话查询增强（Mapper）：**
- 支持按 businessSessionDomain、businessSessionType、assistantAccount 多维度筛选
- 异步 toolSessionId 创建：通过 Gateway 回调填充

**定时清理：**
- 每 10 分钟将超过 30 分钟无活动的 ACTIVE 会话标记为 IDLE
- MySQL SessionRoute 清理已移除，改用 Redis TTL 过期

---

### 特性 12：数据模型变更

#### 涉及文件
- `skill-server/src/main/java/com/opencode/cui/skill/model/SkillSession.java`
- `skill-server/src/main/java/com/opencode/cui/skill/model/SkillMessage.java`
- `skill-server/src/main/java/com/opencode/cui/skill/model/SkillMessagePart.java`
- `skill-server/src/main/java/com/opencode/cui/skill/model/InvokeCommand.java`
- `skill-server/src/main/java/com/opencode/cui/skill/model/StreamMessage.java`
- `skill-server/src/main/java/com/opencode/cui/skill/model/SessionRoute.java`（新增）
- `skill-server/src/main/java/com/opencode/cui/skill/model/MessageHistoryResult.java`（新增）
- `skill-server/src/main/java/com/opencode/cui/skill/model/ProtocolMessagePart.java`（新增）
- `skill-server/src/main/java/com/opencode/cui/skill/model/ProtocolMessageView.java`（新增）
- `skill-server/src/main/java/com/opencode/cui/skill/model/SkillMessageView.java`（新增）
- `skill-server/src/main/java/com/opencode/cui/skill/model/ImMessageRequest.java`（新增）

#### 关键变更

| 模型 | 变更内容 |
|------|---------|
| `SkillSession` | 新增 businessSessionDomain/Type/Id、assistantAccount 字段 |
| `InvokeCommand` | 支持 assistantId 注入 |
| `StreamMessage` | 新增消息类型 route_confirm、route_reject、agent_online、agent_offline |
| `SessionRoute` | 新增模型：ak、welinkSessionId、toolSessionId、sourceType、sourceInstance |
| `MessageHistoryResult` | 新增模型：items、total、hasMore |

---

### 特性 13：数据库变更

#### 涉及文件
- `skill-server/src/main/resources/db/migration/V7__skill_message_session_seq_unique.sql`
- `skill-server/src/main/resources/mapper/SessionRouteMapper.xml`（新增）
- `skill-server/src/main/resources/mapper/SkillSessionMapper.xml`
- `skill-server/src/main/resources/mapper/SkillMessageMapper.xml`
- `skill-server/src/main/resources/mapper/SkillMessagePartMapper.xml`

#### V7 迁移脚本
```sql
ALTER TABLE skill_message
  DROP INDEX idx_session_seq,
  ADD CONSTRAINT uk_skill_message_session_seq UNIQUE (session_id, seq);
```
将 seq 索引改为唯一约束，确保消息序列的严格唯一性。

#### Mapper 增强
- SkillSessionMapper 新增条件：businessSessionDomain、businessSessionType、assistantAccount
- SkillMessageMapper/PartMapper 同步增强查询条件

---

### 特性 14：SS 配置变更总览

#### 涉及文件
- `skill-server/src/main/resources/application.yml`

#### 新增配置项

```yaml
skill:
  instance-id: ${HOSTNAME:skill-server-local}

  instance-registry:
    refresh-interval-ms: 10000

  gateway:
    ws-url: ws://localhost:8081/ws/skill
    internal-token: changeme
    reconnect-initial-delay-ms: 1000
    reconnect-max-delay-ms: 30000
    discovery-url: http://localhost:8081/internal/instances
    discovery-interval-ms: 10000
    discovery-timeout-ms: 3000
    discovery-fail-threshold: 3

  relay:
    owner-dead-threshold-seconds: 120

  session:
    ownership-cache-ttl-seconds: 1800

  message-history:
    cache-ttl-seconds: 30
    warm-sizes: 50
    refresh:
      core-pool-size: 2
      max-pool-size: 4
      queue-capacity: 200

  assistant-id:
    enabled: true
    target-tool-type: assistant
    persona-base-url: ${SKILL_ASSISTANT_ID_PERSONA_BASE_URL:}
    cache-ttl-minutes: 30
    match-ak: false
```

---

## 第三部分：两服务共同变更

---

### 特性 15：统一日志体系

#### 涉及文件（AI Gateway）
- `ai-gateway/src/main/java/com/opencode/cui/gateway/logging/MdcConstants.java`
- `ai-gateway/src/main/java/com/opencode/cui/gateway/logging/MdcHelper.java`
- `ai-gateway/src/main/java/com/opencode/cui/gateway/logging/LogTimer.java`
- `ai-gateway/src/main/java/com/opencode/cui/gateway/logging/SensitiveDataMasker.java`
- `ai-gateway/src/main/java/com/opencode/cui/gateway/config/MdcRequestInterceptor.java`
- `ai-gateway/src/main/resources/logback-spring.xml`
- `ai-gateway/src/test/java/com/opencode/cui/gateway/logging/MdcConstantsTest.java`
- `ai-gateway/src/test/java/com/opencode/cui/gateway/logging/MdcHelperTest.java`
- `ai-gateway/src/test/java/com/opencode/cui/gateway/logging/LogTimerTest.java`

#### 涉及文件（Skill Server）
- `skill-server/src/main/java/com/opencode/cui/skill/logging/MdcConstants.java`
- `skill-server/src/main/java/com/opencode/cui/skill/logging/MdcHelper.java`
- `skill-server/src/main/java/com/opencode/cui/skill/logging/LogTimer.java`
- `skill-server/src/main/java/com/opencode/cui/skill/logging/SensitiveDataMasker.java`
- `skill-server/src/main/java/com/opencode/cui/skill/config/MdcRequestInterceptor.java`
- `skill-server/src/main/java/com/opencode/cui/skill/config/WebMvcConfig.java`
- `skill-server/src/main/resources/logback-spring.xml`
- `skill-server/src/test/java/com/opencode/cui/skill/logging/MdcConstantsTest.java`
- `skill-server/src/test/java/com/opencode/cui/skill/logging/MdcHelperTest.java`
- `skill-server/src/test/java/com/opencode/cui/skill/logging/LogTimerTest.java`

#### 设计方案

**MDC 字段定义（5 个，两服务一致）：**

| 字段 | 常量值 | 用途 | 示例 |
|------|--------|------|------|
| TRACE_ID | `"traceId"` | 跨服务请求追踪 | UUID |
| SESSION_ID | `"sessionId"` | 会话级关联 | welinkSessionId |
| AK | `"ak"` | Agent 级关联 | agent_001 |
| USER_ID | `"userId"` | 用户标识 | user_12345 |
| SCENARIO | `"scenario"` | 场景标识 | ws-agent-invoke |

**统一日志格式（logback-spring.xml）：**
```
%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] %-5level [${SERVICE_NAME}] [%X{traceId:-}] [%X{sessionId:-}] [%X{ak:-}] %logger{36}.%method - %msg%n
```

**日志滚动策略（两服务一致）：**
- 单文件最大：20MB
- 保留天数：30 天
- 总大小上限：2GB
- 归档格式：`.log.gz`（gzip 压缩）

**日志前缀约定：**
- `[ENTRY]` — 请求/消息处理入口
- `[EXIT]` — 请求/消息处理出口
- `[ERROR]` — 异常分支
- `[EXT_CALL]` — 外部 API 调用（含 durationMs）
- `[SKIP]` — 跳过处理

**MdcHelper 工具类方法：**

| 方法 | 功能 |
|------|------|
| `putTraceId/putSessionId/putAk/putUserId/putScenario` | 设置单个 MDC 字段 |
| `clearAll()` | 清理全部 MDC key（finally 块必须调用） |
| `ensureTraceId()` | 保证 traceId 存在，不存在则生成 UUID |
| `snapshot()` | 快照当前 MDC 值（跨线程传播用） |
| `restore(Map)` | 从快照恢复 MDC（新线程中调用） |
| **GW**: `fromGatewayMessage(GatewayMessage)` | 从 GatewayMessage 实例提取 4 个字段 |
| **SS**: `fromJsonNode(JsonNode)` | 从 JsonNode 提取 4 个字段 |

**LogTimer 工具类：**
```java
// 泛型计时（有返回值）
public static <T> T timed(Logger log, String operation, Supplier<T> action)
// 无返回值计时
public static void timedRun(Logger log, String operation, Runnable action)
```
- 成功：`[EXT_CALL] {operation} completed: durationMs={ms}`
- 失败：`[EXT_CALL] {operation} failed: durationMs={ms}, error={msg}`

**SensitiveDataMasker：**
- `maskMac("AA:BB:CC:DD:EE:FF")` → `"****:EE:FF"`
- `maskToken("abcdefghijklmnop")` → `"abcd****mnop"`

**MdcRequestInterceptor：**
- 拦截路径：`/api/**`
- preHandle：从 `X-Trace-Id` header 读取 traceId / 设置 scenario
- GW 版：不从 HTTP 提取 sessionId
- SS 版：从 URL 路径 `/api/skill/sessions/{sessionId}` 正则提取 sessionId
- afterCompletion：`MdcHelper.clearAll()`

**拦截器执行顺序（SS）：**
1. MdcRequestInterceptor（MDC 日志）
2. ImTokenAuthInterceptor（IM 认证）
→ MDC 先于认证，确保认证日志也带 traceId

**跨线程 MDC 传播：**
```java
// 原线程
Map<String, String> snap = MdcHelper.snapshot();
executorService.submit(() -> {
    MdcHelper.restore(snap);
    try { doWork(); }
    finally { MdcHelper.clearAll(); }
});
```

---

### 特性 16：Snowflake ID 配置外部化

#### 涉及文件
- `ai-gateway/src/main/java/com/opencode/cui/gateway/config/SnowflakeProperties.java`
- `ai-gateway/src/main/java/com/opencode/cui/gateway/service/SnowflakeIdGenerator.java`
- `skill-server/src/main/java/com/opencode/cui/skill/config/SnowflakeProperties.java`
- `skill-server/src/main/java/com/opencode/cui/skill/service/SnowflakeIdGenerator.java`

#### 设计方案

两服务新增 `SnowflakeProperties` 配置类，支持 `worker-id` / `datacenter-id` 通过环境变量或配置文件注入，避免多实例部署时 ID 冲突。

---

## 第四部分：架构演进总结

```
v2.5 (0320)                             v3 (route-redesign-0321)
──────────────                           ──────────────────────────
SS → 单 GW 连接                       →  SS → 全连接 GW 网格 (ConsistentHashRing)
MySQL session_route                    →  Redis ownership (Lua CAS + TTL)
Owner 固定不变                         →  失联探活 + 乐观锁自动接管
广播路由                               →  一致性哈希精准路由 + 上行学习表
Agent 离线消息丢弃                     →  gw:pending 缓冲队列 + 重连投递
本地 HMAC 验签                         →  双模式验签 + 三级缓存
设备绑定硬规则                         →  三开关灵活控制
无日志规范                             →  MDC 全链路追踪 + 统一格式
无 AssistantId                         →  三层缓存动态注入
findOnlineByAkId LIMIT 1              →  列表返回支持多 toolType
skill.instance-id 配置项              →  直接读 HOSTNAME 环境变量
```

**测试覆盖**：共新增 42 个测试文件，覆盖一致性哈希、路由决策、ownership 接管、GW 发现、AssistantId 解析等核心路径。

---

## 第五部分：后续修正

---

### 修正 1：移除 `skill.instance-id` 配置项，直接使用 HOSTNAME

#### 涉及文件
- `skill-server/src/main/java/com/opencode/cui/skill/service/SessionRouteService.java`
- `skill-server/src/main/java/com/opencode/cui/skill/service/SkillInstanceRegistry.java`
- `skill-server/src/main/java/com/opencode/cui/skill/ws/GatewayWSClient.java`

#### 变更说明

**问题**：三处 `@Value` 注入使用 `${skill.instance-id:${HOSTNAME:skill-server-local}}`，允许通过配置中心覆盖 `skill.instance-id`。若在共享配置（如 Nacos）中设置该值，所有 SS 实例将共用同一个 instanceId，导致：
- 会话 ownership 判断错乱（所有实例都认为自己是 owner）
- CAS takeover 逻辑失效
- 心跳探活失去意义

**修正**：删除 `skill.instance-id` 配置层，三处统一改为 `@Value("${HOSTNAME:skill-server-local}")`，直接读取操作系统 `HOSTNAME` 环境变量（容器环境下每个 Pod 天然唯一），本地开发兜底为 `skill-server-local`。

---

### 修正 2：SessionRebuildService 重建重试保护（防无限循环）

#### 涉及文件
- `skill-server/src/main/java/com/opencode/cui/skill/service/SessionRebuildService.java`
- `skill-server/src/main/resources/application.yml`
- `skill-server/src/test/java/com/opencode/cui/skill/service/SessionRebuildServiceTest.java`

#### 变更说明

**问题**：当 OpenCode Agent 遇到 LLM API 不可达（"Unable to connect"）时，新创建的 session 可能立即失效，后续 CHAT 调用返回 "Session not found" / 404，触发 `handleToolError()` → `rebuildService.handleSessionNotFound()` → 清空 `toolSessionId` → 重发 `create_session`，形成无限循环。`rebuildFromStoredUserMessage()` 每次从 DB 查询最后用户消息，循环永远有燃料。

**修正**：在 `rebuildToolSession()` 入口新增 per-session 重建计数器（Caffeine 缓存，`expireAfterAccess`），超过上限后停止重建并通知用户，冷却期过后自动恢复。

**关键设计决策**：
- **计数器检查位置**：放在 `rebuildToolSession()` 而非 `handleSessionNotFound()`，因为系统有两条路径触发重建（tool_error 驱动 + IM 消息驱动），`rebuildToolSession()` 是两条路径的汇合点，统一拦截。
- **线程安全**：使用 `cache.get(key, mappingFunction)` 保证原子创建，`AtomicInteger.incrementAndGet()` 保证并发安全。
- **拦截时清空 stale toolSessionId**：避免冷却过期后 IM 消息用旧 ID 直接发 CHAT 失败。
- **`expireAfterAccess`**：每次访问计数器刷新 TTL，冷却从最后一次重建尝试计时。

**新增配置**：
```yaml
skill:
  session:
    rebuild-max-attempts: ${SKILL_SESSION_REBUILD_MAX_ATTEMPTS:3}     # 最大重建次数
    rebuild-cooldown-seconds: ${SKILL_SESSION_REBUILD_COOLDOWN_SECONDS:30}  # 冷却时间（秒）
```

**测试覆盖**：4 个单元测试 — 正常重建、超限拦截、不同 session 独立计数、拦截后清理 pending 消息。

**设计文档**：`docs/superpowers/specs/2026-03-26-rebuild-retry-guard-design.md`

---

### 修正 3：消除 Redis SCAN/KEYS 全局整改

#### 涉及文件

**SS 侧：**
- `skill-server/src/main/java/com/opencode/cui/skill/service/RedisMessageBroker.java`
- `skill-server/src/main/java/com/opencode/cui/skill/ws/SkillStreamHandler.java`
- `skill-server/src/main/java/com/opencode/cui/skill/service/GatewayDiscoveryService.java`
- `skill-server/src/test/java/com/opencode/cui/skill/ws/UserWsRegistryTest.java`（删除）
- `skill-server/src/test/java/com/opencode/cui/skill/service/GatewayDiscoveryServiceTest.java`
- `skill-server/src/test/java/com/opencode/cui/skill/ws/SkillStreamHandlerTest.java`

**GW 侧：**
- `ai-gateway/src/main/java/com/opencode/cui/gateway/service/GatewayInstanceRegistry.java`
- `ai-gateway/src/main/java/com/opencode/cui/gateway/controller/InternalInstanceController.java`
- `ai-gateway/src/main/java/com/opencode/cui/gateway/service/RedisMessageBroker.java`（注释更新）
- `ai-gateway/src/test/java/com/opencode/cui/gateway/service/GatewayInstanceRegistryTest.java`
- `ai-gateway/src/test/java/com/opencode/cui/gateway/controller/InternalInstanceControllerTest.java`

#### 变更说明

**问题**：`RedisMessageBroker.cleanupUserWsForInstance` 使用 Redis SCAN 遍历 `ss:internal:user-ws:*`，在其他人环境启动时 `cursor.hasNext()` 无限循环。根因是 Lettuce 连接池在多轮 SCAN 迭代时切换连接，导致游标丢失、从头重扫。

**深层问题（调查中逐步发现）**：

1. **user-ws 注册表是死代码** — `getUserWsInstances()` 无任何调用者，为未实现的 gRPC Mesh 预埋，当前只有写入成本（每次 WS 连接 HINCRBY），无读取收益
2. **instanceId 是临时的** — 发版时 instanceId 变化，启动清理只能清理新 ID，旧 ID 残留永远清理不到
3. **`scanGatewayInstances` 是生产死代码** — SS 和 GW 在生产环境不共 Redis，SCAN 永远扫不到 GW 的 key
4. **`gw:instance:{id}` legacy key 无真实消费者** — 旧版 SS（0320）通过配置 URL 直连 GW，从未有 Redis 扫描逻辑，"兼容旧版"是错误假设
5. **GW `InternalInstanceController` 使用 KEYS** — 原则上全面禁用 KEYS/SCAN

#### 具体改动

**SS 侧 — 删除 user-ws 注册表（RedisMessageBroker）：**

删除 5 个方法和 2 个常量：
- `USER_WS_KEY_PREFIX`、`GW_INSTANCE_KEY_PREFIX`
- `registerUserWs()`、`unregisterUserWs()`、`getUserWsInstances()`
- `cleanupUserWsForInstance()`、`scanGatewayInstances()`

当前功能不受影响：跨实例消息投递靠 Redis pub/sub（`user-stream:{userId}`），会话级路由靠 `SessionRouteService`，本地 WS 连接管理靠 `SkillStreamHandler.userSubscribers` 内存 Map。

**SS 侧 — 删除 user-ws 调用点（SkillStreamHandler）：**

- 删除 `@PostConstruct cleanupStaleWsEntries()` 方法
- 从 `registerUserSubscriber()` / `unregisterSubscriber()` 中移除 `registerUserWs` / `unregisterUserWs` 调用
- 移除 `SkillInstanceRegistry` 依赖（构造函数参数、字段），该类在 Handler 中不再有使用场景

**SS 侧 — GatewayDiscoveryService 重写：**

- 删除 `tryScanRedis()` 和 `extractWsUrl()`（Redis SCAN 降级路径），删除 `RedisMessageBroker` 依赖
- 新增 HTTP 发现结果缓存：不可变 `CachedDiscoveryResult` 内部类 + `AtomicReference` 持有，TTL 可配置（默认 30s，`skill.gateway.discovery-cache-ttl-seconds`）
- 改后 discovery chain：`本地缓存 → HTTP → 保持现有连接`

延迟预期：GW 实例变化最长可能延迟 `discoveryCacheTtlSeconds`（30s）才被 SS 感知。加上 GW 侧 `staleThresholdSeconds`（60s），一个宕机 GW 最长约 90s 后从发现结果中消失。

**GW 侧 — 实例注册改为单 HASH（GatewayInstanceRegistry）：**

改前（双写两套独立 key）：
```
SET gw:instance:{id}           value TTL=30s   // legacy，无消费者
SET gw:internal:instance:{id}  value TTL=30s   // 被 KEYS 扫描
```

改后（单 HASH，单次写入）：
```
HSET gw:internal:instances  {instanceId}  {json含lastHeartbeat时间戳}
```

- 注册/心跳：`HSET` 覆盖自己的 field（更新 `lastHeartbeat`）
- 注销：`HDEL` 移除自己的 field
- HASH 无整体 TTL，靠 `lastHeartbeat` 时间戳 + 读端惰性清理

**GW 侧 — InternalInstanceController 改用 HGETALL：**

改前：`KEYS gw:internal:instance:*` → 逐个 `GET`
改后：`HGETALL gw:internal:instances` → 解析每个 field → 检查 `lastHeartbeat` 新鲜度（< 2×TTL = 60s，可配置 `gateway.instance-registry.stale-threshold-seconds`）→ 过期条目惰性 `HDEL`

`lastHeartbeat` 为 null 时也视为过期（防御 fallback JSON 无此字段的场景）。

#### Redis 操作变化

| 操作 | 改前 | 改后 |
|------|------|------|
| SS 启动清理 | SCAN `ss:internal:user-ws:*` | **删除**（不需要） |
| SS 用户 WS 注册 | HINCRBY + HDEL | **删除**（不需要） |
| SS GW 发现降级 | SCAN `gw:instance:*` | **删除**（生产死代码） |
| GW 实例注册 | 双写 2 个独立 key + TTL | `HSET` 单 HASH |
| GW 实例发现接口 | `KEYS gw:internal:instance:*` | `HGETALL gw:internal:instances` + lastHeartbeat 过滤 |

#### Redis Key 变化

| Key | 改前 | 改后 |
|-----|------|------|
| `ss:internal:user-ws:{userId}` | 存在（写了没人读） | **删除** |
| `gw:instance:{id}` | 存在（无消费者） | **删除** |
| `gw:internal:instance:{id}` | 存在（被 KEYS 扫描） | **删除** |
| `gw:internal:instances` | 不存在 | **新增**（HASH，聚合所有 GW 实例） |

#### 宕机/发版场景处理

| 场景 | 处理方式 |
|------|---------|
| GW 正常运行 | 每 10s `HSET` 刷新 `lastHeartbeat` |
| GW 优雅关闭 | `@PreDestroy` 主动 `HDEL` |
| GW 宕机 | `lastHeartbeat` 停止更新，下次 `InternalInstanceController` 被调用时发现过期 → 惰性 `HDEL` |
| GW 发版（instanceId 变） | 旧 field 心跳过期 → 惰性清理；新 field 正常注册 |

#### 测试覆盖

- `UserWsRegistryTest` — **删除**（被测方法全部删除）
- `GatewayDiscoveryServiceTest` — **重写**（7 个测试：HTTP 成功/失败/保持/阈值跳过、缓存命中/过期、实例消失通知）
- `SkillStreamHandlerTest` — **更新**（移除 `SkillInstanceRegistry` 构造参数）
- `GatewayInstanceRegistryTest` — **重写**（5 个测试：HASH 注册/心跳/注销/ID 返回/JSON 字段）
- `InternalInstanceControllerTest` — **重写**（4 个测试：存活实例返回/空 HASH/过期惰性清理/格式错误跳过）
