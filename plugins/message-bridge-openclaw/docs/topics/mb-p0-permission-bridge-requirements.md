# P0 阶段四：permission_reply 能力补齐需求（OpenClaw 映射版）

**需求标识**: `FR-MB-OPENCLAW-P0-PERMISSION-BRIDGE`  
**文档版本**: `v1.1`  
**日期**: `2026-03-16`  
**责任范围**: `message-bridge-openclaw` 插件（文档层定义）

## 1. 背景与现状

当前 `message-bridge-openclaw` 已能解析并接收 `invoke.permission_reply`，但执行路径仍是 fail-closed（`tool_error(unsupported_in_openclaw_v1)`）。

已确认的关键事实：

1. OpenClaw 侧存在可用的授权决策能力：`exec approvals`，并有明确请求/决议通道（`exec.approval.requested` / `exec.approval.resolve`）。
2. `message-bridge-opencode-plugin`（飞书侧）已验证“用户回复 -> v2优先 + v1回退”的权限回传模式，可作为交互与兼容策略参考。
3. 对 `message-bridge-openclaw` 的本阶段落地，应优先采用可执行的 `exec approvals` 映射，不在本阶段扩展为通用 `permission.*` 抽象。
4. 对齐 `message-bridge <-> ai-gateway` 现行协议时，权限状态通常以 `tool_event` 承载业务事件（如 `permission.asked / permission.updated`），而不是新增传输消息类型。
5. `message-bridge-openclaw` 当前尚未实现权限状态上行投影，阶段四需补齐完整闭环事件链路。

## 2. 目标与范围

### 2.1 本阶段目标

在不改变 ai-gateway 现有下行协议的前提下，补齐 `permission_reply` 的可执行闭环：  
`invoke.permission_reply` -> 映射 -> OpenClaw 审批决策提交 -> 稳定结果/错误回传。

### 2.2 范围内

1. `invoke.permission_reply` 入参校验与规范化。
2. `permissionId` 透传到 OpenClaw `approvalId` 的解析规则（opaque passthrough）。
3. `response` 到 OpenClaw 决策值的语义映射。
4. 决策提交失败场景的稳定 `tool_error` 语义。
5. 最小可观测字段与验收指标定义。

### 2.3 非范围

1. `question_reply` 业务能力实现（继续 fail-closed）。
2. 通用 `permission.*` 抽象重构（仅记录为后续演进项）。
3. UI 交互细节与多端呈现设计。
4. pairing/security/messaging/directory/outbound 能力并行扩展。

### 2.4 职责边界（冻结）

1. OpenClaw 负责 `approvalId/permissionId` 的生成、唯一性与生命周期管理。
2. 插件不负责 `permissionId` 唯一性保障，不改写、不重分配、不二次编码。
3. 插件仅负责：
   - 协议透传（`permissionId` 直通）
   - 会话关联校验（`toolSessionId` + `permissionId`）
   - 错误收敛（统一 `tool_error`）

## 3. 协议与语义映射

### 3.1 入参（保持现状）

`invoke.permission_reply.payload`：

```json
{
  "toolSessionId": "string",
  "permissionId": "string",
  "response": "once | always | reject"
}
```

### 3.2 决策映射（必须一致）

| ai-gateway `response` | OpenClaw decision |
| --- | --- |
| `once` | `allow-once` |
| `always` | `allow-always` |
| `reject` | `deny` |

目标能力：调用 OpenClaw 审批决策通道（`exec.approval.resolve` 语义层）。

### 3.3 成功输出契约（冻结）

`permission_reply` 决策提交成功后，默认不额外发送新的上行业务报文（对齐现有 `message-bridge` 文档口径）。

约束：

1. 成功路径不返回 `tool_error`。
2. 成功路径不新增新的传输消息类型。
3. 如需 `tool_done` 兼容回执，必须作为显式兼容策略单独评估并默认关闭，不作为本阶段默认契约。

### 3.4 OpenCode 权限状态上行消息（协议对齐）

基线口径（参考 `message-bridge` 现行协议）：

1. 权限状态事件通过 `tool_event` 承载。
2. 事件类型必须覆盖：`permission.asked`、`permission.updated`。
3. 关键关联字段以 `event.properties` 提供，至少包含：
   - `sessionID`
   - `id`（或等价权限请求标识）

本阶段要求：

1. openclaw 插件需支持完整闭环可观测：`permission.asked -> invoke.permission_reply -> permission.updated`。
2. 若宿主输出的是 `permission.replied`，插件对外仍统一投影为 `permission.updated`，并可附加诊断字段 `sourceEvent=permission.replied`。

## 4. 关联约束

1. `permissionId` 为业务主键，按 opaque passthrough 直接作为 OpenClaw `approvalId` 使用，不引入映射缓存。
2. 不可解析场景（不存在/过期/已决议）必须返回稳定 `tool_error`，且包含：
   - `error`（端到端强保证）
   - `welinkSessionId?`、`toolSessionId?`（按现有协议可路由字段）
3. `errorCode` / `action` 不作为公共 wire 字段；如需诊断，统一记录在插件日志与内部观测字段中。
4. 重放幂等要求：同一 `permissionId` 重复提交不得触发二次副作用。  
   第二次固定返回 `tool_error(permission_already_resolved)`，且不污染 session 状态。
5. 任何异常路径均保持 fail-closed：必须收敛为 `tool_error`。
6. 插件仅维护最小状态，字段至少包含：`toolSessionId`、`permissionId`、`status(pending/resolved/expired)`、`resolvedAt?`、`expiresAt?`。
7. 最小状态仅用于幂等、防重放、会话隔离；不承担 `permissionId` 唯一性保障。

## 5. 错误模型（需求层）

本阶段要求稳定错误码集合至少覆盖：

1. `invalid_payload`
2. `permission_not_found`
3. `permission_expired`
4. `permission_already_resolved`
5. `permission_resolve_failed`
6. `permission_session_mismatch`

约束：

1. 错误码与错误文本分离，错误码可用于自动化判定。
2. 端到端最小错误契约遵循 `tool_error = { type, welinkSessionId?, toolSessionId?, error }`。
3. `tool_error` 对外仍遵循最小契约，不额外扩展 `action/errorCode` 公共字段。
4. 错误路径不得改变会话路由与会话状态机。

## 6. 验收指标（最小可执行）

1. 成功率：有效 `permission_reply` 在透传路径下决策成功率 `=100%`（联调样本集）。
2. 一致性：三种 `response` 与目标决策映射一致率 `=100%`。
3. 稳定性：非法/过期/重复输入均返回结构化 `tool_error`，且 session 状态无回归。
4. 可观测性：日志最少包含：
   - `toolSessionId`
   - `permissionId`
   - `decision`
   - `resolveResult`
   - `errorClass`（内部诊断分类）
   - `latencyMs`

门禁窗口补充：

1. 发布前在同一目标环境、固定模型与固定网关配置下，连续执行至少 30 个有效样本（`permission_reply`）作为验收样本集。
2. 样本集必须包含至少 1 个错误场景（重复/过期/会话错配任一），用于验证 fail-closed 收敛。

## 7. 追溯与联动

上游/关联文档：

1. `../../README.md` 专题入口（本专题）
2. `../implementation-plan.md` 阶段四任务索引（本专题 FR）
3. `./mb-p0-permission-bridge-solution.md` 对应方案专题

双向链路要求：

`README` -> `implementation-plan` -> `mb-p0-permission-bridge-requirements.md` -> `implementation-plan`

## 8. 后续演进项（非本阶段交付）

“通用 `permission API`”作为后续演进项记录：

1. 当 OpenClaw 提供稳定的通用权限接口时，再从当前 `exec approvals` 映射升级为通用实现。
2. 本条仅作为 roadmap，不纳入本轮验收与发布门禁。
