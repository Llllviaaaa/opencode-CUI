# SessionRebuildService 重建重试保护

## 问题

当 OpenCode Agent 遇到 LLM API 不可达（"Unable to connect"）时，新创建的 session 可能立即失效。
后续 CHAT 调用返回 "Session not found" / 404，触发 `handleToolError()` → `rebuildService.handleSessionNotFound()`，
清空 `toolSessionId` 并重新发送 `create_session`，形成无限循环：

```
create_session → session_created → CHAT → 404 tool_error
→ handleSessionNotFound → clearToolSessionId → create_session → ...
```

`rebuildFromStoredUserMessage()` 每次从 DB 查询最后用户消息作为 pending message，
循环永远有燃料，没有任何终止条件。

## 根因

`SessionRebuildService` 的所有重建路径（`handleSessionNotFound`、`handleContextOverflow`、`rebuildToolSession`）
没有重试次数限制。

## 设计

### 改动范围

| 文件 | 改动 |
|------|------|
| `SessionRebuildService.java` | 新增重建计数器 + `rebuildToolSession()` 入口检查 |
| `application.yml` | 新增两个配置项 |

不改动：`GatewayMessageRouter`、`ImInboundController`、`BridgeRuntime`。

### 新增配置

```yaml
skill:
  session:
    rebuild-max-attempts: ${SKILL_SESSION_REBUILD_MAX_ATTEMPTS:3}
    rebuild-cooldown-seconds: ${SKILL_SESSION_REBUILD_COOLDOWN_SECONDS:30}
```

- `rebuild-max-attempts`：单个 session 在冷却窗口内允许的最大重建次数，默认 3。
- `rebuild-cooldown-seconds`：冷却时间（秒），默认 30。计数器到期后自动清除，恢复正常重建能力。

### SessionRebuildService 改动

#### 新增成员

```java
/** 重建计数器：sessionId → 已重建次数，过期时间 = cooldownSeconds */
private final Cache<String, AtomicInteger> rebuildAttemptCounters;
private final int maxAttempts;
private final int cooldownSeconds;
```

`rebuildAttemptCounters` 使用 Caffeine 缓存，`expireAfterAccess = cooldownSeconds`，`maximumSize = 1000`。

选择 `expireAfterAccess`（而非 `expireAfterWrite`）：每次访问计数器都会刷新 TTL，
避免在多次重建间隔较长时计数器提前过期导致保护失效。
冷却倒计时从**最后一次重建尝试**开始，而非首次。

#### 计数器检查位置：`rebuildToolSession()`

将计数器检查下沉到 `rebuildToolSession()` 入口，而非 `handleSessionNotFound()` / `handleContextOverflow()`。

原因：系统有两条路径触发重建：
1. `handleToolError()` → `handleSessionNotFound()` → `rebuildFromStoredUserMessage()` → `rebuildToolSession()`
2. `ImInboundController` → `requestToolSession()` → `rebuildToolSession()`

如果只在路径 1 检查，路径 2（IM 消息驱动）会绕过保护。将检查放在两条路径的汇合点
`rebuildToolSession()`，统一拦截。

`handleSessionNotFound` 和 `handleContextOverflow` 共享同一个计数器预算。
两种错误类型都表明 session 出了问题，共享预算是合理的。

#### rebuildToolSession() 伪代码

```
rebuildToolSession(sessionId, session, pendingMessage, callback):
  // --- 计数器检查（新增）---
  counter = rebuildAttemptCounters.get(sessionId, k -> new AtomicInteger(0))
  attempts = counter.incrementAndGet()

  if attempts > maxAttempts:
    log.warn("Rebuild exhausted: session={}, attempts={}, cooldownSeconds={}", ...)
    pendingRebuildMessages.invalidate(sessionId)
    sessionService.clearToolSessionId(parseSessionId(sessionId))  // 清空 stale toolSessionId
    callback.broadcast(sessionId, session.getUserId(),
        StreamMessage.error("会话连接异常（重建已达上限），请等待 " + cooldownSeconds + " 秒后重试"))
    return

  log.info("Rebuild attempt {}/{} for session={}", attempts, maxAttempts, sessionId)

  // --- 原有逻辑（不变）---
  if pendingMessage != null:
    pendingRebuildMessages.put(sessionId, pendingMessage)
  callback.broadcast(sessionId, session.getUserId(), StreamMessage.sessionStatus("retry"))
  // ... 构建 payload，发送 create_session invoke ...
```

使用 `cache.get(key, mappingFunction)` 保证原子创建，`incrementAndGet()` 保证线程安全。
"先递增后检查"模式避免两个线程同时通过检查的竞态。

#### 拦截时清空 toolSessionId

当计数器拦截重建时，主动调用 `sessionService.clearToolSessionId()`。

原因：如果不清空，session 保留旧的（已失效的）`toolSessionId`。冷却过期后新 IM 消息到达，
`ImInboundController` 看到 `toolSessionId != null` → 直接走 Path C 发 CHAT →
用旧 ID 必定失败。清空后，冷却过期后的 IM 消息走 Path B（`requestToolSession`），
触发正常重建流程。

#### 不改动的方法

- `handleSessionNotFound()` / `handleContextOverflow()`：不变，仍调用 `rebuildFromStoredUserMessage()`，
  计数器逻辑已在下游 `rebuildToolSession()` 中。
- `consumePendingMessage()` / `clearPendingMessage()`：不变。

### 恢复机制

计数器存在 Caffeine 缓存中，TTL = `rebuild-cooldown-seconds`（默认 30 秒），使用 `expireAfterAccess`。
最后一次重建尝试后 30 秒无新访问 → 计数器自然过期 → 下一次 IM 消息触发正常重建。
不需要手动重置逻辑。

### 错误消息

```
会话连接异常（重建已达上限），请等待 30 秒后重试
```

其中秒数从配置 `rebuild-cooldown-seconds` 读取。
通过 `RebuildCallback.broadcast()` 发送，复用现有的错误广播通道。
对于 IM 会话，该消息经 `handleImAssistantMessage` 转发到 IM；
对于 MiniApp 会话，广播到前端 WebSocket。

### 日志

- 每次重建计数 +1 时：`INFO "Rebuild attempt {}/{} for session={}"`
- 达到上限时：`WARN "Rebuild exhausted: session={}, attempts={}, cooldownSeconds={}"`

## 测试策略

- 单元测试：验证计数器递增、达上限后拦截、`expireAfterAccess` 过期后重置、
  并发调用时计数器线程安全
- 手动测试：模拟 Agent 不可达场景，确认重建在 N 次后停止，冷却后恢复
