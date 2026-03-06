# Message-Bridge 协议契约

**Version:** 1.1  
**Date:** 2026-03-07  
**Status:** Draft  
**Owner:** message-bridge maintainers  
**Related:** `../../product/prd.md`, `../../architecture/overview.md`, `../solution-design.md`

## In Scope

- 插件边界的上下行消息类型契约
- Envelope 字段及语义约束
- `permission_reply` 兼容字段定义

## Out of Scope

- Gateway 内部处理逻辑
- Skill-server 的持久化逻辑

## External Dependencies

- `ai-gateway` WebSocket 端点 `/ws/agent`
- `@opencode-ai/sdk` 的 action 与 event 语义

## 消息类型

| 方向 | 类型 |
|---|---|
| 下行 | `invoke`, `status_query` |
| 上行 | `register`, `heartbeat`, `tool_event`, `tool_done`, `tool_error`, `session_created`, `status_response` |

## Envelope 契约

| 字段 | 必填 | 说明 |
|---|---|---|
| `version` | yes | envelope 版本 |
| `messageId` | yes | 消息唯一 ID |
| `timestamp` | yes | 毫秒时间戳 |
| `source` | yes | 消息来源 |
| `agentId` | yes | 当前 MVP 使用本地生成 agentId |
| `sessionId` | conditional | `status_response` 场景可选 |
| `sequenceNumber` | yes | scope 内单调递增 |
| `sequenceScope` | yes | `session` 或 `global` |

## 兼容字段

| Action | 目标字段 | 兼容字段 | 映射 |
|---|---|---|---|
| `permission_reply` | `response` | `approved` | `true -> allow`, `false -> deny` |
