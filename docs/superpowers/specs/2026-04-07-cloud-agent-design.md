# 云端 Agent 对接设计方案

> 业务助手通过云端 Agent 服务进行对话，个人助手保持现有本地 Agent 链路不变。

---

## 1. 需求概述

### 1.1 背景

当前所有助手（业务 + 个人）都通过 AK/SK 连接本地 PC Agent 进行对话。本地 Agent 离线则无法对话。需要将业务助手的对话链路从本地 Agent 切换到云端 Agent 服务。

### 1.2 核心目标

- 业务助手（`assistantScope = "business"`）直接连接云端 Agent 服务，不依赖本地 Agent 在线
- 个人助手（`assistantScope = "personal"`）保持现有链路不变
- 改动边界限于 Skill Server 和 Gateway，助手创建流程不涉及
- 支持多个不同的业务助手（通过 `appId` 区分），可快速对接新业务助手

### 1.3 设计原则

| 原则 | 说明 |
|------|------|
| SS 管业务 | SS 负责业务逻辑：查询助手类型（scope/appId）、构建云端请求体、事件翻译 |
| GW 管连接 | GW 负责路由寻址：根据 appId 获取 endpoint/protocol/authType、管理认证凭证、建立连接 |
| 各自缓存 | SS 和 GW 各自调用上游接口并缓存，SS 用 scope/appId，GW 用 endpoint/authType |
| 云端适配我们 | 我们定义响应协议标准，云端服务按我们的格式返回 |
| 对前端透明 | 前端通过 StreamMessage 类型区分内容，不需要感知消息来源是云端还是本地 |

---

## 2. 架构设计

### 2.1 整体架构

```
┌──────────────┐    ┌──────────────┐    ┌──────────────────────────────┐
│   MiniApp    │    │  IM Platform │    │  上游助手管理平台              │
│  (前端 UI)    │    │  (回调推送)   │    │  (提供助手信息查询 API)       │
└──────┬───────┘    └──────┬───────┘    └──────────────┬───────────────┘
       │                   │                           │
       │ WS /ws/skill/stream  POST /api/inbound/messages  REST API
       │                   │                           │
       ▼                   ▼                           ▼
┌──────────────────────────────────────────────────────────────────────┐
│                        Skill Server                                  │
│                                                                      │
│  ┌─────────────────────┐  ┌──────────────────────┐                  │
│  │ AssistantInfoService │  │ CloudRequestBuilder  │  ← 新增          │
│  │ (查询助手类型+云端配置) │  │ (根据 appId 构建请求体) │                  │
│  └─────────┬───────────┘  └──────────┬───────────┘                  │
│            │                         │                               │
│  ┌─────────▼─────────────────────────▼───────────┐                  │
│  │            GatewayRelayService                 │                  │
│  │  (根据 assistantScope 构建 invoke 消息)          │                  │
│  └─────────────────────┬─────────────────────────┘                  │
│                        │                                             │
│  ┌─────────────────────▼─────────────────────────┐                  │
│  │     CloudEventTranslator (新增)                │                  │
│  │  (翻译云端 event → StreamMessage)               │                  │
│  └───────────────────────────────────────────────┘                  │
└──────────────────────────┬───────────────────────────────────────────┘
                           │ WS /ws/skill (复用)
                           ▼
┌──────────────────────────────────────────────────────────────────────┐
│                          Gateway                                     │
│                                                                      │
│  ┌───────────────────────────────────────────────┐                  │
│  │           SkillRelayService (扩展)             │                  │
│  │  assistantScope="personal" → EventRelayService │                  │
│  │  assistantScope="business" → CloudAgentService │                  │
│  └──────────┬────────────────────────┬───────────┘                  │
│             │                        │                               │
│  ┌──────────▼──────────┐  ┌──────────▼──────────┐                  │
│  │  EventRelayService  │  │  CloudAgentService   │  ← 新增          │
│  │  (现有，转发给       │  │  (HTTP POST + SSE)   │                  │
│  │   本地 PC Agent)     │  │  ┌────────────────┐  │                  │
│  └─────────────────────┘  │  │ CloudAuthService│  │  ← 新增          │
│                           │  │ (按 authType    │  │                  │
│                           │  │  获取凭证)       │  │                  │
│                           │  └────────────────┘  │                  │
│                           └──────────┬───────────┘                  │
└──────────────────────────────────────┬───────────────────────────────┘
                                       │ HTTP POST + SSE
                                       ▼
                           ┌───────────────────────┐
                           │    云端 Agent 服务      │
                           │  (按 appId 路由到       │
                           │   不同业务助手)          │
                           └───────────────────────┘
```

### 2.2 消息流

**业务助手对话（新增）**：

```
用户发消息 → SS 查询助手类型(AssistantInfoService, 获取 scope + appId)
  → scope="business" → SS 构建 cloudRequest(CloudRequestBuilder, 根据 appId 选策略)
  → SS 发 invoke(assistantScope="business", cloudRequest) 到 GW
  → GW 路由到 CloudAgentService
  → CloudAgentService 根据 ak 获取路由信息(CloudRouteService, 获取 endpoint + authType)
  → CloudAgentService 获取认证凭证(CloudAuthService)
  → HTTP POST cloudRequest 到云端 endpoint
  → 读取 SSE 流 → 逐条注入路由上下文 → 通过现有上行路由转发给 SS
  → SS 翻译事件(CloudEventTranslator) → 推送前端/IM
```

**个人助手对话（不变）**：

```
用户发消息 → SS 查询助手类型(AssistantInfoService)
  → scope="personal" → 走现有流程
  → SS 发 invoke(assistantScope="personal") 到 GW
  → GW 路由到 EventRelayService → 转发给 PC Agent
  → Agent 返回事件 → GW 转发给 SS
  → SS 翻译事件(OpenCodeEventTranslator) → 推送前端/IM
```

---

## 3. Skill Server 改动

### 3.1 新增：AssistantInfoService

**职责**：调用上游助手管理平台 API，查询助手的 scope 和云端配置。

**上游接口**：

```
GET https://api.openplatform.hisuat.huawei.com/appstore/wecodeapi/open/ak/info?ak={ak}
Authorization: Bearer {token}
```

**上游响应**：

```json
{
    "code": "200",
    "messageZh": "成功！",
    "messageEn": "success!",
    "data": {
        "identityType": "3",
        "hisAppId": "app_36209",
        "endpoint": "https://cloud-agent.example.com/api/v1/chat",
        "protocol": "sse",
        "authType": "soa"
    }
}
```

**字段映射**：

| 上游字段 | 我们的模型字段 | 说明 |
|---------|-------------|------|
| identityType | assistantScope | `"2"` → `"personal"`，`"3"` → `"business"` |
| hisAppId | appId | 业务助手标识 |
| endpoint | cloudEndpoint | 云端服务地址（business 时有值） |
| protocol | cloudProtocol | `"sse"` / `"websocket"`（business 时有值） |
| authType | authType | `"soa"` / `"apig"`（business 时有值） |

**内部模型**：

```java
public class AssistantInfo {
    private String assistantScope;    // "business" | "personal"
    private String appId;             // 业务助手标识（business 时有值）
    private String cloudEndpoint;     // 云端服务地址（business 时有值）
    private String cloudProtocol;     // "sse" | "websocket"（business 时有值）
    private String authType;          // "soa" | "apig"（business 时有值）
}
```

**接口**：

```java
public class AssistantInfoService {
    /**
     * 查询助手信息（含 scope 和云端配置）
     * @param ak 助手应用密钥
     * @return 助手信息
     */
    public AssistantInfo getAssistantInfo(String ak);

    /**
     * 仅获取缓存的 scope（用于事件翻译时判断来源）
     * @param ak 助手应用密钥
     * @return "business" | "personal"
     */
    public String getCachedScope(String ak);
}
```

**缓存策略**：Redis 缓存，key 为 `ss:assistant:info:{ak}`，TTL 跟随现有 assistant account 缓存策略。

### 3.2 新增：CloudRequestBuilder（配置驱动策略）

**职责**：根据 `appId` 构建对应格式的云端请求体。策略选择通过配置驱动，不硬编码。

**数据库表**：`sys_config`（通用配置表）

| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT | 主键 |
| config_type | VARCHAR(64) | 配置类型（如 `"cloud_request_strategy"`、`"lookup"`、`"dict"` 等） |
| config_key | VARCHAR(128) | 配置键（如 appId） |
| config_value | VARCHAR(512) | 配置值（如策略名称） |
| description | VARCHAR(256) | 描述 |
| status | TINYINT | 状态：1 启用 / 0 禁用 |
| sort_order | INT | 排序（同类型内排序） |
| created_at | DATETIME | 创建时间 |
| updated_at | DATETIME | 更新时间 |

唯一索引：`(config_type, config_key)`

**策略映射示例数据**：

| config_type | config_key | config_value | description |
|-------------|------------|-------------|-------------|
| cloud_request_strategy | uniassistant | default | 统一助手，使用默认策略 |
| cloud_request_strategy | code-review | default | 代码审查助手，使用默认策略 |
| cloud_request_strategy | special-agent | custom-v2 | 特殊助手，使用 custom-v2 策略 |

**管理接口**：

| 接口 | 说明 |
|------|------|
| `POST /api/admin/configs` | 新增配置 |
| `PUT /api/admin/configs/{id}` | 修改配置 |
| `DELETE /api/admin/configs/{id}` | 删除配置 |
| `GET /api/admin/configs?type={configType}` | 按类型查询配置列表 |

**缓存**：Redis 缓存，按 `config_type:config_key` 为 key。管理接口写入时同步刷新缓存。

**策略匹配规则**：
1. 根据 `appId` 查缓存/DB 获取 `strategyName`
2. 找到 → 使用对应策略
3. 未找到 → 使用 `default` 策略

**核心类**：

```java
public class CloudRequestBuilder {

    private final Map<String, CloudRequestStrategy> strategyMap;  // appId → strategy
    private final CloudRequestStrategy defaultStrategy;

    /**
     * 初始化时根据配置构建 appId → strategy 映射
     */
    public CloudRequestBuilder(CloudRequestProperties config,
                                List<CloudRequestStrategy> strategies) {
        this.defaultStrategy = new DefaultCloudRequestStrategy();
        this.strategyMap = buildStrategyMap(config, strategies);
    }

    public ObjectNode buildCloudRequest(String appId, CloudRequestContext context) {
        CloudRequestStrategy strategy = strategyMap.getOrDefault(appId, defaultStrategy);
        return strategy.build(context);
    }
}
```

**策略接口**：

```java
public interface CloudRequestStrategy {
    String getName();   // 策略名称，对应配置中的 key
    ObjectNode build(CloudRequestContext context);
}
```

**CloudRequestContext**：

```java
public class CloudRequestContext {
    private String content;            // 用户消息内容
    private String contentType;        // "text" | "IMAGE-V1"
    private String assistantAccount;   // 助手账号
    private String sendUserAccount;    // 发送人账号
    private String imGroupId;          // 群 ID（可为 null）
    private String clientLang;         // "zh" | "en"
    private String clientType;         // "asst-pc" | "asst-wecode"
    private String topicId;            // 会话主题 ID（= toolSessionId）
    private String messageId;          // 消息 ID
    private Map<String, Object> extParameters;  // 扩展参数
}
```

**默认策略（DefaultCloudRequestStrategy）** 按标准协议构建：

```json
{
  "type": "text",
  "content": "用户消息",
  "assistantAccount": "assistant-bot-001",
  "sendUserAccount": "c30051824",
  "imGroupId": null,
  "clientLang": "zh",
  "clientType": "asst-pc",
  "topicId": "cloud-1001214",
  "messageId": "202411121103",
  "extParameters": {
    "isHwEmployee": false,
    "actionParam": "",
    "knowledgeId": []
  }
}
```

**扩展场景**：

| 场景 | 操作 | 是否需要开发 |
|------|------|------------|
| 新业务助手，标准协议够用 | 调管理接口新增 appId，strategy_name 填 `"default"` | 否 |
| 新业务助手，需要特殊格式 | 新增策略类 + 调管理接口新增 appId 绑定新策略 | 是（1 个文件） |
| 已有业务助手切换策略 | 调管理接口修改 strategy_name | 否 |
| 未配置的 appId | 自动走 default 策略 | 否 |
| 临时下线某个业务助手 | 调管理接口将 status 置为 0 | 否 |

### 3.3 新增：AssistantScopeStrategy（策略模式）

将 SS 中所有按 `assistantScope` 分支的逻辑统一收敛到策略模式，消除散落各处的 if-else。

**策略接口**：

```java
public interface AssistantScopeStrategy {
    String getScope();   // "business" | "personal"

    /** 构建 invoke 消息 */
    GatewayMessage buildInvoke(InvokeCommand command, AssistantInfo info);

    /** 创建会话时生成 toolSessionId */
    String generateToolSessionId(SkillSession session);

    /** 是否需要等待 session_created 回调 */
    boolean requiresSessionCreatedCallback();

    /** 是否需要检查 Agent 在线状态 */
    boolean requiresOnlineCheck();

    /** 翻译上行事件为 StreamMessage */
    StreamMessage translateEvent(JsonNode event, String sessionId);
}
```

**个人助手策略**：

```java
@Component
public class PersonalScopeStrategy implements AssistantScopeStrategy {
    private final OpenCodeEventTranslator openCodeEventTranslator;

    @Override public String getScope() { return "personal"; }

    @Override public GatewayMessage buildInvoke(InvokeCommand command, AssistantInfo info) {
        // 现有逻辑：构建标准 invoke，注入 assistantId 等
        return buildInvokeMessage(command);
    }

    @Override public String generateToolSessionId(SkillSession session) {
        return null;  // 由 PC Agent 返回 session_created 时绑定
    }

    @Override public boolean requiresSessionCreatedCallback() { return true; }
    @Override public boolean requiresOnlineCheck() { return true; }

    @Override public StreamMessage translateEvent(JsonNode event, String sessionId) {
        return openCodeEventTranslator.translate(event, sessionId);
    }
}
```

**业务助手策略**：

```java
@Component
public class BusinessScopeStrategy implements AssistantScopeStrategy {
    private final CloudRequestBuilder cloudRequestBuilder;
    private final CloudEventTranslator cloudEventTranslator;

    @Override public String getScope() { return "business"; }

    @Override public GatewayMessage buildInvoke(InvokeCommand command, AssistantInfo info) {
        // 构建 cloudRequest，设置 assistantScope
        ObjectNode cloudRequest = cloudRequestBuilder.buildCloudRequest(
            info.getAppId(), buildCloudRequestContext(command));
        GatewayMessage msg = buildInvokeMessage(command);
        msg.setAssistantScope("business");
        msg.getPayload().set("cloudRequest", cloudRequest);
        return msg;
    }

    @Override public String generateToolSessionId(SkillSession session) {
        return "cloud-" + snowflakeIdGenerator.nextId();  // SS 本地生成
    }

    @Override public boolean requiresSessionCreatedCallback() { return false; }
    @Override public boolean requiresOnlineCheck() { return false; }

    @Override public StreamMessage translateEvent(JsonNode event, String sessionId) {
        return cloudEventTranslator.translate(event);
    }
}
```

**策略调度器**：

```java
@Component
public class AssistantScopeDispatcher {
    private final Map<String, AssistantScopeStrategy> strategyMap;

    public AssistantScopeDispatcher(List<AssistantScopeStrategy> strategies) {
        this.strategyMap = strategies.stream()
            .collect(Collectors.toMap(AssistantScopeStrategy::getScope, Function.identity()));
    }

    public AssistantScopeStrategy getStrategy(String scope) {
        return strategyMap.getOrDefault(scope, strategyMap.get("personal"));
    }
}
```

**使用方式（消除 if-else）**：

```java
// GatewayRelayService — 构建 invoke
AssistantInfo info = assistantInfoService.getAssistantInfo(command.getAk());
AssistantScopeStrategy strategy = scopeDispatcher.getStrategy(info.getAssistantScope());
GatewayMessage msg = strategy.buildInvoke(command, info);
gatewayWSClient.send(msg);

// ImSessionManager / SkillSessionController — 创建会话
String toolSessionId = strategy.generateToolSessionId(session);
boolean needCallback = strategy.requiresSessionCreatedCallback();

// ImInboundController / SkillMessageController — 在线检查
if (strategy.requiresOnlineCheck()) {
    // 检查 Agent 在线状态...
}

// GatewayMessageRouter — 事件翻译
StreamMessage msg = strategy.translateEvent(event, sessionId);
```

### 3.4 新增：CloudEventTranslator（注册表模式）

**职责**：翻译云端 event.type 为 StreamMessage。使用注册表模式替代 switch-case，新增事件类型只需注册新 Handler。

**Handler 接口**：

```java
@FunctionalInterface
public interface CloudEventHandler {
    StreamMessage handle(JsonNode properties);
}
```

**注册表**：

```java
@Component
public class CloudEventTranslator {

    private final Map<String, CloudEventHandler> handlers = new HashMap<>();

    @PostConstruct
    public void init() {
        // 文本内容
        handlers.put("text.delta", props -> StreamMessage.builder()
            .type(Types.TEXT_DELTA)
            .content(props.path("content").asText())
            .role(props.path("role").asText("assistant"))
            .build());
        handlers.put("text.done", props -> StreamMessage.builder()
            .type(Types.TEXT_DONE)
            .content(props.path("content").asText())
            .role(props.path("role").asText("assistant"))
            .messageId(props.path("messageId").asText(null))
            .partId(props.path("partId").asText(null))
            .build());
        // thinking.delta、thinking.done 同理...

        // 工具执行
        handlers.put("tool.update", props -> StreamMessage.builder()
            .type(Types.TOOL_UPDATE)
            .toolName(props.path("toolName").asText())
            .toolCallId(props.path("toolCallId").asText())
            .status(props.path("status").asText())
            .build());
        // step.start、step.done 同理...

        // 交互
        handlers.put("question", props -> StreamMessage.builder()
            .type(Types.QUESTION)
            .toolCallId(props.path("toolCallId").asText())
            .question(props.path("question").asText())
            .build());
        // permission.ask、permission.reply 同理...

        // 会话状态
        handlers.put("session.status", props -> StreamMessage.sessionStatus(
            props.path("status").asText()));
        handlers.put("session.title", props -> StreamMessage.builder()
            .type(Types.SESSION_TITLE)
            .title(props.path("title").asText())
            .build());
        // session.error 同理...

        // 文件
        handlers.put("file", props -> StreamMessage.builder()
            .type(Types.FILE)
            .fileName(props.path("fileName").asText())
            .fileUrl(props.path("fileUrl").asText())
            .fileMime(props.path("fileMime").asText(null))
            .build());

        // 云端扩展
        handlers.put("planning.delta", props -> StreamMessage.builder()
            .type(Types.PLANNING_DELTA)
            .content(props.path("content").asText())
            .build());
        handlers.put("planning.done", props -> StreamMessage.builder()
            .type(Types.PLANNING_DONE)
            .content(props.path("content").asText())
            .build());
        handlers.put("searching", props -> StreamMessage.builder()
            .type(Types.SEARCHING)
            .keywords(toStringList(props.path("keywords")))
            .build());
        handlers.put("search_result", props -> StreamMessage.builder()
            .type(Types.SEARCH_RESULT)
            .searchResults(toSearchResultList(props.path("results")))
            .build());
        handlers.put("reference", props -> StreamMessage.builder()
            .type(Types.REFERENCE)
            .references(toReferenceList(props.path("references")))
            .build());
        handlers.put("ask_more", props -> StreamMessage.builder()
            .type(Types.ASK_MORE)
            .askMoreQuestions(toStringList(props.path("questions")))
            .build());
    }

    public StreamMessage translate(JsonNode event) {
        String eventType = event.path("type").asText();
        JsonNode props = event.path("properties");
        CloudEventHandler handler = handlers.get(eventType);
        return handler != null ? handler.handle(props) : null;
    }
}
```

**扩展**：新增事件类型只需在 `init()` 中注册新 handler，或通过外部 `registerHandler(type, handler)` 方法动态注册。

这种方式的优点：
- 不依赖 event.type 命名区分（`session.status` 等在两套协议中命名相同）
- GW 不需要注入额外标记，保持纯透传
- scope 信息在 SS 处理 invoke 时已缓存，查询无额外开销

### 3.8 改动：IM 出站处理

**现有逻辑**：IM 场景下，`text.done` 的内容发送到 IM 平台。

**新增逻辑**：业务助手的 IM 出站只发送 `text.done`/`text.delta` 的最终聚合文本。以下云端扩展事件不发送到 IM：
- `planning.delta` / `planning.done`
- `thinking.delta` / `thinking.done`
- `searching` / `search_result` / `reference`
- `ask_more`

**改动文件**：`GatewayMessageRouter.routeAssistantMessage()` 中增加 IM 过滤逻辑。

### 3.9 新增：StreamMessage 类型扩展

在 `StreamMessage.Types` 接口中新增云端扩展类型：

```java
public interface Types {
    // ... 现有类型 ...

    // 云端扩展
    String PLANNING_DELTA = "planning.delta";
    String PLANNING_DONE = "planning.done";
    String SEARCHING = "searching";
    String SEARCH_RESULT = "search_result";
    String REFERENCE = "reference";
    String ASK_MORE = "ask_more";
}
```

`StreamMessage` 模型新增字段（用于云端扩展类型）：

```java
// searching
private List<String> keywords;

// search_result
private List<SearchResultItem> searchResults;

// reference
private List<ReferenceItem> references;

// ask_more
private List<String> askMoreQuestions;
```

---

## 4. Gateway 改动

### 4.1 改动：GatewayMessage 模型

新增字段：

```java
public class GatewayMessage {
    // ... 现有字段 ...

    private String assistantScope;   // "business" | "personal"
}
```

### 4.2 改动：SkillRelayService（策略模式）

**改动点**：使用 `InvokeRouteStrategy` 策略模式替代 if-else 路由分支。

**策略接口**：

```java
public interface InvokeRouteStrategy {
    String getScope();   // "business" | "personal"
    void route(GatewayMessage message);
}
```

**策略实现**：

```java
@Component
public class PersonalInvokeRouteStrategy implements InvokeRouteStrategy {
    @Override public String getScope() { return "personal"; }
    @Override public void route(GatewayMessage message) {
        // 现有逻辑：转发给本地 PC Agent
    }
}

@Component
public class BusinessInvokeRouteStrategy implements InvokeRouteStrategy {
    private final CloudAgentService cloudAgentService;
    @Override public String getScope() { return "business"; }
    @Override public void route(GatewayMessage message) {
        cloudAgentService.handleInvoke(message);
    }
}
```

**调度**：

```java
public void handleInvokeFromSkill(WebSocketSession session, GatewayMessage message) {
    String scope = Optional.ofNullable(message.getAssistantScope()).orElse("personal");
    InvokeRouteStrategy strategy = routeStrategyMap.getOrDefault(scope, personalStrategy);
    strategy.route(message);
}
```

### 4.3 新增：CloudAgentService

**职责**：管理与云端服务的临时连接，按消息粒度建立 HTTP POST + SSE 连接。

**核心流程**：

```java
public class CloudAgentService {

    private final CloudRouteService cloudRouteService;
    private final CloudAuthService cloudAuthService;
    private final CloudProtocolClient cloudProtocolClient;
    private final SkillRelayService skillRelayService;

    public void handleInvoke(GatewayMessage invokeMessage) {
        String ak = invokeMessage.getAk();
        JsonNode cloudRequest = invokeMessage.getPayload().path("cloudRequest");
        String toolSessionId = invokeMessage.getPayload().path("toolSessionId").asText();

        // 1. 根据 ak 获取路由信息（endpoint、protocol、authType），Redis 缓存
        CloudRouteInfo routeInfo = cloudRouteService.getRouteInfo(ak);

        // 2. 构建连接上下文
        CloudConnectionContext context = new CloudConnectionContext(
            routeInfo.getEndpoint(), cloudRequest,
            routeInfo.getAppId(), routeInfo.getAuthType(),
            invokeMessage.getTraceId());

        // 3. 通过 protocol 策略发起连接（SSE / WebSocket）
        cloudProtocolClient.connect(routeInfo.getProtocol(), context, event -> {
            // 4. 注入路由上下文（从原始 invoke 获取）
            event.setAk(ak);
            event.setUserId(invokeMessage.getUserId());
            event.setWelinkSessionId(invokeMessage.getWelinkSessionId());
            event.setTraceId(invokeMessage.getTraceId());
            if (event.getToolSessionId() == null) {
                event.setToolSessionId(toolSessionId);
            }

            // 5. 通过现有上行路由转发给 SS
            skillRelayService.relayToSkill(event);

        }, error -> {
            // 6. 错误 → 构建 tool_error 回传 SS
            GatewayMessage errorMsg = buildCloudError(invokeMessage, error);
            skillRelayService.relayToSkill(errorMsg);
        });
    }
}
```

> `CloudProtocolStrategy` 内部调用 `CloudAuthService.applyAuth()` 填充认证头，CloudAgentService 本身不关心认证细节。
```

**连接管理**：
- 每次 invoke 建立一个新的 HTTP 连接，SSE 流结束（tool_done/tool_error）后连接关闭
- 使用异步 HTTP 客户端（如 Java 11+ HttpClient 或 WebClient），避免阻塞线程
- 连接超时和读取超时可配置

**错误处理**：

| 错误场景 | 处理方式 |
|---------|---------|
| 连接超时 | 构建 `tool_error(cloud_connection_timeout)` |
| 读取超时 | 构建 `tool_error(cloud_read_timeout)` |
| HTTP 非 200 | 构建 `tool_error` 并映射错误码 |
| SSE 解析失败 | 记录日志，跳过该条事件 |
| 认证凭证获取失败 | 构建 `tool_error(cloud_auth_failed)` |

### 4.4 新增：CloudRouteService

**职责**：根据 appId 获取云端路由信息（endpoint、protocol、authType）。调用与 SS 相同的上游接口，GW 侧独立缓存。

```java
public class CloudRouteService {

    /**
     * 获取云端路由信息
     * @param appId 业务助手标识
     * @return 路由信息（endpoint、protocol、authType）
     */
    public CloudRouteInfo getRouteInfo(String ak);
}

public class CloudRouteInfo {
    private String appId;       // 业务助手标识（hisAppId）
    private String endpoint;    // 云端服务地址
    private String protocol;    // "sse" | "websocket"
    private String authType;    // "soa" | "apig"
}
```

**上游接口**：与 SS 调用相同的 API（`GET /appstore/wecodeapi/open/ak/info?ak={ak}`），用 invoke 中的 `ak` 查询。

**缓存策略**：Redis 缓存，key 为 `gw:cloud:route:{ak}`，TTL 可配置。

### 4.5 新增：CloudAuthService（策略模式）

**职责**：根据 authType 获取云端认证凭证。通过策略模式支持多种认证方式，新增认证方式只需实现新策略类。

**策略接口**：

```java
public interface CloudAuthStrategy {
    String getAuthType();                          // "soa" | "apig" | ...
    void applyAuth(HttpRequest request, String appId);  // 填充认证头
}
```

**已有策略实现**：

```java
@Component
public class SoaAuthStrategy implements CloudAuthStrategy {
    @Override public String getAuthType() { return "soa"; }
    @Override public void applyAuth(HttpRequest request, String appId) {
        // 获取 SOA token，设置请求头
    }
}

@Component
public class ApigAuthStrategy implements CloudAuthStrategy {
    @Override public String getAuthType() { return "apig"; }
    @Override public void applyAuth(HttpRequest request, String appId) {
        // 获取 APIG 签名，设置请求头
    }
}
```

**调度器**：

```java
@Component
public class CloudAuthService {

    private final Map<String, CloudAuthStrategy> strategyMap;

    public CloudAuthService(List<CloudAuthStrategy> strategies) {
        this.strategyMap = strategies.stream()
            .collect(Collectors.toMap(CloudAuthStrategy::getAuthType, Function.identity()));
    }

    public void applyAuth(HttpRequest request, String appId, String authType) {
        CloudAuthStrategy strategy = strategyMap.get(authType);
        if (strategy == null) {
            throw new IllegalArgumentException("Unknown authType: " + authType);
        }
        strategy.applyAuth(request, appId);
    }
}
```

**扩展**：新增认证方式只需实现 `CloudAuthStrategy` 接口，Spring 自动注册。

### 4.6 新增：CloudProtocolClient（策略模式）

**职责**：根据 protocol 类型选择不同的连接方式与云端通信。通过策略模式支持 SSE、WebSocket 等协议，新增协议只需实现新策略类。

**策略接口**：

```java
public interface CloudProtocolStrategy {
    String getProtocol();      // "sse" | "websocket" | ...
    void connect(CloudConnectionContext context,
                 Consumer<GatewayMessage> onEvent,
                 Consumer<Throwable> onError);
}
```

**CloudConnectionContext**：

```java
public class CloudConnectionContext {
    private String endpoint;           // 云端地址
    private JsonNode cloudRequest;     // 请求体
    private String appId;              // 业务助手标识
    private String authType;           // 认证方式
    private String traceId;            // 链路追踪 ID
}
```

**已有策略实现**：

```java
@Component
public class SseProtocolStrategy implements CloudProtocolStrategy {
    @Override public String getProtocol() { return "sse"; }
    @Override public void connect(CloudConnectionContext context,
                                  Consumer<GatewayMessage> onEvent,
                                  Consumer<Throwable> onError) {
        // HTTP POST + 读取 SSE 流，逐条解析为 GatewayMessage 回调 onEvent
    }
}

@Component
public class WebSocketProtocolStrategy implements CloudProtocolStrategy {
    @Override public String getProtocol() { return "websocket"; }
    @Override public void connect(CloudConnectionContext context,
                                  Consumer<GatewayMessage> onEvent,
                                  Consumer<Throwable> onError) {
        // 建立 WebSocket 连接，发送请求，接收响应回调 onEvent
    }
}
```

**调度器**：

```java
@Component
public class CloudProtocolClient {

    private final Map<String, CloudProtocolStrategy> strategyMap;

    public CloudProtocolClient(List<CloudProtocolStrategy> strategies) {
        this.strategyMap = strategies.stream()
            .collect(Collectors.toMap(CloudProtocolStrategy::getProtocol, Function.identity()));
    }

    public void connect(String protocol, CloudConnectionContext context,
                        Consumer<GatewayMessage> onEvent,
                        Consumer<Throwable> onError) {
        CloudProtocolStrategy strategy = strategyMap.get(protocol);
        if (strategy == null) {
            throw new IllegalArgumentException("Unknown protocol: " + protocol);
        }
        strategy.connect(context, onEvent, onError);
    }
}
```

**扩展**：新增协议只需实现 `CloudProtocolStrategy` 接口，Spring 自动注册。

### 4.7 新增：云端 IM 推送接口

**背景**：云端 Agent 有定时任务推送功能，需要主动向 IM 单聊/群聊发送消息（非用户触发的对话）。

**流程**：

```
云端定时任务 → GW REST 接口（POST /api/gateway/cloud/im-push）
  → GW 构建 GatewayMessage(type="im_push")
  → 通过现有 WS 通道（/ws/skill）发给 SS
  → SS 调用 IM 出站接口发送消息
```

**设计选型**：GW 对外提供 REST 接口给云端调用，对内复用现有 WS 通道转发给 SS。理由：
- 保持 GW ↔ SS 单一通信通道（WS），不引入第二种通信方式
- 复用现有 WS 多实例路由（Redis relay）
- GW 不需要知道 SS 的 HTTP 地址

#### GW 侧：REST 接口

**接口**：`POST /api/gateway/cloud/im-push`

**请求体**：

```json
{
  "assistantAccount": "assistant-bot-001",
  "sendUserAccount": "c30051824",
  "imGroupId": null,
  "topicId": "cloud-1001214",
  "content": "您好，这是定时推送的消息内容"
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| assistantAccount | String | ✅ | 以哪个助手身份发送 |
| sendUserAccount | String | ✅ | 目标用户账号 |
| imGroupId | String | | 群 ID。有值 → 群聊推送；null → 单聊推送 |
| topicId | String | ✅ | 会话主题 ID（= toolSessionId），用于 GW 路由 |
| content | String | ✅ | 文本内容（可含自定义 Markdown 协议） |

**认证**：云端调用此接口时由 GW 的 `CloudAuthService` 验证身份（复用 authType 策略）。

**GW 处理逻辑**：

```java
@RestController
@RequestMapping("/api/gateway/cloud")
public class CloudPushController {

    private final SkillRelayService skillRelayService;

    @PostMapping("/im-push")
    public ResponseEntity<Void> imPush(@RequestBody ImPushRequest request) {
        GatewayMessage msg = new GatewayMessage();
        msg.setType("im_push");
        // topicId 就是 toolSessionId（SS 构建 cloudRequest 时传给云端的）
        msg.setToolSessionId(request.getTopicId());
        msg.setPayload(objectMapper.valueToTree(request));
        msg.setTraceId(UUID.randomUUID().toString());

        // 复用现有上行路由，基于 toolSessionId 精确投递
        skillRelayService.relayToSkill(msg);
        return ResponseEntity.ok().build();
    }
}
```

#### SS 侧：处理 im_push 消息

**GatewayMessage 新增类型**：在 `GatewayMessage.Type` 中新增 `im_push`。

**GatewayMessageRouter 新增处理分支**：

```java
case "im_push" -> handleImPush(message);
```

**handleImPush 逻辑**：

```java
private void handleImPush(GatewayMessage message) {
    JsonNode payload = message.getPayload();
    String assistantAccount = payload.path("assistantAccount").asText();
    String sendUserAccount = payload.path("sendUserAccount").asText();
    String imGroupId = payload.path("imGroupId").asText(null);
    String topicId = message.getToolSessionId();
    String content = payload.path("content").asText();

    // 校验 topicId 对应的会话确实属于该 assistantAccount
    SkillSession session = sessionService.findByToolSessionId(topicId);
    if (session == null) {
        log.warn("im_push ignored: no session found for topicId={}", topicId);
        return;
    }
    // 通过 session 的 ak 解析 assistantAccount，与请求中的 assistantAccount 比对
    String resolvedAccount = assistantAccountResolverService.resolveAccount(session.getAk());
    if (!assistantAccount.equals(resolvedAccount)) {
        log.warn("im_push rejected: assistantAccount mismatch, request={}, resolved={}",
                assistantAccount, resolvedAccount);
        return;
    }

    // 根据 imGroupId 判断单聊/群聊，调用 IM 出站接口
    if (imGroupId != null) {
        imOutboundService.sendGroupMessage(assistantAccount, imGroupId, content);
    } else {
        imOutboundService.sendDirectMessage(assistantAccount, sendUserAccount, content);
    }
}
```

**特点**：
- 不走会话管理（不创建/查找 SkillSession）
- 不走事件翻译（不经过 Translator）
- 不推送到 MiniApp 前端
- 纯透传：收到 → 发到 IM

### 4.8 不改动的部分

以下组件不需要改动：

| 组件 | 原因 |
|------|------|
| AgentWebSocketHandler | 只处理本地 Agent 连接，与云端无关 |
| EventRelayService | 个人助手流程不变 |
| AkSkAuthService | 个人助手认证不变 |
| AgentRegistryService | 本地 Agent 注册不变 |
| RedisMessageBroker | 云端响应通过现有上行路由，复用 Redis 中继 |

---

## 5. 前端改动（skill-miniapp）

### 5.1 新增 StreamMessage 类型渲染

前端 `StreamAssembler` 需要处理新增的 StreamMessage 类型：

| 新类型 | 渲染方式 |
|--------|---------|
| `planning.delta` / `planning.done` | 展示为"规划中"状态的可折叠区域 |
| `searching` | 展示搜索关键词标签 |
| `search_result` | 展示搜索结果列表 |
| `reference` | 展示引用卡片（标题 + 来源 + 链接） |
| `ask_more` | 展示追问建议按钮列表，点击后发送对应文本 |

### 5.2 自定义 Markdown 协议处理

前端需要解析 `text.delta`/`text.done` 中的自定义 Markdown 协议：

- `sendTextMsg`：点击后发送指定文本到当前对话
- `openIMChat`：点击后打开指定的单聊/群聊窗口

### 5.3 不改动的部分

- WebSocket 连接（`/ws/skill/stream`）不变
- 消息历史 API 不变
- 会话管理 API 不变
- Agent 选择器需要适配（业务助手不展示在线状态，或始终显示在线）

---

## 6. 数据模型变更

### 6.1 SkillSession 表

不新增字段。`assistantScope` 信息通过 AK 实时查询 `AssistantInfoService`，不持久化到 session 表。

`toolSessionId` 字段：
- 个人助手：由 PC Agent 返回 session_created 时绑定
- 业务助手：SS 创建会话时本地生成（格式 `"cloud-{snowflakeId}"`）

### 6.2 GatewayMessage（内存 DTO）

新增 `assistantScope` 字段，见 4.1。

### 6.3 StreamMessage（内存 DTO）

新增云端扩展字段，见 3.9。

### 6.4 新增表：sys_config

通用配置表，存储各类运行时配置（策略映射、lookup、数据字典等），通过管理接口增删改查，见 3.2 节。

---

## 7. 配置项

### 7.1 Skill Server

| 配置项 | 说明 | 默认值 |
|--------|------|--------|
| `skill.assistant-info.api-url` | 上游助手信息查询 API 地址 | - |
| `skill.assistant-info.cache-ttl` | Redis 缓存 TTL（秒） | 300 |

### 7.2 Gateway

| 配置项 | 说明 | 默认值 |
|--------|------|--------|
| `gateway.cloud.connect-timeout` | 连接云端超时（毫秒） | 5000 |
| `gateway.cloud.read-timeout` | SSE 读取超时（毫秒） | 120000 |
| `gateway.cloud.auth.soa.*` | SOA 认证相关配置 | - |
| `gateway.cloud.auth.apig.*` | APIG 认证相关配置 | - |

---

## 8. 对接新业务助手清单

当需要对接一个新的业务助手时，按以下步骤执行：

| # | 步骤 | 责任方 | 改动范围 |
|---|------|--------|---------|
| 1 | 上游平台注册新 appId，配置 endpoint、authType | 上游平台 | 配置 |
| 2a | 标准协议够用：调管理接口新增 appId 映射 | 运维 | 零开发 |
| 2b | 需要特殊格式：新增策略类 + 调管理接口绑定 | SS 开发 | 1 个文件 |
| 3 | 云端按协议 P3 适配响应格式 | 云端团队 | 云端侧 |
| 4 | 如有新 authType，GW 增加认证策略 | GW 开发 | 1 个文件（可选） |
| 5 | 如有新 event.type，SS 增加翻译逻辑 + 前端增加渲染 | SS/前端 | 可选 |

**大部分新业务助手只需改配置，GW 不需要为新 appId 做改动。**

---

## 9. 协议文档

完整的通信协议规范见：[`2026-04-07-cloud-agent-protocol.md`](./2026-04-07-cloud-agent-protocol.md)

覆盖 4 段消息流（SS→GW、GW→云端、云端→GW、GW→SS），20 种事件类型的完整报文定义。

---

## 10. 风险与缓解

| 风险 | 影响 | 缓解措施 |
|------|------|---------|
| 云端服务不可用 | 业务助手无法对话 | tool_error 实时反馈用户；个人助手不受影响 |
| 云端响应格式不符合协议 | SSE 解析失败 | GW 跳过解析失败的事件并记录日志；SS 忽略未知 event.type |
| 上游助手信息 API 不可用 | 无法判断助手类型 | Redis 缓存兜底；缓存失效时降级为 personal |
| 云端响应延迟 | 用户等待时间长 | GW 可配置读取超时；前端展示 loading 状态 |
| 认证凭证过期 | 云端返回 401 | CloudAuthService 自动刷新凭证并重试 |
| **云端返回 question/permission 事件** | **用户无法回复，对话中断** | **见 10.1 详细说明** |

### 10.1 TODO：云端 question/permission 旁路回复

**背景**：云端服务内部有两个 LLM 来源——优先走云端自己的 opencode 客户端，客户端离线时降级走云端 LLM。当云端走 opencode 客户端时，可能产生 `question` 和 `permission.ask` 事件。

**当前状态**：不纳入本次设计和实施范围。要求云端尽量避免返回这两类事件。

**风险**：如果云端返回了 question/permission 事件，用户在前端能看到问题/权限请求卡片，但无法回复（回复无法传回云端），导致对话中断。

**协议预定义**见协议文档第 10 章（供后续实施参考，本次不实现）。

**已知待解决问题**：

1. 多 GW 实例下 SSE 连接映射为本地内存，reply invoke 可能路由到错误的 GW 实例
2. 旁路 REST 端点不应简单拼接，需由上游接口单独提供
3. SSE 连接等待回复期间占用线程，需考虑异步方案

**临时措施**：SS 收到云端的 question/permission 事件时，可考虑转为提示消息（如"该助手暂不支持交互式问答"），避免用户困惑。
