# OpenCode-CUI Message-Bridge 插件需求文档（PRD）

**Version:** v1.4  
**Date:** 2026-03-06  
**Status:** 冻结稿  
**Owner:** message-bridge maintainers  
**Related:** `../architecture/overview.md`, `../quality/test-strategy.md`  
**实现目录**: `/Users/zy/Code/opencode/opencode-CUI/plugins/message-bridge`

## 一、摘要
本需求文档定义 `message-bridge` 插件首版交付范围、协议契约、扩展机制、验收标准与外部依赖。  
插件为全新实现（非 `pc-agent` 二次开发），但兼容现有 `pc-agent <-> ai-gateway` 外边界。  
本次仅实现插件，不改 `ai-gateway`、`skill-server` 业务代码。

## 二、目标与范围
### 2.1 目标
1. 实现 OpenCode 原生插件与 `ai-gateway` 的稳定桥接。
2. 事件上行与 action 下行具备可扩展机制。
3. 长期对齐 `@opencode-ai/sdk` 的 SSE 事件与 REST 接口语义。

### 2.2 In Scope
1. WS 鉴权连接、心跳、重连。
2. 下行 `invoke/status_query` 与上行协议消息。
3. 可配置白名单与 action 注册表。
4. `permission_reply` 双字段兼容。
5. `close_session -> session.abort` 固化。
6. 配置文件获取与校验（参考 bridge）。
7. 插件级单测/集成/最小 E2E。

### 2.3 Out of Scope
1. Gateway/Skill-Server 业务代码改造。
2. 服务端幂等去重实现。
3. 监控平台接入与告警系统建设。
4. 多平台 adapter、webhook、文件媒体桥接、slash 命令体系。

## 三、设计原则
1. 透明透传优先：事件主体不做业务改写。  
2. 可扩展优先：事件与 action 不写死在核心引擎。  
3. SDK 对齐优先：以 `@opencode-ai/sdk` 定义为长期目标。  
4. 差异可追踪：现网兼容项必须有收敛计划与版本归属。

## 四、外部边界契约
### 4.1 网关连接
- Endpoint：`/ws/agent`
- 鉴权 query：`ak/ts/nonce/sign`
- 鉴权容忍窗口由 Gateway 定义；插件不定义 `auth-tolerance` 参数。

### 4.2 下行（Gateway -> 插件）
- `invoke`
- `status_query`

### 4.3 上行（插件 -> Gateway）
- `register`
- `heartbeat`
- `tool_event`
- `tool_done`
- `tool_error`
- `session_created`
- `status_response`

### 4.4 Envelope（统一要求）
所有上行业务消息必须携带 envelope：
- `version`
- `messageId`
- `timestamp`
- `source`
- `agentId`
- `sessionId`（业务消息建议必填；`status_response` 场景可选）
- `sequenceNumber`
- `sequenceScope`

`status_response` 也必须携带 envelope。

### 4.5 agentId 绑定规则（P0）
1. 插件建立 WS 后发送 `register`。  
2. 当前实现中，Gateway 无显式 `register_success` 响应；发送 `register` 后进入 `READY`。  
3. `READY` 前不发送带 envelope 的业务消息。  
4. 当前实现状态映射：`DISCONNECTED/CONNECTING -> tool_error(code=GATEWAY_UNREACHABLE)`，`CONNECTED -> tool_error(code=AGENT_NOT_READY)`。  
5. 当前实现 `envelope.agentId` 使用插件本地生成的 `localAgentId`（如 `bridge-{uuid}`）。  
6. 连接重建后必须重新注册并重新生成 `agentId`，不复用旧值。  
7. 目标态为服务端分配 `gatewayAgentId` 并显式确认，作为后续收敛项（见 `TODO-MB-001`）。  

## 五、功能需求（FR）
### FR-MB-01（P0）网关连接与鉴权
建立并维护与 Gateway 的 WS 长连接，完成签名鉴权。

### FR-MB-02（P0）事件上行可扩展机制
- 采用“前缀 + 精确匹配”的可配置 allowlist。  
- 默认允许：
- `message.*`
- `permission.*`
- `session.*`
- `file.edited`
- `todo.updated`
- `command.executed`
- 不支持事件默认丢弃并记录 `unsupported_event`。

### FR-MB-03（P0）action 下行可扩展机制
- 采用 Action Registry。  
- 每个 action 必须定义：`validator`、`executor`、`errorMapper`。  
- 新 action 接入不得修改连接与核心转发引擎。

### FR-MB-04（P0）基础 action 支持
- `chat`
- `create_session`
- `close_session`
- `permission_reply`
- `status_query`

### FR-MB-05（P0）关闭语义
`close_session` 固定映射 `session.abort`，不执行 `session.delete`。

### FR-MB-06（P0）权限回复兼容
- 目标字段：`response: allow|always|deny`
- 兼容字段：`approved: boolean`
- 兼容映射：
- `approved=true -> allow`
- `approved=false -> deny`
- SDK 映射：
- `allow -> once`
- `always -> always`
- `deny -> reject`

### FR-MB-07（P0）Fast Fail（量化）
不可达定义：
1. Gateway 不可达：`connectionState in {DISCONNECTED, CONNECTING}`
2. OpenCode 不可达：SDK 调用超时或连接异常

时限：
- SDK 单次调用超时默认 `10s`（可配置）

行为：
1. 对每个 invoke，在 `<=100ms` 内完成连接态判定。  
2. 不可达时立即回传 `tool_error`（含 `code/error/sessionId/envelope`），采用 best effort 发送。  
3. 若发送失败，记录本地结构化日志并累计错误计数。  
4. 不排队、不缓冲 invoke。  
5. 连接层继续重连，不退出进程。  

### FR-MB-08（P1）注册与状态查询
- 连接成功发送 `register(deviceName, os, toolType, toolVersion)`  
- `status_query.sessionId` 可选  
- `status_response` 返回 `opencodeOnline:boolean` + envelope，`sessionId` 可选并按请求透传

### FR-MB-09（P0）配置文件获取（参考 bridge）
配置发现必须支持：
- 用户级：`~/.config/opencode/message-bridge.jsonc`
- 项目级：`<workspace>/.opencode/message-bridge.jsonc`
- 环境变量：`BRIDGE_*`
- 默认回退

优先级：
`env > project > user > default`

`<workspace>` 定义：
1. 优先 `ctx.projectRoot`
2. 若无则 `process.cwd()`
3. 不向上递归搜索 git root

配置治理要求：
- 支持 JSONC（注释、尾逗号）
- `config_version=1` 校验
- 结构化错误：`path/code/message`
- 敏感字段脱敏
- `enabled=false` 时安全禁用

### FR-MB-10（P1）SDK 对齐映射表治理
文档维护 `GatewayAction/Event <-> SDK 方法/事件` 对照表，状态：
- `aligned`
- `compatible`
- `legacy`

## 六、非功能需求（NFR）
### NFR-MB-01 可用性
- 连接可用性 >= 99.9%
- invoke 成功率 >= 99.5%

### NFR-MB-02 稳定性默认参数
- `heartbeatIntervalMs=30000`
- `reconnectBaseMs=1000`
- `reconnectMaxMs=30000`
- 指数退避，最大 30s

### NFR-MB-03 安全
- `sk`、签名原文、敏感鉴权参数不得落日志
- 配置与运行错误输出需脱敏

### NFR-MB-04 告警建议阈值
- 5 分钟失败率 > 5% 告警（平台接入为后续任务）

## 七、类型与错误模型冻结
- `InvokeAction = chat | create_session | close_session | permission_reply | status_query`
- `PermissionReplyPayload(target) = { permissionId, toolSessionId?, response }`
- `PermissionReplyPayload(compat) = { permissionId, toolSessionId?, approved }`
- `StatusResponse = { type: 'status_response', opencodeOnline: boolean, sessionId?: string, envelope }`
- `tool_error = { type, sessionId?, code, error, envelope }`

错误码最小集：
- `GATEWAY_UNREACHABLE`
- `SDK_TIMEOUT`
- `SDK_UNREACHABLE`
- `AGENT_NOT_READY`
- `INVALID_PAYLOAD`
- `UNSUPPORTED_ACTION`

## 八、SDK 对齐策略
### 8.1 目标
与 `@opencode-ai/sdk` SSE/REST 定义逐步收敛。

### 8.2 现状兼容
允许保留现网别名与兼容字段（如 `approved`）。

### 8.3 收敛机制
每个差异项必须记录：
- 当前行为
- 目标行为
- owner
- 目标版本
- 退场条件

实现口径与验收口径以以下文档为准：
- `../architecture/overview.md`（架构口径）
- `../quality/test-strategy.md`（测试口径）

## 九、测试与验收标准
### 9.1 测试分层
1. Unit：白名单、映射、路由、错误分支、envelope/sequence  
2. Integration：Mock Gateway WS + Mock SDK Client  
3. E2E Smoke：注册、心跳、create+chat+close、permission_reply、断连重连、不可达启动失败  

### 9.2 必测场景
1. 五类 action 正常链路。  
2. 白名单允许/拒绝路径。  
3. `approved/response` 双字段兼容。  
4. `close_session -> abort` 且不 delete。  
5. Fast Fail 返回 `tool_error`。  
6. envelope 完整与 sequence 递增。  
7. `status_response` envelope 一致性。  
8. 配置发现/优先级/JSONC/version 校验。  
9. 新增事件或 action 不改核心引擎的扩展性验证。  
10. 状态到错误码映射一致：`DISCONNECTED/CONNECTING -> GATEWAY_UNREACHABLE`，`CONNECTED -> AGENT_NOT_READY`。  

### 9.3 质量门槛
- `typecheck` 通过  
- unit/integration/e2e smoke 全通过  
- 覆盖率（插件目录）：
- `lines >= 80%`
- `branches >= 70%`

## 十、兼容矩阵
- 策略：已验证矩阵 + 主版本范围  
- 已验证基线：`@opencode-ai/sdk@1.2.15`  
- 主版本范围：`1.2.x`（其他小版本需回归验证）

## 十一、外部依赖（后续，不阻塞本次）
1. Skill-Server `approved -> response` 统一迁移  
2. Gateway/Skill-Server 幂等去重落地  
3. 监控平台接入  
4. 打包分发与 CI/CD 自动化  

## 十二、风险与回滚
### 12.1 风险
1. 兼容层保留期过长导致复杂度上升  
2. 网关协议演进引发插件兼容回归  

### 12.2 回滚
1. 插件回退至上一稳定版本  
2. 保持后端协议不变，仅插件回滚  
3. 保留失败日志用于根因分析  

## 十三、显式假设
1. 插件无状态，不承担业务持久化。  
2. 幂等与一致性由服务端负责。  
3. 首版仅网关桥接，不扩展多平台能力。  
4. 本文档为后续架构设计、方案设计、测试构建的唯一基线，变更需评审。  
