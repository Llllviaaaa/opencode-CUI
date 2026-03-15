# P0 阶段四：permission_reply 方案专题（OpenClaw 映射版）

**ID:** `FR-MB-OPENCLAW-P0-PERMISSION-BRIDGE`  
**Version:** `v1.2`  
**Date:** `2026-03-16`  
**Status:** `方案冻结`  
**Owner:** `message-bridge maintainers`  
**关联需求:** `./mb-p0-permission-bridge-requirements.md`  
**关联实施计划:** `../implementation-plan.md`

## 1. 方案结论

本阶段不引入 OpenClaw 侧新的通用 `permission.*` 宿主接口，而是基于宿主已存在且已被现有扩展使用的 `exec approvals` 能力完成 `permission_reply` 闭环。

冻结结论：

1. OpenClaw 宿主侧真实可用的审批事件链路为：
   - 请求广播：`exec.approval.requested`
   - 决策提交：`exec.approval.resolve`
   - 结果广播：`exec.approval.resolved`
2. `permission_reply` 的实现采用单一映射路径：
   - `permissionId` 直接透传为 `approvalId`
   - `once | always | reject` 直接映射为 `allow-once | allow-always | deny`
3. `message-bridge-openclaw` 不直接依赖 OpenCode 的 `permission.ask` 风格宿主能力，也不在本阶段抽象新的 OpenClaw `permission.ask` 兼容层。
4. `message-bridge-openclaw` 当前对 `permission_reply` 仍为 fail-closed，现阶段方案的目标是把该路径替换为基于 `exec approvals` 的确定性实现。

## 2. 宿主事实与边界

### 2.1 已确认宿主能力

OpenClaw 网关已提供以下能力：

1. 审批请求创建后向具备 `operator.approvals` scope 的客户端广播 `exec.approval.requested`。
2. 审批客户端通过 `exec.approval.resolve` 提交 `allow-once | allow-always | deny`。
3. 决策成功后网关广播 `exec.approval.resolved`。

这意味着宿主层已经具备：

1. 感知权限请求
2. 提交权限授予/拒绝
3. 获取授予结果

三段式闭环，不需要 `message-bridge-openclaw` 自行发明审批协议。

### 2.2 现有扩展复用依据

`@openclaw/extensions` 中现有实现已经验证该闭环可用：

1. Telegram 审批处理器订阅 `exec.approval.requested` / `exec.approval.resolved`。
2. Discord 审批处理器同样订阅上述事件，并通过网关客户端调用 `exec.approval.resolve`。
3. `/approve` 命令路径也已落到 `exec.approval.resolve`，说明宿主对“按钮/命令/聊天面”的决策入口已经统一。

因此本插件应复用宿主既有模型，而不是再设计一套桥接内审批总线。

### 2.3 Plugin SDK 边界

本阶段需要明确一个重要边界：

1. OpenClaw 插件 API 当前暴露了 `registerGatewayMethod`、`registerCommand`、`registerService`、`runtime.events` 等通用能力。
2. 但 Plugin SDK 并未提供一个显式的、面向外部插件的“审批事件订阅 + 审批决策提交”高级封装。
3. 现有 Telegram/Discord 审批能力主要依赖宿主内部网关客户端与审批事件，不是通过一个统一的 `plugin-sk` 审批接口完成。

因此对 `message-bridge-openclaw` 的落地原则是：

1. 文档语义上依赖宿主 `exec approvals`
2. 实现上允许通过现有 OpenClaw 运行时与网关能力接入
3. 不要求本阶段先补一个新的通用 Plugin SDK 审批抽象

## 3. 实现路径

### 3.1 下行动作处理

入口仍固定为 `invoke.permission_reply`。

入参固定校验：

1. `toolSessionId` 非空字符串
2. `permissionId` 非空字符串
3. `response` 属于 `once | always | reject`

校验失败统一输出：

1. `tool_error`
2. `error` 文本应稳定包含可判定语义（例如 `invalid_payload`）
3. 可选诊断维度写入插件日志，不作为公共 wire 字段

### 3.2 决策映射

固定映射表：

| ai-gateway `response` | OpenClaw decision |
| --- | --- |
| `once` | `allow-once` |
| `always` | `allow-always` |
| `reject` | `deny` |

映射层不引入二义性，不做语义推断。

### 3.3 `permissionId` 与 `approvalId` 映射

冻结为 opaque passthrough：

1. `permissionId` 直接作为 `exec.approval.resolve.id`
2. 插件不改写、不编码、不生成映射缓存
3. 插件只负责校验该 `permissionId` 是否属于当前 `toolSessionId`

这与需求文档中的职责边界保持一致。

### 3.4 决策提交通道

`message-bridge-openclaw` 在收到合法的 `invoke.permission_reply` 后，调用宿主审批决议通道：

1. method: `exec.approval.resolve`
2. params:
   - `id = permissionId`
   - `decision = allow-once | allow-always | deny`

成功路径默认行为：

1. 不新增新的桥接传输消息类型
2. 不额外发送 `tool_done`
3. 仅依赖宿主后续产生的审批结果事件完成上游状态收敛

这个选择与 `message-bridge` 当前文档口径一致，也避免在“提交成功但状态未完成传播”时制造双重语义。

## 4. 上行事件投影

### 4.1 宿主事件到桥接事件

本阶段将宿主审批事件统一投影为 `tool_event`：

1. `exec.approval.requested` -> `tool_event(event.type=permission.asked)`
2. `exec.approval.resolved` -> `tool_event(event.type=permission.updated)`

### 4.2 最小字段约束

`tool_event.event.properties` 至少包含：

1. `sessionID`
2. `id`
3. `status`

建议附加字段：

1. `decision`
2. `sourceEvent`
3. `expiresAt`
4. `resolvedBy`

其中：

1. `id` 对应 `permissionId`
2. `sourceEvent` 用于标明宿主真实来源，如 `exec.approval.requested` / `exec.approval.resolved`

### 4.3 事件链路目标

桥接侧对外闭环目标固定为：

`permission.asked -> invoke.permission_reply -> permission.updated`

这里的 `permission.updated` 是桥接投影后的稳定对外语义，不要求上游感知 OpenClaw 的内部事件名。

## 5. 状态模型与幂等

插件维护最小状态，仅用于会话归属校验、幂等和过期判断。

最小字段冻结为：

1. `toolSessionId`
2. `permissionId`
3. `status: pending | resolved | expired`
4. `expiresAt?`
5. `resolvedAt?`

处理规则：

1. 收到 `exec.approval.requested` 时创建 `pending`
2. 收到 `exec.approval.resolved` 时转为 `resolved`
3. 审批超时或宿主返回 unknown/expired 时转为 `expired`
4. 同一 `permissionId` 在 `resolved` 后重复提交固定返回 `permission_already_resolved`

插件不承担：

1. `permissionId` 全局唯一性保障
2. 宿主审批生命周期托管
3. 多副本间一致性协调

## 6. 错误模型

固定错误码集合：

1. `invalid_payload`
2. `permission_not_found`
3. `permission_expired`
4. `permission_already_resolved`
5. `permission_resolve_failed`
6. `permission_session_mismatch`

错误分类规则冻结如下（用于实现内映射与日志观测）：

| 场景 | 条件 | `errorClass`（内部） |
| --- | --- | --- |
| 入参非法 | 缺字段、空字符串、非法 `response` | `invalid_payload` |
| 本地无记录 | 未找到 `toolSessionId + permissionId` pending 关联 | `permission_not_found` |
| 宿主返回未知或已过期 | `exec.approval.resolve` 返回 unknown/expired 类错误 | `permission_expired` |
| 重复提交 | 本地状态已是 `resolved` | `permission_already_resolved` |
| 会话错配 | `permissionId` 属于其他 `toolSessionId` | `permission_session_mismatch` |
| 决策提交失败 | 网关调用失败、参数校验失败、不可恢复 RPC 错误 | `permission_resolve_failed` |

端到端错误最小契约保持不变：

```json
{
  "type": "tool_error",
  "toolSessionId": "string",
  "welinkSessionId": "string",
  "error": "string"
}
```

`errorCode`、`action` 不作为公共 wire 字段透传；对外保持标准 `tool_error` 最小契约，诊断信息写日志与内部观测。

## 7. 非目标与降级

### 7.1 本阶段非目标

1. `question_reply` 实现
2. OpenClaw 通用 `permission.*` 抽象
3. Plugin SDK 新增通用审批 helper
4. 多端审批 UI 统一抽象

### 7.2 降级策略

若以下任一条件不成立：

1. 无法可靠调用 `exec.approval.resolve`
2. 无法稳定接收 `exec.approval.requested` / `exec.approval.resolved`
3. 无法建立 `toolSessionId + permissionId` 最小状态闭环

则保持当前 fail-closed：

1. `tool_error`
2. `error` 包含 `unsupported_in_openclaw_v1`
3. `error=unsupported_in_openclaw_v1:permission_reply`

## 8. 实施任务映射

1. 桥接入口层：将 `permission_reply` 从 `sendUnsupported(...)` 替换为真实处理分支。
2. 宿主接入层：接入 OpenClaw 审批决议提交能力，调用 `exec.approval.resolve`。
3. 状态同步层：订阅宿主审批请求/结果事件，维护最小状态机。
4. 上行投影层：把宿主审批事件标准化为 `tool_event(permission.asked/permission.updated)`。
5. 错误收敛层：把宿主 unknown/expired/ambiguous/RPC error 映射为冻结错误码集合。
6. 测试层：覆盖成功、重复提交、过期提交、会话错配、非法 payload 五类场景。

## 9. 验收门禁

1. `invoke.permission_reply` 成功提交时可稳定触发宿主 `exec.approval.resolve`
2. 三种 `response` 的决策映射一致率为 `100%`
3. 宿主审批请求能稳定投影为 `permission.asked`
4. 宿主审批结果能稳定投影为 `permission.updated`
5. 非法、重复、过期、错配输入全部 fail-closed 收敛为结构化 `tool_error`

## 10. 追溯关系

1. `README` 记录专题入口与阶段状态
2. `implementation-plan` 记录阶段任务与门禁
3. `mb-p0-permission-bridge-requirements.md` 记录需求冻结口径
4. 本文档记录“基于已确认 OpenClaw 宿主能力”的实现路径冻结口径
