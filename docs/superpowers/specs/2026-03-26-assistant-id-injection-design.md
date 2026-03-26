# AssistantId 自动注入设计

## 概述

在 Skill Server 的所有 Agent 交互场景中（创建会话、发送消息、toolSessionId 重建等），根据 `ak` 对应的 `toolType` 和会话的 `assistantAccount`，自动从 persona 外部服务获取 `assistantId`，并注入到发送给 Gateway 的 payload 中。

## 需求背景

- `skill_session` 表已有 `assistant_account` 字段（IM 域助手账号）
- Gateway 的 `agent_connection` 表有 `tool_type` 字段，标识 Agent 工具类型
- 当 `tool_type` 为特定值（可配置，默认 `"assistant"`）且会话有 `assistantAccount` 时，需要调用 persona 接口获取 `agentId`，在交互 payload 中携带

## 配置项

```yaml
skill:
  assistant-id:
    enabled: true                          # 功能总开关
    target-tool-type: "assistant"          # 需匹配的 toolType 值
    persona-base-url: "http://xxx"         # persona 服务 base URL
    cache-ttl-minutes: 30                  # Redis 缓存 TTL
    match-ak: false                        # 是否用 ak 对返回结果做二次过滤
```

对应 `@ConfigurationProperties` 配置类（参照 `SnowflakeProperties` 风格）：

```java
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "skill.assistant-id")
public class AssistantIdProperties {
    private boolean enabled = true;
    private String targetToolType = "assistant";
    private String personaBaseUrl;
    private int cacheTtlMinutes = 30;
    private boolean matchAk = false;
}
```

## 核心组件

### AssistantIdResolverService

**职责**：判断是否需要注入 + 获取 `assistantId`，单一职责。

**方法签名**：

```java
/**
 * 根据 ak 和 sessionId 解析 assistantId。
 * 内部通过 sessionId 查询 assistantAccount，通过 ak 查询 toolType。
 * 返回 null 表示不需要注入或解析失败（静默降级）。
 */
public String resolve(String ak, String sessionId)
```

**依赖**：
- `AssistantIdProperties` — 配置
- `GatewayApiClient` — 查询 ak 对应的 toolType
- `SkillSessionRepository` — 查询 session 的 assistantAccount
- `RestTemplate` — 调用 persona 接口
- `StringRedisTemplate` — Redis 缓存
- `ObjectMapper` — JSON 解析

**类型转换说明**：`sessionId` 参数为 String 类型（来自 `InvokeCommand.sessionId()`），内部通过 `Long.parseLong(sessionId)` 转换后调用 `SkillSessionRepository.findById(Long)`。转换失败时静默降级（log.warn + return null）。

**内部流程**：

```
1. 前置检查
   - enabled == false → return null
   - sessionId 解析为 Long 失败 → log.warn → return null

2. 查询 session 获取 assistantAccount
   - Caffeine 本地缓存查 session（key: sessionId, TTL: 5min, maximumSize: 1000）
   - 未命中 → SkillSessionRepository.findById(Long.parseLong(sessionId))
   - assistantAccount 为空 → return null

3. 查询 toolType
   - Caffeine 本地缓存查 toolType（key: ak, TTL: 5min）
   - 未命中 → GatewayApiClient.getAgentByAk(ak)
   - toolType != target-tool-type（忽略大小写）→ return null

4. Redis 缓存查询 assistantId
   - key: ss:assistant-id:{assistantAccount}（match-ak=false）
   - key: ss:assistant-id:{assistantAccount}:{ak}（match-ak=true）
   - 命中 → return cachedId

5. 调用 persona 接口
   - GET {persona-base-url}/welink-persona-settings/persona-new?personaWelinkId={assistantAccount}
   - 解析返回 JSON data 数组
   - match-ak=true 时，按 ak 字段过滤数组
   - 取第一条记录的 id 字段

6. 结果处理
   - id 非空 → 写入 Redis 缓存（TTL 可配置）→ return id
   - id 为空 → log.warn → return null

7. 异常处理
   - 任何异常 → log.warn（含 durationMs）→ return null（静默降级）
```

### Persona 接口定义

**请求**：
```
GET {persona-base-url}/welink-persona-settings/persona-new?personaWelinkId={assistantAccount}
```

**响应**：
```json
{
    "code": 200,
    "msg": "success",
    "data": [{
        "id": "xxxxxx",
        "userId": "xxxxxxx",
        "headImgUrl": "",
        "personaName": "",
        "personaDescribe": "",
        "lastUsedTime": "",
        "isTop": 0,
        "newFlag": 1,
        "createdFrom": 1,
        "ak": "xxxxx",
        "sk": "xxxxx",
        "robotId": "",
        "personaWelinkId": "",
        "directory": ""
    }]
}
```

**取值规则**：取 `data` 数组第一条的 `id` 字段作为 `assistantId`。开启 `match-ak` 时，先用 `ak` 字段过滤数组再取第一条。

## GatewayApiClient 扩展

新增方法，复用现有 Client 和认证机制：

```java
/**
 * 通过 ak 查询 Agent 摘要信息（含 toolType）。
 * 调用 GET {gatewayBaseUrl}/api/gateway/agents?ak={ak}
 * 返回 null 表示查询失败或 Agent 不在线。
 */
public AgentSummary getAgentByAk(String ak)
```

Gateway 端 `GET /api/gateway/agents?ak=xxx` 已支持按 ak 过滤，返回 `AgentSummaryResponse`（含 `toolType`）。Agent 离线时返回空数组，此时静默降级。

## 集中注入点

在 `GatewayRelayService.buildInvokeMessage()` 中注入。需要处理 `payloadNode` 的类型安全问题——从 `command.payload()` 解析出的 `JsonNode` 不一定是 `ObjectNode`，payload 也可能为 null：

```java
// 现有逻辑：构建 message JSON, 解析 payload
// ...

// 新增：注入 assistantId
String assistantId = assistantIdResolverService.resolve(command.ak(), command.sessionId());
if (assistantId != null) {
    // 确保 payloadNode 是 ObjectNode 才能 put
    ObjectNode targetPayload;
    if (payloadNode instanceof ObjectNode on) {
        targetPayload = on;
    } else {
        // payload 为 null 或非 Object 类型时，创建新的 ObjectNode
        targetPayload = objectMapper.createObjectNode();
        message.set("payload", targetPayload);
    }
    targetPayload.put("assistantId", assistantId);
}
```

**优势**：
- `InvokeCommand` 零改动
- 所有调用方零改动
- 所有经过 `buildInvokeMessage()` 的 invoke 消息自动覆盖

## 缓存设计

| 层级 | 缓存内容 | 存储 | TTL | Key 格式 |
|------|---------|------|-----|---------|
| L1 | session → assistantAccount | Caffeine | 5 min, max 1000 | sessionId |
| L2 | ak → toolType | Caffeine | 5 min, max 500 | ak |
| L3 | assistantAccount → assistantId | Redis | 可配置（默认 30 min） | `ss:assistant-id:{assistantAccount}` 或 `ss:assistant-id:{assistantAccount}:{ak}` |

- L1/L2 使用 Caffeine 本地缓存（带 maximumSize 防止内存溢出），避免每次消息都查库和调 Gateway API
- L3 使用 Redis，实例间共享，减少 persona 接口调用频率
- 并发缓存踩踏：多线程同时 miss 时允许重复调用 persona 接口（幂等且低频），不加分布式锁
- `assistantAccount` 在 session 生命周期内不变，L1 缓存不存在一致性风险

## 降级策略

**静默降级**：以下任一情况发生时，不注入 `assistantId`，消息正常发送，仅记录 WARN 日志：
- 功能开关关闭
- `sessionId` 无法解析为 Long
- session 无 `assistantAccount`
- Agent 离线（Gateway 查不到）
- `toolType` 不匹配
- persona 接口调用失败（超时、服务不可用、返回空数据）
- 返回数据中无有效 `id`

**Persona HTTP 超时**：复用项目全局 `RestTemplate`（连接 5s，读取 10s）。如 persona 服务响应慢不会阻塞主流程。

**缓存失效**：Redis L3 缓存依赖 TTL 自然过期。运维需立即生效时，可通过 Redis CLI 手动删除 `ss:assistant-id:*` 相关 key。

## 数据流全景

```
GatewayRelayService.buildInvokeMessage(command)
  │
  ├─ 构建 message JSON（现有逻辑不变）
  │
  └─ assistantIdResolverService.resolve(ak, sessionId)
       │
       ├─ 1. enabled == false → return null
       │
       ├─ 2. Caffeine[L1] 查 session → assistantAccount
       │     └─ miss → SkillSessionRepository.findById(sessionId)
       │     └─ assistantAccount 为空 → return null
       │
       ├─ 3. Caffeine[L2] 查 ak → toolType
       │     └─ miss → GatewayApiClient.getAgentByAk(ak)
       │     └─ toolType != target-tool-type → return null
       │
       ├─ 4. Redis[L3] 查 assistantId 缓存
       │     └─ hit → return cachedId
       │
       ├─ 5. HTTP GET persona 接口
       │     └─ match-ak=true 时按 ak 过滤
       │     └─ 取第一条 id
       │
       ├─ 6. id 非空 → 写 Redis[L3] → return id
       │
       └─ 7. 异常或空 → log.warn → return null
```

## 文件变更清单

### 新增文件

| 文件 | 说明 |
|------|------|
| `skill-server/.../config/AssistantIdProperties.java` | 配置属性类 |
| `skill-server/.../service/AssistantIdResolverService.java` | 核心解析 Service |

### 修改文件

| 文件 | 改动 |
|------|------|
| `skill-server/.../service/GatewayApiClient.java` | 新增 `getAgentByAk(String ak)` 方法 |
| `skill-server/.../service/GatewayRelayService.java` | `buildInvokeMessage()` 中注入 resolver，加 2-3 行 |
| `skill-server/src/main/resources/application.yml` | 新增 `skill.assistant-id` 配置段 |

## 日志规范

遵循项目统一日志标准：

```
[ENTRY] AssistantIdResolver.resolve: ak=xxx, sessionId=yyy
[EXT_CALL] PersonaAPI.getPersona success: assistantAccount=zzz, assistantId=aaa, durationMs=45
[EXT_CALL] PersonaAPI.getPersona failed: assistantAccount=zzz, durationMs=100, error=...
[EXIT] AssistantIdResolver.resolve: ak=xxx, sessionId=yyy, assistantId=aaa, source=cache|api, durationMs=50
```

MDC 自动继承调用方的 traceId、sessionId、ak。
