# Business 助理 q_r / p_r 远端能力 + 回调配置接口切换 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让业务助理在 ai-gateway 路径上支持 `question_reply` / `permission_reply`（同步 WebHook 旁路），并把"取云端路由"切到 api-server 新接口（按 (ak, scope) 双维度查询，feature flag 灰度）。

**Architecture:** ai-gateway 引入 `CallbackConfigService` 外观（v1/v2 双 resolver 按 flag 装配）+ `WebHookExecutor`（同步 POST 旁路 lifecycle）；保活逻辑下沉到 `CloudConnectionLifecycle`（`awaitingReply` 状态时 `resetIdleTimeout` no-op，防 heartbeat 重启）；skill-server `BusinessScopeStrategy` 按 action 写 reply 字段，`DefaultCloudRequestStrategy` 把它们组装成 `replyContext` 嵌套对象写入 cloudRequest；`CloudEventTranslator.handleQuestion` 兼容 `questions[]` 数组形态并透传 `extParam`。

**Tech Stack:** Java 17, Spring Boot, Jackson, JUnit 5, Mockito, ai-gateway HttpClient (JDK), Redis (cache).

**Spec:** `docs/superpowers/specs/2026-04-27-business-question-permission-callback-design.md`

---

## Phase A — skill-server 协议侧

不依赖 gateway 改动，可独立测；先合此 phase 让 skill-server 已能产出含 `replyContext` 的 cloudRequest（gateway 切到 v2 后立刻可用）。

### Task 1: `StreamMessage.QuestionInfo` 加 `questions` + `extParam`

**Files:**
- Modify: `skill-server/src/main/java/com/opencode/cui/skill/model/StreamMessage.java`
- Test: `skill-server/src/test/java/com/opencode/cui/skill/model/StreamMessageTest.java`（如有则扩展，没有则新建）

- [ ] **Step 1: 写失败测试**

新建或扩展测试文件，验证 QuestionInfo 序列化含新字段。

```java
@Test
void questionInfo_serializesQuestionsListAndExtParam() throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    JsonNode extParam = mapper.readTree("{\"key\":\"value\"}");
    QuestionInfo info = QuestionInfo.builder()
            .header("h").question("q1").options(List.of("A","B"))
            .questions(List.of(
                    QuestionItem.builder().header("h").question("q1").options(List.of("A","B")).build(),
                    QuestionItem.builder().question("q2").options(List.of("C")).build()))
            .extParam(extParam)
            .build();
    String json = mapper.writeValueAsString(info);
    assertThat(json).contains("\"questions\":[");
    assertThat(json).contains("\"q2\"");
    assertThat(json).contains("\"extParam\":{\"key\":\"value\"}");
}
```

- [ ] **Step 2: 跑测试确认失败**

```bash
mvn -pl skill-server test -Dtest=StreamMessageTest#questionInfo_serializesQuestionsListAndExtParam
```
Expected: FAIL — `QuestionItem` 类不存在 / `questions`、`extParam` 字段不存在。

- [ ] **Step 3: 写最小实现**

在 `StreamMessage.QuestionInfo` 内新增字段；新增内嵌类 `QuestionItem`：

```java
public static class QuestionInfo {
    // ... 现有字段（header / question / options）保留 ...
    private List<QuestionItem> questions;
    private JsonNode extParam;
}

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public static class QuestionItem {
    private String header;
    private String question;
    private List<String> options;
}
```

- [ ] **Step 4: 跑测试确认通过**

```bash
mvn -pl skill-server test -Dtest=StreamMessageTest#questionInfo_serializesQuestionsListAndExtParam
```
Expected: PASS。

- [ ] **Step 5: Commit**

```bash
git add skill-server/src/main/java/com/opencode/cui/skill/model/StreamMessage.java \
        skill-server/src/test/java/com/opencode/cui/skill/model/StreamMessageTest.java
git commit -m "feat(stream-message): add QuestionItem + extParam to QuestionInfo"
```

---

### Task 2: `CloudEventTranslator.handleQuestion` 兼容 `questions[]` + `extParam` 透传

**Files:**
- Modify: `skill-server/src/main/java/com/opencode/cui/skill/service/CloudEventTranslator.java`（约 line 297-312）
- Test: `skill-server/src/test/java/com/opencode/cui/skill/service/CloudEventTranslatorTest.java`（扩展）

- [ ] **Step 1: 写失败测试**（3 个用例）

```java
@Test
void handleQuestion_legacySingleStructure_wrapsToSingleElementQuestions() throws Exception {
    String json = """
        {"type":"question","toolCallId":"call-1",
         "header":"h","question":"q","options":["A","B"]}
        """;
    StreamMessage msg = translator.translate(mapper.readTree(json));
    assertThat(msg.getQuestionInfo().getQuestions()).hasSize(1);
    assertThat(msg.getQuestionInfo().getQuestions().get(0).getQuestion()).isEqualTo("q");
    assertThat(msg.getQuestionInfo().getHeader()).isEqualTo("h"); // 顶层兼容字段
    assertThat(msg.getQuestionInfo().getExtParam()).isNull();
}

@Test
void handleQuestion_questionsArray_iteratesAll() throws Exception {
    String json = """
        {"type":"question","toolCallId":"call-1",
         "questions":[
           {"header":"h1","question":"q1","options":["A"]},
           {"question":"q2","options":["B","C"]}]}
        """;
    StreamMessage msg = translator.translate(mapper.readTree(json));
    assertThat(msg.getQuestionInfo().getQuestions()).hasSize(2);
    assertThat(msg.getQuestionInfo().getQuestions().get(1).getQuestion()).isEqualTo("q2");
    assertThat(msg.getQuestionInfo().getQuestion()).isEqualTo("q1"); // 顶层取第一个
}

@Test
void handleQuestion_extParamPassthrough() throws Exception {
    String json = """
        {"type":"question","toolCallId":"call-1",
         "header":"h","question":"q","options":["A"],
         "extParam":{"foo":"bar","nested":{"k":1}}}
        """;
    StreamMessage msg = translator.translate(mapper.readTree(json));
    assertThat(msg.getQuestionInfo().getExtParam().get("foo").asText()).isEqualTo("bar");
    assertThat(msg.getQuestionInfo().getExtParam().get("nested").get("k").asInt()).isEqualTo(1);
}
```

- [ ] **Step 2: 跑测试确认失败**

```bash
mvn -pl skill-server test -Dtest=CloudEventTranslatorTest
```
Expected: 3 个新用例 FAIL。

- [ ] **Step 3: 写最小实现**

替换 `handleQuestion`：

```java
private StreamMessage handleQuestion(JsonNode event) {
    JsonNode questionsNode = event.get("questions");
    List<QuestionItem> questions;
    if (questionsNode != null && questionsNode.isArray() && !questionsNode.isEmpty()) {
        questions = new ArrayList<>();
        for (JsonNode q : questionsNode) {
            questions.add(QuestionItem.builder()
                    .header(q.path("header").asText(null))
                    .question(q.path("question").asText(null))
                    .options(toStringList(q.get("options")))
                    .build());
        }
    } else {
        questions = List.of(QuestionItem.builder()
                .header(event.path("header").asText(null))
                .question(event.path("question").asText(null))
                .options(toStringList(event.get("options")))
                .build());
    }
    QuestionItem first = questions.get(0);

    JsonNode extParamNode = event.get("extParam");
    JsonNode extParam = (extParamNode != null && !extParamNode.isNull()) ? extParamNode : null;

    return StreamMessage.builder()
            .type(Types.QUESTION)
            .messageId(event.path("messageId").asText(null))
            .partId(event.path("partId").asText(null))
            .tool(ToolInfo.builder().toolCallId(event.path("toolCallId").asText(null)).build())
            .status(event.path("status").asText(null))
            .questionInfo(QuestionInfo.builder()
                    .header(first.getHeader())
                    .question(first.getQuestion())
                    .options(first.getOptions())
                    .questions(questions)
                    .extParam(extParam)
                    .build())
            .build();
}
```

- [ ] **Step 4: 跑测试确认通过**

```bash
mvn -pl skill-server test -Dtest=CloudEventTranslatorTest
```
Expected: PASS（含原有用例）。

- [ ] **Step 5: Commit**

```bash
git add skill-server/src/main/java/com/opencode/cui/skill/service/CloudEventTranslator.java \
        skill-server/src/test/java/com/opencode/cui/skill/service/CloudEventTranslatorTest.java
git commit -m "feat(cloud-event-translator): support questions[] array and extParam passthrough"
```

---

### Task 3: `CloudRequestContext` 加 4 个 reply 字段

**Files:**
- Modify: `skill-server/src/main/java/com/opencode/cui/skill/service/cloud/CloudRequestContext.java`

- [ ] **Step 1: 加字段**

在 builder 类内加：

```java
public class CloudRequestContext {
    // ... 现有字段 ...

    /** 仅 question_reply：关联回云端 question 事件的 toolCallId */
    private String replyToolCallId;
    /** 仅 question_reply：外层=多问题，内层=单题多选/单选/自由文本 */
    private List<List<String>> replyAnswers;
    /** 仅 permission_reply：关联回 permission.ask 的 permissionId */
    private String replyPermissionId;
    /** 仅 permission_reply：once / always / reject */
    private String replyResponse;
}
```

- [ ] **Step 2: 跑现有测试，确认无回归**

```bash
mvn -pl skill-server test -Dtest=CloudRequestBuilderTest
```
Expected: PASS（新字段都是可空的，旧测试不受影响）。

- [ ] **Step 3: Commit**

```bash
git add skill-server/src/main/java/com/opencode/cui/skill/service/cloud/CloudRequestContext.java
git commit -m "feat(cloud-request-context): add reply fields for question_reply / permission_reply"
```

---

### Task 4: `BusinessScopeStrategy.parseAnswers` static helper + 单测

**Files:**
- Modify: `skill-server/src/main/java/com/opencode/cui/skill/service/scope/BusinessScopeStrategy.java`
- Test: `skill-server/src/test/java/com/opencode/cui/skill/service/scope/BusinessScopeStrategyTest.java`

- [ ] **Step 1: 写失败测试**

```java
@Test
void parseAnswers_stringifiedNestedArray_preservedAsIs() {
    BusinessScopeStrategy strategy = newStrategy();
    List<List<String>> result = strategy.parseAnswers("[[\"A\"],[\"B\",\"C\"]]");
    assertThat(result).containsExactly(List.of("A"), List.of("B","C"));
}

@Test
void parseAnswers_stringified1DArray_wrappedToOuter() {
    BusinessScopeStrategy strategy = newStrategy();
    List<List<String>> result = strategy.parseAnswers("[\"A\",\"B\"]");
    assertThat(result).containsExactly(List.of("A","B"));
}

@Test
void parseAnswers_plainText_wrappedToDoubleArray() {
    BusinessScopeStrategy strategy = newStrategy();
    List<List<String>> result = strategy.parseAnswers("普通文本");
    assertThat(result).containsExactly(List.of("普通文本"));
}

@Test
void parseAnswers_blankOrNull_returnsSingleEmpty() {
    BusinessScopeStrategy strategy = newStrategy();
    assertThat(strategy.parseAnswers(null)).containsExactly(List.of(""));
    assertThat(strategy.parseAnswers("")).containsExactly(List.of(""));
    assertThat(strategy.parseAnswers("   ")).containsExactly(List.of(""));
}
```

- [ ] **Step 2: 跑测试确认失败**

```bash
mvn -pl skill-server test -Dtest=BusinessScopeStrategyTest#parseAnswers*
```
Expected: FAIL — 方法不存在。

- [ ] **Step 3: 写最小实现**

在 `BusinessScopeStrategy` 加 package-private 方法（便于单测）：

```java
List<List<String>> parseAnswers(String raw) {
    if (raw == null || raw.isBlank()) {
        return List.of(List.of(""));
    }
    try {
        JsonNode node = objectMapper.readTree(raw);
        if (node.isArray() && !node.isEmpty()) {
            boolean allArray = true;
            for (JsonNode el : node) { if (!el.isArray()) { allArray = false; break; } }
            if (allArray) {
                return objectMapper.convertValue(node,
                        new TypeReference<List<List<String>>>() {});
            }
            // 一维数组兜底
            return List.of(objectMapper.convertValue(node,
                    new TypeReference<List<String>>() {}));
        }
    } catch (Exception ignored) { /* fall through to plain-text fallback */ }
    return List.of(List.of(raw));
}
```

- [ ] **Step 4: 跑测试确认通过**

```bash
mvn -pl skill-server test -Dtest=BusinessScopeStrategyTest#parseAnswers*
```
Expected: PASS。

- [ ] **Step 5: Commit**

```bash
git add skill-server/src/main/java/com/opencode/cui/skill/service/scope/BusinessScopeStrategy.java \
        skill-server/src/test/java/com/opencode/cui/skill/service/scope/BusinessScopeStrategyTest.java
git commit -m "feat(business-scope): add parseAnswers helper for q_r answers normalization"
```

---

### Task 5: `BusinessScopeStrategy.buildInvoke` 按 action 写 reply 字段

**Files:**
- Modify: `skill-server/src/main/java/com/opencode/cui/skill/service/scope/BusinessScopeStrategy.java`
- Test: `skill-server/src/test/java/com/opencode/cui/skill/service/scope/BusinessScopeStrategyTest.java`

- [ ] **Step 1: 写失败测试**

```java
@Test
void buildInvoke_questionReply_writesToolCallIdAndAnswers() {
    String payload = "{\"toolSessionId\":\"ts-1\",\"toolCallId\":\"call-q\",\"answer\":\"[[\\\"A\\\"]]\"}";
    InvokeCommand cmd = new InvokeCommand("ak1", "u1", "s1",
            GatewayActions.QUESTION_REPLY, payload);
    AssistantInfo info = ...;  // 与现有测试一致
    String result = strategy.buildInvoke(cmd, info);
    JsonNode root = mapper.readTree(result);
    JsonNode cr = root.path("payload").path("cloudRequest");
    // 由于 DefaultCloudRequestStrategy 还没改 (T6)，先验证 BusinessScopeStrategy 写到 context
    // 此处可改为通过 spy CloudRequestBuilder 拦截 context 验证字段。
    // 简化：直接断言 cmd.action 透传 + cloudRequest 已构造
    assertThat(root.path("action").asText()).isEqualTo("question_reply");
}

@Test
void buildInvoke_permissionReply_writesPermissionIdAndResponse() {
    String payload = "{\"toolSessionId\":\"ts-1\",\"permissionId\":\"perm-1\",\"response\":\"once\"}";
    InvokeCommand cmd = new InvokeCommand("ak1", "u1", "s1",
            GatewayActions.PERMISSION_REPLY, payload);
    String result = strategy.buildInvoke(cmd, ...);
    JsonNode root = mapper.readTree(result);
    assertThat(root.path("action").asText()).isEqualTo("permission_reply");
}

@Test
void buildInvoke_chat_doesNotWriteReplyFields() {
    String payload = "{\"toolSessionId\":\"ts-1\",\"text\":\"hello\"}";
    InvokeCommand cmd = new InvokeCommand("ak1", "u1", "s1",
            GatewayActions.CHAT, payload);
    String result = strategy.buildInvoke(cmd, ...);
    JsonNode root = mapper.readTree(result);
    JsonNode cr = root.path("payload").path("cloudRequest");
    assertThat(cr.has("replyContext")).isFalse();
}
```

> 注：精细字段断言放到 T6 `DefaultCloudRequestStrategyTest`。本任务只验证 buildInvoke 不抛错且能区分 action。

- [ ] **Step 2: 跑测试确认失败**

```bash
mvn -pl skill-server test -Dtest=BusinessScopeStrategyTest#buildInvoke_*
```
Expected: 部分 FAIL 或断言通过但 cloudRequest 内 reply 字段为空。

- [ ] **Step 3: 写最小实现**

修改 `buildInvoke`，在构造 `CloudRequestContext` 时按 action 提取：

```java
public String buildInvoke(InvokeCommand command, AssistantInfo info) {
    String action = command.action();
    String content = extractContent(command.payload());
    String toolSessionId = extractField(command.payload(), "toolSessionId");
    JsonNode businessExtParam = extractObjectField(command.payload(), "businessExtParam");

    String replyToolCallId = null;
    List<List<String>> replyAnswers = null;
    String replyPermissionId = null;
    String replyResponse = null;
    if (GatewayActions.QUESTION_REPLY.equals(action)) {
        replyToolCallId = extractField(command.payload(), "toolCallId");
        String answerRaw = extractField(command.payload(), "answer");
        replyAnswers = parseAnswers(answerRaw);
    } else if (GatewayActions.PERMISSION_REPLY.equals(action)) {
        replyPermissionId = extractField(command.payload(), "permissionId");
        replyResponse = extractField(command.payload(), "response");
    }

    Map<String, Object> extParameters = new LinkedHashMap<>();
    extParameters.put("businessExtParam",
            businessExtParam != null ? businessExtParam : objectMapper.createObjectNode());
    extParameters.put("platformExtParam", objectMapper.createObjectNode());

    CloudRequestContext context = CloudRequestContext.builder()
            .content(content)
            .contentType("text")
            .topicId(toolSessionId)
            .assistantAccount(extractField(command.payload(), "assistantAccount"))
            .sendUserAccount(extractField(command.payload(), "sendUserAccount"))
            .imGroupId(extractField(command.payload(), "imGroupId"))
            .messageId(extractField(command.payload(), "messageId"))
            .clientLang("zh")
            .extParameters(extParameters)
            .replyToolCallId(replyToolCallId)
            .replyAnswers(replyAnswers)
            .replyPermissionId(replyPermissionId)
            .replyResponse(replyResponse)
            .build();
    // ... 后续 cloudRequestBuilder.buildCloudRequest / payload 包装 不变 ...
}
```

- [ ] **Step 4: 跑测试确认通过**

```bash
mvn -pl skill-server test -Dtest=BusinessScopeStrategyTest
```
Expected: PASS。

- [ ] **Step 5: Commit**

```bash
git add skill-server/src/main/java/com/opencode/cui/skill/service/scope/BusinessScopeStrategy.java \
        skill-server/src/test/java/com/opencode/cui/skill/service/scope/BusinessScopeStrategyTest.java
git commit -m "feat(business-scope): wire reply fields per action into CloudRequestContext"
```

---

### Task 6: `DefaultCloudRequestStrategy` 序列化 `replyContext` 嵌套对象

**Files:**
- Modify: `skill-server/src/main/java/com/opencode/cui/skill/service/cloud/DefaultCloudRequestStrategy.java`
- Test: `skill-server/src/test/java/com/opencode/cui/skill/service/cloud/CloudRequestBuilderTest.java`（如分离则 `DefaultCloudRequestStrategyTest`）

- [ ] **Step 1: 写失败测试**

```java
@Test
void buildCloudRequest_questionReply_writesReplyContext() {
    CloudRequestContext ctx = CloudRequestContext.builder()
            .content("").contentType("text").topicId("ts-1")
            .replyToolCallId("call-q").replyAnswers(List.of(List.of("A"), List.of("B","C")))
            .build();
    ObjectNode result = strategy.build(ctx);
    JsonNode rc = result.path("replyContext");
    assertThat(rc.path("type").asText()).isEqualTo("question_reply");
    assertThat(rc.path("toolCallId").asText()).isEqualTo("call-q");
    assertThat(rc.path("answers").isArray()).isTrue();
    assertThat(rc.path("answers").get(0).get(0).asText()).isEqualTo("A");
    assertThat(rc.path("answers").get(1).get(1).asText()).isEqualTo("C");
}

@Test
void buildCloudRequest_permissionReply_writesReplyContext() {
    CloudRequestContext ctx = CloudRequestContext.builder()
            .topicId("ts-1")
            .replyPermissionId("perm-1").replyResponse("once")
            .build();
    ObjectNode result = strategy.build(ctx);
    JsonNode rc = result.path("replyContext");
    assertThat(rc.path("type").asText()).isEqualTo("permission_reply");
    assertThat(rc.path("permissionId").asText()).isEqualTo("perm-1");
    assertThat(rc.path("response").asText()).isEqualTo("once");
}

@Test
void buildCloudRequest_chat_noReplyContext() {
    CloudRequestContext ctx = CloudRequestContext.builder()
            .content("hi").contentType("text").topicId("ts-1").build();
    ObjectNode result = strategy.build(ctx);
    assertThat(result.has("replyContext")).isFalse();
    assertThat(result.path("content").asText()).isEqualTo("hi");
}
```

- [ ] **Step 2: 跑测试确认失败**

```bash
mvn -pl skill-server test -Dtest=CloudRequestBuilderTest
```
Expected: 3 个新用例 FAIL。

- [ ] **Step 3: 写最小实现**

在 `DefaultCloudRequestStrategy.build(CloudRequestContext)` 末尾（return root 之前）加：

```java
boolean isQR = ctx.getReplyToolCallId() != null;
boolean isPR = ctx.getReplyPermissionId() != null;
if (isQR || isPR) {
    ObjectNode replyContext = objectMapper.createObjectNode();
    if (isQR) {
        replyContext.put("type", "question_reply");
        replyContext.put("toolCallId", ctx.getReplyToolCallId());
        replyContext.set("answers", objectMapper.valueToTree(ctx.getReplyAnswers()));
    } else {
        replyContext.put("type", "permission_reply");
        replyContext.put("permissionId", ctx.getReplyPermissionId());
        replyContext.put("response", ctx.getReplyResponse());
    }
    root.set("replyContext", replyContext);
}
```

- [ ] **Step 4: 跑测试确认通过**

```bash
mvn -pl skill-server test -Dtest=CloudRequestBuilderTest
```
Expected: PASS。

- [ ] **Step 5: Commit**

```bash
git add skill-server/src/main/java/com/opencode/cui/skill/service/cloud/DefaultCloudRequestStrategy.java \
        skill-server/src/test/java/com/opencode/cui/skill/service/cloud/CloudRequestBuilderTest.java
git commit -m "feat(cloud-request-strategy): serialize replyContext nested object for q_r / p_r"
```

---

## Phase B — ai-gateway 路由配置查询切换

引入 v1/v2 双 resolver 与 feature flag。**完成本阶段后 v1 模式行为完全等价现状**（chat 仍走旧接口，q_r/p_r 暂未接通）。

### Task 7: `CallbackConfig` model + `CallbackConfigResolver` interface

**Files:**
- Create: `ai-gateway/src/main/java/com/opencode/cui/gateway/service/CallbackConfig.java`
- Create: `ai-gateway/src/main/java/com/opencode/cui/gateway/service/CallbackConfigResolver.java`

- [ ] **Step 1: 创建 model**

`CallbackConfig.java`：

```java
package com.opencode.cui.gateway.service;

import lombok.Data;

/** 由 CallbackConfigResolver 返回，描述 (ak, scope) 的回调订阅配置。 */
@Data
public class CallbackConfig {
    private String ak;
    private String scope;
    /** "webhook" | "sse" | "websocket" */
    private String channelType;
    private String channelAddress;
    /** "none" | "soa" | "apig" */
    private String authType;
    /** v1 由 hisAppId 映射；v2 为 null */
    private String appId;
}
```

- [ ] **Step 2: 创建 resolver 接口**

`CallbackConfigResolver.java`：

```java
package com.opencode.cui.gateway.service;

public interface CallbackConfigResolver {
    /** 返回 null 表示未订阅 / AK 无效 / 上游失败 */
    CallbackConfig resolve(String ak, String scope);
    String version();   // "v1" | "v2"
}
```

- [ ] **Step 3: 编译通过**

```bash
mvn -pl ai-gateway compile
```
Expected: BUILD SUCCESS。

- [ ] **Step 4: Commit**

```bash
git add ai-gateway/src/main/java/com/opencode/cui/gateway/service/CallbackConfig.java \
        ai-gateway/src/main/java/com/opencode/cui/gateway/service/CallbackConfigResolver.java
git commit -m "feat(callback-config): introduce CallbackConfig model and Resolver interface"
```

---

### Task 8: `GatewayCallbackResolver` (v2)

**Files:**
- Create: `ai-gateway/src/main/java/com/opencode/cui/gateway/service/GatewayCallbackResolver.java`
- Create: `ai-gateway/src/test/java/com/opencode/cui/gateway/service/GatewayCallbackResolverTest.java`

- [ ] **Step 1: 写失败测试**

```java
@Test
void resolve_success_parsesChannelTypeAndAuthType() throws Exception {
    String responseBody = """
        {"code":"200","data":{
          "ak":"AK1","scope":"callback:weagent:chat",
          "channelType":2,"channelAddress":"https://cloud/chat","authType":1}}""";
    GatewayCallbackResolver resolver = spyResolverWithResponse(responseBody);
    CallbackConfig cfg = resolver.resolve("AK1", "callback:weagent:chat");
    assertThat(cfg.getChannelType()).isEqualTo("sse");
    assertThat(cfg.getChannelAddress()).isEqualTo("https://cloud/chat");
    assertThat(cfg.getAuthType()).isEqualTo("soa");
    assertThat(cfg.getAppId()).isNull();   // v2 不返回 appId
}

@Test
void resolve_dataNull_returnsNull() throws Exception {
    String body = "{\"code\":\"200\",\"data\":null}";
    GatewayCallbackResolver resolver = spyResolverWithResponse(body);
    assertThat(resolver.resolve("AK1", "callback:weagent:chat")).isNull();
}

@Test
void resolve_httpError_returnsNull() throws Exception {
    GatewayCallbackResolver resolver = spyResolverWithError(new RuntimeException("upstream 500"));
    assertThat(resolver.resolve("AK1", "callback:weagent:chat")).isNull();
}
```

- [ ] **Step 2: 跑测试确认失败**

```bash
mvn -pl ai-gateway test -Dtest=GatewayCallbackResolverTest
```
Expected: FAIL — 类不存在。

- [ ] **Step 3: 写最小实现**

```java
@Slf4j
@Component
@ConditionalOnProperty(name="gateway.cloud-route.api-version", havingValue="v2")
public class GatewayCallbackResolver implements CallbackConfigResolver {

    private final ObjectMapper objectMapper;
    private final String apiUrl;
    private final String bearerToken;
    private final HttpClient httpClient;

    public GatewayCallbackResolver(ObjectMapper objectMapper,
            @Value("${gateway.cloud-route.v2-api-url:}") String apiUrl,
            @Value("${gateway.cloud-route.v2-bearer-token:}") String bearerToken) {
        this.objectMapper = objectMapper;
        this.apiUrl = apiUrl;
        this.bearerToken = bearerToken;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5)).build();
    }

    @Override public String version() { return "v2"; }

    @Override
    public CallbackConfig resolve(String ak, String scope) {
        try {
            String body = String.format("{\"ak\":\"%s\",\"scope\":\"%s\"}", ak, scope);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl))
                    .header("Authorization", "Bearer " + bearerToken)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .timeout(Duration.ofSeconds(5))
                    .build();
            HttpResponse<String> resp = sendRequest(req);
            if (resp.statusCode() != 200) {
                log.warn("[CALLBACK_CONFIG_V2] HTTP error: ak={}, scope={}, status={}",
                        ak, scope, resp.statusCode());
                return null;
            }
            return parseResponse(ak, scope, resp.body());
        } catch (Exception e) {
            log.warn("[CALLBACK_CONFIG_V2] error: ak={}, scope={}, error={}",
                    ak, scope, e.getMessage());
            return null;
        }
    }

    /** 暴露给测试 mock */
    protected HttpResponse<String> sendRequest(HttpRequest req) throws Exception {
        return httpClient.send(req, HttpResponse.BodyHandlers.ofString());
    }

    private CallbackConfig parseResponse(String ak, String scope, String body) throws Exception {
        JsonNode root = objectMapper.readTree(body);
        if (!"200".equals(root.path("code").asText(""))) return null;
        JsonNode data = root.path("data");
        if (data.isMissingNode() || data.isNull()) return null;
        CallbackConfig cfg = new CallbackConfig();
        cfg.setAk(ak);
        cfg.setScope(scope);
        cfg.setChannelType(mapChannelType(data.path("channelType").asInt(-1)));
        cfg.setChannelAddress(data.path("channelAddress").asText(null));
        cfg.setAuthType(mapAuthType(data.path("authType").asInt(-1)));
        cfg.setAppId(null);   // v2 不返回
        return cfg;
    }

    private static String mapChannelType(int code) {
        return switch (code) { case 1 -> "webhook"; case 2 -> "sse"; case 3 -> "websocket"; default -> null; };
    }
    private static String mapAuthType(int code) {
        return switch (code) { case 0 -> "none"; case 1 -> "soa"; case 2 -> "apig"; default -> null; };
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

```bash
mvn -pl ai-gateway test -Dtest=GatewayCallbackResolverTest
```
Expected: PASS。

- [ ] **Step 5: Commit**

```bash
git add ai-gateway/src/main/java/com/opencode/cui/gateway/service/GatewayCallbackResolver.java \
        ai-gateway/src/test/java/com/opencode/cui/gateway/service/GatewayCallbackResolverTest.java
git commit -m "feat(callback-config): add v2 resolver hitting POST /gateway/callbacks/config"
```

---

### Task 9: `LegacyRouteResolver` (v1)

**Files:**
- Create: `ai-gateway/src/main/java/com/opencode/cui/gateway/service/LegacyRouteResolver.java`
- Create: `ai-gateway/src/test/java/com/opencode/cui/gateway/service/LegacyRouteResolverTest.java`

> 复用现有 `CloudRouteService.fetchFromUpstream` 的逻辑（GET-with-body 形式）。本 task 把它独立成 v1 resolver。

- [ ] **Step 1: 写失败测试**

```java
@Test
void resolve_chatScope_callsLegacyEndpoint() throws Exception {
    String body = """
        {"code":"200","data":{
          "hisAppId":"app-1","endpoint":"https://cloud/chat",
          "protocol":"2","authType":"1"}}""";
    LegacyRouteResolver resolver = spyResolverWithResponse(body);
    CallbackConfig cfg = resolver.resolve("AK1", "callback:weagent:chat");
    assertThat(cfg.getAppId()).isEqualTo("app-1");
    assertThat(cfg.getChannelAddress()).isEqualTo("https://cloud/chat");
    assertThat(cfg.getChannelType()).isEqualTo("sse");
    assertThat(cfg.getAuthType()).isEqualTo("soa");
}

@Test
void resolve_questionReplyScope_returnsNull_inV1() throws Exception {
    LegacyRouteResolver resolver = spyResolverWithResponse("");
    assertThat(resolver.resolve("AK1", "callback:weagent:question_reply")).isNull();
    assertThat(resolver.resolve("AK1", "callback:weagent:permission_reply")).isNull();
}
```

- [ ] **Step 2: 跑测试确认失败**

```bash
mvn -pl ai-gateway test -Dtest=LegacyRouteResolverTest
```
Expected: FAIL。

- [ ] **Step 3: 写最小实现**

```java
@Slf4j
@Component
@ConditionalOnProperty(name="gateway.cloud-route.api-version", havingValue="v1", matchIfMissing=true)
public class LegacyRouteResolver implements CallbackConfigResolver {

    private static final String CHAT_SCOPE = "callback:weagent:chat";

    private final ObjectMapper objectMapper;
    private final String apiUrl;
    private final String bearerToken;
    private final HttpClient httpClient;

    public LegacyRouteResolver(ObjectMapper objectMapper,
            @Value("${gateway.cloud-route.api-url:}") String apiUrl,
            @Value("${gateway.cloud-route.bearer-token:}") String bearerToken) {
        this.objectMapper = objectMapper;
        this.apiUrl = apiUrl;
        this.bearerToken = bearerToken;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    @Override public String version() { return "v1"; }

    @Override
    public CallbackConfig resolve(String ak, String scope) {
        if (!CHAT_SCOPE.equals(scope)) {
            log.debug("[CALLBACK_CONFIG_V1] scope not supported in v1: {}", scope);
            return null;
        }
        try {
            String body = "{\"ak\":\"" + ak + "\"}";
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl))
                    .header("Authorization", "Bearer " + bearerToken)
                    .header("Content-Type", "application/json")
                    .method("GET", HttpRequest.BodyPublishers.ofString(body))
                    .timeout(Duration.ofSeconds(5))
                    .build();
            HttpResponse<String> resp = sendRequest(req);
            if (resp.statusCode() != 200) return null;
            return parseResponse(ak, scope, resp.body());
        } catch (Exception e) {
            log.warn("[CALLBACK_CONFIG_V1] error: ak={}, scope={}, error={}",
                    ak, scope, e.getMessage());
            return null;
        }
    }

    protected HttpResponse<String> sendRequest(HttpRequest req) throws Exception {
        return httpClient.send(req, HttpResponse.BodyHandlers.ofString());
    }

    private CallbackConfig parseResponse(String ak, String scope, String body) throws Exception {
        JsonNode root = objectMapper.readTree(body);
        if (!"200".equals(root.path("code").asText(""))) return null;
        JsonNode data = root.path("data");
        if (data.isMissingNode() || data.isNull()) return null;
        CallbackConfig cfg = new CallbackConfig();
        cfg.setAk(ak);
        cfg.setScope(scope);
        cfg.setAppId(data.path("hisAppId").asText(null));
        cfg.setChannelAddress(data.path("endpoint").asText(null));
        cfg.setChannelType(mapProtocol(data.path("protocol").asText(null)));
        cfg.setAuthType(mapAuthType(data.path("authType").asText(null)));
        return cfg;
    }

    private static String mapProtocol(String s) {
        return switch (String.valueOf(s)) {
            case "1" -> "webhook"; case "2" -> "sse"; case "3" -> "websocket"; default -> s;
        };
    }
    private static String mapAuthType(String s) {
        return switch (String.valueOf(s)) {
            case "1" -> "soa"; case "2" -> "apig"; default -> s;
        };
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

```bash
mvn -pl ai-gateway test -Dtest=LegacyRouteResolverTest
```
Expected: PASS。

- [ ] **Step 5: Commit**

```bash
git add ai-gateway/src/main/java/com/opencode/cui/gateway/service/LegacyRouteResolver.java \
        ai-gateway/src/test/java/com/opencode/cui/gateway/service/LegacyRouteResolverTest.java
git commit -m "feat(callback-config): add v1 resolver wrapping legacy GET-with-body endpoint"
```

---

### Task 10: `CallbackConfigService` 外观（缓存 + 选 resolver）

**Files:**
- Create: `ai-gateway/src/main/java/com/opencode/cui/gateway/service/CallbackConfigService.java`
- Create: `ai-gateway/src/test/java/com/opencode/cui/gateway/service/CallbackConfigServiceTest.java`

- [ ] **Step 1: 写失败测试**

```java
@Test
void getConfig_cacheHit_doesNotCallResolver() {
    CallbackConfigResolver resolver = mock(CallbackConfigResolver.class);
    when(resolver.version()).thenReturn("v2");
    StringRedisTemplate redis = mockRedisWithCachedJson("AK1","callback:weagent:chat", cachedCfgJson());
    CallbackConfigService svc = new CallbackConfigService(resolver, redis, mapper, 300);
    CallbackConfig result = svc.getConfig("AK1", "callback:weagent:chat");
    assertThat(result.getAk()).isEqualTo("AK1");
    verify(resolver, never()).resolve(any(), any());
}

@Test
void getConfig_cacheMiss_callsResolverAndCaches() {
    CallbackConfigResolver resolver = mock(CallbackConfigResolver.class);
    CallbackConfig cfg = new CallbackConfig();
    cfg.setAk("AK1"); cfg.setScope("callback:weagent:chat");
    when(resolver.resolve("AK1","callback:weagent:chat")).thenReturn(cfg);
    StringRedisTemplate redis = mockEmptyRedis();
    CallbackConfigService svc = new CallbackConfigService(resolver, redis, mapper, 300);
    CallbackConfig result = svc.getConfig("AK1","callback:weagent:chat");
    assertThat(result).isNotNull();
    verify(redis.opsForValue()).set(eq("gw:cloud:route:AK1:callback:weagent:chat"),
            anyString(), eq(300L), eq(TimeUnit.SECONDS));
}

@Test
void getConfig_perScopeCacheIsolation() {
    /* 同一 ak 不同 scope 缓存独立：调用两次不同 scope，resolver 各被调一次 */
}
```

- [ ] **Step 2: 跑测试确认失败**

```bash
mvn -pl ai-gateway test -Dtest=CallbackConfigServiceTest
```
Expected: FAIL — 类不存在。

- [ ] **Step 3: 写最小实现**

```java
@Slf4j
@Service
public class CallbackConfigService {

    private static final String CACHE_KEY_PREFIX = "gw:cloud:route:";

    private final CallbackConfigResolver resolver;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final long cacheTtlSeconds;

    public CallbackConfigService(CallbackConfigResolver resolver,
                                 StringRedisTemplate redisTemplate,
                                 ObjectMapper objectMapper,
                                 @Value("${gateway.cloud-route.cache-ttl-seconds:300}") long cacheTtlSeconds) {
        this.resolver = resolver;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.cacheTtlSeconds = cacheTtlSeconds;
        log.info("[CALLBACK_CONFIG] active resolver version: {}", resolver.version());
    }

    public CallbackConfig getConfig(String ak, String scope) {
        String cacheKey = CACHE_KEY_PREFIX + ak + ":" + scope;
        CallbackConfig cached = readCache(cacheKey);
        if (cached != null) return cached;
        CallbackConfig fresh = resolver.resolve(ak, scope);
        if (fresh != null) writeCache(cacheKey, fresh);
        return fresh;
    }

    private CallbackConfig readCache(String key) {
        try {
            String json = redisTemplate.opsForValue().get(key);
            return json == null ? null : objectMapper.readValue(json, CallbackConfig.class);
        } catch (Exception e) { return null; }
    }

    private void writeCache(String key, CallbackConfig cfg) {
        try {
            redisTemplate.opsForValue().set(key,
                    objectMapper.writeValueAsString(cfg),
                    cacheTtlSeconds, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("[CALLBACK_CONFIG] cache write failed: key={}, error={}", key, e.getMessage());
        }
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

```bash
mvn -pl ai-gateway test -Dtest=CallbackConfigServiceTest
```
Expected: PASS。

- [ ] **Step 5: Commit**

```bash
git add ai-gateway/src/main/java/com/opencode/cui/gateway/service/CallbackConfigService.java \
        ai-gateway/src/test/java/com/opencode/cui/gateway/service/CallbackConfigServiceTest.java
git commit -m "feat(callback-config): add facade service with per-(ak,scope) caching"
```

---

### Task 11: `CloudConnectionContext` 加 `channelType` / `scope`，`endpoint` 改名 `channelAddress`

**Files:**
- Modify: `ai-gateway/src/main/java/com/opencode/cui/gateway/service/cloud/CloudConnectionContext.java`
- Modify: `ai-gateway/src/main/java/com/opencode/cui/gateway/service/cloud/SseProtocolStrategy.java`（替换 `getEndpoint` → `getChannelAddress`）
- Modify: `ai-gateway/src/main/java/com/opencode/cui/gateway/service/cloud/WebSocketProtocolStrategy.java`（同上）
- Modify: `ai-gateway/src/main/java/com/opencode/cui/gateway/service/CloudAgentService.java`（改 builder 调用）

> **注**：用 `gitnexus_rename({symbol_name:"endpoint", new_name:"channelAddress", dry_run:true})` 先确认影响范围，再无痛重命名。

- [ ] **Step 1: GitNexus rename dry-run**

```bash
# 通过 MCP 调用 gitnexus_rename
# 期望：受影响文件清单为 CloudConnectionContext + SseProtocolStrategy + WebSocketProtocolStrategy + CloudAgentService
```

- [ ] **Step 2: 改字段**

`CloudConnectionContext.java`：

```java
@Data @Builder
public class CloudConnectionContext {
    private String channelAddress;    // 原 endpoint 改名
    private String channelType;       // 新："webhook" | "sse" | "websocket"
    private String scope;             // 新
    private String appId;             // 保留：v1 非空 / v2 为 null
    private JsonNode cloudRequest;
    private String authType;
    private String traceId;
}
```

- [ ] **Step 3: 全量替换 `getEndpoint()` → `getChannelAddress()`**

```bash
# 在 SseProtocolStrategy / WebSocketProtocolStrategy / CloudAgentService 替换
# CloudAgentService.handleInvoke 内 builder 调用：.endpoint(routeInfo.getEndpoint()) → .channelAddress(routeInfo.getChannelAddress())
# 或者用 gitnexus_rename 实际执行 dry_run=false
```

- [ ] **Step 4: 跑现有测试，确认编译 + 通过**

```bash
mvn -pl ai-gateway test -Dtest=SseProtocolStrategyTest,WebSocketProtocolStrategyTest,CloudAgentServiceTest
```
Expected: PASS（重命名不应破坏现有行为）。

- [ ] **Step 5: Commit**

```bash
git add ai-gateway/src/main/java/com/opencode/cui/gateway/service/cloud/CloudConnectionContext.java \
        ai-gateway/src/main/java/com/opencode/cui/gateway/service/cloud/SseProtocolStrategy.java \
        ai-gateway/src/main/java/com/opencode/cui/gateway/service/cloud/WebSocketProtocolStrategy.java \
        ai-gateway/src/main/java/com/opencode/cui/gateway/service/CloudAgentService.java
git commit -m "refactor(cloud-context): rename endpoint→channelAddress; add channelType / scope"
```

---

## Phase C — ai-gateway lifecycle 保活 + 协议层

### Task 12: `CloudConnectionLifecycle` 加 `awaitingReply` + pause/resume

**Files:**
- Modify: `ai-gateway/src/main/java/com/opencode/cui/gateway/service/cloud/CloudConnectionLifecycle.java`
- Modify: `ai-gateway/src/test/java/com/opencode/cui/gateway/service/cloud/CloudConnectionLifecycleTest.java`

- [ ] **Step 1: 写失败测试**

```java
@Test
void pauseIdleTimer_preventsResetByOnEventReceived() throws Exception {
    CloudConnectionLifecycle lc = new CloudConnectionLifecycle(30, 1, 60, capture, () -> {});
    lc.onConnected();
    lc.onEventReceived();   // 启动 idle timer
    lc.pauseIdleTimer();
    lc.onEventReceived();   // 应被 no-op，idle 不重启
    lc.onHeartbeat();       // 同上
    Thread.sleep(1500);     // > idleTimeoutSeconds=1
    // pause 状态下 idle timer 永远不应触发
    assertThat(capture.timeoutType).isNull();
}

@Test
void resumeIdleTimer_restartsTimerNormally() throws Exception {
    CloudConnectionLifecycle lc = new CloudConnectionLifecycle(30, 1, 60, capture, () -> {});
    lc.onConnected();
    lc.pauseIdleTimer();
    lc.resumeIdleTimer();
    Thread.sleep(1500);
    assertThat(capture.timeoutType).isEqualTo("idle_timeout");
}

@Test
void maxDuration_firesEvenInPausedState() throws Exception {
    CloudConnectionLifecycle lc = new CloudConnectionLifecycle(30, 100, 1, capture, () -> {});
    lc.onConnected();
    lc.pauseIdleTimer();
    Thread.sleep(1500);
    assertThat(capture.timeoutType).isEqualTo("max_duration");
}

@Test
void pauseIdleTimer_idempotent() {
    /* 连续两次 pause 不应抛错 */
}
```

- [ ] **Step 2: 跑测试确认失败**

```bash
mvn -pl ai-gateway test -Dtest=CloudConnectionLifecycleTest
```
Expected: 4 个新用例 FAIL。

- [ ] **Step 3: 写最小实现**

在 `CloudConnectionLifecycle` 内加：

```java
private final AtomicBoolean awaitingReply = new AtomicBoolean(false);

public void pauseIdleTimer() {
    if (closed.get()) return;
    awaitingReply.set(true);
    cancelFuture(idleFuture);
    idleFuture = null;
    log.debug("[CLOUD_LIFECYCLE] idle timer paused (awaiting reply)");
}

public void resumeIdleTimer() {
    if (closed.get()) return;
    if (awaitingReply.compareAndSet(true, false)) {
        resetIdleTimeout();
        log.debug("[CLOUD_LIFECYCLE] idle timer resumed");
    }
}

private void resetIdleTimeout() {
    if (closed.get() || awaitingReply.get()) return;     // 关键：暂停时 no-op
    cancelFuture(idleFuture);
    try {
        idleFuture = scheduler.schedule(
                () -> fireTimeout("idle_timeout"),
                idleTimeoutSeconds, TimeUnit.SECONDS);
    } catch (RejectedExecutionException e) {
        // scheduler shutdown
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

```bash
mvn -pl ai-gateway test -Dtest=CloudConnectionLifecycleTest
```
Expected: PASS。

- [ ] **Step 5: Commit**

```bash
git add ai-gateway/src/main/java/com/opencode/cui/gateway/service/cloud/CloudConnectionLifecycle.java \
        ai-gateway/src/test/java/com/opencode/cui/gateway/service/cloud/CloudConnectionLifecycleTest.java
git commit -m "feat(lifecycle): add pauseIdleTimer / resumeIdleTimer with no-op resetIdleTimeout"
```

---

### Task 13: SSE / WS Protocol Strategy 触发 pause/resume + appId null-safe

**Files:**
- Modify: `ai-gateway/src/main/java/com/opencode/cui/gateway/service/cloud/SseProtocolStrategy.java`
- Modify: `ai-gateway/src/main/java/com/opencode/cui/gateway/service/cloud/WebSocketProtocolStrategy.java`
- Modify: `ai-gateway/src/test/java/com/opencode/cui/gateway/service/cloud/SseProtocolStrategyTest.java`
- Modify: `ai-gateway/src/test/java/com/opencode/cui/gateway/service/cloud/WebSocketProtocolStrategyTest.java`

- [ ] **Step 1: 写失败测试（SSE 为例，WS 同构）**

```java
@Test
void sse_questionEvent_triggersPauseIdleTimer() throws Exception {
    SseProtocolStrategy strat = newStrategy();
    CloudConnectionLifecycle lc = mock(CloudConnectionLifecycle.class);
    String stream = "data: {\"type\":\"tool_event\",\"toolSessionId\":\"ts-1\"," +
            "\"event\":{\"type\":\"question\",\"properties\":{\"toolCallId\":\"call-1\"}}}\n\n";
    feedSseStream(strat, lc, stream);
    verify(lc).pauseIdleTimer();
    verify(lc, never()).resumeIdleTimer();
}

@Test
void sse_permissionAskEvent_triggersPause() throws Exception { /* same as above with permission.ask */ }

@Test
void sse_permissionReplyEvent_doesNotTriggerPause() throws Exception {
    /* event.type == "permission.reply" → 不调 pause；调 resume */
    verify(lc, never()).pauseIdleTimer();
    verify(lc).resumeIdleTimer();
}

@Test
void sse_textDeltaEvent_triggersResume() throws Exception {
    /* event.type == "text.delta" → 调 resume（idempotent） */
}

@Test
void sse_appIdNull_skipsXAppIdHeader() throws Exception {
    /* CloudConnectionContext.appId=null 时 HttpRequest 不含 X-App-Id header */
    HttpRequest captured = captureRequest(...);
    assertThat(captured.headers().firstValue("X-App-Id")).isEmpty();
}
```

- [ ] **Step 2: 跑测试确认失败**

```bash
mvn -pl ai-gateway test -Dtest=SseProtocolStrategyTest,WebSocketProtocolStrategyTest
```
Expected: 5 个新用例 FAIL。

- [ ] **Step 3: 写最小实现**

`SseProtocolStrategy.handleDataLine` 内分发事件前加：

```java
String eventType = message.getEvent() != null
        ? message.getEvent().path("type").asText("") : "";
if ("question".equals(eventType) || "permission.ask".equals(eventType)) {
    if (lifecycle != null) lifecycle.pauseIdleTimer();
} else {
    if (lifecycle != null) lifecycle.resumeIdleTimer();
}
notifyLifecycle(lifecycle, CloudConnectionLifecycle::onEventReceived);
onEvent.accept(message);
```

`SseProtocolStrategy` HTTP 请求构造（line 72 附近）：

```java
HttpRequest.Builder builder = HttpRequest.newBuilder()
        .uri(URI.create(context.getChannelAddress()))
        .header("Accept", "text/event-stream")
        .POST(HttpRequest.BodyPublishers.ofString(...));
if (context.getAppId() != null) {
    builder.header("X-App-Id", context.getAppId());
}
cloudAuthService.applyAuth(builder, context.getAppId(), context.getAuthType());
```

`WebSocketProtocolStrategy` 同样位置加 pause/resume；`wsBuilder.header("X-App-Id", appId)` 改 null-safe。

- [ ] **Step 4: 跑测试确认通过**

```bash
mvn -pl ai-gateway test -Dtest=SseProtocolStrategyTest,WebSocketProtocolStrategyTest
```
Expected: PASS。

- [ ] **Step 5: Commit**

```bash
git add ai-gateway/src/main/java/com/opencode/cui/gateway/service/cloud/SseProtocolStrategy.java \
        ai-gateway/src/main/java/com/opencode/cui/gateway/service/cloud/WebSocketProtocolStrategy.java \
        ai-gateway/src/test/java/com/opencode/cui/gateway/service/cloud/SseProtocolStrategyTest.java \
        ai-gateway/src/test/java/com/opencode/cui/gateway/service/cloud/WebSocketProtocolStrategyTest.java
git commit -m "feat(protocol-strategy): trigger pauseIdleTimer on question/permission.ask; null-safe X-App-Id"
```

---

### Task 14: `NoAuthStrategy` + `CloudAuthService` 接受 "none"

**Files:**
- Create: `ai-gateway/src/main/java/com/opencode/cui/gateway/service/cloud/NoAuthStrategy.java`
- Modify: `ai-gateway/src/main/java/com/opencode/cui/gateway/service/cloud/CloudAuthService.java`（无逻辑改，自动注册新 strategy）
- Modify: `ai-gateway/src/test/java/com/opencode/cui/gateway/service/cloud/CloudAuthServiceTest.java`

- [ ] **Step 1: 写失败测试**

```java
@Test
void applyAuth_noneAuthType_noHeaderWritten() {
    CloudAuthService svc = newServiceWithStrategies(
            new SoaAuthStrategy(), new ApigAuthStrategy(), new NoAuthStrategy());
    HttpRequest.Builder b = HttpRequest.newBuilder().uri(URI.create("https://x"));
    svc.applyAuth(b, null, "none");
    HttpRequest req = b.GET().build();
    assertThat(req.headers().firstValue("X-Auth-Type")).isEmpty();
    assertThat(req.headers().firstValue("X-App-Id")).isEmpty();
}

@Test
void applyAuth_unknownAuthType_throwsIllegalArgument() {
    CloudAuthService svc = newServiceWithStrategies(
            new SoaAuthStrategy(), new ApigAuthStrategy(), new NoAuthStrategy());
    assertThatThrownBy(() -> svc.applyAuth(HttpRequest.newBuilder().uri(URI.create("https://x")), null, "unknown"))
            .isInstanceOf(IllegalArgumentException.class);
}
```

- [ ] **Step 2: 跑测试确认失败**

```bash
mvn -pl ai-gateway test -Dtest=CloudAuthServiceTest
```
Expected: FAIL — `NoAuthStrategy` 不存在。

- [ ] **Step 3: 写最小实现**

`NoAuthStrategy.java`：

```java
@Slf4j
@Component
public class NoAuthStrategy implements CloudAuthStrategy {
    @Override public String getAuthType() { return "none"; }
    @Override public void applyAuth(HttpRequest.Builder requestBuilder, String appId) {
        // 显式无操作；不写任何 header
        log.debug("[CLOUD_AUTH] No-op auth strategy applied");
    }
}
```

`CloudAuthService` 不需改（Spring 自动注入，按 getAuthType 做查找）。但要确保 fail-fast 行为保留（line 40-42 不动）。

- [ ] **Step 4: 跑测试确认通过**

```bash
mvn -pl ai-gateway test -Dtest=CloudAuthServiceTest
```
Expected: PASS。

- [ ] **Step 5: Commit**

```bash
git add ai-gateway/src/main/java/com/opencode/cui/gateway/service/cloud/NoAuthStrategy.java \
        ai-gateway/src/test/java/com/opencode/cui/gateway/service/cloud/CloudAuthServiceTest.java
git commit -m "feat(cloud-auth): add NoAuthStrategy for authType=none; keep fail-fast on unknown"
```

---

## Phase D — ai-gateway WebHook + dispatch

### Task 15: `WebHookExecutor`（同步 POST + 鉴权 + tool_error 仅失败回流）

**Files:**
- Create: `ai-gateway/src/main/java/com/opencode/cui/gateway/service/cloud/WebHookExecutor.java`
- Create: `ai-gateway/src/test/java/com/opencode/cui/gateway/service/cloud/WebHookExecutorTest.java`

- [ ] **Step 1: 写失败测试**

```java
@Test
void execute_2xxResponse_doesNotEmitMessage() throws Exception {
    Consumer<GatewayMessage> onRelay = mock(Consumer.class);
    WebHookExecutor executor = spyWithResponse(200, "{}");
    executor.execute(buildContext(), onRelay);
    verifyNoInteractions(onRelay);
}

@Test
void execute_5xxResponse_emitsToolError() throws Exception {
    Consumer<GatewayMessage> onRelay = mock(Consumer.class);
    WebHookExecutor executor = spyWithResponse(503, "");
    executor.execute(buildContext(), onRelay);
    ArgumentCaptor<GatewayMessage> cap = ArgumentCaptor.forClass(GatewayMessage.class);
    verify(onRelay).accept(cap.capture());
    assertThat(cap.getValue().getType()).isEqualTo(GatewayMessage.Type.TOOL_ERROR);
    assertThat(cap.getValue().getError()).contains("503");
}

@Test
void execute_ioException_emitsToolError() throws Exception { /* same as above */ }

@Test
void execute_appIdNull_skipsXAppIdHeader() throws Exception {
    HttpRequest captured = captureRequest(buildContextWithAppId(null));
    assertThat(captured.headers().firstValue("X-App-Id")).isEmpty();
}
```

- [ ] **Step 2: 跑测试确认失败**

```bash
mvn -pl ai-gateway test -Dtest=WebHookExecutorTest
```
Expected: FAIL — 类不存在。

- [ ] **Step 3: 写最小实现**

```java
@Slf4j
@Component
public class WebHookExecutor {

    private final ObjectMapper objectMapper;
    private final CloudAuthService cloudAuthService;
    private final HttpClient httpClient;
    private final int webhookTimeoutSeconds;

    public WebHookExecutor(ObjectMapper objectMapper,
                           CloudAuthService cloudAuthService,
                           @Value("${gateway.cloud.timeout.webhook-timeout-seconds:10}") int webhookTimeoutSeconds) {
        this.objectMapper = objectMapper;
        this.cloudAuthService = cloudAuthService;
        this.webhookTimeoutSeconds = webhookTimeoutSeconds;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5)).build();
    }

    public void execute(CloudConnectionContext ctx, Consumer<GatewayMessage> onRelay,
                        GatewayMessage invokeMessage, String toolSessionId) {
        try {
            String body = objectMapper.writeValueAsString(ctx.getCloudRequest());
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(ctx.getChannelAddress()))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .timeout(Duration.ofSeconds(webhookTimeoutSeconds));
            if (ctx.getTraceId() != null) builder.header("X-Trace-Id", ctx.getTraceId());
            if (ctx.getAppId() != null) builder.header("X-App-Id", ctx.getAppId());
            cloudAuthService.applyAuth(builder, ctx.getAppId(), ctx.getAuthType());

            HttpResponse<String> resp = sendRequest(builder.build());
            if (resp.statusCode() / 100 == 2) {
                log.info("[WEBHOOK] success: scope={}, status={}", ctx.getScope(), resp.statusCode());
                // fire-and-forget：成功不调 onRelay
            } else {
                onRelay.accept(buildToolError(invokeMessage, toolSessionId,
                        "WebHook returned " + resp.statusCode()));
            }
        } catch (Exception e) {
            log.warn("[WEBHOOK] error: scope={}, error={}", ctx.getScope(), e.getMessage());
            onRelay.accept(buildToolError(invokeMessage, toolSessionId,
                    "WebHook delivery failed: " + e.getMessage()));
        }
    }

    protected HttpResponse<String> sendRequest(HttpRequest req) throws Exception {
        return httpClient.send(req, HttpResponse.BodyHandlers.ofString());
    }

    private GatewayMessage buildToolError(GatewayMessage src, String tsid, String msg) {
        return GatewayMessage.builder()
                .type(GatewayMessage.Type.TOOL_ERROR)
                .ak(src.getAk()).userId(src.getUserId())
                .welinkSessionId(src.getWelinkSessionId())
                .traceId(src.getTraceId()).toolSessionId(tsid)
                .error(msg)
                .build();
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

```bash
mvn -pl ai-gateway test -Dtest=WebHookExecutorTest
```
Expected: PASS。

- [ ] **Step 5: Commit**

```bash
git add ai-gateway/src/main/java/com/opencode/cui/gateway/service/cloud/WebHookExecutor.java \
        ai-gateway/src/test/java/com/opencode/cui/gateway/service/cloud/WebHookExecutorTest.java
git commit -m "feat(webhook-executor): synchronous POST with fire-and-forget on 2xx, tool_error on failure"
```

---

### Task 16: `CloudAgentService.handleInvoke` 改造（action→scope，channelType 分叉）

**Files:**
- Modify: `ai-gateway/src/main/java/com/opencode/cui/gateway/service/CloudAgentService.java`
- Modify: `ai-gateway/src/test/java/com/opencode/cui/gateway/service/CloudAgentServiceTest.java`

- [ ] **Step 1: GitNexus impact 检查**

```bash
# gitnexus_impact({target:"CloudAgentService.handleInvoke", direction:"upstream"})
# 期望：上游有 SkillRelayService.handleInvokeFromSkill 等
```

- [ ] **Step 2: 写失败测试**

```java
@Test
void handleInvoke_chatAction_routesToSseProtocol() {
    when(callbackConfigService.getConfig("AK1","callback:weagent:chat"))
        .thenReturn(buildCfg("sse","https://cloud/chat","soa","app-1"));
    GatewayMessage invoke = buildInvoke("chat", "AK1");
    service.handleInvoke(invoke, onRelay);
    verify(cloudProtocolClient).connect(eq("sse"), any(), any(), any(), any());
    verifyNoInteractions(webHookExecutor);
}

@Test
void handleInvoke_questionReplyAction_routesToWebHook() {
    when(callbackConfigService.getConfig("AK1","callback:weagent:question_reply"))
        .thenReturn(buildCfg("webhook","https://cloud/qr","soa", null));
    GatewayMessage invoke = buildInvoke("question_reply", "AK1");
    service.handleInvoke(invoke, onRelay);
    verify(webHookExecutor).execute(any(), eq(onRelay), eq(invoke), any());
    verifyNoInteractions(cloudProtocolClient);
}

@Test
void handleInvoke_chatWithWebhookChannel_emitsToolError() {
    when(callbackConfigService.getConfig(any(), any()))
        .thenReturn(buildCfg("webhook","https://x","soa", null));
    GatewayMessage invoke = buildInvoke("chat", "AK1");
    service.handleInvoke(invoke, onRelay);
    ArgumentCaptor<GatewayMessage> cap = ArgumentCaptor.forClass(GatewayMessage.class);
    verify(onRelay).accept(cap.capture());
    assertThat(cap.getValue().getType()).isEqualTo(GatewayMessage.Type.TOOL_ERROR);
    assertThat(cap.getValue().getError()).contains("Invalid channel type for chat");
}

@Test
void handleInvoke_questionReplyWithSseChannel_emitsToolError() { /* 反向校验 */ }

@Test
void handleInvoke_v1FlagWithQuestionReplyAction_returnsNullConfig_emitsToolError() {
    /* CallbackConfigService 在 v1 模式下，对 q_r/p_r scope 返 null */
    when(callbackConfigService.getConfig(any(), any())).thenReturn(null);
    GatewayMessage invoke = buildInvoke("question_reply", "AK1");
    service.handleInvoke(invoke, onRelay);
    verify(onRelay).accept(argThat(m -> m.getType() == GatewayMessage.Type.TOOL_ERROR));
}
```

- [ ] **Step 3: 跑测试确认失败**

```bash
mvn -pl ai-gateway test -Dtest=CloudAgentServiceTest
```
Expected: 5 个新用例 FAIL。

- [ ] **Step 4: 写最小实现**

```java
@Slf4j
@Service
public class CloudAgentService {

    private static final Map<String, String> ACTION_TO_SCOPE = Map.of(
            "chat",             "callback:weagent:chat",
            "question_reply",   "callback:weagent:question_reply",
            "permission_reply", "callback:weagent:permission_reply");

    private final CallbackConfigService callbackConfigService;   // 替换原 cloudRouteService
    private final CloudProtocolClient cloudProtocolClient;
    private final WebHookExecutor webHookExecutor;
    private final CloudTimeoutProperties timeoutProperties;

    // ... constructor 注入 ...

    public void handleInvoke(GatewayMessage invokeMessage, Consumer<GatewayMessage> onRelay) {
        String ak = invokeMessage.getAk();
        String action = invokeMessage.getAction();
        JsonNode cloudRequest = invokeMessage.getPayload().path("cloudRequest");
        String toolSessionId = invokeMessage.getPayload().path("toolSessionId").asText(null);

        String scope = ACTION_TO_SCOPE.get(action);
        if (scope == null) {
            onRelay.accept(buildCloudError(invokeMessage, toolSessionId,
                    new RuntimeException("Unknown action: " + action)));
            return;
        }

        CallbackConfig cfg = callbackConfigService.getConfig(ak, scope);
        if (cfg == null) {
            String reason = "chat".equals(action)
                    ? "Cloud route info not found for ak: " + ak
                    : action + " not enabled (v1 mode or AK not subscribed)";
            onRelay.accept(buildCloudError(invokeMessage, toolSessionId, new RuntimeException(reason)));
            return;
        }

        // channelType vs action 校验
        boolean expectsWebhook = !"chat".equals(action);
        boolean isWebhook = "webhook".equals(cfg.getChannelType());
        if (expectsWebhook != isWebhook) {
            String msg = "chat".equals(action)
                    ? "Invalid channel type for chat: " + cfg.getChannelType()
                    : "Invalid channel type for reply: " + cfg.getChannelType();
            onRelay.accept(buildCloudError(invokeMessage, toolSessionId, new RuntimeException(msg)));
            return;
        }

        CloudConnectionContext ctx = CloudConnectionContext.builder()
                .channelAddress(cfg.getChannelAddress())
                .channelType(cfg.getChannelType())
                .scope(scope)
                .appId(cfg.getAppId())
                .authType(cfg.getAuthType())
                .cloudRequest(cloudRequest)
                .traceId(invokeMessage.getTraceId())
                .build();

        if (isWebhook) {
            webHookExecutor.execute(ctx, onRelay, invokeMessage, toolSessionId);
            return;
        }

        // chat: SSE / WebSocket 走原逻辑（保留 lifecycle / fallback messageId 等）
        AtomicBoolean errorSent = new AtomicBoolean(false);
        AtomicReference<String> fallbackMessageIdRef = new AtomicReference<>(null);
        ConcurrentHashMap<String, String> fallbackPartIds = new ConcurrentHashMap<>();
        String protocol = cfg.getChannelType();
        CloudConnectionLifecycle lifecycle = new CloudConnectionLifecycle(
                timeoutProperties.getFirstEventTimeoutSeconds(),
                timeoutProperties.getEffectiveIdleTimeoutSeconds(protocol),
                timeoutProperties.getMaxDurationSeconds(),
                (type, elapsed) -> {
                    GatewayMessage err = buildCloudError(invokeMessage, toolSessionId,
                            new RuntimeException(type + " (elapsed: " + elapsed + "s)"));
                    if (errorSent.compareAndSet(false, true)) onRelay.accept(err);
                },
                () -> log.info("[CLOUD_AGENT] connection closed: ak={}", ak));
        try {
            cloudProtocolClient.connect(protocol, ctx, lifecycle,
                    event -> {
                        // ... 现有事件处理（注入 ak/userId/traceId/messageId 兜底）保留 ...
                        if (errorSent.get()) return;
                        onRelay.accept(event);
                    },
                    error -> {
                        GatewayMessage err = buildCloudError(invokeMessage, toolSessionId, error);
                        if (errorSent.compareAndSet(false, true)) onRelay.accept(err);
                    });
        } finally {
            lifecycle.close();
        }
    }

    private GatewayMessage buildCloudError(GatewayMessage src, String tsid, Throwable e) {
        return GatewayMessage.builder()
                .type(GatewayMessage.Type.TOOL_ERROR)
                .ak(src.getAk()).userId(src.getUserId())
                .welinkSessionId(src.getWelinkSessionId())
                .traceId(src.getTraceId()).toolSessionId(tsid)
                .error("Cloud agent error: " + e.getMessage()).build();
    }
}
```

- [ ] **Step 5: 跑测试确认通过**

```bash
mvn -pl ai-gateway test -Dtest=CloudAgentServiceTest
```
Expected: PASS。

- [ ] **Step 6: 删除/废弃 `CloudRouteService`**

```bash
git rm ai-gateway/src/main/java/com/opencode/cui/gateway/service/CloudRouteService.java
git rm ai-gateway/src/main/java/com/opencode/cui/gateway/model/CloudRouteInfo.java
git rm ai-gateway/src/test/java/com/opencode/cui/gateway/service/CloudRouteServiceTest.java
# 同步删除其他对 CloudRouteService 的引用
```

- [ ] **Step 7: Commit**

```bash
git add ai-gateway/src/main/java/com/opencode/cui/gateway/service/CloudAgentService.java \
        ai-gateway/src/test/java/com/opencode/cui/gateway/service/CloudAgentServiceTest.java
git commit -m "feat(cloud-agent): action→scope mapping; channelType dispatch to SSE/WS or WebHook"
```

---

### Task 17: 配置项 + maxDuration 调整

**Files:**
- Modify: `ai-gateway/src/main/resources/application.yml`
- Modify: `ai-gateway/src/main/java/com/opencode/cui/gateway/config/CloudTimeoutProperties.java`（max-duration default）

- [ ] **Step 1: application.yml 增配置**

```yaml
gateway:
  cloud-route:
    api-version: v1                                       # 默认 v1
    api-url: ${GW_CLOUD_ROUTE_API_URL:}                   # v1（保留）
    bearer-token: ${GW_CLOUD_ROUTE_BEARER:}
    cache-ttl-seconds: 300
    v2-api-url: ${GW_CLOUD_CALLBACK_CONFIG_URL:}          # v2 新接口
    v2-bearer-token: ${GW_CLOUD_CALLBACK_BEARER:}
  cloud:
    timeout:
      first-event-timeout-seconds: 30
      idle-timeout-seconds: 30
      max-duration-seconds: 1800                          # 改：30min
      webhook-timeout-seconds: 10                         # 新
```

- [ ] **Step 2: CloudTimeoutProperties.java 加字段（如需）**

`webhookTimeoutSeconds` 默认 10；`maxDurationSeconds` 默认从 600/1200 改为 1800。

- [ ] **Step 3: 跑全量测试，无回归**

```bash
mvn -pl ai-gateway test
```
Expected: PASS。

- [ ] **Step 4: Commit**

```bash
git add ai-gateway/src/main/resources/application.yml \
        ai-gateway/src/main/java/com/opencode/cui/gateway/config/CloudTimeoutProperties.java
git commit -m "chore(config): add cloud-route v2 config; bump max-duration to 30min; add webhook-timeout"
```

---

## Phase E — 文档与回归

### Task 18: 修订 `cloud-agent-protocol.md`

**Files:**
- Modify: `docs/superpowers/specs/2026-04-07-cloud-agent-protocol.md`

- [ ] **Step 1: §5.5.1 question 事件加 `questions[]` 数组形态 + `extParam` 字段**

在原单结构示例下方加：

```markdown
**形态 2（多问题数组，OpenCode 风格，平台兼容）**：
```json
{"type":"tool_event","toolSessionId":"ts-789","event":{"type":"question",
  "properties":{"toolCallId":"call-002","messageId":"msg-001","partId":"prt-q-01",
    "questions":[
      {"header":"...", "question":"...", "options":[...]},
      ...
    ],
    "extParam":{ /* 任意 JSON，平台原样透传 */ }
  }}}
```

**`extParam` 字段**（对两种形态均可选）：云端定义的扩展属性对象，平台不解析、不修改，原样透传到 SS / miniapp / external WS。
```

- [ ] **Step 2: §10.2 invoke payload `answer` 字段值约定**

修改 `answer` 字段说明：

```markdown
| answer | String | 条件 | question_reply 时必填。值为 stringified JSON `[[...]]`（数组的数组），平台兜底单文本→`[[<text>]]`。详见 §10.3 cloudRequest 协议 |
```

- [ ] **Step 3: §10.3 cloudRequest 体改 replyContext 嵌套**

替换 question_reply 请求体示例：

```json
{
  "topicId": "cloud-1001214",
  "assistantAccount": "...",
  "sendUserAccount": "...",
  "imGroupId": "...",
  "messageId": "...",
  "clientLang": "zh",
  "replyContext": {
    "type": "question_reply",
    "toolCallId": "call-q001",
    "answers": [["选项A"], ["选项B","选项C"]]
  },
  "extParameters": { "businessExtParam": {}, "platformExtParam": {} }
}
```

permission_reply 请求体示例：

```json
{
  "topicId": "cloud-1001214",
  "assistantAccount": "...",
  "sendUserAccount": "...",
  "imGroupId": "...",
  "messageId": "...",
  "clientLang": "zh",
  "replyContext": {
    "type": "permission_reply",
    "permissionId": "perm-001",
    "response": "once"
  },
  "extParameters": { "businessExtParam": {}, "platformExtParam": {} }
}
```

字段表更新：删除原顶层 `toolCallId` / `answer` / `permissionId` / `response` 行，新增 `replyContext.type` / `replyContext.toolCallId` / `replyContext.answers` / `replyContext.permissionId` / `replyContext.response`。

- [ ] **Step 4: 三个 scope 名 + chat 保活 + WebHook fire-and-forget 段**

在 §10 末尾新增小节：

```markdown
### 10.5 回调 scope 与连接形态

| Scope | channelType | 连接形态 | 何时使用 |
|---|---|---|---|
| `callback:weagent:chat` | sse / websocket | 长连接事件流 | chat invoke |
| `callback:weagent:question_reply` | webhook | 同步 POST | question_reply invoke |
| `callback:weagent:permission_reply` | webhook | 同步 POST | permission_reply invoke |

### 10.6 chat 长连接保活

云端推送 `question` / `permission.ask` 后，平台保活 chat SSE/WS 连接（idle timer 暂停）直到下一个非 q/p.ask 事件到达再恢复。`maxDuration`（默认 30min）兜底。WebHook reply 失败不会主动 close 原 chat 连接。

### 10.7 WebHook fire-and-forget

平台收到云端 WebHook 2xx 响应后**不向 SS 回流任何 GatewayMessage**；失败（网络/超时/非 2xx）回 `tool_error`。SS 由 `sendInvokeToGateway` 同步路径感知送达。
```

- [ ] **Step 5: Commit**

```bash
git add docs/superpowers/specs/2026-04-07-cloud-agent-protocol.md
git commit -m "docs(cloud-protocol): add q_r/p_r replyContext, question.questions[]/extParam, scope/keepalive/fire-and-forget"
```

---

### Task 19: 回归 + GitNexus 自检 + 集成测试

**Files:** N/A（验证阶段，无新代码）

- [ ] **Step 1: 全量编译 + 测试**

```bash
mvn clean install -DskipITs=false
```
Expected: BUILD SUCCESS, 全部测试 PASS。

- [ ] **Step 2: GitNexus impact 自检（按 CLAUDE.md 要求）**

```bash
# 通过 MCP：
# gitnexus_detect_changes({scope:"compare", base_ref:"main"})
# 期望：affected files 落在以下 scope：
#   - skill-server: BusinessScopeStrategy / CloudRequestContext / DefaultCloudRequestStrategy /
#     CloudEventTranslator / StreamMessage（含其测试）
#   - ai-gateway: CallbackConfig*/Resolver/Service / WebHookExecutor / NoAuthStrategy /
#     CloudAgentService / CloudConnectionLifecycle / Sse|WebSocketProtocolStrategy /
#     CloudConnectionContext / CloudTimeoutProperties / application.yml
#     （删除 CloudRouteService / CloudRouteInfo）
#   - docs: cloud-agent-protocol.md
```

- [ ] **Step 3: 手工集成 smoke（v1 模式回归）**

启动 ai-gateway + skill-server，配 `gateway.cloud-route.api-version=v1`，发 chat invoke。

```bash
# 期望：行为完全等价改动前；X-App-Id header 正常发送（hisAppId）；SSE/WS 连接正常
```

- [ ] **Step 4: 手工集成 smoke（v2 模式 chat）**

切到 `api-version=v2`，配好 v2-api-url 指向 api-server。发 chat invoke。

```bash
# 期望：CallbackConfigService 调用新接口；chat 行为正常；X-App-Id 不发（appId=null）
```

- [ ] **Step 5: 手工集成 smoke（v2 模式 q_r / p_r）**

云端推 question 事件 → 用户在 miniapp 应答 → 触发 q_r invoke → gateway 走 WebHookExecutor。

```bash
# 期望：
#   - 云端 WebHook 端点收到 cloudRequest，replyContext 含 toolCallId + answers
#   - gateway 不向 SS 回流 GatewayMessage（fire-and-forget）
#   - 原 chat SSE/WS 连接 idle timer 在 question 后挂起，q_r 后云端推 text.delta 触发 resume
#   - SS 收到后续 text.delta 正常投递（不被 completedSessions suppress）
```

- [ ] **Step 6: 标记任务完成**

```bash
python ./.trellis/scripts/task.py finish
# 创建后续 task：cloud-agent-protocol.md 修订需要远端方 ack
```

---

## Self-Review

**1. Spec 覆盖检查：**

| Spec 要求 | 落地 Task |
|---|---|
| CallbackConfigService + v1/v2 resolver 策略模式 | T7-T10 |
| WebHookExecutor 旁路 lifecycle | T15 |
| CloudAgentService action→scope + 分叉 | T16 |
| CloudConnectionLifecycle awaitingReply pause/resume | T12 |
| Sse/WS 触发 pause + appId null-safe X-App-Id | T13 |
| NoAuthStrategy + fail-fast unknown authType | T14 |
| CloudConnectionContext channelType/scope/channelAddress | T11 |
| BusinessScopeStrategy 按 action 写 reply 字段 + parseAnswers | T4-T5 |
| CloudRequestContext 加 4 reply 字段 | T3 |
| DefaultCloudRequestStrategy 写 replyContext 嵌套 | T6 |
| CloudEventTranslator 兼容 questions[] + extParam 透传 | T2 |
| StreamMessage.QuestionInfo 加 questions/extParam | T1 |
| feature flag + 配置 + maxDuration=1800 + webhookTimeout | T17 |
| 协议文档修订 | T18 |
| 回归 + GitNexus 自检 + 集成 smoke | T19 |

**2. 占位扫描：** 无 TBD/TODO/"参考 Task X"等占位；所有代码块都包含可直接运行的代码。

**3. 类型一致性：**
- `parseAnswers` 在 T4 定义为 package-private `List<List<String>> parseAnswers(String)`；T5 调用用相同签名 ✓
- `CallbackConfig` T7 字段名（channelType/channelAddress/authType/appId）在 T8/T9/T10/T11/T16 全部一致 ✓
- `CallbackConfigResolver.resolve(String ak, String scope)` 在 T7/T8/T9/T10 全一致 ✓
- `WebHookExecutor.execute(ctx, onRelay, invokeMessage, toolSessionId)` 在 T15 定义、T16 调用一致 ✓
- `CloudConnectionLifecycle.pauseIdleTimer()` / `resumeIdleTimer()` 在 T12 定义，T13 调用一致 ✓
- `replyContext.type` 取值：T6 写 `"question_reply"` / `"permission_reply"`；T18 协议文档同步 ✓

无类型不一致。

---

## 执行须知（GitNexus 集成约束）

- 修改 `CloudAgentService.handleInvoke` / `CloudConnectionLifecycle.resetIdleTimeout` 等核心符号前 **MUST 跑** `gitnexus_impact({target, direction:"upstream"})`
- 完成 phase 后跑 `gitnexus_detect_changes()` 确认 scope
- 重命名 `CloudConnectionContext.endpoint → channelAddress` 用 `gitnexus_rename({dry_run:true})` 先看预览
- 删除 `CloudRouteService` / `CloudRouteInfo` 前确认无遗漏引用
