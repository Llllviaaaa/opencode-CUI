# 云端 Agent 对接 完整测试计划

---

## 1. 测试范围

### 1.1 被测系统边界

```
MiniApp → SS → GW → 云端(mock)
IM      → SS → GW → 云端(mock) → GW → SS → IM(mock)
云端推送 → GW → SS → IM(mock)
```

### 1.2 测试层级

| 层级 | 说明 | 工具 |
|------|------|------|
| 单元测试 | 单类/单方法，mock 依赖 | JUnit 5 + Mockito |
| 集成测试 | 多组件协作，真实 Spring Context | @SpringBootTest |
| 端到端测试 | SS + GW + Mock 全链路 | Mock Server + HTTP 调用 |
| 协议一致性测试 | 验证报文格式符合协议文档 | JSON Schema / 手动验证 |

---

## 2. 测试用例矩阵

### 2.1 SS：AssistantInfoService

| # | 用例 | 输入 | 期望 | 层级 |
|---|------|------|------|------|
| S01 | 缓存命中 | Redis 有值 | 直接返回，不调上游 | 单元 |
| S02 | 缓存未命中 → 上游返回 business | Redis 无值，上游 identityType=3 | 返回 scope=business，写缓存 | 单元 |
| S03 | 缓存未命中 → 上游返回 personal | Redis 无值，上游 identityType=2 | 返回 scope=personal，写缓存 | 单元 |
| S04 | 上游 API 不可用 | HTTP 连接失败 | 返回 null | 单元 |
| S05 | 上游返回非 200 code | code="500" | 返回 null | 单元 |
| S06 | getCachedScope null 降级 | getAssistantInfo 返回 null | 返回 "personal" | 单元 |
| S07 | 缓存 TTL 过期后重新查询 | TTL 过期 | 重新调上游，刷新缓存 | 集成 |

### 2.2 SS：SysConfigService

| # | 用例 | 输入 | 期望 | 层级 |
|---|------|------|------|------|
| S08 | getValue 缓存命中 | Redis 有值 | 直接返回 | 单元 |
| S09 | getValue 缓存未命中 → DB 有值且启用 | DB status=1 | 返回值，写缓存 | 单元 |
| S10 | getValue DB 值禁用 | DB status=0 | 返回 null | 单元 |
| S11 | getValue DB 无记录 | DB 无匹配 | 返回 null | 单元 |
| S12 | create 后缓存清除 | 新增配置 | 旧缓存被清 | 单元 |
| S13 | update 后缓存清除 | 修改配置 | 旧缓存被清 | 单元 |
| S14 | CRUD 管理接口 | REST 调用 | 正确增删改查 | 集成 |

### 2.3 SS：CloudRequestBuilder

| # | 用例 | 输入 | 期望 | 层级 |
|---|------|------|------|------|
| S15 | 未配置 appId → 默认策略 | SysConfig 无记录 | 走 DefaultCloudRequestStrategy | 单元 |
| S16 | 配置 appId → default 策略 | SysConfig value="default" | 走 DefaultCloudRequestStrategy | 单元 |
| S17 | 配置 appId → 自定义策略 | SysConfig value="custom-v2" | 走对应策略 | 单元 |
| S18 | 配置策略名不存在 → 降级默认 | SysConfig value="nonexist" | 走 DefaultCloudRequestStrategy | 单元 |
| S19 | DefaultStrategy 字段映射完整 | 完整 context | JSON 包含所有字段 | 单元 |
| S20 | DefaultStrategy null 处理 | contentType=null, extParameters=null | 默认值正确 | 单元 |

### 2.4 SS：CloudEventTranslator

| # | 用例 | 输入 | 期望 | 层级 |
|---|------|------|------|------|
| S21 | text.delta 翻译 | event.type=text.delta | StreamMessage type=TEXT_DELTA, content/role 正确 | 单元 |
| S22 | text.done 翻译 | event.type=text.done | type=TEXT_DONE, 含 messageId/partId | 单元 |
| S23 | thinking.delta 翻译 | event.type=thinking.delta | type=THINKING_DELTA | 单元 |
| S24 | thinking.done 翻译 | event.type=thinking.done | type=THINKING_DONE | 单元 |
| S25 | tool.update 翻译 | event.type=tool.update | type=TOOL_UPDATE, toolName/status 等 | 单元 |
| S26 | step.start 翻译 | event.type=step.start | type=STEP_START | 单元 |
| S27 | step.done 翻译 | event.type=step.done | type=STEP_DONE, tokens/cost/reason | 单元 |
| S28 | question 翻译 | event.type=question | type=QUESTION, toolCallId/question/options | 单元 |
| S29 | permission.ask 翻译 | event.type=permission.ask | type=PERMISSION_ASK, permissionId/permType | 单元 |
| S30 | permission.reply 翻译 | event.type=permission.reply | type=PERMISSION_REPLY, response | 单元 |
| S31 | session.status 翻译 | event.type=session.status | type=SESSION_STATUS, sessionStatus | 单元 |
| S32 | session.title 翻译 | event.type=session.title | type=SESSION_TITLE, title | 单元 |
| S33 | session.error 翻译 | event.type=session.error | type=SESSION_ERROR, error | 单元 |
| S34 | file 翻译 | event.type=file | type=FILE, fileName/fileUrl/fileMime | 单元 |
| S35 | planning.delta 翻译 | event.type=planning.delta | type=PLANNING_DELTA, content | 单元 |
| S36 | planning.done 翻译 | event.type=planning.done | type=PLANNING_DONE, content | 单元 |
| S37 | searching 翻译 | event.type=searching | type=SEARCHING, keywords 列表 | 单元 |
| S38 | search_result 翻译 | event.type=search_result | type=SEARCH_RESULT, searchResults 列表 | 单元 |
| S39 | reference 翻译 | event.type=reference | type=REFERENCE, references 列表 | 单元 |
| S40 | ask_more 翻译 | event.type=ask_more | type=ASK_MORE, askMoreQuestions 列表 | 单元 |
| S41 | 未知 event.type | event.type=unknown | 返回 null | 单元 |
| S42 | event 为 null | null 输入 | 返回 null / 不异常 | 单元 |
| S43 | event 无 type 字段 | {} | 返回 null | 单元 |

### 2.5 SS：AssistantScopeStrategy 调度

| # | 用例 | 输入 | 期望 | 层级 |
|---|------|------|------|------|
| S44 | getStrategy("business") | scope=business | BusinessScopeStrategy | 单元 |
| S45 | getStrategy("personal") | scope=personal | PersonalScopeStrategy | 单元 |
| S46 | getStrategy(null) | scope=null | 降级 PersonalScopeStrategy | 单元 |
| S47 | getStrategy("unknown") | scope=unknown | 降级 PersonalScopeStrategy | 单元 |
| S48 | BusinessScope.generateToolSessionId | - | 返回 "cloud-" 前缀 | 单元 |
| S49 | BusinessScope.requiresSessionCreatedCallback | - | false | 单元 |
| S50 | BusinessScope.requiresOnlineCheck | - | false | 单元 |
| S51 | PersonalScope.generateToolSessionId | - | null | 单元 |
| S52 | PersonalScope.requiresSessionCreatedCallback | - | true | 单元 |
| S53 | PersonalScope.requiresOnlineCheck | - | true | 单元 |

### 2.6 SS：GatewayRelayService 集成

| # | 用例 | 输入 | 期望 | 层级 |
|---|------|------|------|------|
| S54 | business 助手 invoke 构建 | ak=business | invoke 中含 assistantScope="business" + cloudRequest | 集成 |
| S55 | personal 助手 invoke 构建 | ak=personal | 走现有 buildInvokeMessage 逻辑 | 集成 |
| S56 | business invoke 的 cloudRequest 包含 topicId | - | cloudRequest.topicId = toolSessionId | 集成 |

### 2.7 SS：会话创建

| # | 用例 | 输入 | 期望 | 层级 |
|---|------|------|------|------|
| S57 | business 助手创建会话 | scope=business | toolSessionId 本地生成 "cloud-*"，不发 create_session | 集成 |
| S58 | personal 助手创建会话 | scope=personal | toolSessionId=null，发 create_session 给 GW | 集成 |

### 2.8 SS：Agent 在线检查

| # | 用例 | 输入 | 期望 | 层级 |
|---|------|------|------|------|
| S59 | business 助手 IM inbound | scope=business, Agent 离线 | 不检查在线，正常处理 | 集成 |
| S60 | personal 助手 IM inbound | scope=personal, Agent 离线 | 检查在线，返回离线错误 | 集成 |
| S61 | business 助手 MiniApp 消息 | scope=business, Agent 离线 | 不检查在线，正常处理 | 集成 |

### 2.9 SS：事件翻译路由

| # | 用例 | 输入 | 期望 | 层级 |
|---|------|------|------|------|
| S62 | business tool_event → CloudEventTranslator | ak=business, event=text.delta | 使用 CloudEventTranslator | 集成 |
| S63 | personal tool_event → OpenCodeEventTranslator | ak=personal, event=message.part.delta | 使用 OpenCodeEventTranslator | 集成 |

### 2.10 SS：IM 出站过滤

| # | 用例 | 输入 | 期望 | 层级 |
|---|------|------|------|------|
| S64 | business IM 收到 text.done | text.done 事件 | 发送到 IM | 集成 |
| S65 | business IM 收到 planning.delta | planning.delta 事件 | 不发送到 IM | 集成 |
| S66 | business IM 收到 thinking.delta | thinking.delta 事件 | 不发送到 IM | 集成 |
| S67 | business IM 收到 searching | searching 事件 | 不发送到 IM | 集成 |
| S68 | business IM 收到 search_result | search_result 事件 | 不发送到 IM | 集成 |
| S69 | business IM 收到 reference | reference 事件 | 不发送到 IM | 集成 |
| S70 | business IM 收到 ask_more | ask_more 事件 | 不发送到 IM | 集成 |
| S71 | personal IM 不受影响 | 正常 text.done | 发送到 IM（现有逻辑） | 集成 |

### 2.11 SS：IM 推送处理

| # | 用例 | 输入 | 期望 | 层级 |
|---|------|------|------|------|
| S72 | im_push 单聊 | imGroupId=null | 调 IM 单聊出站 | 单元 |
| S73 | im_push 群聊 | imGroupId 有值 | 调 IM 群聊出站 | 单元 |
| S74 | im_push topicId 无对应 session | topicId 不存在 | 忽略，打 warn 日志 | 单元 |
| S75 | im_push assistantAccount 不匹配 | 账号与 session 的 ak 不对应 | 拒绝，打 warn 日志 | 单元 |
| S76 | im_push 内容为空 | content="" | 正常发送（或跳过，视业务） | 单元 |

### 2.12 GW：CloudRouteService

| # | 用例 | 输入 | 期望 | 层级 |
|---|------|------|------|------|
| G01 | 缓存命中 | Redis 有值 | 直接返回 | 单元 |
| G02 | 缓存未命中 → 上游返回 | 正常响应 | 返回 CloudRouteInfo，写缓存 | 单元 |
| G03 | 上游不可用 | 连接失败 | 返回 null | 单元 |
| G04 | 上游 code 非 200 | code="500" | 返回 null | 单元 |
| G05 | 正确解析全字段 | 完整响应 | appId/endpoint/protocol/authType 正确 | 单元 |

### 2.13 GW：CloudAuthService

| # | 用例 | 输入 | 期望 | 层级 |
|---|------|------|------|------|
| G06 | authType=soa | soa | 调 SoaAuthStrategy | 单元 |
| G07 | authType=apig | apig | 调 ApigAuthStrategy | 单元 |
| G08 | authType=unknown | unknown | 抛 IllegalArgumentException | 单元 |
| G09 | authType=null | null | 抛异常 | 单元 |

### 2.14 GW：CloudProtocolClient

| # | 用例 | 输入 | 期望 | 层级 |
|---|------|------|------|------|
| G10 | protocol=sse | sse | 调 SseProtocolStrategy | 单元 |
| G11 | protocol=unknown | unknown | 调 onError | 单元 |
| G12 | protocol=null | null | 调 onError | 单元 |

### 2.15 GW：CloudAgentService

| # | 用例 | 输入 | 期望 | 层级 |
|---|------|------|------|------|
| G13 | 正常流程 | business invoke | 获取路由 → 连接 → 注入上下文 → relay | 单元 |
| G14 | 路由信息获取失败 | CloudRouteService 返回 null | 构建 tool_error 回传 SS | 单元 |
| G15 | 连接错误 | SSE 连接失败 | 构建 tool_error 回传 SS | 单元 |
| G16 | 事件路由上下文注入 | tool_event 回来 | ak/userId/welinkSessionId/traceId 正确注入 | 单元 |
| G17 | toolSessionId 注入 | 云端未回传 toolSessionId | 从原始 invoke 注入 | 单元 |
| G18 | toolSessionId 保留 | 云端回传了 toolSessionId | 不覆盖 | 单元 |

### 2.16 GW：SkillRelayService 路由

| # | 用例 | 输入 | 期望 | 层级 |
|---|------|------|------|------|
| G19 | assistantScope=business | business invoke | 走 BusinessInvokeRouteStrategy → CloudAgentService | 集成 |
| G20 | assistantScope=personal | personal invoke | 走现有逻辑（不走 CloudAgentService） | 集成 |
| G21 | assistantScope=null | 无 scope | 走现有逻辑（默认 personal） | 集成 |

### 2.17 GW：CloudPushController

| # | 用例 | 输入 | 期望 | 层级 |
|---|------|------|------|------|
| G22 | 正常推送 | 完整 ImPushRequest | 构建 im_push GatewayMessage，toolSessionId=topicId | 单元 |
| G23 | topicId 用于路由 | topicId="cloud-123" | GatewayMessage.toolSessionId="cloud-123" | 单元 |

### 2.18 GW：SseProtocolStrategy

| # | 用例 | 输入 | 期望 | 层级 |
|---|------|------|------|------|
| G24 | 正常 SSE 读取 | Mock 返回多条 data: | 每条解析为 GatewayMessage，逐条调 onEvent | 集成 |
| G25 | SSE 含 tool_done | 最后一条 tool_done | 正常解析，连接关闭 | 集成 |
| G26 | SSE 含 tool_error | tool_error 事件 | 正常解析，连接关闭 | 集成 |
| G27 | HTTP 非 200 | Mock 返回 429 | 调 onError | 集成 |
| G28 | SSE 行格式错误 | 非法 JSON | 跳过该行，继续读取 | 集成 |
| G29 | 连接超时 | 不可达地址 | 调 onError | 集成 |
| G30 | 认证头正确设置 | authType=soa | 请求头含认证信息 | 集成 |

### 2.19 前端：StreamAssembler

| # | 用例 | 输入 | 期望 | 层级 |
|---|------|------|------|------|
| F01 | planning.delta 追加 | planning.delta msg | 创建 planning part, content 追加 | 单元 |
| F02 | planning.done 替换 | planning.done msg | planning part content 替换, isStreaming=false | 单元 |
| F03 | searching | searching msg | 创建 searching part, keywords 设置 | 单元 |
| F04 | search_result | search_result msg | 创建 search_result part, searchResults 设置 | 单元 |
| F05 | reference | reference msg | 创建 reference part, references 设置 | 单元 |
| F06 | ask_more | ask_more msg | 创建 ask_more part, askMoreQuestions 设置 | 单元 |

---

## 3. 端到端场景（需 Mock Server + SS + GW）

### E2E-01：业务助手 MiniApp 对话全链路

**前置**：Mock server 运行，SS/GW 指向 mock，ak=test-business-ak 已注册

```
1. POST SS /api/skill/sessions 创建会话（ak=test-business-ak）
2. 验证 DB: toolSessionId 为 "cloud-*" 格式
3. POST SS /api/skill/sessions/{id}/messages 发消息
4. 验证 WS /ws/skill/stream 收到事件序列：
   - planning.delta → planning.done
   - searching → search_result → reference
   - thinking.delta → thinking.done
   - text.delta *N → text.done
   - ask_more
   - session.status(idle)
5. 验证 mock SSE 接口收到请求（topicId = toolSessionId）
```

### E2E-02：业务助手 IM 对话全链路

```
1. POST SS /api/inbound/messages（ak=test-business-ak, businessDomain=im）
2. 验证不检查 Agent 在线
3. 验证 mock SSE 接口收到请求
4. 验证 mock IM 出站只收到 text 内容（无 planning/thinking/searching 等）
5. 验证 IM 消息内容正确
```

### E2E-03：个人助手回归（不走云端）

```
1. POST SS /api/skill/sessions 创建会话（ak=test-personal-ak）
2. 验证 DB: toolSessionId 为 null（等待 session_created）
3. 验证不调用 mock SSE 接口
4. 验证走现有 PC Agent 路由
```

### E2E-04：IM 推送全链路

```
1. 先通过 E2E-01 创建一个 business 会话（让路由映射存在）
2. POST GW /api/gateway/cloud/im-push
3. 验证 mock IM 出站收到推送消息
4. 验证 content 正确
```

### E2E-05：IM 推送校验失败

```
1. POST GW /api/gateway/cloud/im-push（topicId 不存在）
2. 验证 mock IM 出站不收到消息
3. POST GW /api/gateway/cloud/im-push（assistantAccount 不匹配）
4. 验证 mock IM 出站不收到消息
```

### E2E-06：云端不可用

```
1. 关闭 mock SSE 接口
2. 发送 business 助手消息
3. 验证 SS 收到 tool_error
4. 验证前端/IM 收到错误消息
```

### E2E-07：上游 API 不可用 + 缓存降级

```
1. 先正常请求一次（缓存写入）
2. 关闭 mock 上游 API
3. 再次请求
4. 验证从缓存读取，正常处理
```

---

## 4. 测试数据

### Mock AK

| AK | 类型 | appId | endpoint |
|----|------|-------|----------|
| test-business-ak | business (3) | app_test_001 | http://localhost:9999/api/v1/chat |
| test-personal-ak | personal (2) | - | - |

### Mock SSE 事件序列

```
planning.delta → planning.done → searching → search_result → reference
→ thinking.delta → thinking.done → text.delta *N → text.done → ask_more → tool_done
```

---

## 5. 已有测试覆盖统计

| 模块 | 测试类 | 用例数 | 覆盖用例 |
|------|--------|--------|---------|
| SS | SysConfigServiceTest | 8 | S08-S13 |
| SS | AssistantInfoServiceTest | 8 | S01-S06 |
| SS | CloudEventTranslatorTest | 23 | S21-S43 |
| SS | CloudRequestBuilderTest | 9 | S15-S20 |
| SS | AssistantScopeDispatcherTest | 4 | S44-S47 |
| SS | BusinessScopeStrategyTest | 7 | S48-S50 + 部分 |
| SS | GatewayMessageRouterImPushTest | 6 | S72-S76 |
| GW | CloudRouteServiceTest | 6 | G01-G05 |
| GW | CloudAuthServiceTest | 4 | G06-G09 |
| GW | CloudProtocolClientTest | 4 | G10-G12 |
| GW | CloudAgentServiceTest | 5 | G13-G18 |
| GW | InvokeRouteStrategyTest | 7 | G19-G21 |
| **合计** | | **91** | |

### 缺失的测试

| 用例范围 | 缺失内容 |
|---------|---------|
| S07 | 缓存 TTL 过期重查（集成测试） |
| S14 | SysConfigController CRUD 接口（集成测试） |
| S54-S56 | GatewayRelayService 集成（business/personal invoke 构建） |
| S57-S58 | 会话创建流程（toolSessionId 生成） |
| S59-S61 | Agent 在线检查跳过 |
| S62-S63 | 事件翻译路由分派 |
| S64-S71 | IM 出站过滤 |
| G22-G23 | CloudPushController |
| G24-G30 | SseProtocolStrategy 集成 |
| F01-F06 | 前端 StreamAssembler（TypeScript 测试） |
| E2E-01~07 | 端到端场景 |
