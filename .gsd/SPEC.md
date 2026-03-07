# SPEC.md — Project Specification

> **Status**: `FINALIZED`

## Vision

将本地运行的 AI 编程助手 OpenCode 集成到企业 IM 协同平台中，让用户通过群聊中的技能触发和小程序式对话界面，远程访问自己桌面上的 OpenCode 实例。系统通过 AK/SK 安全认证、WebSocket 双向长连接、事件驱动的流式协议，在保证数据安全的前提下实现实时 AI 对话体验。

## Goals

1. **安全接入**：PC Agent 通过 AK/SK 签名认证与 AI-Gateway 建立长连接，实现 OpenCode 实例的安全远程接入
2. **实时对话**：用户通过 Web UI（skill-miniapp）与本地 OpenCode 进行实时流式对话，支持完整的 OpenCode 协议（文本、思维链、工具调用、权限审批、AI 提问等 17 种消息类型）
3. **会话管理**：支持多会话（Session）创建、切换、隔离，按群聊维度隔离聊天记录
4. **消息持久化**：AI 对话消息（含 Part 级别结构化数据）持久化到 MySQL，支持历史消息查询
5. **断线恢复**：WebSocket 断线后通过 Redis 缓冲 + 三阶段恢复机制无缝续接
6. **IM 推送**：用户可选择 OpenCode 的回答发送到 IM 群聊消息流中
7. **多实例部署**：Gateway 和 Skill Server 支持多实例水平扩展，通过 Redis Pub/Sub 实现跨实例路由

## Non-Goals (Out of Scope)

- IM 客户端（类钉钉/飞书）的开发
- 小程序容器和小程序 UI 的开发（客户端团队负责）
- AK/SK 创建和应用管理界面（已由其他服务实现）
- 本地技能列表查询接口（依赖外部服务提供）
- OpenCode 自身的功能开发
- 移动端适配

## Users

- **主要用户**：在 IM 平台上创建了应用并配置了 AK/SK 的开发者/知识工作者
- **使用方式**：用户在 IM 群聊中通过 "/" 触发技能选择 → 填写问题 → 生成小程序/Web UI 界面 → 与自己本地的 OpenCode 实时对话
- **用户模型**：一个 AK/SK 对应一个用户、一个 OpenCode 实例；仅应用创建者可使用（非群内所有成员）

## System Boundary

本项目团队负责的组件：

| 组件                       | 类型                  | 职责                                                                            |
| -------------------------- | --------------------- | ------------------------------------------------------------------------------- |
| **AI-Gateway**             | Java/Spring Boot 服务 | WebSocket 网关、AK/SK 认证、Agent 注册、事件双向中继、多实例路由                |
| **Skill Server**           | Java/Spring Boot 服务 | 会话管理、消息持久化、OpenCode 事件翻译、流式缓冲、IM 推送、前端 WebSocket 推流 |
| **PC Agent**               | TypeScript/Bun 插件   | OpenCode 插件，桥接 OpenCode SDK 与 Gateway                                     |
| **Skill Miniapp (Web UI)** | React/Vite 前端       | IM 风格对话界面，用于联调和验证（等效于小程序能力）                             |
| **Test Simulator**         | React/Vite 前端       | E2E 集成测试工具                                                                |

外部依赖（不在我们范围内）：

| 依赖                | 提供方     | 交互方式                                   |
| ------------------- | ---------- | ------------------------------------------ |
| IM 客户端 + 小程序  | 客户端团队 | 小程序调用 Skill Server REST/WebSocket API |
| 应用管理/AK SK 生成 | 平台服务   | 数据库共享 AK/SK 凭证                      |
| 本地技能查询        | 外部服务   | REST API                                   |
| OpenCode            | 本地桌面   | OpenCode SDK                               |

## Constraints

- **技术栈**：后端 Java 21 / Spring Boot 3.4.6 / Maven；前端 React + TypeScript + Vite
- **数据存储**：MySQL (持久化) + Redis (缓存/Pub/Sub)
- **通信协议**：WebSocket 双向长连接，JSON 消息格式
- **部署**：Gateway 和 Skill Server 支持多实例部署（ALB + Redis Pub/Sub 路由）
- **安全**：AK/SK HMAC-SHA256 签名认证 + 内部 Token 认证
- **协议兼容**：必须支持 OpenCode 完整事件协议（31 Event types, 12 Part types → 17 种 StreamMessage）

## Success Criteria

- [ ] PC Agent 通过 AK/SK 认证与 Gateway 建立稳定 WebSocket 连接
- [ ] 用户在 Web UI 上发送消息，OpenCode 回答实时流式渲染（text.delta、thinking、tool 等）
- [ ] 支持 OpenCode 所有交互类型：文本、工具调用、权限审批、AI 提问、思维链
- [ ] 会话创建、切换、列表查询功能完整
- [ ] 消息持久化到 MySQL，Web UI 可加载历史消息
- [ ] WebSocket 断线后自动重连并恢复流式状态
- [ ] 用户可选择一条 AI 回答发送到 IM 群聊（通过 IM 推送 API）
- [ ] Gateway 和 Skill Server 多实例部署下功能正常
