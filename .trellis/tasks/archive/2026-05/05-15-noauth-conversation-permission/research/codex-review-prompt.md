你是一位资深 Java / Spring Boot 后端架构师。请对下面这份 PRD 做一次**严格的设计评审**——不是鼓掌，是找问题。

## 背景

`opencode-CUI` 是一个 IM 助手平台，三层架构：

- **skill-miniapp**：React 前端
- **skill-server**：Spring Boot 后端（本任务主要修改对象）
- **ai-gateway**：Spring Boot 网关，与 PCAgent (WebSocket) 通信

skill-server 当前对外的通道：

- **miniapp 通道**（`/api/skill/sessions/*`）：cookie userId 鉴权，C 端入口。
- **external 通道**（`/api/external/invoke`）：Bearer token 鉴权，业务后端入口，4 个 action。
- **IM 入站**（`/api/inbound/messages`）：IM 平台回调。

`SkillSession` 是核心持久化对象，关键字段：`id`, `userId`, `ak`, `assistantAccount`, `toolSessionId`, `businessSessionDomain`, `businessSessionType`, `businessSessionId`, `status`。

`AssistantAccountResolverService.resolve(assistantAccount)` 远端解析得到 `(ak, ownerWelinkId)`，支持 EXISTS / NOT_EXISTS / UNKNOWN 三态。

`AssistantInfoService.getAssistantInfo(ak)` 返回 `AssistantInfo`，含 `getScope()`（"business"/"personal"）与 `getBusinessTag()`。

`BusinessScopeStrategy` 处理 business scope assistant 的 invoke 构建，`buildInvoke` 内部从 payload 取 `assistantAccount`、`sendUserAccount`、`toolSessionId` 等组装云端 HTTP 请求；`generateToolSessionId()` 用 Snowflake 预生成。

`AssistantSquareCloudRequestStrategy.build()` 对 `assistantAccount` 与 `sendUserAccount` blank 都会抛 `IllegalArgumentException`。

`SysConfigService` 是现有的 sys_config 表抽象层，提供 Redis 缓存 + pub-sub invalidate + DB 降级；外部已经在用 `assistant_offline:message` 这种 type:key 格式。

`SkillSessionController.createSession`：当前 ak / assistantAccount 均可选；`AssistantAccountResolverService.isSkipOnNullAssistantAccount()` 决定 null assistantAccount 是放行还是 400。

`SkillMessageController.sendMessage`：`routeToGateway` 首句 `if (session.getAk() == null) return;` → 没有 AI 响应。`SkillMessageController.replyPermission`：`if (session.getAk() == null) return 400`。

## 待评审 PRD

请读：`.trellis/tasks/05-15-noauth-conversation-permission/prd.md`

它包含 D1-D9 共 9 条决策、A/B/C/D 四块 AC、3 个 PR 的 Implementation Plan。

## 待 ground-truth 的代码文件

你的评审必须基于真实代码，**不要凭空臆测**。请用 Read 或 grep 工具实际读这些文件：

- `skill-server/src/main/java/com/opencode/cui/skill/controller/SkillSessionController.java`
- `skill-server/src/main/java/com/opencode/cui/skill/controller/SkillMessageController.java`
- `skill-server/src/main/java/com/opencode/cui/skill/service/AssistantAccountResolverService.java`
- `skill-server/src/main/java/com/opencode/cui/skill/service/SkillSessionService.java`
- `skill-server/src/main/java/com/opencode/cui/skill/service/SessionAccessControlService.java`
- `skill-server/src/main/java/com/opencode/cui/skill/service/scope/BusinessScopeStrategy.java`
- `skill-server/src/main/java/com/opencode/cui/skill/service/scope/AssistantScopeDispatcher.java`
- `skill-server/src/main/java/com/opencode/cui/skill/service/cloud/AssistantSquareCloudRequestStrategy.java`
- `skill-server/src/main/java/com/opencode/cui/skill/service/SysConfigService.java`（结构 + 缓存机制 + pub-sub channel 名）
- `skill-server/src/main/java/com/opencode/cui/skill/service/AssistantInfoService.java`
- `skill-server/src/main/resources/mapper/SkillSessionMapper.xml`
- 现有的协议规范：`docs/superpowers/specs/2026-05-12-miniapp-skill-server-protocol.md`

## 评审维度（请逐一覆盖）

### 1. D1-D9 决策审视

每条决策有没有逻辑漏洞？决策之间是否自洽？特别关注：

- **D2 + D4 + tripwire**：规则里写死 `businessTag` 与 `AssistantInfoService.getBusinessTag(ak)` 做对比。如果运营在 sys_config 里改了规则的 businessTag，但 AssistantInfoService 那边的资产源没改，启动校验会失败把规则丢掉——这个 fail-fast 的失败模式是否反而比"运行时仍能跑"更糟？
- **D6（创建时回写）+ D9（补绑接口）共存**：D6 承诺"规则改动不影响老 session"。D9 又允许"老 session 通过 bind 接口"获得当前规则。这两个承诺在什么场景下会冲突或令用户困惑？
- **D8 + D9**：D8 说"domain / type 任一为空则跳过规则"；D9 bind 接口看 `session.businessSessionDomain` / `Type`。如果 session 创建时 domain / type 是空的，后面想 bind 是不是永远无路可走？

### 2. AC 覆盖完整性

A/B/C/D 四块 AC 有没有遗漏的失败场景、并发竞态、边界条件？特别检查：

- **并发**：两个请求同时调 createSession，命中同一规则，是否有 sys_config UPDATE 的中间态被读到？
- **TOCTOU**：bind 接口 check `session.ak == null` 之后、UPDATE 之前，另一个请求把 ak 写入了，bind 还是会覆盖？
- **toolSessionId 预生成**：AC §D 说 bind 后 toolSessionId 也补上。如果 session 之前由别的路径已经预生成了 toolSessionId（即使 ak 还是 null），bind 是覆盖还是保留？AC 没说。

### 3. 跨层一致性 / fail-fast 联动

- `AssistantSquareCloudRequestStrategy.build()` 对 blank `assistantAccount` 抛 IAE——D2 决策"规则里 assistantAccount 非空"在哪条具体调用链上保证这个 invariant？如果某条边界条件（例如 sys_config 行存在但 value JSON 缺 `assistantAccount` 字段）使得规则缓存里塞了一条 `(ak, null, businessTag)`，最终在云端 HTTP 调用时炸 IAE，是不是只有运行时才发现？
- D4 假设默认助手必为 business scope，但实际代码里 personal scope 也可能误配 ak — 启动校验 + 规则丢弃后，调用方仍然走"裸创建空壳"老路径，但**调用方不知道是规则被丢了**。是否需要告警？

### 4. 并发与时序

- **startup race**：`ApplicationReadyEvent` 加载规则前，如果有请求进来（HTTP 端口已 ready），lookup 返回 empty → 调用走错路径。是否需要 service ready barrier？
- **pub-sub invalidate**：Redis pub-sub 是 fire-and-forget。两实例同时收到事件做 reload，sys_config 又被第二次改了——会不会出现一个实例 lag 一个 generation？
- **lookup 与 reload 的可见性**：reload 时是先 clear cache 再 put，还是构建完新 map 再原子替换？前者会有窗口 lookup miss。

### 5. 回滚 / 降级

- sys_config 整个挂了（DB 不可达 + Redis 不可达）：service 启动时 fail-fast 还是 fail-open？现有 SysConfigService 有 DB 降级直查机制——本任务的规则加载是否复用？
- 规则配错（如 ak 是错号）+ 运营压力下要快速回滚——把那行 status=0 即可还是要做别的？

### 6. 测试盲区

除了 AC 列的项，至少还有哪些场景应当测但被遗漏？特别看：

- 规则中 `compositeKey(domain, type)` 是否对前后空格 / 大小写做归一？文档说"大小写敏感"——但 trim 是否做？test 是否覆盖？
- bind 接口对 closed session 拒绝（AC §D 第 4 条）——是否也覆盖 session.status == ABORTED 等其他非 ACTIVE 状态？

### 7. 更优替代方案

- 把规则放 `sys_config` 单行 JSON 真比新建 `default_assistant_rule` mapper 表好吗？schema 改动一次性的成本 vs 长期复合键查表的成本？
- D9 的 bind 接口设计是否过度保守？"已经有 ak 一律 409" 会让运营手动场景僵硬；是否应该提供一个明确的 `--force` 模式？
- D6 强约束"老 session 不被规则回写"——是否会与 D9 形成"两条令人困惑的语义"？是否应统一为"规则改变只对新 session 生效，老 session 不变；运营想换助手只能通过显式 bind"？

## 输出格式

请用 Markdown，结构：

```markdown
# Codex 评审 — noauth-conversation-permission PRD

## 摘要（不超过 5 行）
[整体判断 + 风险等级]

## Critical Issues（必须改才能开工）
### C1: [症状]
- **根因**：
- **建议**：
- **影响 AC**：

### C2: ...

## Major Concerns（建议改）
### M1: [症状]
- ...

## Minor / 风格建议
1. ...
2. ...

## 替代方案 / 改良建议
（如果有更简单/更安全的实现路径）

## 总评结论
- 是否可以进入实施？
- 若有 Critical Issues，列出"先做哪几件事再开 PR1"。
```

## 评审纪律

- 所有判断必须**指向具体文件 + 行号**（或具体决策号）。
- 不要重复 PRD 的内容——只写**问题**和**建议**。
- 不要客套——直接说哪里有坑、哪里能更好。
- 用中文输出。
