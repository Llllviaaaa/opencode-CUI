# Part 批量持久化优化设计

> 日期: 2026-04-16

## 背景

当前 `MessagePersistenceService` 在流式消息到达时，每个 part 逐条写入 MySQL（`partRepository.upsert()`）。每次写入实际涉及 2 次 DB 往返：

1. `resolvePartSeq()` → `findByPartId()` 或 `findMaxSeqByMessageId()` — 1 次查询
2. `partRepository.upsert()` — 1 次写入

N 个 part = 2N 次 DB 往返，造成写入性能瓶颈。

## 目标

将 N 个 part 的写入从 **2N 次 MySQL 往返** 优化为 **N 次 Redis 写入 + 1 次 MySQL 批量写入**。

## 设计

### 核心思路

Part 到达时暂存 Redis，`tool_done`（消息结束）时批量刷入 MySQL。

### 写入流程

```
part 到达 → persistIfFinal()
  → 构建 SkillMessagePart 对象
  → seq 由 Redis INCR 分配（key: ss:part-seq:{messageDbId}）
  → 序列化为 JSON，RPUSH 到 Redis list（key: ss:part-buf:{messageDbId}）
  → INCR 和 RPUSH 通过 Redis pipeline 合并为 1 次往返
```

### 刷盘流程

触发点：`handleSessionStatus(idle/completed)`（由 `tool_done` / `handleToolDone` 生成）

```
handleSessionStatus(idle/completed)
  → flushPartBuffer(messageDbId)
    → LRANGE ss:part-buf:{messageDbId} 0 -1    // 读取所有缓冲 part
    → 反序列化为 List<SkillMessagePart>
    → partRepository.batchUpsert(parts)          // 1 次 MySQL 批量写入
    → 累积 step-done 的 tokens/cost，调用 messageService.updateMessageStats()
    → DEL ss:part-buf:{messageDbId}              // 清理缓冲
    → DEL ss:part-seq:{messageDbId}              // 清理 seq 计数器
  → syncAllPendingContent()                       // 从 DB 读 text part 拼接回写主消息
  → tracker.removeAndFinalize()                   // markMessageFinished
```

### 刷盘触发点

刷盘**仅由 `tool_done`**（即 `handleSessionStatus(idle/completed)`）触发。

每条消息有独立的 `messageDbId`，对应独立的 Redis key。消息之间互不影响，无需在 messageId 切换时做特殊处理。未刷盘的消息由 Redis key TTL（1h）兜底清理。

### Permission 查询兼容

`synthesizePermissionReplyFromToolOutcome()` 在消息流式过程中被调用（`GatewayMessageRouter.handleAssistantToolEvent`），需要查询 pending permission part。

改为先查 Redis 缓冲，降级查 DB：

```java
// 1. 从 Redis list 中反向扫描找 permission.ask 且 status != completed 的 part
SkillMessagePart pending = partBufferService.findLatestPendingPermission(sessionId, messageDbId);
// 2. 降级查 DB（兼容 takeover 后已刷盘的场景、兼容历史数据）
if (pending == null) {
    pending = partRepository.findLatestPendingPermissionPart(sessionId);
}
```

### Takeover 安全

- 数据暂存在 Redis（非 JVM 内存），实例挂掉后数据不丢失
- 新实例接管 session 后：
  - 未结束的消息：新 part 继续 RPUSH，`tool_done` 时统一刷盘
  - 已结束但未刷盘的消息：新实例在 `handleSessionStatus` 中正常 flush

### Redis Key 设计

| Key | 类型 | 用途 | TTL |
|-----|------|------|-----|
| `ss:part-buf:{messageDbId}` | LIST | 缓冲序列化的 part JSON | 1h（兜底清理，正常由 flush 删除） |
| `ss:part-seq:{messageDbId}` | STRING (counter) | 原子递增的 seq 计数器 | 1h（同上） |

### 性能对比

| | 当前 | 优化后 |
|--|------|--------|
| 每个 part | 1 次 DB 查询 + 1 次 DB 写入 | 1 次 Redis pipeline（RPUSH + INCR） |
| 消息结束 | syncContent + markFinished | 1 次 batch upsert + syncContent + markFinished |
| N 个 part 总计 | **2N 次 MySQL 往返** | **N 次 Redis 往返 + 1 次 MySQL 往返** |

## 改动范围

### 新增

1. **`PartBufferService`** — 封装 Redis 缓冲操作
   - `bufferPart(Long messageDbId, SkillMessagePart part)` — RPUSH + pipeline
   - `nextSeq(Long messageDbId)` — INCR
   - `flushParts(Long messageDbId)` — LRANGE + 反序列化 + DEL
   - `findLatestPendingPermission(Long sessionId, Long messageDbId)` — 从 Redis list 扫描

2. **`batchUpsert` 方法** — `SkillMessagePartRepository` 接口 + `SkillMessagePartMapper.xml`
   - 使用 MyBatis `<foreach>` 构建批量 INSERT ... ON DUPLICATE KEY UPDATE

### 修改

3. **`MessagePersistenceService`**
   - `persistTextPart/persistToolPart/persistPermissionPart/persistFilePart/persistStepDone` — 改为调用 `partBufferService.bufferPart()` 替代 `partRepository.upsert()`
   - `resolvePartSeq` — 改为调用 `partBufferService.nextSeq()` 替代 DB 查询
   - `handleSessionStatus` — 在 `syncAllPendingContent` 前插入 `flushPartBuffer()` 调用
   - 新增 `flushPartBuffer(Long messageDbId)` — 读取 Redis 缓冲 → batchUpsert → updateMessageStats → 清理 Redis
   - `synthesizePermissionReplyFromToolOutcome` — 先查 Redis 再查 DB

4. **`ActiveMessageTracker`** — 不改动，`finalizeActiveMessage` / `finalizeActiveAssistantTurn` 保持原有防御性清理逻辑

### 不改动

- 推送链路（`outboundDeliveryDispatcher`、WebSocket）
- `GatewayMessageRouter` 路由逻辑
- `ActiveMessageTracker` 核心追踪逻辑
- 前端
