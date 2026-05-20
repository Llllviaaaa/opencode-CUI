# 类型安全

> `skill-server` 当前的类型策略是“可变持久化模型 + 不可变 record 命令对象 + 单一 `StreamMessage` 聚合 DTO”。

---

## 概览

- **Java 21**：允许 `record`、switch 表达式等现代语法
- **Lombok**：用于大多数可变模型类
- **Jackson**：字段重命名、忽略、`@JsonUnwrapped`
- **MyBatis**：枚举以名字映射，不以 ordinal 映射

重要校准：

- 当前 `StreamMessage` **没有使用 Jackson 多态子类**；它是一个单一 DTO，靠嵌套静态类 + `@JsonUnwrapped` 表达不同消息组。
- `senderUserAccount` 已完成**信封层迁移**，不要再把它放进 payload。

---

## 模型分层

| 类型 | 代表类 | 用途 |
|------|--------|------|
| 持久化实体 | `SkillSession`, `SkillMessage`, `SkillMessagePart` | MySQL / MyBatis 映射 |
| 协议 DTO | `StreamMessage`, `ApiResponse`, `ExternalInvokeRequest` | WebSocket / REST 协议 |
| 命令 / 查询对象 | `InvokeCommand`, `SessionListQuery`, `ImMessageRequest` | service 间传递、不可变参数 |

代码证据：

- `SkillSession`：`skill-server/src/main/java/com/opencode/cui/skill/model/SkillSession.java:23-120`
- `SkillMessage`：`skill-server/src/main/java/com/opencode/cui/skill/model/SkillMessage.java:14-76`
- `SkillMessagePart`：`skill-server/src/main/java/com/opencode/cui/skill/model/SkillMessagePart.java:19-109`
- `StreamMessage`：`skill-server/src/main/java/com/opencode/cui/skill/model/StreamMessage.java:23-277`
- `InvokeCommand`：`skill-server/src/main/java/com/opencode/cui/skill/model/InvokeCommand.java:54-102`

---

## Lombok 使用策略

### 1. 可变模型类使用 Lombok

`SkillSession` 是当前持久化实体的标准样式：

```java
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SkillSession {

    @JsonProperty("welinkSessionId")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    @Builder.Default
    private Status status = Status.ACTIVE;
    ...
}
```

来源：`skill-server/src/main/java/com/opencode/cui/skill/model/SkillSession.java:23-92`

`StreamMessage` 也使用同一组合，但额外加 `@JsonInclude(NON_NULL)`：

```java
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class StreamMessage {
    private String type;
    private Long seq;
    ...
}
```

来源：`skill-server/src/main/java/com/opencode/cui/skill/model/StreamMessage.java:23-88`

规则：

- 持久化实体、可变协议 DTO：`@Data + @Builder + @NoArgsConstructor + @AllArgsConstructor`
- 有默认值的字段必须加 `@Builder.Default`
- MyBatis / Jackson 共同参与的类不要删除无参构造

### 2. 不可变参数使用 record

`InvokeCommand` 和 `ImMessageRequest` 是当前 record 风格的基准：

```java
public record InvokeCommand(
                String ak,
                String userId,
                String sessionId,
                String action,
                String payload,
                Boolean suppressReply,
                String domain,
                String domainType,
                String businessSessionId,
                @Nullable List<String> allowedSlashCommands) {
    // 5/6/8/9 参 secondary constructor 兼容旧 caller（test + 非升级生产代码）
}
```

字段语义：
- 前 5 个：核心调用参数（ak / userId / sessionId / action / payload）
- `suppressReply`：null = 不写入 INVOKE 报文；仅群聊 + plugin channel 命中"禁群聊"白名单时置 true
- `domain` / `domainType` / `businessSessionId`：`SkillSession.businessSession*` 三字段，用于 `AssistantScopeDispatcher` 反查默认助手规则 + 构造 `platformExtParam`
- `allowedSlashCommands`（v3 allowed-slash-commands 任务）：personal scope CHAT 允许的 slash 命令清单；从 `sys_config(allowed_slash_commands, ${domain}_${type})` 解析得到；null = 不下发该 platformExtParam key；仅 A 表 3 处（A4 CHAT 分支 / A7 dispatchChatToGateway / A10 retryPendingMessages）显式传 list，其余 9 处生产代码 + 62 处 test callsite 通过 secondary constructor 默认 null

来源：`skill-server/src/main/java/com/opencode/cui/skill/model/InvokeCommand.java:54-102`

```java
public record ImMessageRequest(
        String businessDomain,
        String sessionType,
        String sessionId,
        String assistantAccount,
        String senderUserAccount,
        String content,
        String msgType,
        String imageUrl,
        List<ChatMessage> chatHistory) {
    ...
}
```

来源：`skill-server/src/main/java/com/opencode/cui/skill/model/ImMessageRequest.java:18-53`

规则：

- service 间传递的命令 / 查询参数，优先考虑 `record`
- 会被 MyBatis 直接填充、或需要逐步 mutate 的对象，不要改成 `record`

### 2.1 record 静态工厂命名：禁止与 accessor 重名

record 的每个 component 会自动生成同名 accessor 方法（如 `boolean online()` 生成 `public boolean online()`）。如果在 record 内定义同名的**静态**方法，Java 编译器不允许"返回值不同但参数为空"的两个方法共存，会报编译错误。

错误模式（`AvailabilityResult` 实际踩坑）：
```java
// ❌ static factory online() 与 accessor boolean online() 同名冲突
public record AvailabilityResult(boolean online, ...) {
    public static AvailabilityResult online() { ... }  // 编译错误
}
```

正确模式 — 静态工厂统一用 `of` 前缀：
```java
// ✅ 静态工厂用 of 前缀，与 accessor 区分
public record AvailabilityResult(boolean online, ...) {
    public static AvailabilityResult ofOnline() { ... }
    public static AvailabilityResult ofOfflineTyped(String message, String toolType) { ... }
}
```

来源：`skill-server/src/main/java/com/opencode/cui/skill/model/AvailabilityResult.java`

规则：

- record 静态工厂方法**必须**加 `of` 前缀（如 `ofOnline()`、`ofError()`），避免与 component accessor 方法名冲突。
- 静态工厂只用于"固定场景的便利构造"，不替代 Builder 或全参构造。

---

## SkillSession / SkillMessage / SkillMessagePart

### SkillSession

`SkillSession` 的几个关键类型约束：

- `id` 对外输出为 `welinkSessionId`
- `Long` 通过 `ToStringSerializer` 输出，防止前端精度丢失
- `lastActiveAt` 对外别名是 `updatedAt`
- `status` 是内部枚举，不暴露裸字符串常量

```java
@JsonProperty("welinkSessionId")
@JsonSerialize(using = com.fasterxml.jackson.databind.ser.std.ToStringSerializer.class)
private Long id;

@JsonProperty("updatedAt")
private LocalDateTime lastActiveAt;

public enum Status {
    ACTIVE, IDLE, CLOSED
}
```

来源：`skill-server/src/main/java/com/opencode/cui/skill/model/SkillSession.java:41-92`

### SkillMessage

`SkillMessage` 的类型边界较窄：角色与内容类型都用 enum。

```java
public enum Role {
    USER, ASSISTANT, SYSTEM, TOOL
}

public enum ContentType {
    MARKDOWN, CODE, PLAIN
}
```

来源：`skill-server/src/main/java/com/opencode/cui/skill/model/SkillMessage.java:35-75`

### SkillMessagePart

`SkillMessagePart` 目前仍是“宽表式 part 实体”，不同 part 类型共用一套对象：

```java
private String partType;
private String content;
private String toolName;
private String toolCallId;
private String toolStatus;
private String fileName;
private Integer tokensIn;
private Double cost;
private String subagentSessionId;
```

来源：`skill-server/src/main/java/com/opencode/cui/skill/model/SkillMessagePart.java:34-109`

规则：

- part 级扩展先确认是否真需要加列；不要为了临时协议字段把实体无限做宽。
- role、status、contentType 这类有限值优先 enum，不要裸字符串横飞。

---

## StreamMessage：单一聚合 DTO，而非多态子类

当前 `StreamMessage` 的设计重点不是多态，而是**单对象 + 嵌套分组平铺**。

```java
@JsonIgnore
private String sessionId;
private String welinkSessionId;
private String emittedAt;

@JsonUnwrapped
private ToolInfo tool;

@JsonUnwrapped
private PermissionInfo permission;

@JsonUnwrapped
private QuestionInfo questionInfo;

@JsonUnwrapped
private UsageInfo usage;

@JsonUnwrapped
private FileInfo file;
```

来源：`skill-server/src/main/java/com/opencode/cui/skill/model/StreamMessage.java:32-87`

这意味着：

- 不要给 `StreamMessage` 新增 Jackson polymorphic 注解（`@JsonTypeInfo` / `@JsonSubTypes`）
- 新消息类型通常先复用既有字段组；确实不够时，再新增一个小的嵌套静态类

类型常量统一收口在 `Types`：

```java
public static final class Types {
    public static final String TEXT_DELTA = "text.delta";
    public static final String TOOL_UPDATE = "tool.update";
    public static final String SESSION_STATUS = "session.status";
    public static final String PERMISSION_REPLY = "permission.reply";
    public static final String ERROR = "error";
    public static final String SEARCH_RESULT = "search_result";
}
```

来源：`skill-server/src/main/java/com/opencode/cui/skill/model/StreamMessage.java:175-213`

静态工厂方法也已经稳定存在：

```java
public static StreamMessage sessionStatus(String status) { ... }
public static StreamMessage error(String errorMessage) { ... }
public static StreamMessage agentOnline() { ... }
public static StreamMessage agentOffline() { ... }
```

来源：`skill-server/src/main/java/com/opencode/cui/skill/model/StreamMessage.java:217-277`

---

## canonical sessionId / welinkSessionId 规则

`StreamMessageEmitter` 会把内部 `sessionId` 和对外 `welinkSessionId` 统一写成同一个 canonical sessionId：

```java
private void enrich(String sessionId, StreamMessage msg) {
    if (msg == null || sessionId == null) return;

    msg.setSessionId(sessionId);
    msg.setWelinkSessionId(sessionId);
    ...
}
```

来源：`skill-server/src/main/java/com/opencode/cui/skill/service/delivery/StreamMessageEmitter.java:64-81`

测试已经把这个行为固定下来：

```java
StreamMessage msg = StreamMessage.builder()
        .type(StreamMessage.Types.TEXT_DELTA)
        .welinkSessionId("business-123")
        .role("assistant")
        .build();

emitter.emitToSession(session, "101", "user-a", msg);
assertEquals("101", msg.getWelinkSessionId());
```

来源：`skill-server/src/test/java/com/opencode/cui/skill/service/delivery/StreamMessageEmitterTest.java:42-55`

规则：

- 业务侧 sessionId 不要直接塞进 `StreamMessage.welinkSessionId`
- 统一让 emitter 做 canonical overwrite

---

## scope + protocol 字段的类型约束

personal-scope 事件现在会根据顶层 `protocol` 字段决定翻译器：

```java
JsonNode protocolNode = event.path("protocol");
if (protocolNode.isMissingNode() || protocolNode.isNull()) {
    return openCodeEventTranslator.translate(event);
}
String protocol = protocolNode.asText("");
if ("cloud".equalsIgnoreCase(protocol)) {
    return cloudEventTranslator.translate(event, sessionId);
}
if ("opencode".equalsIgnoreCase(protocol)) {
    return openCodeEventTranslator.translate(event);
}
log.warn("[PersonalScope] unknown protocol value=\"{}\", fallback to OpenCodeEventTranslator, ...", protocol, ...);
return openCodeEventTranslator.translate(event);
```

来源：`skill-server/src/main/java/com/opencode/cui/skill/service/scope/PersonalScopeStrategy.java:75-97`

cloud protocol 的 part/message 标识由 `CloudEventTranslator` 二次归一化：

```java
if (!SESSION_LEVEL_TYPES.contains(eventType)) {
    if (msg.getSourceMessageId() == null && msg.getMessageId() != null) {
        msg.setSourceMessageId(msg.getMessageId());
    }
    if (msg.getRole() == null) {
        msg.setRole("assistant");
    }
    if (!MESSAGE_LEVEL_TYPES.contains(eventType)) {
        if (msg.getPartSeq() == null && sessionId != null && msg.getPartId() != null) {
            ...
            msg.setPartSeq(seq);
        }
    }
}
```

来源：`skill-server/src/main/java/com/opencode/cui/skill/service/CloudEventTranslator.java:150-188`

测试证据：`skill-server/src/test/java/com/opencode/cui/skill/service/scope/PersonalScopeCloudProtocolIntegrationTest.java:104-203`

规则：

- `protocol` 只接受 `cloud` / `opencode` / 缺失，未知值只能 fallback，不要扩散魔法字符串。
- cloud event 要带稳定的 `messageId` / `partId`；SS 只负责补 `sourceMessageId` / `partSeq` / 默认 role。

---

## senderUserAccount 信封层迁移

`senderUserAccount` 的当前稳定类型位置：

- external：`ExternalInvokeRequest.senderUserAccount`
- IM：`ImMessageRequest.senderUserAccount`
- service：`InboundProcessingService` 通过方法参数接收，再写入 `sendUserAccount`

external DTO：

```java
@Data
public class ExternalInvokeRequest {
    private String action;
    private String businessDomain;
    private String sessionType;
    private String sessionId;
    private String assistantAccount;
    private String senderUserAccount;
    private JsonNode payload;
}
```

来源：`skill-server/src/main/java/com/opencode/cui/skill/model/ExternalInvokeRequest.java:10-49`

下游组包时使用的也是信封层参数，而不是 payload 中的旧字段：

```java
String effectiveSender = "group".equals(sessionType)
        && senderUserAccount != null && !senderUserAccount.isBlank()
        ? senderUserAccount : ownerWelinkId;
payloadFields.put("sendUserAccount", effectiveSender);
```

来源：`skill-server/src/main/java/com/opencode/cui/skill/service/InboundProcessingService.java:159-173`

测试明确规定 legacy payload 字段无效：

`skill-server/src/test/java/com/opencode/cui/skill/controller/ExternalInboundControllerTest.java:137-150`

规则：

- 不要再定义 `payload.senderUserAccount`
- 新入口如果需要发送者身份，字段名和位置必须与现有 envelope 对齐

---

## MyBatis 枚举映射

枚举在 XML 中统一走 `EnumTypeHandler`，按名字映射：

```xml
<result property="status" column="status"
        typeHandler="org.apache.ibatis.type.EnumTypeHandler"
        javaType="com.opencode.cui.skill.model.SkillSession$Status"/>

<result property="role" column="role"
        typeHandler="org.apache.ibatis.type.EnumTypeHandler"
        javaType="com.opencode.cui.skill.model.SkillMessage$Role"/>
```

Java 侧来源：

- `skill-server/src/main/java/com/opencode/cui/skill/model/SkillSession.java:58-92`
- `skill-server/src/main/java/com/opencode/cui/skill/model/SkillMessage.java:35-75`

配套 XML：

- `skill-server/src/main/resources/mapper/SkillSessionMapper.xml:7-22`
- `skill-server/src/main/resources/mapper/SkillMessageMapper.xml:7-22`

规则：

- 不要在 XML 里映射 enum ordinal
- service 层要用 `Status.CLOSED.name()` 这类显式字符串下沉到 SQL

---

## 反序列化对抗性输入：先 readTree 再按 schema 校验

Redis list / MQ payload 这类 "内容可能直接来自用户输入" 的存储位置，**禁止**直接 `mapper.readValue(raw, TargetDto.class)` 来判断 "新格式 vs 老格式"。用户消息正文本身可能是合法 JSON（如 `{"foo":"bar"}`），`readValue` 会成功反序列化得到一个 `text=null` 的对象，把原始文本静默吞掉。

正确做法：**先 `readTree(JsonNode)`，严格按 schema 关键字段判断**，三重校验都通过才走新格式 deserialize，否则当 raw 老格式处理。

```java
// ❌ 直接 readValue → 对抗性 JSON 文本被误判，丢消息
try {
    PendingChatRequest req = mapper.readValue(raw, PendingChatRequest.class);
    // req.text 可能是 null，原始文本丢了
} catch (JsonProcessingException e) {
    return PendingChatRequest.fromSessionFallback(session, raw);
}

// ✅ readTree + schema 三重校验
JsonNode node;
try {
    node = mapper.readTree(raw);
} catch (JsonProcessingException e) {
    return PendingChatRequest.fromSessionFallback(session, raw);
}
if (node.isObject()
        && node.path("text").isTextual()
        && !node.path("text").asText().isEmpty()) {
    return mapper.treeToValue(node, PendingChatRequest.class);  // 新格式
}
return PendingChatRequest.fromSessionFallback(session, raw);     // 老格式 / 对抗输入
```

参考实现：`skill-server/src/main/java/com/opencode/cui/skill/service/SessionRebuildService.java::consumePendingMessages`

规则：

- 凡 "存储内容可能是用户原始输入" 的反序列化点（Redis list/hash value、MQ body、文件输入），都走 `readTree` + 字段 schema 校验。
- schema 判断必须挑**新格式独有**的字段（这里是 `text` 必为非空 textual），不能只判断 `isObject`。
- fallback 路径必须能拿到原始 raw 字符串，不要在 `readTree` 之前先 trim / decode。

---

## 常见错误

1. 不要把 `StreamMessage` 重构成 Jackson 多态层级；当前实现不是这个方向。
2. 不要删掉模型类的无参构造；MyBatis 和 Jackson 都会依赖它。
3. 不要把 Long ID 原样当 number 输出给前端；`SkillSession.id` 必须保持字符串化。
4. 不要恢复 `payload.senderUserAccount`；信封层迁移已经完成。
5. 不要手动拼 `welinkSessionId`；让 `StreamMessageEmitter` 统一覆写。
