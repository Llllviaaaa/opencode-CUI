# 测试模式

## 测试框架

- **`ai-gateway`** 使用 `spring-boot-starter-test`。
- **`skill-server`** 使用 `spring-boot-starter-test`。
- **Java 测试**整体采用 JUnit 5 + Mockito 风格。
- **PC Agent 测试**通过 `src/main/pc-agent/package.json` 中的 `bun test` 运行。
- **Miniapp** 当前没有自动化测试框架配置。

## 测试文件组织

- Gateway 测试位于 `ai-gateway/src/test/java/com/opencode/cui/gateway/...`，基本镜像主代码结构。
- Skill Server 测试位于 `skill-server/src/test/java/com/opencode/cui/skill/...`，同样镜像主代码结构。
- PC Agent 测试位于 `src/main/pc-agent/__tests__/`。
- `skill-miniapp/src` 下没有发现 `*.test.tsx`、`*.spec.tsx` 等测试文件。

## 测试结构

- 当前测试以**模块级单元测试 / 服务测试**为主，而不是整链路集成测试。
- Java 测试一般围绕单个 Controller、Service 或 Handler 进行，依赖通过 mock 注入。
- 协议相关测试重点关注字段映射、事件翻译、JSON 结构是否符合预期。
- PC Agent 测试重点覆盖事件桥接、连接状态、权限映射和鉴权签名。

## Mocking

- **Java 侧**广泛使用 Mockito 风格的 `mock`、`when`、`verify`、`never`、`times`。
- 典型文件包括：
  - `ai-gateway/src/test/java/com/opencode/cui/gateway/ws/SkillWebSocketHandlerTest.java`
  - `skill-server/src/test/java/com/opencode/cui/skill/ws/SkillStreamHandlerTest.java`
- **PC Agent** 通常使用假的 gateway/client 对象直接调用方法，不引入复杂测试容器。
- Miniapp 因为没有自动化测试，因此没有统一的浏览器 / 网络 mock 层。

## Fixtures 与工厂

- 当前仓库更偏向**内联构造测试数据**，而不是建立统一 fixture 工厂。
- JSON 事件常常直接写在测试里，用来覆盖事件翻译边界情况。
- `sessionId`、`messageId`、`toolCallId`、`permissionId` 等测试数据通常保持固定值，便于断言。

## 覆盖情况

- **Gateway**：已有 REST、鉴权、模型序列化、事件转发、Skill WebSocket 相关测试。
- **Skill Server**：已有 Controller、Session、Message、Relay、Translator、Stream Handler 相关测试。
- **PC Agent**：已有 `AkSkAuth`、`EventFilter`、`EventRelay`、`GatewayConnection`、`PermissionMapper` 测试。
- **Miniapp**：当前没有自动化测试覆盖。
- 仓库中没有发现 coverage report、覆盖率门禁或统一测试报告汇总机制。

## 测试类型

- **Gateway**
  - Controller 测试
  - 模型序列化测试
  - Service 测试
  - WebSocket Handler 测试
- **Skill Server**
  - Controller 测试
  - Service 测试
  - Stream Handler 测试
  - 协议翻译与消息持久化测试
- **PC Agent**
  - 单元测试
  - 协议桥接与连接管理测试
- **缺失类型**
  - Miniapp 组件 / Hook 测试
  - 端到端四段链路测试
  - 性能与压力测试

## 常见测试模式

- 验证 `tool_event`、`tool_done`、`permission`、`question` 等协议消息的翻译是否正确。
- 验证注册、重连、拒绝、超时等连接状态变化。
- 验证 JSON 序列化 / 反序列化兼容性。
- 通过直接调用协作者并断言交互行为，避免拉起完整容器。

*映射时间：2026-03-11。*
