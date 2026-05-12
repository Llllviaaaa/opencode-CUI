# 代码路径审计：白名单不命中 vs SS 自建 toolSessionId

## 总规则（用户期望）

业务助理（scope=="business"）+ 白名单不命中 → **完全走 personal 创建/重建流程**：
- toolSessionId 必须由 GW(opencode) 通过 `session_created` 回调返回
- 不应出现 `BusinessScopeStrategy.generateToolSessionId()` 调用（即不应生成 `cloud-XXX`）

唯一会生成 `cloud-XXX` 的地方：`BusinessScopeStrategy.generateToolSessionId()`（.../scope/BusinessScopeStrategy.java:141）。

---

## 所有 generateToolSessionId() 调用点（白名单 gate 检查）

| # | 文件:行 | 路径名 | 是否走 dispatcher.getStrategy(info)（含白名单 gate） | 风险 |
|---|---------|-------|------------------------------------------------|------|
| 1 | SkillSessionController.java:117 | miniapp 创建会话 | ✅ 走 | 看似 OK |
| 2 | ImSessionManager.java:143 | IM 异步创建会话 | ✅ 走 | 看似 OK |
| 3 | InboundProcessingService.java:181 | IM/external 情况 B（toolSessionId 缺失自愈） | ✅ 走 | 看似 OK |
| 4 | InboundProcessingService.java:228 | IM 情况 C（appendToPending 判定） | ✅ 走 | 不实际生成 ID |
| 5 | InboundProcessingService.java:263 | self-heal 锁内补 ID | ✅ 走 | 看似 OK |
| 6 | InboundProcessingService.java:547 | processRebuild 业务助理重建 | ✅ 走 | 看似 OK |

→ **代码层面所有 generateToolSessionId() 入口都过白名单 gate，理论上 dispatcher 切到 personal 后不会再生成 cloud-XXX**。

---

## 但仍可能让"业务助理 SS 自建流程"被走的疑似 bug 路径

### 路径 A：存量数据 — DB 里已有 `cloud-XXX`，dispatchChatToGateway 直接转发
- 文件：`InboundProcessingService.dispatchChatToGateway`（line 320-370）
- 触发：toolSessionId 已存（之前白名单命中或未启用时），现在白名单不命中
- 现状：**直接读 `session.getToolSessionId()` 转发**，不重新校验是否匹配当前 strategy
- 影响：cloud-XXX 透传到 GW → GW personal 路径 → opencode 找不到 → 失败

### 路径 B：existing session + requestToolSession 没考虑 strategy
- 文件：`ImSessionManager.createSessionAsync` line 117-126
- 现状：`existing != null` 时直接 `requestToolSession(existing, pendingMessage)`
- `requestToolSession` → `SessionRebuildService.rebuildToolSession` → **直接发 GW create_session**（不分 strategy）
- 影响：业务助理在白名单内时，应该走本地 BusinessScopeStrategy 生成 cloud-XXX，但被强制走了 GW personal 路径

### 路径 C：SkillMessageController.routeToGateway 的 rebuild 分支
- 文件：`SkillMessageController.java:200-206`
- 现状：`session.toolSessionId == null` → `gatewayRelayService.rebuildToolSession(...)`（**不分 strategy 直接发 GW create_session**）
- 影响：业务助理（白名单内）的会话如果 toolSessionId 被清空（rebuild exhausted 等），就会被错误地走 GW personal 重建路径

### 路径 D：SessionRebuildService.rebuildToolSession 本身不分 strategy
- 文件：`SessionRebuildService.java:86-141`
- 现状：固定发 `GatewayActions.CREATE_SESSION`，**完全没用 dispatcher**
- 影响：所有调用 rebuild 的路径，对业务助理都是错的（业务助理应该本地预生成 cloud-XXX）

### 路径 E：processQuestionReply / processPermissionReply 直接转发存量 toolSessionId
- 文件：`InboundProcessingService.java:415-433` 和 `479-510`
- 文件：`SkillMessageController.java:209-234` 也有类似
- 现状：直接读 `session.getToolSessionId()` 转发到 GW
- 影响：与路径 A 同源 — 存量 cloud-XXX 透传

### 路径 F：GatewayMessageRouter.routeAssistantMessage 用 getCachedScope（不走白名单 gate）
- 文件：`GatewayMessageRouter.java:653-654`
- 现状：用 `assistantInfoService.getCachedScope(sessionAk)` 而非 dispatcher
- 影响：业务助理被踢出白名单后，事件过滤逻辑仍按 business 判，可能误过滤 OpenCode 事件

---

## 可能的具体 bug 假说

### 假说 1（最贴近你的描述）：路径 D 是核心
- 现状：SessionRebuildService.rebuildToolSession 不分 strategy，所有 rebuild 都走 GW create_session
- 你的话："会话重建流程也应该走个人助理流程" → 但**所有 rebuild 实际上都已经在走个人助理流程了**（因为代码硬编码 create_session）
- **反向问题**：业务助理（白名单内）的 rebuild **本应**走 `BusinessScopeStrategy.generateToolSessionId()` 本地生成新 cloud-XXX，但代码当成 personal 走了？

### 假说 2：路径 A + B 联合 — IM 端会话延续旧 cloud-XXX
- T1：助理在白名单内 → IM 进入第一条消息 → createSessionAsync → cloud-XXX 落 DB
- T2：助理被踢出白名单
- T3：IM 进入第二条消息 → 情况 C → dispatchChatToGateway 用旧 cloud-XXX → GW personal → opencode 失败

### 假说 3：路径 D 反向 — 业务助理 rebuild 走错
- 业务助理（白名单内）的会话因 context overflow 触发 rebuild
- SessionRebuildService 不区分 strategy → 发 GW create_session
- GW 收到 → 走 personal 创建 OpenCode session → 回 session_created（toolSessionId 是 OpenCode 真实 ID）
- 这反而是 personal ID，不是 cloud-XXX，与你描述的"SS 自建" 不一致

---

## 我现在最大不确定点

你说的"SS 自建 toolSessionId 流程"具体指：
1. **`BusinessScopeStrategy.generateToolSessionId()` 生成 `cloud-XXX`** 这一步（最常见解读）
2. 还是 **任何由 SS 控制的 toolSessionId 流转**（包括 SessionRebuildService 这种全局逻辑）
3. 你看到的具体场景是 **路径 A（消息转发）/B（会话创建）/D（重建）/其它**？
