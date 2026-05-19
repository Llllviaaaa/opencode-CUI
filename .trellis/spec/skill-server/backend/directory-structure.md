# 目录结构

> `skill-server` 后端代码的组织方式与放置规则。

---

## 概览

`skill-server` 仍然是经典 Spring layer 分包，但最近的重构已经把“协议翻译”“出站分发”“scope 策略”拆到了明确的子包里：

- `service/cloud/`：cloud protocol 请求构建与事件翻译支撑。
- `service/delivery/`：统一出站分发与各渠道 delivery strategy。
- `service/scope/`：personal / business 助手作用域分派。

当前目录统计（基于 `skill-server/src/main/java/com/opencode/cui/skill/` 实际文件数）：

- `config/`：13
- `controller/`：6
- `service/`：39（不含子包）
- `service/cloud/`：4
- `service/delivery/`：6
- `service/scope/`：4
- `repository/`：5
- `model/`：23
- `ws/`：3
- `logging/`：4

---

## skill-server 包结构

```text
skill-server/src/main/java/com/opencode/cui/skill/
├── SkillServerApplication.java             # Spring Boot 入口
├── config/                                 # Bean / MVC / Redis / WS / properties
│   ├── AsyncConfig.java                    # 命名 Executor（messageHistoryRefreshExecutor）
│   ├── AssistantIdProperties.java
│   ├── AssistantInfoProperties.java
│   ├── CorsConfig.java
│   ├── DeliveryProperties.java
│   ├── GlobalExceptionHandler.java
│   ├── ImTokenAuthInterceptor.java
│   ├── MdcRequestInterceptor.java
│   ├── RedisConfig.java
│   ├── RestTemplateConfig.java
│   ├── SkillConfig.java                    # 注册 `/ws/skill/stream`
│   ├── SnowflakeProperties.java
│   └── WebMvcConfig.java
├── controller/
│   ├── AgentQueryController.java
│   ├── ExternalInboundController.java      # external 信封协议入口
│   ├── ImInboundController.java            # IM 回调入口
│   ├── SkillMessageController.java         # session/{id}/messages
│   ├── SkillSessionController.java         # session CRUD / create_session 分流
│   └── SysConfigController.java
├── service/
│   ├── AssistantAccountResolverService.java
│   ├── AssistantIdResolverService.java
│   ├── AssistantInfoService.java           # AK -> scope / owner / identityType
│   ├── CloudEventTranslator.java           # cloud protocol -> StreamMessage
│   ├── GatewayApiClient.java
│   ├── GatewayMessageRouter.java           # gateway 上行事件路由
│   ├── GatewayRelayService.java            # invoke 下发
│   ├── InboundProcessingService.java       # IM / external 共用入口编排
│   ├── MessageHistoryCacheService.java
│   ├── MessagePersistenceService.java
│   ├── OpenCodeEventTranslator.java
│   ├── ProtocolMessageMapper.java
│   ├── ProtocolException.java
│   ├── RedisMessageBroker.java
│   ├── SessionAccessControlService.java
│   ├── SessionRebuildService.java
│   ├── SessionRouteService.java
│   ├── SkillInstanceRegistry.java
│   ├── SkillMessageService.java
│   ├── SkillSessionService.java
│   ├── SnapshotService.java
│   ├── StreamBufferService.java
│   └── ...（共 39 个顶层类）
├── service/cloud/
│   ├── CloudRequestBuilder.java
│   ├── CloudRequestContext.java
│   ├── CloudRequestStrategy.java
│   └── DefaultCloudRequestStrategy.java
├── service/delivery/
│   ├── ExternalWsDeliveryStrategy.java
│   ├── ImRestDeliveryStrategy.java
│   ├── MiniappDeliveryStrategy.java
│   ├── OutboundDeliveryDispatcher.java
│   ├── OutboundDeliveryStrategy.java
│   └── StreamMessageEmitter.java           # 当前统一出站入口
├── service/scope/
│   ├── AssistantScopeDispatcher.java
│   ├── AssistantScopeStrategy.java
│   ├── BusinessScopeStrategy.java
│   └── PersonalScopeStrategy.java          # protocol=cloud/opencode 分派
├── repository/
│   ├── SkillDefinitionRepository.java
│   ├── SkillMessagePartRepository.java
│   ├── SkillMessageRepository.java
│   ├── SkillSessionRepository.java
│   └── SysConfigMapper.java
├── model/
│   ├── ApiResponse.java
│   ├── ExternalInvokeRequest.java
│   ├── ImMessageRequest.java
│   ├── InvokeCommand.java
│   ├── PageResult.java
│   ├── ProtocolMessagePart.java
│   ├── ProtocolMessageView.java
│   ├── SaveMessageCommand.java
│   ├── SessionListQuery.java
│   ├── SkillMessage.java
│   ├── SkillMessagePart.java
│   ├── SkillMessageView.java
│   ├── SkillSession.java
│   ├── StreamMessage.java
│   └── ...（共 23 个模型）
├── ws/
│   ├── ExternalStreamHandler.java
│   ├── GatewayWSClient.java
│   └── SkillStreamHandler.java             # miniapp stream 连接 / 订阅 / 回放
└── logging/
    ├── LogTimer.java
    ├── MdcConstants.java
    ├── MdcHelper.java
    └── SensitiveDataMasker.java

skill-server/src/main/resources/
├── application.yml                         # skill.*、logging.*、datasource、redis
├── log4j2-spring.xml                      # 当前主日志布局（不是 logback）
├── mapper/
│   ├── SkillDefinitionMapper.xml
│   ├── SkillMessageMapper.xml
│   ├── SkillMessagePartMapper.xml
│   ├── SkillSessionMapper.xml
│   └── SysConfigMapper.xml
├── db/migration/
│   ├── V1__skill.sql
│   ├── V2__message_parts.sql
│   ├── V3__align_session_protocol.sql
│   ├── V4__align_userid_type.sql
│   ├── V5__snowflake_primary_keys.sql
│   ├── V6__session_chat_triple.sql
│   ├── V7__skill_message_session_seq_unique.sql
│   ├── V8__subagent_message_part.sql
│   ├── V9__tool_session_id_index.sql
│   └── V10__create_sys_config.sql
└── templates/
    └── group-chat-prompt.txt
```

目录证据：

- Java 文件清单：`skill-server/src/main/java/com/opencode/cui/skill/`
- `StreamMessageEmitter`：`skill-server/src/main/java/com/opencode/cui/skill/service/delivery/StreamMessageEmitter.java:19-136`
- `PersonalScopeStrategy`：`skill-server/src/main/java/com/opencode/cui/skill/service/scope/PersonalScopeStrategy.java:12-97`

---

## 关键放置规则

### 1. 入站入口放 `controller/`

- miniapp 自有 REST API：`SkillSessionController`、`SkillMessageController`
- 外部协议入口：`ExternalInboundController`
- IM 回调入口：`ImInboundController`

代码证据：

- `skill-server/src/main/java/com/opencode/cui/skill/controller/ExternalInboundController.java:22-162`
- `skill-server/src/main/java/com/opencode/cui/skill/controller/ImInboundController.java:23-123`

### 2. 共享编排放 `service/`

跨入口复用、但不属于单一协议的逻辑集中在 `service/` 顶层，例如：

- `InboundProcessingService`：IM / external 共用入站编排。
- `SkillMessageService`：消息 CRUD、分页、历史缓存刷新。
- `SessionRouteService`：Redis ownership。

代码证据：

- `skill-server/src/main/java/com/opencode/cui/skill/service/InboundProcessingService.java:100-176`
- `skill-server/src/main/java/com/opencode/cui/skill/service/SkillMessageService.java:36-215`
- `skill-server/src/main/java/com/opencode/cui/skill/service/SessionRouteService.java:22-245`

### 3. 出站策略放 `service/delivery/`

2026-04 的重构后，不再把所有“发给谁 / 怎么发”的分支堆在 `GatewayMessageRouter` 或 handler 里；统一放在 `service/delivery/`：

- `StreamMessageEmitter`：唯一权威出站入口。
- `OutboundDeliveryDispatcher`：根据 session domain 选策略。
- `MiniappDeliveryStrategy` / `ImRestDeliveryStrategy` / `ExternalWsDeliveryStrategy`：各渠道落地。

代码证据：

- `skill-server/src/main/java/com/opencode/cui/skill/service/delivery/StreamMessageEmitter.java:19-136`
- `skill-server/src/main/java/com/opencode/cui/skill/service/GatewayMessageRouter.java:855-870`

### 4. scope 差异放 `service/scope/`

如果行为随 assistant scope（`personal` / `business`）变化，不要把 `if ("business")` 写回 Controller；统一通过 `AssistantScopeDispatcher` 和策略对象分派。

代码证据：

- `skill-server/src/main/java/com/opencode/cui/skill/controller/SkillSessionController.java:85-106`
- `skill-server/src/main/java/com/opencode/cui/skill/service/scope/PersonalScopeStrategy.java:40-97`

---

## 命名规范

| 类别 | 命名规则 | 示例 |
|------|---------|------|
| 控制器 | `{Domain}Controller` | `SkillSessionController` |
| 服务 | `{Domain}Service` 或语义化组件名 | `SkillMessageService`, `RedisMessageBroker`, `StreamMessageEmitter` |
| Repository | `{Entity}Repository` / 既有 Mapper 名 | `SkillSessionRepository`, `SysConfigMapper` |
| 实体 / 模型 | `{Name}`（不加 Entity 后缀） | `SkillSession`, `SkillMessagePart` |
| DTO / 协议对象 | 用途命名（不加 DTO 后缀） | `StreamMessage`, `ApiResponse`, `InvokeCommand` |
| 配置类 | `{Domain}Config` 或 `{Domain}Properties` | `RedisConfig`, `DeliveryProperties` |
| 异常 | `{Domain}Exception` | `ProtocolException` |
| 请求体 | 单入口用内部 `static class`；跨入口复用用 record / model | `CreateSessionRequest`, `ImMessageRequest` |
| 工具类 | `{Domain}Utils` / `{Domain}Helper` | `ProtocolUtils`, `MdcHelper` |

代码证据：

- `CreateSessionRequest`：`skill-server/src/main/java/com/opencode/cui/skill/controller/SkillSessionController.java:70-111`
- `ImMessageRequest`：`skill-server/src/main/java/com/opencode/cui/skill/model/ImMessageRequest.java:18-53`

---

## 内部传输 DTO 必须标注作用域

`model/` 同时混放两类对象：① 跨服务 / 跨入口的**外部契约 DTO**（`ExternalInvokeRequest`、`ImMessageRequest`、`StreamMessage`、`ApiResponse`）；② 只在进程内 / Redis / 内部队列周转的**内部传输 DTO**（`InvokeCommand`、`PendingChatRequest`、`SaveMessageCommand`）。两者升级策略完全不同：外部契约动了要全链路追字段，内部 DTO 改了只影响一个进程编译。

**约定**：内部传输 DTO 必须在类 Javadoc 第一行明确说明它**不是**外部 API 契约。这样后续读代码 / review PR 时一眼能看出"这字段加错地方了"。

```java
/**
 * 内部传输 DTO：Redis pending list (ss:pending-rebuild:{sessionId}) 缓存
 * 的 personal scope 首次对话 chat 上下文。
 *
 * <p><b>不是外部 API 契约</b> —— 字段集仅服务于 SS 进程内
 * SessionRebuildService ↔ GatewayMessageRouter 周转，跨实例 Redis
 * 兼容靠 {@link #fromSessionFallback} 兜底，不走版本协商。
 */
public record PendingChatRequest(
        String text,
        String assistantAccount,
        String sendUserAccount,
        String imGroupId,
        String messageId,
        Map<String, Object> businessExtParam) {
    ...
}
```

参考：`PendingChatRequest`、`InvokeCommand`、`SaveMessageCommand`。

规则：

- 新增 `model/*.java` 时先回答"这对象会跨服务 / 跨入口出现吗"。否，则必须在 Javadoc 标"内部传输 DTO"。
- 内部 DTO **不强制**`@JsonInclude` / `@JsonProperty` 重命名等外部契约修饰；只保证 Jackson 默认序列化能往返。
- 内部 DTO 字段变更不需要同步 plugin / miniapp `types.ts`，但**必须**评估 Redis / 队列里的老格式 entry 兼容（见 type-safety.md "反序列化对抗性输入" 段）。

---

## 新增功能时的目录约定

1. **新增 API**：优先扩展现有 Controller；只有语义清晰的新入口才建新 `controller/*.java`。
2. **新增普通业务逻辑**：放 `service/{Domain}Service.java`。
3. **新增出站渠道**：放 `service/delivery/{Domain}DeliveryStrategy.java`，并交给 `OutboundDeliveryDispatcher`。
4. **新增 scope 差异**：扩展 `service/scope/AssistantScopeStrategy`，不要在 Controller / Router 写散落分支。
5. **新增 cloud 协议变体**：优先放 `service/cloud/` 或 `CloudEventTranslator`，不要混到 `OpenCodeEventTranslator`。
6. **新增数据表**：同步新增 `model/`、`repository/`、`resources/mapper/`、`resources/db/migration/`。
7. **新增共享请求体**：如果被 IM / external / miniapp 多入口共享，放 `model/`；否则留在 Controller 内部。
