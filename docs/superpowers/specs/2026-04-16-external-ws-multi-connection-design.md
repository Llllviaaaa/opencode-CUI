# External WS 多连接支持

> 日期: 2026-04-16
> 状态: Draft

## 问题

当前 `ExternalStreamHandler.connectionPool` 结构为 `source -> { instanceId -> WebSocketSession }`，同一个外部 pod（相同 `source + instanceId`）在同一个 SS 实例上只能保留一条 WS 连接。重连时会踢掉旧连接（第 87-101 行）。

这导致外部 pod 无法通过建立多条 WS 连接到多个（或同一个）SS 实例来实现高可用冗余。

## 目标

- 允许同一个外部 pod（相同 `source + instanceId`）在同一个 SS 实例上建立多条 WS 连接
- 多条连接可以分布在同一个或不同的 SS 实例上
- 出站推送时从可用连接中**任选一条**发送
- 发送失败自动从池中移除并重试下一条
- 对外部 pod 透明：握手协议不变，SS 侧用 `WebSocketSession.getId()` 自动区分

## 非目标

- 不做连接数上限限制
- 不做广播（所有连接都发）
- 不做会话亲和性（不同 session 绑定不同连接）
- 不改变 L2 跨实例路由策略
- 不改变握手认证协议

## 设计

### 1. 新增 ConnectionPool 内部类

在 `ExternalStreamHandler` 中新增 `ConnectionPool` 静态内部类，封装多连接管理逻辑。

**数据结构**:

```
source -> { instanceId -> { wsSessionId -> WebSocketSession } }
```

**对外 API**:

```java
static class ConnectionPool {
    /** 添加连接 */
    void add(String source, String instanceId, WebSocketSession session);

    /** 移除指定连接，返回该 source 剩余连接数 */
    int remove(String source, String instanceId, WebSocketSession session);

    /** 移除指定连接（通过 wsSessionId），返回该 source 剩余连接数 */
    int removeBySessionId(String source, String instanceId, String wsSessionId);

    /** 从指定 source 的所有连接中任选一条打开的。无可用连接返回 null */
    WebSocketSession pickOne(String source);

    /** 获取指定 source 的总连接数 */
    int countBySource(String source);

    /** 指定 source 是否有活跃连接 */
    boolean hasActiveConnections(String source);

    /** 获取所有 source */
    Set<String> sources();

    /** 遍历指定 source 下所有 session（用于心跳检查） */
    void forEach(String source, BiConsumer<String, WebSocketSession> action);
    // 参数: compositeKey ("instanceId:wsSessionId"), session

    /** 遍历所有 source 下所有 session */
    void forEachAll(Consumer<WebSocketSession> action);
}
```

### 2. ExternalStreamHandler 改动

#### 2.1 连接池字段替换

```java
// 旧
private final Map<String, Map<String, WebSocketSession>> connectionPool = new ConcurrentHashMap<>();

// 新
private final ConnectionPool connectionPool = new ConnectionPool();
```

#### 2.2 afterConnectionEstablished

- **移除踢旧连接逻辑**。同一 `source + instanceId` 的多条连接并存。
- 调用 `connectionPool.add(source, instanceId, session)`
- 调用 `wsRegistry.register(source, connectionPool.countBySource(source))`

#### 2.3 afterConnectionClosed

- 调用 `connectionPool.remove(source, instanceId, session)`
- 如果 `countBySource(source) == 0`：取消 Redis channel 订阅，调用 `wsRegistry.unregister(source)`
- 否则：调用 `wsRegistry.register(source, connectionPool.countBySource(source))`

#### 2.4 pushToOne — 发送失败重试

```java
public boolean pushToOne(String source, String message) {
    TextMessage textMessage = new TextMessage(message);
    int maxRetries = 3;
    for (int i = 0; i < maxRetries; i++) {
        WebSocketSession session = connectionPool.pickOne(source);
        if (session == null) return false;
        try {
            synchronized (session) { session.sendMessage(textMessage); }
            return true;
        } catch (Exception e) {
            log.warn("pushToOne failed, removing session and retrying: source={}, sessionId={}, attempt={}/{}",
                    source, session.getId(), i + 1, maxRetries);
            connectionPool.removeBySessionId(source,
                    (String) session.getAttributes().get(INSTANCE_ID_ATTR),
                    session.getId());
        }
    }
    return false;
}
```

#### 2.5 pushToSource（广播）

遍历方式适配新结构：`connectionPool.forEach(source, (key, session) -> ...)`

#### 2.6 hasActiveConnections

委托给 `connectionPool.hasActiveConnections(source)`。

#### 2.7 checkHeartbeatTimeouts

遍历方式适配：`connectionPool.forEachAll(session -> ...)`

心跳续期逻辑不变：`connectionPool.sources()` 遍历所有 source 调用 `wsRegistry.heartbeat(source)`。

### 3. ExternalWsRegistry — 无改动

`register(domain, connectionCount)` 接口不变，只是传入的 `connectionCount` 现在反映多连接总数。`findInstanceWithConnection`、`unregister`、`heartbeat` 均无需改动。

### 4. ExternalWsDeliveryStrategy — 无改动

L1/L2/L3 投递逻辑完全不变。`pushToOne` 的行为变化（多连接 + 重试）对调用方透明。

### 5. 握手协议 — 无改动

外部 pod 仍然传 `{ token, source, instanceId }`，SS 侧用 `WebSocketSession.getId()` 自动区分。

## 影响范围

| 文件 | 改动类型 |
|------|----------|
| `ExternalStreamHandler.java` | 新增 `ConnectionPool` 内部类，重构连接池字段和相关方法 |
| `ExternalWsRegistry.java` | 无改动 |
| `ExternalWsDeliveryStrategy.java` | 无改动 |
| 握手协议 | 无改动 |
| Redis 注册表结构 | 无改动 |

## 风险与注意

1. **并发安全**: `ConnectionPool` 内部使用 `ConcurrentHashMap` 嵌套，`add/remove` 操作需注意原子性。`pickOne` 从 values 中取第一个 open 的 session，无需强一致性。
2. **pushToOne 重试上限**: 固定 3 次。极端情况下 3 条连接全部不可用，返回 false 走 L2 relay 或 L3 降级，行为与现在一致。
3. **内存**: 每多一条连接多一个 `WebSocketSession` 引用和一个 `lastActivity` 条目，开销可忽略。
