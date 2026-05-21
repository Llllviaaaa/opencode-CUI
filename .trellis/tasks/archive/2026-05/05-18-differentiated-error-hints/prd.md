# 差异化错误提示（未配置 / 已配置离线 / 类型已知 / 类型未知）

> v3：吸收 codex 两轮评审反馈（v1 Critical=0/Major=6/Minor=2，v2 Critical=2/Major=5/Minor=3）。本版收口全部 10 项 v2 issue。

## Goal

把当前"agent 离线 = 一条固定文案"的单一提示，按「机器人是否配置过 / 当前是否在线 / Agent 类型是否已知」三维拆成四档差异化文案。返回路径完全复用现有 sys_message + WebSocket error 流，不改前端渲染。

## Flowchart（用户原始需求）

```
用户发消息 → 查机器人配置状态
  ├─ 未配置（agent_connection 表无任何 AK 记录）→ "未配置"文案（markdown 含链接）
  └─ 已配置 → 查 Agent 连接状态
       ├─ 在线 → 正常处理（不变）
       └─ 离线 → 取最近活跃 toolType（last_seen_at DESC, id DESC）
            ├─ 类型已知（sys_config 命中 tool_type:<toolType>）→ 该类型差异化文案
            └─ 类型未知（命中失败 / toolType 缺失）→ 默认文案（message key）
```

## Offline 覆盖矩阵

| # | 文件 | 位置 | 触发 | 改造 |
|---|---|---|---|---|
| 1 | `SkillMessageController.routeToGateway` | line 191-205 | miniapp 普通消息 | 接 AvailabilityService，保留持久化+WS广播 |
| 2 | `SkillMessageController.replyPermission` | line 437-444 | miniapp 回权限审批 | 接 AvailabilityService，保留 HTTP 503 返回 |
| 3 | `InboundProcessingService.checkAgentOnline` | line 596-614 | 外部 IM 入站：chat/question_reply/permission_reply/rebuild **4 条 action**（line 152/412/476/540 入）| 接 AvailabilityService，保留 InboundResult+handleAgentOffline |
| 4 | `InboundProcessingService.handleAgentOffline` | line 620-639 | 上面 #3 副作用 | 接受 caller 传入的 message，不再二次取文案 |
| 5 | `GatewayMessageRouter.handleAgentOffline/Online` | line 797-820 | gateway 主动推 agent_online/offline 状态事件 | **不出文案**（状态事件由前端按 status 渲染）；但要**调 AvailabilityService.evict(ak)** 主动失效缓存 |
| 6 | `AgentWebSocketHandler` 离线/上线广播 | line 301-303/400-403 | gateway 侧 WS 断连/上线 | 同 #5，触发 evict |
| 7 | `GatewayRelayService.sendInvokeToGateway` 无 WS / 发送失败 | line 130-142 | online 判定后下发链路失败 | **本期 out-of-scope**（决策见 D7），明确接受现有静默失败 |

## Requirements

### R1 数据源 & 一次查询

- gateway 新增内部接口（**方向：skill-server → gateway**）：
  ```
  GET /internal/agent/availability?ak=xxx
  Authorization: Bearer <internal-token>
  → 总是 HTTP 200，业务 200 body：
    {
      "exists":   true|false,        // agent_connection 表是否有任何记录
      "online":   true|false,        // 是否存在 ONLINE 且活跃的连接
      "latestToolType": "opencode"|null,  // last_seen_at DESC, id DESC 第一行
      "lastSeenAt": "2026-05-18T10:00:00Z"|null
    }
  → 仅 5xx/解析失败/超时算"查询异常"
  ```
- gateway repository 拆三个**职责单一**查询（消除 v2 命名歧义）：
  - `existsByAkId(akId)` → boolean（任意 status）
  - `existsOnlineActiveByAkId(akId)` → boolean
  - `findLatestByAkIdOrderByLastSeenAtDescIdDesc(akId)` → AgentConnection（**所有 status**，按 `ORDER BY COALESCE(last_seen_at, created_at) DESC, id DESC LIMIT 1`，兼容历史 last_seen_at null 数据）
- 新增索引（**gateway 模块**，不放 skill-server）：`agent_connection (ak_id, last_seen_at DESC, id DESC)`。

### R2 `AssistantAvailabilityService` 单入口 + 主动 evict

- 方法：`AvailabilityResult resolve(String ak)` + `void evict(String ak)`。
- **缓存**：Redis key `ss:availability:{ak}`，TTL=30s 兜底；正常路径靠主动 evict。
- **主动失效**：在 #5/#6 的 4 个事件入口（skill-server 端 `GatewayMessageRouter.handleAgentOnline/Offline` + gateway 端 `AgentWebSocketHandler` 上/下线广播）调 `evict(ak)`，**先删缓存再广播**，杜绝 30s 黑窗口。
- 返回结构：
  ```java
  record AvailabilityResult(
      boolean online,         // caller 据此分流
      String  message,        // 仅 offline 时有值，调用方持久化+广播复用此值
      String  toolType,       // 日志/埋点
      Source  source          // ONLINE / OFFLINE_TYPED / OFFLINE_DEFAULT / NOT_CONFIGURED / FALLBACK_ERROR
  )
  ```
- **Redis 异常处理（v2 Critical 1 修正）**：缓存读/写/删失败一律当 cache miss 或 best-effort，**不改变业务判定**；只有 gateway 5xx/超时/解析失败才进 FALLBACK_ERROR。
- 文案产生时**只取一次** `sysConfigService.getValue`，结果随 `AvailabilityResult` 一路用到底，保证持久化与广播文案一致。

### R3 文案存储 sys_config + TEXT + 全缓存清理

- key 设计（type=`assistant_offline`）：
  - `not_configured` → 未配置文案
  - `tool_type:<toolType>` → 类型已知（toolType `trim().toLowerCase()`，空白拒绝）
  - `message` → 类型未知兜底（保留现有 key 向后兼容）
- **schema 迁移**：`sys_config.config_value VARCHAR(512) → TEXT`。
- **运维脚本**必须**同时清两个 namespace**：
  - `ss:config:assistant_offline:*`（SysConfigService 缓存，**真实 TTL 是 5 分钟**，不是 30s）
  - `ss:availability:*`（AvailabilityService 缓存）

### R4 Fallback Matrix（v2 Critical 1 + Minor 3 修正）

| 输入条件 | source | 文案来源 |
|---|---|---|
| `exists=false` | NOT_CONFIGURED | `sys_config(assistant_offline, not_configured)` ① |
| `exists=true, online=false, toolType` 非空 + `tool_type:<x>` 命中且非空 | OFFLINE_TYPED | `sys_config(assistant_offline, tool_type:<x>)` |
| `exists=true, online=false`, 其余 | OFFLINE_DEFAULT | `sys_config(assistant_offline, message)` ② |
| `exists=true, online=true` | ONLINE | 不出文案 |
| gateway HTTP 5xx / 超时 / 解析失败 | FALLBACK_ERROR | 硬编码 `DEFAULT_OFFLINE_MESSAGE` |
| **Redis 异常** | **不变更 source**，按 gateway 实际响应判定 | — |

key 取空回退规则（展开 v2 Minor 3）：
- ① `not_configured` blank/miss → 退到 `message` → 仍 blank/miss → 硬编码 default
- `tool_type:<x>` blank/miss → 退到 `message` → 仍 blank/miss → 硬编码 default
- ② `message` blank/miss → 硬编码 default

### R5 Caller 改造（按入口分别给）

**#1 `routeToGateway`**（持久化 + WS 广播 同文案）：
```diff
- AgentSummary agent = gatewayApiClient.getAgentByAk(session.getAk());
- if (agent == null) {
-     messageService.saveSystemMessage(numericSessionId, offlineMessageProvider.get());
-     gatewayRelayService.publishProtocolMessage(sessionId,
-         StreamMessage.builder().type(ERROR).error(offlineMessageProvider.get()).build());
-     return;
- }
+ AvailabilityResult r = availabilityService.resolve(session.getAk());
+ if (!r.online()) {
+     messageService.saveSystemMessage(numericSessionId, r.message());
+     gatewayRelayService.publishProtocolMessage(sessionId,
+         StreamMessage.builder().type(ERROR).error(r.message()).build());
+     return;
+ }
```

**#2 `replyPermission`**（HTTP 503，**不持久化、不 WS**——原行为照保留）：
```diff
- if (agent == null) {
-     return ResponseEntity.ok(ApiResponse.error(503, offlineMessageProvider.get()));
- }
+ AvailabilityResult r = availabilityService.resolve(session.getAk());
+ if (!r.online()) {
+     return ResponseEntity.ok(ApiResponse.error(503, r.message()));
+ }
```

**#3 `checkAgentOnline` + #4 `handleAgentOffline`**（InboundResult + emitter + direct session 持久化）：
```diff
- if (gatewayApiClient.getAgentByAk(ak) != null) return null;
- handleAgentOffline(domain, sessionType, sessionId, ak, assistantAccount);
- return InboundResult.error(503, offlineMessageProvider.get(), sessionId, ...);
+ AvailabilityResult r = availabilityService.resolve(ak);
+ if (r.online()) return null;
+ handleAgentOffline(domain, sessionType, sessionId, ak, assistantAccount, r.message());  // 多传 message
+ return InboundResult.error(503, r.message(), sessionId, ...);

// handleAgentOffline 签名增加 String offlineMessage 参数，删除内部 offlineMessageProvider.get() 调用
```

### R6 鉴权全覆盖（v2 Major 修正）

- 抽 `InternalAuthProperties`（两端各一份配置）。
- **启动期校验**：token 为空 / 等于 `changeme` → 直接 `IllegalStateException` 启动失败。
- **保护范围**：不只新接口，已有 `/api/gateway/agents`、`/agents/status` 一并接 InternalAuth filter（清理 `changeme` 默认值）。

## Acceptance Criteria

- [ ] AK 在 `agent_connection` 表无任何记录 → NOT_CONFIGURED 文案。
- [ ] AK 有记录 + offline + `toolType=opencode` + `tool_type:opencode` 已配置 → OFFLINE_TYPED 文案。
- [ ] AK 有记录 + offline + toolType=未配置类型 → OFFLINE_DEFAULT。
- [ ] AK 有记录 + offline + toolType=null → OFFLINE_DEFAULT。
- [ ] AK 有多条记录，按 `last_seen_at DESC, id DESC` 取最新；`last_seen_at` 相同时用 `id DESC` 决胜。
- [ ] AK 部分历史记录 `last_seen_at` 为 null，能用 `COALESCE(last_seen_at, created_at)` 兜底。
- [ ] AK 在线 → 走原正常路径，无回归。
- [ ] **Redis 读失败 + gateway online ⇒ ONLINE**（不误判离线）。
- [ ] **Redis 写/删失败 + gateway online ⇒ ONLINE**（best-effort）。
- [ ] gateway 5xx / 超时 / 解析失败 → FALLBACK_ERROR + 硬编码默认文案。
- [ ] **gateway agent_online/offline 事件触发 evict(ak)**：先 evict 后广播；offline→立刻 online 无 30s 误判，online→立刻 offline 无放行。
- [ ] **token 未配 / =`changeme`** → 服务**启动失败**（不只是新接口，已有 `/api/gateway/agents`、`/agents/status` 也校验）。
- [ ] 同次调用 `sys_message` 持久化 与 WS `error` 广播文案**完全一致**（同一 `r.message()` 实例）。
- [ ] sys_config `not_configured` blank → 退 `message`；`message` 也 blank → 硬编码。
- [ ] sys_config `tool_type:opencode` blank → 退 `message`；`message` blank → 硬编码。
- [ ] sys_config value 长 5KB markdown 全量存取无截断。
- [ ] 4 条 inbound action（chat/question_reply/permission_reply/rebuild）× 4 档（NOT_CONFIGURED / OFFLINE_TYPED / OFFLINE_DEFAULT / ONLINE）全覆盖。
- [ ] 运维脚本同时清 `ss:config:assistant_offline:*` 和 `ss:availability:*`；执行后 SQL 改文案立即生效（不等 5 分钟）。

## Definition of Done

- 单测：
  - `AssistantAvailabilityServiceTest`：5 个 source 分支 + 缓存命中/miss + Redis 读/写/删异常降级 + gateway 异常 → FALLBACK_ERROR + evict 行为。
  - `AssistantOfflineMessageProviderTest`（重构后）：保留 + 新增 typed/not_configured/blank 回退矩阵。
  - `SkillMessageControllerTest`：route 4 档 + replyPermission 4 档（HTTP 503 路径，无持久化/无 WS）。
  - `InboundProcessingServiceTest`：4 inbound action × 4 档 + handleAgentOffline 接受 message 参数的回归。
  - `AgentAvailabilityControllerTest`（gateway 端）：401 鉴权 / token=changeme 启动失败 / exists=false / offline+typed / offline+toolType=null / online 契约。
  - `AgentConnectionRepositoryTest`：last_seen_at DESC + id DESC + COALESCE 兜底。
  - 事件 evict 集成测试：GatewayMessageRouter handleAgentOnline/Offline 调 evict。
  - 迁移测试：sys_config 5KB markdown 写入读出。
- Lint / typecheck / CI 通过。
- 更新 `documents/protocol/v3/02-skillserver-gateway.md`（新增 `/internal/agent/availability` 段）。
- 迁移脚本编号（**已校验真实仓库**）：
  - skill-server: `V13__alter_sys_config_value_text.sql`、`V14__seed_assistant_offline_defaults.sql`
  - ai-gateway: `V6__agent_connection_ak_last_seen_index.sql`
- 运维脚本：`scripts/cache/clear-assistant-offline-cache.sh`（清两个 namespace）。
- 回滚预案：删除 sys_config 三 key → 全部回退 `message` 单条 = 当前行为；接口异常已天然兜底；evict 失败不影响业务路径。

## Out of Scope

- 不改 miniapp 渲染（error 字段照旧）。
- 不引入运营管理后台。
- 不改 gateway agent 注册 / 心跳 / 离线检测逻辑。
- 不为 #5/#6 状态广播事件做文案差异化。
- **D7 不修 #7 `GatewayRelayService.sendInvokeToGateway` 的静默失败**（在线判定后下发链路失败）：现有行为是 log + return，本期沿用；后续如要差异化，独立任务接管。
- 不做 toolType 别名映射。

## Decision (ADR-lite)

- **Context**：v1 / v2 PRD 经两轮 codex 评审暴露双读竞态、排序字段、字段长度、缓存失效、Redis 异常误判、migration 编号、token 默认值、命名歧义等 16 项 issue。
- **Decision**：
  1. 单 service 单查询单文案（`AvailabilityService`），**Redis 是 best-effort 缓存**不参与业务判定；
  2. 缓存通过已有 agent online/offline 事件**主动 evict**，TTL 30s 仅兜底；
  3. gateway repository 拆三个职责单一查询，使用 `COALESCE(last_seen_at, created_at) DESC, id DESC LIMIT 1`；
  4. 文案存 sys_config TEXT，运维脚本清 `ss:config:assistant_offline:*` + `ss:availability:*`；
  5. Fallback Matrix 区分「业务分支 NOT_CONFIGURED」「类型未知 OFFLINE_DEFAULT」「系统异常 FALLBACK_ERROR」+ 详尽 blank 回退链；
  6. 鉴权抽 `InternalAuthProperties`，全 internal 接口生效，启动期校验非 `changeme`；
  7. **D7：在线后下发失败的静默路径本期 out-of-scope**，独立任务跟进。
- **Consequences**：
  - 收益：无竞态、无 Redis 误判、状态实时（事件驱动 evict）、文案运维可控、回滚零成本。
  - 代价：gateway 多一接口 + 一个索引；skill-server 多一次 HTTP 查询（缓存命中后接近零成本）；sys_config schema 升 TEXT；两边 token 配置强制。
  - 后续可扩展：差异化 key 加 scope 维度；D7 入下一期。

## Technical Notes

### 涉及文件（核对真实仓库）

**skill-server**（已确认 migration 已到 V12，下个 V13/V14）
- `service/AssistantAvailabilityService.java`（新，含 evict）
- `model/AvailabilityResult.java` / `AvailabilitySource.java`（新）
- `service/AssistantOfflineMessageProvider.java`（重构为内部 message 计算 helper，被 AvailabilityService 调）
- `service/GatewayMessageRouter.java`（line 797-820 加 evict 调用）
- `service/InboundProcessingService.java`（line 596-639 改造 + handleAgentOffline 签名加参数）
- `controller/SkillMessageController.java`（line 191-205, 437-444 改造）
- `service/GatewayApiClient.java`（新增 `getAvailability`，清理 `changeme`）
- `config/InternalAuthProperties.java`（新，启动期校验）
- 测试：以上各对应 test 类

**ai-gateway**（已确认 migration 到 V5，下个 V6）
- `controller/AgentController.java`（新增 `/internal/agent/availability`，清理 `changeme`）
- `service/AgentRegistryService.java`（新增 `queryAvailability`）
- `repository/AgentConnectionRepository.java` + `mapper/AgentConnectionMapper.xml`（新 3 个查询）
- `ws/AgentWebSocketHandler.java`（line 301-303 / 400-403 加 evict 触发或事件透传）
- `config/InternalAuthProperties.java`（新）

**db migration**
- `skill-server/.../V13__alter_sys_config_value_text.sql`
- `skill-server/.../V14__seed_assistant_offline_defaults.sql`
- `ai-gateway/.../V6__agent_connection_ak_last_seen_index.sql`

**docs**
- `documents/protocol/v3/02-skillserver-gateway.md`（新增 availability 协议段）

**ops**
- `scripts/cache/clear-assistant-offline-cache.sh`（清两 namespace）

### 关键约束

- markdown 文案 ≤ 4KB 建议；TEXT 字段无硬限制。
- Availability 缓存 TTL 30s 仅兜底，正常路径靠 evict。
- SysConfigService 现有缓存 TTL 5 分钟，运维脚本必须清。
- token 强校验 fail-fast。
- toolType 规范化 `trim().toLowerCase()`，空白拒绝（视同 toolType=null）。

## Review History

- **v1 (2026-05-18) codex round 1 (gpt-5.5 xhigh)**：GO with fixes，C=0/M=6/m=2。
- **v2 (2026-05-18) codex round 2**：GO with fixes，C=2/M=5/m=3，残留率约 25%。
- **v3 (2026-05-18)**：本版全部收口。
  - Critical：Redis 异常不进 FALLBACK_ERROR + migration 编号修正 V13/V14、V6。
  - Major：事件驱动 evict、SysConfig TTL 真实值澄清 + 运维清两 namespace、repository 拆三查询、token 抽 InternalAuth 全覆盖、#7 显式 out-of-scope（D7）。
  - Minor：R5 按入口分别给 diff、AC 补 Redis/token/blank/multi-record 等负例、Fallback blank 回退链详写。
