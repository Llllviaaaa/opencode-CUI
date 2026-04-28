# Business Tag 云端协议白名单 — 设计文档

- 日期：2026-04-27
- 范围：skill-server
- 类型：功能新增 + bug 修复 + 字段重命名

---

## 背景与目标

### 当前行为

`skill-server` 在收到 skill 调用时，通过 `AssistantInfoService` 拿到 `AssistantInfo.assistantScope`：
- `assistantScope == "business"` → `BusinessScopeStrategy`，走云端协议
- 其他 → `PersonalScopeStrategy`，走本地协议

判定发生在 `AssistantScopeDispatcher.getStrategy(String scope)`。生产代码共 **9 处**调用点（按 ak 选 strategy）。

### 需求

业务方希望对"哪些 business 助手可以走云端协议"做更细粒度的白名单控制，按 `businessTag` 维度允许/拒绝。**未命中白名单的 business 助手降级走本地协议**。

### 顺带修复的 bug

`AssistantInfoService.parseApiResponse:199` 当前从上游 JSON 读取 `data.hisAppId` 写入 `AssistantInfo.appId`，但**上游接口实际返回的字段名一直是 `businessTag`**，从未返回 `hisAppId` 或 `appId`。后果：

- `AssistantInfo.appId` 在线上**一直为 null**
- `BusinessScopeStrategy.buildInvoke:54` 取 `info.getAppId()` 永远 null，并将 null 传入 `cloudRequestBuilder.buildCloudRequest(appId, context)`
- 该 null 作为 `sys_config[cloud_request_strategy, <key>]` 的查询键永远 miss，使"按业务方下发不同 cloud request 构建策略"的设计能力从未真正生效（详见 §风险与未知）

> 注：`appId` 不会进入 `cloudRequest` payload 本身——payload 由 `CloudRequestStrategy.build(context)` 构造，context 字段中无 appId。bug 的可观察后果仅在 skill-server 内部 `CloudRequestBuilder` 的策略选择路径上。

本次顺手修这个 bug：解析键改为 `data.businessTag`，并把 `AssistantInfo.appId` 重命名为 `AssistantInfo.businessTag`。

---

## 决策记录

| 议题 | 决策 | 理由 |
|---|---|---|
| businessTag 来源 | 上游 AssistantInfoService 接口的 `data.businessTag` 字段 | 上游一直返回该字段；现有解析键 `hisAppId` 是 bug |
| Model 字段命名 | `AssistantInfo.appId` → `AssistantInfo.businessTag`（重命名） | 字段语义就是 businessTag，原名是误读 |
| 白名单存放 | 复用现有 `sys_config` 表 + `SysConfigService` | 项目无配置中心；该表已用于 `cloud_request_strategy`、离线消息开关等 |
| 落表形态 | 一行一 tag（`config_type='business_cloud_whitelist'`，`config_key=<tag>`） | 比 CSV 字符串更友好：可单条增删、`status=0` 软下线、不受 512 字符限制 |
| Gate 实现位置 | 在 `AssistantScopeDispatcher` 入口处加白名单 gate；新增 `getStrategy(AssistantInfo info)` 重载，旧 `getStrategy(String scope)` 保留作为内部纯 lookup | 集中 gate 逻辑、调用点改动量小、不改 strategy 接口 |
| **不做** session 级 sticky strategy | 每次调用实时判定 strategy（基于当时白名单状态） | 业务确认：会话生命周期短，白名单变更频率低，"会话内 strategy 漂移" 场景罕见，sticky 设计成本超过收益 |
| 判断顺序 | `BusinessWhitelistService.allowsCloud(tag)` 内部：先 `isFeatureEnabled()` 检查总开关，再 `tag null/blank` 检查 | 总开关 `'0'` 时**不**触碰 tag，行为完全等同当前线上 |
| 未命中行为 | 降级 `PersonalScopeStrategy`（走本地） | 用户明确指定 |
| 空白名单语义 | Fail-open（全 business 放行） | 兼容当前线上行为，灰度上线安全 |
| businessTag 为 null/blank（仅在白名单启用时） | 走本地 + WARN | 异常态保守降级；总开关关闭时不触发该分支 |
| 总开关 | 新增 `cloud_route/business_whitelist_enabled`，默认 `'0'` = 关闭白名单（老行为） | 上线灰度 + 紧急回滚双重兜底；秒级生效（走 SysConfigService 单 key 缓存 + evict） |
| SysConfig 缓存 TTL | 30 分钟硬编码 → 可配置 properties，默认 5 分钟 | 改善运营变更生效时延；不同环境可独立调整 |
| 白名单集合缓存失效 | 仅 TTL 自然过期（不接 SysConfigController evict） | 增删 tag 频率低，5 分钟延迟可接受 |

---

## 架构与组件

### 改动 / 新增清单

| 组件 | 类型 | 说明 |
|---|---|---|
| `V11__init_business_whitelist_config.sql` | **新增** | 注入总开关默认值（**幂等 INSERT**） |
| `AssistantInfo` | 修改 | 字段 `appId` → `businessTag`（重命名） |
| `AssistantInfoService.parseApiResponse` | 修改（bug fix） | 解析键 `data.hisAppId` → `data.businessTag`；setter 跟随重命名 |
| `BusinessScopeStrategy:54` | 修改 | `info.getAppId()` → `info.getBusinessTag()` |
| `BusinessWhitelistService` | **新增** | 封装 `allowsCloud(businessTag)`：总开关 + 集合缓存 + 故障降级 |
| `AssistantScopeDispatcher` | 修改 | **新增** `getStrategy(AssistantInfo info)` 重载（内部含白名单 gate）；旧 `getStrategy(String scope)` **保留**为纯 lookup |
| `SysConfigProperties` | **新增** | `@ConfigurationProperties(prefix="skill.sys-config")`，含 `cacheTtlMinutes`（默认 5） |
| `SysConfigService` | 修改 | TTL 从硬编码 30L → 注入 `SysConfigProperties.cacheTtlMinutes` |
| 9 处调用点（机械替换） | 修改 | 模式从 `String scope = assistantInfoService.getCachedScope(ak); strategy = dispatcher.getStrategy(scope);` 改为 `AssistantInfo info = assistantInfoService.getAssistantInfo(ak); strategy = dispatcher.getStrategy(info);`：<br/>　・`SkillSessionController:115`<br/>　・`ImSessionManager:141`<br/>　・`InboundProcessingService:172`<br/>　・`InboundProcessingService:219`<br/>　・`InboundProcessingService:512`<br/>　・`InboundProcessingService:567`<br/>　・`SkillMessageController:177`<br/>　・`SkillMessageController:408`<br/>　・`GatewayMessageRouter:521` |
| `GatewayRelayService:107-122`（特殊改造） | 修改 | invoke 主路径，需要保留对 personal 的本地 `buildInvokeMessage` 分支：①保持 `info = assistantInfoService.getAssistantInfo(ak)` 不变；②原 `if (info != null && info.isBusiness())` 改为 `strategy = dispatcher.getStrategy(info); if ("business".equals(strategy.getScope()))`；③business 分支调 `strategy.buildInvoke(command, info)`；④else 分支保留 `buildInvokeMessage(command)` 本地路径 |
| 单元 / 集成测试 | 新增 + 修改 | 详见测试策略章节 |

> **不改动**：
> - ❌ `SkillSession` 表结构（无 V12）
> - ❌ `SkillSessionRepository` mapper XML
> - ❌ `BusinessScopeStrategy` / `PersonalScopeStrategy` 接口签名
> - ❌ ai-gateway 端任何代码（已确认对 ai-gateway 透明）

### 类关系

```
9 处调用点
        │
        ├─ info = assistantInfoService.getAssistantInfo(ak)
        ├─ strategy = dispatcher.getStrategy(info)
        │       │
        │       ├─ if info == null                       → personalStrategy
        │       ├─ if info.assistantScope != "business"  → personalStrategy
        │       ├─ if whitelistService.allowsCloud(info.businessTag) → businessStrategy
        │       └─ else                                  → personalStrategy
        └─ strategy.buildInvoke(...) / requiresOnlineCheck() / translateEvent(...) / generateToolSessionId()

BusinessWhitelistService.allowsCloud(tag):  ← 顺序很关键
        ├─ if !isFeatureEnabled()  → return true   （总开关 = '0'，不触碰 tag）
        ├─ if tag null/blank       → return false + WARN
        ├─ tags = loadWhitelistTags()
        ├─ if tags.isEmpty()       → return true + INFO   （fail-open）
        ├─ DB / Redis 异常路径     → return true + WARN  （fail-open）
        └─ return tags.contains(tag)
                │
                ├─→ SysConfigService.getValue("cloud_route", "business_whitelist_enabled")
                │      （单 key Redis 缓存，TTL = SysConfigProperties.cacheTtlMinutes，默认 5min）
                └─→ SysConfigMapper.findByType("business_cloud_whitelist")
                       （集合 Redis 缓存 ss:config:set:business_cloud_whitelist，TTL 同上）
```

---

## 数据流

```
1. 请求到达（ak 已知）
2. info = AssistantInfoService.getAssistantInfo(ak)
     ├─ Redis ss:assistant:info:{ak}（300s TTL，独立于 sys_config）
     └─ miss → 上游接口
         上游 JSON: { code:"200", data:{ identityType:"3", businessTag:"tag-foo",
                       endpoint:"...", protocol:"2", authType:"1" } }
         parseApiResponse → AssistantInfo {
             assistantScope: "business",
             businessTag: "tag-foo",
             cloudEndpoint, cloudProtocol, authType
         }
3. strategy = dispatcher.getStrategy(info)
     ├─ if (info == null)                                  → personalStrategy
     ├─ if (info.assistantScope != "business")             → personalStrategy
     ├─ if (whitelistService.allowsCloud(info.businessTag)) → businessStrategy
     └─ else                                               → personalStrategy
4. strategy.buildInvoke(command, info) / requiresOnlineCheck() / translateEvent(event, sid) / generateToolSessionId()

BusinessWhitelistService.allowsCloud(tag):  ← 判断顺序很关键
     ├─ if (!featureEnabled())   → true   （总开关 = '0'，不触碰 tag）
     ├─ if (tag null/blank)      → false + WARN
     ├─ tags = loadWhitelistTags()
     ├─ if (tags.isEmpty())      → true + INFO   （fail-open）
     ├─ DB/Redis 异常            → true + WARN   （fail-open）
     └─ return tags.contains(tag)
```

> **判断顺序的设计目的**：总开关检查放在最前，保证 `business_whitelist_enabled='0'` 时 tag 字段是否为空、白名单表是否为空都**不影响**最终结果（永远 return true）。这是"完全等同当前线上"承诺的代码层保证。

### sys_config 落表

**白名单条目**（一行一 tag，`business_cloud_whitelist` type）：

```sql
INSERT INTO sys_config (config_type, config_key, config_value, description, status) VALUES
  ('business_cloud_whitelist', 'tag-foo', '1', '业务方 foo 允许走云端协议', 1),
  ('business_cloud_whitelist', 'tag-bar', '1', '业务方 bar 允许走云端协议', 1);
```

读取方式：`sysConfigMapper.findByType('business_cloud_whitelist')` → 过滤 `status=1` → 收集 `config_key` 为 `Set<String>`。

运营/运维通过现有 `SysConfigController` POST/PUT/DELETE 增删改条目。`status=0` 可临时下线某 tag 不删除原始数据。

**总开关**（migration 自动注入，`cloud_route` type，**幂等写法**）：

```sql
-- V11__init_business_whitelist_config.sql
INSERT INTO sys_config (config_type, config_key, config_value, description, status) VALUES
  ('cloud_route', 'business_whitelist_enabled', '0',
   '业务助手云端白名单开关：1=启用白名单 gate；0=关闭 gate（全 business 放行，老行为）', 1)
ON DUPLICATE KEY UPDATE id = id;
```

> 利用 `sys_config` 已有的 `UNIQUE KEY uk_type_key (config_type, config_key)` 约束（V10），`ON DUPLICATE KEY UPDATE id = id` 表示"已存在则不覆盖"，从而避免环境差异导致 Flyway 执行失败。

### 缓存层

| 缓存 | Redis key | 内容 | TTL | 失效方式 |
|---|---|---|---|---|
| AssistantInfo（已有，复用） | `ss:assistant:info:{ak}` | 单值 JSON | `AssistantInfoProperties.cacheTtlSeconds`（默认 300s） | TTL 自然过期 |
| SysConfig 单条（已有，复用） | `ss:config:{type}:{key}` | 单值 | `SysConfigProperties.cacheTtlMinutes`（默认 5min） | `SysConfigService.update/create` 主动 evict |
| 白名单集合（**新增**） | `ss:config:set:business_cloud_whitelist` | JSON 数组 | 同 SysConfigProperties | 仅 TTL 自然过期 |

> **取舍说明**：
> - 总开关走 `SysConfigService.getValue` → 已有 evict 机制 → 启停**秒级生效**（紧急回滚刚需）
> - 白名单集合无 evict → 增删 tag 最长 5 分钟生效，可接受

### 缓存故障降级

- Redis 读失败 → 直查 DB
- DB 也失败 → fail-open（白名单视为允许），WARN 日志

---

## 错误 / 边界矩阵

| 输入 / 状态 | 总开关 | 行为 | 日志 |
|---|---|---|---|
| `info == null`（上游不可达） | 任意 | personal | DEBUG（AssistantInfoService 内部已 WARN） |
| `info.assistantScope != "business"` | 任意 | personal（不查白名单） | 无 |
| 总开关 `'0'` | `'0'` | 全 business 放行（云端） | DEBUG `whitelist disabled, all business allowed` |
| 总开关值非 `'0'`/`'1'` | 异常 | 视为 `'0'`（fail-open） | WARN `unknown whitelist switch value, treating as disabled` |
| 总开关查询异常 | 异常 | fail-open（视为关闭） | WARN（已由 SysConfigService 内部捕获） |
| `info.businessTag` null/blank | `'1'` | personal | WARN `business scope but businessTag missing: ak={}` |
| `info.businessTag` null/blank | `'0'` | 云端（**不触发判空，等同当前线上**） | 无 |
| 白名单集合查询异常 | `'1'` | fail-open（云端） | WARN |
| 白名单集合为空 | `'1'` | 云端（fail-open） | INFO |
| businessTag 命中 status=1 | `'1'` | 云端 | DEBUG |
| businessTag 不在表 / status=0 | `'1'` | personal | INFO `businessTag {tag} not in whitelist, fallback to local` |

**默认行为约定**：除"明确不在白名单"以外，`BusinessWhitelistService` 内的所有异常路径一律 fail-open。配合总开关默认 `'0'`，形成双层兜底——只有当运维**明确**打开总开关 + **明确**配置白名单 + **明确**该 tag 不在内，才会发生降级。

---

## 兼容性 / 迁移

### 发布步骤

| 步骤 | 行为状态 | 风险 |
|---|---|---|
| 1. 合并改动（重命名 + bug 修复 + 加 gate） | 总开关默认 `'0'`，全 business 走云端 | `info.businessTag` 不再为 null（修 bug）；对 ai-gateway 透明 |
| 2. DBA 跑 V11 migration | 自动注入总开关默认值 | 无；migration 失败时 `getValue` 返回 null → fail-open |
| 3. 配置白名单条目（运营往 `business_cloud_whitelist` 插行） | 仍未生效（总开关 = `'0'`） | 无 |
| 4. 把总开关切到 `'1'`（逐环境）| 白名单生效 | 配错的 tag 会被降级走本地 |
| 5. 出问题：总开关切回 `'0'` | 立即恢复老行为（单 key 缓存秒级 evict） | 无 |

### 发布期 Redis 缓存兼容性

`AssistantInfoService` 用 `new ObjectMapper()` 反序列化 Redis 缓存的 `AssistantInfo` JSON（`AssistantInfoService:50, 67`）。Jackson 默认 **`FAIL_ON_UNKNOWN_PROPERTIES = true`**。

**问题**：发布前 Redis 缓存的旧 JSON 形如 `{"assistantScope":"business","appId":"app_xxx",...}`；发布后 model 字段已改为 `businessTag`，旧 key 反序列化时会抛 `UnrecognizedPropertyException`。

**实际行为**：异常被 `AssistantInfoService:69-71` 的 `try-catch` 兜住：
```java
} catch (Exception e) {
    log.warn("[AssistantInfoService] cache read error: ak={}, error={}", ak, e.getMessage());
}
// fall through 到上游 fetch + 重新写缓存（用新 schema）
```

**副作用**：发布后 ≤300s 窗口内（`AssistantInfoProperties.cacheTtlSeconds`），每个活跃 ak **至少一次**缓存击穿到上游 API；之后写入的新 JSON 已是新 schema，缓存恢复正常。

**应对**：
- 发布前观察上游 API 当前 QPS，估算 300s 窗口内最坏情况下的请求量
- 发布后短期监控：上游 API 调用量、`cache read error` WARN 日志数量、AssistantInfo Redis hit ratio

### 回滚

| 改动 | 回滚策略 |
|---|---|
| V11（sys_config 加行） | 无需 down migration；旧应用版本不读 `business_whitelist_enabled` key 不影响；如需删除：`DELETE FROM sys_config WHERE config_type='cloud_route' AND config_key='business_whitelist_enabled'` |
| 应用代码 | 走标准灰度回滚 |

---

## 测试策略

### 单元测试（新增 / 修改）

1. **`AssistantInfoServiceTest`**（修改 + 新增 bug 回归）
   - 新增：`parseApiResponse` 读 `data.businessTag` 而非 `data.hisAppId`（bug 回归）
   - 新增：`businessTag == null` → `AssistantInfo.businessTag` 为 null
   - 修改：~14 处 `setAppId/getAppId/hisAppId` 跟随重命名

2. **`BusinessWhitelistServiceTest`**（**新建**）—— 判断顺序很重要
   - 总开关 `'0'` + tag null → true（**总开关关闭时不触碰 tag**）
   - 总开关 `'0'` + tag blank → true
   - 总开关 `'0'` + tag 任意 → true
   - 总开关 `'1'` + tag null → false + WARN
   - 总开关 `'1'` + tag blank → false + WARN
   - 总开关 `'1'` + 空表 → true（fail-open）
   - 总开关 `'1'` + tag 命中（status=1） → true
   - 总开关 `'1'` + tag 命中但 status=0 → false
   - 总开关 `'1'` + tag 不在表 → false
   - 总开关值非 `'0'`/`'1'` + tag 任意 → true（视为 disabled）
   - DB 异常 → fail-open（捕获 + WARN）
   - 集合缓存：第二次调用不再查 DB（mock verify atMostOnce）
   - 集合缓存：Redis 不可用 → 直查 DB

3. **`AssistantScopeDispatcherTest`**（修改）
   - 保留：原有 `getStrategy(scope)` 测试不变（旧方法纯 lookup）
   - 新增：`getStrategy(null info)` → PersonalScopeStrategy
   - 新增：`getStrategy(personal info)` → PersonalScopeStrategy，verify whitelistService 不被调用
   - 新增：`getStrategy(business info, allowsCloud=true)` → BusinessScopeStrategy
   - 新增：`getStrategy(business info, allowsCloud=false)` → PersonalScopeStrategy

4. **`BusinessScopeStrategyTest`**（修改）
   - `setAppId` → `setBusinessTag`，~14 处机械替换
   - 保留原有断言；可选 mock verify `cloudRequestBuilder.buildCloudRequest(<businessTag>, ctx)` 第一个参数 = businessTag

5. **`SysConfigServiceTest`**（修改）
   - TTL 从硬编码改成 properties 注入；构造时使用不同 properties 值，verify Redis `set` 调用使用对应 TTL

6. **`AssistantInfoCacheCompatTest`**（**新建**）
   - 给定旧 schema JSON（`{"assistantScope":"business","appId":"app_x",...}`）放入 mock Redis
   - 调 `getAssistantInfo(ak)` → 不抛异常，返回非 null（fall through 到上游）
   - 验证 WARN 日志包含 `cache read error`

### 受影响的外围测试 mock 面（必改）

以下测试目前 stub `dispatcher.getStrategy(scope)`，生产代码切到 `getStrategy(info)` 后必须同步：

| 测试文件 | 当前 stub | 应改为 |
|---|---|---|
| `SkillMessageControllerTest:352` | `when(dispatcher.getStrategy(anyString())).thenReturn(strategy)` | `when(dispatcher.getStrategy(any(AssistantInfo.class))).thenReturn(strategy)` |
| `SkillSessionControllerTest:55` | 同上 | 同上 |
| `InboundProcessingServiceTest:107` | 同上 | 同上 |
| `GatewayRelayServiceScopeTest:80` | 同上 | 同上 |
| `ImSessionManagerTest`（如存在） | 同上 | 同上 |
| `GatewayMessageRouterImPushTest` | 同上 | 同上 |

### 集成测试

7. **`BusinessTagWhitelistEndToEndTest`**（**新建**）
   - SpringBoot test：插入真实 sys_config 行 + 模拟 AssistantInfo
   - 覆盖矩阵：总开关 0/1 × 空白名单 / 命中 / 不命中 / null tag（≥6 组合）

### 回归测试

确认下列测试在重命名 + 调用点切换后仍全部通过：
- `BusinessScopeStrategyTest` 13 个 case
- `GatewayRelayServiceScopeTest`、`OnlineCheckScopeTest`、`SessionCreationScopeTest`、`EventTranslationScopeTest`
- `ExtParametersIntegrationTest`、`SkillMessageControllerTest`、`AssistantInfoServiceTest`
- `InboundProcessingServiceTest`、`GatewayMessageRouterImPushTest`

---

## 验收标准

- [ ] 上游 JSON 含 `data.businessTag` → `AssistantInfo.businessTag` 正确填充（bug 回归）
- [ ] 上游 JSON 不含 `data.hisAppId` 或 `data.appId` → 不再出现读取错误日志
- [ ] 总开关 = `'0'` 时，所有 business → 云端（**包括 businessTag null/blank 也不降级**）
- [ ] 总开关 = `'1'` 且白名单空 → 所有 business → 云端（fail-open）
- [ ] 总开关 = `'1'` 且 tag 命中 → 云端
- [ ] 总开关 = `'1'` 且 tag 不命中 → 本地（PersonalScopeStrategy）
- [ ] 总开关 = `'1'` 且 businessTag null/blank → 本地 + WARN
- [ ] DB / Redis 异常 → fail-open + WARN，不影响业务流
- [ ] `SysConfigService.cacheTtlMinutes` 可由 `application.yml` 调整
- [ ] V11 migration 幂等：在已有相同行的环境再次执行不报错
- [ ] 发布期 Redis 缓存兼容：旧 schema 缓存被 `try-catch` 兜住，不阻塞业务流
- [ ] 受影响的外围测试 mock 全部从 `getStrategy(scope)` 切换到 `getStrategy(info)`
- [ ] lint + checkstyle + 单元 + 集成测试全部通过
- [ ] V11 migration 在 dev / staging 跑通

---

## 风险与未知

### 已知 trade-off：会话内 strategy 漂移（接受）

设计决定**不做** session 级 sticky strategy，每次调用实时判定。这意味着以下场景会发生：

- T0：用户 A 给业务助手发了消息，businessTag=tag-foo 在白名单 → 走 BusinessScopeStrategy + cloud
- T1：运营把 tag-foo 从白名单移除（status=0）
- T2：白名单集合缓存 5 min TTL 过期，重新加载（不含 tag-foo）
- T3：同一会话上的后续消息 → 实时判定结果 = personal → 协议错乱（toolSessionId 是 cloud-* 但 invoke 走 personal）

**业务确认接受**：会话生命周期通常较短（秒级 / 一两分钟），白名单变更频率低，三个条件（长会话 + 跨 TTL + 中途运维改白名单）同时触发的概率可忽略。

**应对**：
- 监控：白名单变更后短期观察 `[SKIP] strategy_build_null` 错误日志、事件翻译错误率
- 缓解：运维改白名单尽量选择业务低峰期，进一步降低长会话受影响概率
- 紧急：若真发生且影响大，把总开关切回 `'0'`（秒级生效）让所有 business 回到云端

### 已知 trade-off：上游 AssistantInfoService 短时不可达

上游 `getAssistantInfo` 返回 null 时（上游故障 / 抖动 / 网络问题），`dispatcher.getStrategy(null)` 直接返回 PersonalScopeStrategy。

- invoke 路径：`GatewayRelayService` 走 personal 本地 `buildInvokeMessage`，**不会失败**
- 但已建立的 business 会话此时被强制切到 personal（与上一条 trade-off 同源——会话内 strategy 漂移）
- 持续时间：不超过 AssistantInfo 缓存 300s TTL（上游恢复后 cache miss 重新拉到正确 info）

### Bug 修复对 `CloudRequestBuilder` 的实际副作用：可忽略

`CloudRequestBuilder.buildCloudRequest(appId, ctx):42` 把 appId 当作 `sys_config[cloud_request_strategy, <appId>]` 的 `config_key`，用于查找按业务方定制的 cloud request 构建策略：

```java
String strategyName = sysConfigService.getValue("cloud_request_strategy", appId);
CloudRequestStrategy strategy = resolveStrategy(strategyName, appId);
//   strategyName == null            → fallback default（无日志）
//   strategy lookup miss            → fallback default + WARN
//   strategy lookup hit             → 使用对应 strategy
```

**关键事实**：仓库内 `CloudRequestStrategy` 接口当前**只有 `DefaultCloudRequestStrategy` 一个实现**。

修 bug 前后行为对照：

| sys_config 状态 | 修 bug 前 | 修 bug 后 | payload 行为 | 日志变化 |
|---|---|---|---|---|
| 无相关条目 | null → default | null → default | **不变** | 无 |
| `('<tag>','default')` 命中 | null → default | hit → default | **不变** | 无 |
| `('<tag>','foobar')` 命中（非 default 名） | null → default | hit → strategyMap miss → default | **不变** | 多一条 WARN |
| `('<tag>','default')` 不命中 | null → default | null → default | **不变** | 无 |

**结论**：仓库当前无第二个实现，修 bug 对 cloudRequest payload 与云端调用行为**无变化**。最坏情况是某些死配指向不存在的 strategy 名时多一条 WARN 日志（有用信号）。

### ai-gateway 端不受影响（已确认）

- `CloudAgentService.handleInvoke:56-77` 把 `payload.cloudRequest` 当作**不透明 JSON** 原样转发给云端
- ai-gateway 端用于 `X-App-Id` header 和 SOA/APIG 鉴权的 `appId` 来自 **gateway 自己的** `CloudRouteService.getRouteInfo(ak)`（独立调上游接口），跟 skill-server 完全独立
- `BusinessScopeStrategy` 构建的 `CloudRequestContext` 字段中**没有 appId**，因此 `cloudRequest` payload 里**根本不存在 appId 字段**

### 上游字段假设
- 假设：上游 AssistantInfoService 接口稳定返回 `data.businessTag`
- 验证方式：合入前抓真实流量样本验证，或与上游接口提供方确认契约

---

## 范围之外（YAGNI）

以下需求本次**不做**，未来如有诉求再单独立项：

- 灰度比例（如 30% business 走云端）—— 当前是布尔白名单，无比例
- 反向黑名单（业务方默认走云端，仅指定 tag 走本地）
- 多维度白名单（按 ak / userId / 区域）
- 白名单集合主动 evict（增删 tag 5min 内生效，不主动 evict）
- 上游接口字段层面的兼容（不读 `hisAppId`，因上游从未返回该字段）
- 配置变更审计日志（依赖现有 `SysConfigController` 的标准日志）
- **会话级 sticky strategy（持久化 effective_scope / business_tag 到 SkillSession）** —— 评估后认为业务场景下"会话内 strategy 漂移"风险可接受，sticky 设计成本超过收益。如果未来发生事故或业务场景变化（长会话普遍化），可在那时单独立项加 V12 schema、StrategyResolution 二元组、session-first 懒迁移等
- **`PersonalScopeStrategy.buildInvoke` 完整化** —— 当前是 placeholder 返回 null，真正逻辑在 `GatewayRelayService.buildInvokeMessage`。本次不重构 invoke 路径架构
