# Agent 认证与身份管理 文档包

> 版本：1.0  
> 日期：2026-03-09

本目录用于沉淀 PCAgent 认证方式改造与 Agent 身份持久化的完整设计文档。核心目标是将认证参数从 URL Query Parameter 迁移到 WebSocket 子协议，实施两阶段校验，保障单一活跃连接策略，并通过记录复用实现稳定的 Agent 身份。

## 锁定决策

- 认证参数通过 `Sec-WebSocket-Protocol: auth.{base64-json}` 传输，不暴露在 URL 中
- 两阶段校验：握手阶段验签 + register 阶段校验设备绑定和重复连接
- 重复连接策略固定为 **保留旧连接、拒绝新连接**（close code `4409`）
- Agent 身份持久化：同一 AK + toolType 复用已有 `agent_connection` 记录（UPDATE 而非 INSERT）
- 设备绑定校验默认关闭，不可用时降级放行（fail-open）
- 新增 10 秒注册超时机制

## 范围

本目录覆盖：

- PCAgent → Gateway WebSocket 认证改造
- 设备绑定校验（第三方服务集成）
- 重复连接检测与拒绝
- Agent 身份持久化与数据库迁移
- PC Agent 客户端协议适配

本目录不覆盖：

- Gateway 与 Skill Server 之间的路由（见 `gateway-skill-routing/`）
- Miniapp UI 改造
- OpenCode 事件协议变更（见 `stream-message-design/`）

## 文档索引

| 文件                                                                     | 内容                                   | 状态   |
| ------------------------------------------------------------------------ | -------------------------------------- | ------ |
| [01-current-auth-protocol.md](./01-current-auth-protocol.md)             | 当前认证实现现状、代码位置、已知问题   | 已完成 |
| [02-auth-design.md](./02-auth-design.md)                                 | 子协议认证设计、方案对比与选型         | 已完成 |
| [03-register-validation-design.md](./03-register-validation-design.md)   | 注册阶段三步校验、设备绑定、重复连接   | 已完成 |
| [04-identity-persistence-design.md](./04-identity-persistence-design.md) | Agent 身份持久化、数据库迁移、记录复用 | 已完成 |
| [05-implementation-plan.md](./05-implementation-plan.md)                 | 模块改造顺序、文件变更清单             | 已完成 |
| [06-requirements.md](./06-requirements.md)                               | 背景、目标、非目标、约束、验收标准     | 已完成 |
| [07-progress.md](./07-progress.md)                                       | 当前进展、已确认决策、风险、下一步     | 已完成 |
| [08-test-cases.md](./08-test-cases.md)                                   | 单测、集成、安全、部署验证用例         | 已完成 |

## 术语

| 术语       | 定义                                                   |
| ---------- | ------------------------------------------------------ |
| AK         | Access Key，PCAgent 用于认证的唯一标识符               |
| SK         | Secret Key，用于对请求签名的密钥                       |
| 子协议认证 | 通过 `Sec-WebSocket-Protocol` 头部传递认证信息的方式   |
| 设备绑定   | AK 与特定设备 MAC 地址 + toolType 的绑定关系           |
| 身份持久化 | 同一 AK + toolType 复用同一条 `agent_connection` 记录  |
| 注册超时   | WebSocket 连接建立后 10 秒内未发送 register 消息则断开 |

## 新增配置契约

| 配置项                                   | 所属服务   | 说明                                |
| ---------------------------------------- | ---------- | ----------------------------------- |
| `gateway.device-binding.enabled`         | ai-gateway | 是否启用设备绑定校验（默认 false）  |
| `gateway.device-binding.url`             | ai-gateway | 第三方设备绑定校验服务地址          |
| `gateway.device-binding.timeout-ms`      | ai-gateway | 设备绑定校验超时时间（默认 3000ms） |
| `gateway.agent.register-timeout-seconds` | ai-gateway | 注册超时时间（默认 10s）            |

## 自定义 WebSocket 关闭码

| 关闭码 | 含义             | PC Agent 行为 |
| ------ | ---------------- | ------------- |
| `4403` | 设备绑定校验失败 | 不自动重连    |
| `4408` | 注册超时         | 不自动重连    |
| `4409` | 重复连接被拒绝   | 不自动重连    |
