# OpenClaw 插件安全策略详解

## 概述

OpenClaw 插件通过 `security.resolveDmPolicy` 配置私聊（DM, Direct Message）的安全策略，控制谁可以与 AI 助手进行私聊。

**核心设计目标：**
- **默认安全**：未授权用户无法直接与 AI 对话
- **灵活配置**：支持多种授权模式
- **用户友好**：配对流程简单明了

---

## 四种安全策略

| 策略 | 说明 | 适用场景 |
|------|------|----------|
| **`pairing`** | **配对授权**（默认）<br>新用户收到配对码，需管理员批准 | 个人助手、私密使用 |
| **`allowlist`** | **白名单**<br>仅配置的白名单用户可以访问 | 企业内部、小团队 |
| **`open`** | **开放**<br>任何人都可以私聊 | 公开机器人、社区服务 |
| **`disabled`** | **禁用**<br>禁止所有私聊 | 仅群聊模式 |

---

## 策略配置

### 1. 配置方式

```typescript
// channel.ts
security: {
  resolveDmPolicy: ({ cfg, accountId, account }) => {
    return {
      // 策略类型
      policy: account.config.dmPolicy ?? "pairing",
      
      // 白名单用户列表
      allowFrom: account.config.allowFrom ?? [],
      
      // 配置路径（用于错误提示）
      policyPath: `channels.feishu.dmPolicy`,
      allowFromPath: `channels.feishu.allowFrom`,
      
      // 配对授权提示
      approveHint: "运行：openclaw pairing approve feishu <code>",
      
      // 用户ID标准化函数
      normalizeEntry: (raw) => raw.replace(/^(feishu|lark):/i, ""),
    };
  },
}
```

### 2. 配置文件示例

```json
// ~/.openclaw/openclaw.json
{
  "channels": {
    "feishu": {
      "appId": "cli_xxx",
      "appSecret": "xxx",
      
      // 策略选择：pairing/allowlist/open/disabled
      "dmPolicy": "pairing",
      
      // 白名单（allowlist 模式下使用）
      "allowFrom": ["user1", "user2"],
      
      // 多账号配置
      "accounts": {
        "main": {
          "appId": "cli_xxx",
          "dmPolicy": "pairing"
        },
        "secondary": {
          "appId": "cli_yyy",
          "dmPolicy": "allowlist",
          "allowFrom": ["user3"]
        }
      }
    }
  }
}
```

---

## 配对（Pairing）流程详解

配对模式是最安全的策略，新用户需要通过配对码获得授权。

### 1. 配对流程时序图

```
┌─────────┐                    ┌──────────┐                    ┌─────────┐
│  用户   │                    │  OpenClaw │                   │ 管理员  │
└────┬────┘                    └────┬─────┘                    └───┬─────┘
     │                              │                              │
     │  1. 首次发送私聊消息         │                              │
     │ ───────────────────────────> │                              │
     │                              │                              │
     │                              │  2. 生成配对码               │
     │                              │  ──────────────────────────> │
     │                              │                              │
     │  3. 返回配对码提示           │                              │
     │  "您的配对码: ABCD1234      │                              │
     │   请管理员运行:              │                              │
     │   openclaw pairing           │                              │
     │   approve feishu ABCD1234"   │                              │
     │ <─────────────────────────── │                              │
     │                              │                              │
     │                              │              4. 管理员执行配对命令
     │                              │ <──────────────────────────── │
     │                              │                              │
     │                              │  5. 验证配对码               │
     │                              │  ──────────────────────────> │
     │                              │                              │
     │                              │              6. 配对成功     │
     │                              │ <──────────────────────────── │
     │                              │                              │
     │  7. 发送配对成功通知         │                              │
     │  "✅ 配对成功！您现在可以    │                              │
     │   与 AI 助手对话了"          │                              │
     │ <─────────────────────────── │                              │
     │                              │                              │
     │  8. 正常对话                 │                              │
     │ ───────────────────────────> │                              │
     │                              │                              │
```

### 2. 配对码生成

```typescript
// plugins/openclaw/src/pairing/pairing-store.ts

const PAIRING_CODE_LENGTH = 8;                    // 8位字符
const PAIRING_CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"; // 排除 0O1I
const PAIRING_PENDING_TTL_MS = 60 * 60 * 1000;    // 有效期 1 小时
const PAIRING_PENDING_MAX = 3;                    // 最大待处理请求数

function randomCode(): string {
  let out = "";
  for (let i = 0; i < PAIRING_CODE_LENGTH; i++) {
    const idx = crypto.randomInt(0, PAIRING_CODE_ALPHABET.length);
    out += PAIRING_CODE_ALPHABET[idx];
  }
  return out;
}

// 生成唯一配对码（避免冲突）
function generateUniqueCode(existing: Set<string>): string {
  for (let attempt = 0; attempt < 500; attempt += 1) {
    const code = randomCode();
    if (!existing.has(code)) {
      return code;
    }
  }
  throw new Error("failed to generate unique pairing code");
}
```

**配对码特点：**
- ✅ 8 位大写字母数字
- ✅ 排除易混淆字符（0, O, 1, I）
- ✅ 随机生成，500 次防冲突
- ✅ 1 小时有效期
- ✅ 最多 3 个待处理请求

### 3. 配对请求处理

```typescript
// plugins/openclaw/src/pairing/pairing-store.ts

export async function upsertChannelPairingRequest(params: {
  channel: PairingChannel;
  id: string;           // 用户ID
  accountId: string;
  meta?: Record<string, string>;
}): Promise<{ code: string; created: boolean }> {
  return await withFileLock(filePath, async () => {
    // 1. 清理过期请求
    const pruned = pruneExpiredRequests(reqs, nowMs);
    
    // 2. 检查是否已存在
    const existingIdx = reqs.findIndex((r) => r.id === id);
    
    if (existingIdx >= 0) {
      // 3. 已存在：更新 lastSeenAt，返回原配对码
      const existing = reqs[existingIdx];
      const code = existing.code || generateUniqueCode(existingCodes);
      reqs[existingIdx] = { ...existing, lastSeenAt: now, code };
      return { code, created: false };
    }
    
    // 4. 新建配对请求
    const code = generateUniqueCode(existingCodes);
    const next: PairingRequest = {
      id,
      code,
      createdAt: now,
      lastSeenAt: now,
      meta,
    };
    reqs.push(next);
    return { code, created: true };
  });
}
```

### 4. 配对审批

```typescript
// plugins/openclaw/src/pairing/pairing-store.ts

export async function approveChannelPairingCode(params: {
  channel: PairingChannel;
  code: string;
  accountId?: string;
}): Promise<{ id: string; entry?: PairingRequest } | null> {
  return await withFileLock(filePath, async () => {
    // 1. 读取配对请求
    const pruned = await readPrunedPairingRequests(filePath);
    
    // 2. 查找配对码
    const idx = pruned.findIndex((r) => 
      r.code.toUpperCase() === code.toUpperCase()
    );
    
    if (idx < 0) return null;  // 配对码不存在或已过期
    
    const entry = pruned[idx];
    
    // 3. 从待处理列表移除
    pruned.splice(idx, 1);
    await writeJsonFile(filePath, { version: 1, requests: pruned });
    
    // 4. 添加到白名单存储
    await addChannelAllowFromStoreEntry({
      channel,
      entry: entry.id,
      accountId,
    });
    
    return { id: entry.id, entry };
  });
}
```

### 5. 配对批准通知

```typescript
// channel.ts
pairing: {
  idLabel: "feishuUserId",
  normalizeAllowEntry: (entry) => entry.replace(/^(feishu|lark):/i, ""),
  
  // 配对成功后通知用户
  notifyApproval: async ({ cfg, id }) => {
    const account = resolveFeishuAccount({ cfg });
    const client = createFeishuClient(account);
    
    await sendTextMessage(client, id, 
      "✅ 配对成功！您现在可以与 AI 助手对话了。"
    );
  },
}
```

---

## 白名单（Allowlist）模式

白名单模式不需要配对流程，管理员直接配置允许访问的用户。

### 1. 配置方式

```json
{
  "channels": {
    "feishu": {
      "dmPolicy": "allowlist",
      "allowFrom": ["user1", "user2", "user3"]
    }
  }
}
```

### 2. 白名单存储

白名单存储在独立的 JSON 文件中：

```
~/.openclaw/credentials/
├── feishu-pairing.json          # 待处理配对请求
├── feishu-allowFrom.json        # 白名单（默认账号）
└── feishu-main-allowFrom.json   # 白名单（main账号）
```

**存储格式：**
```json
{
  "version": 1,
  "allowFrom": ["user1", "user2", "user3"]
}
```

### 3. CLI 管理白名单

```bash
# 添加到白名单
openclaw pairing allowlist add feishu user1

# 从白名单移除
openclaw pairing allowlist remove feishu user1

# 查看白名单
openclaw pairing allowlist list feishu
```

---

## 开放（Open）模式

开放模式允许任何人私聊，适用于公开服务。

### 1. 配置

```json
{
  "channels": {
    "feishu": {
      "dmPolicy": "open",
      "allowFrom": ["*"]  // 通配符表示允许所有人
    }
  }
}
```

### 2. 安全警告

系统会检测 `open` 策略并给出安全警告：

```typescript
// status-issues.ts
if (account.dmPolicy === "open") {
  issues.push({
    channel: "feishu",
    severity: "warning",
    message: 'dmPolicy is "open", allowing any user to message the bot',
    fix: 'Set channels.feishu.dmPolicy to "pairing" or "allowlist"',
  });
}
```

**⚠️ 警告：** 开放模式会将 AI 助手暴露给所有用户，请确保：
- AI 助手的工具权限受限
- 不暴露敏感功能
- 监控使用量防止滥用

---

## 用户验证流程

当用户发送私聊消息时，系统按以下顺序验证：

```typescript
async function verifyUserAccess(params: {
  senderId: string;
  dmPolicy: ChannelDmPolicy;
}): Promise<"allow" | "pairing" | "deny"> {
  // 1. 标准化用户ID
  const normalizedId = dmPolicy.normalizeEntry?.(params.senderId) 
    || params.senderId;
  
  // 2. 检查白名单
  if (dmPolicy.allowFrom.includes(normalizedId) || 
      dmPolicy.allowFrom.includes("*")) {
    return "allow";
  }
  
  // 3. 检查配对存储
  const pairedUsers = await readChannelAllowFromStore(
    channel, 
    accountId
  );
  if (pairedUsers.includes(normalizedId)) {
    return "allow";
  }
  
  // 4. 根据策略决定
  switch (dmPolicy.policy) {
    case "open":
      return "allow";
    case "pairing":
      return "pairing";  // 发起配对流程
    case "allowlist":
    case "disabled":
    default:
      return "deny";
  }
}
```

---

## CLI 命令

OpenClaw 提供了 `pairing` 命令管理配对和授权：

```bash
# 查看配对状态
openclaw pairing status

# 批准配对请求
openclaw pairing approve <channel> <code>
# 例如: openclaw pairing approve feishu ABCD1234

# 查看白名单
openclaw pairing allowlist list <channel>

# 添加到白名单
openclaw pairing allowlist add <channel> <userId>

# 从白名单移除
openclaw pairing allowlist remove <channel> <userId>
```

---

## 最佳实践

### 1. **个人使用**
```json
{
  "channels": {
    "feishu": {
      "dmPolicy": "pairing"  // 最安全，需手动批准
    }
  }
}
```

### 2. **小团队内部**
```json
{
  "channels": {
    "feishu": {
      "dmPolicy": "allowlist",
      "allowFrom": ["user1", "user2", "user3"]
    }
  }
}
```

### 3. **公开服务（谨慎）**
```json
{
  "channels": {
    "feishu": {
      "dmPolicy": "open",
      "allowFrom": ["*"]
    }
  },
  "agents": {
    "defaults": {
      "sandbox": {
        "mode": "non-main"  // 限制工具权限
      }
    }
  }
}
```

### 4. **多账号隔离**
```json
{
  "channels": {
    "feishu": {
      "accounts": {
        "personal": {
          "dmPolicy": "pairing"
        },
        "work": {
          "dmPolicy": "allowlist",
          "allowFrom": ["colleague1", "colleague2"]
        }
      }
    }
  }
}
```

---

## 安全建议

| 场景 | 推荐策略 | 原因 |
|------|----------|------|
| 个人 AI 助手 | `pairing` | 完全控制谁可以访问 |
| 家庭使用 | `allowlist` | 预配置家庭成员 |
| 企业团队 | `allowlist` | 管理员统一管理 |
| 公开演示 | `open` + 工具限制 | 方便访问但限制功能 |
| 生产服务 | `pairing` 或 `allowlist` | 防止未授权访问 |

**安全提示：**
- 🔒 默认使用 `pairing` 策略
- 🔒 定期审查白名单
- 🔒 配对码 1 小时过期，过期需重新配对
- 🔒 配对请求最多保留 3 个，防止滥用
- 🔒 使用 `normalizeEntry` 标准化用户ID，避免格式问题
