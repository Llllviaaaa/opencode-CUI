# 配置契约

本文档以当前代码实现为准，描述 `message-bridge` 已支持的配置项、环境变量、默认值、校验规则与兼容约束。

## 1. 配置来源与优先级

配置按以下顺序覆盖，后者优先级更高：

1. 内建默认值
2. 用户级配置
   - `~/.config/opencode/message-bridge.jsonc`
   - `~/.config/opencode/message-bridge.json`
3. 项目级配置
   - `<workspace>/.opencode/message-bridge.jsonc`
   - `<workspace>/.opencode/message-bridge.json`
4. 环境变量 `BRIDGE_*`

补充规则：

- 同一目录下若 `.jsonc` 与 `.json` 同时存在，优先读取 `.jsonc`
- 项目级配置会从 `workspacePath` 或 `process.cwd()` 开始，沿父目录向上查找，直到文件系统根目录
- 配置文件支持 JSONC，包括注释与尾逗号

## 2. 默认值

默认值定义于 `src/config/default-config.ts`。

| 配置键 | 默认值 | 说明 |
|---|---|---|
| `enabled` | `true` | 是否启用 bridge |
| `config_version` | `1` | 配置版本 |
| `gateway.url` | `ws://localhost:8081/ws/agent` | Gateway WebSocket 地址 |
| `gateway.channel` | `opencode` | 配置侧字段名；注册报文中映射到 `toolType` |
| `gateway.heartbeatIntervalMs` | `30000` | 心跳间隔，单位毫秒 |
| `gateway.reconnect.baseMs` | `1000` | 重连基础退避，单位毫秒 |
| `gateway.reconnect.maxMs` | `30000` | 重连最大退避，单位毫秒 |
| `gateway.reconnect.exponential` | `true` | 是否启用指数退避 |
| `gateway.ping.intervalMs` | `30000` | 保留字段，当前未见运行时消费 |
| `sdk.timeoutMs` | `10000` | SDK 调用超时，单位毫秒 |
| `auth.ak` | `""` | Access Key，启用时必填 |
| `auth.sk` | `""` | Secret Key，启用时必填 |
| `events.allowlist` | `DEFAULT_EVENT_ALLOWLIST` | 上行事件白名单 |

## 3. 最小可用配置

```jsonc
{
  "auth": {
    "ak": "your-access-key",
    "sk": "your-secret-key"
  }
}
```

如果需要显式关闭 bridge：

```jsonc
{
  "enabled": false
}
```

## 4. 完整配置结构

```jsonc
{
  "enabled": true,
  "debug": false,
  "config_version": 1,
  "gateway": {
    "url": "ws://localhost:8081/ws/agent",
    "channel": "opencode",
    "heartbeatIntervalMs": 30000,
    "reconnect": {
      "baseMs": 1000,
      "maxMs": 30000,
      "exponential": true
    },
    "ping": {
      "intervalMs": 30000
    }
  },
  "sdk": {
    "timeoutMs": 10000
  },
  "auth": {
    "ak": "your-access-key",
    "sk": "your-secret-key"
  },
  "events": {
    "allowlist": [
      "message.updated",
      "session.status"
    ]
  }
}
```

## 5. 配置项说明

| 配置键 | 类型 | 必填 | 默认值 | 说明 |
|---|---|---|---|---|
| `enabled` | `boolean` | 否 | `true` | 为 `false` 时安全禁用，且不要求 `auth.ak/sk` |
| `debug` | `boolean` | 否 | `false` | 调试日志开关；常由环境变量覆盖 |
| `config_version` | `number` | 否 | `1` | 当前只支持 `1` |
| `gateway.url` | `string` | 否 | `ws://localhost:8081/ws/agent` | 必须以 `ws://` 或 `wss://` 开头 |
| `gateway.channel` | `string` | 否 | `opencode` | 注册消息中的 `toolType` 来源 |
| `gateway.heartbeatIntervalMs` | `number` | 否 | `30000` | 正整数 |
| `gateway.reconnect.baseMs` | `number` | 否 | `1000` | 正整数 |
| `gateway.reconnect.maxMs` | `number` | 否 | `30000` | 正整数 |
| `gateway.reconnect.exponential` | `boolean` | 否 | `true` | 是否指数退避 |
| `gateway.ping.intervalMs` | `number` | 否 | `30000` | 当前仅配置层存在，未见运行时消费 |
| `sdk.timeoutMs` | `number` | 否 | `10000` | 正整数 |
| `auth.ak` | `string` | 条件必填 | `""` | `enabled !== false` 时必填 |
| `auth.sk` | `string` | 条件必填 | `""` | `enabled !== false` 时必填 |
| `events.allowlist` | `string[]` | 否 | `DEFAULT_EVENT_ALLOWLIST` | 仅允许精确事件名，不支持通配符 |

## 6. 环境变量映射

| 环境变量 | 映射配置键 | 说明 |
|---|---|---|
| `BRIDGE_ENABLED` | `enabled` | 仅当值为 `true` 时解析为 `true`，其余值解析为 `false` |
| `BRIDGE_DEBUG` | `debug` | 仅当值为 `true` 时解析为 `true`，其余值解析为 `false` |
| `BRIDGE_CONFIG_VERSION` | `config_version` | 使用 `parseInt(..., 10)` 解析 |
| `BRIDGE_GATEWAY_URL` | `gateway.url` | 支持 `${VAR_NAME}` 替换 |
| `BRIDGE_GATEWAY_CHANNEL` | `gateway.channel` | 支持 `${VAR_NAME}` 替换；会在归一化阶段 `trim()` |
| `BRIDGE_GATEWAY_RECONNECT_BASE_MS` | `gateway.reconnect.baseMs` | 使用 `parseInt(..., 10)` 解析 |
| `BRIDGE_GATEWAY_RECONNECT_MAX_MS` | `gateway.reconnect.maxMs` | 使用 `parseInt(..., 10)` 解析 |
| `BRIDGE_GATEWAY_RECONNECT_EXPONENTIAL` | `gateway.reconnect.exponential` | 仅当值为 `true` 时解析为 `true` |
| `BRIDGE_GATEWAY_HEARTBEAT_INTERVAL_MS` | `gateway.heartbeatIntervalMs` | 优先于兼容别名 |
| `BRIDGE_EVENT_HEARTBEAT_INTERVAL_MS` | `gateway.heartbeatIntervalMs` | 兼容别名；仅在前者不存在时生效 |
| `BRIDGE_GATEWAY_PING_INTERVAL_MS` | `gateway.ping.intervalMs` | 当前仅配置层存在，未见运行时消费 |
| `BRIDGE_AUTH_AK` | `auth.ak` | 支持 `${VAR_NAME}` 替换 |
| `BRIDGE_AUTH_SK` | `auth.sk` | 支持 `${VAR_NAME}` 替换 |
| `BRIDGE_AK` | `auth.ak` | 兼容别名；仅在 `BRIDGE_AUTH_AK` 缺失时生效 |
| `BRIDGE_SK` | `auth.sk` | 兼容别名；仅在 `BRIDGE_AUTH_SK` 缺失时生效 |
| `BRIDGE_SDK_TIMEOUT_MS` | `sdk.timeoutMs` | 使用 `parseInt(..., 10)` 解析 |
| `BRIDGE_EVENTS_ALLOWLIST` | `events.allowlist` | 以逗号分隔并逐项 `trim()` |

环境变量示例：

```bash
BRIDGE_ENABLED=true
BRIDGE_AUTH_AK=your-access-key
BRIDGE_AUTH_SK=your-secret-key
BRIDGE_GATEWAY_URL=ws://gateway.example.com/ws/agent
BRIDGE_GATEWAY_CHANNEL=opencode
BRIDGE_GATEWAY_RECONNECT_BASE_MS=1000
BRIDGE_GATEWAY_RECONNECT_MAX_MS=30000
BRIDGE_SDK_TIMEOUT_MS=10000
BRIDGE_EVENTS_ALLOWLIST=message.updated,session.status
```

## 7. 兼容与废弃约束

当前实现包含以下兼容或清理规则：

- `BRIDGE_AK` / `BRIDGE_SK` 仍兼容旧环境变量命名
- `BRIDGE_EVENT_HEARTBEAT_INTERVAL_MS` 仍兼容旧心跳变量命名
- `BRIDGE_GATEWAY_TOOL_TYPE` 已移除，当前实现会忽略它
- `BRIDGE_GATEWAY_DEVICE_NAME`
- `BRIDGE_GATEWAY_MAC_ADDRESS`
- `BRIDGE_GATEWAY_TOOL_VERSION`

上面三个 `gateway` 元数据相关环境变量已移除，当前实现会忽略它们。注册时使用的 `deviceName`、`macAddress`、`toolVersion` 由运行时自动采集，不属于可配置项。

## 8. 校验规则

当前代码已实现的校验规则：

- 根配置必须是对象
- `config_version` 必须为 `1`
- `enabled` 必须为 `boolean`
- `gateway.url` 必须以 `ws://` 或 `wss://` 开头
- `gateway.reconnect.baseMs` 必须为正整数
- `gateway.reconnect.maxMs` 必须为正整数
- `gateway.heartbeatIntervalMs` 必须为正整数
- `sdk.timeoutMs` 必须为正整数
- `events.allowlist` 必须为字符串数组
- `events.allowlist` 每一项都必须是受支持的精确事件名
- 当 `enabled !== false` 时，`auth.ak` 与 `auth.sk` 必填

当前未见独立校验的字段：

- `debug`
- `gateway.channel`
- `gateway.reconnect.exponential`
- `gateway.ping.intervalMs`

## 9. 默认白名单

默认 `events.allowlist` 包含：

- `message.updated`
- `message.part.updated`
- `message.part.delta`
- `message.part.removed`
- `session.status`
- `session.idle`
- `session.updated`
- `session.error`
- `permission.updated`
- `permission.asked`
- `question.asked`

不支持通配符事件模式。

## 10. 日志事件

配置加载与校验阶段会产生日志事件：

- `config.resolve.started`
- `config.source.loaded`
- `config.resolve.completed`
- `config.source.load_failed`
- `config.validation.passed`
- `config.validation.failed`
