# AssistantId 自动注入 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 Skill Server 的 `GatewayRelayService.buildInvokeMessage()` 中集中拦截，根据 ak 的 toolType 和会话的 assistantAccount，自动从 persona 外部服务获取 assistantId 并注入到 payload。

**Architecture:** 新建 `AssistantIdResolverService`（单一职责：判断 + 获取），通过 Caffeine L1/L2 + Redis L3 三层缓存减少外部调用。在 `GatewayRelayService.buildInvokeMessage()` 中注入 2-3 行调用代码，所有 invoke 消息自动覆盖，InvokeCommand 和所有调用方零改动。

**Tech Stack:** Spring Boot 3.4.6, Java 21, Caffeine (Caffeine cache), Redis (StringRedisTemplate), RestTemplate, Mockito + JUnit 5

**Spec:** `docs/superpowers/specs/2026-03-26-assistant-id-injection-design.md`

---

## 文件结构

### 新增文件

| 文件 | 职责 |
|------|------|
| `skill-server/src/main/java/com/opencode/cui/skill/config/AssistantIdProperties.java` | 配置属性类 |
| `skill-server/src/main/java/com/opencode/cui/skill/service/AssistantIdResolverService.java` | 核心解析 Service |
| `skill-server/src/test/java/com/opencode/cui/skill/service/AssistantIdResolverServiceTest.java` | 解析 Service 单元测试 |

### 修改文件

| 文件 | 改动内容 |
|------|---------|
| `skill-server/src/main/java/com/opencode/cui/skill/service/GatewayApiClient.java` | 新增 `getAgentByAk(String)` 方法 |
| `skill-server/src/main/java/com/opencode/cui/skill/service/GatewayRelayService.java` | 注入 resolver 依赖 + `buildInvokeMessage()` 中调用 |
| `skill-server/src/main/resources/application.yml` | 新增 `skill.assistant-id` 配置段 |
| `skill-server/src/test/java/com/opencode/cui/skill/service/GatewayRelayServiceTest.java` | 新增 mock + assistantId 注入测试 |

---

### Task 1: 配置属性类 AssistantIdProperties

**Files:**
- Create: `skill-server/src/main/java/com/opencode/cui/skill/config/AssistantIdProperties.java`
- Modify: `skill-server/src/main/resources/application.yml`

- [ ] **Step 1: 创建 AssistantIdProperties 配置类**

参照 `SnowflakeProperties`（`skill-server/src/main/java/com/opencode/cui/skill/config/SnowflakeProperties.java`）的注解风格。

```java
package com.opencode.cui.skill.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * AssistantId 自动注入功能配置。
 * 当 Agent 的 toolType 匹配 targetToolType 且会话有 assistantAccount 时，
 * 自动从 persona 接口获取 assistantId 注入到 payload。
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "skill.assistant-id")
public class AssistantIdProperties {

    /** 功能总开关 */
    private boolean enabled = true;

    /** 需匹配的 toolType 值（忽略大小写） */
    private String targetToolType = "assistant";

    /** persona 服务 base URL */
    private String personaBaseUrl;

    /** Redis 缓存 TTL（分钟） */
    private int cacheTtlMinutes = 30;

    /** 是否用 ak 对 persona 返回结果做二次过滤 */
    private boolean matchAk = false;
}
```

- [ ] **Step 2: 在 application.yml 中添加配置段**

在 `skill-server/src/main/resources/application.yml` 的 `skill:` 块末尾（第 93 行 `auto-create-timeout-seconds` 之后）添加：

```yaml
  assistant-id:
    enabled: ${SKILL_ASSISTANT_ID_ENABLED:true}
    target-tool-type: ${SKILL_ASSISTANT_ID_TARGET_TOOL_TYPE:assistant}
    persona-base-url: ${SKILL_ASSISTANT_ID_PERSONA_BASE_URL:}
    cache-ttl-minutes: ${SKILL_ASSISTANT_ID_CACHE_TTL_MINUTES:30}
    match-ak: ${SKILL_ASSISTANT_ID_MATCH_AK:false}
```

- [ ] **Step 3: 验证编译通过**

Run: `cd skill-server && ./mvnw compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add skill-server/src/main/java/com/opencode/cui/skill/config/AssistantIdProperties.java skill-server/src/main/resources/application.yml
git commit -m "feat(ss): 添加 AssistantId 注入功能配置属性类"
```

---

### Task 2: GatewayApiClient 新增 getAgentByAk 方法

**Files:**
- Modify: `skill-server/src/main/java/com/opencode/cui/skill/service/GatewayApiClient.java:47-82`
- Test: `skill-server/src/test/java/com/opencode/cui/skill/service/GatewayApiClientTest.java` (新建)

- [ ] **Step 1: 编写 GatewayApiClient.getAgentByAk 失败测试**

新建测试文件 `GatewayApiClientTest.java`：

```java
package com.opencode.cui.skill.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencode.cui.skill.model.AgentSummary;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.client.RestClientException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GatewayApiClientTest {

    @Mock
    private RestTemplate restTemplate;

    private GatewayApiClient client;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        client = new GatewayApiClient(
                restTemplate,
                objectMapper,
                "http://localhost:8081",
                "test-token");
    }

    @Test
    @DisplayName("getAgentByAk returns AgentSummary with toolType when agent is online")
    void getAgentByAkReturnsAgentWhenOnline() {
        String responseBody = "{\"code\":200,\"data\":[{\"ak\":\"ak-001\",\"status\":\"ONLINE\",\"toolType\":\"assistant\"}]}";
        when(restTemplate.exchange(
                eq("http://localhost:8081/api/gateway/agents?ak=ak-001"),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                eq(String.class)))
                .thenReturn(ResponseEntity.ok(responseBody));

        AgentSummary result = client.getAgentByAk("ak-001");

        assertNotNull(result);
        assertEquals("ak-001", result.getAk());
        assertEquals("assistant", result.getToolType());
    }

    @Test
    @DisplayName("getAgentByAk returns null when agent is offline (empty data)")
    void getAgentByAkReturnsNullWhenOffline() {
        String responseBody = "{\"code\":200,\"data\":[]}";
        when(restTemplate.exchange(
                eq("http://localhost:8081/api/gateway/agents?ak=ak-offline"),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                eq(String.class)))
                .thenReturn(ResponseEntity.ok(responseBody));

        AgentSummary result = client.getAgentByAk("ak-offline");

        assertNull(result);
    }

    @Test
    @DisplayName("getAgentByAk returns null on HTTP error")
    void getAgentByAkReturnsNullOnError() {
        when(restTemplate.exchange(
                any(String.class),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                eq(String.class)))
                .thenThrow(new RestClientException("Connection refused"));

        AgentSummary result = client.getAgentByAk("ak-001");

        assertNull(result);
    }

    @Test
    @DisplayName("getAgentByAk returns null for null or blank ak")
    void getAgentByAkReturnsNullForBlankAk() {
        assertNull(client.getAgentByAk(null));
        assertNull(client.getAgentByAk(""));
        assertNull(client.getAgentByAk("  "));
    }
}
```

- [ ] **Step 2: 运行测试验证失败**

Run: `cd skill-server && ./mvnw test -pl . -Dtest=GatewayApiClientTest -q`
Expected: FAIL — `getAgentByAk` 方法不存在

- [ ] **Step 3: 实现 getAgentByAk 方法**

在 `GatewayApiClient.java` 的 `isAkOwnedByUser` 方法之前（第 95 行前）插入：

```java
    /**
     * 通过 ak 查询 Agent 摘要信息（含 toolType）。
     * 调用 GET {gatewayBaseUrl}/api/gateway/agents?ak={ak}，取返回列表第一条。
     *
     * @param ak Agent 应用密钥
     * @return Agent 摘要，查询失败或 Agent 不在线时返回 null
     */
    public AgentSummary getAgentByAk(String ak) {
        if (ak == null || ak.isBlank()) {
            return null;
        }

        long start = System.nanoTime();
        try {
            String url = gatewayBaseUrl + "/api/gateway/agents?ak=" + ak;
            ResponseEntity<String> response = restTemplate.exchange(
                    url,
                    HttpMethod.GET,
                    new HttpEntity<>(buildHeaders()),
                    String.class);
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                var root = objectMapper.readTree(response.getBody());
                var dataNode = root.path("data");
                if (dataNode.isMissingNode() || dataNode.isNull() || !dataNode.isArray() || dataNode.isEmpty()) {
                    log.info("[EXT_CALL] GatewayAPI.getAgentByAk not_found: ak={}, durationMs={}",
                            ak, elapsedMs);
                    return null;
                }
                AgentSummary agent = objectMapper.convertValue(dataNode.get(0), AgentSummary.class);
                log.info("[EXT_CALL] GatewayAPI.getAgentByAk success: ak={}, toolType={}, durationMs={}",
                        ak, agent.getToolType(), elapsedMs);
                return agent;
            }

            log.warn("[EXT_CALL] GatewayAPI.getAgentByAk non-success: ak={}, status={}, durationMs={}",
                    ak, response.getStatusCode(), elapsedMs);
            return null;
        } catch (Exception e) {
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            log.error("[EXT_CALL] GatewayAPI.getAgentByAk failed: ak={}, durationMs={}, error={}",
                    ak, elapsedMs, e.getMessage());
            return null;
        }
    }
```

- [ ] **Step 4: 运行测试验证通过**

Run: `cd skill-server && ./mvnw test -pl . -Dtest=GatewayApiClientTest -q`
Expected: 4 tests PASS

- [ ] **Step 5: Commit**

```bash
git add skill-server/src/main/java/com/opencode/cui/skill/service/GatewayApiClient.java skill-server/src/test/java/com/opencode/cui/skill/service/GatewayApiClientTest.java
git commit -m "feat(ss): GatewayApiClient 新增 getAgentByAk 方法查询 Agent toolType"
```

---

### Task 3: 核心 AssistantIdResolverService

**Files:**
- Create: `skill-server/src/main/java/com/opencode/cui/skill/service/AssistantIdResolverService.java`
- Create: `skill-server/src/test/java/com/opencode/cui/skill/service/AssistantIdResolverServiceTest.java`

- [ ] **Step 1: 编写 AssistantIdResolverService 测试**

参照 `AssistantAccountResolverServiceTest.java` 的测试风格：

```java
package com.opencode.cui.skill.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencode.cui.skill.config.AssistantIdProperties;
import com.opencode.cui.skill.model.AgentSummary;
import com.opencode.cui.skill.model.SkillSession;
import com.opencode.cui.skill.repository.SkillSessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.client.RestClientException;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AssistantIdResolverServiceTest {

    @Mock
    private SkillSessionRepository sessionRepository;
    @Mock
    private GatewayApiClient gatewayApiClient;
    @Mock
    private RestTemplate restTemplate;
    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;

    private AssistantIdProperties properties;
    private AssistantIdResolverService service;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        properties = new AssistantIdProperties();
        properties.setEnabled(true);
        properties.setTargetToolType("assistant");
        properties.setPersonaBaseUrl("http://persona-api");
        properties.setCacheTtlMinutes(30);
        properties.setMatchAk(false);

        service = new AssistantIdResolverService(
                properties, sessionRepository, gatewayApiClient,
                restTemplate, redisTemplate, objectMapper);
    }

    // --- 前置检查 ---

    @Test
    @DisplayName("resolve returns null when disabled")
    void resolveReturnsNullWhenDisabled() {
        properties.setEnabled(false);
        assertNull(service.resolve("ak-001", "12345"));
    }

    @Test
    @DisplayName("resolve returns null for invalid sessionId")
    void resolveReturnsNullForInvalidSessionId() {
        assertNull(service.resolve("ak-001", "not-a-number"));
    }

    @Test
    @DisplayName("resolve returns null for null sessionId")
    void resolveReturnsNullForNullSessionId() {
        assertNull(service.resolve("ak-001", null));
    }

    @Test
    @DisplayName("resolve returns null for null or blank ak")
    void resolveReturnsNullForNullOrBlankAk() {
        assertNull(service.resolve(null, "12345"));
        assertNull(service.resolve("", "12345"));
        assertNull(service.resolve("  ", "12345"));
    }

    // --- session 查询 ---

    @Test
    @DisplayName("resolve returns null when session not found")
    void resolveReturnsNullWhenSessionNotFound() {
        when(sessionRepository.findById(12345L)).thenReturn(null);
        assertNull(service.resolve("ak-001", "12345"));
    }

    @Test
    @DisplayName("resolve returns null when session has no assistantAccount")
    void resolveReturnsNullWhenNoAssistantAccount() {
        SkillSession session = new SkillSession();
        session.setId(12345L);
        session.setAssistantAccount(null);
        when(sessionRepository.findById(12345L)).thenReturn(session);

        assertNull(service.resolve("ak-001", "12345"));
    }

    // --- toolType 检查 ---

    @Test
    @DisplayName("resolve returns null when toolType does not match")
    void resolveReturnsNullWhenToolTypeMismatch() {
        SkillSession session = new SkillSession();
        session.setId(12345L);
        session.setAssistantAccount("assist-001");
        when(sessionRepository.findById(12345L)).thenReturn(session);

        AgentSummary agent = AgentSummary.builder().ak("ak-001").toolType("opencode").build();
        when(gatewayApiClient.getAgentByAk("ak-001")).thenReturn(agent);

        assertNull(service.resolve("ak-001", "12345"));
    }

    @Test
    @DisplayName("resolve returns null when agent offline")
    void resolveReturnsNullWhenAgentOffline() {
        SkillSession session = new SkillSession();
        session.setId(12345L);
        session.setAssistantAccount("assist-001");
        when(sessionRepository.findById(12345L)).thenReturn(session);
        when(gatewayApiClient.getAgentByAk("ak-001")).thenReturn(null);

        assertNull(service.resolve("ak-001", "12345"));
    }

    // --- Redis 缓存命中 ---

    @Test
    @DisplayName("resolve returns cached assistantId from Redis")
    void resolveReturnsCachedAssistantId() {
        SkillSession session = new SkillSession();
        session.setId(12345L);
        session.setAssistantAccount("assist-001");
        when(sessionRepository.findById(12345L)).thenReturn(session);

        AgentSummary agent = AgentSummary.builder().ak("ak-001").toolType("assistant").build();
        when(gatewayApiClient.getAgentByAk("ak-001")).thenReturn(agent);

        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("ss:assistant-id:assist-001")).thenReturn("cached-agent-id");

        String result = service.resolve("ak-001", "12345");

        assertEquals("cached-agent-id", result);
        // 不应调用 persona 接口
        verify(restTemplate, never()).exchange(any(String.class), any(), any(), eq(String.class));
    }

    // --- persona 接口调用 ---

    @Test
    @DisplayName("resolve fetches assistantId from persona API and caches it")
    void resolveFetchesFromPersonaApi() {
        SkillSession session = new SkillSession();
        session.setId(12345L);
        session.setAssistantAccount("assist-001");
        when(sessionRepository.findById(12345L)).thenReturn(session);

        AgentSummary agent = AgentSummary.builder().ak("ak-001").toolType("assistant").build();
        when(gatewayApiClient.getAgentByAk("ak-001")).thenReturn(agent);

        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("ss:assistant-id:assist-001")).thenReturn(null);

        String personaResponse = "{\"code\":200,\"data\":[{\"id\":\"persona-id-001\",\"ak\":\"ak-001\",\"personaWelinkId\":\"assist-001\"}]}";
        when(restTemplate.exchange(
                eq("http://persona-api/welink-persona-settings/persona-new?personaWelinkId=assist-001"),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                eq(String.class)))
                .thenReturn(ResponseEntity.ok(personaResponse));

        String result = service.resolve("ak-001", "12345");

        assertEquals("persona-id-001", result);
        verify(valueOperations).set("ss:assistant-id:assist-001", "persona-id-001", Duration.ofMinutes(30));
    }

    // --- match-ak 过滤 ---

    @Test
    @DisplayName("resolve filters by ak when matchAk enabled")
    void resolveFiltersByAkWhenEnabled() {
        properties.setMatchAk(true);

        SkillSession session = new SkillSession();
        session.setId(12345L);
        session.setAssistantAccount("assist-001");
        when(sessionRepository.findById(12345L)).thenReturn(session);

        AgentSummary agent = AgentSummary.builder().ak("ak-002").toolType("assistant").build();
        when(gatewayApiClient.getAgentByAk("ak-002")).thenReturn(agent);

        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("ss:assistant-id:assist-001:ak-002")).thenReturn(null);

        // data 数组有两条，只有第二条 ak 匹配
        String personaResponse = "{\"code\":200,\"data\":[" +
                "{\"id\":\"wrong-id\",\"ak\":\"ak-other\"}," +
                "{\"id\":\"correct-id\",\"ak\":\"ak-002\"}" +
                "]}";
        when(restTemplate.exchange(any(String.class), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok(personaResponse));

        String result = service.resolve("ak-002", "12345");

        assertEquals("correct-id", result);
        verify(valueOperations).set("ss:assistant-id:assist-001:ak-002", "correct-id", Duration.ofMinutes(30));
    }

    @Test
    @DisplayName("resolve returns null when matchAk enabled but no ak match in data")
    void resolveReturnsNullWhenNoAkMatch() {
        properties.setMatchAk(true);

        SkillSession session = new SkillSession();
        session.setId(12345L);
        session.setAssistantAccount("assist-001");
        when(sessionRepository.findById(12345L)).thenReturn(session);

        AgentSummary agent = AgentSummary.builder().ak("ak-999").toolType("assistant").build();
        when(gatewayApiClient.getAgentByAk("ak-999")).thenReturn(agent);

        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("ss:assistant-id:assist-001:ak-999")).thenReturn(null);

        String personaResponse = "{\"code\":200,\"data\":[{\"id\":\"id-1\",\"ak\":\"ak-other\"}]}";
        when(restTemplate.exchange(any(String.class), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok(personaResponse));

        assertNull(service.resolve("ak-999", "12345"));
    }

    // --- 降级场景 ---

    @Test
    @DisplayName("resolve returns null on persona API failure (silent degradation)")
    void resolveReturnsNullOnPersonaApiFailure() {
        SkillSession session = new SkillSession();
        session.setId(12345L);
        session.setAssistantAccount("assist-001");
        when(sessionRepository.findById(12345L)).thenReturn(session);

        AgentSummary agent = AgentSummary.builder().ak("ak-001").toolType("assistant").build();
        when(gatewayApiClient.getAgentByAk("ak-001")).thenReturn(agent);

        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("ss:assistant-id:assist-001")).thenReturn(null);

        when(restTemplate.exchange(any(String.class), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
                .thenThrow(new RestClientException("Connection refused"));

        assertNull(service.resolve("ak-001", "12345"));
    }

    @Test
    @DisplayName("resolve returns null when persona API returns empty data array")
    void resolveReturnsNullOnEmptyData() {
        SkillSession session = new SkillSession();
        session.setId(12345L);
        session.setAssistantAccount("assist-001");
        when(sessionRepository.findById(12345L)).thenReturn(session);

        AgentSummary agent = AgentSummary.builder().ak("ak-001").toolType("assistant").build();
        when(gatewayApiClient.getAgentByAk("ak-001")).thenReturn(agent);

        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("ss:assistant-id:assist-001")).thenReturn(null);

        when(restTemplate.exchange(any(String.class), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok("{\"code\":200,\"data\":[]}"));

        assertNull(service.resolve("ak-001", "12345"));
    }

    // --- toolType 大小写不敏感 ---

    @Test
    @DisplayName("resolve matches toolType case-insensitively")
    void resolveMatchesToolTypeCaseInsensitive() {
        SkillSession session = new SkillSession();
        session.setId(12345L);
        session.setAssistantAccount("assist-001");
        when(sessionRepository.findById(12345L)).thenReturn(session);

        // Gateway 返回大写 ASSISTANT
        AgentSummary agent = AgentSummary.builder().ak("ak-001").toolType("ASSISTANT").build();
        when(gatewayApiClient.getAgentByAk("ak-001")).thenReturn(agent);

        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("ss:assistant-id:assist-001")).thenReturn("cached-id");

        assertEquals("cached-id", service.resolve("ak-001", "12345"));
    }
}
```

- [ ] **Step 2: 运行测试验证失败**

Run: `cd skill-server && ./mvnw test -pl . -Dtest=AssistantIdResolverServiceTest -q`
Expected: FAIL — `AssistantIdResolverService` 类不存在

- [ ] **Step 3: 实现 AssistantIdResolverService**

```java
package com.opencode.cui.skill.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.opencode.cui.skill.config.AssistantIdProperties;
import com.opencode.cui.skill.model.AgentSummary;
import com.opencode.cui.skill.model.SkillSession;
import com.opencode.cui.skill.repository.SkillSessionRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * AssistantId 解析服务。
 * 根据 ak 的 toolType 和会话的 assistantAccount，从 persona 接口获取 assistantId。
 *
 * 缓存策略：
 * - L1 Caffeine: session → assistantAccount（5min, max 1000）
 * - L2 Caffeine: ak → toolType（5min, max 500）
 * - L3 Redis: assistantAccount → assistantId（可配置 TTL）
 *
 * 降级策略：任何异常或数据缺失时静默降级，返回 null，消息正常发送。
 */
@Slf4j
@Service
public class AssistantIdResolverService {

    private final AssistantIdProperties properties;
    private final SkillSessionRepository sessionRepository;
    private final GatewayApiClient gatewayApiClient;
    private final RestTemplate restTemplate;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    /** L1: sessionId → assistantAccount */
    private final Cache<String, String> sessionCache = Caffeine.newBuilder()
            .expireAfterAccess(5, TimeUnit.MINUTES)
            .maximumSize(1000)
            .build();

    /** L2: ak → toolType */
    private final Cache<String, String> toolTypeCache = Caffeine.newBuilder()
            .expireAfterAccess(5, TimeUnit.MINUTES)
            .maximumSize(500)
            .build();

    private static final String REDIS_KEY_PREFIX = "ss:assistant-id:";
    /** Caffeine 中用于标记"查过但 session 不存在或无 assistantAccount"的占位符 */
    private static final String ABSENT = "__ABSENT__";

    public AssistantIdResolverService(
            AssistantIdProperties properties,
            SkillSessionRepository sessionRepository,
            GatewayApiClient gatewayApiClient,
            RestTemplate restTemplate,
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper) {
        this.properties = properties;
        this.sessionRepository = sessionRepository;
        this.gatewayApiClient = gatewayApiClient;
        this.restTemplate = restTemplate;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * 根据 ak 和 sessionId 解析 assistantId。
     *
     * @param ak        Agent 应用密钥
     * @param sessionId Skill 侧会话 ID（String 类型，内部转为 Long）
     * @return assistantId，不需要注入或解析失败时返回 null
     */
    public String resolve(String ak, String sessionId) {
        if (!properties.isEnabled()) {
            return null;
        }

        if (sessionId == null || sessionId.isBlank()) {
            return null;
        }

        if (ak == null || ak.isBlank()) {
            return null;
        }

        // sessionId String → Long 转换
        long sessionIdLong;
        try {
            sessionIdLong = Long.parseLong(sessionId);
        } catch (NumberFormatException e) {
            log.warn("[SKIP] AssistantIdResolver.resolve: invalid sessionId={}", sessionId);
            return null;
        }

        log.info("[ENTRY] AssistantIdResolver.resolve: ak={}, sessionId={}", ak, sessionId);

        long start = System.nanoTime();
        try {
            // Step 1: 获取 assistantAccount（L1 Caffeine 缓存）
            String assistantAccount = getAssistantAccount(sessionId, sessionIdLong);
            if (assistantAccount == null) {
                return null;
            }

            // Step 2: 检查 toolType（L2 Caffeine 缓存）
            if (!isTargetToolType(ak)) {
                return null;
            }

            // Step 3: 查 Redis 缓存（L3）
            String cacheKey = buildRedisCacheKey(assistantAccount, ak);
            String cached = redisTemplate.opsForValue().get(cacheKey);
            if (cached != null && !cached.isBlank()) {
                long elapsedMs = (System.nanoTime() - start) / 1_000_000;
                log.info("[EXIT] AssistantIdResolver.resolve: ak={}, sessionId={}, assistantId={}, source=cache, durationMs={}",
                        ak, sessionId, cached, elapsedMs);
                return cached;
            }

            // Step 4: 调用 persona 接口
            String assistantId = fetchFromPersonaApi(assistantAccount, ak);
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;

            if (assistantId != null && !assistantId.isBlank()) {
                // 写入 Redis 缓存
                redisTemplate.opsForValue().set(cacheKey, assistantId,
                        Duration.ofMinutes(properties.getCacheTtlMinutes()));
                log.info("[EXIT] AssistantIdResolver.resolve: ak={}, sessionId={}, assistantId={}, source=api, durationMs={}",
                        ak, sessionId, assistantId, elapsedMs);
                return assistantId;
            }

            log.warn("[EXIT] AssistantIdResolver.resolve: ak={}, sessionId={}, assistantId=null, durationMs={}",
                    ak, sessionId, elapsedMs);
            return null;

        } catch (Exception e) {
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            log.warn("[ERROR] AssistantIdResolver.resolve: ak={}, sessionId={}, durationMs={}, error={}",
                    ak, sessionId, elapsedMs, e.getMessage());
            return null;
        }
    }

    /**
     * 从 L1 缓存或数据库获取 assistantAccount。
     */
    private String getAssistantAccount(String sessionId, long sessionIdLong) {
        String cached = sessionCache.getIfPresent(sessionId);
        if (ABSENT.equals(cached)) {
            return null;
        }
        if (cached != null) {
            return cached;
        }

        SkillSession session = sessionRepository.findById(sessionIdLong);
        if (session == null || session.getAssistantAccount() == null || session.getAssistantAccount().isBlank()) {
            sessionCache.put(sessionId, ABSENT);
            return null;
        }

        String assistantAccount = session.getAssistantAccount();
        sessionCache.put(sessionId, assistantAccount);
        return assistantAccount;
    }

    /**
     * 从 L2 缓存或 Gateway API 检查 ak 的 toolType 是否匹配。
     */
    private boolean isTargetToolType(String ak) {
        String cached = toolTypeCache.getIfPresent(ak);
        if (ABSENT.equals(cached)) {
            return false;
        }
        if (cached != null) {
            return cached.equalsIgnoreCase(properties.getTargetToolType());
        }

        AgentSummary agent = gatewayApiClient.getAgentByAk(ak);
        if (agent == null || agent.getToolType() == null) {
            toolTypeCache.put(ak, ABSENT);
            return false;
        }

        toolTypeCache.put(ak, agent.getToolType());
        return agent.getToolType().equalsIgnoreCase(properties.getTargetToolType());
    }

    /**
     * 调用 persona 接口获取 assistantId。
     */
    private String fetchFromPersonaApi(String assistantAccount, String ak) {
        String url = properties.getPersonaBaseUrl()
                + "/welink-persona-settings/persona-new?personaWelinkId=" + assistantAccount;

        long start = System.nanoTime();
        try {
            HttpHeaders headers = new HttpHeaders();
            HttpEntity<Void> request = new HttpEntity<>(headers);

            ResponseEntity<String> response = restTemplate.exchange(
                    url, HttpMethod.GET, request, String.class);
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;

            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                log.warn("[EXT_CALL] PersonaAPI.getPersona non-success: assistantAccount={}, status={}, durationMs={}",
                        assistantAccount, response.getStatusCode(), elapsedMs);
                return null;
            }

            JsonNode root = objectMapper.readTree(response.getBody());
            JsonNode dataNode = root.path("data");
            if (dataNode.isMissingNode() || !dataNode.isArray() || dataNode.isEmpty()) {
                log.warn("[EXT_CALL] PersonaAPI.getPersona empty_data: assistantAccount={}, durationMs={}",
                        assistantAccount, elapsedMs);
                return null;
            }

            // match-ak 过滤
            JsonNode target = null;
            if (properties.isMatchAk() && ak != null) {
                for (JsonNode item : dataNode) {
                    if (ak.equals(item.path("ak").asText(null))) {
                        target = item;
                        break;
                    }
                }
            } else {
                target = dataNode.get(0);
            }

            if (target == null) {
                log.warn("[EXT_CALL] PersonaAPI.getPersona no_match: assistantAccount={}, matchAk={}, durationMs={}",
                        assistantAccount, ak, elapsedMs);
                return null;
            }

            String id = target.path("id").asText(null);
            if (id != null && !id.isBlank()) {
                log.info("[EXT_CALL] PersonaAPI.getPersona success: assistantAccount={}, assistantId={}, durationMs={}",
                        assistantAccount, id, elapsedMs);
            }
            return id;

        } catch (Exception e) {
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            log.warn("[EXT_CALL] PersonaAPI.getPersona failed: assistantAccount={}, durationMs={}, error={}",
                    assistantAccount, elapsedMs, e.getMessage());
            return null;
        }
    }

    /**
     * 构建 Redis 缓存 key。
     */
    private String buildRedisCacheKey(String assistantAccount, String ak) {
        if (properties.isMatchAk()) {
            return REDIS_KEY_PREFIX + assistantAccount + ":" + ak;
        }
        return REDIS_KEY_PREFIX + assistantAccount;
    }
}
```

- [ ] **Step 4: 运行测试验证通过**

Run: `cd skill-server && ./mvnw test -pl . -Dtest=AssistantIdResolverServiceTest -q`
Expected: 12 tests PASS

- [ ] **Step 5: Commit**

```bash
git add skill-server/src/main/java/com/opencode/cui/skill/service/AssistantIdResolverService.java skill-server/src/test/java/com/opencode/cui/skill/service/AssistantIdResolverServiceTest.java
git commit -m "feat(ss): 实现 AssistantIdResolverService 核心解析逻辑（三层缓存+静默降级）"
```

---

### Task 4: GatewayRelayService 集中注入

**Files:**
- Modify: `skill-server/src/main/java/com/opencode/cui/skill/service/GatewayRelayService.java:64-78,167-202`
- Modify: `skill-server/src/test/java/com/opencode/cui/skill/service/GatewayRelayServiceTest.java:29-93`

- [ ] **Step 1: 编写注入测试**

在 `GatewayRelayServiceTest.java` 中添加新的 mock 和测试方法。

首先添加 mock 字段（在 `gatewayRelayTarget` mock 之后，约第 56 行后）：

```java
        @Mock
        private AssistantIdResolverService assistantIdResolverService;
```

修改 `setUp()` 中的 `GatewayRelayService` 构造（第 87-91 行），改为传入 `assistantIdResolverService`：

```java
                service = new GatewayRelayService(
                                new ObjectMapper(),
                                messageRouter,
                                rebuildService,
                                redisMessageBroker,
                                assistantIdResolverService);
```

**关于现有测试的兼容性说明：** 添加第 5 个构造参数后，现有测试中 `assistantIdResolverService` mock 的 `resolve()` 方法默认返回 `null`（Mockito 默认行为），不会注入 `assistantId`，因此所有现有测试行为不受影响，无需修改。

在文件末尾 `readPublishedMessage` 方法（第 714 行）之前添加测试：

```java
        @Test
        @DisplayName("buildInvokeMessage injects assistantId into payload when resolved")
        void buildInvokeMessageInjectsAssistantId() throws Exception {
                when(gatewayRelayTarget.hasActiveConnection()).thenReturn(true);
                when(gatewayRelayTarget.sendViaHash(any(), any())).thenReturn(true);
                when(assistantIdResolverService.resolve("ak-001", "42")).thenReturn("persona-agent-id");

                service.sendInvokeToGateway(
                                new InvokeCommand("ak-001", "user-1", "42", "chat", "{\"text\":\"hello\"}"));

                ArgumentCaptor<String> msgCaptor = ArgumentCaptor.forClass(String.class);
                verify(gatewayRelayTarget).sendViaHash(eq("ak-001"), msgCaptor.capture());

                JsonNode sent = objectMapper.readTree(msgCaptor.getValue());
                assertEquals("persona-agent-id", sent.path("payload").path("assistantId").asText());
                // 原有 payload 字段不受影响
                assertEquals("hello", sent.path("payload").path("text").asText());
        }

        @Test
        @DisplayName("buildInvokeMessage does not inject when resolver returns null")
        void buildInvokeMessageSkipsWhenResolverReturnsNull() throws Exception {
                when(gatewayRelayTarget.hasActiveConnection()).thenReturn(true);
                when(gatewayRelayTarget.sendViaHash(any(), any())).thenReturn(true);
                when(assistantIdResolverService.resolve("ak-001", "42")).thenReturn(null);

                service.sendInvokeToGateway(
                                new InvokeCommand("ak-001", "user-1", "42", "chat", "{\"text\":\"hello\"}"));

                ArgumentCaptor<String> msgCaptor = ArgumentCaptor.forClass(String.class);
                verify(gatewayRelayTarget).sendViaHash(eq("ak-001"), msgCaptor.capture());

                JsonNode sent = objectMapper.readTree(msgCaptor.getValue());
                assertTrue(sent.path("payload").path("assistantId").isMissingNode());
        }

        @Test
        @DisplayName("buildInvokeMessage creates payload ObjectNode when payload is null")
        void buildInvokeMessageCreatesPayloadWhenNull() throws Exception {
                when(gatewayRelayTarget.hasActiveConnection()).thenReturn(true);
                when(gatewayRelayTarget.sendViaHash(any(), any())).thenReturn(true);
                when(assistantIdResolverService.resolve("ak-001", "42")).thenReturn("agent-id");

                service.sendInvokeToGateway(
                                new InvokeCommand("ak-001", "user-1", "42", "create_session", null));

                ArgumentCaptor<String> msgCaptor = ArgumentCaptor.forClass(String.class);
                verify(gatewayRelayTarget).sendViaHash(eq("ak-001"), msgCaptor.capture());

                JsonNode sent = objectMapper.readTree(msgCaptor.getValue());
                assertEquals("agent-id", sent.path("payload").path("assistantId").asText());
        }
```

- [ ] **Step 2: 运行测试验证失败**

Run: `cd skill-server && ./mvnw test -pl . -Dtest=GatewayRelayServiceTest#buildInvokeMessageInjectsAssistantId -q`
Expected: FAIL — `GatewayRelayService` 构造函数不接受 `AssistantIdResolverService` 参数

- [ ] **Step 3: 修改 GatewayRelayService 注入 resolver**

在 `GatewayRelayService.java` 中：

**3a.** 添加字段（第 68 行 `redisMessageBroker` 之后）：

```java
    private final AssistantIdResolverService assistantIdResolverService;
```

**3b.** 修改构造函数（第 70-93 行），添加 `AssistantIdResolverService` 参数。完整构造函数体如下（必须保留现有的回调注册逻辑）：

```java
    public GatewayRelayService(ObjectMapper objectMapper,
            GatewayMessageRouter messageRouter,
            SessionRebuildService rebuildService,
            RedisMessageBroker redisMessageBroker,
            AssistantIdResolverService assistantIdResolverService) {
        this.objectMapper = objectMapper;
        this.messageRouter = messageRouter;
        this.rebuildService = rebuildService;
        this.redisMessageBroker = redisMessageBroker;
        this.assistantIdResolverService = assistantIdResolverService;

        // 向 MessageRouter 注入下行发送能力，避免循环依赖
        messageRouter.setDownstreamSender(this::sendInvokeToGateway);
        // 向 MessageRouter 注入路由响应发送能力（Task 2.10）
        messageRouter.setRouteResponseSender(new GatewayMessageRouter.RouteResponseSender() {
            @Override
            public void sendRouteConfirm(String toolSessionId, String welinkSessionId) {
                GatewayRelayService.this.sendRouteConfirm(toolSessionId, welinkSessionId);
            }

            @Override
            public void sendRouteReject(String toolSessionId) {
                GatewayRelayService.this.sendRouteReject(toolSessionId);
            }
        });
    }
```

**3c.** 在 `buildInvokeMessage()` 方法中，payload 解析完成后（第 194 行 `}` 之后、第 196 行 `try {` 之前）注入 assistantId：

```java
        // 注入 assistantId（集中拦截：所有 invoke 消息自动覆盖）
        String assistantId = assistantIdResolverService.resolve(command.ak(), command.sessionId());
        if (assistantId != null) {
            ObjectNode targetPayload;
            JsonNode existingPayload = message.get("payload");
            if (existingPayload instanceof ObjectNode on) {
                targetPayload = on;
            } else {
                targetPayload = objectMapper.createObjectNode();
                message.set("payload", targetPayload);
            }
            targetPayload.put("assistantId", assistantId);
        }
```

- [ ] **Step 4: 运行全部 GatewayRelayServiceTest 测试**

Run: `cd skill-server && ./mvnw test -pl . -Dtest=GatewayRelayServiceTest -q`
Expected: ALL tests PASS（含新增的 3 个测试）

- [ ] **Step 5: 运行全量测试确保无回归**

Run: `cd skill-server && ./mvnw test -q`
Expected: ALL tests PASS

- [ ] **Step 6: Commit**

```bash
git add skill-server/src/main/java/com/opencode/cui/skill/service/GatewayRelayService.java skill-server/src/test/java/com/opencode/cui/skill/service/GatewayRelayServiceTest.java
git commit -m "feat(ss): GatewayRelayService 集中注入 assistantId 到 invoke payload"
```

---

### Task 5: 最终验证

- [ ] **Step 1: 运行全量编译和测试**

Run: `cd skill-server && ./mvnw clean test -q`
Expected: BUILD SUCCESS, ALL tests PASS

- [ ] **Step 2: 检查所有新增/修改文件的日志完整性**

逐一检查以下文件是否符合项目日志规范（`[ENTRY]/[EXIT]/[EXT_CALL]/[ERROR]` 前缀、durationMs、MDC 关联字段）：
- `AssistantIdResolverService.java` — resolve 入口/出口、persona API 调用日志
- `GatewayApiClient.java` — getAgentByAk 的 `[EXT_CALL]` 日志
- `GatewayRelayService.java` — 无需额外日志（resolver 内部已有完整日志）

- [ ] **Step 3: Commit 最终验证结果（仅在有修复时执行）**

如果验证过程中有修复，逐一 `git add` 修改的具体文件后提交：

```bash
git add <修改的具体文件路径>
git commit -m "chore(ss): AssistantId 注入功能最终验证修复"
```

如果全量测试直接通过无修改，跳过此步骤。
