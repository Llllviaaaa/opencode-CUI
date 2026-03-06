# Message-Bridge 配置契约

**Version:** 1.1  
**Date:** 2026-03-07  
**Status:** Draft  
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

## 核心结构

| Key | Required | Default | Unit | Configurable |
|---|---|---|---|---|
| `config_version` | yes | `1` | n/a | no |
| `enabled` | yes | `true` | bool | yes |
| `gateway.url` | yes | `ws://localhost:8081/ws/agent` | url | yes |
| `gateway.heartbeatIntervalMs` | no | `30000` | ms | yes |
| `gateway.reconnect.baseMs` | no | `1000` | ms | yes |
| `gateway.reconnect.maxMs` | no | `30000` | ms | yes |
| `sdk.timeoutMs` | no | `10000` | ms | yes |
| `auth.ak` | yes | none | string | yes |
| `auth.sk` | yes | none | string | yes |
| `events.allowlist` | no | PRD defaults | list | yes |

## 校验规则

1. 拒绝未知 `config_version`。
2. 拒绝空的 `gateway.url`、`auth.ak`、`auth.sk`。
3. 超时与间隔字段必须为正数。
4. `reconnect.baseMs` 必须小于等于 `reconnect.maxMs`。
