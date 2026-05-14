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
| ~~7~~ | ~~profile 覆盖 callback authType~~ | **撤销** — authType 走原有 callback API 路径：api-server 端 endpoint 注册数据配 `authType=3`，GW 端 `GatewayCallbackResolver.mapAuthType` 加 `case 3 -> "integration_token"` 映射 |
| 8 | SS / GW profile 接口独立 | 共享 profile name 字符串契约（"default" / "assistant_square"） |
| 9 | SS 字段类型 + fast-fail | `assistantAccount` **String 直传**（协议文档"long"是笔误，实际示例如 `"dig_30051824"`）；`topicId` `Long.parseLong(toolSessionId)`；`imGroupId` String 透传；blank 校验抛 `IllegalArgumentException` + `[ERROR]` 日志 |
| 10 | Authorization header 直传 | `IntegrationTokenAuthStrategy.applyAuth` 写 `Authorization: <token>` |
| 11 | step.start / step.done 补齐 | decoder 首事件前 emit `step.start`；flush 时 emit `step.done`（usage 留空），让助手广场路径事件序列与 OpenCode 对称；session 加 `stepStarted` 字段 |
| 12 | extParameters 透传 | `AssistantSquareCloudRequestStrategy.build` 把 `ctx.extParameters` 写进助手广场入参（助手广场后续会支持该字段） |
| 13 | decoder 内部 protocolType 二级分派 | 新增 `AssistantSquareProtocolHandler` 接口；MVP 实现 `StandardProtocolHandler`（含状态机 + step.start/done）+ `UnknownProtocolFallbackHandler`；athena/uniknow/agentmaker 等其他派系不在 MVP 范围（未来加 @Component 即可，decoder 顶层零改动） |
| 14 | `event:ping` 业务心跳 | `SseEventDecoder` 接口 `default boolean isHeartbeat(String dataLine)`；`AssistantSquareSseEventDecoder` 用 `dataLine.contains("\"eventType\":\"ping\"")` 快速识别；`SseProtocolStrategy` 主循环识别后调 `lifecycle.onHeartbeat` 不下发事件、不进 handler |
| 15 | 业务助手 toolSessionId 重构为 Snowflake | `BusinessScopeStrategy.generateToolSessionId()` 从 `"cloud-" + UUID` 改为 `SnowflakeIdGenerator.nextId().toString()`（单点改动，所有调用方通过 strategy 接口自动受益）；助手广场场景 `topicId = Long.parseLong(toolSessionId)` 直接成功；历史 `cloud-xxx` toolSessionId 不动（助手广场是新接入，旧 session 不会回流） |
| 命名 | "appId" 实际是 businessTag | SS SysConfig key 维度 = `businessTag`（来自 `AssistantInfo.businessTag`）；`CloudRequestProfileRegistry.resolve(String businessTag)` 用正确命名；`CloudRequestBuilder.buildCloudRequest` 原 `appId` 参数名是历史命名 bug，**不动**（任务范围聚焦） |
| 16 | profile 改为 SysConfig 数据 + Registry 运行时拼装 | **撤销** profile 作为 @Component 类的设计。`CloudRequestProfile` / `CloudResponseProfile` 改为 **record / POJO**。Registry 运行时按 SysConfig 拼装：①`cloud_protocol_profile:<businessTag>` → profile name ②`cloud_protocol_profile_def:<profileName>` → JSON 定义（`{"request_strategy":..., "response_decoder":...}`）。**支持运维零代码自定义套餐**（含交叉组合如 default 入参 + 助手广场出参）。**约定 fallback**：profile_def 缺失时按 "profile name == strategy name == decoder name" 对称约定查找。带 **5 分钟 in-memory cache**（配置项 `gateway.cloud-protocol-profile.cache-ttl-ms`，默认 300000） |
| 17 | `cloud_request_strategy` 废弃 + `CloudRequestBuilder` 标 @Deprecated | 改造后 `CloudRequestBuilder` 成为 dead code，加 `@Deprecated` + javadoc；旧 SysConfig `cloud_request_strategy:<businessTag>` 数据保留作为回滚兜底，新逻辑不再读取 |

## Requirements

### 功能性
- SS 端新增 `AssistantSquareCloudRequestStrategy`（@Component 自动注册）+ `CloudRequestProfile` **record / POJO**（不再是接口）+ `CloudRequestProfileRegistry` @Service（运行时按 SysConfig 拼装，5min cache）；**不再有** `DefaultRequestProfile` / `AssistantSquareRequestProfile` @Component 类
- SS 端 `AssistantSquareCloudRequestStrategy.build`：
  - `assistantAccount` String 直传；`topicId = Long.parseLong(ctx.getTopicId())`；`imGroupId` String 透传
  - 透传 `ctx.extParameters`（跟 `DefaultCloudRequestStrategy:56-61` 模式一致）
  - blank 校验：`assistantAccount` / `sendUserAccount` 空抛 `IllegalArgumentException`
- SS 端修改 `BusinessScopeStrategy.generateToolSessionId()`：从 `"cloud-" + UUID` 改为 `SnowflakeIdGenerator.nextId().toString()`；构造器注入 `SnowflakeIdGenerator`
- SS 端 `BusinessScopeStrategy.buildInvoke` 改造：调用 `CloudRequestProfileRegistry.resolve(businessTag)` 拿 profile，再 `profile.requestStrategy().build(ctx)`；payload 增加 `cloudProfile = profile.getName()` 字段
- GW 端新增 `SseEventDecoder` 接口（含 `default isHeartbeat`）+ `SseEventDecoderFactory` + `DefaultSseEventDecoder`（封装现有逻辑）+ `AssistantSquareSseEventDecoder`（顶层 decoder）+ `AssistantSquareDecoderSession`（含 `stepStarted` 字段）
- GW 端新增 `AssistantSquareProtocolHandler` 接口 + `StandardProtocolHandler`（MVP 核心实现，含状态机 + step.start/done 补齐）+ `UnknownProtocolFallbackHandler`（兜底丢弃）
- GW 端新增 `CloudResponseProfile` **record / POJO**（不再是接口、不是 @Component）+ `CloudResponseProfileRegistry` @Service（运行时按 SysConfig 拼装，5min cache，通过 `SkillServerConfigClient` 跨服务查 SS）；**不再有** `DefaultResponseProfile` / `AssistantSquareResponseProfile` @Component 类
- GW 端新增 `IntegrationTokenAuthStrategy implements CloudAuthStrategy`（getAuthType="integration_token"）
- GW 端 `GatewayCallbackResolver.mapAuthType` 加 `case 3 -> "integration_token"`
- GW 端 `SseProtocolStrategy` 主循环：识别 `isTerminator` / `isHeartbeat` / `decode` 三分支；finally 块调 `flush`
- SS SysConfig 新增**两个** type：
  - `cloud_protocol_profile_def`：key=profile name，value=JSON 定义 `{"request_strategy":..., "response_decoder":...}`；预置 `default` / `assistant_square` 两条
  - `cloud_protocol_profile`：key=`<businessTag>`，value=profile name；缺失走 "default"
- 新增 SQL 迁移脚本 `V11__init_cloud_protocol_profile.sql` 预置 profile_def 初始数据
- SS 端 `CloudRequestBuilder` 标 `@Deprecated`（dead code 保留作为回滚兜底）；旧 SysConfig `cloud_request_strategy` 数据不动
- invoke payload 新增 `cloudProfile` 字段（向后兼容：缺失 → "default"）
- `CloudConnectionContext` 加 `cloudProfile` 字段
- api-server endpoint 注册数据：给助手广场对应的 ak 配 `authType=3`（数据配置，非代码变更）
- GW application.yml 新增 `gateway.cloud.assistant-square.integration-token` 配置项

### Decoder 三层结构（详见 design.md §2.3）
1. **顶层 `AssistantSquareSseEventDecoder`**：实现 `SseEventDecoder` 接口；`decode` 读 data.protocolType（默认 "standard"），委托给对应 `AssistantSquareProtocolHandler`；`flush` 委托给 standard handler
2. **子 handler `StandardProtocolHandler`（MVP 主要实现）**：持有状态机
   - **step.start 补齐**：首个事件前 emit 一次（用 `session.stepStarted` 标记）
   - 流式 part（text/thinking/planning）：累积 content；切换/中断/finish 时补 `<type>.done`（带累积全文）
   - 单次性事件（searching/search_result/reference/ask_more）：直接发，中断流式段时先补 done
   - **step.done 补齐**：flush 时 emit（在 part .done 之后），usage 留空
   - `event:error` → 顶层 `TOOL_ERROR`
   - 不支持 messageType（HTML/IMAGE-IM/卡片/processStep/TEXT_LIST 等）→ 返回空列表，不报错
3. **子 handler `UnknownProtocolFallbackHandler`（兜底）**：所有输入返回空列表
4. 终止符 `data:FINISH` / `data:[DONE]` 由顶层 decoder 判定，触发 flush + SseProtocolStrategy 主循环退出，再发顶层 `TOOL_DONE`

## Acceptance Criteria

- [ ] SS 端 `AssistantSquareCloudRequestStrategy.build()` 单测：
  - 助手广场字段对齐：`assistantAccount` String 透传、`sendW3Account` 来自 ctx.sendUserAccount、`msgBody` 来自 ctx.content、`clientLang` (默认 zh)、`imGroupId` String 透传
  - `topicId = Long.parseLong(ctx.getTopicId())`（toolSessionId 改 Snowflake 后是纯数字 → parseLong 成功）
  - `extParameters` 透传：null/empty 时填空对象；非空时序列化为 JsonNode
  - blank 校验：`assistantAccount`/`sendUserAccount` 空抛 IllegalArgumentException
  - `topicId` 是非数字字符串时（如旧 `cloud-xxx`）抛 IllegalArgumentException + `[ERROR]` 日志
- [ ] SS 端 `BusinessScopeStrategy.generateToolSessionId()` 单测：返回值是纯数字字符串（`Long.parseLong` 成功）
- [ ] SS 端 `BusinessScopeStrategy.buildInvoke` 单测：调用 ProfileRegistry + payload 含 `cloudProfile` 字段
- [ ] GW 端 `GatewayCallbackResolver.mapAuthType` 单测：`case 3 -> "integration_token"`
- [ ] SS 端 `CloudRequestProfileRegistry.resolve` 单测：
  - SysConfig 缺 `cloud_protocol_profile:<businessTag>` → 走 "default"
  - 显式定义 profile_def 时按 JSON 字段解析（`request_strategy` 字段提取）
  - 约定 fallback：profile_def 缺失时 profile name == strategy name
  - 未注册 strategy name → fallback 到 "default" strategy
  - 5min in-memory cache 命中（第二次查询不调 SysConfig）
- [ ] GW 端 `CloudResponseProfileRegistry.resolve` 单测：同上，但读 `response_decoder` 字段；通过 `SkillServerConfigClient` mock 跨服务查询
- [ ] GW 端 `DefaultSseEventDecoder` **回归测试**：现有 OpenCode SSE 路径行为零变化
- [ ] GW 端 `AssistantSquareSseEventDecoder` 顶层单测：
  - 按 `data.protocolType` 分派到正确的 handler
  - protocolType 字段缺失 → 默认 standard
  - 未知 protocolType（如 athena/uniknow）→ UnknownProtocolFallbackHandler
  - `isTerminator("FINISH")` / `isTerminator("[DONE]")` 返回 true
  - **`isHeartbeat` 识别 `event:ping` 数据行**（`dataLine.contains("\"eventType\":\"ping\"")` 返回 true）
  - `flush` 委托给 standard handler
- [ ] GW 端 `StandardProtocolHandler` 单测（MVP 核心）：
  - 7 类关心事件正确映射（planning/think/message(TEXT)/searching/searchResult/reference/askMore + error/finish）
  - **首个事件前自动补 step.start**
  - **flush 时自动补 step.done（usage 留空）**
  - 状态机所有转移：type 切换 / messageId 切换 / 单次事件中断流式 / finish 触发 flush
  - 累积内容在 `<type>.done` 正确填充
  - 不支持 messageType（HTML/IMAGE-IM/FILE-IM/卡片/processStep/TEXT_LIST 等）丢弃但不报错（仍可正常补 step.start）
  - `event:error` 输出顶层 TOOL_ERROR
- [ ] GW 端 `UnknownProtocolFallbackHandler` 单测：所有输入返回空列表
- [ ] `IntegrationTokenAuthStrategy` 单测：token 注入 + 缺失抛异常
- [ ] `CloudAgentService` 单测扩展：cloudProfile 读取 + 缺失时默认 "default"
- [ ] `SseProtocolStrategy` 单测扩展：
  - decoder dispatch（按 cloudProfile 选 decoder）
  - `isTerminator` 触发 flush + 终止
  - **`isHeartbeat` 触发 `lifecycle.onHeartbeat`，不下发事件、不进 decode**
  - finally 块调 flush（异常 / 超时 / 正常 finish 三路径都覆盖）
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
- **athena / uniknow / agentmaker 协议派系 handler**（未来按需新增 @Component，decoder 顶层零改动）
- HTML / IMAGE-IM / FILE-IM / 卡片系列 / TEXT_LIST / SLOT / processStep 等 messageType 纳入标准协议
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
