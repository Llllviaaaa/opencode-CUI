# 补 miniapp / external doc 缺失的 subagent 协议

## Goal

前序任务（`05-12-protocol-docs-gw-plugin-ss-inbound`）交付的 miniapp↔SS（#24）与 external↔SS（#25）两份接入协议规范中，仅把 `subagentSessionId` / `subagentName` 当作 Part 级可选字段一笔带过，**没有写 subagent 协议的语义、生命周期、字段携带规则、入站路由责任、UI 渲染模式**。本任务补齐这部分内容，让接入方能根据 doc 独立完成 subagent 支持。

## What I already know

- 协议蓝本：`docs/superpowers/specs/2026-05-11-subagent-unified-design.md` 第 3 节明确"协议只有 2 字段：`subagentSessionId` + `subagentName`"，路径化命名表达嵌套（`"代码审查 > 设计"`），**server/miniapp/DB 零改动**
- UI 渲染设计：`docs/superpowers/specs/2026-04-01-subagent-miniapp-display-design.md` 给出折叠块（SubtaskBlock）+ 阻塞交互冒泡的模式
- 代码：`StreamMessage.java:63-65` 已有 `subagentSessionId` / `subagentName` 顶层字段；plugin 端 `SubagentSessionMapper` 维护父子映射
- PR3 (#26) GW↔plugin doc 已写：`tool_event` 信封带 `subagentSessionId` + `subagentName`、子会话 `session.idle` 不补 tool_done

## Requirements

每份 doc 新增一节专门讲 subagent 协议，覆盖：
1. 协议本质：2 字段 + 路径化名字 + 平铺渲染
2. 哪些 type 会带：所有 Part 级事件（text.delta/done、thinking.delta/done、tool.update、question、permission.ask、permission.reply、file、step.start/done）
3. **入站应答的路由责任**：`question_reply` / `permission_reply` / `abort_session`（external 独有：`close_session`）payload **必须回显**收到的 `subagentSessionId`；缺失则 plugin 路由失败
4. UI 渲染模式（折叠块 + 冒泡）的描述（可链接到 `2026-04-01-subagent-miniapp-display-design.md`）
5. 嵌套：路径化 `subagentName`（`"实现 > 设计"`），无服务端字段、无 miniapp 嵌套渲染逻辑
6. 生命周期：subagent 完成由 Part 流自然结束 + `toolStatus=completed/error` 表达，**无专门 done 事件**

并修订现有字段表里关于 subagent 的描述（从"可选"升级为"协议关键字段"），在易混点速查表里加一行 subagent 相关陷阱。

## Acceptance Criteria

- [ ] 两份 doc 各新增一个独立的 §Subagent 协议小节（建议放在「保活」节之前或「易混点」节之前）
- [ ] 每份 doc 在 §6/§7（出站事件 / 入站动作）字段表里把 `subagentSessionId` 标注从「可选」升级为「协议路由字段，subagent 场景必传」
- [ ] 易混字段速查节增加 subagent 相关 1-2 行（典型陷阱：忘记回显 subagentSessionId 导致 reply 失败）
- [ ] 不重复 `2026-05-11-subagent-unified-design.md` 的内容，**通过链接引用**
- [ ] 凭空字段为 0；与代码 / subagent-unified-design / GW↔plugin doc 三方一致

## Definition of Done

- 两份 doc 修订完成、单一 PR、合入 main
- 章节风格与现有 doc 一致
- markdown 渲染通过

## Technical Approach

单分支 `docs/pr4-subagent-gap-fix`，单 PR 同时改两份 doc：
- `docs/superpowers/specs/2026-05-12-miniapp-skill-server-protocol.md`
- `docs/superpowers/specs/2026-05-12-external-skill-server-protocol.md`

派 implement sub-agent 完成；预估每份 +100~150 行。

## Out of Scope

- 不改 GW↔plugin doc（PR3 #26 已经写齐）
- 不改业务代码
- 不重写 subagent 设计稿
- 不创建新的 subagent UI mockup

## Technical Notes

- 蓝本：`docs/superpowers/specs/2026-04-29-business-cloud-agent-protocol-v2.md`
- 协议源：`docs/superpowers/specs/2026-05-11-subagent-unified-design.md`（第 3 节）
- UI 源：`docs/superpowers/specs/2026-04-01-subagent-miniapp-display-design.md`
- 代码源：`skill-server/src/main/java/com/opencode/cui/skill/model/StreamMessage.java:63-65`
