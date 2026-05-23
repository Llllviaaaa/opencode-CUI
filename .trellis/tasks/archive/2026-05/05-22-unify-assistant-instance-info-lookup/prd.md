# 统一通过分身实例信息接口获取助理数据

## Goal

将当前分散的数据获取链路收口到“查询数字分身实例信息”接口：

`GET /assistant-api/integration/v4-1/we-crew/instance/query?partnerAccount={welinkId}`

这次改造覆盖 skill-server 和 ai-gateway：

* skill-server 用该接口获取并缓存分身身份信息，派生 AK、ownerWelinkId、personal/business、businessTag。
* ai-gateway 在真正远端调用前，用同一个接口自行获取并缓存远端调用配置 `remoteProperty[]`。
* SS -> GW 不透传 `remoteProperty.url/headers/commProtocol/dataProtocol` 等远端路由配置，只传 gateway 查询实例接口所需的分身身份标识。

## What I Already Know

* 新接口按 `partnerAccount` 查询分身实例信息，`partnerAccount` 是分身对应的 WeLink ID，也就是当前链路里常说的 `assistantAccount`。
* 新接口返回 `data.appKey`、`data.ownerWelinkId`、`data.bizRobotTag`、`data.isRemote`、`data.remoteProperty[]` 等字段。
* `remoteProperty` 是数组，通过 `type` 区分远端能力，例如 `chat`、`question`。
* 远端助理理论上没有 AK，因此不能继续把 `appKey` 作为存在性、business 判断、远端配置查询的硬前提。
* 当前 `skill.assistant.resolve-url` 默认值已经是新接口路径，但 `AssistantAccountResolverService` 仍要求 `appKey + ownerWelinkId` 才判定 EXISTS。
* 当前 `AssistantInfoService` 仍通过 AK 调旧接口 `skill.assistant-info.api-url` 获取 identityType/businessTag/endpoint/protocol/authType。
* 当前 `BusinessScopeStrategy` / `DefaultAssistantScopeStrategy` 构建的 gateway invoke payload 主要包含 `cloudRequest`、`toolSessionId`、`cloudProfile`。
* 当前 ai-gateway 的 `CloudAgentService` 按 `(ak, scope, cloudProfile)` 调 `CallbackConfigService` 获取 callback config。

## Requirements

* 新建统一分身实例信息模型，至少覆盖：
  `partnerAccount`、`ownerWelinkId`、`appKey`、`isRemote`、`bizRobotTag`、`userType`、`remoteProperty[]`。
* skill-server 新建或改造实例信息查询服务：
  按 `partnerAccount/assistantAccount` 读全量缓存；未命中调用新接口；成功后写缓存；UNKNOWN 不写错误缓存。
* skill-server 保留旧入口兼容：
  `AssistantAccountResolverService.resolveWithStatus/check/resolveAk` 调用方不大面积改签名，但内部数据源改为实例信息服务。
* skill-server 存在性判断适配远端无 AK：
  `code=200 + data 非空 + partnerAccount/ownerWelinkId 等身份字段可用` 时应判定 EXISTS；不能因为 `appKey` 缺失直接 NOT_EXISTS。
* skill-server scope 派生规则：
  `isRemote=true` 或存在可用 `remoteProperty[]` 时视为 business/remote；本地自定义分身走 personal；`bizRobotTag` 派生为 `businessTag`。
* skill-server 发送 gateway invoke 时，必须让 gateway 能拿到 `partnerAccount`：
  推荐写入 `payload.assistantAccount`，必要时兼容 `payload.partnerAccount`。该字段只是身份标识，不是远端路由配置。
* ai-gateway 新增实例信息查询服务：
  按 `partnerAccount` 调同一个新接口，缓存完整实例信息或至少缓存 `remoteProperty[]` 派生的远端路由配置。
* ai-gateway 远端配置选择规则：
  `chat` action 选择 `remoteProperty[].type == "chat"`；
  `question_reply` 和 `permission_reply` 暂定选择 `remoteProperty[].type == "question"`；
  如果未来上游拆分更细 type，再扩展 action -> ability type 映射表。
* ai-gateway 将 `remoteProperty` 转换为内部连接配置：
  `url -> channelAddress`；
  `commProtocol=sse/ws/http -> channelType=sse/websocket/webhook`；
  `headers[] -> 请求 header/鉴权注入策略`；
  `dataProtocol -> 协议 profile/decoder 选择的输入或保留字段。
* ai-gateway 需要升级 header/auth 注入：
  现有 `CloudAuthService` 只支持单个 `authType + appId`；新接口返回 `headers[]`，需要支持多个 header 配置，至少识别 `soa`、`integration`、`apig`、`iam`、`custom`、`cookie`，不支持时明确失败且不泄露 secret。
* ai-gateway 兼容回退：
  默认保留旧 `CallbackConfigService` 作为兼容 fallback；当 invoke 没有 `assistantAccount/partnerAccount`、实例接口不可用、或 `remoteProperty` 缺少对应能力时，可回退旧路径。
  但远端无 AK 的场景主路径必须走实例接口；如果实例接口也拿不到配置，应返回新的明确错误，不误报为 AK callback 配置缺失。

## Acceptance Criteria

* [ ] IM/external 入站首次通过 `assistantAccount` 查询时，skill-server 只需调用一次实例接口即可获得 ownerWelinkId、scope、businessTag 等数据。
* [ ] 远端助理 `isRemote=true` 且 `appKey` 缺失时，不再被判定为 NOT_EXISTS。
* [ ] 本地/个人助理仍可使用 AK 走原本本地插件在线检查和 gateway 下行路径。
* [ ] 业务/远端助理不再依赖“通过 AK 查询远端 callback 配置”作为唯一来源。
* [ ] gateway 能从 invoke payload 获取 `assistantAccount/partnerAccount`，自行调用实例接口并选择 `remoteProperty`。
* [ ] `remoteProperty[]` 能按 `type` 选择 `chat` 或 `question` 配置；缺失能力时返回明确错误。
* [ ] `question_reply`、`permission_reply`、rebuild/retry 等路径仍能保留 gateway 查询所需的 partnerAccount。
* [ ] gateway 支持 `remoteProperty[].headers[]` 的多 header 处理，custom/cookie 值不落日志。
* [ ] 覆盖 skill-server 单元测试：实例接口解析、缓存命中/未命中、远端无 AK、三态 EXISTS/NOT_EXISTS/UNKNOWN。
* [ ] 覆盖 ai-gateway 单元测试：实例接口查询缓存、action -> remoteProperty type 映射、commProtocol 映射、headers 映射、远端无 AK 不触发 callback_config_missing。

## Technical Approach

### skill-server

* 新增 `AssistantInstanceInfoService` 或等价服务，按 `partnerAccount` 查询并缓存实例信息。
* `AssistantAccountResolverService` 改为从实例信息派生 `ResolveOutcome`：
  `ak` 可以为空；`ownerWelinkId` 和 `isRemote`/实例状态应参与存在性判断。
* `AssistantInfoService` 改为从实例信息派生 `AssistantInfo`：
  `assistantScope = remote ? business : personal`；
  `businessTag = bizRobotTag`。
* `BusinessScopeStrategy` 和 `DefaultAssistantScopeStrategy` 不透传远端配置。
  它们只保证 gateway invoke payload 有 `assistantAccount` 或 `partnerAccount`，让 gateway 自己查。
* 多生产者一致性必须检查：
  `InboundProcessingService.dispatchChatToGateway`、`GatewayMessageRouter.retryPendingMessages`、`SkillMessageController` 的 question/permission reply、`SessionRebuildService` 都要保留该身份字段。

### ai-gateway

* 新增 gateway 侧 `AssistantInstanceInfoService` 或等价服务，配置项建议：
  `gateway.assistant.instance-query-url`、`gateway.assistant.instance-query-token`、`gateway.assistant.instance-cache-ttl-seconds`。
* `CloudAgentService` 在 business/remote invoke 时：
  1. 从 payload 读取 `assistantAccount`，兼容 `partnerAccount`。
  2. 根据 action 映射能力 type。
  3. 调实例信息服务获取 `remoteProperty[]`。
  4. 将选中配置转换成 `CloudConnectionContext`。
  5. 走现有 `CloudProtocolClient` / `WebHookExecutor`。
* `CallbackConfigService` 不作为远端新链路主路径；它保留为兼容 fallback。
* 新增或扩展 remote header resolver：
  对 `headers[]` 逐项应用，`custom` 写 `customKey/customValue`，cookie 写 Cookie，其他类型走现有或新增 auth strategy。

## Decision (ADR-lite)

**Context**: 旧实现把 AK 同时用于本地插件路由、业务远端配置查询、助理身份查询。远端助理无 AK 后，这些语义会冲突。gateway 是实际发起远端调用的一层，因此远端 url/header 配置应该由 gateway 自己查询和缓存。

**Decision**: partnerAccount/assistantAccount 对应的实例信息是统一事实源。skill-server 和 ai-gateway 都调用该接口，但职责不同：skill-server 管身份和会话判断，ai-gateway 管远端调用配置。SS -> GW 只传身份标识，不传远端路由配置。

**Consequences**: 两个服务都需要改造与测试。gateway 会新增实例查询和缓存路径，但远端配置所有权更清晰；未来新增 `remoteProperty[].type` 时，主要扩展 gateway 的 action -> ability type 映射。

## Open Questions

* 暂无阻塞问题。已确认 gateway 不消费 SS 透传远端路由配置，而是自行调用实例接口获取 `remoteProperty[]`。

## Out of Scope

* 不改前端页面。
* 不改新接口本身的鉴权实现，只消费已有机机鉴权配置。
* 不删除默认助手规则机制。
* 不一次性移除全部旧 AK / CallbackConfig / SysConfig fallback 逻辑，除非明确要求硬切。
* 不在 SS -> GW invoke payload 中透传 `remoteProperty`、url、headers、commProtocol、dataProtocol。

## Technical Notes

* 已检查 `skill-server/src/main/java/com/opencode/cui/skill/service/AssistantAccountResolverService.java`：
  当前 `resolve-url` 已指向新接口，但 `judge` 仍要求 `appKey` 存在，否则 NOT_EXISTS。
* 已检查 `skill-server/src/main/java/com/opencode/cui/skill/service/AssistantInfoService.java`：
  当前通过 AK 调 `skill.assistant-info.api-url` 解析 `identityType/businessTag/endpoint/protocol/authType`。
* 已检查 `skill-server/src/main/java/com/opencode/cui/skill/service/scope/BusinessScopeStrategy.java`：
  当前 business invoke 只写 `cloudRequest/toolSessionId/cloudProfile`，需要补身份字段供 gateway 查询。
* 已检查 `skill-server/src/main/java/com/opencode/cui/skill/service/scope/DefaultAssistantScopeStrategy.java`：
  默认助手也构建 business wire，需要同样处理身份字段，但不透传远端配置。
* 已检查 `ai-gateway/src/main/java/com/opencode/cui/gateway/service/CloudAgentService.java`：
  当前按 action 映射 scope，再调用 `CallbackConfigService.getConfig(ak, scope, cloudProfile)`。
* 已检查 `ai-gateway/src/main/java/com/opencode/cui/gateway/service/cloud/CloudAuthService.java`：
  当前只接收单一 `authType`，需要适配 `remoteProperty[].headers[]`。
* GitNexus context 已查看：
  `AssistantAccountResolverService`、`AssistantInfoService`、`BusinessScopeStrategy`、`CloudAgentService`、`CallbackConfigService`。
