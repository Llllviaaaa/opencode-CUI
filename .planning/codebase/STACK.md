# 技术栈

## 编程语言

- **Java 21**：两套后端服务都基于 Java 21，代码分别位于 `ai-gateway/src/main/java/com/opencode/cui/gateway` 和 `skill-server/src/main/java/com/opencode/cui/skill`。
- **TypeScript**：前端小程序位于 `skill-miniapp/src`，PC Agent 插件位于 `src/main/pc-agent`。
- **SQL**：数据库结构通过迁移脚本维护，位于 `ai-gateway/src/main/resources/db/migration` 和 `skill-server/src/main/resources/db/migration`。
- **Markdown / PlantUML**：设计与实现文档集中在 `documents/`，属于项目的重要上下文，而不是简单归档资料。

## 运行时

- **Spring Boot 3.4.6**：
  - `ai-gateway` 默认监听 `8081`，配置见 `ai-gateway/src/main/resources/application.yml`
  - `skill-server` 默认监听 `8082`，配置见 `skill-server/src/main/resources/application.yml`
- **Vite + 浏览器运行时**：`skill-miniapp` 通过 Vite 启动开发环境，构建产物面向浏览器。
- **OpenCode 插件运行时**：`src/main/pc-agent` 作为本地插件桥接 OpenCode 与远端网关，测试使用 `bun test`，编译使用 `tsc`。
- **MySQL + Redis**：两套后端服务都依赖 MySQL 和 Redis，缺一不可。

## 框架

- **Spring Boot Web**：提供 REST API 能力。
- **Spring Boot WebSocket**：提供 Agent、Skill Server、Miniapp 三条实时链路。
- **MyBatis**：Repository 接口与 XML Mapper 组合用于持久化访问。
- **Lombok**：主要用于日志与样板代码简化。
- **React 18 + Vite 5**：小程序前端使用 React 18，构建与开发工具为 Vite。
- **OpenCode SDK**：PC Agent 通过 `@opencode-ai/sdk` 对接本地 OpenCode 能力。

## 关键依赖

- **`ai-gateway/pom.xml`**
  - `spring-boot-starter-web`
  - `spring-boot-starter-websocket`
  - `mybatis-spring-boot-starter`
  - `mysql-connector-j`
  - `spring-boot-starter-data-redis`
  - `jackson-databind`
- **`skill-server/pom.xml`**
  - 与 Gateway 相同的 Spring / MyBatis / MySQL / Redis 基础栈
  - `org.java-websocket:Java-WebSocket`，用于 `skill-server/src/main/java/com/opencode/cui/skill/ws/GatewayWSClient.java`
- **`skill-miniapp/package.json`**
  - `react`
  - `react-dom`
  - `react-markdown`
  - `remark-gfm`
  - `shiki`
- **`src/main/pc-agent/package.json`**
  - `@opencode-ai/sdk`
  - `ws`

## 配置方式

- **后端配置集中在 `application.yml`**：
  - `ai-gateway/src/main/resources/application.yml`
  - `skill-server/src/main/resources/application.yml`
- **前端运行时配置通过 Vite 环境变量注入**：
  - `skill-miniapp/src/utils/api.ts`
  - `skill-miniapp/src/hooks/useSkillStream.ts`
  - `skill-miniapp/src/utils/devAuth.ts`
- **日志目录** 通过配置项落盘到本地文件系统，默认写入 `OPENCODE_LOG_DIR` 指向的目录。
- 根目录 `package.json` 很薄，只负责引用本地 PC Agent 包：`file:./src/main/pc-agent`。

## 平台要求

- **Java 21 + Maven**：构建和运行 `ai-gateway` 与 `skill-server` 所需。
- **Node.js + npm**：构建和运行 `skill-miniapp` 所需。
- **Bun**：运行 `src/main/pc-agent` 测试所需。
- **MySQL**：持久化会话、消息、Agent 注册状态、AK/SK 等数据。
- **Redis**：多实例协同、路由归属和消息广播依赖 Redis。
- **现代浏览器**：前端运行环境。

*映射时间：2026-03-11。*
