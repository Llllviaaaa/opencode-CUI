---
status: complete
phase: 07-gateway-multi-service-source-isolation
source:
  - ai-gateway/src/main/java/com/opencode/cui/gateway/model/GatewayMessage.java
  - ai-gateway/src/main/java/com/opencode/cui/gateway/service/EventRelayService.java
  - ai-gateway/src/main/java/com/opencode/cui/gateway/service/RedisMessageBroker.java
  - ai-gateway/src/main/java/com/opencode/cui/gateway/service/SkillRelayService.java
  - ai-gateway/src/main/java/com/opencode/cui/gateway/ws/SkillWebSocketHandler.java
  - ai-gateway/src/test/java/com/opencode/cui/gateway/service/EventRelayServiceTest.java
  - ai-gateway/src/test/java/com/opencode/cui/gateway/service/RedisMessageBrokerTest.java
  - ai-gateway/src/test/java/com/opencode/cui/gateway/service/SkillRelayServiceTest.java
  - ai-gateway/src/test/java/com/opencode/cui/gateway/ws/SkillWebSocketHandlerTest.java
  - documents/protocol/02-layer2-skill-gateway-api.md
  - documents/gateway-skill-routing/06-requirements.md
started: 2026-03-12T11:00:00+08:00
updated: 2026-03-12T11:00:00+08:00
---

## Current Test

[testing complete]

## Tests

### 1. 上游标准协议显式携带 source
expected: 上游服务发给 gateway 的标准消息显式携带 source，且 gateway 到 pc-agent 的消息不会下沉 source。
result: pass
evidence:
  - GatewayMessage 已新增 source 和 withoutRoutingContext
  - EventRelayService 发往 agent 时移除 source
  - Skill server 握手与 invoke 已显式携带 source

### 2. 握手绑定 source 并拒绝非法来源
expected: 上游连接握手成功后会绑定可信 source；source 缺失、不允许或与连接身份不一致时，gateway 会协议级拒绝。
result: pass
evidence:
  - SkillWebSocketHandler 在握手阶段解析 token + source
  - SkillRelayService 返回 source_not_allowed / source_mismatch
  - SkillWebSocketHandlerTest 与 SkillRelayServiceTest 已覆盖校验失败路径

### 3. 回流先按 source 分域再按 owner 路由
expected: OpenCode 回流不会跨服务错投；owner 选择按 source 分域执行，owner key 使用 source:instanceId。
result: pass
evidence:
  - RedisMessageBroker owner 注册已升级为 source 分域
  - SkillRelayService 本地连接池和 remote owner 选择均按 source 执行
  - SkillRelayServiceTest 覆盖 new-service 不会路由到 skill-server

### 4. fallback 仅允许同域执行
expected: 原 owner 不可用时只允许在同一 source 域内 fallback；同域无可用 owner 时直接失败，不跨域兜底。
result: pass
evidence:
  - SkillRelayService 仅查询 getActiveSourceOwners(source)
  - SkillRelayServiceTest 覆盖同域 fallback 和无 source 拒绝场景

### 5. 路由具备 traceId 与结构化日志字段
expected: 关键路由链路会产出 traceId、source、ownerKey、routeDecision、fallbackUsed、errorCode 等字段，便于排查错投。
result: pass
evidence:
  - GatewayMessage 已新增 traceId
  - EventRelayService / SkillRelayService 已补充结构化路由日志
  - 自动化测试验证 traceId 会在路由消息中生成并透传

## Summary

total: 5
passed: 5
issues: 0
pending: 0
skipped: 0

## Gaps

[]
