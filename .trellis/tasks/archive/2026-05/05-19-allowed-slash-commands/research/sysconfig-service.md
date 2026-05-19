# Research: sysconfig 服务调用方式

- **Query**: skill-server `SysConfigService` 的调用方式、缓存、命名约定
- **Scope**: internal
- **Date**: 2026-05-19

## 服务定位

- 全限定名：`com.opencode.cui.skill.service.SysConfigService`
- 源码：`skill-server/src/main/java/com/opencode/cui/skill/service/SysConfigService.java:32`
- Spring `@Service`，构造函数注入 `SysConfigMapper`、`StringRedisTemplate`、`SysConfigProperties`

## 主方法签名

```java
public String getValue(String configType, String configKey)
```

文件位置：`SysConfigService.java:48`

**返回值类型**：`String`（**始终是字符串**，复杂结构由 caller 自己 `objectMapper.readValue` 反序列化）

**异常行为**（`SysConfigService.java:48-84`）：

| 情况 | 行为 |
|---|---|
| Redis 读取失败 | catch `RuntimeException` → WARN 日志 → 降级直查 DB |
| DB 查不到 (`SysConfigMapper.findByTypeAndKey == null`) | 返回 `null`，DEBUG 日志 `Config not found in DB` |
| 查到但 `status != 1`（禁用） | 返回 `null`，**不写缓存**，DEBUG 日志 `Config is disabled` |
| `status == 1` 但 Redis 写缓存失败 | 静默 WARN，**仍返回 value**（不影响业务） |
| 命中正常 | 返回 `config.getConfigValue()` |

**绝不抛 checked exception**。其他 service 经常 `try { ... } catch (RuntimeException e) { ... fail-open/fail-safe }`（见 `BusinessWhitelistService.java:82-96`、`ChannelSuppressReplyWhitelistService.java:86-101`），属于约定的防御层。

## 缓存机制

**有内置 Redis 缓存**（无需 caller 再加一层）：

- 缓存 key 格式：`ss:config:{configType}:{configKey}`（见 `SysConfigService.java:34` + `:133-135`）
- TTL：来自 `SysConfigProperties.cacheTtlMinutes`，默认 **5 分钟**（`SysConfigProperties.java:16`）
- **仅缓存 `status=1`** 的配置值（disabled 项每次穿透 DB，但 DB 命中后也不写缓存）
- `create` / `update` 主动 evict（`SysConfigService.java:101-118` + `:137-145`），`delete` 依赖 TTL 自然过期

> 配置类：`com.opencode.cui.skill.config.SysConfigProperties`（`@ConfigurationProperties(prefix = "skill.sys-config")`）；外部 yaml 可设 `skill.sys-config.cache-ttl-minutes`。

## 命名约定（key namespace / scope）

观察现网 caller 的 `(configType, configKey)` 模式：

| caller (file:line) | configType | configKey 形态 | 用途 |
|---|---|---|---|
| `AssistantOfflineMessageProvider.java:22` | `"assistant_offline"` | `"message"` | 单 key 单值（文案） |
| `BusinessWhitelistService.java:83` | `"cloud_route"` | `"business_whitelist_enabled"` | 总开关（"0"/"1"） |
| `BusinessWhitelistService.java:111` (`SysConfigMapper.findByType`) | `"business_cloud_whitelist"` | （集合，按 type 查全表） | 用 configKey 当 set 成员 |
| `ChannelSuppressReplyWhitelistService.java:88` | `"channel_suppress_reply"` | `"channel_suppress_reply_enabled"` | 总开关 |
| `ChannelSuppressReplyWhitelistService.java` (load) | `"suppress_reply_channel_whitelist"` | （集合，按 type 查全表） | set 成员 |
| `CloudRequestBuilder.java:50` | `"cloud_request_strategy"` | `appId`（运行时变量） | per-appId 策略名 |
| `CloudRequestProfileRegistry.java:79` | `"cloud_protocol_profile"` | `businessTag`（运行时变量） | per-tag profile 名 |
| `CloudRequestProfileRegistry.java:92` | `"cloud_protocol_profile_def"` | profileName | profile 完整定义 JSON |
| `DefaultAssistantRuleService.java:64` | `"default_assistant_rule"` | `domain + ":" + domainType` | per-(domain,type) 规则 JSON |

### 命名风格总结

1. **configType = 业务能力域**（snake_case），**configKey = 同一域内的具体子标识**（可以是固定字符串、运行时 ak/tag、或复合 key）
2. **复合 key 用分隔符**：现网约定不统一 —— `DefaultAssistantRuleService` 用 `:`（`helpdesk:direct`），本任务 PRD 决策用 `_`（`${domain}_${type}`），二者并存可以接受，但要注意：
   - **没有 namespace 前缀**：configKey 直接是 `${domain}_${type}`（PRD 已对齐"无前缀，下划线拼接"）
   - 如果未来 domain/type 字符串里出现下划线，会有歧义 —— 当前业务上 domain 是 `im` / `miniapp` 等，type 是 `direct` / `group`，暂时无冲突风险
3. **总开关单独建 type**：例如 `cloud_route.business_whitelist_enabled` —— 把开关和数据分两个 configType 存

### 值的格式约定

- 简单标量：直接存字符串（`"1"` / `"0"` / 文案）
- 复杂结构：存 JSON 字符串，caller `ObjectMapper.readTree` / `readValue`，见 `DefaultAssistantRuleService.java:68-79`、`CloudRequestProfileRegistry.java:91-100`
- **本任务**：PRD 决策存 JSON 数组字符串 `["plan","ask","run"]` → caller `objectMapper.readValue(json, new TypeReference<List<String>>(){})` 或 `readTree` + 手动收集 textNode

## 调用示例

### 示例 1：简单标量 + 兜底默认

`AssistantOfflineMessageProvider.java:21-24`:

```java
public String get() {
    String v = sysConfigService.getValue(CONFIG_TYPE, CONFIG_KEY);
    return (v == null || v.isBlank()) ? DEFAULT_OFFLINE_MESSAGE : v;
}
```

特点：
- 直接 `getValue` 不 try-catch（让 `RuntimeException` 冒泡到 caller）
- `null` / 空字符串 → 默认值

### 示例 2：JSON 反序列化 + 校验失败兜底

`DefaultAssistantRuleService.java:59-79`:

```java
public Optional<DefaultAssistantRule> lookup(String domain, String domainType) {
    if (isBlank(domain) || isBlank(domainType)) {
        return Optional.empty();
    }
    String configKey = domain + ":" + domainType;
    String json = sysConfigService.getValue(CONFIG_TYPE, configKey);
    if (json == null) {
        return Optional.empty();
    }
    try {
        DefaultAssistantRule rule = objectMapper.readValue(json, DefaultAssistantRule.class);
        return Optional.of(rule);
    } catch (JsonProcessingException e) {
        log.warn("[RuleService] invalid JSON for {}:{}: {}", domain, domainType, e.getMessage());
        return Optional.empty();
    } catch (IllegalArgumentException e) {
        log.warn("[RuleService] invalid rule fields for {}:{}: {}", domain, domainType, e.getMessage());
        return Optional.empty();
    }
}
```

特点：
- caller 先做 blank 检查，**避免对空 key 发起 DB 查询**（性能/语义双重）
- `null` / `JsonProcessingException` / record 构造器抛 `IllegalArgumentException` → 全部转 `Optional.empty()`
- WARN 日志带上下文（domain / domainType / 错误消息）
- **不在外层再加缓存**（注释明确说："本 service 只是薄壳，复用 SysConfigService 内部 Redis 5min 缓存"）

### 示例 3：开关 + 集合两阶段读取

`BusinessWhitelistService.java:81-126`:

```java
private boolean isFeatureEnabled() {
    try {
        String v = sysConfigService.getValue(CONFIG_TYPE_SWITCH, CONFIG_KEY_SWITCH);
        if ("1".equals(v)) return true;
        if (v == null) { ...; return false; }   // 未配置 = 关闭
        if ("0".equals(v)) return false;
        log.warn("[Whitelist] unknown switch value '{}', treating as disabled", v);
        return false;
    } catch (RuntimeException e) {
        log.warn("[Whitelist] switch read failed, fail-open (treat as disabled): error={}", e.getMessage());
        return false;
    }
}
```

特点：
- 开关读取后做严格三态判定 (`"1"` / `"0"` / 其他)
- `RuntimeException` 兜底 → fail-safe（功能默认关闭）

## 对 allowed-slash-commands 任务的可复用建议

| 关注点 | 现网答案 |
|---|---|
| 服务类全名 | `com.opencode.cui.skill.service.SysConfigService` |
| 调用签名 | `sysConfigService.getValue("?", "?")` 返 `String`（可 null） |
| 是否需要自己加缓存 | **不需要**，`SysConfigService` 内置 Redis 5min TTL |
| key 不存在的行为 | 返 `null`（不抛异常），caller 自行兜底 |
| 异常处理风格 | caller 一般 try-catch RuntimeException，按业务决定 fail-open / fail-safe |
| key 拼接方式 | PRD 已决策 `${businessSessionDomain}_${businessSessionType}`，**configType 待定**（建议起名 `allowed_slash_commands` 或类似，与现网风格保持） |
| JSON 数组解码 | `objectMapper.readValue(json, new TypeReference<List<String>>() {})`；解析失败按 PRD 决策"不下发该字段" |

## Caveats

- PRD 已决策 sysconfig key 是 `${businessSessionDomain}_${businessSessionType}`，**没有给出 configType**。本研究列了所有现网 configType（`assistant_offline` / `cloud_route` / `default_assistant_rule` 等），但本任务该用哪个 configType 是 prd.md 待定项，需 brainstorm 或 codex 评审里收口。
- `SysConfigService` 单条缓存的命中跟 caller 完全无关 —— 即使 caller 自己加内存缓存也不会影响（但会让 update evict 的传播延迟变大）。`DefaultAssistantRuleService` 注释明确反对再加一层，本任务建议跟随同样原则。
- 没有看到任何 caller 对 `getValue` 做空 String/JSON 数组的"统一解析 helper"，每个 caller 自己写解析。如果未来要新增 list 类型的 sysconfig 多了，可以考虑抽一个 `parseStringList`，但**当前 PRD 范围内不需要**（属于改进建议，已被任务约束排除）。
