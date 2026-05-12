# 接入协议文档三件套（GW↔plugin / miniapp↔SS / external↔SS）

## Goal

参照 `docs/superpowers/specs/2026-04-29-business-cloud-agent-protocol-v2.md` 的体例，为新业务接入方输出三份独立的对接协议规范。每份都做到「字段表 + JSON 示例 + 事件清单 + 错误矩阵 + 字段↔代码映射」，能直接对照开发。

## Requirements

1. 拆为三份独立文档，落在 `docs/superpowers/specs/2026-05-12-*.md`：
   - `2026-05-12-miniapp-skill-server-protocol.md` — miniapp 前端 ↔ SS（`/ws/skill/stream` + Cookie userId；入站走 miniapp controller）
   - `2026-05-12-external-skill-server-protocol.md` — external 业务模块（IM/CRM 等）↔ SS（`/ws/external/stream` + Sec-WebSocket-Protocol；入站 `POST /api/external/invoke` action=chat/q_r/p_r/rebuild）
   - `2026-05-12-gateway-plugin-protocol.md` — ai-gateway ↔ message-bridge 插件（双向：上行事件流 + 下行 7 个 action：Chat/CreateSession/CloseSession/AbortSession/QuestionReply/PermissionReply/StatusQuery）
2. 每份共用骨架（对齐 v2 doc）：
   - 概览（角色 + 链路图）
   - 接入契约（endpoint / 握手 / 鉴权）
   - 请求/响应公共字段表
   - 事件/动作清单（逐项 JSON 示例 + 字段表）
   - 长连接保活与生命周期
   - 错误处理矩阵
   - 配置项一览
   - 字段↔代码映射（file:method 行级对照）
   - 给接入方的注意事项 / 易混字段速查
3. 只写当前 HEAD 实现，不包含 v1/v2 切换章节
4. GW↔plugin 面向「插件/引擎适配作者」（外部受众），不暴露内部错误码细节但允许引用 file:line
5. 中文，与 v2 doc 一致

## Acceptance Criteria

- [ ] 三份 md 文件落到 `docs/superpowers/specs/` 下，命名带 2026-05-12 日期前缀
- [ ] 每份含完整字段表 + JSON 示例 + 错误矩阵 + 字段↔代码映射
- [ ] 每个 JSON 示例都可在代码里反查到产生它的位置（不出现凭空字段）
- [ ] 「易混字段速查」节覆盖该协议下所有命名陷阱
- [ ] miniapp doc 把现有 `2026-04-10-stream-protocol.md` 内容吸收为出站事件章节并补足入站章节
- [ ] external doc 把 `2026-04-13-external-ws-channel-design.md` 设计稿升级为接入规范
- [ ] plugin doc 至少覆盖 `contracts/upstream-events.ts` 全部事件 + 7 个下行 action 的 schema

## Definition of Done

- 文档自洽：示例字段名/类型/可选性与代码 100% 对齐
- 字段↔代码映射节可作为反查表使用
- Markdown 语法过 mdlint
- 三个 PR 各自单独可 review

## Technical Approach

- **PR1（最小）** — miniapp↔SS：以 `2026-04-10-stream-protocol.md` 作为出站起点，补入站 controller（来源：SS miniapp inbound controller）、鉴权、保活、错误矩阵、字段-代码映射
- **PR2** — external↔SS：以 `2026-04-13-external-ws-channel-design.md` 为底，新增 `POST /api/external/invoke` 四种 action 的请求体、`/ws/external/stream` 握手协议（Sec-WebSocket-Protocol AKSK）、StreamMessage 出站（与 miniapp 共用，链接到 miniapp doc 而非复制）
- **PR3（最大）** — GW↔plugin：双向协议
  - 上行：扫 `protocol/upstream/SupportedUpstreamEvents.ts` + `UpstreamEventExtractor.ts` 列出所有事件
  - 下行：扫 `action/*.ts` 列出 7 个 action 的 input/output schema
  - 连接握手：扫 `connection/GatewayConnection.ts` + `connection/AkSkAuth.ts` 写鉴权
  - 重连/状态：扫 `connection/ReconnectPolicy.ts` + `connection/StateManager.ts`

## Decision (ADR-lite)

**Context**：需要为新业务接入提供与云端 v2 协议同等质量的对接规范。
**Decision**：拆三份独立文档（按受众/通道差异自然划分），每份完整字段-代码映射，分三个 PR 交付。
**Consequences**：
- ✅ 每份文档受众单一、易读、可独立维护
- ✅ 分 PR 便于 stakeholder（前端/IM/插件作者）各自 review
- ⚠️ 三份会有部分重复（如 StreamMessage 字段会在 miniapp 与 external 之间引用），通过链接而非复制处理
- ⚠️ 字段-代码映射的维护成本：建议未来加 PostToolUse 检查或定期审计

## Out of Scope

- 不修改任何业务代码
- 不重写 v2 云端协议文档
- 不写 mock 服务器
- 不引入 v1/v2 切换章节

## Technical Notes

- 蓝本：`docs/superpowers/specs/2026-04-29-business-cloud-agent-protocol-v2.md`
- 相关素材：`2026-04-10-stream-protocol.md`、`2026-04-13-external-ws-channel-design.md`、`2026-04-02-plugin-reply-event-forwarding-design.md`、`2026-04-02-gw-connection-level-routing-design.md`
- 三个 PR 估算体量：PR1 ~600 行；PR2 ~800 行；PR3 ~1500-2000 行
