# 新增埋码字段并打印 GW 远程调用入参

## Goal

在现有服务端埋码上报链路中补充机器人 `robotId` 字段，并增强 ai-gateway 调用远程助手接口时的入参日志，便于后续定位不同机器人实例下的调用行为。

## What I already know

* 用户要求先新建分支处理两个问题。
* 埋码上报需要新增 `robotId`：可通过查询 `assistantInfo` 接口获得，接口返回字段为 `id`。
* 用户修正：不需要上报 `groupId`，只需要 `robotId`。
* GW 调用远程接口时需要打印入参，包括 header 信息。
* 当前分支为 `codex/add-telemetry-fields-gw-remote-logs`。

## Assumptions

* `robotId` 优先复用现有 assistant info / assistant instance 查询能力，不引入新的远程查询链路，避免重复调用。
* GW 日志需要足够定位问题，但不能泄露敏感鉴权明文；如已有脱敏工具或日志约定，应复用。

## Requirements

* 服务端埋码上报 payload 新增 `robotId` 字段，取 assistant info 接口返回的 `id`。
* ai-gateway 在调用远程助手接口前打印请求入参，至少包括目标 URL、header 关键信息、body/request payload。
* 日志输出遵循现有日志风格和敏感字段处理方式。

## Acceptance Criteria

* [ ] 埋码上报可看到来自 assistant info 返回字段 `id` 的 `robotId`。
* [ ] GW 远程接口调用日志包含 header 和 body/request 入参。
* [ ] 相关单元测试或可执行验证覆盖新增字段与日志路径。

## Definition of Done

* Tests added/updated where practical.
* Lint / targeted tests pass, or any skipped validation is explicitly explained.
* GitNexus impact analysis is run before editing modified symbols.
* `gitnexus_detect_changes()` is run before finishing/commit.

## Out of Scope

* 不调整埋码平台协议以外的业务字段语义。
* 不重构 assistant info 查询架构。
* 不改变 GW 远程调用的请求行为。

## Technical Notes

* 需先定位现有 WeLink/telemetry 上报事件组装位置。
* 需定位 GW 远程接口调用入口与现有 header/body 构造位置。
* 最新口径：不新增 `groupId` 埋码字段。
* 评估结果：`robotId` 可通过 assistant info / instance 查询结果中的 `id` 保留到 `AssistantInfo.id`，再进入埋码 listener。
* GW 远程调用入口覆盖 `SseProtocolStrategy`、`WebHookExecutor`、`WebSocketProtocolStrategy`；日志统一脱敏 header 后输出。
