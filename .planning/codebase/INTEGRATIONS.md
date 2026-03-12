# 外部集成

## API 与外部服务

- **Miniapp → Skill Server REST API**
  - 调用入口在 `skill-miniapp/src/utils/api.ts`
  - 覆盖会话创建、消息发送、历史消息查询、权限回复、发送到 IM、在线 Agent 查询等接口
- **Miniapp → Skill Server WebSocket**
  - 连接地址为 `/ws/skill/stream`
  - 客户端实现位于 `skill-miniapp/src/hooks/useSkillStream.ts`
  - 服务端处理位于 `skill-server/src/main/java/com/opencode/cui/skill/ws/SkillStreamHandler.java`
- **Skill Server → AI-Gateway WebSocket**
  - 客户端实现位于 `skill-server/src/main/java/com/opencode/cui/skill/ws/GatewayWSClient.java`
  - 默认目标为 `ws://localhost:8081/ws/skill`
- **Skill Server → AI-Gateway REST**
  - 客户端封装在 `skill-server/src/main/java/com/opencode/cui/skill/service/GatewayApiClient.java`
  - 主要用途是查询在线 Agent 列表
- **PC Agent → AI-Gateway WebSocket**
  - 连接逻辑在 `src/main/pc-agent/GatewayConnection.ts`
  - 目标端点为 `/ws/agent`
- **PC Agent → OpenCode SDK**
  - 桥接实现位于 `src/main/pc-agent/EventRelay.ts` 与 `src/main/pc-agent/plugin.ts`
- **Skill Server → IM 平台 API**
  - 由 `skill-server/src/main/java/com/opencode/cui/skill/service/ImMessageService.java` 负责
  - 目标地址由 `IM_API_URL` 配置

## 数据存储

- **Gateway MySQL**
  - Agent 在线状态表：`ai-gateway/src/main/resources/db/migration/V1__gateway.sql`
  - AK/SK 凭据表：`ai-gateway/src/main/resources/db/migration/V2__ak_sk_credential.sql`
- **Skill Server MySQL**
  - 会话、消息主表：`skill-server/src/main/resources/db/migration/V1__skill.sql`
  - 消息 Part 表：`skill-server/src/main/resources/db/migration/V2__message_parts.sql`
- **Redis**
  - Gateway 侧实现：`ai-gateway/src/main/java/com/opencode/cui/gateway/service/RedisMessageBroker.java`
  - Skill Server 侧实现：`skill-server/src/main/java/com/opencode/cui/skill/service/RedisMessageBroker.java`
- **内存状态**
  - Gateway 活跃连接与本地路由状态保存在 `EventRelayService.java`、`SkillRelayService.java`
  - Skill Server 的流式缓冲与订阅状态保存在 `GatewayRelayService.java`、`SkillStreamHandler.java`、`MessagePersistenceService.java`

## 身份认证与鉴权

- **PC Agent 鉴权**
  - 签名生成：`src/main/pc-agent/AkSkAuth.ts`
  - 服务端校验：`ai-gateway/src/main/java/com/opencode/cui/gateway/service/AkSkAuthService.java`
  - 传输方式：WebSocket subprotocol，由 `src/main/pc-agent/GatewayConnection.ts` 发起
- **Skill Server → Gateway 内部鉴权**
  - Header 注入：`skill-server/src/main/java/com/opencode/cui/skill/ws/GatewayWSClient.java`
  - Header 校验：`ai-gateway/src/main/java/com/opencode/cui/gateway/ws/SkillWebSocketHandler.java`
- **Miniapp 用户身份**
  - 通过 `userId` Cookie 传递
  - 读取位置包括 `SkillSessionController.java`、`SkillMessageController.java`、`AgentQueryController.java`、`SkillStreamHandler.java`
  - 本地开发自动注入逻辑位于 `skill-miniapp/src/utils/devAuth.ts`
- **设备绑定与 Agent 身份**
  - 绑定逻辑由 `ai-gateway/src/main/java/com/opencode/cui/gateway/service/DeviceBindingService.java` 负责

## 可观测性

- 两套后端都使用 **文件日志**，配置在各自的 `application.yml` 中。
- 后端包级日志默认开启较高粒度调试，便于排查协议链路问题。
- PC Agent 有额外的本地调试文件日志，位于 `src/main/pc-agent/EventRelay.ts`。
- 仓库中**没有发现**外部 APM、Tracing、Metrics 平台集成。

## CI/CD 与部署

- 仓库中**没有发现** CI 工作流、Dockerfile、Compose、Kubernetes、Helm 等部署文件。
- 当前构建方式是模块级分散管理：
  - `ai-gateway`：Maven
  - `skill-server`：Maven
  - `skill-miniapp`：Vite + TypeScript
  - `src/main/pc-agent`：TypeScript + Bun 测试

## 环境配置

- **Gateway 关键环境变量**
  - `MYSQL_HOST`
  - `MYSQL_PORT`
  - `MYSQL_AI_GATEWAY_DB`
  - `GATEWAY_INSTANCE_ID`
  - `SKILL_SERVER_INTERNAL_TOKEN`
  - `OPENCODE_LOG_DIR`
- **Skill Server 关键环境变量**
  - `MYSQL_HOST`
  - `MYSQL_PORT`
  - `MYSQL_SKILL_DB`
  - `MYSQL_USERNAME`
  - `MYSQL_PASSWORD`
  - `REDIS_HOST`
  - `REDIS_PORT`
  - `REDIS_DATABASE`
  - `REDIS_PASSWORD`
  - `SKILL_GATEWAY_WS_URL`
  - `IM_API_URL`
  - `CORS_ORIGINS`
- **Miniapp 关键环境变量**
  - `VITE_SKILL_SERVER_URL`
  - `VITE_SKILL_SERVER_WS`
  - `VITE_SKILL_USER_ID`

## Webhook 与回调

- 本项目没有典型意义上的第三方 Webhook 集成。
- 取而代之的是内部事件回调链：
  - Gateway → Skill Server 的 WebSocket 回推
  - Skill Server → Miniapp 的 WebSocket 流式推送
  - Miniapp → Skill Server 的权限回复 API
  - Skill Server → IM 平台的发送接口

*映射时间：2026-03-11。*
