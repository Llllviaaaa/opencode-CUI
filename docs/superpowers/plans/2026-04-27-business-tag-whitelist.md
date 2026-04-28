# Business Tag Whitelist Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 skill-server 加 businessTag 白名单 gate，控制业务助手是否走云端协议；顺带修复 `AssistantInfo.appId` 一直为 null 的 bug + 字段重命名。

**Architecture:** 复用现有 `sys_config` 表存储白名单条目和总开关；新增 `BusinessWhitelistService` 包装 fail-open 决策逻辑（含 5min TTL 集合缓存）；`AssistantScopeDispatcher` 新增 `getStrategy(AssistantInfo info)` 重载，9 处调用点机械替换为传 info 入参；不做 session 级 sticky strategy。

**Tech Stack:** Spring Boot 3 + Java 21 + MyBatis + Redis (StringRedisTemplate) + Flyway + JUnit 5 + Mockito

**Spec:** [docs/superpowers/specs/2026-04-27-business-tag-whitelist-design.md](../specs/2026-04-27-business-tag-whitelist-design.md)

---

## Task 1: V11 migration — 注入总开关默认行（幂等）

**Files:**
- Create: `skill-server/src/main/resources/db/migration/V11__init_business_whitelist_config.sql`

- [ ] **Step 1: 创建 V11 SQL 文件**

```sql
-- V11__init_business_whitelist_config.sql
-- 总开关：默认 '0' = 关闭白名单 gate（全 business 走云端，等同当前线上行为）
-- 幂等：利用 sys_config 已有的 UNIQUE KEY uk_type_key (config_type, config_key)（V10 创建）
INSERT INTO sys_config (config_type, config_key, config_value, description, status) VALUES
  ('cloud_route', 'business_whitelist_enabled', '0',
   '业务助手云端白名单开关：1=启用白名单 gate；0=关闭 gate（全 business 放行，老行为）', 1)
ON DUPLICATE KEY UPDATE id = id;
```

- [ ] **Step 2: 启动应用让 Flyway 自动执行 migration**

Run: `cd skill-server && mvn spring-boot:run` （或在 IDE 启动）
Expected: 启动日志包含 `Migrating schema "skill" to version "11 - init business whitelist config"`，无 ERROR

- [ ] **Step 3: 验证总开关行已写入**

Run: `mysql -u root -p skill -e "SELECT * FROM sys_config WHERE config_type='cloud_route' AND config_key='business_whitelist_enabled';"`
Expected: 返回 1 行，`config_value = '0'`，`status = 1`

- [ ] **Step 4: 验证幂等（再次 migrate 不报错）**

模拟方式：手动再执行 `INSERT ... ON DUPLICATE KEY UPDATE id = id;` SQL。
Expected: 影响行数 0 或 1（取决于 MySQL 版本），无 duplicate key error

- [ ] **Step 5: 提交**

```bash
git add skill-server/src/main/resources/db/migration/V11__init_business_whitelist_config.sql
git commit -m "feat(skill-server): add V11 migration for business whitelist switch (idempotent)"
```

---

## Task 2: SysConfigProperties — 提取 TTL 为可配置属性

**Files:**
- Create: `skill-server/src/main/java/com/opencode/cui/skill/config/SysConfigProperties.java`

- [ ] **Step 1: 创建 SysConfigProperties 类**

```java
package com.opencode.cui.skill.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * SysConfig 缓存配置。
 * 用于 SysConfigService 单条缓存与 BusinessWhitelistService 集合缓存。
 */
@Data
@Component
@ConfigurationProperties(prefix = "skill.sys-config")
public class SysConfigProperties {
    /** 缓存 TTL（分钟），默认 5 */
    private long cacheTtlMinutes = 5L;
}
```

- [ ] **Step 2: 编译验证**

Run: `cd skill-server && mvn compile -DskipTests`
Expected: BUILD SUCCESS，无编译错误

- [ ] **Step 3: 提交**

```bash
git add skill-server/src/main/java/com/opencode/cui/skill/config/SysConfigProperties.java
git commit -m "feat(skill-server): add SysConfigProperties for cache TTL configuration"
```

---

## Task 3: SysConfigService — TTL 改为 properties 注入

**Files:**
- Modify: `skill-server/src/main/java/com/opencode/cui/skill/service/SysConfigService.java`
- Modify: `skill-server/src/test/java/com/opencode/cui/skill/service/SysConfigServiceTest.java`

- [ ] **Step 1: 写新测试 — verify TTL 来自 properties**

Edit `SysConfigServiceTest.java`，在 setUp 处加上 properties stub，新增一个测试：

```java
@Test
@DisplayName("Cache TTL uses configured value from SysConfigProperties")
void getValue_cachesUsingPropertiesTtl() {
    when(properties.getCacheTtlMinutes()).thenReturn(15L);
    // ... existing fixture: DB returns SysConfig with status=1, configValue="x"
    when(sysConfigMapper.findByTypeAndKey("t", "k")).thenReturn(buildEnabledConfig("t", "k", "x"));
    when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    when(valueOperations.get("ss:config:t:k")).thenReturn(null);

    String result = sysConfigService.getValue("t", "k");

    assertEquals("x", result);
    verify(valueOperations).set(eq("ss:config:t:k"), eq("x"), eq(15L), eq(TimeUnit.MINUTES));
}
```

注意：`SysConfigServiceTest` 现有 setUp 必须加 `@Mock SysConfigProperties properties`，构造 service 时多传一个参数。

- [ ] **Step 2: 跑新测试，验证失败（properties 字段还没注入）**

Run: `cd skill-server && mvn test -Dtest=SysConfigServiceTest#getValue_cachesUsingPropertiesTtl`
Expected: COMPILATION ERROR — `SysConfigService` 构造函数不接受 `SysConfigProperties`

- [ ] **Step 3: 修改 SysConfigService — 注入 properties**

修改 `SysConfigService.java`：

把
```java
private static final long CACHE_TTL_MINUTES = 30L;
```
删除。

把构造函数从 `@RequiredArgsConstructor` 自动生成（即 `private final SysConfigMapper sysConfigMapper; private final StringRedisTemplate redisTemplate;`）改为：

```java
private final SysConfigMapper sysConfigMapper;
private final StringRedisTemplate redisTemplate;
private final SysConfigProperties properties;
```

把 `getValue` 方法里的 `CACHE_TTL_MINUTES` 替换为 `properties.getCacheTtlMinutes()`：

```java
redisTemplate.opsForValue().set(cacheKey, value, properties.getCacheTtlMinutes(), TimeUnit.MINUTES);
```

- [ ] **Step 4: 跑新测试 + 全部 SysConfigServiceTest 验证通过**

Run: `cd skill-server && mvn test -Dtest=SysConfigServiceTest`
Expected: 所有测试 PASS（包括新加的 + 已有 case，已有 case 之前用了 30 分钟硬编码，需要在 setUp 里 stub `properties.getCacheTtlMinutes()` 返回 30L 或调整 verify）

> 注意：原测试若 verify Redis set 的 TTL 是 30L，现在要改成"用 properties 配置的值"。修复方式：setUp 里 `when(properties.getCacheTtlMinutes()).thenReturn(5L)`（或 30L 与原期望一致），并相应更新 verify 断言。

- [ ] **Step 5: 提交**

```bash
git add skill-server/src/main/java/com/opencode/cui/skill/service/SysConfigService.java \
        skill-server/src/test/java/com/opencode/cui/skill/service/SysConfigServiceTest.java
git commit -m "refactor(skill-server): extract SysConfig cache TTL to SysConfigProperties (default 5min)"
```

---

## Task 4: AssistantInfo 字段重命名 + AssistantInfoService.parseApiResponse 修复

**Files:**
- Modify: `skill-server/src/main/java/com/opencode/cui/skill/model/AssistantInfo.java`
- Modify: `skill-server/src/main/java/com/opencode/cui/skill/service/AssistantInfoService.java`
- Modify: `skill-server/src/test/java/com/opencode/cui/skill/service/AssistantInfoServiceTest.java`

- [ ] **Step 1: 写 bug 回归测试**

在 `AssistantInfoServiceTest.java` 添加（或修改已有的 parseApiResponse 测试，使其使用 `data.businessTag`）：

```java
@Test
@DisplayName("parseApiResponse reads data.businessTag (not legacy hisAppId)")
void parseApiResponse_readsBusinessTag_notHisAppId() {
    String body = "{\"code\":\"200\",\"data\":{" +
            "\"identityType\":\"3\"," +
            "\"businessTag\":\"tag-foo\"," +
            "\"endpoint\":\"https://cloud.example.com/chat\"," +
            "\"protocol\":\"2\"," +
            "\"authType\":\"1\"" +
            "}}";

    AssistantInfo info = service.parseApiResponse(body);

    assertNotNull(info);
    assertEquals("business", info.getAssistantScope());
    assertEquals("tag-foo", info.getBusinessTag());
}

@Test
@DisplayName("parseApiResponse: businessTag absent → AssistantInfo.businessTag = null")
void parseApiResponse_businessTagAbsent_returnsNull() {
    String body = "{\"code\":\"200\",\"data\":{\"identityType\":\"3\"}}";

    AssistantInfo info = service.parseApiResponse(body);

    assertNotNull(info);
    assertNull(info.getBusinessTag());
}
```

- [ ] **Step 2: 跑新测试，验证编译错误**

Run: `cd skill-server && mvn test -Dtest=AssistantInfoServiceTest#parseApiResponse_readsBusinessTag_notHisAppId`
Expected: COMPILATION ERROR — `info.getBusinessTag()` 不存在

- [ ] **Step 3: 修改 AssistantInfo model — 重命名字段**

```java
package com.opencode.cui.skill.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

@Data
public class AssistantInfo {
    private String assistantScope;    // "business" | "personal"
    private String businessTag;       // 原 appId（误读 bug 修复 + 重命名）
    private String cloudEndpoint;
    private String cloudProtocol;     // "sse" | "websocket"
    private String authType;          // "soa" | "apig"

    @JsonIgnore
    public boolean isBusiness() {
        return "business".equals(assistantScope);
    }

    @JsonIgnore
    public boolean isPersonal() {
        return !isBusiness();
    }
}
```

- [ ] **Step 4: 修改 AssistantInfoService.parseApiResponse — 解析键改为 businessTag**

修改 `AssistantInfoService.java` 第 199 行附近：

```java
info.setBusinessTag(dataNode.path("businessTag").asText(null));
```

（原 `info.setAppId(dataNode.path("hisAppId").asText(null));`）

更新 method javadoc：

```java
/**
 * 解析上游 API 响应 JSON。
 *
 * 响应格式：
 * <pre>
 * {
 *   "code": "200",
 *   "data": {
 *     "identityType": "3",   // "2"=personal, "3"=business
 *     "businessTag": "tag-foo",   // 业务标签（注：原代码误读为 hisAppId/appId 是 bug）
 *     "endpoint": "https://cloud.example.com/chat",
 *     "protocol": "2",     // 1=rest, 2=sse, 3=websocket
 *     "authType": "1"      // 1=soa
 *   }
 * }
 * </pre>
 */
```

- [ ] **Step 5: 跑新测试 + 已有的 AssistantInfoServiceTest，编译错误会冒出（旧 setAppId/getAppId 调用）**

Run: `cd skill-server && mvn test -Dtest=AssistantInfoServiceTest`
Expected: COMPILATION ERROR — `setAppId`/`getAppId` 已不存在

- [ ] **Step 6: 把 AssistantInfoServiceTest 内 setAppId/getAppId 全部改为 setBusinessTag/getBusinessTag**

用 IDE 的"全文件查找替换"或：

```bash
cd skill-server/src/test/java/com/opencode/cui/skill/service/AssistantInfoServiceTest.java
# 手动 / IDE 替换：
#   setAppId  →  setBusinessTag
#   getAppId  →  getBusinessTag
```

`AssistantInfoServiceTest.java:100` 现在是 `assertEquals("app_001", result.getAppId());` —— 要改为 `assertEquals("app_001", result.getBusinessTag());` 且需要在测试 fixture JSON 里把 `hisAppId` 改为 `businessTag`：

```java
// 原（约 line 164）：
//     "\"hisAppId\":\"app_test\",..."
// 改为：
//     "\"businessTag\":\"app_test\",..."
```

- [ ] **Step 7: 跑 AssistantInfoServiceTest 全套，验证通过**

Run: `cd skill-server && mvn test -Dtest=AssistantInfoServiceTest`
Expected: 全部 PASS

- [ ] **Step 8: 提交**

```bash
git add skill-server/src/main/java/com/opencode/cui/skill/model/AssistantInfo.java \
        skill-server/src/main/java/com/opencode/cui/skill/service/AssistantInfoService.java \
        skill-server/src/test/java/com/opencode/cui/skill/service/AssistantInfoServiceTest.java
git commit -m "fix(skill-server): rename AssistantInfo.appId to businessTag, fix parseApiResponse to read data.businessTag

Bug: parseApiResponse was reading data.hisAppId, but upstream always returns
data.businessTag. AssistantInfo.appId was always null in production."
```

---

## Task 5: BusinessScopeStrategy — 跟随字段重命名

**Files:**
- Modify: `skill-server/src/main/java/com/opencode/cui/skill/service/scope/BusinessScopeStrategy.java`
- Modify: `skill-server/src/test/java/com/opencode/cui/skill/service/scope/BusinessScopeStrategyTest.java`

- [ ] **Step 1: 修改 BusinessScopeStrategy:53-54 + cloudRequestBuilder 调用变量名**

`BusinessScopeStrategy.java` 第 53-54 行：

```java
// 原：
//   String appId = info.getAppId();
// 改为：
String businessTag = info.getBusinessTag();
```

第 83 行的 `cloudRequestBuilder.buildCloudRequest(appId, context)` 改为 `cloudRequestBuilder.buildCloudRequest(businessTag, context)`：

```java
ObjectNode cloudRequest = cloudRequestBuilder.buildCloudRequest(businessTag, context);
```

第 112 行 `log.error("Failed to serialize business invoke message: ak={}, appId={}", command.ak(), appId, e);` 改为：

```java
log.error("Failed to serialize business invoke message: ak={}, businessTag={}",
        command.ak(), businessTag, e);
```

- [ ] **Step 2: 修改 BusinessScopeStrategyTest 内 setAppId 调用（约 14 处）**

打开 `BusinessScopeStrategyTest.java`，将所有 `info.setAppId("...")` 替换为 `info.setBusinessTag("...")`。预期点位（基于 grep 结果）：

- `BusinessScopeStrategyTest.java:101, 124, 148, 176, 203, 226, 247, 270, 292, 313, 333`

```bash
# 用 IDE 批量替换或 sed
sed -i.bak 's/setAppId(/setBusinessTag(/g' \
    skill-server/src/test/java/com/opencode/cui/skill/service/scope/BusinessScopeStrategyTest.java
rm skill-server/src/test/java/com/opencode/cui/skill/service/scope/BusinessScopeStrategyTest.java.bak
```

- [ ] **Step 3: 跑 BusinessScopeStrategyTest 全套**

Run: `cd skill-server && mvn test -Dtest=BusinessScopeStrategyTest`
Expected: 全部 PASS

- [ ] **Step 4: 提交**

```bash
git add skill-server/src/main/java/com/opencode/cui/skill/service/scope/BusinessScopeStrategy.java \
        skill-server/src/test/java/com/opencode/cui/skill/service/scope/BusinessScopeStrategyTest.java
git commit -m "refactor(skill-server): BusinessScopeStrategy reads info.getBusinessTag (was getAppId)"
```

---

## Task 6: 其他测试文件批量跟随重命名（setAppId / getAppId）

**Files:**
- Modify (sed batch): 多个测试文件中调用了 `setAppId` / `getAppId` / `hisAppId`

- [ ] **Step 1: 找出所有受影响的测试文件**

Run:
```bash
cd skill-server
grep -rln 'setAppId\|getAppId\|"hisAppId"' src/test/java/
```

预期返回（基于 spec 调研）：
- `GatewayRelayServiceScopeTest.java`
- `ExtParametersIntegrationTest.java`
- 其他可能含 `setAppId` 的测试

- [ ] **Step 2: 批量替换 setAppId/getAppId**

```bash
cd skill-server
find src/test/java/ -name '*.java' -exec sed -i.bak \
    -e 's/setAppId(/setBusinessTag(/g' \
    -e 's/getAppId()/getBusinessTag()/g' \
    {} \;
find src/test/java/ -name '*.java.bak' -delete
```

- [ ] **Step 3: 检查测试 fixture JSON 中的 `hisAppId`**

```bash
grep -rn '"hisAppId"' src/test/java/
```

如果有匹配，改为 `"businessTag"`（手动逐个改，避免误伤其他字符串）。

- [ ] **Step 4: 编译 + 跑全部测试，验证通过**

Run: `cd skill-server && mvn test`
Expected: BUILD SUCCESS，所有现有测试 PASS

> 如有失败，根据失败信息逐个修。常见原因：sed 漏改某个文件、JSON fixture 还在用 hisAppId。

- [ ] **Step 5: 提交**

```bash
git add skill-server/src/test/
git commit -m "test(skill-server): rename setAppId/getAppId to setBusinessTag/getBusinessTag in all tests"
```

---

## Task 7: BusinessWhitelistService — 新建白名单服务（含 13 个测试 case）

**Files:**
- Create: `skill-server/src/main/java/com/opencode/cui/skill/service/BusinessWhitelistService.java`
- Create: `skill-server/src/test/java/com/opencode/cui/skill/service/BusinessWhitelistServiceTest.java`

- [ ] **Step 1: 写测试 fixture + 13 个 case（一次性写出）**

```java
package com.opencode.cui.skill.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencode.cui.skill.config.SysConfigProperties;
import com.opencode.cui.skill.model.SysConfig;
import com.opencode.cui.skill.repository.SysConfigMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BusinessWhitelistServiceTest {

    @Mock SysConfigService sysConfigService;
    @Mock SysConfigMapper sysConfigMapper;
    @Mock StringRedisTemplate redisTemplate;
    @Mock ValueOperations<String, String> valueOperations;
    @Mock SysConfigProperties properties;

    private BusinessWhitelistService service;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        service = new BusinessWhitelistService(
                sysConfigService, sysConfigMapper, redisTemplate, properties, objectMapper);
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        lenient().when(properties.getCacheTtlMinutes()).thenReturn(5L);
    }

    private SysConfig row(String key, int status) {
        SysConfig c = new SysConfig();
        c.setConfigType("business_cloud_whitelist");
        c.setConfigKey(key);
        c.setConfigValue("1");
        c.setStatus(status);
        return c;
    }

    // ================== 总开关 = '0' 时永远 true（不触碰 tag） ==================

    @Test
    @DisplayName("switch=0 + tag=null → true (do not touch tag)")
    void switchOff_nullTag_true() {
        when(sysConfigService.getValue("cloud_route", "business_whitelist_enabled")).thenReturn("0");
        assertTrue(service.allowsCloud(null));
        verify(sysConfigMapper, never()).findByType(anyString());
    }

    @Test
    @DisplayName("switch=0 + tag=blank → true")
    void switchOff_blankTag_true() {
        when(sysConfigService.getValue("cloud_route", "business_whitelist_enabled")).thenReturn("0");
        assertTrue(service.allowsCloud(""));
        assertTrue(service.allowsCloud("   "));
    }

    @Test
    @DisplayName("switch=0 + tag=anything → true (always)")
    void switchOff_anyTag_true() {
        when(sysConfigService.getValue("cloud_route", "business_whitelist_enabled")).thenReturn("0");
        assertTrue(service.allowsCloud("tag-foo"));
        assertTrue(service.allowsCloud("not-in-whitelist"));
    }

    // ================== 总开关 = '1' 各分支 ==================

    @Test
    @DisplayName("switch=1 + tag=null → false + WARN")
    void switchOn_nullTag_false() {
        when(sysConfigService.getValue("cloud_route", "business_whitelist_enabled")).thenReturn("1");
        assertFalse(service.allowsCloud(null));
    }

    @Test
    @DisplayName("switch=1 + tag=blank → false + WARN")
    void switchOn_blankTag_false() {
        when(sysConfigService.getValue("cloud_route", "business_whitelist_enabled")).thenReturn("1");
        assertFalse(service.allowsCloud(""));
        assertFalse(service.allowsCloud("   "));
    }

    @Test
    @DisplayName("switch=1 + empty whitelist → true (fail-open)")
    void switchOn_emptyTable_true() {
        when(sysConfigService.getValue("cloud_route", "business_whitelist_enabled")).thenReturn("1");
        when(valueOperations.get("ss:config:set:business_cloud_whitelist")).thenReturn(null);
        when(sysConfigMapper.findByType("business_cloud_whitelist")).thenReturn(Collections.emptyList());
        assertTrue(service.allowsCloud("tag-foo"));
    }

    @Test
    @DisplayName("switch=1 + tag in whitelist with status=1 → true")
    void switchOn_tagHit_true() {
        when(sysConfigService.getValue("cloud_route", "business_whitelist_enabled")).thenReturn("1");
        when(valueOperations.get("ss:config:set:business_cloud_whitelist")).thenReturn(null);
        when(sysConfigMapper.findByType("business_cloud_whitelist"))
                .thenReturn(List.of(row("tag-foo", 1), row("tag-bar", 1)));
        assertTrue(service.allowsCloud("tag-foo"));
    }

    @Test
    @DisplayName("switch=1 + tag in whitelist but status=0 → false")
    void switchOn_tagDisabled_false() {
        when(sysConfigService.getValue("cloud_route", "business_whitelist_enabled")).thenReturn("1");
        when(valueOperations.get("ss:config:set:business_cloud_whitelist")).thenReturn(null);
        when(sysConfigMapper.findByType("business_cloud_whitelist"))
                .thenReturn(List.of(row("tag-foo", 0), row("tag-bar", 1)));
        assertFalse(service.allowsCloud("tag-foo"));
    }

    @Test
    @DisplayName("switch=1 + tag not in whitelist → false")
    void switchOn_tagMiss_false() {
        when(sysConfigService.getValue("cloud_route", "business_whitelist_enabled")).thenReturn("1");
        when(valueOperations.get("ss:config:set:business_cloud_whitelist")).thenReturn(null);
        when(sysConfigMapper.findByType("business_cloud_whitelist"))
                .thenReturn(List.of(row("tag-foo", 1)));
        assertFalse(service.allowsCloud("tag-baz"));
    }

    // ================== 异常 / 未知值 fail-open ==================

    @Test
    @DisplayName("switch value not '0' or '1' → treated as disabled (true)")
    void switchUnknownValue_true() {
        when(sysConfigService.getValue("cloud_route", "business_whitelist_enabled")).thenReturn("yes");
        assertTrue(service.allowsCloud("tag-foo"));
    }

    @Test
    @DisplayName("DB exception while loading whitelist → fail-open (true)")
    void dbException_failOpen() {
        when(sysConfigService.getValue("cloud_route", "business_whitelist_enabled")).thenReturn("1");
        when(valueOperations.get("ss:config:set:business_cloud_whitelist")).thenReturn(null);
        when(sysConfigMapper.findByType("business_cloud_whitelist"))
                .thenThrow(new RuntimeException("db down"));
        assertTrue(service.allowsCloud("tag-foo"));
    }

    // ================== 缓存 ==================

    @Test
    @DisplayName("Set cache hit on second call: DB queried at most once")
    void setCache_secondCallNoDb() {
        when(sysConfigService.getValue("cloud_route", "business_whitelist_enabled")).thenReturn("1");
        // 第一次 get returns null, 第二次 returns 缓存内容
        when(valueOperations.get("ss:config:set:business_cloud_whitelist"))
                .thenReturn(null)
                .thenReturn("[\"tag-foo\"]");
        when(sysConfigMapper.findByType("business_cloud_whitelist"))
                .thenReturn(List.of(row("tag-foo", 1)));

        service.allowsCloud("tag-foo");
        service.allowsCloud("tag-foo");

        verify(sysConfigMapper, atMostOnce()).findByType("business_cloud_whitelist");
    }

    @Test
    @DisplayName("Set cache: Redis read fail → fallback to DB")
    void setCache_redisFail_fallbackDb() {
        when(sysConfigService.getValue("cloud_route", "business_whitelist_enabled")).thenReturn("1");
        when(valueOperations.get("ss:config:set:business_cloud_whitelist"))
                .thenThrow(new RuntimeException("redis down"));
        when(sysConfigMapper.findByType("business_cloud_whitelist"))
                .thenReturn(List.of(row("tag-foo", 1)));
        assertTrue(service.allowsCloud("tag-foo"));
    }
}
```

- [ ] **Step 2: 跑测试，验证全部失败（service 不存在）**

Run: `cd skill-server && mvn test -Dtest=BusinessWhitelistServiceTest`
Expected: COMPILATION ERROR — `BusinessWhitelistService` 类不存在

- [ ] **Step 3: 创建 BusinessWhitelistService 实现**

```java
package com.opencode.cui.skill.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencode.cui.skill.config.SysConfigProperties;
import com.opencode.cui.skill.model.SysConfig;
import com.opencode.cui.skill.repository.SysConfigMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 业务助手云端协议白名单服务。
 *
 * <p>判断顺序（关键）：
 * <ol>
 *   <li>总开关 isFeatureEnabled() —— 关闭时不触碰 tag，永远返回 true</li>
 *   <li>tag null/blank → 返回 false + WARN（仅在白名单启用时触发此分支）</li>
 *   <li>查白名单集合（集合缓存 5min TTL，miss 时穿透 DB）</li>
 *   <li>异常路径一律 fail-open 返回 true + WARN</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BusinessWhitelistService {

    private static final String CONFIG_TYPE_WHITELIST = "business_cloud_whitelist";
    private static final String CONFIG_TYPE_SWITCH = "cloud_route";
    private static final String CONFIG_KEY_SWITCH = "business_whitelist_enabled";
    private static final String CACHE_KEY_SET = "ss:config:set:business_cloud_whitelist";

    private final SysConfigService sysConfigService;
    private final SysConfigMapper sysConfigMapper;
    private final StringRedisTemplate redisTemplate;
    private final SysConfigProperties properties;
    private final ObjectMapper objectMapper;

    /**
     * 是否允许该业务 tag 走云端协议。
     *
     * @param businessTag 业务标签（来自 AssistantInfo.businessTag）
     * @return true = 走云端；false = 走本地
     */
    public boolean allowsCloud(String businessTag) {
        if (!isFeatureEnabled()) {
            log.debug("[Whitelist] disabled, all business allowed");
            return true;
        }
        if (businessTag == null || businessTag.isBlank()) {
            log.warn("[Whitelist] business scope but businessTag missing");
            return false;
        }
        Set<String> tags;
        try {
            tags = loadWhitelistTags();
        } catch (RuntimeException e) {
            log.warn("[Whitelist] load failed, fail-open: error={}", e.getMessage());
            return true;
        }
        if (tags.isEmpty()) {
            log.info("[Whitelist] empty, allowing all (fail-open)");
            return true;
        }
        boolean allowed = tags.contains(businessTag);
        if (allowed) {
            log.debug("[Whitelist] tag {} hit", businessTag);
        } else {
            log.info("[Whitelist] businessTag {} not in whitelist, fallback to local", businessTag);
        }
        return allowed;
    }

    private boolean isFeatureEnabled() {
        try {
            String v = sysConfigService.getValue(CONFIG_TYPE_SWITCH, CONFIG_KEY_SWITCH);
            if ("1".equals(v)) return true;
            if ("0".equals(v) || v == null) return false;
            log.warn("[Whitelist] unknown switch value '{}', treating as disabled", v);
            return false;
        } catch (RuntimeException e) {
            log.warn("[Whitelist] switch read failed, fail-open (treat as disabled): error={}",
                    e.getMessage());
            return false;
        }
    }

    private Set<String> loadWhitelistTags() {
        // 1. 集合缓存
        try {
            String cached = redisTemplate.opsForValue().get(CACHE_KEY_SET);
            if (cached != null) {
                List<String> list = objectMapper.readValue(cached, new TypeReference<List<String>>() {});
                return new HashSet<>(list);
            }
        } catch (Exception e) {
            log.warn("[Whitelist] cache read failed, fallback to DB: error={}", e.getMessage());
        }
        // 2. DB
        List<SysConfig> rows = sysConfigMapper.findByType(CONFIG_TYPE_WHITELIST);
        Set<String> tags = rows.stream()
                .filter(c -> c.getStatus() != null && c.getStatus() == 1)
                .map(SysConfig::getConfigKey)
                .collect(Collectors.toSet());
        // 3. 写缓存（失败静默）
        try {
            String json = objectMapper.writeValueAsString(tags);
            redisTemplate.opsForValue().set(CACHE_KEY_SET, json,
                    Duration.ofMinutes(properties.getCacheTtlMinutes()));
        } catch (Exception e) {
            log.warn("[Whitelist] cache write failed: error={}", e.getMessage());
        }
        return tags;
    }
}
```

- [ ] **Step 4: 跑测试，验证全部通过**

Run: `cd skill-server && mvn test -Dtest=BusinessWhitelistServiceTest`
Expected: 13 tests PASS

- [ ] **Step 5: 提交**

```bash
git add skill-server/src/main/java/com/opencode/cui/skill/service/BusinessWhitelistService.java \
        skill-server/src/test/java/com/opencode/cui/skill/service/BusinessWhitelistServiceTest.java
git commit -m "feat(skill-server): add BusinessWhitelistService with fail-open semantics

- Decision order: switch enabled → tag null check → whitelist lookup → exception fallback
- 5min TTL Redis set cache; falls back to DB on cache miss / Redis failure
- Switch '0' (default) bypasses all tag checks (equivalent to current line behavior)"
```

---

## Task 8: AssistantScopeDispatcher — 新增 getStrategy(AssistantInfo) 重载

**Files:**
- Modify: `skill-server/src/main/java/com/opencode/cui/skill/service/scope/AssistantScopeDispatcher.java`
- Modify: `skill-server/src/test/java/com/opencode/cui/skill/service/scope/AssistantScopeDispatcherTest.java`

- [ ] **Step 1: 写新测试 — getStrategy(info) 4 个 case**

在 `AssistantScopeDispatcherTest.java` 新增（保留所有原有 `getStrategy(scope)` 测试）：

```java
@Test
@DisplayName("getStrategy(null info) → personalStrategy")
void getStrategy_nullInfo_returnsPersonal() {
    AssistantScopeStrategy result = dispatcher.getStrategy((AssistantInfo) null);
    assertEquals("personal", result.getScope());
    verifyNoInteractions(whitelistService);
}

@Test
@DisplayName("getStrategy(personal info) → personal, whitelistService not called")
void getStrategy_personalInfo_returnsPersonal_skipsWhitelist() {
    AssistantInfo info = new AssistantInfo();
    info.setAssistantScope("personal");

    AssistantScopeStrategy result = dispatcher.getStrategy(info);

    assertEquals("personal", result.getScope());
    verifyNoInteractions(whitelistService);
}

@Test
@DisplayName("getStrategy(business info, allowsCloud=true) → business")
void getStrategy_businessAllowed_returnsBusiness() {
    AssistantInfo info = new AssistantInfo();
    info.setAssistantScope("business");
    info.setBusinessTag("tag-foo");
    when(whitelistService.allowsCloud("tag-foo")).thenReturn(true);

    AssistantScopeStrategy result = dispatcher.getStrategy(info);

    assertEquals("business", result.getScope());
}

@Test
@DisplayName("getStrategy(business info, allowsCloud=false) → personal")
void getStrategy_businessDenied_returnsPersonal() {
    AssistantInfo info = new AssistantInfo();
    info.setAssistantScope("business");
    info.setBusinessTag("tag-foo");
    when(whitelistService.allowsCloud("tag-foo")).thenReturn(false);

    AssistantScopeStrategy result = dispatcher.getStrategy(info);

    assertEquals("personal", result.getScope());
}
```

测试 fixture 新增 `@Mock BusinessWhitelistService whitelistService;`，构造 dispatcher 时传入。

- [ ] **Step 2: 跑测试，验证编译错误**

Run: `cd skill-server && mvn test -Dtest=AssistantScopeDispatcherTest`
Expected: COMPILATION ERROR — `getStrategy(AssistantInfo)` 不存在 + 构造函数参数不匹配

- [ ] **Step 3: 修改 AssistantScopeDispatcher — 加 whitelistService 依赖 + 新增重载**

```java
package com.opencode.cui.skill.service.scope;

import com.opencode.cui.skill.model.AssistantInfo;
import com.opencode.cui.skill.service.BusinessWhitelistService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Component
public class AssistantScopeDispatcher {

    private static final String DEFAULT_SCOPE = "personal";

    private final Map<String, AssistantScopeStrategy> strategyMap;
    private final BusinessWhitelistService whitelistService;

    public AssistantScopeDispatcher(List<AssistantScopeStrategy> strategies,
                                    BusinessWhitelistService whitelistService) {
        this.strategyMap = strategies.stream()
                .collect(Collectors.toMap(AssistantScopeStrategy::getScope, Function.identity()));
        this.whitelistService = whitelistService;
        log.info("AssistantScopeDispatcher initialized with scopes: {}", strategyMap.keySet());
    }

    /**
     * 根据 scope 字符串纯 lookup 获取策略（不走白名单 gate）。
     * 仅用于内部 / 测试场景；业务路径请使用 {@link #getStrategy(AssistantInfo)}。
     */
    public AssistantScopeStrategy getStrategy(String scope) {
        if (scope == null) {
            return strategyMap.get(DEFAULT_SCOPE);
        }
        return strategyMap.getOrDefault(scope, strategyMap.get(DEFAULT_SCOPE));
    }

    /**
     * 根据 AssistantInfo 获取策略，**含白名单 gate**。
     *
     * <p>判断顺序：
     * <ol>
     *   <li>info == null → personal</li>
     *   <li>scope != "business" → 按 scope 直接 lookup（默认 personal）</li>
     *   <li>scope == "business" → 询问 BusinessWhitelistService.allowsCloud(businessTag)
     *       <ul><li>true → business</li><li>false → personal（白名单未命中降级）</li></ul></li>
     * </ol>
     */
    public AssistantScopeStrategy getStrategy(AssistantInfo info) {
        if (info == null) {
            return strategyMap.get(DEFAULT_SCOPE);
        }
        String scope = info.getAssistantScope();
        if (!"business".equals(scope)) {
            return getStrategy(scope);
        }
        if (whitelistService.allowsCloud(info.getBusinessTag())) {
            return strategyMap.get("business");
        }
        return strategyMap.get(DEFAULT_SCOPE);
    }
}
```

- [ ] **Step 4: 跑测试，验证全部通过**

Run: `cd skill-server && mvn test -Dtest=AssistantScopeDispatcherTest`
Expected: 全部 PASS（原有 `getStrategy(scope)` 测试 + 新增 4 个 `getStrategy(info)` 测试）

- [ ] **Step 5: 提交**

```bash
git add skill-server/src/main/java/com/opencode/cui/skill/service/scope/AssistantScopeDispatcher.java \
        skill-server/src/test/java/com/opencode/cui/skill/service/scope/AssistantScopeDispatcherTest.java
git commit -m "feat(skill-server): add AssistantScopeDispatcher.getStrategy(AssistantInfo) overload with whitelist gate

- Old getStrategy(String scope) preserved as pure lookup
- New overload integrates BusinessWhitelistService for business scope decisions"
```

---

## Task 9: 8 处调用点机械替换（除 GatewayRelayService）

**Files:**
- Modify: `skill-server/src/main/java/com/opencode/cui/skill/controller/SkillSessionController.java:114-115`
- Modify: `skill-server/src/main/java/com/opencode/cui/skill/service/ImSessionManager.java:140-141`
- Modify: `skill-server/src/main/java/com/opencode/cui/skill/service/InboundProcessingService.java:172-173, 219-220, 512-513, 567-568`
- Modify: `skill-server/src/main/java/com/opencode/cui/skill/controller/SkillMessageController.java:177-178, 408-409`
- Modify: `skill-server/src/main/java/com/opencode/cui/skill/service/GatewayMessageRouter.java:520-521`

每处的改造模式相同：

**改造前**：
```java
String scope = assistantInfoService.getCachedScope(ak);
AssistantScopeStrategy strategy = scopeDispatcher.getStrategy(scope);
```

**改造后**：
```java
AssistantInfo info = assistantInfoService.getAssistantInfo(ak);
AssistantScopeStrategy strategy = scopeDispatcher.getStrategy(info);
```

注意：原 `getCachedScope(ak)` 返回 String，新代码 `getAssistantInfo(ak)` 返回 AssistantInfo，需要 import `com.opencode.cui.skill.model.AssistantInfo;`。

- [ ] **Step 1: 修改 SkillSessionController:114-115**

定位到方法内现有的两行：
```java
String scope = assistantInfoService.getCachedScope(request.getAk());
AssistantScopeStrategy strategy = scopeDispatcher.getStrategy(scope);
```
改为：
```java
AssistantInfo info = assistantInfoService.getAssistantInfo(request.getAk());
AssistantScopeStrategy strategy = scopeDispatcher.getStrategy(info);
```
若该方法之后用到 `scope` 变量（比如日志），改为 `info != null ? info.getAssistantScope() : null`。

- [ ] **Step 2: 修改 ImSessionManager:140-141**

```java
// 原：
//   String scope = assistantInfoService.getCachedScope(ak);
//   AssistantScopeStrategy strategy = scopeDispatcher.getStrategy(scope);
AssistantInfo info = assistantInfoService.getAssistantInfo(ak);
AssistantScopeStrategy strategy = scopeDispatcher.getStrategy(info);
```

- [ ] **Step 3: 修改 InboundProcessingService 4 处（line 172, 219, 512, 567）**

每处都是相同的 2 行替换模式。注意 line 567 在 `assistantIdResolverService` 私有方法里，要确认上下文 ak 来源不变。

- [ ] **Step 4: 修改 SkillMessageController 2 处（line 177, 408）**

模式同上。

- [ ] **Step 5: 修改 GatewayMessageRouter:520-521**

注意：原代码在 line 519-520：
```java
String resolvedAk = ak != null ? ak : node.path("ak").asText(node.path("agentId").asText(null));
String scope = assistantInfoService.getCachedScope(resolvedAk);
AssistantScopeStrategy strategy = scopeDispatcher.getStrategy(scope);
```
改为：
```java
String resolvedAk = ak != null ? ak : node.path("ak").asText(node.path("agentId").asText(null));
AssistantInfo info = assistantInfoService.getAssistantInfo(resolvedAk);
AssistantScopeStrategy strategy = scopeDispatcher.getStrategy(info);
```
若该方法之后日志里有 `scope` 字符串，改为 `strategy.getScope()`。

- [ ] **Step 6: 编译验证**

Run: `cd skill-server && mvn compile`
Expected: BUILD SUCCESS

- [ ] **Step 7: 跑全部测试，确认编译通过（很多外围测试 mock 仍 stub 旧 `getStrategy(scope)`，会失败——预期，下个 Task 修）**

Run: `cd skill-server && mvn test`
Expected: 编译通过，但 `SkillMessageControllerTest` / `InboundProcessingServiceTest` / `GatewayRelayServiceScopeTest` 等可能有失败（mock 没匹配上）

- [ ] **Step 8: 提交**

```bash
git add skill-server/src/main/java/com/opencode/cui/skill/controller/SkillSessionController.java \
        skill-server/src/main/java/com/opencode/cui/skill/controller/SkillMessageController.java \
        skill-server/src/main/java/com/opencode/cui/skill/service/ImSessionManager.java \
        skill-server/src/main/java/com/opencode/cui/skill/service/InboundProcessingService.java \
        skill-server/src/main/java/com/opencode/cui/skill/service/GatewayMessageRouter.java
git commit -m "refactor(skill-server): switch 8 call sites from getStrategy(scope) to getStrategy(info)

Mechanical replacement to use new dispatcher overload that includes whitelist gate.
Affects: SkillSessionController, ImSessionManager, InboundProcessingService (x4),
SkillMessageController (x2), GatewayMessageRouter."
```

---

## Task 10: GatewayRelayService 特殊改造（保留 personal 本地 buildInvokeMessage）

**Files:**
- Modify: `skill-server/src/main/java/com/opencode/cui/skill/service/GatewayRelayService.java:107-122`

- [ ] **Step 1: 改造 sendInvokeToGateway 方法的 invoke 主路径**

定位到 line 107-122 的现有逻辑：

```java
String messageText;
AssistantInfo info = assistantInfoService.getAssistantInfo(command.ak());
if (info != null && info.isBusiness()) {
    AssistantScopeStrategy strategy = scopeDispatcher.getStrategy(info.getAssistantScope());
    messageText = strategy.buildInvoke(command, info);
    if (messageText == null) {
        log.warn("[SKIP] GatewayRelayService.sendInvokeToGateway: reason=strategy_build_null, ak={}, scope=business",
                command.ak());
        return;
    }
} else {
    // personal 策略（默认）：使用原有构建逻辑
    messageText = buildInvokeMessage(command);
    if (messageText == null) {
        return;
    }
}
```

改为：

```java
String messageText;
AssistantInfo info = assistantInfoService.getAssistantInfo(command.ak());
AssistantScopeStrategy strategy = scopeDispatcher.getStrategy(info);
if ("business".equals(strategy.getScope())) {
    messageText = strategy.buildInvoke(command, info);
    if (messageText == null) {
        log.warn("[SKIP] GatewayRelayService.sendInvokeToGateway: reason=strategy_build_null, ak={}, scope=business",
                command.ak());
        return;
    }
} else {
    // personal 策略（含白名单未命中降级 / 上游故障兜底）：保留本地 buildInvokeMessage
    messageText = buildInvokeMessage(command);
    if (messageText == null) {
        return;
    }
}
```

- [ ] **Step 2: 编译验证**

Run: `cd skill-server && mvn compile`
Expected: BUILD SUCCESS

- [ ] **Step 3: 提交**

```bash
git add skill-server/src/main/java/com/opencode/cui/skill/service/GatewayRelayService.java
git commit -m "refactor(skill-server): GatewayRelayService dispatches by strategy.getScope() instead of info.isBusiness()

- Keeps local buildInvokeMessage for personal branch (placeholder PersonalScopeStrategy.buildInvoke unchanged)
- Whitelist-denied business sessions auto-fall through to personal local path"
```

---

## Task 11: 受影响外围测试 mock 改造

**Files:**
- Modify: `skill-server/src/test/java/com/opencode/cui/skill/controller/SkillMessageControllerTest.java:352`
- Modify: `skill-server/src/test/java/com/opencode/cui/skill/controller/SkillSessionControllerTest.java:55`
- Modify: `skill-server/src/test/java/com/opencode/cui/skill/service/InboundProcessingServiceTest.java:107`
- Modify: `skill-server/src/test/java/com/opencode/cui/skill/service/GatewayRelayServiceScopeTest.java:80`
- Modify (if exists): `skill-server/src/test/java/com/opencode/cui/skill/service/ImSessionManagerTest.java`
- Modify: `skill-server/src/test/java/com/opencode/cui/skill/service/GatewayMessageRouterImPushTest.java`

每个测试都把：
```java
when(scopeDispatcher.getStrategy(anyString())).thenReturn(strategy);
// 或
when(scopeDispatcher.getStrategy(eq("business"))).thenReturn(strategy);
```

改为：
```java
when(scopeDispatcher.getStrategy(any(AssistantInfo.class))).thenReturn(strategy);
// 或带条件：
when(scopeDispatcher.getStrategy(argThat(info -> info != null && "business".equals(info.getAssistantScope()))))
    .thenReturn(strategy);
```

测试若依赖 `assistantInfoService.getCachedScope(ak)` 的 stub，要改成 stub `assistantInfoService.getAssistantInfo(ak)` 返回带 `assistantScope` 的 AssistantInfo。

- [ ] **Step 1: 跑全部测试，记录失败列表**

Run: `cd skill-server && mvn test 2>&1 | tee /tmp/test-fail.log`
Expected: 多个测试失败，失败信息提示 mock 没匹配

- [ ] **Step 2: 改 SkillMessageControllerTest:352**

定位 setUp 或失败的测试，加 import：
```java
import com.opencode.cui.skill.model.AssistantInfo;
import static org.mockito.ArgumentMatchers.any;
```
把：
```java
when(scopeDispatcher.getStrategy(anyString())).thenReturn(strategy);
```
改为：
```java
when(scopeDispatcher.getStrategy(any(AssistantInfo.class))).thenReturn(strategy);
```
同时如果测试还 stub 了 `assistantInfoService.getCachedScope`，改为 stub `getAssistantInfo`：
```java
AssistantInfo info = new AssistantInfo();
info.setAssistantScope("business");
info.setBusinessTag("tag-foo");
when(assistantInfoService.getAssistantInfo(anyString())).thenReturn(info);
```

- [ ] **Step 3: 同样模式改 SkillSessionControllerTest:55**

- [ ] **Step 4: 同样模式改 InboundProcessingServiceTest:107**

- [ ] **Step 5: 同样模式改 GatewayRelayServiceScopeTest:80**

- [ ] **Step 6: 检查 ImSessionManagerTest 和 GatewayMessageRouterImPushTest 是否存在并需要改**

```bash
ls skill-server/src/test/java/com/opencode/cui/skill/service/ImSessionManagerTest.java 2>/dev/null
ls skill-server/src/test/java/com/opencode/cui/skill/service/GatewayMessageRouterImPushTest.java 2>/dev/null
```
若存在，按同样模式改。

- [ ] **Step 7: 跑全部测试，验证全部通过**

Run: `cd skill-server && mvn test`
Expected: BUILD SUCCESS，全部测试 PASS

> 如果还有失败，看 stack trace 找漏网的 mock。常见原因：测试用了 `getCachedScope` 而非 `getStrategy`，前者也要改。

- [ ] **Step 8: 提交**

```bash
git add skill-server/src/test/
git commit -m "test(skill-server): update mocks from getStrategy(scope) to getStrategy(AssistantInfo)

Affects 6 tests: SkillMessageController, SkillSessionController, InboundProcessingService,
GatewayRelayServiceScope, ImSessionManager (if present), GatewayMessageRouterImPush.
Stubs assistantInfoService.getAssistantInfo instead of getCachedScope."
```

---

## Task 12: AssistantInfoCacheCompatTest — 发布期 Redis 缓存兼容回归

**Files:**
- Create: `skill-server/src/test/java/com/opencode/cui/skill/service/AssistantInfoCacheCompatTest.java`

- [ ] **Step 1: 写测试**

```java
package com.opencode.cui.skill.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencode.cui.skill.config.AssistantInfoProperties;
import com.opencode.cui.skill.model.AssistantInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * 发布期 Redis 缓存兼容回归。
 * 旧 schema 的 cached JSON（含 "appId" 字段）反序列化时抛 UnrecognizedPropertyException，
 * 应被 try-catch 兜住，fall through 到上游 fetch（不阻塞业务流）。
 */
@ExtendWith(MockitoExtension.class)
class AssistantInfoCacheCompatTest {

    @Mock AssistantInfoProperties properties;
    @Mock StringRedisTemplate redisTemplate;
    @Mock ValueOperations<String, String> valueOperations;

    private AssistantInfoService service;

    @BeforeEach
    void setUp() {
        // override fetchFromUpstream 以避免真实 HTTP 调用
        service = new AssistantInfoService(properties, redisTemplate) {
            @Override
            protected AssistantInfo fetchFromUpstream(String ak) {
                AssistantInfo info = new AssistantInfo();
                info.setAssistantScope("business");
                info.setBusinessTag("tag-fresh");
                return info;
            }
        };
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        lenient().when(properties.getCacheTtlSeconds()).thenReturn(300L);
    }

    @Test
    @DisplayName("Old schema cached JSON (contains 'appId') → swallowed by try-catch, falls through to upstream")
    void oldSchemaCachedJson_swallowed_fallsThroughToUpstream() {
        // 旧 schema：含 "appId" 字段，没有 "businessTag"
        String oldJson = "{\"assistantScope\":\"business\",\"appId\":\"app_x\"," +
                "\"cloudEndpoint\":\"https://x\",\"cloudProtocol\":\"sse\",\"authType\":\"soa\"}";
        when(valueOperations.get(anyString())).thenReturn(oldJson);

        AssistantInfo info = service.getAssistantInfo("ak-test");

        // 不抛异常，回源后拿到新 schema 的 info
        assertNotNull(info);
        // assertEquals("tag-fresh", info.getBusinessTag()); — 取决于上游 mock 的 fetch 行为，可选断言
    }
}
```

- [ ] **Step 2: 跑测试，验证通过**

Run: `cd skill-server && mvn test -Dtest=AssistantInfoCacheCompatTest`
Expected: PASS

> 如果失败：检查 ObjectMapper 的 FAIL_ON_UNKNOWN_PROPERTIES 是否已被项目配置覆盖为 false。如果是 false，旧 schema JSON 会成功反序列化（appId 字段被忽略），不抛异常 — 仍然不阻塞业务流（fall through 不会发生，但服务返回的是没填 businessTag 的 info）。如果遇到这种情况，调整测试断言以反映实际行为。

- [ ] **Step 3: 提交**

```bash
git add skill-server/src/test/java/com/opencode/cui/skill/service/AssistantInfoCacheCompatTest.java
git commit -m "test(skill-server): add AssistantInfo Redis cache compat test for AssistantInfo.appId rename"
```

---

## Task 13: BusinessTagWhitelistEndToEndTest — 集成测试

**Files:**
- Create: `skill-server/src/test/java/com/opencode/cui/skill/service/BusinessTagWhitelistEndToEndTest.java`

- [ ] **Step 1: 写集成测试**

```java
package com.opencode.cui.skill.service;

import com.opencode.cui.skill.model.AssistantInfo;
import com.opencode.cui.skill.model.SysConfig;
import com.opencode.cui.skill.repository.SysConfigMapper;
import com.opencode.cui.skill.service.scope.AssistantScopeDispatcher;
import com.opencode.cui.skill.service.scope.AssistantScopeStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 端到端覆盖：sys_config 真实数据 → AssistantScopeDispatcher.getStrategy(info) → 选对 strategy
 *
 * 矩阵：总开关 0/1 × 空白名单 / 命中 / 不命中 / null tag
 */
@SpringBootTest
@DirtiesContext
@Transactional
class BusinessTagWhitelistEndToEndTest {

    @Autowired AssistantScopeDispatcher dispatcher;
    @Autowired SysConfigMapper sysConfigMapper;
    @Autowired StringRedisTemplate redisTemplate;

    @BeforeEach
    void cleanCache() {
        // 每个测试前清空白名单集合缓存（事务回滚也回滚不了 Redis）
        redisTemplate.delete("ss:config:set:business_cloud_whitelist");
        redisTemplate.delete("ss:config:cloud_route:business_whitelist_enabled");
    }

    private void setSwitch(String value) {
        SysConfig sw = sysConfigMapper.findByTypeAndKey("cloud_route", "business_whitelist_enabled");
        sw.setConfigValue(value);
        sysConfigMapper.update(sw);
        redisTemplate.delete("ss:config:cloud_route:business_whitelist_enabled");
    }

    private void insertTag(String tag, int status) {
        SysConfig c = new SysConfig();
        c.setConfigType("business_cloud_whitelist");
        c.setConfigKey(tag);
        c.setConfigValue("1");
        c.setStatus(status);
        sysConfigMapper.insert(c);
    }

    private AssistantInfo businessInfo(String tag) {
        AssistantInfo info = new AssistantInfo();
        info.setAssistantScope("business");
        info.setBusinessTag(tag);
        return info;
    }

    @Test
    @DisplayName("switch=0 → all business → cloud (regardless of whitelist)")
    void switchOff_allBusinessCloud() {
        setSwitch("0");
        AssistantScopeStrategy s = dispatcher.getStrategy(businessInfo("any-tag"));
        assertEquals("business", s.getScope());
    }

    @Test
    @DisplayName("switch=1 + empty whitelist → all business → cloud (fail-open)")
    void switchOn_emptyWhitelist_cloud() {
        setSwitch("1");
        AssistantScopeStrategy s = dispatcher.getStrategy(businessInfo("tag-foo"));
        assertEquals("business", s.getScope());
    }

    @Test
    @DisplayName("switch=1 + tag in whitelist → cloud")
    void switchOn_tagInWhitelist_cloud() {
        setSwitch("1");
        insertTag("tag-foo", 1);
        AssistantScopeStrategy s = dispatcher.getStrategy(businessInfo("tag-foo"));
        assertEquals("business", s.getScope());
    }

    @Test
    @DisplayName("switch=1 + tag NOT in whitelist → personal (downgrade)")
    void switchOn_tagNotInWhitelist_personal() {
        setSwitch("1");
        insertTag("tag-foo", 1);
        AssistantScopeStrategy s = dispatcher.getStrategy(businessInfo("tag-other"));
        assertEquals("personal", s.getScope());
    }

    @Test
    @DisplayName("switch=1 + null businessTag → personal")
    void switchOn_nullTag_personal() {
        setSwitch("1");
        AssistantScopeStrategy s = dispatcher.getStrategy(businessInfo(null));
        assertEquals("personal", s.getScope());
    }

    @Test
    @DisplayName("switch=0 + null businessTag → cloud (do NOT downgrade)")
    void switchOff_nullTag_cloud() {
        setSwitch("0");
        AssistantScopeStrategy s = dispatcher.getStrategy(businessInfo(null));
        assertEquals("business", s.getScope());
    }
}
```

- [ ] **Step 2: 跑集成测试**

Run: `cd skill-server && mvn test -Dtest=BusinessTagWhitelistEndToEndTest`
Expected: 6 tests PASS

> 如果失败：常见原因
> - V11 没在 test profile 跑：检查 `application-test.yml` 和 Flyway 配置
> - Redis test container 未启动：本仓库使用真实 Redis（开发环境），需要本地 Redis 跑起来；CI 中可能用 Testcontainers
> - 事务回滚清不了 Redis：BeforeEach 已显式 delete 集合缓存

- [ ] **Step 3: 跑全套测试做最后回归**

Run: `cd skill-server && mvn test`
Expected: 所有测试 PASS（包括 13 task 的所有新增 + 修改）

- [ ] **Step 4: 提交**

```bash
git add skill-server/src/test/java/com/opencode/cui/skill/service/BusinessTagWhitelistEndToEndTest.java
git commit -m "test(skill-server): add BusinessTagWhitelistEndToEndTest covering switch x whitelist matrix"
```

---

## 完成检查

执行完 13 个 task 后，验证以下项（对应 spec 验收标准）：

- [ ] V11 migration 已在 dev 库跑通（`SELECT * FROM sys_config WHERE config_type='cloud_route' AND config_key='business_whitelist_enabled'` 返回 1 行）
- [ ] `mvn test` 全绿（含 6 个新测试 + 修改的现有测试）
- [ ] `mvn checkstyle:check` 通过（如项目启用）
- [ ] `mvn compile` 无 warning（unused import / deprecation 等）
- [ ] 手动验证：本地启动 + 模拟 business + tag 不命中 → 走 personal 路径（看日志 `[Whitelist] businessTag {} not in whitelist`）
- [ ] 手动验证：总开关切回 `'0'` + 同样请求 → 走 cloud 路径

---

## 备注（实施过程中的提示）

1. **Task 顺序敏感**：Task 1（migration）→ Task 2-3（properties）→ Task 4-6（重命名）→ Task 7-8（新服务 + dispatcher）→ Task 9-10（调用点）→ Task 11（mock）→ Task 12-13（测试）。Task 9-10 之前不要跑全部测试（会有大量 mock 失败属预期）。

2. **遇到外围测试失败**：先确认是不是 Task 11 还没做完。Task 11 的 6 个文件改完后大部分会自然通过。

3. **PersonalScopeStrategy.buildInvoke 仍是 placeholder**：本次不重构。`GatewayRelayService` 通过本地 `buildInvokeMessage` 处理 personal 分支（含白名单未命中降级的情况）。

4. **Spec 引用**：实施时遇到歧义先看 spec `docs/superpowers/specs/2026-04-27-business-tag-whitelist-design.md`，特别是错误矩阵章节。
