# Snowflake Session ID 治理说明

## 目标

为 `skill-server` 与 `ai-gateway` 建立统一的 `Long` 型 Snowflake ID 规则，确保：

- `welinkSessionId` 在双服务并行运行时保持全局唯一
- 应用侧生成主键，不再依赖数据库 `AUTO_INCREMENT`
- 两边使用同一套 bit layout、epoch 和时钟回拨策略

## 统一规则

两边统一采用以下布局：

| 字段 | 位数 | 说明 |
|------|------|------|
| timestamp delta | 其余高位 | `currentTimeMillis - epochMs` |
| serviceCode | 4 | 区分服务域 |
| workerId | 10 | 区分实例 |
| sequence | 12 | 同毫秒内序列 |

- `epochMs`: `1735689600000`（2025-01-01 00:00:00 UTC）
- `clockBackwardsStrategy`: `WAIT`
- `maxBackwardMs`: `5`

默认服务编码：

| 服务 | serviceCode |
|------|-------------|
| `skill-server` | `1` |
| `ai-gateway` | `2` |

## 配置项

`skill-server` 使用 `skill.snowflake.*`，`ai-gateway` 使用 `gateway.snowflake.*`。

关键字段：

- `epoch-ms`
- `service-code`
- `worker-id`
- `service-bits`
- `worker-bits`
- `sequence-bits`
- `clock-backwards-strategy`
- `max-backward-ms`

启动时会校验：

- bit allocation 必须为正数
- `serviceCode` 与 `workerId` 不能超出对应 bit 上限
- 总位宽不能超过有符号 `Long` 的可用范围

## 主键落点

`skill-server`：

- `skill_session.id`
- `skill_message.id`
- `skill_message_part.id`

`ai-gateway`：

- `agent_connection.id`
- `ak_sk_credential.id`

对应 MyBatis `insert` 已改为显式写入 `id`，不再使用 `useGeneratedKeys`。

## 测试环境重建

测试环境允许清库重建时，按以下原则执行：

1. 先清理旧测试数据，避免保留依赖自增主键的历史记录。
2. 重新执行数据库 migration，确保 `V5__snowflake_primary_keys.sql` 生效。
3. 重启 `skill-server` 与 `ai-gateway`，让新配置和新写库链路同时生效。
4. 重点回归以下链路：
   - `skill-server` 创建会话后返回 Snowflake `welinkSessionId`
   - `gateway` 注册/复用 agent 记录时继续保持身份复用
   - 流式消息、回流消息、权限请求等依赖 session/message 主键的路径无回归

## 风险边界

- 该方案假设测试环境可以接受 schema 重建，不提供长期双轨兼容。
- 若未来新增第三个上游服务，必须先分配新的 `serviceCode`，不能复用现有编码。
