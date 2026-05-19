# Research: platformExtParam 组装点全景

- **Query**: skill-server 当前组装 `platformExtParam`（含 `businessSession{Domain,Type,Id}`）的所有位置
- **Scope**: internal
- **Date**: 2026-05-19
- **参考 commit**: `0b6266a feat(skill-server): platformExtParam 落地三字段（businessSession{Domain,Type,Id}）`

## TL;DR

`extParameters.platformExtParam` 的最终落地点只有 **3 个 builder 调用**，通过统一 helper `PlatformExtParamBuilder.build(...)` 出口。三处分别对应三种 scope strategy 的出站 wire：

| # | 文件:行 | 函数 | scope / 入口 |
|---|---|---|---|
| 1 | `skill-server/src/main/java/com/opencode/cui/skill/service/scope/BusinessScopeStrategy.java:98` | `buildInvoke(InvokeCommand, AssistantInfo)` | business（云端助手 first/retry/normal 共用） |
| 2 | `skill-server/src/main/java/com/opencode/cui/skill/service/scope/DefaultAssistantScopeStrategy.java:132` | `buildInvoke(InvokeCommand, AssistantInfo)` | default_assistant（miniapp 通道 first/retry/normal 共用） |
| 3 | `skill-server/src/main/java/com/opencode/cui/skill/service/GatewayRelayService.java:225-229` | `buildInvokeMessage(InvokeCommand)` | personal（first/retry/normal 共用；唯一 personal 出站路径） |

此外 **retry 路径有第 4 处组装**，但它走的是 personal 的"提前构造好 extParameters → 让 `GatewayRelayService` 幂等跳过"模式：

| # | 文件:行 | 函数 | scope / 入口 |
|---|---|---|---|
| 4 | `skill-server/src/main/java/com/opencode/cui/skill/service/GatewayMessageRouter.java:928-932` | `retryPendingMessages(String, String, String, String)` | personal retry（session_created 回调后回放 pending list） |

> 总入口数 = 3 strategy builder + 1 retry builder = **4 处实际调用 `PlatformExtParamBuilder.build` 的代码点**。

## 调用收口（决策路径）

`GatewayRelayService.sendInvokeToGateway(InvokeCommand)` 是所有出站 invoke 的唯一汇聚点（`skill-server/src/main/java/com/opencode/cui/skill/service/GatewayRelayService.java:94`），按 scope 分流：

```
sendInvokeToGateway
  ├─ scope == "business"             → BusinessScopeStrategy.buildInvoke  (#1)
  ├─ scope == "default_assistant"    → DefaultAssistantScopeStrategy.buildInvoke (#2)
  └─ scope == "personal"             → GatewayRelayService.buildInvokeMessage (#3)
                                          (retry 走 #4 提前注入 extParameters，#3 内幂等跳过)
```

scope 选择来自 `AssistantScopeDispatcher.getStrategy(command.domain(), command.domainType(), info)`（`GatewayRelayService.java:112-114`）。

## helper 出口

唯一组装函数 `PlatformExtParamBuilder.build(ObjectMapper, String, String, String)` 位于：

- `skill-server/src/main/java/com/opencode/cui/skill/service/PlatformExtParamBuilder.java:40`

签名：

```java
public static ObjectNode build(ObjectMapper objectMapper,
                               String businessSessionDomain,
                               String businessSessionType,
                               String businessSessionId)
```

行为契约（`PlatformExtParamBuilder.java:32-52`）：

- **三字段 key 始终出现**（即使全 null）
- 任意字段为 null → 序列化为 JSON `null`（`NullNode`），不省略 key
- 字段非 null → 包成 `TextNode`

> 这意味着 `allowedSlashCommands` 如果走相同 helper，需要扩展 build 签名；如果不希望默认下发，最简方案是 **不复用 helper**，在三个 caller 里各自判定 "拿到非 null 再 set"。详见各 caller 切入点。

## 字段来源（per scope）

### #1 BusinessScopeStrategy.buildInvoke

`BusinessScopeStrategy.java:94-100`:

```java
Map<String, Object> extParameters = new LinkedHashMap<>();
extParameters.put("businessExtParam",
        businessExtParam != null ? businessExtParam : objectMapper.createObjectNode());
extParameters.put("platformExtParam",
        PlatformExtParamBuilder.build(objectMapper,
                command.domain(), command.domainType(), command.businessSessionId()));
```

- `command.domain()` ← `InvokeCommand.domain` ← caller 传入（`SkillMessageController` / `InboundProcessingService` 等从 `SkillSession.businessSessionDomain` 取）
- `command.domainType()` ← `InvokeCommand.domainType` ← caller 传入（同上，对应 `SkillSession.businessSessionType`）
- `command.businessSessionId()` ← `InvokeCommand.businessSessionId` ← caller 传入（对应 `SkillSession.businessSessionId`）

> business 路径不读 session.businessSession{Domain,Type}，**完全靠 InvokeCommand 传入**。

### #2 DefaultAssistantScopeStrategy.buildInvoke

`DefaultAssistantScopeStrategy.java:128-133`:

```java
Map<String, Object> extParameters = new LinkedHashMap<>();
extParameters.put("businessExtParam",
        businessExtParam != null ? businessExtParam : objectMapper.createObjectNode());
extParameters.put("platformExtParam",
        PlatformExtParamBuilder.build(objectMapper,
                command.domain(), command.domainType(), command.businessSessionId()));
```

字段来源与 #1 完全一致（同为 `InvokeCommand`）。

### #3 GatewayRelayService.buildInvokeMessage (personal scope first/retry/normal 共用)

`GatewayRelayService.java:212-231`:

```java
JsonNode payloadAfterAssistantId = message.get("payload");
if (payloadAfterAssistantId instanceof ObjectNode payloadObj
        && !payloadObj.has("extParameters")) {
    // 1) 把 payload 顶层 businessExtParam 摘出来（与 P2a 对齐：搬入 extParameters）
    JsonNode removedBusinessExt = payloadObj.remove("businessExtParam");

    // 2) 构造 extParameters 信封：businessExtParam（兜底 {}）+ platformExtParam（三字段）
    ObjectNode extParameters = objectMapper.createObjectNode();
    if (removedBusinessExt != null && !removedBusinessExt.isNull()) {
        extParameters.set("businessExtParam", removedBusinessExt);
    } else {
        extParameters.set("businessExtParam", objectMapper.createObjectNode());
    }
    extParameters.set("platformExtParam",
            PlatformExtParamBuilder.build(objectMapper,
                    command.domain(),
                    command.domainType(),
                    command.businessSessionId()));
    payloadObj.set("extParameters", extParameters);
}
```

字段来源：同 #1（`InvokeCommand` 三字段）。

**幂等保护**：`!payloadObj.has("extParameters")` —— 如果 retry 路径已提前注入（见 #4），这里跳过，避免双注入。

### #4 GatewayMessageRouter.retryPendingMessages (personal retry path)

`GatewayMessageRouter.java:916-933`:

```java
// PR2 platformExtParam：直接把 businessExtParam + platformExtParam 一并放进
// extParameters 信封, 与 P2a wire 形态对齐。GatewayRelayService.buildInvokeMessage 内的
// 幂等保护会检测到 extParameters 已存在 -> 不再二次注入。
JsonNode ext = req.businessExtParam();
ObjectNode extParameters = objectMapper.createObjectNode();
if (ext != null && !ext.isNull()) {
    extParameters.set("businessExtParam", ext);
} else {
    extParameters.set("businessExtParam", objectMapper.createObjectNode());
}
// PR2: businessSessionId 来源复用 req.imGroupId()（PRD R9 命名冗余约定）；
// 单聊场景 imGroupId == null → platformExtParam.businessSessionId = JSON null。
extParameters.set("platformExtParam",
        PlatformExtParamBuilder.build(objectMapper,
                req.businessSessionDomain(),
                req.businessSessionType(),
                req.imGroupId()));
chatPayload.set("extParameters", extParameters);
```

字段来源：来自 `PendingChatRequest req`（从 Redis pending list 反序列化）：

- `req.businessSessionDomain()` ← `PendingChatRequest.businessSessionDomain` 字段（入队时由 `ImSessionManager.createSessionAsync` 或 `InboundProcessingService.dispatchChatToGateway` 写入）
- `req.businessSessionType()` ← 同上
- `req.imGroupId()` ← `PendingChatRequest.imGroupId`（PRD R9 命名冗余约定，群聊为 sessionId，单聊为 null）

## domain / type 取值来源汇总

| 入口路径 | `command.domain()` | `command.domainType()` | 备注 |
|---|---|---|---|
| `SkillMessageController.routeToGateway` (normal chat) | `session.getBusinessSessionDomain()` | `session.getBusinessSessionType()` | `SkillMessageController.java:261-263` |
| `SkillSessionController.createSession` (create_session invoke) | `session.getBusinessSessionDomain()` | `session.getBusinessSessionType()` | `SkillSessionController.java:180-182` |
| `InboundProcessingService.dispatchChatToGateway` (IM/external chat) | request 入参 `businessDomain` | request 入参 `sessionType` | `InboundProcessingService.java:397-404` |
| `InboundProcessingService.processQuestionReply` | request 入参 | request 入参 | `InboundProcessingService.java:465-472` |
| `InboundProcessingService.processPermissionReply` | request 入参 | request 入参 | `InboundProcessingService.java:533-540` |
| `ImSessionManager.createSessionAsync` (business 即时 chat) | 入参 `businessDomain` | 入参 `sessionType` | `ImSessionManager.java:163-172` |
| `ImSessionManager.ensureToolSession` (create_session) | `session.getBusinessSessionDomain()` | `session.getBusinessSessionType()` | `ImSessionManager.java:318-327` |
| `GatewayMessageRouter.retryPendingMessages` (personal retry) | `req.businessSessionDomain()` | `req.businessSessionType()` | `GatewayMessageRouter.java:945-946` |

## 关键观察（对 allowed-slash-commands 任务的影响）

1. **复用 `InvokeCommand` 通道**：如果让 `allowedSlashCommands` 跟 `businessSession{Domain,Type,Id}` 走同一条路，最少需要再加 1 个 `InvokeCommand` 字段；所有 caller 都得更新（PR1 报告里说升 9 参时改了 11 处 callsite）。
2. **绕开 InvokeCommand 的更轻量方案**：因为 sysconfig key = `${domain}_${type}`，3 个 builder 都已经拿到 domain+type，可以直接在 builder 内部读 sysconfig 拿 list → set 到 platformExtParam。但这会让 strategy 类长出 SysConfigService 依赖，且 retry 路径 (#4) 写在 router 里也得改。
3. **空值兜底契约**：当前 helper 是 "key 始终出现，值可为 JSON null"，新字段 `allowedSlashCommands` PRD 决策是 "**不下发该字段**"（key 不出现），二者契约不一致 → 不能直接复用 `PlatformExtParamBuilder.build` 的 4 参签名，要么扩 builder 加新签名，要么在 caller 处 `if (list != null) extParameters.with("platformExtParam").set("allowedSlashCommands", list)`。
4. **retry 路径 (#4) 不读 SkillSession**：从 `PendingChatRequest` 反序列化，意味着 `allowedSlashCommands` 也要么写入 `PendingChatRequest`（入队时取 sysconfig 一次冻结），要么 retry 时再现取一次（取值时间 = retry 时间，可能跟 first 不一致）—— 这是 PRD 还没明确的语义点（值是否在 pending 期间允许漂移）。

## Caveats

- 调研未覆盖 `ws/` 下的 WebSocket handler 是否还有别的出站 invoke 入口（已全文 grep `sendInvokeToGateway`，所有调用都收口到上述 controller + service 列表，无遗漏）。
- `SnowflakeIdGenerator` 不参与 platformExtParam 构造，仅生成 toolSessionId；本研究不涉及。
