# Phase 6 Summary

## Outcome

Phase 6 已完成，实现结果如下：

- `GatewayMessage` 顶层新增 `userId`
- `skill-server` invoke 时携带 `userId`
- `gateway` 基于 Redis 中的 `ak -> userId` 做 invoke 校验，并在回流时补回 `userId`
- `pc-agent` 收发报文不再携带 `userId`
- `skill-server` 实时广播从 `session:{sessionId}` 切换到 `user-stream:{userId}`
- `SkillStreamHandler` 负责用户第一条流连接订阅、最后一条流连接退订
- 旧的 session 级实时广播 API 已从 `skill-server` 的 `RedisMessageBroker` 中移除

## Validation

已通过的关键验证：

- `ai-gateway`
  - `mvn test "-Dtest=GatewayMessageTest,EventRelayServiceTest"`
- `skill-server`
  - `mvn clean test "-Dtest=SkillMessageControllerTest,SkillSessionControllerTest,GatewayRelayServiceTest,SkillStreamHandlerTest"`

验证覆盖点包括：

- `userId` 在 gateway / skill-server 链路中的写入、校验和回流补全
- `pc-agent` 出入站消息不暴露 `userId`
- `user-stream:{userId}` 广播与用户级订阅生命周期
- 旧 `session` 实时路径不再参与主消费链路

## Follow-up

- 建议在集成环境补跑真实 A/B/C 多实例联调，验证“连接在 A、发送在 B、回流在 C”场景
- v1.1 设计说明已新增到 `documents/gateway-skill-routing/09-connection-aligned-consumption-fix.md`
