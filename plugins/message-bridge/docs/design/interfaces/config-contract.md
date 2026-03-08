# Message-Bridge 配置契约

**Version:** 1.2  
**Date:** 2026-03-08  
**Status:** Active  
**Owner:** message-bridge maintainers  
**Related:** `../../product/prd.md`, `../solution-design.md`, `../implementation-plan.md`

## In Scope

- 插件侧配置结构与默认值
- 必填/选填字段及校验规则

## Out of Scope

- 配置分发系统
- 服务端配置契约

## External Dependencies

- 环境变量与本地配置文件源
- `jsonc-parser`（JSONC 解析）

## 配置加载优先级

配置从多个源加载，**后加载的覆盖先加载的**（优先级从高到低）：

```
env (BRIDGE_*) > project (.opencode/message-bridge.jsonc) > user (~/.config/opencode/message-bridge.jsonc) > default
```

### 项目配置查找机制

项目配置支持**向上查找**，从当前工作目录（或指定 workspace）向上查找到文件系统根：

1. 从起始目录开始
2. 检查 `.opencode/message-bridge.jsonc` 是否存在
3. 如不存在，进入父目录继续查找
4. 直到找到配置文件或到达文件系统根

**示例场景**：
```
/workspace/project/
  ├── .opencode/
  │   └── message-bridge.jsonc  ← 配置在这里
  ├── src/
  │   └── components/
  │       └── Button.tsx        ← 在这里运行也能找到配置
  └── .git/
```

## 核心结构

| Key | Required | Default | Unit | Configurable |
|---|---|---|---|---|
| `config_version` | no | `1` | n/a | yes |
| `enabled` | no | `true` | bool | yes |
| `debug` | no | `false` | bool | yes |
| `gateway.url` | no | `ws://localhost:8081/ws/agent` | url | yes |
| `gateway.deviceName` | no | `Local Machine` | string | yes |
| `gateway.toolType` | no | `opencode` | string | yes |
| `gateway.toolVersion` | no | `1.0.0` | string | yes |
| `gateway.heartbeatIntervalMs` | no | `30000` | ms | yes |
| `gateway.reconnect.baseMs` | no | `1000` | ms | yes |
| `gateway.reconnect.maxMs` | no | `30000` | ms | yes |
| `gateway.reconnect.exponential` | no | `true` | bool | yes |
| `gateway.ping.intervalMs` | no | `30000` | ms | yes |
| `gateway.ping.pongTimeoutMs` | no | `10000` | ms | yes |
| `sdk.timeoutMs` | no | `10000` | ms | yes |
| `auth.ak` | yes* | none | string | yes |
| `auth.sk` | yes* | none | string | yes |
| `events.allowlist` | no | PRD defaults | list | yes |

\* `auth.ak` 和 `auth.sk` 仅在 `enabled !== false` 时为必填字段。

### Debug 模式

`debug` 字段控制是否启用详细日志输出：

- 设为 `true` 时，日志会包含更详细的 `extra` 字段（敏感信息会被脱敏）
- 可通过配置文件设置 `debug: true`，或环境变量 `BRIDGE_DEBUG=true`
- `BRIDGE_DEBUG` 为旧版环境变量，仍支持用于向后兼容

## 最小配置示例

```jsonc
{
  "auth": {
    "ak": "your-access-key",
    "sk": "your-secret-key"
  }
}
```

## 校验规则

1. `config_version` 必须为 1（如果提供）。
2. `enabled` 必须为 boolean（如果提供）。
3. `gateway.url` 必须以 `ws://` 或 `wss://` 开头（如果提供）。
4. 超时与间隔字段必须为正数（如果提供）。
5. `auth.ak` 和 `auth.sk` 在启用状态下不能为空字符串。
6. 若出现 `sdk.baseUrl`，应直接报错（`DEPRECATED_FIELD`）。

## 配置加载日志

插件启动时会输出配置加载信息：

```
[message-bridge] Config sources: default -> user:/path -> project:/path -> env
[message-bridge] Configuration validation failed:
  [MISSING_REQUIRED] auth.ak: auth.ak is required
```
