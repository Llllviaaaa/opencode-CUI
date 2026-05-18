# 流式事件乱序+重复问题清单

> 初次分析：2026-05-15（基于 commit `c7049b8`）
> 分析者：Claude Opus 4.7 (1M) + GPT-5.5 xhigh 双线交叉验证
> 项目：opencode-CUI
> 症状：GW 多实例 + SS 多实例下，miniapp 和 externalws 的流式事件出现乱序+重复

> **2026-05-15 复查更新**：拉取最新 main（合入 PR #30 `15ce232`）后逐条复查。
> #30 仅修复了根因 #5（externalws L2 stale pod routing）。其余 6 条原样存在。
> 详见文末 §"#30 合入后复查结果"。

---

## 故障现象

- **乱序**：服务端发 D1="今天" → D2="天气" → D3="真" → D4="好"，用户屏幕看到 "真天气好今天" 之类的错位；或者文字在屏幕上"跳动"
- **重复**：服务端发 1 条 "我爱你"，用户看到 "我爱你我爱你"；或 tool 卡片状态在"运行中"和"已完成"之间反复横跳

---

## 根因清单（按"杀伤力 × 频率"排序）

### #1 编号机制没拼起来：服务端晚贴号 + 客户端不看号

**杀伤力：极高｜频率：每条消息**

#### 服务端
- `skill-server/src/main/java/com/opencode/cui/skill/service/delivery/MiniappDeliveryStrategy.java:42`
  - producer publish 时**不带 seq**
- `skill-server/src/main/java/com/opencode/cui/skill/ws/SkillStreamHandler.java:228, 261, 270`
  - consumer 收到 Redis 消息后才调 `nextTransportSeq` 贴号
  - 多 SS 实例下，同一逻辑消息在两个实例各自贴出不同的号

#### 客户端
- `skill-miniapp/src/hooks/useSkillStream.ts:761-942`
  - 整个 `handleStreamMessage` 函数搜不到任何 `msg.seq` 的引用
  - `sortMessages`（line 98-109）排序用的是 `messageSeq`（业务级），不是传输层 `seq`
- `skill-miniapp/src/protocol/StreamAssembler.ts:127-135`
  - text.delta 处理：`part.content += msg.content` —— 按到达顺序裸拼接

#### 对照
- `ExternalWsDeliveryStrategy.java:51` producer 端贴号是对的，但客户端（外部业务系统）也未必按 seq 排

---

### #2 Spring Redis 监听器是多线程并发派发

**杀伤力：极高｜频率：消息密度高时必中**

- `skill-server/src/main/java/com/opencode/cui/skill/config/RedisConfig.java:48-55`
  ```java
  ThreadPoolTaskExecutor executor (core=5, max=50, queue=200)
  container.setTaskExecutor(executor)
  ```
  - 同 channel 连续到达的消息被丢给 5~50 个 worker 并发处理
- `synchronized(session)` 只保证不交错写，不保证抢锁顺序
- 单 SS 单设备也会乱序

---

### #3 半死自愈后，user-stream 订阅可能没回来（codex 修正）

**杀伤力：高｜频率：网络抖动 / Redis 长连接重置**

- `skill-server/src/main/java/com/opencode/cui/skill/service/RedisMessageBroker.java:268-281`（#30 后行号 +6）
  - `forceReconnectListenerContainer(verifyChannel, timeoutMs)` 只校验**传入的那一个 channel**
- `skill-server/src/main/java/com/opencode/cui/skill/service/SkillInstanceRegistry.java:147-148`
  - 实际调用：`forceReconnectListenerContainer(SS_RELAY_CHANNEL_PREFIX + instanceId, …)`
  - 只 verify `ss:relay:{instanceId}`，其他 channel（user-stream:*、agent:*、stream:*、ss:external-relay:*）是否真的回来了，没有逐个验证
- `skill-server/src/main/java/com/opencode/cui/skill/service/SkillInstanceRegistry.java:219-220`
  - `verifyOwnSubscriptionAlive` 同样只检查 `SS_RELAY_CHANNEL_PREFIX + instanceId`
- 风险：自愈期间 user-stream 静默挂掉，用户消息直接丢

---

### #4 subscribe 是 check-then-act 且非原子

**杀伤力：高｜频率：多端登录、刷新页面、网络重连**

- `skill-server/src/main/java/com/opencode/cui/skill/service/RedisMessageBroker.java:153-172`（#30 后行号 +6）
  ```java
  private void subscribe(String channel, Consumer<String> handler) {
      unsubscribe(channel);              // ← 先取消老的
      // ⚠️ 这里到下一行有窗口
      activeListeners.put(channel, listener);
      listenerContainer.addMessageListener(...);
  }
  ```
- 两种坏情况：
  - **窗口内消息丢失**：unsubscribe 后、addMessageListener 前到达的消息无人接
  - **双重订阅**：两个线程并发 subscribe 时，listenerContainer 上挂两个 listener → 同一消息被处理两次

---

### #5 externalws L2 转发到死 pod ✅ 已修复（PR #30, commit 15ce232）

**原杀伤力：中｜频率：pod 滚动重启 / 扩缩容**

旧实现：`external-ws:registry:{domain}` 共享 hash + TTL 设在 hash key 上 → 任一活实例 EXPIRE 续整个 hash → 死实例字段被无限期续命。

修复后架构（owner-only writes）：
- 新 key `external-ws:held-by:{instanceId}` HASH，每实例自管 `{domain → count}`
- 新 key `instance:roster` ZSET，活实例花名册（score = unix-ms 心跳时间）
- `ExternalWsRegistry.findInstanceWithConnection` (line 92-122)：先 `listAliveInstances()` 拿活实例，再 pipeline HGET 选第一个 count > 0
- 心跳从 30s 缩到 10s，TTL = 30s = 3× 心跳
- `@PreDestroy cleanupHeldBy` 加速 graceful shutdown 感知

行为保证：kill -9 后 ≤ 30s 死实例从 roster 过期 → L2 不再选中。

---

### #6 takeover 接管时双 emit

**杀伤力：中｜频率：故障转移**

- `skill-server/src/main/java/com/opencode/cui/skill/service/GatewayMessageRouter.java:396-454`
  - takeover 逻辑没"先停老 owner，再启新 owner"的语义
- 老 owner 半死期间已经在处理的消息会继续 emit；新 owner 接管后处理新消息也 emit → 同一用户收到两份

---

### #7 客户端文本拼接是不假思索的 +=

**杀伤力：放大上面所有问题到肉眼可见｜频率：每条文本**

- `skill-miniapp/src/protocol/StreamAssembler.ts:127-135`
  - `text.delta`: `part.content += msg.content`
  - 任何重复 / 乱序 / 时序错乱的消息都直接拼到屏幕上
- 没有任何防御性逻辑（没有按 seq 排序、没有按 partId+offset 去重）

---

## 修复优先级

| 步骤 | 内容 | 解决的根因 |
|---|---|---|
| 1 | 客户端按 seq 排序 + 去重 + reorder buffer | 消除 #1、#7 的肉眼影响；缓解 #2、#4、#6 的可见症状 |
| 2 | 服务端 miniapp 把贴 seq 挪到 producer 端 | 让 #1 闭环 |
| 3 | Redis 监听器改单线程或 per-session 串行 | 从根上消除 #2 |
| 4 | subscribe 加锁/CAS 改原子 | 消除 #4 |
| 5 | 自愈逻辑覆盖所有 channel | 消除 #3 |
| 6 | externalws L2 加 ack/重试 + stale 清理 | 消除 #5（已在做） |
| 7 | takeover 时强制停老 owner | 消除 #6 |

第 1+2 步组合可以把用户报障压下 80%。

---

## 双线分析的差异点

我（Claude）原结论：`forceReconnectListenerContainer` 重启 container 后会**重新注册 activeListeners 里所有 channel**。

Codex 修正：它**只**校验 `verifyChannel`（实际只传 `ss:relay`），**对 user-stream 等其他 channel 是否真的恢复没有逐个验证**。

→ 影响：原以为是"双重订阅导致重复"，实际更可能是"user-stream 静默断 → 消息丢失"。重复和丢失是相反方向的故障，都存在。

---

## 工件路径

- 简报：`.trellis/workspace/Llllviaaaa/codex-brief-stream-ordering.md`
- Codex 原始结果：`.trellis/workspace/Llllviaaaa/codex-result-stream-ordering.md`
- Codex 运行日志：`.trellis/workspace/Llllviaaaa/codex-run.log`

---

## #30 合入后复查结果（2026-05-15）

| 根因 | 状态 | 说明 |
|---|---|---|
| #1 编号机制没拼起来 | 🔴 仍存在 | 文件未改动（MiniappDeliveryStrategy / SkillStreamHandler / useSkillStream / StreamAssembler 都不在 #30 改动范围） |
| #2 Redis listener 多线程派发 | 🔴 仍存在 | `RedisConfig.java:48-55` 未改 |
| #3 自愈只 verify ss:relay | 🔴 仍存在 | `SkillInstanceRegistry.java:147-148` 调用现场坐实，只校验 ss:relay |
| #4 subscribe 非原子 | 🔴 仍存在 | `RedisMessageBroker.java:153-172`，行号 +6 但代码逻辑同 |
| #5 externalws L2 stale pod | ✅ **已修** | owner-only writes + ZSET 花名册，详见上文 #5 |
| #6 takeover 双 emit | 🔴 仍存在 | `GatewayMessageRouter.java` 不在 #30 改动范围 |
| #7 客户端 += 拼接 | 🔴 仍存在 | `StreamAssembler.ts:127-135` 不在 #30 改动范围（#30 没动任何 .ts） |
| #8 死订阅 `stream:{source}` | 🔴 仍存在 | `ExternalStreamHandler.java:92-95, 142-158, 265` 完整保留；grep 全仓 `convertAndSend.*stream:` / `publishToChannel.*stream:` 仍只在 docs/plans 测试示例里出现，无生产 publisher |

### 复查方法
1. `git pull --ff-only origin main` → 1 commit `15ce232`
2. 重读 #30 改动的 4 个 Java 文件（RedisMessageBroker / ExternalWsRegistry / SkillInstanceRegistry / ExternalStreamHandler）
3. 对其他 6 条根因：直接 grep 关键代码段，确认行号漂移幅度，确认逻辑未变
4. Spot check `convertAndSend.*stream:` / `publishToChannel.*stream:` 的 publisher 仍然不存在

### 修复优先级（更新版）
- ~~#5 externalws L2~~ → 已修，从清单移除
- 仍优先做 1+2：客户端 seq dedup/reorder + 服务端 producer-side seq
- 之后 #4 subscribe 原子化（成本最低、修复影响最大的剩余项）
- #3 自愈覆盖范围扩大（codex 的修正点）
- #2 listener 改单线程或 per-session 串行（影响吞吐，需压测）
- #6 takeover 协议化（最复杂，留到最后）
- #8 死订阅 `stream:{source}` 直接删（极小改动，可顺手清理）
