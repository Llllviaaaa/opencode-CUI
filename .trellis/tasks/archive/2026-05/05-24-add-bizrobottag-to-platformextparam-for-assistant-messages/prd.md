# add bizRobotTag to platformExtParam for assistant messages

## Goal

在 skill-server 下发给个人助理、默认助理、远端助理的 invoke/cloudRequest 报文中，给 `extParameters.platformExtParam` 增加 `bizRobotTag` 字段，让下游能从平台扩展参数里直接拿到当前助理的业务机器人标签。

## What I already know

* 用户明确要求给个人助理、默认助理、远端助理下发报文的 `platformExtParam` 增加 `bizRobotTag`。
* `PlatformExtParamBuilder` 是当前 `extParameters.platformExtParam` 的共享构造口，已经统一写入 `businessSessionDomain`、`businessSessionType`、`businessSessionId`，personal chat 还会按条件写入 `allowedSlashCommands`。
* 远端助理的 instance/query 数据模型已有 `AssistantInstanceInfo.bizRobotTag`，并且 `AssistantInfoService.getAssistantInfo(ak, assistantAccount)` 会把远端 instance 的 `bizRobotTag` 映射到 `AssistantInfo.businessTag`。
* 默认助理的规则模型 `DefaultAssistantRule.businessTag` 是当前云端 profile/路由标签来源，可作为下发 `bizRobotTag` 的值来源。
* 个人助理普通下发路径由 `GatewayRelayService.buildInvokeMessage` 注入 `payload.extParameters.platformExtParam`；默认和远端云端路径分别由 `DefaultAssistantScopeStrategy`、`BusinessScopeStrategy` 构建 `cloudRequest.extParameters.platformExtParam`。
* pending/retry 路径会自己重建 `extParameters.platformExtParam`，之前业务会话字段和 allowed slash 字段都需要在 `PendingChatRequest` 中保留或冻结，避免 live-send 和 retry-send 报文形态漂移。

## Assumptions

* 新字段 wire key 固定为 `bizRobotTag`，放在 `extParameters.platformExtParam.bizRobotTag`。
* `bizRobotTag` 跟现有三个业务会话字段一样属于 SS-owned platform 字段：key 始终出现，值缺失时序列化为 JSON `null`。
* 字段值复用当前路由标签来源：
  * 远端助理：`AssistantInfo.businessTag`，该值来自 instance/query 的 `bizRobotTag`。
  * 默认助理：`DefaultAssistantRule.businessTag`。
  * 个人助理：`AssistantInfo.businessTag` 可解析时使用；无法解析或为空时下发 JSON `null`。

## Requirements

* R1: `PlatformExtParamBuilder` 支持写入 `bizRobotTag`，并保持旧调用方兼容。
* R2: personal path 的 `GatewayRelayService.buildInvokeMessage` 在注入 `payload.extParameters.platformExtParam` 时写入 `bizRobotTag`。
* R3: default assistant path 的 `DefaultAssistantScopeStrategy` 在 `cloudRequest.extParameters.platformExtParam` 写入 `bizRobotTag`。
* R4: remote/business path 的 `BusinessScopeStrategy` 在 `cloudRequest.extParameters.platformExtParam` 写入 `bizRobotTag`。
* R5: pending/retry 重发路径的 `PendingChatRequest` 和 `GatewayMessageRouter.retryPendingMessages` 保留并复用 `bizRobotTag`，避免首次发送和重发报文字段不一致。
* R6: 老 Redis pending entry 兼容：缺少 `bizRobotTag` 的老格式反序列化后不报错，retry 时该字段下发 JSON `null`。
* R7: 不改入站外部 API，不要求调用方传 `bizRobotTag`，字段由 skill-server 自己解析/注入。

## Acceptance Criteria

* [ ] 个人助理 invoke payload 中存在 `payload.extParameters.platformExtParam.bizRobotTag`。
* [ ] 默认助理 cloudRequest 中存在 `cloudRequest.extParameters.platformExtParam.bizRobotTag`，值来自默认助理规则的 `businessTag`。
* [ ] 远端助理 cloudRequest 中存在 `cloudRequest.extParameters.platformExtParam.bizRobotTag`，值来自远端实例的 `bizRobotTag` 映射。
* [ ] retry/rebuild 发送出的 chat payload 保留 `platformExtParam.bizRobotTag` 字段；老 pending entry 缺值时字段为 JSON null。
* [ ] 既有 `businessSessionDomain`、`businessSessionType`、`businessSessionId`、`allowedSlashCommands` 语义不变。

## Definition of Done

* 单元/集成测试覆盖 personal/default/remote 和 retry 关键路径。
* Maven 测试至少跑相关 skill-server 测试类。
* GitNexus impact 在编辑目标符号前完成，`detect_changes` 在收尾时完成。
* `implement.jsonl` / `check.jsonl` 只包含本任务需要的 spec/guideline 上下文。

## Out of Scope

* 不修改 ai-gateway 协议处理，当前仓库搜索未发现 ai-gateway 对 `platformExtParam` 做字段级过滤。
* 不调整 `cloudProfile` 解析规则。
* 不新增数据库列；`bizRobotTag` 仅作为下发报文字段传播。

## Technical Notes

* 相关入口：`GatewayRelayService.buildInvokeMessage`、`BusinessScopeStrategy.buildInvoke`、`DefaultAssistantScopeStrategy.buildInvoke`、`GatewayMessageRouter.retryPendingMessages`。
* 相关 DTO/helper：`InvokeCommand`、`PendingChatRequest`、`PlatformExtParamBuilder`。
* 规范依据：skill-server backend `directory-structure.md` / `conventions.md` / `type-safety.md`；共享 guides 的 code-reuse 和 cross-layer 要求优先复用公共 helper，并覆盖 multi-producer envelope parity。
