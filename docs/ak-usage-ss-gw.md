# AK 在 SS / GW 中的使用场景梳理

日期：2026-05-21  
范围：`skill-miniapp`、`skill-server`、`ai-gateway`、本地 Agent 插件、IM / External 入站、云端助手链路。

## 先说结论

AK 不是只有一种含义。当前系统里它有三种用法：

1. **个人 / 本地 Agent 场景**：AK 是“找哪台已连接插件/Agent”的路由 key。
2. **业务云端助手场景**：AK 是“查哪套云端回调配置”的业务 key，不再找本地 Agent。
3. **默认云端助手场景**：调用方可以不传 AK，SS 根据 `(domain, type)` 规则注入一个 virtual AK；发到 GW 后仍然按“业务云端助手”处理。

一句话：**个人助手靠 AK 找本地插件；云端助手靠 AK 找云端配置；默认助手先由 SS 注入 AK，再走云端。**

## 入口和 AK 从哪里来

| 入口 | 调用方传什么 | SS 怎么拿 AK | 这时 AK 的作用 |
| --- | --- | --- | --- |
| miniapp 当前页面 | `selectedAgent.ak` | 前端直接传给 `POST /api/skill/sessions` | 选中本地 Agent / 助手 |
| IM 入站 | `assistantAccount` | SS 调解析服务，把 `assistantAccount` 解析成 AK + ownerWelinkId | 定位助手、建会话、后续路由 |
| External 入站 | `assistantAccount` | 同 IM | 定位助手、建会话、后续路由 |
| 默认助手 | 不传 AK、不传 `assistantAccount`，只传 `domain/type` | SS 查 `default_assistant_rule:{domain}:{type}`，注入 virtual AK | 云端配置 key，不代表真实本地 Agent |
| 本地插件连接 GW | AK/SK 签名 | GW 握手验签后，把 AK 绑定到 WS 连接 | 后面所有下行消息靠 AK 找这条连接 |

注意：**miniapp 当前 UI 代码会要求选中 `selectedAgent`，并且建会话时总是传 `selectedAgent.ak`。所以“默认助手不传 AK”的能力在 SS 后端已经有，但不是当前 miniapp 页面默认触发的路径。**

## 场景一：miniapp + 个人 / 本地 Agent

这是最传统的链路：用户在 miniapp 里和本地插件里的 OpenCode 对话。

### 1. 插件先上线

1. 插件读取自己的 AK/SK。
2. 插件发起 GW WebSocket 连接，请求头子协议里带 `auth.{base64-json}`。
3. 这个 JSON 里有 `ak / ts / nonce / sign`。
4. GW 用 AK/SK 验签，验证通过后拿到 `userId`。
5. 插件随后发 `register`。
6. GW 做设备绑定校验、全局重复连接校验。
7. 通过后，GW 把这个 AK 绑定到：
   - 本机内存里的 WebSocket session。
   - Redis 的 `conn:ak` / `gw:internal:agent:{ak}`。
   - AK -> userId 映射。
8. GW 通知 SS：这个 AK 对应的 Agent online。

到这里，AK 的意思很明确：**这个 AK 现在对应一条可用的插件连接。**

### 2. miniapp 选择 AK

1. miniapp 调 `GET /api/skill/agents`。
2. SS 转调 GW 查询当前用户在线 Agent。
3. 返回的列表里每个 Agent 都带 `ak`。
4. miniapp 自动选择唯一 Agent，或者用户手动选择。
5. 当前选中的值就是 `selectedAgent.ak`。

### 3. 用户新建会话

1. miniapp 调 `POST /api/skill/sessions`。
2. 请求体带：
   - `ak`
   - `title`
   - `businessSessionDomain = miniapp`
   - `businessSessionType = direct`
   - 可选 `businessSessionId`
3. SS 创建 `SkillSession`，把 AK 写进会话。
4. SS 查 `AssistantInfo`，决定这个 AK 是 personal 还是 business。
5. 如果是 personal：
   - SS 不生成 `toolSessionId`。
   - SS 发 `create_session` invoke 给 GW。
6. GW 收到后，因为没有 `assistantScope=business`，按 personal 走原有逻辑。
7. GW 用 AK 找本地插件连接。
8. 找到后，把 `create_session` 发给插件。

### 4. 插件创建真实工具会话

1. 插件收到 `create_session`。
2. 插件在 OpenCode 侧创建真实会话。
3. 插件回发 `session_created`，里面带：
   - `toolSessionId`
   - `welinkSessionId`，也就是 SS 的 `SkillSession.id`
4. GW 给消息补上 AK 和 userId。
5. GW 根据 `welinkSessionId/toolSessionId` 把消息路由回 SS。
6. SS 把 `toolSessionId` 绑定回 `SkillSession`。
7. 如果之前有 pending 用户消息，SS 会在这里重放。

到这里，会话正式可用。

### 5. 用户发送普通消息

1. miniapp 先在 UI 里乐观展示用户消息。
2. miniapp 调 `POST /api/skill/sessions/{sessionId}/messages`。
3. SS 校验会话归属、会话状态、助手删除态。
4. SS 保存用户消息。
5. SS 通过 WebSocket/Redis 用户通道把用户消息广播回 miniapp。
6. SS 做 personal 在线检查：用 AK 查 Agent 是否在线。
7. SS 构造 invoke：
   - 顶层 `ak`
   - 顶层 `userId`
   - 顶层 `action=chat`
   - payload 里有 `text/toolSessionId/assistantAccount/sendUserAccount/messageId/businessExtParam`
8. GW 校验 `source/ak/userId`。
9. GW 写路由表：
   - `toolSessionId -> SS 实例`
   - `welinkSessionId -> SS 实例`
10. GW 用 AK 找插件：
   - 先找本机插件连接。
   - 本机没有，就查 Redis 找其他 GW。
   - 都找不到，就进 pending 队列，等插件上线再投递。
11. 插件收到 `chat`，调用 OpenCode。

### 6. 回复如何回来

1. 插件把 OpenCode 事件发回 GW，例如 `tool_event/tool_done/tool_error/permission_request`。
2. GW 给事件补 AK/userId/traceId。
3. GW 按 `welinkSessionId/toolSessionId` 路由到 SS。
4. SS 根据 AK 查助手 scope：
   - personal 默认用 `OpenCodeEventTranslator`。
   - 如果事件显式带 `protocol=cloud`，会切到 `CloudEventTranslator`。
5. SS 把原始事件翻译成前端协议 `StreamMessage`。
6. SS 给消息补：
   - `sessionId`
   - `welinkSessionId`
   - `emittedAt`
   - message/part 上下文
7. miniapp 会话走 `MiniappDeliveryStrategy`，通过 Redis 用户通道推给前端 WebSocket。
8. miniapp 的 `useSkillStream` 收到消息后，用 `StreamAssembler` 合并流式片段并刷新页面。
9. `tool_done` 到达后，SS 发 `sessionStatus=idle`，miniapp 结束本轮流式状态。

## 场景二：miniapp + 业务云端助手

如果 miniapp 创建的会话带了一个 business 类型 AK，链路会和 personal 不一样。

### 关键差异

1. SS 仍然会把 AK 写入 `SkillSession`。
2. SS 查 `AssistantInfo`，如果 `assistantScope=business` 且 `businessTag` 通过白名单，就选择 `BusinessScopeStrategy`。
3. business 策略会本地生成 `toolSessionId`。
4. SS 不再发 `create_session` 给本地插件。
5. SS 不等 `session_created` 回调。
6. SS 不做 Agent 在线检查。
7. 发给 GW 的 invoke 顶层会带 `assistantScope=business`。
8. GW 看到 `assistantScope=business` 后，不走 `dispatchToAgent`，直接进入 `CloudAgentService`。

也就是说，这里 AK 不再是“找哪台插件”，而是“用哪个 AK 去查云端回调配置”。

### 发送消息到云端

SS 会把用户消息转换成云端请求：

1. 从 payload 取 `text`。
2. 把 `toolSessionId` 当作云端 `topicId`。
3. 带上 `assistantAccount`。
4. 带上真实发送人 `sendUserAccount`。
5. 群聊带 `imGroupId`，单聊通常为空。
6. 带 `messageId`。
7. 把 `businessExtParam` 和 `platformExtParam` 放进 `extParameters`。
8. 根据 `businessTag` 解析 `cloudProfile`。
9. 最后发给 GW：
   - 顶层 `ak`
   - 顶层 `assistantScope=business`
   - payload.cloudRequest
   - payload.toolSessionId
   - payload.cloudProfile

### GW 云端调用

1. GW 收到 invoke。
2. 看到 `assistantScope=business`。
3. GW 学习路由，把 `toolSessionId/welinkSessionId` 写入路由表。
4. GW 进入 `BusinessInvokeRouteStrategy`。
5. `BusinessInvokeRouteStrategy` 调 `CloudAgentService.handleInvoke`。
6. `CloudAgentService` 根据 action 映射 callback scope：
   - `chat -> callback:weagent:chat`
   - `question_reply -> callback:weagent:question_reply`
   - `permission_reply -> callback:weagent:permission_reply`
7. GW 用 `(ak, callback scope, cloudProfile)` 查云端回调配置。
8. 如果查不到配置：
   - GW 回流 `tool_error`
   - reason 是 `callback_config_missing`
   - SS 不会把它误判成 session 失效重建。
9. 如果查到了配置：
   - `chat` 必须是 `sse` 或 `websocket`。
   - `question_reply/permission_reply` 必须是 `webhook`。
10. `chat` 走流式连接，云端事件一条条回流。
11. `question_reply/permission_reply` 走 webhook，成功后不回流消息；失败才回流 `tool_error`。

### 云端回复如何结束

1. 云端 SSE/WebSocket 推事件给 GW。
2. GW 给每条事件补：
   - `ak`
   - `userId`
   - `welinkSessionId`
   - `toolSessionId`
   - `traceId`
3. 如果云端没有 `messageId/partId`，GW 会兜底生成。
4. GW 把事件路由回 SS。
5. SS 用 `CloudEventTranslator` 翻译。
6. miniapp 场景照常走 `MiniappDeliveryStrategy` 推 WebSocket。
7. 最终 `tool_done` 到达后，SS 推 `idle` 状态，本轮回复结束。

## 场景三：IM 入站 + 个人 / 云端助手

IM 入口和 miniapp 最大不同是：IM 请求通常不直接传 AK，而是传 `assistantAccount`。

### 1. IM 请求进入 SS

IM 平台调用 `POST /api/inbound/messages`，请求里必须有：

- `businessDomain=im`
- `sessionType=direct/group`
- `sessionId`
- `assistantAccount`
- `senderUserAccount`
- `content`
- `msgType=text`
- 可选 `chatHistory`
- 可选 `businessExtParam`

SS 控制器先做基础校验。这里不允许 `assistantAccount` 或 `senderUserAccount` 为空。

### 2. `assistantAccount -> AK`

1. SS 调 `AssistantAccountResolverService.resolveWithStatus(assistantAccount)`。
2. 如果助手不存在，直接返回 410。
3. 如果解析状态未知，直接返回 404。
4. 如果存在，拿到：
   - AK
   - ownerWelinkId
5. 后面会话查找、在线检查、发送 GW 都用这个 AK。

### 3. 查或建会话

SS 用四元组查会话：

`businessDomain + sessionType + sessionId + ak`

分三种情况：

1. **没有会话**：
   - SS 创建 `SkillSession`。
   - personal：把用户消息放进 pending，发 `create_session` 给 GW，等插件回 `session_created` 后重放。
   - business：本地生成 `toolSessionId`，马上发 `chat` 到 GW，不等 `session_created`。
2. **有会话但没有 `toolSessionId`**：
   - business：尝试自愈，重新生成 `toolSessionId`。
   - personal：触发 rebuild，等插件补回 `session_created`。
3. **会话已就绪**：
   - 直接构造 `chat` invoke 发 GW。

### 4. IM 发给 GW 的 payload

IM 场景发出的 payload 会带这些字段：

- `text`
- `toolSessionId`
- `assistantAccount`
- `sendUserAccount`
- `imGroupId`，群聊才有
- `messageId`
- `businessExtParam`

AK 在顶层，不在 payload 里。顶层 AK 负责告诉 GW：这条消息属于哪个助手/配置。

### 5. personal 和 business 的分叉

如果是 personal：

1. SS 需要检查本地 Agent 是否在线。
2. GW 用 AK 找插件连接。
3. 插件回复后，SS 把回复转成 IM 可发送文本。

如果是 business：

1. SS 跳过在线检查。
2. SS 不写 personal pending，避免云端消息被重复重放。
3. SS 生成云端请求。
4. GW 用 AK 查云端配置并调用云端。

### 6. IM 回复怎么发回去

SS 收到回流事件后：

1. 先用 `toolSessionId/welinkSessionId` 找到 `SkillSession`。
2. 再用会话里的 AK 判断 scope。
3. 如果是 business IM，会过滤掉一些不适合发到 IM 的扩展事件，例如 planning、thinking、search、reference、ask_more。
4. IM 非流式，所以 SS 会累积 `text.delta`。
5. 收到 `text.done`，或者 `tool_done` 时发现有累积文本，就合成最终文本。
6. `ImRestDeliveryStrategy` 只把这些类型发到 IM：
   - `text.done`
   - `error/session_error`
   - `permission_ask`
   - `question`
7. 真正调用 IM 出站时，发送方用 `session.assistantAccount`。

## 场景四：External 入站

External 入口和 IM 很像，只是接口更通用。

支持的 action：

- `chat`
- `question_reply`
- `permission_reply`
- `rebuild`

共同点：

1. 请求必须带 `assistantAccount`。
2. SS 仍然先解析 `assistantAccount -> AK`。
3. 再用 AK 查会话。
4. 再按 personal/business/default 的策略发 GW。
5. 回复投递由 `OutboundDeliveryDispatcher` 根据 invoke-source 标记决定：
   - 来源是 IM，走 IM REST。
   - 来源是 EXTERNAL，走 External WS。
   - 没标记则按 session 类型兜底。

## 场景五：默认云端助手

这是之前容易漏掉的“云端助手”场景。

### 触发条件

调用 `POST /api/skill/sessions` 时：

1. 没传 AK。
2. 没传 `assistantAccount`。
3. 传了 `businessSessionDomain` 和 `businessSessionType`。
4. SS 能在 `sys_config` 里查到：

`config_type=default_assistant_rule`  
`config_key={domain}:{type}`

规则内容里必须有：

- virtual `ak`
- virtual `assistantAccount`
- `businessTag`

### 建会话

1. SS 查规则。
2. 命中后，把 virtual AK 和 virtual `assistantAccount` 注入 `SkillSession`。
3. SS 本地生成 `toolSessionId`。
4. SS 不发 `create_session` 给 GW。
5. SS 不等插件回调。

### 发消息

1. 后续 `sendMessage/replyPermission` 会先看这个 session 的 `(domain, type)` 是否命中默认助手规则。
2. 命中后跳过助手删除校验，因为 virtual `assistantAccount` 上游不一定存在。
3. `AssistantScopeDispatcher` 直接选择 `DefaultAssistantScopeStrategy`。
4. 策略重新按 `(domain, type)` 查规则。
5. 用规则里的 `assistantAccount/businessTag` 构建 cloudRequest。
6. wire 上仍然发：
   - 顶层 `ak = virtual AK`
   - 顶层 `assistantScope=business`
   - payload.cloudRequest
   - payload.toolSessionId
   - payload.cloudProfile

### 到 GW 后

GW 不知道它是“默认助手”，也不需要知道。

对 GW 来说，它就是一条 business 云端 invoke：

1. 用 virtual AK + callback scope + cloudProfile 查回调配置。
2. 调云端 SSE/WebSocket 或 webhook。
3. 把云端事件回流给 SS。
4. SS 用 `CloudEventTranslator` 翻译并投递。

所以默认助手的重点是：**AK 不是用户传来的，是 SS 按规则注入的；但一旦发到 GW，它和业务云端助手走同一条云端路由。**

## AK 在每一步到底干了什么

| 阶段 | personal / 本地 Agent | business / 云端助手 | 默认助手 |
| --- | --- | --- | --- |
| 身份来源 | miniapp 选中 AK，或 IM assistantAccount 解析出 AK | 显式 AK，或 IM assistantAccount 解析出 AK | SS 规则注入 virtual AK |
| 建会话 | AK 写入 `SkillSession` | AK 写入 `SkillSession` | virtual AK 写入 `SkillSession` |
| toolSessionId | 等插件 `session_created` | SS 本地生成 | SS 本地生成 |
| 在线检查 | 用 AK 查 Agent 在线 | 跳过 | 跳过 |
| GW 下行 | GW 用 AK 找插件连接 | GW 用 AK 查云端 callback config | GW 用 virtual AK 查云端 callback config |
| 回复路由 | 主要靠 `toolSessionId/welinkSessionId` 回 SS，AK 做上下文 | 同左 | 同左 |
| 翻译器 | 默认 OpenCode，事件声明 cloud 时可走 Cloud | Cloud | Cloud |
| 投递 | miniapp WS / IM REST / External WS | miniapp WS / IM REST / External WS | miniapp WS / IM REST / External WS |

## 容易混淆的点

1. **业务云端助手不是“Agent 离线也能走 personal”**  
   业务云端助手是另一条路：SS 明确发 `assistantScope=business`，GW 直接调云端。

2. **默认助手不是没有 AK**  
   调用方可以不传 AK，但 SS 会注入 virtual AK。GW 侧仍然靠这个 AK 查配置。

3. **cloudProfile 不等于 AK**  
   AK 决定“哪套回调配置”；cloudProfile 决定“用哪套云端请求/响应协议套餐”。

4. **回流不主要靠 AK 找 SS**  
   回流通常靠 `toolSessionId/welinkSessionId` 找 SS 实例。AK 会随消息一起带回来，用于翻译、日志、上下文和兜底。

5. **IM 入站不信任调用方传 AK**  
   IM 传的是 `assistantAccount`，SS 自己解析出 AK。这样能统一处理删除态、未知态和 owner 信息。

6. **miniapp 当前不是默认助手入口**  
   当前 miniapp 页面建会话时必须有 `selectedAgent.ak`。默认助手能力在 SS 有，但当前页面代码没有走“不传 AK”的路径。

## 主要源码依据

- miniapp 选择 AK / 建会话 / 发消息：
  - `skill-miniapp/src/hooks/useAgentSelector.ts`
  - `skill-miniapp/src/pages/SkillMain.tsx`
  - `skill-miniapp/src/hooks/useSkillStream.ts`
  - `skill-miniapp/src/utils/api.ts`
- SS 会话、消息、IM 入站：
  - `skill-server/src/main/java/com/opencode/cui/skill/controller/SkillSessionController.java`
  - `skill-server/src/main/java/com/opencode/cui/skill/controller/SkillMessageController.java`
  - `skill-server/src/main/java/com/opencode/cui/skill/controller/ImInboundController.java`
  - `skill-server/src/main/java/com/opencode/cui/skill/controller/ExternalInboundController.java`
  - `skill-server/src/main/java/com/opencode/cui/skill/service/InboundProcessingService.java`
  - `skill-server/src/main/java/com/opencode/cui/skill/service/ImSessionManager.java`
- SS scope / 云端请求：
  - `skill-server/src/main/java/com/opencode/cui/skill/service/scope/AssistantScopeDispatcher.java`
  - `skill-server/src/main/java/com/opencode/cui/skill/service/scope/PersonalScopeStrategy.java`
  - `skill-server/src/main/java/com/opencode/cui/skill/service/scope/BusinessScopeStrategy.java`
  - `skill-server/src/main/java/com/opencode/cui/skill/service/scope/DefaultAssistantScopeStrategy.java`
  - `skill-server/src/main/java/com/opencode/cui/skill/service/DefaultAssistantRuleService.java`
- GW 本地 Agent / 云端路由：
  - `ai-gateway/src/main/java/com/opencode/cui/gateway/ws/AgentWebSocketHandler.java`
  - `ai-gateway/src/main/java/com/opencode/cui/gateway/service/EventRelayService.java`
  - `ai-gateway/src/main/java/com/opencode/cui/gateway/service/SkillRelayService.java`
  - `ai-gateway/src/main/java/com/opencode/cui/gateway/service/cloud/BusinessInvokeRouteStrategy.java`
  - `ai-gateway/src/main/java/com/opencode/cui/gateway/service/CloudAgentService.java`
  - `ai-gateway/src/main/java/com/opencode/cui/gateway/service/CallbackConfigService.java`
  - `ai-gateway/src/main/java/com/opencode/cui/gateway/service/cloud/WebHookExecutor.java`
  - `ai-gateway/src/main/java/com/opencode/cui/gateway/service/cloud/CloudProtocolClient.java`
- SS 回流投递：
  - `skill-server/src/main/java/com/opencode/cui/skill/service/GatewayMessageRouter.java`
  - `skill-server/src/main/java/com/opencode/cui/skill/service/delivery/StreamMessageEmitter.java`
  - `skill-server/src/main/java/com/opencode/cui/skill/service/delivery/OutboundDeliveryDispatcher.java`
  - `skill-server/src/main/java/com/opencode/cui/skill/service/delivery/MiniappDeliveryStrategy.java`
  - `skill-server/src/main/java/com/opencode/cui/skill/service/delivery/ImRestDeliveryStrategy.java`
- 插件 AK/SK：
  - `plugins/agent-plugin/plugins/message-bridge/src/connection/AkSkAuth.ts`
  - `plugins/agent-plugin/plugins/message-bridge/src/connection/GatewayConnection.ts`
