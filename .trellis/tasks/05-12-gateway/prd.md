# gateway 助手广场非标协议适配器

> 详细设计方案见 [design.md](./design.md)。本 PRD 是 trellis brainstorm 收敛后的 MVP 摘要。

## Goal

让 ai-gateway 能对接"助手广场"（POST `/integration/v4-1/gateway/chat`）这种**未适配我们标准协议**的云端业务：
- 入参字段不一致（`assistantAccount/sendW3Account/msgBody/clientLang/imGroupId/topicId`）
- 出参是非标 SSE 流，混杂多业务源 messageType，无 .done 终态，无 partId

**核心架构决策**：在 SS 和 GW 两端引入两层抽象——**Strategy 层（单一职责）+ Profile 层（套餐组合）**，对称扩展，不动现有 OpenCode 路径。

## Decisions (locked)

完整决策记录在 [design.md](./design.md) 各章。10 项 brainstorm 锁定项摘要：

| # | 决策 | 落地 |
|---|---|---|
| 1 | SSE 终止符识别交给 decoder | `SseEventDecoder.isTerminator(line)`；新增 `SseEventDecoderFactory` 独立工厂 |
| 2 | decoder 工厂方法创建 session | `SseEventDecoder.createSession()`；状态全在 per-connection session |
| 3 | CloudConnectionContext 加 `cloudProfile` 字段 | 由 `CloudAgentService.handleInvoke` 从 invoke payload 读取填充 |
| 4 | `event:error` 翻译路径 | decoder 输出顶层 `GatewayMessage(TOOL_ERROR)` |
| 5 | 流中断时仍 flush | finally 块调 `decoder.flush(session)`，所有路径 |
| 6 | `event:think` → `thinking.delta` | `event:processStep` MVP 丢弃 |
| 7 | profile 覆盖 callback authType | profile 接口加 `String authType()`；`AssistantSquareProfile` 返回 `"integration_token"` |
| 8 | SS / GW profile 接口独立 | 共享 profile name 字符串契约（"default" / "assistant_square"） |
| 9 | SS 字段类型转换 + fast-fail | `Long.parseLong` + IllegalArgumentException + `[ERROR]` 日志 |
| 10 | Authorization header 直传 | `IntegrationTokenAuthStrategy.applyAuth` 写 `Authorization: <token>` |

## Requirements

### 功能性
- SS 端新增 `AssistantSquareCloudRequestStrategy`（@Component 自动注册）+ `CloudRequestProfile` 抽象 + `CloudRequestProfileRegistry`
- GW 端新增 `SseEventDecoder` 接口 + `SseEventDecoderFactory` + `DefaultSseEventDecoder`（封装现有逻辑）+ `AssistantSquareSseEventDecoder` + `AssistantSquareDecoderSession`
- GW 端新增 `CloudResponseProfile` 抽象 + `CloudResponseProfileRegistry` + `DefaultResponseProfile` + `AssistantSquareResponseProfile`
- GW 端新增 `IntegrationTokenAuthStrategy implements CloudAuthStrategy`
- SS SysConfig 新增 type `cloud_protocol_profile:<appId>`，取值为 profile name
- invoke payload 新增 `cloudProfile` 字段（向后兼容：缺失 → "default"）
- `CloudConnectionContext` 加 `cloudProfile` 字段

### Decoder 状态机要求（详见 design.md §2.3）
- 流式 part（text/thinking/planning）：累积 content；切换/finish 时补 `<type>.done`（带累积全文）
- 单次性事件（searching/search_result/reference/ask_more）：直接发，中断流式段时先补 done
- `event:finish` (`data:FINISH`) → flush 后顶层 `TOOL_DONE`
- `event:error` → 顶层 `TOOL_ERROR`
- 未支持 messageType（HTML/IMAGE-IM/卡片/processStep/TEXT_LIST 等）→ 返回空列表，不报错

## Acceptance Criteria

- [ ] SS 端 `AssistantSquareCloudRequestStrategy.build()` 单测：助手广场字段（assistantAccount/sendW3Account/msgBody/clientLang/imGroupId/topicId）对齐
- [ ] SS 端字段类型转换 + fast-fail 单测：`assistantAccount`/`topicId` 非数字时抛 IllegalArgumentException
- [ ] GW 端 `DefaultSseEventDecoder` **回归测试**：现有 OpenCode SSE 路径行为零变化
- [ ] GW 端 `AssistantSquareSseEventDecoder` 单测：
  - 7 类关心事件正确映射
  - 状态机所有转移（type 切换 / messageId 切换 / 单次事件中断流式 / finish 触发 flush）
  - 累积内容在 .done 正确填充
  - 多媒体类型 / processStep 丢弃但不报错
  - `event:error` 输出顶层 TOOL_ERROR
- [ ] `IntegrationTokenAuthStrategy` 单测：token 注入 + 缺失抛异常
- [ ] `CloudAgentService` 单测扩展：profile 解析 + `profile.authType()` override 路径
- [ ] `SseProtocolStrategy` 单测扩展：decoder dispatch + isTerminator + flush 调用
- [ ] 既有 `CloudPushController` / `CloudAgentService` chat 路径回归测试通过
- [ ] `mvn -pl ai-gateway test` 与 `mvn -pl skill-server test` 全绿
- [ ] Lint / typecheck CI 绿

## Definition of Done

- 单元测试覆盖：入参 build + 出参翻译状态机 + 终态判定 + Token 注入
- 不引入新中间件 / 新依赖
- 日志符合 `[ai-gateway]` / `[ENTRY/EXIT/EXT_CALL]` 规范
- 敏感数据（Authorization header）通过 `SensitiveDataMasker` 脱敏
- 现有 OpenCode 路径行为零变化（DefaultSseEventDecoder 等价封装）

## Out of Scope

- question_reply / permission_reply（助手广场仅支持 chat）
- 多 token / 多租户（MVP 单 token 静态配置）
- HTML / 卡片 / 多媒体类型纳入标准协议
- 重连机制
- 废弃 SysConfig `cloud_request_strategy:<appId>`（implement 阶段决定，**不强制**）
- 修复现有 fallback partId 在同 normalizedType 跨段共享的缺陷（GW 现存行为，本任务不动）

## Technical Notes

### 关键文件
- **SS 端参考**：
  - `skill-server/.../service/cloud/CloudRequestStrategy.java` — 现有接口
  - `skill-server/.../service/cloud/CloudRequestBuilder.java` — 现有工厂
  - `skill-server/.../service/cloud/DefaultCloudRequestStrategy.java` — 参考实现
  - `skill-server/.../service/cloud/CloudRequestContext.java` — 上下文字段
- **GW 端参考**：
  - `ai-gateway/.../service/cloud/CloudProtocolStrategy.java` — 现有接口
  - `ai-gateway/.../service/cloud/SseProtocolStrategy.java:154` — 现有 readValue 逻辑（迁移到 DefaultSseEventDecoder）
  - `ai-gateway/.../service/cloud/CloudConnectionContext.java` — 上下文（需加字段）
  - `ai-gateway/.../service/cloud/CloudAuthStrategy.java` — 现有鉴权接口
  - `ai-gateway/.../service/CloudAgentService.java` — invoke 编排（需读 payload.cloudProfile + override authType）
  - `ai-gateway/.../service/CloudAgentService.java:184-220` — messageId/partId fallback 兜底（复用）
  - `ai-gateway/.../model/GatewayMessage.java` — 标准协议 DTO
- **协议消费方（用于事件 schema 对齐）**：
  - `skill-server/.../service/CloudEventTranslator.java` — 已注册的 event.type 全集
  - `skill-server/.../service/OpenCodeEventTranslator.java` — OpenCode 路径参考
- **助手广场协议**：`D:/04_Documents/助手广场提供给gateway的对话协议.md`

### 联调待验证（PRD TODO）
| 项 | MVP 假设 | 验证方式 |
|---|---|---|
| `messageBody` 字段是 delta 还是全量 | 增量 | 抓 1 条真实 SSE 流 |
| 同一会话流是否多 messageId | 单个 | 抓流确认 |
| `Authorization` 是否需要 `Bearer` 前缀 | 否 | 联调实测 |

## Decision Brainstorm Trail（参考）

- 路径选择：路径 2（SSE 内部 decoder 工厂） — 不新增 channelType，复用 sse；不重写传输层
- 配置维度：抽象工厂模式（Profile = 套餐组合）—— 解决用户提出的"默认入参 + 非标出参"反例
- decoder 选择来源：invoke payload `cloudProfile` 字段，**SS 是 vendor 知识唯一权威源**，不卷入 api-server
- 字段类型：profile name 字符串（开放扩展集合），跟 SS `STRATEGY_NAME` 风格一致
- 命名：profile name = `"default"` / `"assistant_square"`，跟 `DefaultCloudRequestStrategy.STRATEGY_NAME` 命名族对齐
