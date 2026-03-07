# ROADMAP.md

> **Current Phase**: Phase 1
> **Milestone**: v1.0 — Web UI 全功能联调版

## Must-Haves (from SPEC)

- [ ] PC Agent ↔ Gateway AK/SK 认证长连接
- [ ] Web UI 实时流式对话（支持 17 种 StreamMessage）
- [ ] 会话创建、切换、列表
- [ ] 消息持久化 + 历史加载
- [ ] 断线重连 + 状态恢复
- [ ] IM 推送（选择回答发送到群聊）
- [ ] 多实例部署支持

## Phases

### Phase 1: 端到端集成验证
**Status**: ⬜ Not Started
**Objective**: 验证现有代码的端到端能力，确认 PC Agent → Gateway → Skill Server → Web UI 全链路基本可用
**Requirements**: 确认现有 stream-message-design Phase 1-4 的实现与协议设计一致，修复集成中发现的问题
**Deliverable**: 一个可运行的端到端 demo，用户在 Web UI 上发消息 → OpenCode 回答流式渲染

### Phase 2: 会话管理完善
**Status**: ⬜ Not Started
**Objective**: 完善会话生命周期管理，支持多会话创建/切换/列表/隔离
**Requirements**: 会话 CRUD API、Web UI 会话选择器、按群聊维度隔离、同一时间单会话生效
**Deliverable**: Web UI 中可创建新会话、切换会话、查看会话列表

### Phase 3: Gateway 多实例路由改造
**Status**: ⬜ Not Started
**Objective**: 实现 gateway-skill-routing 文档包中设计的内部长连接方向调整和多实例路由
**Requirements**: Skill Server → ALB → Gateway 建链、owner 注册/心跳、Redis 定向中继、session sticky 路由
**Deliverable**: 双 Gateway 实例部署下功能正常

### Phase 4: IM 推送与 Web UI 打磨
**Status**: ⬜ Not Started
**Objective**: 实现 IM 消息推送能力，并打磨 Web UI 达到小程序等效体验
**Requirements**: 选择回答发送到 IM、Web UI 交互优化、错误处理、边界场景覆盖
**Deliverable**: Web UI 可完全替代小程序进行联调，IM 推送可用
