# 代码库关注点

## 技术债

- **缺少仓库级统一构建入口**
  - 文件：`package.json`、`ai-gateway/pom.xml`、`skill-server/pom.xml`、`skill-miniapp/package.json`、`src/main/pc-agent/package.json`
  - 影响：四个模块分别使用不同的构建与测试方式，整仓验证需要人工串联，容易漏掉某一段。
  - 建议：增加统一的根级脚本或工作流，至少能串起 build / typecheck / test。
- **数据库迁移脚本存在，但仓库内未看到明确的迁移执行闭环**
  - 文件：`ai-gateway/src/main/resources/db/migration/`、`skill-server/src/main/resources/db/migration/`
  - 影响：环境初始化可能依赖人工执行或外部流程，存在代码与数据库版本漂移风险。
  - 建议：补齐 Flyway 或明确文档化真实迁移执行方式。
- **核心知识分散在代码与长文档设计包之间**
  - 文件：`documents/protocol/`、`documents/gateway-skill-routing/`、`documents/stream-message-design/`
  - 影响：新人理解全链路成本高，需要同时阅读大量文档和实现代码。
  - 建议：把 `.planning/codebase/` 作为压缩后的长期入口，并在仓库根增加导航说明。

## 已知问题

- **前端暴露了 `getDefinitions()` API，但后端没有对应 Controller 路由**
  - 文件：`skill-miniapp/src/utils/api.ts`、`skill-server/src/main/java/com/opencode/cui/skill/controller/`
  - 复现：如果前端未来调用 `getDefinitions()`，当前后端并没有 `/api/skill/definitions` 路由实现。
  - 影响：一旦前端使用该 helper，将直接返回 404。
  - 建议：要么补齐接口，要么删除未使用 helper，避免形成“假契约”。

## 安全考虑

- **后端配置文件中存在本地开发用默认凭据 / 默认内部令牌**
  - 文件：`ai-gateway/src/main/resources/application.yml`、`skill-server/src/main/resources/application.yml`
  - 影响：如果直接沿用到共享环境，容易形成弱安全基线。
  - 建议：把敏感值完全交给环境变量，不在仓库内提供可直接使用的默认值。
- **Gateway 迁移脚本里存在开发用 AK/SK 初始化数据**
  - 文件：`ai-gateway/src/main/resources/db/migration/V2__ak_sk_credential.sql`
  - 影响：非本地环境执行迁移时，可能自动植入已知凭据。
  - 建议：把开发凭据初始化移出正式迁移，改为本地初始化脚本或测试夹具。
- **CORS 和 WebSocket Origin 目前基本是全开放**
  - 文件：`skill-server/src/main/java/com/opencode/cui/skill/config/CorsConfig.java`、`skill-server/src/main/java/com/opencode/cui/skill/config/SkillConfig.java`、`ai-gateway/src/main/java/com/opencode/cui/gateway/config/GatewayConfig.java`
  - 影响：开发阶段方便，但生产环境边界过宽。
  - 建议：按环境收敛允许来源，生产环境默认白名单。
- **Miniapp 侧身份目前依赖明文 `userId` Cookie**
  - 文件：`skill-miniapp/src/utils/devAuth.ts`、`skill-server/src/main/java/com/opencode/cui/skill/controller/SkillSessionController.java`、`skill-server/src/main/java/com/opencode/cui/skill/ws/SkillStreamHandler.java`
  - 影响：适合开发联调，不适合作为生产级用户认证方案。
  - 建议：接入真正的登录态与服务端身份校验机制。

## 性能瓶颈

- **流式事件路径上存在高频翻译 + 持久化开销**
  - 文件：`skill-server/src/main/java/com/opencode/cui/skill/service/GatewayRelayService.java`、`skill-server/src/main/java/com/opencode/cui/skill/service/MessagePersistenceService.java`
  - 影响：当 OpenCode 输出频率较高时，可能导致较多对象转换和数据库操作。
  - 建议：评估是否需要对非终态 delta 做更强的合并或批处理。
- **PC Agent 调试日志采用同步文件写入**
  - 文件：`src/main/pc-agent/EventRelay.ts`
  - 影响：`appendFileSync` 位于热路径上，事件量大时会增加阻塞成本。
  - 建议：改为可配置开关，并考虑异步写入。
- **断线恢复需要同时拼装数据库历史和内存缓冲**
  - 文件：`skill-server/src/main/java/com/opencode/cui/skill/ws/SkillStreamHandler.java`、`skill-server/src/main/java/com/opencode/cui/skill/service/StreamBufferService.java`
  - 影响：长会话和多活跃会话情况下，恢复成本会上升。
  - 建议：增加度量，并对 snapshot 大小做限制或分页。

## 脆弱区域

- **协议映射跨越四个模块，任何一层字段变化都可能引发连锁问题**
  - 文件：`src/main/pc-agent/EventRelay.ts`、`ai-gateway/src/main/java/com/opencode/cui/gateway/model/GatewayMessage.java`、`skill-server/src/main/java/com/opencode/cui/skill/service/OpenCodeEventTranslator.java`、`skill-miniapp/src/protocol/`
  - 风险：OpenCode SDK、Gateway 消息结构或前端协议字段一旦变化，可能在下游悄悄失效。
  - 建议：改协议前先补跨模块测试，再统一修改。
- **重连 / 恢复逻辑依赖顺序号正确性**
  - 文件：`skill-server/src/main/java/com/opencode/cui/skill/service/SequenceTracker.java`、`skill-server/src/main/java/com/opencode/cui/skill/ws/SkillStreamHandler.java`
  - 风险：顺序错乱只会在掉线恢复场景暴露，平时很难发现。
  - 建议：新增专门的恢复场景测试后再改这部分逻辑。
- **Gateway 多实例归属选择依赖 Redis 协同**
  - 文件：`ai-gateway/src/main/java/com/opencode/cui/gateway/service/SkillRelayService.java`、`ai-gateway/src/main/java/com/opencode/cui/gateway/service/RedisMessageBroker.java`
  - 风险：单机场景不容易复现多实例归属错误，问题更可能在联调或线上暴露。
  - 建议：关键改动要做至少双实例验证。

## 扩展性限制

- **虽然有多实例设计，但部分活跃连接状态仍保存在内存中**
  - 文件：`ai-gateway/src/main/java/com/opencode/cui/gateway/service/EventRelayService.java`、`skill-server/src/main/java/com/opencode/cui/skill/ws/SkillStreamHandler.java`
  - 现状：仓库里能看到多实例协同原语，但没有压力测试或容量基线。
  - 影响：连接数或活跃会话数上升后，单实例内存与推送成本可能先成为瓶颈。
- **日志主要写本地文件，不利于规模化排障**
  - 文件：两套 `application.yml` 与 `src/main/pc-agent/EventRelay.ts`
  - 影响：实例变多后，很难统一追踪跨服务问题。
  - 建议：后续引入集中式日志与检索能力。

## 风险依赖

- **`@opencode-ai/sdk` 是高耦合上游依赖**
  - 文件：`src/main/pc-agent/package.json`、`src/main/pc-agent/EventRelay.ts`、`skill-server/src/main/java/com/opencode/cui/skill/service/OpenCodeEventTranslator.java`、`skill-miniapp/src/protocol/OpenCodeEventParser.ts`
  - 风险：SDK 事件结构变化会直接波及 Gateway、Skill Server、Miniapp 三段协议适配。
  - 建议：把事件兼容性测试做厚，并明确版本约束。
- **`org.java-websocket` 与 Spring WebSocket 并存**
  - 文件：`skill-server/pom.xml`、`skill-server/src/main/java/com/opencode/cui/skill/ws/GatewayWSClient.java`
  - 风险：两套 WebSocket 栈并存会增加调试和行为一致性成本。
  - 建议：如果维护成本继续升高，可评估统一技术路线。

## 缺失的关键能力

- **缺少 Miniapp → Skill Server → Gateway → PC Agent 的端到端自动化验证**
  - 影响：最关键的用户链路目前主要靠分段单测与人工联调保证。
  - 建议：增加整链路 smoke test 或脚本化联调方案。
- **缺少生产级用户认证方案**
  - 影响：当前 `userId` Cookie 机制适合开发，不适合正式环境。
  - 建议：尽快补充统一认证与鉴权接入层。
- **缺少仓库内 CI/CD 与部署定义**
  - 影响：环境搭建和发布流程依赖人工经验。
  - 建议：至少补齐 CI 校验与开发环境启动文档。

## 测试覆盖空白

- **Miniapp 没有自动化测试**
  - 文件：`skill-miniapp/src/`
  - 风险：流式拼接、权限卡片、重连恢复、渲染兼容性回归都可能直接漏掉。
  - 优先级：高
- **Gateway 的 Agent 注册主链路缺少更直接的 Handler 级测试**
  - 文件：`ai-gateway/src/main/java/com/opencode/cui/gateway/ws/AgentWebSocketHandler.java`
  - 风险：重复连接、设备绑定、注册超时、心跳处理等逻辑回归时，不一定能被现有测试及时发现。
  - 优先级：高
- **缺少多实例 Redis 路由测试**
  - 文件：Gateway / Skill Server 的 Redis Broker 与 Relay 相关类
  - 风险：分布式归属、广播、恢复请求问题更容易在线上暴露。
  - 优先级：中
- **缺少迁移脚本验证测试**
  - 文件：两侧 `db/migration/` 目录
  - 风险：数据库结构和代码预期可能逐步偏离。
  - 优先级：中

*映射时间：2026-03-11。*
