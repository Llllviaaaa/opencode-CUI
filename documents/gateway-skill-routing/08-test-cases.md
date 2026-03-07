# 测试用例文档

> 版本：1.0  
> 日期：2026-03-07

## 一、后端单元测试

### 1.1 Gateway 内部 skill 接入

| 用例 ID | 场景 | 操作 | 期望结果 |
| --- | --- | --- | --- |
| UT-GW-01 | skill 长连接鉴权成功 | 使用正确 token 建立 `/ws/internal/skill` | gateway 接受连接并注册 local skill link |
| UT-GW-02 | skill 长连接鉴权失败 | 使用错误 token 建立连接 | gateway 拒绝连接 |
| UT-GW-03 | owner 注册 | 建立第一条 skill 长连接 | 写入 `gw:skill:owner:{instanceId}`，并 `SADD gw:skill:owners` |
| UT-GW-04 | owner 退订 | 断开最后一条 skill 长连接 | 删除 owner TTL key，并从 `gw:skill:owners` 移除 |
| UT-GW-05 | relay 订阅 | gateway 成为 owner | 已订阅 `gw:relay:{instanceId}` |

### 1.2 Gateway 上行 relay 路由

| 用例 ID | 场景 | 操作 | 期望结果 |
| --- | --- | --- | --- |
| UT-GW-11 | 本地 session 绑定命中 | 本地存在 `sessionId -> linkId` | 上行事件直接发送到对应 local skill link |
| UT-GW-12 | sticky owner 命中 | 本地无绑定，Redis 有有效 `gw:session:skill-owner:{sessionId}` | 发布到 `gw:relay:{instanceId}` |
| UT-GW-13 | sticky owner 失效 | sticky owner 指向的 TTL key 已过期 | 重新选择 owner 并覆盖 sticky 映射 |
| UT-GW-14 | 无 session 事件 | `agent_online` / `agent_offline` | 使用默认 active skill link 或定向 relay 到 owner gateway |
| UT-GW-15 | owner 选择算法 | 活跃 owner 为多个实例 | 使用 Rendezvous Hashing 选择结果稳定 |

### 1.3 Skill 主动建链客户端

| 用例 ID | 场景 | 操作 | 期望结果 |
| --- | --- | --- | --- |
| UT-SK-01 | 建链成功 | 读取 `skill.gateway.ws-url` 建立连接 | 成功连到 ALB 命中的 gateway |
| UT-SK-02 | 断线重连 | 主动断开连接 | 按配置执行指数退避重连 |
| UT-SK-03 | 发送 invoke | 调用 `sendInvokeToGateway()` | 通过新长连接发送 `GatewayMessage.invoke` |
| UT-SK-04 | 接收上行事件 | 收到 `tool_event` / `tool_done` 等消息 | 进入 `GatewayRelayService.handleGatewayMessage()` |

## 二、集成测试

### 2.1 下行闭环

| 用例 ID | 场景 | 步骤 | 期望结果 |
| --- | --- | --- | --- |
| IT-01 | skill 通过 ALB 建链 | 启动 2 个 gateway，1 个 skill-server，经 ALB 建链 | skill-server 成功连接到任意一个健康 gateway |
| IT-02 | invoke 命中 agent owner gateway | skill 发起 `chat` invoke，目标 agent 挂在另一台 gateway | `agent:{agentId}` 路由命中正确 gateway，并下发给 PCAgent |
| IT-03 | session 绑定建立 | invoke 带 `sessionId` 通过 gateway 发出 | connected gateway 建立本地 `sessionId -> linkId` 绑定，并写入 `gw:session:skill-owner:{sessionId}` |

### 2.2 上行闭环

| 用例 ID | 场景 | 步骤 | 期望结果 |
| --- | --- | --- | --- |
| IT-11 | agent owner gateway 有本地 skill link | agent 上行事件进入本地有绑定的 gateway | 事件直接通过对应 local skill link 进入 skill-server |
| IT-12 | agent owner gateway 无本地 skill link | agent 上行事件进入 non-owner gateway | 事件通过 `gw:relay:{instanceId}` 定向中继到 owner gateway |
| IT-13 | 同一 session 稳定回流 | 连续发送同一 `sessionId` 的多条 `tool_event` | 始终命中同一条 local skill link |
| IT-14 | session 广播 | skill-server 收到上行事件后广播 `session:{sessionId}` | 持有前端订阅的 skill 实例均收到 StreamMessage |

## 三、故障切换测试

### 3.1 Owner 失效

| 用例 ID | 场景 | 步骤 | 期望结果 |
| --- | --- | --- | --- |
| FT-01 | owner gateway 宕机 | sticky owner 指向的 gateway 下线 | `gw:skill:owner:{instanceId}` 过期后重新选主 |
| FT-02 | owner gateway 失去所有 skill link | 主动断开 owner gateway 本地最后一条 skill 连接 | gateway 被移出 `gw:skill:owners` |
| FT-03 | session sticky 重绑 | owner 失效后同一 session 再次有上行事件 | 重新写入 `gw:session:skill-owner:{sessionId}` |

### 3.2 长连接重连

| 用例 ID | 场景 | 步骤 | 期望结果 |
| --- | --- | --- | --- |
| FT-11 | ALB target 切换 | skill-server 当前连接的 gateway 下线 | skill-server 重连到新的健康 gateway |
| FT-12 | gateway 重启 | 持有本地 session 绑定的 gateway 重启 | 本地绑定消失，后续事件通过 sticky owner 或重选恢复 |

## 四、部署与 UAT 场景

### 4.1 部署检查

| 用例 ID | 场景 | 步骤 | 期望结果 |
| --- | --- | --- | --- |
| UAT-01 | skill 共享配置 | skill 集群所有实例使用相同 `skill.gateway.ws-url` | 全部实例可通过 ALB 建链 |
| UAT-02 | ALB 健康检查 | 配置 HTTP 健康检查端点 | 只有健康 gateway 接收 skill 长连接 |
| UAT-03 | ALB idle timeout | 配置 idle timeout 大于内部心跳周期 | 长连接不因空闲超时被频繁断开 |

### 4.2 多实例链路验证

| 用例 ID | 场景 | 步骤 | 期望结果 |
| --- | --- | --- | --- |
| UAT-11 | 2 gateway + 2 skill | 启动双实例 gateway 和 skill | 下行 invoke、上行 relay、session 广播全部可用 |
| UAT-12 | 混合 owner 场景 | 一台 gateway 只有 agent，无 skill；另一台只有 skill，无 agent | agent 上行事件可经 relay 回流 skill 集群 |

## 五、语义边界与恢复说明

### 5.1 Pub/Sub at-most-once

| 用例 ID | 场景 | 步骤 | 期望结果 |
| --- | --- | --- | --- |
| SEM-01 | relay 期间 owner 瞬时掉线 | 向 `gw:relay:{instanceId}` publish 时 owner 同时失效 | 允许消息丢失，系统记录日志并等待后续重选 |
| SEM-02 | sticky owner 陈旧 | `gw:session:skill-owner:{sessionId}` 指向不存在 owner | 重新选主并覆盖 sticky 映射 |

### 5.2 Recovery 说明

对于 `SEM-01` 这类场景，系统的恢复依赖不是 Redis relay 本身，而是：

- skill 侧已有的 sequence gap 检测
- 客户端 reconnect / resume
- 后续事件触发的 sticky owner 重选

本方案文档明确接受：

- relay 层没有确认和重试
- failover 期间可能出现少量流式缺口
- steady-state 通过 sticky 路由尽量降低这类风险
