# Subagent 中台协议（标准化） + OpenClaw 接入设计

> **Date**: 2026-05-11
> **Type**: Bridge-only (服务端零改动)
> **Status**: 待评审
> **核心原则**: 复用现有 V8 协议字段；所有上游差异由 bridge plugin 内部消化；server/miniapp/DB 不动

---

## 1. 需求概述（BA）

### 1.1 用户故事

**As** 使用 AI 助理（CUI）完成复杂任务的最终用户

**I want** 当 AI 把任务**分解给"专门的子智能体"**去做时（如"代码审查 AI"、"长链路检索 AI"），CUI 里能：
- 看到子任务在干啥（逐字流式）
- 响应子任务的权限请求
- 看到子任务最终结果（成功/失败）
- 子任务结束后，主对话气泡说总结

**So that** 复杂任务不再是黑盒进度条干等，我能透明感知 AI 的协作过程、在关键节点干预。同时让 CUI 具备承载多智能体协作的能力，**且未来对接新 AI 平台时不需要重做整条链路**。

### 1.2 主要交互画面（文字版）

**场景 A：主对话派生子任务**
```
[用户] "帮我审 main.py，重点看安全问题"
[AI 主对话气泡] "好，我让代码审查 AI 看看..."
  ↓
  ┌─────────────────────────────────────────┐
  │ 🔍 代码审查 (subagent)         运行中 │
  │   正在分析 main.py 导入...               │
  │   发现可疑模式：subprocess 未转义        │
  │   [permission] 是否执行 grep -r ...     │ ← 卡内 permission
  │     [一次] [一直] [拒绝]                 │
  └─────────────────────────────────────────┘
  ↓ (完成)
  ┌─────────────────────────────────────────┐
  │ 🔍 代码审查 (subagent)         已完成 │
  └─────────────────────────────────────────┘
[AI 主对话气泡] "审完了，3 个问题：1) ... 2) ... 3) ..."
```

**场景 B：嵌套（孙任务）—— 平铺方案**
```
[主对话]
  ┌ 🔧 实现 (subagent)          运行中 ┐
  └────────────────────────────────────┘
  ┌ 📐 设计 - 由"实现"派生 (subagent)  运行中 ┐  ← subagentName 路径化表达层级
  └──────────────────────────────────────────┘
```

> 选择平铺而非缩进：现有 SubtaskBlock 直接复用，零 UI 改动；层级信息靠 `subagentName` 携带（如 `"实现 > 设计"`）。

### 1.3 业务价值

1. **可观察性**：长链路任务透明
2. **可控性**：permission 闭环
3. **复用上游能力**：直接享用 openclaw / opencode 的多智能体编排
4. **标准协议**：本次定下来的协议即未来对接任何 AI 平台的标准——**新平台只动 bridge，server 不动**

---

## 2. 上游 Subagent 实现对比

### 2.1 opencode（已对接）

| 维度 | 实现细节 |
|---|---|
| 派生方式 | 主对话调 Task 工具 → opencode 内部派生子 session |
| 子任务标识 | 独立子 sessionId |
| 父子关联 | 每个 part 事件自带 `subagentSessionId / subagentName` |
| 流式 | 协议原生支持 |
| 生命周期 | 作为 part 流自然结束 |
| permission | part 自带 subagentSessionId |
| 嵌套 | 协议层用 depth/parent，目前未深度验证 |
| 对接难度 | **低**（协议层就给完整上下文） |

### 2.2 openclaw（本次新对接）

| 维度 | 实现细节 |
|---|---|
| 派生方式 | 主对话调 `sessions_spawn` 工具 → 创建独立子会话 |
| 子任务标识 | `childSessionKey = "agent:<id>:subagent:<uuid>"` |
| 父子关联 | `SessionEntry.spawnedBy` + `SubagentRunRecord` 注册表 |
| 子会话独立性 | 独立 sessionFile / model / workspace |
| 事件流 | 统一经 `onAgentEvent`，`evt.sessionKey = childSessionKey` |
| 生命周期 | 状态机：start / end / error / timeout / killed / aborted（含 15s grace） |
| permission | `onAgentEvent stream="approval"`，靠顶层 `evt.sessionKey` 关联（data 不带 sessionID） |
| question | **无此能力** |
| 嵌套 | 原生支持（spawnDepth / spawnedBy 链 / cascade kill） |
| 结果回主对话 | `runSubagentAnnounceFlow` 把结果回注到父 session，触发一次新 agent run |
| 控制工具 | `subagents` 工具 list / kill / steer |
| 清理 | openclaw 内部 cleanup 自治（bridge 不该插手 delete） |
| 对接难度 | **中**（bridge 需做父子图反查 + 状态机归一化） |

### 2.3 关键差异

| 维度 | opencode | openclaw |
|---|---|---|
| 子任务 ID | 子 sessionId 直传 | childSessionKey 需 bridge 反查 |
| 父子关联 | 协议直接 | spawnedBy 字段反查 |
| 生命周期 | 自然 | 完整状态机 |
| permission 路由 | 自带 ID | 顶层 sessionKey |
| 中止/关闭 | bridge 主动 | openclaw 自治 |
| 嵌套 | depth 字段 | sessionStore 链 |
| 用户提问 | 支持 | 不支持 |
| 结果回主对话 | 同 part 流 | announce flow 触发新 run |

---

## 3. 中台 Subagent 标准协议

### 3.1 协议原则

- **服务端零改动**：复用 V8 已有字段 `subagentSessionId + subagentName`；不加 schema、不动 DB、不动 server 代码
- **bridge 全包**：上游差异（嵌套、生命周期、状态机、announce 回注）全部在 bridge plugin 内部消化
- **协议下沉到 part 字段**：part 是否属于子任务、属于哪个子任务、显示什么名字——这三件事就是协议的全部

### 3.2 协议字段（复用 V8）

每个 part 事件可携带的两个 subagent 字段（已存在）：

| 字段 | 类型 | 含义 |
|---|---|---|
| `subagentSessionId` | string? | 子任务唯一标识（空 = 主对话） |
| `subagentName` | string? | 显示名（可路径化，如 `"实现 > 设计"` 表达嵌套） |

**就这两个字段，没了**。

终态用 part 现有字段表达：
- `toolStatus`（pending/running/completed/error）—— 用于工具 part
- `finishReason`（step-finish 现有）—— 用于子任务结束 marker
- `toolError`（现有）—— 用于错误信息

### 3.3 上游 → 中台字段填法

| 中台 | opencode bridge 填法 | openclaw bridge 填法 |
|---|---|---|
| subagentSessionId | 子 sessionId 直传 | childSessionKey 透传 |
| subagentName | child agent name | walkChain 拼接：`<祖先>.label > <父>.label > <self>.label` |
| 终态（completed/error） | part 流自然结束 | bridge 内归一化：openclaw 6 种 phase → `completed`（end/ok）或 `error`（其他都归 error，把原因写进 toolError） |

### 3.4 嵌套表达：不做缩进 UI，用名字路径化

子任务 `A` 又派生孙任务 `B`，bridge 发出来的 part：
- `subagentSessionId = B.childSessionKey`
- `subagentName = "代码审查 > 设计"`

CUI 看到的是两张兄弟卡片，名字本身告诉用户"设计是从代码审查里派生的"。

**好处**：不需要服务端字段、不需要 miniapp 嵌套渲染逻辑，**协议简单到极致**。
**代价**：UI 视觉层级稍弱，但对绝大多数子任务场景够用（90% 场景嵌套不超过 2 层）。

### 3.5 后续 Agent 平台的"自适配清单"

对接新平台 X 时，写一个新 bridge plugin 实现这 4 件事：

1. **识别**：从平台原生事件识别"这条事件属于哪个子任务"（map / 反查 / 协议字段，方式自由）
2. **填字段**：把识别结果填到 `subagentSessionId + subagentName` 两个 part 字段
3. **状态归一**：平台各种结束语义 → `completed` 或 `error`（写 toolError）
4. **下行路由**：收到带 subagentSessionId 的下行 action 时，路由到平台对应 API

server / miniapp / DB **零改动**。

---

## 4. 技术设计

### 4.1 功能实现设计

#### 4.1.1 整体数据流

```
   平台原生事件（开始/字流/工具/permission/结束）
                    │
                    ▼
   ┌──────────────────────────────┐
   │  bridge plugin 内部消化       │
   │  - 识别子任务（反查父链）     │
   │  - 归一化生命周期             │
   │  - 填 subagentSessionId/Name  │
   └──────────────┬───────────────┘
                  │
                  ▼
   现有 part 协议（V8 字段透传）
                  │
                  ▼
   ai-gateway 透传 ←→ skill-server 落库 ←→ miniapp 渲染
                  ↑
       （这三层不需要改动，复用现有 V8 链路）
```

#### 4.1.2 openclaw bridge 关键时序

**派生与首事件**
```
模型 ──tool_call sessions_spawn──→ openclaw
                                      │
                            sessionStore 写 spawnedBy
                                      │
                            callGateway("agent", sessionKey=child)
                                      │
                            emit assistant text event (sessionKey=child)
                                      │
                  ┌───────────────────┴──────────────────┐
                  │     bridge.onAgentEvent              │
                  │                                      │
                  │  evt.sessionKey 不在父表             │
                  │  → walkSpawnedByChain                │
                  │  → 拿到 root toolSessionId + label   │
                  │  → 注入 subagentSessionId/Name       │
                  │  → 发 part 上行                      │
                  └───────────────────┬──────────────────┘
                                      │
              （后续 ai-gateway/server/miniapp 按现状走）
```

**permission**
```
子 agent ──emit approval (stream=approval, evt.sessionKey=child)──→ bridge
                                                                       │
                                       识别为子任务 → 注入 subagentSessionId
                                                                       │
                                       发 permission part 上行（V8 流）
                                                                       │
                                                                       ▼
                                                            CUI 在 SubtaskBlock 内弹卡
                                                                       │
                                                            ←── 用户点击
                                                                       │
                                       permission_reply 下行带 subagentSessionId
                                                                       │
                                       bridge 校验匹配 → 转 openclaw approvalPort
```

**结束**
```
openclaw emit lifecycle:end (aborted? stopReason? error?)
                  │
                  ▼
    bridge 归一化：
      - phase=end + 无 error → 发一条 finishReason=stop / toolStatus=completed part
      - phase=error / aborted / timeout / killed → toolStatus=error + toolError 填原因

    ↓ (15s 后清 bridge 缓存)

openclaw runSubagentAnnounceFlow ──→ 父 sessionKey 上跑新 agent run
                                        │
                              bridge 当主对话事件处理（不识别）
                                        │
                              CUI 主对话气泡冒出"我审完了，问题是..."
```

#### 4.1.3 异常处理

| 异常 | 处理 |
|---|---|
| sessionStore 读失败 | 事件静默丢 + warn 日志，下次重试 |
| walkChain 无限循环 | MAX_GUARD=10 截断 |
| 子任务事件 sessionKey 找不到根 | 静默丢 + 日志 |
| 终止后 late event | 15s 黑名单期内丢 |
| permission_reply ID 不匹配 | tool_error |

### 4.2 接口设计

#### 4.2.1 协议（零变化）

- 上行：现有 ToolEventMessage / part 协议，无新字段
- 下行：现有 InvokeMessage（chat/permission_reply/abort_session/close_session/question_reply）

`subagentSessionId` 已是各 action payload 可选字段（V8 已就绪）。

#### 4.2.2 openclaw plugin runtime API（bridge 调用）

仅用现有公开 API：
- `runtime.events.onAgentEvent` —— 事件订阅
- `runtime.agent.session.loadSessionStore` —— 读 spawnedBy
- `runtime.subagent.run` —— 子任务下行 chat
- `runtime.gatewayRequest("sessions.abort", ...)` —— 子任务 abort
- `approvalPort.resolve` —— permission

不依赖 openclaw 新增任何接口。

### 4.3 数据设计

**持久化**：零改动。复用 V8 `subagent_session_id` + `subagent_name`。

**bridge 内存**：
```ts
interface SubagentEntry {
  childSessionKey: string;
  rootToolSessionId: string;
  displayName: string;       // 路径化，如 "代码审查 > 设计"
  registeredAt: number;
}
byChildSessionKey: Map<string, SubagentEntry>;
terminatedSessionKeys: Set<string>;   // 15s 黑名单
```

**运营数据（埋点）**：
- `subagent.event.unresolved_parent` — walkChain 失败次数
- `subagent.event.late_drop` — 黑名单命中
- `subagent.outcome.{completed|error}` — 终态分布

### 4.4 集成设计

```
miniapp ──SSE──→ skill-server ──Cloud──→ ai-gateway ──WS──→ bridge ──in-process──→ openclaw
```

各跳协议契约**沿用 V8 现状**。bridge 是唯一改动模块。

依赖：
- openclaw plugin SDK >= 2026.3.24
- 不依赖其他外部系统

### 4.5 依赖项及影响面分析

#### 4.5.1 直接依赖

只有 bridge plugin：

| 模块 | 改动 |
|---|---|
| `OpenClawGatewayBridge.ts` | onAgentEvent 增子任务分支 + approval 分支 + 终态归一 + close/abort 改不 delete |
| `SessionRegistry.ts` | 增 byChildSessionKey + walkChain |
| `ApprovalRegistry.ts` | 增 subagentSessionId 字段（内部用） |
| `contracts/downstream.ts` | chat/permission/abort/close payload 加可选 subagentSessionId |
| 死代码删除 | subscribeRuntimeGatewayEvents / question 相关代码 |

**服务端（ai-gateway / skill-server / miniapp / DB）零改动**。

#### 4.5.2 间接依赖

| 上游 | 下游影响 | 对策 |
|---|---|---|
| bridge 删 question 死代码 | ai-gateway 若仍下发 question_reply → bridge fail-closed | 同步删 ai-gateway 该路径 |
| bridge 不再 delete child session | 若 openclaw cleanup 不删主对话 session → 泄漏 | spike 验证后决定是否保留对非 subagent sessionKey 的 delete |

#### 4.5.3 运行时影响监控

埋点见 §4.3。告警阈值：
- `unresolved_parent` 占比 > 1% → 严重（walkChain 设计问题）
- `late_drop` 突增 → 关注 openclaw 是否有新 late event 路径

---

## 5. DFX 设计

### 5.1 性能

| 关注点 | 设计 |
|---|---|
| onAgentEvent 延迟 | sessionStore 读 200ms 超时，超时丢 + 日志，不阻塞事件流 |
| sessionStore 高频读 | byChildSessionKey 缓存，hit 后 0 IO |
| 嵌套深度 | MAX_GUARD=10 截断 |
| 上行体积 | 复用 V8 字段，零增量 |

不涉及高 QPS / 大数据场景。

### 5.2 高可用

| 层级 | 高可用 |
|---|---|
| bridge plugin | in-process 与 openclaw 共生死 |
| 中间态恢复 | byChildSessionKey 不持久化；重启后首次见到子事件重新 walkChain |
| 其他层 | 复用现有，不变 |

### 5.3 安全

| 威胁 | 缓解 |
|---|---|
| 伪造 subagentSessionId 下行 | bridge 校验 ID 必须属于当前活跃子任务集 |
| permission 误授 | permission_reply 必须带匹配 subagentSessionId，ApprovalRegistry mismatch → tool_error |
| late event 触发废弃行为 | 15s 黑名单 |
| walkChain 死循环 | MAX_GUARD 截断 |
| sessionStore 敏感数据泄漏 | bridge 只读 spawnedBy/label，不读 sessionFile 内容 |

不涉及鉴权/加密改动。

### 5.4 兼容性

| 兼容方向 | 设计 |
|---|---|
| ai-gateway / server / miniapp | **零改动**，天然兼容（协议没变） |
| opencode（已对接）继续工作 | 原 opencode bridge 不动 |
| 老版本 openclaw plugin runtime | peerDependency 检查，加载失败明确报错 |
| 未来新平台 X | 写新 bridge plugin（4 步自适配清单），server/miniapp 不动 |
| 协议演进 | 如未来真需嵌套缩进 UI 等高级特性，再做 V9 加字段；本次维持 V8 |

---

## 6. 实施序列

只有 bridge plugin 改动，**2 个 PR 即可**：

### PR1 — bridge 先决修复
- 把 permission 处理从死代码 `subscribeRuntimeGatewayEvents` 迁移到 `handleRuntimeAgentEvent` 的 `stream="approval"` 分支
- 删除 question 相关代码（buildQuestionAskedEvent / runtime.question.reply / QuestionRegistry）
- 独立可上线

### PR2 — bridge subagent 完整支持
- SessionRegistry 增 byChildSessionKey + walkSpawnedByChain
- handleRuntimeAgentEvent 增子任务分支：识别 → 反查 → 注入 V8 字段 → 透传
- handleRuntimeAgentEvent 增 lifecycle 归一化（end/error/timeout/killed/aborted → completed/error + toolError）
- 下行 chat/permission/abort/close 增子任务路由（带 subagentSessionId 时路由到子 sessionKey）
- close/abort 不再 delete openclaw session
- late event 15s 黑名单
- 测试：派生/逐字流/permission/终态/嵌套/late event/父任务级联清理

**预计工作量：6-9 人天**（比之前 12-17 d 砍掉约 50%）

---

## 7. 开工前必须 spike

只剩 1 件：

**openclaw `runtime.subagent.deleteSession` 对非 subagent sessionKey 的行为** — 决定 bridge close_session 是否保留对主 sessionKey 的 delete 调用。

之前的"sessionStore.spawnedBy 写入时序"和"ai-gateway 是否还发 question_reply"在 PR 中顺手验证即可，不需要专门 spike。

---

## 附录 A：术语表

| 术语 | 含义 |
|---|---|
| subagent | AI 主对话派生出的子任务/子会话 |
| childSessionKey | openclaw 给子会话的 ID，格式 `agent:<agentId>:subagent:<uuid>` |
| spawnedBy | openclaw SessionEntry 上记录的"我的父会话 sessionKey" |
| announce flow | openclaw 把子任务结果回注父对话的内部流程 |
| 中台协议 | 我们定义的、独立于上游实现的 subagent 协议（V8 两个字段） |
| toolSessionId | bridge 视角的对话 ID（`ses_<uuid>`） |
| walkSpawnedByChain | bridge 内部顺 spawnedBy 一路向上找根的算法 |
| late event | 子任务终止后还到达的延迟事件 |

## 附录 B：源码引用

参见 `specs/2026-05-11-openclaw-subagent-bridge-design.md` §"关键源码索引"。

## 附录 C：相关文档

- 上游调研（含 5 个关键发现 + Codex 评审）：`specs/2026-05-11-openclaw-subagent-bridge-design.md` (v2.1)
- 实施伪代码（含详细代码骨架）：`plans/2026-05-11-openclaw-subagent-bridge-impl.md`
- Codex 评审记录：`.codex-runs/subagent-design-review.md`

## 附录 D：v2 → v3 简化说明

- v2 加了 `subagentDepth` / `subagentParentSessionId` / `subagentLifecycle` / `subagentOutcome` 4 个新字段，配套 V9 migration、ai-gateway 透传、server 落库、miniapp 嵌套渲染 —— 工作量 12-17 d
- **v3 删掉这 4 个新字段**：嵌套通过 `subagentName` 路径化表达；终态复用现有 part 字段（toolStatus / finishReason / toolError） —— 工作量 6-9 d
- 服务端零改动：协议稳定即标准
- 代价：UI 嵌套不缩进，平铺成兄弟卡片。可接受
