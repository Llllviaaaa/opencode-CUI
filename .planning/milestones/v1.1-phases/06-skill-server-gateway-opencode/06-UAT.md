# Phase 6 UAT

## 状态

已通过

## 用例 1：miniapp 实时收到 opencode 返回

### 用户反馈

- miniapp 发送消息后，`opencode` 有返回
- 当时 miniapp 没有实时显示
- 刷新页面后，返回内容出现在 miniapp 中

### 证据

- `pc-agent` 日志：`C:\Users\15721\AppData\Local\Temp\pc-agent-debug.log`
  - 可见连续 `relayUpstream` 事件，说明 agent / opencode 回流存在
- gateway 日志：`D:\02_Lab\Logs\ai-gateway\ai-gateway.log`
  - 可见 `Relaying to skill: ak=test-ak-001, userId=1, type=tool_event/tool_done`
  - 说明 gateway 已把 `userId` 补回并成功转发给 skill-server
- skill-server 日志：`D:\02_Lab\Logs\skill-server\skill-server.log`
  - `02:13:00.095` 第二条用户流连接已建立
  - `02:13:00.309` 第一条连接出现 transport error
  - `02:13:00.360` 错误触发 `Unsubscribed from user stream: userId=1`
  - `02:13:19 ~ 02:13:21` 期间仍持续 `Published to Redis channel user-stream:1`
  - 说明实时消息已经发布，但前端订阅被提前退掉

### 结论

该用例最初失败。

失败原因不是 `opencode` 未返回，也不是 `gateway` 未转发，而是 `SkillStreamHandler` 对同一个 WebSocket 连接发生了重复清理：

- `handleTransportError(...)`
- `afterConnectionClosed(...)`

二者对同一条连接重复执行 `unregisterSubscriber(...)`，导致在仍有第二条活跃连接时，错误地把整个 `user-stream:{userId}` 订阅退掉。

因此：

- 实时链路失效
- 消息只完成持久化
- 页面刷新后通过 snapshot 再次读到消息

### 已实施修复

- 将 `SkillStreamHandler.unregisterSubscriber(...)` 改为幂等
- 首次清理时即移除 session 的 `userId` 标记
- 第二次清理命中同一 session 时直接跳过，不再重复递减连接计数

### 自动化回归

- 新增测试：`transport error followed by close does not unsubscribe remaining user stream connection`
- 验证命令：
  - `mvn test "-Dtest=SkillStreamHandlerTest,GatewayRelayServiceTest"` in `skill-server`

结果：通过

### 当前判定

- 根因已定位
- 代码修复已完成
- 自动化回归已通过
- 用户已在运行环境重启 `skill-server` 并完成复测
- miniapp 实时回流正常，未再出现“刷新后才看到 opencode 返回”的问题

## 下一步

1. 如需正式收口 milestone，可继续执行 `$gsd-audit-milestone`
2. 若后续再次出现实时回流异常，优先检查 `SkillStreamHandler` 的连接清理与订阅生命周期日志
