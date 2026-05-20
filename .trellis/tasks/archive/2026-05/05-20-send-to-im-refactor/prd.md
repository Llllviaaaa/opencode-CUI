# send-to-im 接口改造：删 chatId + cookie sender 校验

## Goal

把 `POST /api/skill/sessions/{sessionId}/send-to-im` 的发送目标和发送人确定方式收紧：
- 目标 (chatId) 不再由前端 body 传入，统一从 `SkillSession.businessSessionId` 按约定格式解析出 `targetType + targetId`；
- 发送人 (senderAccount) 强制等于 cookie `userId`（== accountId），与从 businessSessionId 解析出的 senderAccount 做等值校验；

目的：杜绝前端绕过 sessionId 给任意 chatId 发消息；让"以谁名义发"在协议层显式可追溯。

> 用户最初描述里的"ownerWelinkId 校验放开"已拆分到独立任务 `05-20-single-chat-sender-fallback-removal`（影响入站 chat 路径协议语义，与本出站任务无代码重叠）。

## What I already know

### 现状代码（已读 `SkillMessageController.java:350-397` + `ImMessageService.java`）
- `SendToImRequest { content, chatId }`；优先 `body.chatId`，回落 `session.businessSessionId`。
- `accessControlService.requireSessionAccess(sessionId, userIdCookie)` 校验 cookie userId == session.userId（403）；这一段保留。
- `ImMessageService.sendMessage(chatId, content)` POST `${skill.im.api-url}/messages/send` body `{chatId, content, msgType:"text"}`；`RestTemplate` 无任何 auth interceptor → 发送人身份隐式来自 IM 平台对该 endpoint 的应用 token 识别。
- 前端调用方：`useSendToIm.ts`（`sendToIm(content, chatId)`）、`api.ts` 的 `sendToIm`、`SendToImButton.tsx`、`SkillMain.tsx`。

### 用户已确认的关键事实
- Cookie 名 `userId`，其值即 accountId（沿用现有 `@CookieValue("userId")`）。
- `businessSessionId` 格式两种：
  - `group_<groupId>_<senderAccount>`
  - `direct_<targetAccount>_<senderAccount>`
- groupId / targetAccount / senderAccount 都是纯数字/字母，**绝对不含 `_`** → 可直接 `split("_")` 三段解析。
- 校验失败响应：HTTP 200 + 业务码 403 "Sender mismatch"；格式非法 400；cookie 缺失 401。
- 下游 IM API 沿用同一个 `/messages/send` endpoint，新增 `targetType / targetId / senderAccount` 字段。
- `SendToImRequest.chatId` **直接删字段**（不兼容旧前端）。

## Assumptions (temporary)

- `groupId` / `targetAccount` 是字符串字段（不强制数值类型）。
- 下游 IM API 接受新增字段（不会因为 unknown field 报 400）。
- 没有第三个第四个 prefix（除 `group_` / `direct_` 以外的格式 = 非法）。
- 移除 created_by 校验之后**没有等价的兜底权限校验缺口**——`requireSessionAccess`（session.userId 归属校验）已经足够防越权。

## Open Questions

- ~~解析 businessSessionId 的工具放在哪里？~~ → **Decided**: 新建 `model/BusinessSessionId.java` record + 静态 `parse(String)`，返 `Optional<BusinessSessionId>`，配套 `targetType` enum (GROUP / DIRECT)。
- ~~Sender mismatch 日志细节~~ → **Decided**: 明文打 `sessionId / cookieAccount / expectedSender / businessSessionId`（与现有 userId 日志风格一致，不 mask）。WARN 级别。
- ~~cookie userId 缺失业务码~~ → **Decided**: 400 "userId is required"（沿用 `SessionAccessControlService.requireUserId` 既有约定）。

## Requirements (evolving)

### R1 删除 chatId 字段，从 businessSessionId 解析
- `SendToImRequest` 只剩 `content` 字段。
- 解析 `session.businessSessionId`：
  - 以 `group_` 开头 → split("_") 必须正好 3 段 → `targetType="group", targetId=parts[1], senderAccount=parts[2]`
  - 以 `direct_` 开头 → split("_") 必须正好 3 段 → `targetType="direct", targetId=parts[1], senderAccount=parts[2]`
  - 任何其它情况 → 业务码 400 "Invalid businessSessionId format"
- 解析逻辑暴露为可复用的 helper（具体位置见 Open Question）。

### R2 Sender 从 cookie 取并校验
- `@CookieValue("userId")` 仍为可选注入，但接口内部首先转 `requireUserId(userIdCookie)`（沿用 `SessionAccessControlService.requireUserId`，缺失即 400 "userId is required"，与全局风格一致）。
- 在已通过 `requireSessionAccess` 后，把 cookie userId 与 businessSessionId 解析出的 senderAccount 做 `equals` 比较：
  - 不一致 → 业务码 403 "Sender mismatch"。
  - 一致 → 继续下发。

### R3 ImMessageService 改签名 + body 增加字段
- 新签名：`boolean sendMessage(String targetType, String targetId, String senderAccount, String content)`。
- body 字段：`{ targetType, targetId, senderAccount, content, msgType: "text" }`。
- 入参 null/blank 校验：四个字段任一为空 → 直接 `return false`（与现有 chatId/content 校验同等级）。
- 失败返回 / 日志风格沿用现有。

### R4 content 长度上限
- `content.length() > 4000` → 业务码 400 "Content too long (max 4000 chars)"。
- 在现有 `content blank` 校验同一处加；优先于其它校验早 fail。

### R5 前端同步去 chatId
- `useSendToIm.ts`：`sendToIm(content: string)`；删 `chatId` 参数。
- `utils/api.ts` 中对应方法签名同步。
- `SendToImButton.tsx`、`SkillMain.tsx` 等调用方移除 chatId 传参。

## Acceptance Criteria (evolving)

- [ ] body 不含 `chatId` 字段；前端发版后调用形如 `POST /send-to-im { content: "..." }`。
- [ ] businessSessionId = `group_<g>_<u>` 且 cookie userId = `<u>` → 200, 下发 body 含 `targetType="group", targetId=<g>, senderAccount=<u>`。
- [ ] businessSessionId = `direct_<t>_<u>` 且 cookie userId = `<u>` → 200, 下发 body 含 `targetType="direct", targetId=<t>, senderAccount=<u>`。
- [ ] businessSessionId 格式非 `group_/direct_` 前缀 → 业务码 400 "Invalid businessSessionId format"。
- [ ] businessSessionId 三段 split 段数 ≠ 3 → 业务码 400。
- [ ] cookie userId ≠ businessSessionId 末段 senderAccount → 业务码 403 "Sender mismatch"。
- [ ] cookie userId 缺失 → 业务码 400 "userId is required"（沿用 `requireUserId` 既有约定，**或**改 401 待用户拍板）。
- [ ] session 不属于当前 cookie userId → 业务码 403 "Session access denied"（既有行为，不变）。
- [ ] `content.length() > 4000` → 业务码 400 "Content too long (max 4000 chars)"。
- [ ] `SkillMessageControllerTest` 覆盖以上各分支。
- [ ] 前端 `useSendToIm` 类型签名变更，TS 编译通过，调用方全部更新。

## Definition of Done

- 单测覆盖：format 合法/非法 × 4 种、sender match/mismatch × 2、cookie 缺失 × 1、IM 下游失败 × 1。
- mvn test / lint / typecheck CI 通过。
- 前端 TS 编译 + 调用方全部对齐，无遗漏调用 `sendToIm(x, y)` 两参形态。
- `documents/protocol/v3/01-miniapp-skillserver.md` 中 send-to-im 段（如有）更新。
- 按用户偏好走 codex (gpt-5.5 xhigh) Critical 评审一轮再开 PR1（[[feedback_codex_design_review]]）。

## Out of Scope

- 不改 `SessionAccessControlService.requireSessionAccess` 已有的 session.userId 归属校验。
- 不引入 IM 平台账号代发 (on_behalf_of) 机制——发送人字段是显式声明，不做密码学绑定。
- 不改 ImMessageService 底层 HTTP / 鉴权方式（仍是无 interceptor 的 RestTemplate）。
- 不动 send-message（普通消息发送）路径里的 sendUserAccount 逻辑——本次只动 send-to-im。
- 不做 chatId 字段向后兼容兜底——发版顺序需保证前后端同步。
- 不做幂等（idempotencyKey）——重复点按钮的去重沿用现有"500 用户感知失败"语义。

## Technical Notes

### 涉及文件（基于 grep）
**skill-server**
- `controller/SkillMessageController.java`（line 350-397 + `SendToImRequest` 内部类 line 595-600）
- `service/ImMessageService.java`
- `service/SessionAccessControlService.java`（**不改**，只复用 `requireUserId`）
- `test/.../SkillMessageControllerTest.java`

**skill-miniapp**
- `src/hooks/useSendToIm.ts`
- `src/utils/api.ts`
- `src/components/SendToImButton.tsx`
- `src/pages/SkillMain.tsx`

### 关键约束
- businessSessionId 三段格式契约由本任务首次明文化，需写进 `documents/protocol/v3/01-miniapp-skillserver.md`。
- ID 段不含下划线是**业务侧约定**，本任务靠 split 段数校验来 fail-fast，但不主动验证字符集（防御性可后续加）。

## Decision (ADR-lite)

**Context**：send-to-im 现状 (1) chatId 由 body 传可被前端绕过 sessionId 任意定向；(2) sender 隐式来自 IM 平台 bot 身份，谁触发都看不出来；(3) 校验有冗余且 ownerWelinkId 校验阻拦合理的群成员场景。

**Decision**：
1. body 删 chatId 字段（**不向后兼容**，前后端同步发版），统一从 `session.businessSessionId` 按 `(group|direct)_<id>_<sender>` 三段 split 解析；
2. sender 从 cookie userId 取，强制等于 businessSessionId 末段 senderAccount，不一致 → 业务码 403；
3. 解析器作为独立 `model/BusinessSessionId` record，targetType enum + Optional 返回；
4. content 长度上限 4000 字符 fail-fast；不做幂等；
5. ownerWelinkId 限制放开**拆到独立任务** `05-20-single-chat-sender-fallback-removal`，本任务不动入站路径。

**Consequences**：
- 收益：定向逻辑契约化、sender 显式可审计、协议格式有解析器后续扩展容易；
- 代价：前后端必须同步发版（无兼容期）；新 record + targetType enum 增加 1 个类；
- 后续：长度上限若实测过紧再放宽；幂等独立任务。

## Implementation Plan (small PRs)

- **PR1**: `BusinessSessionId` record + parse 单测；`SkillMessageController.sendToIm` 改造 + 业务码分支；`ImMessageService.sendMessage` 改签名；controller 测试全覆盖。
- **PR2**: 前端 `useSendToIm` / `api.ts` 签名变更 + 调用方 `SendToImButton` / `SkillMain` 同步；TS 编译通过。
- **PR3**: 协议文档 `documents/protocol/v3/01-miniapp-skillserver.md` send-to-im 段同步；运维 changelog（如有）。

## Review History

- v1 (2026-05-20): brainstorm 阶段初稿。
- v2 (2026-05-20): 收口偏好题（解析器位置 / 日志风格 / cookie 缺失码 / MVP 范围）+ ownerWelinkId 拆独立任务。
