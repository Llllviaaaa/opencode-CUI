# 前端 StreamMessage 协议设计（修订版）

> 目标：定义 `Skill Server -> Miniapp` 的实时流协议，使其能稳定支持消息分组、断线恢复、历史对齐和工具交互。

## 一、设计结论

本协议不应只追踪 `partId`，还必须追踪“消息级身份”。

- 必加：`messageId`
  说明：同一条 assistant 气泡内的所有 `text/thinking/tool/question/file` 都必须归属到同一个 `messageId`
- 必加：`messageSeq`
  说明：表示该消息在当前会话中的稳定顺序，用于历史消息和实时流对齐
- 建议加：`role`
  说明：避免前端把所有实时流都硬编码成 `assistant`
- 保留：`seq`
  说明：这里的 `seq` 表示“传输序号”，只负责 WebSocket 排序、去重、丢包检测，不等价于消息顺序
- 建议加：`sessionId`
  说明：虽然当前是单会话 WebSocket，但日志、抓包、恢复和未来多路复用都需要
- 建议加：`emittedAt`
  说明：便于重放、排障、跨端比对

最终结论：协议至少应同时有两层顺序字段。

- `seq`: 传输顺序
- `messageSeq`: 会话内消息顺序

---

## 二、字段分层

### 2.1 传输层公共字段

所有 StreamMessage 都应具备以下字段：

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `type` | `StreamMessageType` | 是 | 语义化事件类型 |
| `seq` | `number` | 是 | 传输序号，按 WebSocket 推送顺序单调递增 |
| `sessionId` | `string` | 是 | Skill 会话 ID |
| `emittedAt` | `string` | 是 | 事件发出时间，ISO-8601 |
| `raw` | `object` | 否 | 原始 OpenCode 事件，用于高级调试 |

### 2.2 消息层公共字段

所有“属于某条消息气泡”的事件都应具备以下字段：

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `messageId` | `string` | 是 | Skill Server 分配的稳定消息 ID |
| `messageSeq` | `number` | 是 | 该消息在当前会话中的稳定顺序 |
| `role` | `'user' \| 'assistant' \| 'system' \| 'tool'` | 是 | 当前消息角色 |
| `sourceMessageId` | `string` | 否 | 上游 OpenCode `messageID`，用于追踪源事件 |

说明：

- `messageId` 不应依赖临时前端状态推断
- `messageId` 必须在同一条消息生命周期内保持不变
- 历史消息 API 返回的消息 ID 应与实时流里的 `messageId` 对齐

### 2.3 Part 层字段

所有“属于某条消息中的某个部件”的事件都应具备以下字段：

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `partId` | `string` | 是 | Part 唯一 ID |
| `partSeq` | `number` | 否 | Part 在消息内的稳定顺序 |

说明：

- `partId` 负责同一 Part 的增量更新
- `partSeq` 负责恢复、快照、历史回放时的排序稳定性

---

## 三、消息类型

### 3.1 内容类

这些事件会渲染到聊天气泡内部，必须带 `messageId/messageSeq/role`。

| type | 关键字段 | 说明 |
| --- | --- | --- |
| `text.delta` | `messageId, messageSeq, role, partId, content` | 文本流式追加 |
| `text.done` | `messageId, messageSeq, role, partId, content` | 文本完成 |
| `thinking.delta` | `messageId, messageSeq, role, partId, content` | 思维链流式追加 |
| `thinking.done` | `messageId, messageSeq, role, partId, content` | 思维链完成 |
| `tool.update` | `messageId, messageSeq, role, partId, toolName, toolCallId, status` | 工具状态更新 |
| `question` | `messageId, messageSeq, role, partId, toolCallId, header, question, options` | question 工具的交互问题 |
| `file` | `messageId, messageSeq, role, partId, fileName, fileUrl, fileMime` | 文件或图片附件 |

### 3.2 状态类

这些事件通常不直接形成一个新气泡，但可能从属于某条消息。

| type | 关键字段 | 说明 |
| --- | --- | --- |
| `step.start` | `messageId, messageSeq, role` | 当前消息开始执行某个推理步骤 |
| `step.done` | `messageId, messageSeq, role, tokens, cost, reason` | 当前消息步骤完成 |
| `session.status` | `sessionStatus` | 会话状态，统一使用 `sessionStatus`，不要再用通用 `status` |
| `session.title` | `title` | 会话标题变化 |
| `session.error` | `error` | 会话级错误 |

### 3.3 交互类

| type | 关键字段 | 说明 |
| --- | --- | --- |
| `permission.ask` | `messageId, messageSeq, role, permissionId, permType, title, metadata` | 权限请求 |
| `permission.reply` | `messageId, messageSeq, role, permissionId, response` | 权限响应结果 |

说明：

- `permission.reply` 是正式协议类型
- Miniapp 类型定义不得再写成 `permission.result`

### 3.4 系统类

| type | 关键字段 | 说明 |
| --- | --- | --- |
| `agent.online` | - | 关联 Agent 在线 |
| `agent.offline` | - | 关联 Agent 离线 |
| `error` | `error` | 非会话结构化错误 |

### 3.5 恢复类

这两个类型应被正式纳入协议，而不是只存在于恢复设计文档中。

| type | 关键字段 | 说明 |
| --- | --- | --- |
| `snapshot` | `messages` | 当前会话的已完成消息快照 |
| `streaming` | `sessionStatus, messageId?, messageSeq?, role?, parts` | 当前会话仍在进行中的流式消息 |

---

## 四、TypeScript 类型定义

```ts
type MessageRole = 'user' | 'assistant' | 'system' | 'tool';

type StreamMessageType =
  | 'text.delta'
  | 'text.done'
  | 'thinking.delta'
  | 'thinking.done'
  | 'tool.update'
  | 'question'
  | 'file'
  | 'step.start'
  | 'step.done'
  | 'session.status'
  | 'session.title'
  | 'session.error'
  | 'permission.ask'
  | 'permission.reply'
  | 'agent.online'
  | 'agent.offline'
  | 'error'
  | 'snapshot'
  | 'streaming';

interface BaseStreamMessage {
  type: StreamMessageType;
  seq: number;
  sessionId: string;
  emittedAt: string;
  raw?: Record<string, unknown>;
}

interface MessageScopedFields {
  messageId: string;
  messageSeq: number;
  role: MessageRole;
  sourceMessageId?: string;
}

interface PartScopedFields extends MessageScopedFields {
  partId: string;
  partSeq?: number;
}

type TextDeltaMessage = BaseStreamMessage & PartScopedFields & {
  type: 'text.delta';
  content: string;
};

type TextDoneMessage = BaseStreamMessage & PartScopedFields & {
  type: 'text.done';
  content: string;
};

type ThinkingDeltaMessage = BaseStreamMessage & PartScopedFields & {
  type: 'thinking.delta';
  content: string;
};

type ThinkingDoneMessage = BaseStreamMessage & PartScopedFields & {
  type: 'thinking.done';
  content: string;
};

type ToolUpdateMessage = BaseStreamMessage & PartScopedFields & {
  type: 'tool.update';
  toolName: string;
  toolCallId?: string;
  status: 'pending' | 'running' | 'completed' | 'error';
  input?: Record<string, unknown>;
  output?: string;
  error?: string;
  title?: string;
  metadata?: Record<string, unknown>;
};

type QuestionMessage = BaseStreamMessage & PartScopedFields & {
  type: 'question';
  toolName: 'question';
  toolCallId?: string;
  status: 'running';
  header?: string;
  question: string;
  options?: string[];
};

type FileMessage = BaseStreamMessage & PartScopedFields & {
  type: 'file';
  fileName?: string;
  fileUrl: string;
  fileMime?: string;
};

type StepStartMessage = BaseStreamMessage & MessageScopedFields & {
  type: 'step.start';
};

type StepDoneMessage = BaseStreamMessage & MessageScopedFields & {
  type: 'step.done';
  tokens?: {
    input?: number;
    output?: number;
    reasoning?: number;
    cache?: { read?: number; write?: number };
  };
  cost?: number;
  reason?: string;
};

type SessionStatusMessage = BaseStreamMessage & {
  type: 'session.status';
  sessionStatus: 'busy' | 'idle' | 'retry' | 'completed';
};

type SessionTitleMessage = BaseStreamMessage & {
  type: 'session.title';
  title: string;
};

type SessionErrorMessage = BaseStreamMessage & {
  type: 'session.error';
  error: string;
};

type PermissionAskMessage = BaseStreamMessage & MessageScopedFields & {
  type: 'permission.ask';
  permissionId: string;
  permType?: string;
  title?: string;
  metadata?: Record<string, unknown>;
};

type PermissionReplyMessage = BaseStreamMessage & MessageScopedFields & {
  type: 'permission.reply';
  permissionId: string;
  response: string;
};

type SnapshotMessage = BaseStreamMessage & {
  type: 'snapshot';
  messages: Array<{
    id: string;
    seq: number;
    role: MessageRole;
    content: string;
    contentType: 'plain' | 'markdown' | 'code';
    createdAt?: string;
    parts?: Array<Record<string, unknown>>;
    meta?: Record<string, unknown>;
  }>;
};

type StreamingMessage = BaseStreamMessage & {
  type: 'streaming';
  sessionStatus: 'busy' | 'idle';
  messageId?: string;
  messageSeq?: number;
  role?: Extract<MessageRole, 'assistant' | 'tool' | 'system'>;
  parts: Array<{
    partId: string;
    partSeq?: number;
    type: 'text' | 'thinking' | 'tool' | 'question' | 'permission' | 'file';
    content?: string;
    toolName?: string;
    toolCallId?: string;
    status?: 'pending' | 'running' | 'completed' | 'error';
    header?: string;
    question?: string;
    options?: string[];
    fileName?: string;
    fileUrl?: string;
    fileMime?: string;
    metadata?: Record<string, unknown>;
  }>;
};

type StreamMessage =
  | TextDeltaMessage
  | TextDoneMessage
  | ThinkingDeltaMessage
  | ThinkingDoneMessage
  | ToolUpdateMessage
  | QuestionMessage
  | FileMessage
  | StepStartMessage
  | StepDoneMessage
  | SessionStatusMessage
  | SessionTitleMessage
  | SessionErrorMessage
  | PermissionAskMessage
  | PermissionReplyMessage
  | SnapshotMessage
  | StreamingMessage;
```

---

## 五、字段命名约束

### 5.1 `seq` 与 `messageSeq`

- `seq`：传输顺序
  用于 WebSocket 排序、幂等处理、断线恢复缺口检测
- `messageSeq`：消息顺序
  用于会话内消息排序，必须能与历史 API 对齐

禁止把这两者混用。

### 5.2 `status` 与 `sessionStatus`

- `status` 只用于工具状态，例如 `tool.update.status`
- `sessionStatus` 只用于会话状态，例如 `session.status.sessionStatus`

### 5.3 文件字段

`file` 类型必须使用：

- `fileName`
- `fileUrl`
- `fileMime`

不要再复用：

- `title`
- `content`
- `metadata.mime`

### 5.4 权限回复字段

`permission.reply` 必须使用 `response`，例如：

- `approved`
- `rejected`
- `once`
- `always`

---

## 六、一次完整对话的推荐时序

```text
T1  session.status
    { type:"session.status", seq:1, sessionId:"42", emittedAt:"...", sessionStatus:"busy" }

T2  step.start
    { type:"step.start", seq:2, sessionId:"42", emittedAt:"...", messageId:"m_2", messageSeq:2, role:"assistant" }

T3  thinking.delta
    { type:"thinking.delta", seq:3, sessionId:"42", emittedAt:"...", messageId:"m_2", messageSeq:2, role:"assistant", partId:"p_1", partSeq:1, content:"分析需求..." }

T4  text.delta
    { type:"text.delta", seq:4, sessionId:"42", emittedAt:"...", messageId:"m_2", messageSeq:2, role:"assistant", partId:"p_2", partSeq:2, content:"好的，" }

T5  question
    { type:"question", seq:5, sessionId:"42", emittedAt:"...", messageId:"m_2", messageSeq:2, role:"assistant", partId:"p_3", partSeq:3, toolName:"question", toolCallId:"call_1", status:"running", header:"项目配置", question:"选择模板", options:["Vite","CRA"] }

T6  permission.ask
    { type:"permission.ask", seq:6, sessionId:"42", emittedAt:"...", messageId:"m_2", messageSeq:2, role:"assistant", permissionId:"perm_1", permType:"bash", title:"Run create-vite", metadata:{ command:"npx create-vite" } }

T7  text.done
    { type:"text.done", seq:7, sessionId:"42", emittedAt:"...", messageId:"m_2", messageSeq:2, role:"assistant", partId:"p_4", partSeq:4, content:"项目创建成功" }

T8  step.done
    { type:"step.done", seq:8, sessionId:"42", emittedAt:"...", messageId:"m_2", messageSeq:2, role:"assistant", tokens:{ input:5000, output:200 }, cost:0.01, reason:"stop" }

T9  session.status
    { type:"session.status", seq:9, sessionId:"42", emittedAt:"...", sessionStatus:"idle" }
```

---

## 七、与当前实现的收口要求

当前实现和文档至少要收口以下差异：

- Miniapp 类型定义要补上：
  - `session.title`
  - `session.error`
  - `permission.reply`
- Miniapp 类型定义要移除：
  - `permission.result`
- 协议实现要补上：
  - `messageId`
  - `messageSeq`
  - `role`
  - `sessionId`
  - `emittedAt`
- `session.status` 统一使用 `sessionStatus`
- `snapshot` 和 `streaming` 需要正式建型，不再作为文档外特殊包

---

## 八、最小落地顺序

如果分阶段实施，建议顺序如下：

1. 先补 `messageId/messageSeq/role`
2. 再补 `sessionId/emittedAt`
3. 再补 `snapshot/streaming` 的正式 schema
4. 最后清理命名分叉：
   - `permission.result -> permission.reply`
   - `status -> sessionStatus`
   - `file.title/content/metadata.mime -> fileName/fileUrl/fileMime`

这四步完成后，Skill Server 和 Miniapp 的实时协议才能和历史消息模型稳定对齐。
