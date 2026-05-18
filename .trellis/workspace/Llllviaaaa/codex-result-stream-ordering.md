## 结论摘要
- [确认] miniapp 最大问题是“有 transport `seq`，但前端不用”：`MiniappDeliveryStrategy` 只 publish envelope，不设 `seq`（`skill-server/.../MiniappDeliveryStrategy.java:38-42`）；`SkillStreamHandler` 收到 Redis 后才 `nextStreamSeq` 赋值（`SkillStreamHandler.java:228-240,261-273,397-399`）。前端 `handleStreamMessage` 无 `seq` 去重/重排（`useSkillStream.ts:761-787,995-999`），`StreamAssembler` 对 `text.delta` 直接 append（`StreamAssembler.ts:127-130`）。
- [确认] listener 并发有乱序风险：Redis listener executor 是 `core=5/max=50/queue=200`（`RedisConfig.java:48-55`）。`synchronized(session)` 只保证单次 send 互斥（`SkillStreamHandler.java:375-381`），不保证进入锁的顺序。
- [确认] `stream:{source}` 在代码内基本是死订阅/遗留通道：订阅在 `ExternalStreamHandler.java:37,90-92,237`；代码 grep 未发现 `publishToChannel/convertAndSend.*stream:` 的生产 publisher。`StreamBufferService.java:23-32` 的 `stream:` 是 Redis key，不是 pub/sub。
- [确认] external WS 当前真实路径是 L1 本地 `pushToOne`，L2 `ss:external-relay:{instance}`（`ExternalWsDeliveryStrategy.java:50-68`；`ExternalStreamHandler.java:196-210`）。
- [部分确认] `forceReconnectListenerContainer` 只验证 `ss:relay:{instance}` 恢复（`RedisMessageBroker.java:262-290`；`SkillInstanceRegistry.java:129-139,188-190`）。user-stream 未被逐 channel 验证，不能证明一定丢，但确实缺监控/自愈闭环。

## 关键证据
1. miniapp `messageSeq` 只是消息排序，不是 delta transport 序：前端只按 `messageSeq` 排消息列表（`useSkillStream.ts:98-109`），`applyStreamedMessage` 只保留 `messageSeq`（`useSkillStream.ts:462-503`）。
2. `subscribe` 是 check-then-act 且内部先 `unsubscribe` 再 `addMessageListener`（`RedisMessageBroker.java:147-164,168-172`），重订阅窗口内可能丢 pub/sub 消息。
3. takeover：`publishToSsRelay` 返回 0 后会 `tryTakeover` 并 `dispatchLocally`（`GatewayMessageRouter.java:396-454`），随后可经 `emitToSession` 投递到用户（`GatewayMessageRouter.java:642-678`）。若旧 owner 只是 pub/sub 半死但仍处理残余事件，存在重复/乱序窗口。
4. GW 入站 SS 只处理 `invoke/route_confirm/route_reject`（`SkillWebSocketHandler.java:96-118`）；Agent 到 SS 走 `EventRelayService.relayToSkillServer`（`EventRelayService.java:219-237`）。

## 风险表
| 风险 | 触发 | 后果 | 建议 |
|---|---|---|---|
| miniapp delta 乱序/重复 | Redis listener 并发或 takeover | 文本 append 错序/重复 | 前端按 `seq` reorder+dedup |
| seq 生产端不统一 | miniapp consumer 端赋 seq | seq 与 publish 顺序脱钩 | producer publish 前赋 `seq` |
| external stale registry | TTL 30s、只按 count 选实例 | L2 投递到 stale pod 后丢弃 | L2 ack/失败重试并清 registry |
| force reconnect 漏检 | 只验证 `ss:relay` | user-stream 可能静默断 | 校验/重订阅 active user-stream |

## 修复优先级
先做 1+2：miniapp producer-side `seq` + 前端 `seq` dedup/reorder buffer。再处理 listener per-session ordered dispatch。external 侧建议删除/补齐 `stream:{source}` 语义，实际投递统一走 `ss:external-relay`。MCP 的 GitNexus/ABCoder 查询本轮返回 `user cancelled MCP tool call`，未做代码修改。