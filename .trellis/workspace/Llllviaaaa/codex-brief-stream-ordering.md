# 任务：深挖"miniapp/externalws 流式事件乱序+重复"的所有根因

你是 GPT-5.5 高 reasoning，跟我（Claude）并行分析同一份代码库。我已经做了一轮深挖，把结论列在下面（"我已得出的结论"段）。你的任务：

1. **独立验证**：把下面每个结论的"file:line"打开复读，确认对/错/部分对。错的请指出并贴反证。
2. **找我漏掉的**：从代码细节角度，挖我没有提到的导致**乱序**或**重复**的具体路径（不是泛泛的"分布式系统都难"）。每条要带 file:line + 复现条件。
3. **排序**：用"触发频率 × 用户可见严重度"给所有根因排个序，告诉我最该先修哪个。
4. **挑战我的修复优先级**：我打算先做"客户端按 seq 去重+缓冲"+"服务端 producer 端分配 seq"。你认为这两条够不够？有没有反例场景这两条解决不了？

回答约束：
- 全中文。
- 工具：只读 Read/Grep/Glob。不要 `git apply` / 写代码。允许调 mcp__abcoder / mcp__gitnexus 工具。
- 不要复述我已说过的结论，重点放在"验证 + 补充 + 反驳"。
- 引用必须带 file:line。无证据的不要写。
- 最终结论不要超过 1500 字。

---

## 背景

项目：opencode-CUI（Java/Spring Boot 后端 + TypeScript/React 前端）
- `ai-gateway/` (GW)：PC Agent 的 WebSocket 接入网关，多实例。
- `skill-server/` (SS)：业务后端，多实例。
  - miniapp WS 走 `SkillStreamHandler`（端点 `/ws/skill/stream`）。
  - external WS 走 `ExternalStreamHandler`（端点带 `Sec-WebSocket-Protocol: auth.<base64>`）。
- `skill-miniapp/`：前端 React，hook `useSkillStream.ts`，protocol `StreamAssembler.ts`。
- 跨实例协调：Redis Pub/Sub + INCR + Hash + Lua CAS。

症状：GW 多实例 + SS 多实例 时，miniapp 端和 externalws 端的流式事件**乱序**和**重复**。

---

## 我已得出的结论（请你独立验证）

### A. 线程模型层
- `skill-server/src/main/java/com/opencode/cui/skill/config/RedisConfig.java:48-55` 用 `ThreadPoolTaskExecutor(core=5, max=50, queue=200)` 作为 listener container 的 taskExecutor。同 channel 连续到达的消息会被并发派发到多个 worker → 同一 session 的 D1/D2 可能被两个线程并发处理。`synchronized(session)` 只保证不交错写，不保证按时间顺序写。这是 miniapp 单设备也乱序的根本原因。

### B. Miniapp 数据流
- 服务端 producer (`skill-server/.../delivery/MiniappDeliveryStrategy.java:42`) publish 时**不带 seq**。
- 服务端 consumer (`SkillStreamHandler.java:228, 261, 270`) 收到 Redis 消息后才 `nextTransportSeq` → Redis INCR。这意味着多 SS 实例同 user 时各自分配不同 seq。
- 客户端 (`skill-miniapp/src/hooks/useSkillStream.ts:98-109`) 排序用的是 `messageSeq`（业务级），**不用** `seq`（传输级）。
- 客户端 `applyStreamedMessage` (useSkillStream.ts:462-504) 按到达顺序喂 `StreamAssembler`。
- `StreamAssembler.ts:127-135` 的 text.delta `part.content += msg.content`：乱序 → 文本顺序反；重复 → 内容拼接两次。

### C. ExternalWs 数据流
- 服务端 producer (`ExternalWsDeliveryStrategy.java:51-53`) 在 publish 前调 `nextStreamSeq` → wire 上 seq 单调（这一点对的）。
- 但并发 emit 时 INCR + publish 两步非原子。
- `ExternalStreamHandler.java:91-93` 每个 SS 实例只要有 source=X 连接就订阅 `stream:{source}` channel，handler 是 `pushToSource` 即**本实例 source 下所有连接广播**。仓库 grep 找不到任何 publisher。要么死代码，要么外部系统在用。
- `ExternalWsRegistry.findInstanceWithConnection` (ExternalWsRegistry.java:52-65) 选 L2 target 时不二次校验对方活性，TTL 内 stale pod 仍会被选中。

### D. 故障路径
- `GatewayMessageRouter.route` (GatewayMessageRouter.java:370-462) 在 `publishToSsRelay` 返回 0 subscribers 时触发 takeover。
- `RedisMessageBroker.forceReconnectListenerContainer` (RedisMessageBroker.java:262-275) 自愈半死 pub/sub：重启整个 listenerContainer，重新注册 activeListeners 里所有 channel——**包括 user-stream:{userId}**。
- 后果：takeover 后老 owner 仍订阅 `user-stream:{userId}`；user 在两个实例同时有 WS 时双推。
- `RedisMessageBroker.subscribe` (RedisMessageBroker.java:147-166) 的 `unsubscribe→put→addMessageListener` 不原子。两个线程同 channel 并发 subscribe 时可能挂两个 listener。

### E. 其他细节
- `StreamMessageEmitter.emitToClient` (StreamMessageEmitter.java:92-109) 直接 publishToUser，旁路 dispatcher。和 `emitToSession` 在 inbound 路径上可能都被触发。
- `StreamMessageEmitter.emitToClientWithBuffer` (StreamMessageEmitter.java:111-115) 同时 emit + accumulate；客户端断线重连时 `'streaming'` 分支不重建（useSkillStream.ts:920-937），所以这条路径上**没有 dup**。
- `GatewayMessageRouter.completedSessions` (GatewayMessageRouter.java:81-84, 569-577) 是 5s 固定窗口；turn 快速切换可能误丢。
- `RedisMessageBroker.tryAcquire` (RedisMessageBroker.java:496-503) 存在但全仓库没用在 stream 路径。

---

## 我的修复优先级

1. **客户端按 seq dedup + reorder buffer**（`useSkillStream.handleStreamMessage` 入口）。
2. **服务端 miniapp seq 移到 producer 端**（`MiniappDeliveryStrategy.deliver` 在 publish 前分配；`SkillStreamHandler.pushStreamMessageToUser` 不再覆盖）。
3. listener container 改单线程 / per-channel 串行。
4. `subscribe` 的 check-then-act 改原子。
5. takeover 后清理对应订阅 / 引入 sticky 校验。
6. 删除/收紧 `stream:{source}` 订阅。

---

## 给你的具体任务清单

工作目录在 `D:\02_Lab\Projects\sandbox\opencode-CUI`。请：

1. 跑 `Grep` 校验"`stream:{source}` channel 没有 publisher"这条结论。线索：搜 `convertAndSend.*stream:` / `publishToChannel.*stream:` / `redisTemplate.*stream:`。注意排除 `stream:` 前缀的 Redis **key**（StreamBufferService 用的），只看 pub/sub channel。
2. 读 `RedisMessageBroker.java` 的 `forceReconnectListenerContainer` + `physicalSubscriberCount`，验证我的"半死自愈后老 owner 仍订阅 user-stream 导致双推"的故事是否完整。特别注意：被自愈的进程会重新订阅哪些 channel？user-stream 一定回来吗？
3. 读 `GatewayMessageRouter.handleSsRelayMessage` + `route` + `dispatchLocally`，确认 takeover 的 race 窗口是否真的会让两个实例都 emit StreamMessage 到同一 user。
4. 读 `ExternalStreamHandler.afterConnectionEstablished` + `handleRedisMessage` + `pushToSource`：如果 `stream:{source}` 真的有外部 publisher，每条消息会重复多少次？
5. 看 `ai-gateway/src/main/java/com/opencode/cui/gateway/service/SkillRelayService.java` 和 `RedisMessageBroker.java`（GW 侧的，跟 SS 同名但是不同实现）：GW 多实例如何分流来自 PC Agent 的 tool_event 到 SS？有没有 GW 侧的乱序/重复源？
6. 看 `ExternalWsRegistry.findInstanceWithConnection` 的调用方 + `DeliveryProperties.registryTtlSeconds` 默认值，估算 stale pod 命中的窗口大小。
7. 用 mcp__gitnexus__query 或 mcp__abcoder 找 `applyStreamedMessage` 和 `StreamAssembler.handleMessage` 的所有调用路径，看有没有可能某些事件类型绕过 dedup。
8. 给我一个"按触发频率 × 严重度"的总根因表。

输出格式：
```
## 验证结果（我哪些对/错）
- [对/错/部分对] A. 线程模型层：<证据>
- [对/错/部分对] B. ...

## 我漏掉的（按严重度排）
1. <根因>，证据：file:line，复现条件：...
2. ...

## 总根因排序
| 根因 | 触发频率 | 严重度 | 备注 |
| ... |

## 对我修复方案的挑战
- 方案 1+2 解决不了的反例：...
```
