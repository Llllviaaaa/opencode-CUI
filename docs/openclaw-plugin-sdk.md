# OpenClaw 插件 SDK 接口文档

## 概述

OpenClaw 提供了一套完整的插件 SDK，允许开发者集成新的即时通讯频道（如飞书、钉钉、企业微信等）。插件通过实现 `ChannelPlugin<T>` 接口，可以接入 OpenClaw 的消息收发、配置管理、用户授权和状态监控等全套功能。

**核心设计原则：**
- **声明式配置**：通过 JSON Schema 定义配置结构
- **生命周期管理**：支持启动、停止、热重载
- **安全策略**：支持配对授权、白名单、私聊策略
- **流式输出**：支持块级流式消息输出

---

## 核心接口

### ChannelPlugin<T>

插件的主接口，定义了频道的所有能力。

```typescript
interface ChannelPlugin<T> {
  /** 插件唯一标识 */
  id: string;
  
  /** 元数据：名称、描述、文档等 */
  meta?: Record<string, unknown>;
  
  /** 引导配置向导 */
  onboarding?: ChannelOnboardingAdapter;
  
  /** 配对/授权配置 */
  pairing?: Record<string, unknown>;
  
  /** 能力声明 */
  capabilities?: Record<string, unknown>;
  
  /** 热重载配置 */
  reload?: { configPrefixes?: string[] };
  
  /** JSON Schema 配置验证 */
  configSchema?: unknown;
  
  /** 配置管理 */
  config?: Record<string, unknown>;
  
  /** 安全策略 */
  security?: Record<string, unknown>;
  
  /** 群组设置 */
  groups?: Record<string, unknown>;
  
  /** 线程/回复设置 */
  threading?: Record<string, unknown>;
  
  /** 消息路由 */
  messaging?: Record<string, unknown>;
  
  /** 目录/联系人 */
  directory?: Record<string, unknown>;
  
  /** 安装设置 */
  setup?: Record<string, unknown>;
  
  /** 出站消息 */
  outbound?: Record<string, unknown>;
  
  /** 状态管理 */
  status?: Record<string, unknown>;
  
  /** 网关生命周期 */
  gateway?: Record<string, unknown>;
}
```

---

## 功能模块详解

### 1. 能力声明 (capabilities)

定义频道支持的功能特性。

```typescript
capabilities: {
  /** 支持的聊天类型：direct(私聊), group(群聊) */
  chatTypes: ["direct", "group"],
  
  /** 是否支持媒体消息（图片、视频、文件） */
  media: true,
  
  /** 是否支持表情反应 */
  reactions: false,
  
  /** 是否支持线程回复 */
  threads: false,
  
  /** 是否支持投票 */
  polls: false,
  
  /** 是否支持原生命令 */
  nativeCommands: false,
  
  /** 是否支持块级流式输出 */
  blockStreaming: true,
}
```

**配置建议：**
- 对于支持富文本的频道，启用 `blockStreaming`
- 对于企业通讯工具，建议支持 `threads` 和 `reactions`
- 对于短信类频道，关闭 `media` 和 `reactions`

---

### 2. 出站消息 (outbound)

定义如何向频道发送消息。

```typescript
outbound: {
  /** 
   * 投递模式：
   * - "direct": 直接发送
   * - "buffered": 缓冲发送（适合有频率限制的API）
   */
  deliveryMode: "direct",
  
  /**
   * 消息分块策略
   * 用于将长消息拆分成多条发送
   */
  chunker: (text: string, limit: number) => string[],
  
  /** 分块模式：text(纯文本) 或 markdown */
  chunkerMode: "text",
  
  /** 单条消息最大长度 */
  textChunkLimit: 4000,
  
  /**
   * 发送文本消息
   * @returns 发送结果，包含消息ID
   */
  sendText: async ({
    to,           // 接收者ID
    text,         // 消息内容
    accountId,    // 账号ID
    cfg,          // 频道配置
  }) => {
    // 实现消息发送逻辑
    return { 
      channel: "feishu", 
      ok: true, 
      messageId: "msg_xxx" 
    };
  },
  
  /**
   * 发送媒体消息
   */
  sendMedia: async ({
    to,
    text,         // 可选的说明文字
    mediaUrl,     // 媒体文件URL
    accountId,
    cfg,
  }) => {
    return { channel: "feishu", ok: true };
  },
}
```

**最佳实践：**
1. **消息分块**：根据频道API限制设置 `textChunkLimit`
2. **错误处理**：返回 `{ ok: false, error: string }` 以便系统重试
3. **消息ID**：返回 `messageId` 用于后续更新或删除消息

---

### 3. 配置管理 (config)

管理频道的多账号配置。

```typescript
config: {
  /** 列出所有账号ID */
  listAccountIds: (cfg: ClawdbotConfig) => string[];
  
  /** 解析指定账号的配置 */
  resolveAccount: (
    cfg: ClawdbotConfig, 
    accountId: string
  ) => T | undefined;
  
  /** 获取默认账号ID */
  defaultAccountId: (cfg: ClawdbotConfig) => string;
  
  /** 启用/禁用账号 */
  setAccountEnabled: ({
    cfg,
    accountId,
    enabled,
  }: {
    cfg: ClawdbotConfig;
    accountId: string;
    enabled: boolean;
  }) => ClawdbotConfig;
  
  /** 删除账号 */
  deleteAccount: ({
    cfg,
    accountId,
  }: {
    cfg: ClawdbotConfig;
    accountId: string;
  }) => ClawdbotConfig;
  
  /** 检查账号是否已配置 */
  isConfigured: (account: T) => boolean;
  
  /** 账号快照描述（用于状态显示） */
  describeAccount: (account: T) => ChannelAccountDescriptor;
}
```

**账号配置示例：**

```typescript
// 飞书账号配置结构
interface FeishuAccount {
  appId: string;
  appSecret: string;
  encryptKey?: string;
  verificationToken?: string;
  thinkingThresholdMs?: number;
}

// 实现示例
resolveAccount: (cfg, accountId) => {
  const section = cfg.channels?.feishu;
  if (!section) return undefined;
  
  // 多账号模式
  if (section.accounts?.[accountId]) {
    return section.accounts[accountId];
  }
  
  // 单账号模式（兼容旧配置）
  if (accountId === "default" && section.appId) {
    return {
      appId: section.appId,
      appSecret: section.appSecret,
    };
  }
  
  return undefined;
}
```

---

### 4. 安全策略 (security)

配置私聊安全策略和用户授权。

```typescript
security: {
  /**
   * 解析私聊策略
   * - "pairing": 需要配对授权（默认）
   * - "allowlist": 仅白名单用户
   * - "open": 允许所有用户
   * - "disabled": 禁用私聊
   */
  resolveDmPolicy: ({
    cfg,
    accountId,
    account,
  }: {
    cfg: ClawdbotConfig;
    accountId: string;
    account: T;
  }) => ChannelDmPolicy;
}

// 返回的私聊策略结构
interface ChannelDmPolicy {
  /** 策略类型 */
  policy: "pairing" | "allowlist" | "open" | "disabled";
  
  /** 白名单用户列表 */
  allowFrom?: string[];
  
  /** 配置路径（用于错误提示） */
  policyPath: string;
  allowFromPath?: string;
  
  /** 授权提示信息 */
  approveHint?: string;
  
  /** 用户ID标准化函数 */
  normalizeEntry?: (raw: string) => string;
}
```

**策略说明：**

| 策略 | 说明 | 适用场景 |
|------|------|----------|
| `pairing` | 新用户收到配对码，需管理员批准 | 个人助手（推荐） |
| `allowlist` | 仅配置的白名单用户可以访问 | 企业内部使用 |
| `open` | 任何人都可以私聊 | 公开机器人 |
| `disabled` | 禁用所有私聊 | 仅群聊模式 |

---

### 5. 引导向导 (onboarding)

交互式配置引导流程。

```typescript
onboarding: {
  /** 检查是否已配置 */
  configuredCheck: (cfg: ClawdbotConfig) => boolean;
  
  /** 设置私聊策略 */
  setDmPolicy: (
    cfg: ClawdbotConfig, 
    policy: "pairing" | "allowlist" | "open"
  ) => ClawdbotConfig;
  
  /** 提示用户配置白名单 */
  promptAllowFrom: async ({
    cfg,
    prompter,
    accountId,
  }: {
    cfg: ClawdbotConfig;
    prompter: Prompter;
    accountId: string;
  }) => ClawdbotConfig;
  
  /** 显示设置帮助 */
  noteSetupHelp: async (prompter: Prompter) => void;
  
  /** 运行设置向导 */
  runSetupWizard: async ({
    cfg,
    prompter,
    accountId,
    log,
  }: {
    cfg: ClawdbotConfig;
    prompter: Prompter;
    accountId: string;
    log: Logger;
  }) => ClawdbotConfig;
}
```

**Prompter API：**

```typescript
interface Prompter {
  /** 文本输入 */
  text: (opts: {
    message: string;
    placeholder?: string;
    defaultValue?: string;
  }) => Promise<string>;
  
  /** 密码输入（隐藏） */
  password: (opts: {
    message: string;
  }) => Promise<string>;
  
  /** 确认对话框 */
  confirm: (opts: {
    message: string;
    defaultValue?: boolean;
  }) => Promise<boolean>;
  
  /** 单选 */
  select: <T>(opts: {
    message: string;
    choices: { title: string; value: T }[];
  }) => Promise<T>;
  
  /** 多选 */
  multiselect: <T>(opts: {
    message: string;
    choices: { title: string; value: T }[];
  }) => Promise<T[]>;
  
  /** 信息提示 */
  note: (message: string) => Promise<void>;
  
  /** 警告提示 */
  warn: (message: string) => Promise<void>;
}
```

**向导示例：**

```typescript
runSetupWizard: async ({ cfg, prompter }) => {
  await prompter.note("请前往 https://open.feishu.cn 创建应用");
  
  const appId = await prompter.text({
    message: "请输入 App ID:",
    placeholder: "cli_xxxxxxxxxx",
  });
  
  const appSecret = await prompter.password({
    message: "请输入 App Secret:",
  });
  
  const policy = await prompter.select({
    message: "选择私聊策略:",
    choices: [
      { title: "配对授权（安全）", value: "pairing" },
      { title: "白名单", value: "allowlist" },
      { title: "开放", value: "open" },
    ],
  });
  
  return {
    ...cfg,
    channels: {
      feishu: {
        appId,
        appSecret,
        dmPolicy: policy,
      },
    },
  };
}
```

---

### 6. 网关生命周期 (gateway)

管理频道的连接生命周期。

```typescript
gateway: {
  /**
   * 启动账号连接
   * @returns Provider 对象，包含 stop 方法
   */
  startAccount: async (ctx: {
    /** 账号配置 */
    account: T;
    
    /** 完整配置 */
    cfg: ClawdbotConfig;
    
    /** 日志器 */
    log: Logger;
    
    /** 中止信号 */
    abortSignal: AbortSignal;
    
    /** 状态更新函数 */
    setStatus: (status: ChannelStatusRuntime) => void;
    
    /** 当前账号的运行时状态 */
    runtime: ChannelStatusRuntime;
  }) => {
    // 启动连接（如 WebSocket、Webhook 服务器等）
    const provider = startProvider({ ... });
    
    return {
      /** 停止连接 */
      stop: async () => {
        await provider.disconnect();
      },
    };
  };
}
```

**生命周期时序：**

```
用户运行: openclaw gateway
    │
    ▼
系统加载插件
    │
    ▼
遍历配置中的所有账号
    │
    ▼
调用 gateway.startAccount()
    │
    ├── 建立连接（WebSocket/Webhook）
    ├── 设置事件处理器
    └── 返回 { stop }
    │
    ▼
运行中：接收/发送消息
    │
    ▼
停止时：调用 provider.stop()
    │
    ▼
清理资源，断开连接
```

---

### 7. 状态管理 (status)

实现健康检查和状态报告。

```typescript
status: {
  /** 默认运行状态 */
  defaultRuntime: ChannelStatusRuntime;
  
  /** 收集状态问题 */
  collectStatusIssues: (
    snapshot: ChannelStatusSnapshot
  ) => ChannelStatusIssue[];
  
  /** 构建频道摘要（用于 CLI 显示） */
  buildChannelSummary: ({
    snapshot,
  }: {
    snapshot: ChannelStatusSnapshot;
  }) => ChannelSummary;
  
  /** 探测账号连通性 */
  probeAccount: async ({
    account,
    timeoutMs,
  }: {
    account: T;
    timeoutMs: number;
  }) => ChannelProbeResult;
  
  /** 构建账号快照 */
  buildAccountSnapshot: ({
    account,
    runtime,
  }: {
    account: T;
    runtime: ChannelStatusRuntime;
  }) => ChannelStatusSnapshot;
}

// 状态结构
interface ChannelStatusRuntime {
  accountId: string;
  running: boolean;
  lastStartAt: string | null;
  lastStopAt: string | null;
  lastError: string | null;
}

interface ChannelStatusSnapshot {
  accountId: string;
  configured: boolean;
  running: boolean;
  mode?: string;
  lastError?: string;
}

interface ChannelStatusIssue {
  channel: string;
  accountId: string;
  kind: "auth" | "config" | "network" | "other";
  message: string;
}
```

---

### 8. 群组设置 (groups)

配置群组消息处理策略。

```typescript
groups: {
  /**
   * 是否需要 @提及 才能激活机器人
   */
  resolveRequireMention: ({
    cfg,
    accountId,
    groupId,
  }: {
    cfg: ClawdbotConfig;
    accountId: string;
    groupId: string;
  }) => boolean;
}
```

---

### 9. 线程设置 (threading)

配置回复和线程模式。

```typescript
threading: {
  /**
   * 解析回复模式
   * - "thread": 使用线程回复
   * - "reply": 引用原消息回复
   * - "none": 直接发送新消息
   */
  resolveReplyToMode: ({
    cfg,
    accountId,
  }: {
    cfg: ClawdbotConfig;
    accountId: string;
  }) => "thread" | "reply" | "none";
}
```

---

## 插件生命周期

```mermaid
graph TD
    A[系统启动] --> B[加载插件]
    B --> C[读取 openclaw.plugin.json]
    C --> D[调用 plugin(api)]
    D --> E[注册频道]
    E --> F{需要配置?}
    F -->|是| G[运行 onboarding]
    F -->|否| H[启动账号]
    G --> H
    H --> I[调用 gateway.startAccount]
    I --> J[返回 Provider]
    J --> K[运行中]
    K --> L{配置变更?}
    L -->|是| M[热重载]
    M --> H
    L -->|否| N{停止?}
    N -->|是| O[调用 provider.stop]
    O --> P[清理资源]
    P --> Q[结束]
```

---

## ChannelDock 接口

`ChannelDock` 定义了运行时装载配置，与 `ChannelPlugin` 一起注册。

```typescript
interface ChannelDock {
  /** 频道ID */
  id: string;
  
  /** 能力声明 */
  capabilities?: {
    chatTypes?: string[];
    media?: boolean;
    blockStreaming?: boolean;
  };
  
  /** 出站配置 */
  outbound?: {
    textChunkLimit?: number;
  };
  
  /** 配置适配器 */
  config?: Record<string, unknown>;
  
  /** 群组适配器 */
  groups?: Record<string, unknown>;
  
  /** 线程适配器 */
  threading?: Record<string, unknown>;
}
```

**注册示例：**

```typescript
import { ClawdbotPluginApi } from "clawdbot/plugin-sdk";
import { myPlugin } from "./channel.js";
import { myDock } from "./dock.js";

export default function plugin(api: ClawdbotPluginApi) {
  api.registerChannel({
    plugin: myPlugin,
    dock: myDock,
  });
}
```

---

## 项目结构

```
my-channel-plugin/
├── openclaw.plugin.json          # 插件元数据
├── package.json                  # 包配置
├── README.md                     # 文档
└── src/
    ├── index.ts                  # 入口：导出 plugin 函数
    ├── channel.ts                # ChannelPlugin 完整定义
    ├── dock.ts                   # ChannelDock 定义
    ├── types.ts                  # TypeScript 类型定义
    ├── config-json-schema.ts     # JSON Schema 配置验证
    ├── receive.ts                # 消息接收处理
    ├── send.ts                   # 消息发送实现
    ├── onboarding.ts             # 引导向导实现
    ├── runtime.ts                # 运行时逻辑（连接管理）
    └── status.ts                 # 状态管理
```

---

## 配置 Schema

使用 JSON Schema 定义配置结构，用于验证用户配置。

```typescript
// config-json-schema.ts
export const configSchema = {
  type: "object",
  additionalProperties: false,
  properties: {
    // 单账号模式（向后兼容）
    appId: { type: "string" },
    appSecret: { type: "string" },
    
    // 多账号模式
    accounts: {
      type: "object",
      additionalProperties: {
        type: "object",
        properties: {
          appId: { type: "string" },
          appSecret: { type: "string" },
          enabled: { type: "boolean", default: true },
        },
        required: ["appId", "appSecret"],
      },
    },
    
    // 私聊策略
    dmPolicy: {
      type: "string",
      enum: ["pairing", "allowlist", "open", "disabled"],
      default: "pairing",
    },
    
    // 白名单
    allowFrom: {
      type: "array",
      items: { type: "string" },
    },
    
    // 自定义配置
    thinkingThresholdMs: {
      type: "number",
      default: 2500,
      description: "占位符阈值（毫秒）",
    },
  },
};
```

---

## 消息处理

### 接收消息

实现消息接收处理器，将外部消息转换为 OpenClaw 内部格式。

```typescript
// receive.ts
export async function handleIncomingMessage(
  ctx: ChannelContext,
  payload: ExternalMessage
): Promise<void> {
  // 1. 验证消息
  if (!isValidMessage(payload)) {
    return;
  }
  
  // 2. 提取消息信息
  const message: InboundMessage = {
    channel: "my-channel",
    channelMessageId: payload.id,
    from: payload.sender.id,
    text: payload.content.text,
    timestamp: new Date(payload.timestamp),
    isGroup: payload.chat.type === "group",
    groupId: payload.chat.groupId,
  };
  
  // 3. 调用 OpenClaw 处理
  await ctx.deliver(message);
}
```

### 发送消息

实现消息发送，处理文本和媒体消息。

```typescript
// send.ts
export async function sendTextMessage(
  client: ChannelClient,
  to: string,
  text: string
): Promise<SendResult> {
  try {
    const result = await client.sendMessage({
      chat_id: to,
      msg_type: "text",
      content: { text },
    });
    
    return {
      channel: "my-channel",
      ok: true,
      messageId: result.data.message_id,
    };
  } catch (error) {
    return {
      channel: "my-channel",
      ok: false,
      error: error.message,
    };
  }
}

export async function sendMediaMessage(
  client: ChannelClient,
  to: string,
  mediaUrl: string,
  text?: string
): Promise<SendResult> {
  // 1. 下载媒体文件
  const fileBuffer = await downloadFile(mediaUrl);
  
  // 2. 上传到频道服务器
  const uploadResult = await client.uploadFile(fileBuffer);
  
  // 3. 发送媒体消息
  await client.sendMessage({
    chat_id: to,
    msg_type: "file",
    content: {
      file_key: uploadResult.file_key,
    },
  });
  
  return { channel: "my-channel", ok: true };
}
```

---

## 热重载

支持配置热重载，无需重启网关。

```typescript
reload: {
  // 监控这些配置前缀的变更
  configPrefixes: ["channels.my-channel"],
}
```

当用户修改 `~/.openclaw/openclaw.json` 中的 `channels.my-channel` 配置时：
1. 系统自动检测到变更
2. 调用 `provider.stop()` 停止旧连接
3. 重新加载配置
4. 调用 `gateway.startAccount()` 启动新连接

---

## SDK 工具函数

OpenClaw SDK 提供了多个实用函数：

```typescript
import {
  normalizeAccountId,
  formatPairingApproveHint,
  buildChannelConfigSchema,
  setAccountEnabledInConfigSection,
  deleteAccountFromConfigSection,
  applyAccountNameToChannelSection,
  migrateBaseNameToDefaultAccount,
  addWildcardAllowFrom,
  emptyPluginConfigSchema,
} from "clawdbot/plugin-sdk";

// 标准化账号ID
const accountId = normalizeAccountId(rawId);

// 生成配对授权提示
const hint = formatPairingApproveHint("my-channel");
// 结果: "运行：openclaw pairing approve my-channel <code>"

// 启用账号
const newCfg = setAccountEnabledInConfigSection({
  cfg: config,
  sectionKey: "channels.my-channel",
  accountId: "main",
  enabled: true,
});

// 添加通配符到白名单
const allowFrom = addWildcardAllowFrom(["user1", "user2"]);
// 结果: ["user1", "user2", "*"]
```

---

## 功能实现清单

| 功能类别 | 具体能力 | 实现接口 |
|---------|---------|---------|
| **基础能力** | 频道标识 | `id` |
| | 能力声明 | `capabilities` |
| | 配置验证 | `configSchema` |
| **消息收发** | 接收消息 | `gateway.startAccount` + 事件处理 |
| | 发送文本 | `outbound.sendText` |
| | 发送媒体 | `outbound.sendMedia` |
| | 消息分块 | `outbound.chunker` |
| | 流式输出 | `capabilities.blockStreaming` |
| **会话管理** | 私聊策略 | `security.resolveDmPolicy` |
| | 配对授权 | `pairing.notifyApproval` |
| | 白名单 | `config.resolveAllowFrom` |
| **配置管理** | 多账号 | `config.*` |
| | 设置向导 | `onboarding.runSetupWizard` |
| | 热重载 | `reload.configPrefixes` |
| **群组功能** | @提及触发 | `groups.resolveRequireMention` |
| | 线程回复 | `threading.resolveReplyToMode` |
| **状态监控** | 健康检查 | `status.probeAccount` |
| | 状态摘要 | `status.buildChannelSummary` |
| | 问题诊断 | `status.collectStatusIssues` |

---

## 最佳实践

### 1. 错误处理

```typescript
sendText: async ({ to, text }) => {
  try {
    const result = await api.send(to, text);
    return { channel: "my-channel", ok: true, messageId: result.id };
  } catch (error) {
    // 区分可重试错误和永久错误
    if (error.code === "RATE_LIMITED") {
      return { 
        channel: "my-channel", 
        ok: false, 
        error: error.message,
        retryable: true,  // 标记可重试
      };
    }
    return { 
      channel: "my-channel", 
      ok: false, 
      error: error.message 
    };
  }
}
```

### 2. 日志记录

```typescript
gateway: {
  startAccount: async (ctx) => {
    ctx.log.info(`启动账号: ${ctx.account.accountId}`);
    
    try {
      const provider = await connect({ ... });
      ctx.log.info("连接成功");
      return provider;
    } catch (error) {
      ctx.log.error("连接失败:", error);
      throw error;
    }
  };
}
```

### 3. 资源清理

```typescript
gateway: {
  startAccount: async (ctx) => {
    const ws = new WebSocket(url);
    
    // 监听中止信号
    ctx.abortSignal.addEventListener("abort", () => {
      ws.close();
    });
    
    return {
      stop: async () => {
        ws.close();
      },
    };
  };
}
```

### 4. 配置迁移

```typescript
config: {
  resolveAccount: (cfg, accountId) => {
    const section = cfg.channels?.mychannel;
    
    // 多账号模式
    if (section?.accounts?.[accountId]) {
      return section.accounts[accountId];
    }
    
    // 单账号模式（向后兼容）
    if (accountId === "default" && section?.token) {
      return {
        token: section.token,
        enabled: true,
      };
    }
    
    return undefined;
  },
}
```

---

## 参考实现

### 飞书插件 (openclaw-feishu)

**文件位置**: `plugins/openclaw-feishu/src/`

**关键实现：**
- **流式输出**: 使用占位符+消息更新策略
- **Webhook 接收**: Express 服务器处理飞书事件推送
- **API 调用**: 使用飞书 OpenAPI SDK

### Discord 插件

**文件位置**: `plugins/openclaw/extensions/discord/`

**关键实现：**
- **Gateway 连接**: WebSocket 连接到 Discord Gateway
- **消息分块**: 2000 字符限制分块
- **富文本**: 支持 Markdown 和 Embed

---

## 相关文档

- [OpenClaw 架构概述](https://docs.openclaw.ai/concepts/architecture)
- [频道配置指南](https://docs.openclaw.ai/channels)
- [安全配置](https://docs.openclaw.ai/gateway/security)
- [插件开发示例](https://github.com/openclaw/openclaw/tree/main/plugins)
