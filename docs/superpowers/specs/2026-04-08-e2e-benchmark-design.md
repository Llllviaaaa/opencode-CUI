# 端到端性能压测脚本设计

> 日期：2026-04-08
> 状态：Draft

---

## 目标

编写一个 Python 端到端性能压测工具，测量 Skill Server（SS）和 AI Gateway（GW）的：

1. **吞吐量（TPS）**：每秒完成的消息往返数
2. **并发连接数**：同时支撑的 WebSocket 连接数上限
3. **端到端延迟分布**：P50 / P95 / P99 / min / max / avg

---

## 架构概览

### 目录结构

```
bench/
├── __init__.py
├── main.py              # CLI 入口，解析参数，编排全流程
├── config.py            # 配置管理（CLI > 环境变量 > 默认值）
├── auth.py              # AK/SK HMAC-SHA256 签名生成
├── mock_agent.py        # Mock/Echo Agent，连接 GW /ws/agent
├── client.py            # 虚拟用户，HTTP 发消息 + WS 接收流
├── metrics.py           # 实时指标收集 + 终端展示
├── report.py            # 汇总报告生成（终端表格 + JSON/CSV）
└── credentials.csv      # AK/SK 凭证配置文件（用户手动填写）
```

### 端到端消息流（压测路径）

```
Client (HTTP POST)
  → SS /api/skill/sessions/{sessionId}/messages
    → SS WebSocket Client
      → GW /ws/skill
        → GW EventRelayService
          → Mock Agent (/ws/agent)
            → [Mock: 立即回复 | Echo: 延迟回复]
          → GW SkillRelayService
        → SS GatewayMessageRouter
      → SS /ws/skill/stream
  → Client (WS 收到 tool_done)
```

### 运行模型：1:1 绑定

每一对 Agent+Client 共享同一组 AK/SK 凭证：

- 1 个 Mock Agent 连接 GW `/ws/agent`
- 1 个 Client 创建 Session 并通过 SS 发消息
- Client 串行发送：发一条 → 等 Agent 回复 `tool_done` → 再发下一条
- 整体并发度由 `--pairs` 数量决定

---

## 模块设计

### 1. main.py — 编排入口

**全流程**：

```
1. 解析 CLI 参数 + 加载配置
2. 读取 credentials.csv，取前 N 行（N = pairs）
3. 启动 N 个 Mock Agent，连接 GW，完成 register
4. 等待所有 Agent 就绪
5. 为每个 Client 创建 Session（POST /api/skill/sessions）
6. 启动 N 个 Client 并发发消息（支持 ramp-up 逐步增加）
7. 实时采集指标（每 2 秒终端刷新）
8. 到达 duration 后停止所有 Client 和 Agent
9. 输出终端汇总表格 + 导出报告文件
```

### 2. mock_agent.py — Mock/Echo Agent

**连接建立**：

- WebSocket 连接 `ws://{gw_host}/ws/agent`
- 握手通过 `Sec-WebSocket-Protocol` 子协议传递 AK/SK 签名
- 连接成功后发送 `register` 消息（包含 device 信息）
- 每 30 秒发送 `heartbeat`

**消息处理**：

- **Mock 模式**：收到 invoke 后立即回复
  - 发送 `tool_event`（status: "running", content: "Mock response"）
  - 发送 `tool_done`（status: "completed"）
- **Echo 模式**：收到 invoke 后延迟 N 毫秒再回复
  - `await asyncio.sleep(echo_delay_ms / 1000)`
  - 回复同上

**并发模型**：每个 Agent 是一个 `asyncio.Task`，共享事件循环。

**容错**：断线时上报 metrics，不自动重连（压测中断线即为有意义的指标）。

### 3. client.py — 虚拟用户

**生命周期**：

```
1. 创建会话：POST /api/skill/sessions（指定 ak，获得 sessionId）
2. 建立 WS：ws://{ss_host}/ws/skill/stream（通过 userId cookie 认证）
3. 循环：
   a. POST /api/skill/sessions/{sessionId}/messages（body: { content: "..." }）
   b. 记录 t_send
   c. 等待 WS 收到对应 sessionId 的 tool_done
   d. 记录 t_recv，上报延迟 = t_recv - t_send
   e. 重复直到 duration 结束
4. 关闭 WS 连接
```

**容错**：

- HTTP 请求失败：记录 error，继续下一轮
- WS 响应超时（默认 30 秒）：记录 timeout，继续下一轮

### 4. auth.py — AK/SK 签名

**算法**（与 GW `AkSkAuthService` 一致）：

```
1. timestamp = 当前秒级时间戳
2. nonce = UUID
3. string_to_sign = ak + timestamp + nonce
4. signature = HMAC-SHA256(sk, string_to_sign)
5. 编码为 Sec-WebSocket-Protocol: auth.{base64(ak:timestamp:nonce:signature)}
```

**依赖**：仅 Python 标准库（`hmac`、`hashlib`、`base64`、`uuid`）。

### 5. metrics.py — 实时指标收集

**采集指标**：

| 类别 | 指标 | 说明 |
|------|------|------|
| 吞吐量 | TPS | 每秒完成的消息往返数 |
| 延迟 | min / avg / P50 / P95 / P99 / max | 端到端延迟分布（ms） |
| 连接 | active_agents / active_clients | 当前存活的 WebSocket 连接数 |
| 错误 | http_errors / ws_timeouts / ws_disconnects | 各类失败计数 |
| 计数 | total_sent / total_received / total_failed | 消息总量 |

**实时终端输出**（每 2 秒刷新）：

```
[00:32] pairs=50 | TPS=124.5 | avg=38ms p95=82ms p99=156ms | err=0 | sent=3980 recv=3980
```

### 6. report.py — 汇总报告

**输出文件**（写入 `--output-dir`）：

- `bench_report_{timestamp}.json` — 完整原始数据（每条消息的延迟、时间序列 TPS）
- `bench_summary_{timestamp}.csv` — 汇总表

**终端汇总表格**：

```
═══════════════════════════════════════════
  Benchmark Summary (mock mode, 60s)
═══════════════════════════════════════════
  Pairs:        50
  Total Sent:   6230
  Total Recv:   6228
  Failed:       2
  ───────────────────────────────────────
  TPS (avg):    103.8
  TPS (peak):   128.4
  ───────────────────────────────────────
  Latency (ms):
    min=12  avg=41  P50=38  P95=85  P99=163  max=312
  ───────────────────────────────────────
  Connections:
    Agent disconnects: 0
    Client disconnects: 0
    WS timeouts: 2
═══════════════════════════════════════════
```

### 7. credentials.csv — 凭证配置

**用途**：用户手动填写真实的 AK/SK 凭证，脚本启动时读取。

**格式**：

```csv
ak,sk,user_id
your_ak_here,your_sk_here,your_user_id_here
```

GW 使用远程鉴权模式（调用外部服务校验 AK/SK），无需本地数据库导入。

---

## 配置

### CLI 参数

| 参数 | 默认值 | 说明 |
|------|--------|------|
| `--mode` | `mock` | `mock`（立即回复）或 `echo`（延迟回复） |
| `--pairs` | `10` | Agent+Client 对数（1:1 绑定） |
| `--duration` | `60` | 测试持续时间（秒） |
| `--echo-delay` | `500` | Echo 模式下 Agent 回复延迟（ms） |
| `--ramp-up` | `0` | 每秒新增的 pair 数，0 = 全部同时启动 |
| `--timeout` | `30` | 单条消息响应超时（秒） |
| `--ss-url` | `http://localhost:8082` | Skill Server 地址 |
| `--gw-url` | `ws://localhost:8081` | AI Gateway 地址 |
| `--credentials` | `./bench/credentials.csv` | 凭证文件路径 |
| `--output-dir` | `./bench_results` | 报告输出目录 |
| `--internal-token` | `changeme` | SS→GW 内部认证 token |

### 环境变量覆盖

优先级：CLI > 环境变量 > 默认值

| 环境变量 | 对应参数 |
|----------|----------|
| `BENCH_SS_URL` | `--ss-url` |
| `BENCH_GW_URL` | `--gw-url` |
| `BENCH_INTERNAL_TOKEN` | `--internal-token` |

---

## 依赖

仅使用 Python 标准库 + 两个第三方库：

- `aiohttp` — 异步 HTTP 客户端（发送消息到 SS REST API）
- `websockets` — 异步 WebSocket 客户端（连接 GW 和 SS）

```
# requirements.txt
aiohttp>=3.9
websockets>=12.0
```

---

## 已知瓶颈参考

压测时关注这些服务端配置，它们可能是瓶颈：

| 组件 | 配置 | 默认值 |
|------|------|--------|
| SS → GW WebSocket 连接数 | `skill.gateway.connection-count` | 3 |
| SS MySQL 连接池 | `hikari.maximum-pool-size` | 20 |
| SS Redis 连接池 | `lettuce.pool.max-active` | 50 |
| GW MySQL 连接池 | `hikari.maximum-pool-size` | 20 |
| GW Redis 连接池 | `lettuce.pool.max-active` | 50 |
| GW WebSocket 消息缓冲 | `max-text-message-buffer-size-bytes` | 1 MB |
| GW Agent 心跳超时 | `agent.heartbeat-timeout-seconds` | 90 |
