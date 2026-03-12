# 架构

## 架构模式概览

- 这是一个**四段式桥接系统**：`skill-miniapp` 负责用户交互，`skill-server` 负责会话与消息编排，`ai-gateway` 负责 Agent 连接与路由，`src/main/pc-agent` 负责本地 OpenCode 适配。
- 系统核心是**事件驱动 + 协议转换**：用户请求被封装成 invoke 指令，OpenCode 事件被翻译成统一的 `StreamMessage`，同时落库并实时推送到前端。
- 两个 Java 服务都遵循典型的 **Spring 分层架构**：
  - 接入层：`controller/`、`ws/`
  - 业务层：`service/`
  - 持久层：`repository/` + XML Mapper
  - 配置层：`config/`
- 前端采用 **UI + Hook + Protocol Adapter** 的组织方式。
- PC Agent 采用 **本地插件适配层** 模式，负责把 OpenCode SDK 的上下游协议接到 Gateway。

## 分层

- **Gateway（`ai-gateway`）**
  - WebSocket 接入：`ai-gateway/src/main/java/com/opencode/cui/gateway/ws/AgentWebSocketHandler.java`
  - Skill Server 反向链路接入：`ai-gateway/src/main/java/com/opencode/cui/gateway/ws/SkillWebSocketHandler.java`
  - REST API：`ai-gateway/src/main/java/com/opencode/cui/gateway/controller/AgentController.java`
  - 核心编排：`AgentRegistryService.java`、`EventRelayService.java`、`SkillRelayService.java`
  - 持久化：`repository/` + `src/main/resources/mapper/`
- **Skill Server（`skill-server`）**
  - REST API：`SkillSessionController.java`、`SkillMessageController.java`、`AgentQueryController.java`
  - 前端 WebSocket：`SkillStreamHandler.java`
  - Gateway 回连：`GatewayWSClient.java`
  - 核心编排：`GatewayRelayService.java`、`SkillSessionService.java`、`SkillMessageService.java`、`MessagePersistenceService.java`、`OpenCodeEventTranslator.java`
  - 持久化：`repository/`、XML Mapper、迁移脚本
- **Miniapp（`skill-miniapp`）**
  - 启动入口：`skill-miniapp/src/main.tsx`、`skill-miniapp/src/app.tsx`
  - 页面层：`skill-miniapp/src/pages/`
  - 状态与副作用：`skill-miniapp/src/hooks/`
  - 协议适配：`skill-miniapp/src/protocol/`
  - 渲染组件：`skill-miniapp/src/components/`
- **PC Agent（`src/main/pc-agent`）**
  - 插件入口：`plugin.ts`
  - 鉴权：`AkSkAuth.ts`
  - 连接管理：`GatewayConnection.ts`
  - 事件筛选与转译：`EventFilter.ts`、`EventRelay.ts`、`PermissionMapper.ts`
  - 健康检查：`HealthChecker.ts`

## 数据流

- **1. 创建会话**
  - Miniapp 通过 `skill-miniapp/src/utils/api.ts` 调用 `POST /api/skill/sessions`
  - `SkillSessionController.java` 调用 `SkillSessionService.java`
  - 会话元数据落到 MySQL，成为后续流式消息的归属对象
- **2. 发送用户消息**
  - Miniapp 调用 `POST /api/skill/sessions/{id}/messages`
  - `SkillMessageController.java` 将请求交给 `GatewayRelayService.sendInvokeToGateway(...)`
  - `GatewayWSClient.java` 通过 `/ws/skill` 把 invoke 发往 AI-Gateway
  - Gateway 由 `SkillWebSocketHandler.java` 接收，再经 `SkillRelayService.java` 分发到目标 Agent
  - `EventRelayService.java` 最终把消息送往 `/ws/agent` 上的 PC Agent
- **3. OpenCode 事件回流**
  - PC Agent 在 `src/main/pc-agent/EventRelay.ts` 中接收 SDK 事件并上报 Gateway
  - Gateway 在 `AgentWebSocketHandler.java` 中接收事件
  - `EventRelayService.java` 把事件转发给对应 Skill Server 实例
  - Skill Server 在 `GatewayRelayService.handleGatewayMessage(...)` 中处理原始消息
  - `OpenCodeEventTranslator.java` 把原始事件翻译成统一的 `StreamMessage`
  - `MessagePersistenceService.java` 负责落库与消息上下文维护
  - `SkillStreamHandler.java` 把标准化后的流式事件推送给 Miniapp
- **4. 断线恢复**
  - Miniapp 重连 `/ws/skill/stream` 后可以发送 `{"action":"resume"}`
  - `SkillStreamHandler.java` 会从数据库历史消息与 `StreamBufferService` 组合恢复当前状态
  - 前端先收到 `snapshot`，再收到仍在流式中的 `streaming` / 增量事件
- **5. 多实例路由**
  - Gateway 和 Skill Server 都通过 Redis 进行多实例协同
  - Gateway 的归属选择由 `SkillRelayService.java` 维护
  - Skill Server 的会话广播与恢复请求由 `GatewayRelayService.java` 管理

## 核心抽象

- **`GatewayMessage`**：位于 `ai-gateway/src/main/java/com/opencode/cui/gateway/model/GatewayMessage.java`，是 Gateway、Skill Server、PC Agent 之间的统一传输包。
- **`StreamMessage`**：位于 `skill-server/src/main/java/com/opencode/cui/skill/model/StreamMessage.java`，是 Skill Server 推给前端的标准化事件结构。
- **`SkillSession` / `SkillMessage` / `SkillMessagePart`**：分别描述会话、消息和消息分片的持久化结构。
- **`AgentConnection`**：描述 Gateway 侧在线 Agent 注册状态。
- **`GatewayConnection`**：位于 `src/main/pc-agent/GatewayConnection.ts`，负责 PC Agent 的长连接与重连。
- **`StreamAssembler`**：位于 `skill-miniapp/src/protocol/StreamAssembler.ts`，负责前端拼接增量消息。

## 入口点

- Gateway 启动入口：`ai-gateway/src/main/java/com/opencode/cui/gateway/GatewayApplication.java`
- Skill Server 启动入口：`skill-server/src/main/java/com/opencode/cui/skill/SkillServerApplication.java`
- Miniapp 入口：`skill-miniapp/src/main.tsx`
- PC Agent 入口：`src/main/pc-agent/plugin.ts`
- WebSocket 入口：
  - Gateway：`/ws/agent`、`/ws/skill`
  - Skill Server：`/ws/skill/stream`

## 错误处理

- REST 接口统一用 `ApiResponse` + `ResponseEntity` 返回错误。
- WebSocket 处理逻辑以**记录日志 + 关闭非法连接 / 忽略非法消息**为主，避免单条脏数据拖垮服务。
- `GatewayWSClient.java` 内置指数退避重连。
- `SkillStreamHandler.java` 会把重连中、离线等状态转成前端可消费的流式状态事件。
- Miniapp Hook 不直接把网络异常抛给组件，而是落在 Hook 状态上。
- PC Agent 在 `GatewayConnection.ts` 中通过 `error`、`rejected` 等事件上抛连接异常。

## 横切关注点

- **ID 贯穿全链路**：`userId`、`ak`、`toolSessionId`、`welinkSessionId`、`messageId`、`partId` 是跨模块的关键上下文字段。
- **顺序保证**：`SequenceTracker.java`、`SkillStreamHandler.java`、前端 `history.ts` / `StreamAssembler.ts` 共同承担顺序与恢复语义。
- **边落库边推流**：`MessagePersistenceService.java` 与 `GatewayRelayService.java` 共同实现“实时显示 + 可恢复历史”双目标。
- **重连语义**：客户端和服务端都实现了重连逻辑，说明协议天然按“长连接中断是常态”来设计。
- **日志追踪**：Java 侧日志普遍带 `userId`、`ak`、`sessionId`、`wsId` 等标识，便于跨服务排查。

*映射时间：2026-03-11。*
