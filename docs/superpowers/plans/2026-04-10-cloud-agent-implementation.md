# 云端 Agent 对接实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 业务助手通过云端 Agent 服务进行对话，个人助手保持现有本地 Agent 链路不变。

**Architecture:** SS 通过 AssistantScopeStrategy 策略模式统一处理不同 scope 的助手逻辑（invoke 构建、会话创建、在线检查、事件翻译）。GW 通过 InvokeRouteStrategy 路由到本地 Agent 或云端，CloudAgentService 编排 CloudRouteService + CloudAuthService（策略模式）+ CloudProtocolClient（策略模式）完成云端连接。CloudEventTranslator 使用注册表模式翻译云端事件。

**Tech Stack:** Spring Boot 3.4, Java 21, MyBatis, Redis (Lettuce), Jackson, Spring WebFlux WebClient (SSE 客户端)

**Design docs:**
- 设计文档：`docs/superpowers/specs/2026-04-07-cloud-agent-design.md`
- 协议文档：`docs/superpowers/specs/2026-04-07-cloud-agent-protocol.md`

---

## Phase 1: SS 基础设施

### Task 1: sys_config 数据库表

**Files:**
- Create: `skill-server/src/main/resources/db/migration/V10__create_sys_config.sql`

- [ ] **Step 1: 创建 migration 文件**

```sql
-- V10__create_sys_config.sql
CREATE TABLE IF NOT EXISTS sys_config (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    config_type VARCHAR(64) NOT NULL COMMENT '配置类型',
    config_key VARCHAR(128) NOT NULL COMMENT '配置键',
    config_value VARCHAR(512) NOT NULL COMMENT '配置值',
    description VARCHAR(256) DEFAULT '' COMMENT '描述',
    status TINYINT NOT NULL DEFAULT 1 COMMENT '状态：1启用 0禁用',
    sort_order INT NOT NULL DEFAULT 0 COMMENT '排序',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_type_key (config_type, config_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='通用配置表';

-- 初始化默认云端请求策略
INSERT INTO sys_config (config_type, config_key, config_value, description) VALUES
('cloud_request_strategy', 'uniassistant', 'default', '统一助手，使用默认策略');
```

- [ ] **Step 2: 启动 skill-server 验证 migration**

Run: `cd skill-server && mvn spring-boot:run`
Expected: 应用启动成功，数据库中出现 `sys_config` 表

- [ ] **Step 3: Commit**

```bash
git add skill-server/src/main/resources/db/migration/V10__create_sys_config.sql
git commit -m "feat(skill): add sys_config table migration"
```

---

### Task 2: SysConfig 模型 + Mapper + Service + Controller

**Files:**
- Create: `skill-server/src/main/java/com/opencode/cui/skill/model/SysConfig.java`
- Create: `skill-server/src/main/java/com/opencode/cui/skill/mapper/SysConfigMapper.java`
- Create: `skill-server/src/main/resources/mapper/SysConfigMapper.xml`
- Create: `skill-server/src/main/java/com/opencode/cui/skill/service/SysConfigService.java`
- Create: `skill-server/src/main/java/com/opencode/cui/skill/controller/SysConfigController.java`

- [ ] **Step 1: 创建 SysConfig 模型**

```java
package com.opencode.cui.skill.model;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class SysConfig {
    private Long id;
    private String configType;
    private String configKey;
    private String configValue;
    private String description;
    private Integer status;
    private Integer sortOrder;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
```

- [ ] **Step 2: 创建 MyBatis Mapper 接口**

```java
package com.opencode.cui.skill.mapper;

import com.opencode.cui.skill.model.SysConfig;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;

@Mapper
public interface SysConfigMapper {
    List<SysConfig> selectByType(@Param("configType") String configType);
    SysConfig selectByTypeAndKey(@Param("configType") String configType, @Param("configKey") String configKey);
    int insert(SysConfig config);
    int update(SysConfig config);
    int deleteById(@Param("id") Long id);
}
```

- [ ] **Step 3: 创建 Mapper XML**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN" "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
<mapper namespace="com.opencode.cui.skill.mapper.SysConfigMapper">

    <resultMap id="BaseResultMap" type="com.opencode.cui.skill.model.SysConfig">
        <id column="id" property="id"/>
        <result column="config_type" property="configType"/>
        <result column="config_key" property="configKey"/>
        <result column="config_value" property="configValue"/>
        <result column="description" property="description"/>
        <result column="status" property="status"/>
        <result column="sort_order" property="sortOrder"/>
        <result column="created_at" property="createdAt"/>
        <result column="updated_at" property="updatedAt"/>
    </resultMap>

    <select id="selectByType" resultMap="BaseResultMap">
        SELECT * FROM sys_config WHERE config_type = #{configType} ORDER BY sort_order
    </select>

    <select id="selectByTypeAndKey" resultMap="BaseResultMap">
        SELECT * FROM sys_config WHERE config_type = #{configType} AND config_key = #{configKey}
    </select>

    <insert id="insert" useGeneratedKeys="true" keyProperty="id">
        INSERT INTO sys_config (config_type, config_key, config_value, description, status, sort_order)
        VALUES (#{configType}, #{configKey}, #{configValue}, #{description}, #{status}, #{sortOrder})
    </insert>

    <update id="update">
        UPDATE sys_config SET config_value=#{configValue}, description=#{description},
        status=#{status}, sort_order=#{sortOrder} WHERE id=#{id}
    </update>

    <delete id="deleteById">
        DELETE FROM sys_config WHERE id = #{id}
    </delete>
</mapper>
```

- [ ] **Step 4: 创建 SysConfigService（含 Redis 缓存）**

```java
package com.opencode.cui.skill.service;

import com.opencode.cui.skill.mapper.SysConfigMapper;
import com.opencode.cui.skill.model.SysConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class SysConfigService {

    private final SysConfigMapper sysConfigMapper;
    private final StringRedisTemplate redisTemplate;
    private static final String CACHE_PREFIX = "ss:config:";
    private static final Duration CACHE_TTL = Duration.ofMinutes(30);

    public String getValue(String configType, String configKey) {
        String cacheKey = CACHE_PREFIX + configType + ":" + configKey;
        String cached = redisTemplate.opsForValue().get(cacheKey);
        if (cached != null) return cached;

        SysConfig config = sysConfigMapper.selectByTypeAndKey(configType, configKey);
        if (config != null && config.getStatus() == 1) {
            redisTemplate.opsForValue().set(cacheKey, config.getConfigValue(), CACHE_TTL);
            return config.getConfigValue();
        }
        return null;
    }

    public List<SysConfig> listByType(String configType) {
        return sysConfigMapper.selectByType(configType);
    }

    public SysConfig create(SysConfig config) {
        sysConfigMapper.insert(config);
        evictCache(config.getConfigType(), config.getConfigKey());
        return config;
    }

    public void update(SysConfig config) {
        SysConfig existing = sysConfigMapper.selectByTypeAndKey(config.getConfigType(), config.getConfigKey());
        if (existing != null) evictCache(existing.getConfigType(), existing.getConfigKey());
        sysConfigMapper.update(config);
    }

    public void delete(Long id) {
        sysConfigMapper.deleteById(id);
        // 缓存会自然过期
    }

    private void evictCache(String configType, String configKey) {
        redisTemplate.delete(CACHE_PREFIX + configType + ":" + configKey);
    }
}
```

- [ ] **Step 5: 创建管理接口 Controller**

```java
package com.opencode.cui.skill.controller;

import com.opencode.cui.skill.model.SysConfig;
import com.opencode.cui.skill.service.SysConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/admin/configs")
@RequiredArgsConstructor
public class SysConfigController {

    private final SysConfigService sysConfigService;

    @GetMapping
    public ResponseEntity<List<SysConfig>> list(@RequestParam String type) {
        return ResponseEntity.ok(sysConfigService.listByType(type));
    }

    @PostMapping
    public ResponseEntity<SysConfig> create(@RequestBody SysConfig config) {
        return ResponseEntity.ok(sysConfigService.create(config));
    }

    @PutMapping("/{id}")
    public ResponseEntity<Void> update(@PathVariable Long id, @RequestBody SysConfig config) {
        config.setId(id);
        sysConfigService.update(config);
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        sysConfigService.delete(id);
        return ResponseEntity.ok().build();
    }
}
```

- [ ] **Step 6: 编译验证**

Run: `cd skill-server && mvn compile`
Expected: BUILD SUCCESS

- [ ] **Step 7: Commit**

```bash
git add skill-server/src/main/java/com/opencode/cui/skill/model/SysConfig.java \
  skill-server/src/main/java/com/opencode/cui/skill/mapper/SysConfigMapper.java \
  skill-server/src/main/resources/mapper/SysConfigMapper.xml \
  skill-server/src/main/java/com/opencode/cui/skill/service/SysConfigService.java \
  skill-server/src/main/java/com/opencode/cui/skill/controller/SysConfigController.java
git commit -m "feat(skill): add SysConfig CRUD with Redis cache"
```

---

### Task 3: AssistantInfoService

**Files:**
- Create: `skill-server/src/main/java/com/opencode/cui/skill/model/AssistantInfo.java`
- Create: `skill-server/src/main/java/com/opencode/cui/skill/service/AssistantInfoService.java`
- Create: `skill-server/src/main/java/com/opencode/cui/skill/config/AssistantInfoProperties.java`

- [ ] **Step 1: 创建 AssistantInfo 模型**

```java
package com.opencode.cui.skill.model;

import lombok.Data;

@Data
public class AssistantInfo {
    private String assistantScope;    // "business" | "personal"
    private String appId;             // 业务助手标识
    private String cloudEndpoint;     // 云端服务地址
    private String cloudProtocol;     // "sse" | "websocket"
    private String authType;          // "soa" | "apig"

    public boolean isBusiness() {
        return "business".equals(assistantScope);
    }

    public boolean isPersonal() {
        return !isBusiness();
    }
}
```

- [ ] **Step 2: 创建配置属性类**

```java
package com.opencode.cui.skill.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "skill.assistant-info")
public class AssistantInfoProperties {
    private String apiUrl;           // 上游 API 地址
    private String apiToken;         // Bearer token
    private long cacheTtlSeconds = 300;  // Redis 缓存 TTL
}
```

- [ ] **Step 3: 创建 AssistantInfoService**

```java
package com.opencode.cui.skill.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencode.cui.skill.config.AssistantInfoProperties;
import com.opencode.cui.skill.model.AssistantInfo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

@Slf4j
@Service
@RequiredArgsConstructor
public class AssistantInfoService {

    private final AssistantInfoProperties properties;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();

    private static final String CACHE_PREFIX = "ss:assistant:info:";

    public AssistantInfo getAssistantInfo(String ak) {
        // 1. 查 Redis 缓存
        String cacheKey = CACHE_PREFIX + ak;
        String cached = redisTemplate.opsForValue().get(cacheKey);
        if (cached != null) {
            try {
                return objectMapper.readValue(cached, AssistantInfo.class);
            } catch (Exception e) {
                log.warn("Failed to deserialize cached AssistantInfo for ak={}", ak, e);
            }
        }

        // 2. 调用上游 API
        AssistantInfo info = fetchFromUpstream(ak);

        // 3. 写入缓存
        if (info != null) {
            try {
                String json = objectMapper.writeValueAsString(info);
                redisTemplate.opsForValue().set(cacheKey, json,
                        Duration.ofSeconds(properties.getCacheTtlSeconds()));
            } catch (Exception e) {
                log.warn("Failed to cache AssistantInfo for ak={}", ak, e);
            }
        }

        return info;
    }

    public String getCachedScope(String ak) {
        AssistantInfo info = getAssistantInfo(ak);
        return info != null ? info.getAssistantScope() : "personal";
    }

    private AssistantInfo fetchFromUpstream(String ak) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(properties.getApiUrl() + "?ak=" + ak))
                    .header("Authorization", "Bearer " + properties.getApiToken())
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.error("Upstream API returned {} for ak={}", response.statusCode(), ak);
                return null;
            }

            JsonNode root = objectMapper.readTree(response.body());
            String code = root.path("code").asText();
            if (!"200".equals(code)) {
                log.error("Upstream API error code={} for ak={}", code, ak);
                return null;
            }

            JsonNode data = root.path("data");
            AssistantInfo info = new AssistantInfo();

            // identityType: "2" → personal, "3" → business
            String identityType = data.path("identityType").asText();
            info.setAssistantScope("3".equals(identityType) ? "business" : "personal");
            info.setAppId(data.path("hisAppId").asText(null));
            info.setCloudEndpoint(data.path("endpoint").asText(null));
            info.setCloudProtocol(data.path("protocol").asText(null));
            info.setAuthType(data.path("authType").asText(null));

            return info;
        } catch (Exception e) {
            log.error("Failed to fetch AssistantInfo from upstream for ak={}", ak, e);
            return null;
        }
    }
}
```

- [ ] **Step 4: 添加配置到 application.yml**

在 `skill-server/src/main/resources/application.yml` 中添加：

```yaml
skill:
  assistant-info:
    api-url: https://api.openplatform.hisuat.huawei.com/appstore/wecodeapi/open/ak/info
    api-token: ${ASSISTANT_INFO_API_TOKEN:}
    cache-ttl-seconds: 300
```

- [ ] **Step 5: 编译验证**

Run: `cd skill-server && mvn compile`
Expected: BUILD SUCCESS

- [ ] **Step 6: Commit**

```bash
git add skill-server/src/main/java/com/opencode/cui/skill/model/AssistantInfo.java \
  skill-server/src/main/java/com/opencode/cui/skill/service/AssistantInfoService.java \
  skill-server/src/main/java/com/opencode/cui/skill/config/AssistantInfoProperties.java \
  skill-server/src/main/resources/application.yml
git commit -m "feat(skill): add AssistantInfoService with upstream API + Redis cache"
```

---

### Task 4: StreamMessage 类型扩展

**Files:**
- Modify: `skill-server/src/main/java/com/opencode/cui/skill/model/StreamMessage.java`

- [ ] **Step 1: 在 StreamMessage.Types 中新增云端扩展常量**

在 `StreamMessage.java` 的 `Types` 接口中添加：

```java
// 云端扩展
String PLANNING_DELTA = "planning.delta";
String PLANNING_DONE = "planning.done";
String SEARCHING = "searching";
String SEARCH_RESULT = "search_result";
String REFERENCE = "reference";
String ASK_MORE = "ask_more";
```

- [ ] **Step 2: 在 StreamMessage 类中新增字段**

```java
// 云端扩展字段
private List<String> keywords;                  // searching
private List<SearchResultItem> searchResults;   // search_result
private List<ReferenceItem> references;         // reference
private List<String> askMoreQuestions;           // ask_more
```

- [ ] **Step 3: 创建嵌套模型类**

在 `StreamMessage.java` 中或单独文件中创建：

```java
@Data
@Builder
public static class SearchResultItem {
    private String index;
    private String title;
    private String source;
}

@Data
@Builder
public static class ReferenceItem {
    private String index;
    private String title;
    private String source;
    private String url;
    private String content;
}
```

- [ ] **Step 4: 编译验证**

Run: `cd skill-server && mvn compile`
Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add skill-server/src/main/java/com/opencode/cui/skill/model/StreamMessage.java
git commit -m "feat(skill): extend StreamMessage with cloud event types"
```

---

### Task 5: CloudEventTranslator（注册表模式）

**Files:**
- Create: `skill-server/src/main/java/com/opencode/cui/skill/service/CloudEventTranslator.java`

- [ ] **Step 1: 创建 CloudEventTranslator**

完整代码见设计文档 3.4 节。核心结构：
- `Map<String, CloudEventHandler> handlers` 注册表
- `@PostConstruct init()` 注册所有 20 种事件 handler
- `translate(JsonNode event)` 查表并调用 handler

包含所有事件类型的 handler：text.delta、text.done、thinking.delta、thinking.done、tool.update、step.start、step.done、question、permission.ask、permission.reply、session.status、session.title、session.error、file、planning.delta、planning.done、searching、search_result、reference、ask_more

- [ ] **Step 2: 编译验证**

Run: `cd skill-server && mvn compile`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add skill-server/src/main/java/com/opencode/cui/skill/service/CloudEventTranslator.java
git commit -m "feat(skill): add CloudEventTranslator with registry pattern"
```

---

### Task 6: CloudRequestBuilder（配置驱动策略）

**Files:**
- Create: `skill-server/src/main/java/com/opencode/cui/skill/service/cloud/CloudRequestStrategy.java`
- Create: `skill-server/src/main/java/com/opencode/cui/skill/service/cloud/CloudRequestContext.java`
- Create: `skill-server/src/main/java/com/opencode/cui/skill/service/cloud/DefaultCloudRequestStrategy.java`
- Create: `skill-server/src/main/java/com/opencode/cui/skill/service/cloud/CloudRequestBuilder.java`

- [ ] **Step 1: 创建策略接口和上下文**

```java
// CloudRequestStrategy.java
package com.opencode.cui.skill.service.cloud;

import com.fasterxml.jackson.databind.node.ObjectNode;

public interface CloudRequestStrategy {
    String getName();
    ObjectNode build(CloudRequestContext context);
}

// CloudRequestContext.java
package com.opencode.cui.skill.service.cloud;

import lombok.Builder;
import lombok.Data;
import java.util.Map;

@Data
@Builder
public class CloudRequestContext {
    private String content;
    private String contentType;
    private String assistantAccount;
    private String sendUserAccount;
    private String imGroupId;
    private String clientLang;
    private String clientType;
    private String topicId;
    private String messageId;
    private Map<String, Object> extParameters;
}
```

- [ ] **Step 2: 创建默认策略**

```java
package com.opencode.cui.skill.service.cloud;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

@Component
public class DefaultCloudRequestStrategy implements CloudRequestStrategy {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public String getName() { return "default"; }

    @Override
    public ObjectNode build(CloudRequestContext context) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("type", context.getContentType() != null ? context.getContentType() : "text");
        node.put("content", context.getContent());
        node.put("assistantAccount", context.getAssistantAccount());
        node.put("sendUserAccount", context.getSendUserAccount());
        node.put("imGroupId", context.getImGroupId());
        node.put("clientLang", context.getClientLang() != null ? context.getClientLang() : "zh");
        node.put("clientType", context.getClientType());
        node.put("topicId", context.getTopicId());
        node.put("messageId", context.getMessageId());

        if (context.getExtParameters() != null) {
            node.set("extParameters", objectMapper.valueToTree(context.getExtParameters()));
        } else {
            node.putObject("extParameters");
        }
        return node;
    }
}
```

- [ ] **Step 3: 创建 CloudRequestBuilder（配置驱动调度）**

```java
package com.opencode.cui.skill.service.cloud;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.opencode.cui.skill.service.SysConfigService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Component
public class CloudRequestBuilder {

    private final Map<String, CloudRequestStrategy> strategyMap;
    private final CloudRequestStrategy defaultStrategy;
    private final SysConfigService sysConfigService;

    private static final String CONFIG_TYPE = "cloud_request_strategy";

    public CloudRequestBuilder(List<CloudRequestStrategy> strategies, SysConfigService sysConfigService) {
        this.strategyMap = strategies.stream()
                .collect(Collectors.toMap(CloudRequestStrategy::getName, Function.identity()));
        this.defaultStrategy = this.strategyMap.get("default");
        this.sysConfigService = sysConfigService;
    }

    public ObjectNode buildCloudRequest(String appId, CloudRequestContext context) {
        String strategyName = sysConfigService.getValue(CONFIG_TYPE, appId);
        CloudRequestStrategy strategy = (strategyName != null)
                ? strategyMap.getOrDefault(strategyName, defaultStrategy)
                : defaultStrategy;
        log.debug("Building cloud request for appId={} with strategy={}", appId, strategy.getName());
        return strategy.build(context);
    }
}
```

- [ ] **Step 4: 编译验证**

Run: `cd skill-server && mvn compile`
Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add skill-server/src/main/java/com/opencode/cui/skill/service/cloud/
git commit -m "feat(skill): add CloudRequestBuilder with config-driven strategy"
```

---

## Phase 2: SS 集成

### Task 7: AssistantScopeStrategy + Dispatcher

**Files:**
- Create: `skill-server/src/main/java/com/opencode/cui/skill/service/scope/AssistantScopeStrategy.java`
- Create: `skill-server/src/main/java/com/opencode/cui/skill/service/scope/PersonalScopeStrategy.java`
- Create: `skill-server/src/main/java/com/opencode/cui/skill/service/scope/BusinessScopeStrategy.java`
- Create: `skill-server/src/main/java/com/opencode/cui/skill/service/scope/AssistantScopeDispatcher.java`

- [ ] **Step 1: 创建策略接口**

```java
package com.opencode.cui.skill.service.scope;

import com.fasterxml.jackson.databind.JsonNode;
import com.opencode.cui.skill.model.AssistantInfo;
import com.opencode.cui.skill.model.GatewayMessage;
import com.opencode.cui.skill.model.InvokeCommand;
import com.opencode.cui.skill.model.StreamMessage;

public interface AssistantScopeStrategy {
    String getScope();
    GatewayMessage buildInvoke(InvokeCommand command, AssistantInfo info);
    String generateToolSessionId();
    boolean requiresSessionCreatedCallback();
    boolean requiresOnlineCheck();
    StreamMessage translateEvent(JsonNode event, String sessionId);
}
```

- [ ] **Step 2: 创建 PersonalScopeStrategy**

将 GatewayRelayService 现有的 invoke 构建逻辑、OpenCodeEventTranslator 调用封装进来。保持与现有逻辑完全一致。

```java
package com.opencode.cui.skill.service.scope;

// ... 封装现有逻辑，调用 OpenCodeEventTranslator
// generateToolSessionId() 返回 null
// requiresSessionCreatedCallback() 返回 true
// requiresOnlineCheck() 返回 true
```

- [ ] **Step 3: 创建 BusinessScopeStrategy**

```java
package com.opencode.cui.skill.service.scope;

// ... 构建 cloudRequest 到 payload
// generateToolSessionId() 返回 "cloud-" + snowflakeId
// requiresSessionCreatedCallback() 返回 false
// requiresOnlineCheck() 返回 false
// translateEvent() 调用 CloudEventTranslator
```

- [ ] **Step 4: 创建 AssistantScopeDispatcher**

```java
package com.opencode.cui.skill.service.scope;

import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.function.Function;

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

- [ ] **Step 5: 编译验证**

Run: `cd skill-server && mvn compile`
Expected: BUILD SUCCESS

- [ ] **Step 6: Commit**

```bash
git add skill-server/src/main/java/com/opencode/cui/skill/service/scope/
git commit -m "feat(skill): add AssistantScopeStrategy pattern"
```

---

### Task 8: SS 集成改动（GatewayRelayService + Controllers + Router）

**Files:**
- Modify: `skill-server/src/main/java/com/opencode/cui/skill/service/GatewayRelayService.java`
- Modify: `skill-server/src/main/java/com/opencode/cui/skill/controller/ImInboundController.java`
- Modify: `skill-server/src/main/java/com/opencode/cui/skill/controller/SkillMessageController.java`
- Modify: `skill-server/src/main/java/com/opencode/cui/skill/controller/SkillSessionController.java`
- Modify: `skill-server/src/main/java/com/opencode/cui/skill/service/ImSessionManager.java`
- Modify: `skill-server/src/main/java/com/opencode/cui/skill/service/GatewayMessageRouter.java`

- [ ] **Step 1: 改造 GatewayRelayService.sendInvokeToGateway()**

注入 `AssistantInfoService` 和 `AssistantScopeDispatcher`，使用策略构建 invoke：

```java
AssistantInfo info = assistantInfoService.getAssistantInfo(command.getAk());
AssistantScopeStrategy strategy = scopeDispatcher.getStrategy(info.getAssistantScope());
GatewayMessage msg = strategy.buildInvoke(command, info);
gatewayWSClient.send(msg);
```

- [ ] **Step 2: 改造会话创建流程**

在 `ImSessionManager.createSessionAsync()` 和 `SkillSessionController.createSession()` 中：

```java
AssistantInfo info = assistantInfoService.getAssistantInfo(ak);
AssistantScopeStrategy strategy = scopeDispatcher.getStrategy(info.getAssistantScope());

String toolSessionId = strategy.generateToolSessionId();
// 如果 toolSessionId 不为 null，直接赋值给 session，跳过 create_session
if (toolSessionId != null) {
    session.setToolSessionId(toolSessionId);
    // 不发 create_session，直接标记就绪
}
boolean needCallback = strategy.requiresSessionCreatedCallback();
```

- [ ] **Step 3: 改造 Agent 在线检查**

在 `ImInboundController` 和 `SkillMessageController.routeToGateway()` 中：

```java
AssistantInfo info = assistantInfoService.getAssistantInfo(ak);
AssistantScopeStrategy strategy = scopeDispatcher.getStrategy(info.getAssistantScope());

if (strategy.requiresOnlineCheck()) {
    // 现有在线检查逻辑
}
```

- [ ] **Step 4: 改造 GatewayMessageRouter.handleToolEvent() 事件翻译**

```java
String scope = assistantInfoService.getCachedScope(ak);
AssistantScopeStrategy strategy = scopeDispatcher.getStrategy(scope);
StreamMessage msg = strategy.translateEvent(event, sessionId);
```

- [ ] **Step 5: 改造 IM 出站过滤**

在 `GatewayMessageRouter.routeAssistantMessage()` 中，业务助手的 IM 出站过滤云端扩展事件：

```java
if ("business".equals(scope) && isImSession) {
    String msgType = msg.getType();
    if (Set.of(Types.PLANNING_DELTA, Types.PLANNING_DONE, Types.THINKING_DELTA, Types.THINKING_DONE,
               Types.SEARCHING, Types.SEARCH_RESULT, Types.REFERENCE, Types.ASK_MORE).contains(msgType)) {
        // 不发送到 IM，仅广播到 MiniApp（如果也有 MiniApp 订阅的话）
        return;
    }
}
```

- [ ] **Step 6: 编译验证**

Run: `cd skill-server && mvn compile`
Expected: BUILD SUCCESS

- [ ] **Step 7: Commit**

```bash
git add skill-server/src/main/java/com/opencode/cui/skill/service/GatewayRelayService.java \
  skill-server/src/main/java/com/opencode/cui/skill/controller/ImInboundController.java \
  skill-server/src/main/java/com/opencode/cui/skill/controller/SkillMessageController.java \
  skill-server/src/main/java/com/opencode/cui/skill/controller/SkillSessionController.java \
  skill-server/src/main/java/com/opencode/cui/skill/service/ImSessionManager.java \
  skill-server/src/main/java/com/opencode/cui/skill/service/GatewayMessageRouter.java
git commit -m "feat(skill): integrate AssistantScopeStrategy into SS pipeline"
```

---

## Phase 3: Gateway

### Task 9: GatewayMessage 模型扩展

**Files:**
- Modify: `ai-gateway/src/main/java/com/opencode/cui/gateway/model/GatewayMessage.java`

- [ ] **Step 1: 新增 assistantScope 字段**

```java
private String assistantScope;  // "business" | "personal"
```

确保 Jackson `@JsonInclude(NON_NULL)` 覆盖此字段。

- [ ] **Step 2: 编译验证**

Run: `cd ai-gateway && mvn compile`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add ai-gateway/src/main/java/com/opencode/cui/gateway/model/GatewayMessage.java
git commit -m "feat(gateway): add assistantScope to GatewayMessage"
```

---

### Task 10: CloudRouteService

**Files:**
- Create: `ai-gateway/src/main/java/com/opencode/cui/gateway/model/CloudRouteInfo.java`
- Create: `ai-gateway/src/main/java/com/opencode/cui/gateway/service/CloudRouteService.java`

- [ ] **Step 1: 创建 CloudRouteInfo 模型**

```java
package com.opencode.cui.gateway.model;

import lombok.Data;

@Data
public class CloudRouteInfo {
    private String appId;
    private String endpoint;
    private String protocol;   // "sse" | "websocket"
    private String authType;   // "soa" | "apig"
}
```

- [ ] **Step 2: 创建 CloudRouteService（调上游 API + Redis 缓存）**

与 SS 的 AssistantInfoService 调用相同的上游 API，但只提取路由相关字段（appId、endpoint、protocol、authType）。Redis 缓存 key 为 `gw:cloud:route:{ak}`。

- [ ] **Step 3: 添加配置到 application.yml**

```yaml
gateway:
  cloud:
    route:
      api-url: https://api.openplatform.hisuat.huawei.com/appstore/wecodeapi/open/ak/info
      api-token: ${CLOUD_ROUTE_API_TOKEN:}
      cache-ttl-seconds: 300
    connect-timeout: 5000
    read-timeout: 120000
```

- [ ] **Step 4: 编译验证**

Run: `cd ai-gateway && mvn compile`
Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add ai-gateway/src/main/java/com/opencode/cui/gateway/model/CloudRouteInfo.java \
  ai-gateway/src/main/java/com/opencode/cui/gateway/service/CloudRouteService.java \
  ai-gateway/src/main/resources/application.yml
git commit -m "feat(gateway): add CloudRouteService with upstream API + Redis cache"
```

---

### Task 11: CloudAuthService（策略模式）

**Files:**
- Create: `ai-gateway/src/main/java/com/opencode/cui/gateway/service/cloud/CloudAuthStrategy.java`
- Create: `ai-gateway/src/main/java/com/opencode/cui/gateway/service/cloud/SoaAuthStrategy.java`
- Create: `ai-gateway/src/main/java/com/opencode/cui/gateway/service/cloud/ApigAuthStrategy.java`
- Create: `ai-gateway/src/main/java/com/opencode/cui/gateway/service/cloud/CloudAuthService.java`

- [ ] **Step 1: 创建策略接口**

```java
package com.opencode.cui.gateway.service.cloud;

import java.net.http.HttpRequest;

public interface CloudAuthStrategy {
    String getAuthType();
    void applyAuth(HttpRequest.Builder requestBuilder, String appId);
}
```

- [ ] **Step 2: 创建 SoaAuthStrategy 和 ApigAuthStrategy**

各自实现获取凭证和填充认证头的逻辑。具体认证细节根据 SOA/APIG 文档实现。

- [ ] **Step 3: 创建 CloudAuthService 调度器**

```java
package com.opencode.cui.gateway.service.cloud;

import org.springframework.stereotype.Component;
import java.net.http.HttpRequest;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.function.Function;

@Component
public class CloudAuthService {
    private final Map<String, CloudAuthStrategy> strategyMap;

    public CloudAuthService(List<CloudAuthStrategy> strategies) {
        this.strategyMap = strategies.stream()
                .collect(Collectors.toMap(CloudAuthStrategy::getAuthType, Function.identity()));
    }

    public void applyAuth(HttpRequest.Builder requestBuilder, String appId, String authType) {
        CloudAuthStrategy strategy = strategyMap.get(authType);
        if (strategy == null) {
            throw new IllegalArgumentException("Unknown authType: " + authType);
        }
        strategy.applyAuth(requestBuilder, appId);
    }
}
```

- [ ] **Step 4: 编译验证**

Run: `cd ai-gateway && mvn compile`
Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add ai-gateway/src/main/java/com/opencode/cui/gateway/service/cloud/
git commit -m "feat(gateway): add CloudAuthService with strategy pattern"
```

---

### Task 12: CloudProtocolClient（策略模式 + SSE 实现）

**Files:**
- Create: `ai-gateway/src/main/java/com/opencode/cui/gateway/service/cloud/CloudProtocolStrategy.java`
- Create: `ai-gateway/src/main/java/com/opencode/cui/gateway/service/cloud/CloudConnectionContext.java`
- Create: `ai-gateway/src/main/java/com/opencode/cui/gateway/service/cloud/SseProtocolStrategy.java`
- Create: `ai-gateway/src/main/java/com/opencode/cui/gateway/service/cloud/CloudProtocolClient.java`

- [ ] **Step 1: 创建策略接口和连接上下文**

```java
// CloudProtocolStrategy.java
package com.opencode.cui.gateway.service.cloud;

import com.opencode.cui.gateway.model.GatewayMessage;
import java.util.function.Consumer;

public interface CloudProtocolStrategy {
    String getProtocol();
    void connect(CloudConnectionContext context,
                 Consumer<GatewayMessage> onEvent,
                 Consumer<Throwable> onError);
}

// CloudConnectionContext.java
package com.opencode.cui.gateway.service.cloud;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class CloudConnectionContext {
    private String endpoint;
    private JsonNode cloudRequest;
    private String appId;
    private String authType;
    private String traceId;
}
```

- [ ] **Step 2: 创建 SseProtocolStrategy**

核心实现：
1. 构建 HTTP POST 请求（JSON body = cloudRequest）
2. 通过 `CloudAuthService.applyAuth()` 填充认证头
3. 使用 Java HttpClient 发送请求，读取 SSE 流
4. 逐行解析 `data: {JSON}`，反序列化为 `GatewayMessage`
5. 调用 `onEvent` 回调

```java
@Component
public class SseProtocolStrategy implements CloudProtocolStrategy {
    private final CloudAuthService cloudAuthService;
    private final ObjectMapper objectMapper;

    @Override public String getProtocol() { return "sse"; }

    @Override
    public void connect(CloudConnectionContext context,
                        Consumer<GatewayMessage> onEvent,
                        Consumer<Throwable> onError) {
        try {
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(context.getEndpoint()))
                    .header("Content-Type", "application/json")
                    .header("X-Trace-Id", context.getTraceId())
                    .header("X-Request-Id", UUID.randomUUID().toString())
                    .header("X-App-Id", context.getAppId())
                    .POST(HttpRequest.BodyPublishers.ofString(
                            objectMapper.writeValueAsString(context.getCloudRequest())));

            cloudAuthService.applyAuth(requestBuilder, context.getAppId(), context.getAuthType());

            HttpClient client = HttpClient.newHttpClient();
            HttpResponse<Stream<String>> response = client.send(
                    requestBuilder.build(),
                    HttpResponse.BodyHandlers.ofLines());

            if (response.statusCode() != 200) {
                onError.accept(new RuntimeException("HTTP " + response.statusCode()));
                return;
            }

            response.body().forEach(line -> {
                if (line.startsWith("data: ") || line.startsWith("data:")) {
                    String json = line.substring(line.indexOf(':') + 1).trim();
                    try {
                        GatewayMessage msg = objectMapper.readValue(json, GatewayMessage.class);
                        onEvent.accept(msg);
                    } catch (Exception e) {
                        log.warn("Failed to parse SSE event: {}", json, e);
                    }
                }
            });
        } catch (Exception e) {
            onError.accept(e);
        }
    }
}
```

> 注意：生产环境应使用异步 HTTP 客户端（如 WebClient）替代同步 HttpClient，避免阻塞。可在后续优化阶段改进。

- [ ] **Step 3: 创建 CloudProtocolClient 调度器**

```java
@Component
public class CloudProtocolClient {
    private final Map<String, CloudProtocolStrategy> strategyMap;

    public CloudProtocolClient(List<CloudProtocolStrategy> strategies) {
        this.strategyMap = strategies.stream()
                .collect(Collectors.toMap(CloudProtocolStrategy::getProtocol, Function.identity()));
    }

    public void connect(String protocol, CloudConnectionContext context,
                        Consumer<GatewayMessage> onEvent, Consumer<Throwable> onError) {
        CloudProtocolStrategy strategy = strategyMap.get(protocol);
        if (strategy == null) {
            onError.accept(new IllegalArgumentException("Unknown protocol: " + protocol));
            return;
        }
        strategy.connect(context, onEvent, onError);
    }
}
```

- [ ] **Step 4: 编译验证**

Run: `cd ai-gateway && mvn compile`
Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add ai-gateway/src/main/java/com/opencode/cui/gateway/service/cloud/
git commit -m "feat(gateway): add CloudProtocolClient with SSE strategy"
```

---

### Task 13: CloudAgentService + InvokeRouteStrategy

**Files:**
- Create: `ai-gateway/src/main/java/com/opencode/cui/gateway/service/CloudAgentService.java`
- Create: `ai-gateway/src/main/java/com/opencode/cui/gateway/service/cloud/InvokeRouteStrategy.java`
- Create: `ai-gateway/src/main/java/com/opencode/cui/gateway/service/cloud/PersonalInvokeRouteStrategy.java`
- Create: `ai-gateway/src/main/java/com/opencode/cui/gateway/service/cloud/BusinessInvokeRouteStrategy.java`
- Modify: `ai-gateway/src/main/java/com/opencode/cui/gateway/service/SkillRelayService.java`

- [ ] **Step 1: 创建 CloudAgentService**

完整代码见设计文档 4.3 节。编排 CloudRouteService → CloudProtocolClient → 注入路由上下文 → relayToSkill。

- [ ] **Step 2: 创建 InvokeRouteStrategy 接口和实现**

```java
public interface InvokeRouteStrategy {
    String getScope();
    void route(GatewayMessage message);
}

@Component
public class PersonalInvokeRouteStrategy implements InvokeRouteStrategy {
    // 封装现有 SkillRelayService 的本地 Agent 转发逻辑
    @Override public String getScope() { return "personal"; }
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

- [ ] **Step 3: 改造 SkillRelayService.handleInvokeFromSkill()**

```java
public void handleInvokeFromSkill(WebSocketSession session, GatewayMessage message) {
    String scope = Optional.ofNullable(message.getAssistantScope()).orElse("personal");
    InvokeRouteStrategy strategy = routeStrategyMap.getOrDefault(scope, personalStrategy);
    strategy.route(message);
}
```

注意：提取现有的 invoke → Agent 的逻辑到 `PersonalInvokeRouteStrategy.route()` 中，保持 SkillRelayService 的其他逻辑（如 relayToSkill 上行路由）不变。

- [ ] **Step 4: 编译验证**

Run: `cd ai-gateway && mvn compile`
Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add ai-gateway/src/main/java/com/opencode/cui/gateway/service/CloudAgentService.java \
  ai-gateway/src/main/java/com/opencode/cui/gateway/service/cloud/InvokeRouteStrategy.java \
  ai-gateway/src/main/java/com/opencode/cui/gateway/service/cloud/PersonalInvokeRouteStrategy.java \
  ai-gateway/src/main/java/com/opencode/cui/gateway/service/cloud/BusinessInvokeRouteStrategy.java \
  ai-gateway/src/main/java/com/opencode/cui/gateway/service/SkillRelayService.java
git commit -m "feat(gateway): add CloudAgentService + InvokeRouteStrategy"
```

---

## Phase 4: 前端

### Task 14: StreamAssembler 新类型渲染

**Files:**
- Modify: `skill-miniapp/src/protocol/StreamAssembler.ts`
- Create: 新的 UI 组件（根据前端架构决定具体文件）

- [ ] **Step 1: 在 StreamAssembler 中处理新 StreamMessage 类型**

新增对以下类型的处理：
- `planning.delta` / `planning.done` → 构建 PlanningPart
- `searching` → 构建 SearchingPart
- `search_result` → 构建 SearchResultPart
- `reference` → 构建 ReferencePart
- `ask_more` → 构建 AskMorePart

- [ ] **Step 2: 创建对应的 UI 组件**

每种新类型一个组件，渲染方式见设计文档 5.1 节。

- [ ] **Step 3: 验证前端编译**

Run: `cd skill-miniapp && npm run build`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add skill-miniapp/src/
git commit -m "feat(miniapp): add cloud event type rendering"
```

---

## Phase 5: 云端 IM 推送接口

### Task 15: GW 云端推送 REST 接口

**Files:**
- Create: `ai-gateway/src/main/java/com/opencode/cui/gateway/model/ImPushRequest.java`
- Create: `ai-gateway/src/main/java/com/opencode/cui/gateway/controller/CloudPushController.java`

- [ ] **Step 1: 创建 ImPushRequest 模型**

```java
package com.opencode.cui.gateway.model;

import lombok.Data;

@Data
public class ImPushRequest {
    private String assistantAccount;  // 助手账号
    private String sessionType;       // "direct" / "group"
    private String sessionId;         // IM 侧会话 ID
    private String content;           // 推送文本内容
    private String msgType;           // "text"
}
```

- [ ] **Step 2: 创建 CloudPushController**

```java
package com.opencode.cui.gateway.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencode.cui.gateway.model.GatewayMessage;
import com.opencode.cui.gateway.model.ImPushRequest;
import com.opencode.cui.gateway.service.SkillRelayService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/gateway/cloud")
@RequiredArgsConstructor
public class CloudPushController {

    private final SkillRelayService skillRelayService;
    private final ObjectMapper objectMapper;

    @PostMapping("/im-push")
    public ResponseEntity<Void> imPush(@RequestBody ImPushRequest request) {
        GatewayMessage msg = new GatewayMessage();
        msg.setType("im_push");
        msg.setPayload(objectMapper.valueToTree(request));
        msg.setTraceId(UUID.randomUUID().toString());

        skillRelayService.broadcastToSkill(msg);
        return ResponseEntity.ok().build();
    }
}
```

- [ ] **Step 3: 在 GatewayMessage.Type 中新增 im_push**

在 `GatewayMessage.java` 的 `Type` 接口中添加：

```java
String IM_PUSH = "im_push";
```

- [ ] **Step 4: 编译验证**

Run: `cd ai-gateway && mvn compile`
Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add ai-gateway/src/main/java/com/opencode/cui/gateway/model/ImPushRequest.java \
  ai-gateway/src/main/java/com/opencode/cui/gateway/controller/CloudPushController.java \
  ai-gateway/src/main/java/com/opencode/cui/gateway/model/GatewayMessage.java
git commit -m "feat(gateway): add cloud IM push REST endpoint"
```

---

### Task 16: SS 处理 im_push 消息

**Files:**
- Modify: `skill-server/src/main/java/com/opencode/cui/skill/service/GatewayMessageRouter.java`

- [ ] **Step 1: 在 GatewayMessageRouter.dispatchLocally() 中新增 im_push 分支**

```java
case "im_push" -> handleImPush(message);
```

- [ ] **Step 2: 实现 handleImPush 方法**

```java
private void handleImPush(GatewayMessage message) {
    JsonNode payload = message.getPayload();
    String assistantAccount = payload.path("assistantAccount").asText();
    String sessionType = payload.path("sessionType").asText();
    String sessionId = payload.path("sessionId").asText();
    String content = payload.path("content").asText();
    String msgType = payload.path("msgType").asText("text");

    log.info("Handling im_push: assistantAccount={}, sessionType={}, sessionId={}, traceId={}",
            assistantAccount, sessionType, sessionId, message.getTraceId());

    // 直接调用 IM 出站接口发送，不走会话管理
    imOutboundService.sendMessage(assistantAccount, sessionType, sessionId, content);
}
```

> 注意：`imOutboundService` 是现有的 IM 出站服务，确认实际类名和方法签名后调整。

- [ ] **Step 3: 编译验证**

Run: `cd skill-server && mvn compile`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add skill-server/src/main/java/com/opencode/cui/skill/service/GatewayMessageRouter.java
git commit -m "feat(skill): handle im_push message for cloud IM push"
```

---

## Phase 6: 云端 Question/Permission 旁路回复（P2）

> 本 Phase 为 P2 优先级，首期可跳过。首期要求云端避免返回 question/permission 事件。

### Task 17: GW SSE 连接映射管理

**Files:**
- Modify: `ai-gateway/src/main/java/com/opencode/cui/gateway/service/CloudAgentService.java`

- [ ] **Step 1: 新增 SSE 连接映射**

在 `CloudAgentService` 中维护 `topicId → SSE 连接上下文` 的映射：

```java
// topicId → 活跃的 SSE 连接上下文（用于旁路回复时定位连接）
private final ConcurrentHashMap<String, ActiveSseConnection> activeSseConnections = new ConcurrentHashMap<>();

@Data
private static class ActiveSseConnection {
    private final String ak;
    private final String endpoint;
    private final String appId;
    private final String authType;
    private final Instant createdAt;
}
```

- [ ] **Step 2: 在 handleInvoke(chat) 时注册连接映射**

```java
// chat invoke 建立 SSE 连接时
String topicId = invokeMessage.getPayload().path("cloudRequest").path("topicId").asText();
activeSseConnections.put(topicId, new ActiveSseConnection(ak, routeInfo.getEndpoint(), routeInfo.getAppId(), routeInfo.getAuthType(), Instant.now()));

// SSE 结束（tool_done/tool_error）时清理
activeSseConnections.remove(topicId);
```

- [ ] **Step 3: 编译验证**

Run: `cd ai-gateway && mvn compile`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add ai-gateway/src/main/java/com/opencode/cui/gateway/service/CloudAgentService.java
git commit -m "feat(gateway): add SSE connection mapping for sideband reply"
```

---

### Task 18: GW 旁路回复路由

**Files:**
- Modify: `ai-gateway/src/main/java/com/opencode/cui/gateway/service/CloudAgentService.java`
- Modify: `ai-gateway/src/main/java/com/opencode/cui/gateway/service/cloud/BusinessInvokeRouteStrategy.java`

- [ ] **Step 1: CloudAgentService 新增 handleReply 方法**

```java
public void handleReply(GatewayMessage invokeMessage) {
    String action = invokeMessage.getAction();
    JsonNode payload = invokeMessage.getPayload();
    String topicId = payload.path("toolSessionId").asText();

    // 从连接映射获取云端信息
    ActiveSseConnection conn = activeSseConnections.get(topicId);
    if (conn == null) {
        log.warn("No active SSE connection for topicId={}, cannot send reply", topicId);
        GatewayMessage errorMsg = buildCloudError(invokeMessage,
                new RuntimeException("No active SSE connection for reply"));
        skillRelayService.relayToSkill(errorMsg);
        return;
    }

    // 构建旁路 REST 请求体
    ObjectNode replyBody = objectMapper.createObjectNode();
    replyBody.put("type", action); // "question_reply" / "permission_reply"
    replyBody.put("topicId", topicId);

    if ("question_reply".equals(action)) {
        replyBody.put("toolCallId", payload.path("toolCallId").asText());
        replyBody.put("answer", payload.path("answer").asText());
    } else if ("permission_reply".equals(action)) {
        replyBody.put("permissionId", payload.path("permissionId").asText());
        replyBody.put("response", payload.path("response").asText());
    }

    // 调云端旁路 REST 接口
    String replyEndpoint = conn.getEndpoint() + "/reply";
    try {
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(replyEndpoint))
                .header("Content-Type", "application/json")
                .header("X-Trace-Id", invokeMessage.getTraceId())
                .header("X-App-Id", conn.getAppId())
                .POST(HttpRequest.BodyPublishers.ofString(
                        objectMapper.writeValueAsString(replyBody)));

        cloudAuthService.applyAuth(requestBuilder, conn.getAppId(), conn.getAuthType());

        HttpResponse<String> response = httpClient.send(
                requestBuilder.build(), HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            log.error("Cloud reply endpoint returned {}: {}", response.statusCode(), response.body());
        }
    } catch (Exception e) {
        log.error("Failed to send reply to cloud for topicId={}", topicId, e);
    }
    // 不需要处理响应 —— 后续事件通过原有 SSE 连接回来
}
```

- [ ] **Step 2: BusinessInvokeRouteStrategy 增加 action 路由**

```java
@Override
public void route(GatewayMessage message) {
    String action = message.getAction();
    if ("question_reply".equals(action) || "permission_reply".equals(action)) {
        cloudAgentService.handleReply(message);
    } else {
        cloudAgentService.handleInvoke(message);
    }
}
```

- [ ] **Step 3: 编译验证**

Run: `cd ai-gateway && mvn compile`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add ai-gateway/src/main/java/com/opencode/cui/gateway/service/CloudAgentService.java \
  ai-gateway/src/main/java/com/opencode/cui/gateway/service/cloud/BusinessInvokeRouteStrategy.java
git commit -m "feat(gateway): add sideband reply routing for question/permission"
```

---

### Task 19: SS 侧 question_reply/permission_reply invoke 构建

**Files:**
- Modify: `skill-server/src/main/java/com/opencode/cui/skill/service/scope/BusinessScopeStrategy.java`

- [ ] **Step 1: BusinessScopeStrategy.buildInvoke 支持 reply action**

```java
@Override
public GatewayMessage buildInvoke(InvokeCommand command, AssistantInfo info) {
    GatewayMessage msg = buildInvokeMessage(command);
    msg.setAssistantScope("business");

    String action = command.getAction();
    if ("question_reply".equals(action) || "permission_reply".equals(action)) {
        // reply 不需要 cloudRequest，payload 直接透传（answer/toolCallId 或 permissionId/response）
        return msg;
    }

    // chat：构建 cloudRequest
    ObjectNode cloudRequest = cloudRequestBuilder.buildCloudRequest(
            info.getAppId(), buildCloudRequestContext(command));
    msg.getPayload().set("cloudRequest", cloudRequest);
    return msg;
}
```

- [ ] **Step 2: 编译验证**

Run: `cd skill-server && mvn compile`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add skill-server/src/main/java/com/opencode/cui/skill/service/scope/BusinessScopeStrategy.java
git commit -m "feat(skill): support question_reply/permission_reply in BusinessScopeStrategy"
```

---

## Phase 7: 配置 + 集成测试

### Task 20: 端到端集成验证

- [ ] **Step 1: 确保配置完整**

检查 SS 和 GW 的 `application.yml` 中所有新增配置项已正确设置：
- `skill.assistant-info.api-url` / `api-token` / `cache-ttl-seconds`
- `gateway.cloud.route.api-url` / `api-token` / `cache-ttl-seconds`
- `gateway.cloud.connect-timeout` / `read-timeout`
- `gateway.cloud.auth.soa.*` / `apig.*`

- [ ] **Step 2: 通过管理接口初始化策略配置**

```bash
curl -X POST http://localhost:8080/api/admin/configs \
  -H "Content-Type: application/json" \
  -d '{"configType":"cloud_request_strategy","configKey":"uniassistant","configValue":"default","description":"统一助手","status":1,"sortOrder":0}'
```

- [ ] **Step 3: MiniApp 链路测试**

1. 创建一个 business scope 的会话
2. 发送消息，验证 SS 构建 cloudRequest → GW 路由到云端 → SSE 事件回传 → 前端渲染
3. 验证 toolSessionId 为 `cloud-*` 格式（本地生成）
4. 验证不检查 Agent 在线状态

- [ ] **Step 4: IM 链路测试**

1. 通过 IM 入站接口发送消息到 business scope 助手
2. 验证 IM 出站只收到 text 内容，不收到 planning/thinking/searching 等
3. 验证不检查 Agent 在线状态

- [ ] **Step 5: 个人助手回归测试**

1. 确认 personal scope 助手的完整流程不受影响
2. 验证 create_session → session_created → chat 流程正常
3. 验证 Agent 在线检查正常

- [ ] **Step 6: 错误场景测试**

1. 云端不可用 → 验证 tool_error 回传
2. 认证失败 → 验证 tool_error(cloud_auth_failed)
3. SSE 流中断 → 验证 tool_error(cloud_read_timeout)
4. 上游 API 不可用 → 验证 Redis 缓存兜底

- [ ] **Step 7: IM 推送接口测试**

1. 调用 `POST /api/gateway/cloud/im-push` 推送单聊消息
```bash
curl -X POST http://localhost:8081/api/gateway/cloud/im-push \
  -H "Content-Type: application/json" \
  -d '{"assistantAccount":"assistant-bot-001","sessionType":"direct","sessionId":"im-123","content":"定时推送测试","msgType":"text"}'
```
2. 验证 IM 出站收到消息
3. 推送群聊消息，验证同样正常
4. 验证推送不走会话管理（不创建 SkillSession）

- [ ] **Step 8: Question/Permission 旁路回复测试（P2）**

> 如果 Phase 6 已实现，执行以下测试：

1. 模拟云端 SSE 返回 question 事件，验证前端展示问题卡片
2. 用户回答，验证 SS 发 invoke(action=question_reply) → GW 调旁路 REST
3. 验证云端收到回复后继续在同一 SSE 推送后续事件
4. 模拟 permission.ask + permission_reply 同样流程
5. 测试 SSE 连接超时场景

- [ ] **Step 9: Commit**

```bash
git commit -m "test: cloud agent end-to-end integration verification"
```
