# MiniApp 场景压测设计规格

## 1. 概述

针对 MiniApp 场景，对 Skill Server (SS, :8082) 和 AI-Gateway (GW, :8081) 进行独立压测和端到端压测。使用纯 Python (asyncio + websockets + aiohttp) 编写压测脚本，通过 Mock 组件实现各层独立测试。

### 1.1 MiniApp 完整链路

```
下行（用户发消息）：
POST /api/inbound/messages (SS HTTP)
  → InboundProcessingService
  → GatewayRelayService → /ws/skill (GW WebSocket)
  → SkillRelayService → /ws/agent (Agent WebSocket)

上行（Agent 回复）：
Agent → /ws/agent (GW)
  → EventRelayService → SkillRelayService
  → SS WebSocket → GatewayMessageRouter
  → StreamBufferService → /ws/skill/stream → MiniApp
```

### 1.2 核心压测模式

所有吞吐量测试采用**同步请求-响应模式**：每个会话发出 invoke 后，必须收到 tool_done 才发下一条。通过渐进增加并发会话数来加压。

## 2. 项目结构

```
benchmark/
├── config/
│   ├── default.yaml              # 默认配置（地址、端口、token、加压梯度）
│   └── ak_sk_pairs.csv           # GW 压测用 AK/SK 对
├── core/
│   ├── runner.py                 # 渐进式加压引擎
│   ├── metrics.py                # 实时指标收集 + CSV/JSON 报告
│   ├── auth.py                   # AK/SK HMAC-SHA256 签名 + SS token Base64 编码
│   └── protocol.py               # 消息协议定义（所有 JSON 消息结构）
├── mocks/
│   ├── mock_gw.py                # Mock Gateway: WebSocket Server /ws/skill
│   ├── mock_ss.py                # Mock Skill Server: WebSocket Client 连 /ws/skill
│   ├── mock_agent.py             # Mock Agent: WebSocket Client 连 /ws/agent
│   └── mock_miniapp.py           # Mock MiniApp: WebSocket Client 连 /ws/skill/stream
├── scenarios/
│   ├── ss_bench.py               # SS 压测场景
│   ├── gw_bench.py               # GW 压测场景
│   └── e2e_bench.py              # 端到端压测场景
├── run.py                        # 统一入口 CLI
└── requirements.txt
```

## 3. Mock 组件设计

### 3.1 MockGW (`mocks/mock_gw.py`)

**角色**：WebSocket Server，替代真实 GW，供 SS 的 GatewayRelayService 连接。

**行为**：
- 监听 `/ws/skill` 端点
- 验证 SS 的 `Sec-WebSocket-Protocol` 认证头：`auth.{base64({"token":"...", "source":"skill-server", "instanceId":"..."})}`
- 接收 SS 发来的 invoke 消息
- 根据 invoke 的 ak + toolSessionId，自动回复 `tool_event`（可选）+ `tool_done`
- 支持配置回复延迟（模拟 Agent 处理时间）

**invoke 接收格式**：
```json
{
  "type": "invoke",
  "ak": "app_key",
  "source": "skill-server",
  "userId": "user_id",
  "welinkSessionId": "session_id",
  "action": "chat",
  "payload": {
    "text": "prompt",
    "toolSessionId": "tool_session_id",
    "assistantAccount": "bot_account",
    "sendUserAccount": "sender",
    "messageId": "msg_id"
  },
  "traceId": "trace_id"
}
```

**tool_done 回复格式**：
```json
{
  "type": "tool_done",
  "ak": "app_key",
  "toolSessionId": "tool_session_id",
  "welinkSessionId": "session_id",
  "usage": { "input_tokens": 100, "output_tokens": 50 },
  "traceId": "trace_id"
}
```

### 3.2 MockSS (`mocks/mock_ss.py`)

**角色**：WebSocket Client，模拟 SS 的 GatewayRelayService 连接 GW。

**行为**：
- 连接 GW 的 `/ws/skill`，携带 `Sec-WebSocket-Protocol: auth.{base64({"token":"internal-token", "source":"skill-server", "instanceId":"mock-ss-1"})}`
- 建立少量连接（默认 8 条，与真实 SS 一致）
- 发送 invoke 消息，等待对应的 tool_done 回复
- 支持并发会话：多个会话复用连接池，每个会话独立跑同步循环

**invoke 发送格式**：同 3.1 中的 invoke 格式。

### 3.3 MockAgent (`mocks/mock_agent.py`)

**角色**：WebSocket Client，模拟 PCAgent 连接 GW。

**认证流程**：
1. 连接 `/ws/agent`，携带 `Sec-WebSocket-Protocol: auth.{base64({"ak":"...", "ts":"毫秒时间戳", "nonce":"随机串", "sign":"HmacSHA256(ak+ts+nonce, sk)"})}`
2. 连接成功后发送 register 消息：
```json
{
  "type": "register",
  "ak": "app_key",
  "deviceName": "benchmark-agent",
  "macAddress": "00:00:00:00:00:01",
  "os": "linux",
  "toolType": "benchmark",
  "toolVersion": "1.0.0"
}
```
3. 定时发送心跳（每 30 秒）：
```json
{ "type": "heartbeat", "ak": "app_key" }
```

**收到 invoke 后的行为**：
- 解析 invoke 消息
- 回复 tool_done：
```json
{
  "type": "tool_done",
  "ak": "app_key",
  "toolSessionId": "从 invoke 中获取",
  "welinkSessionId": "从 invoke 中获取",
  "usage": { "input_tokens": 100, "output_tokens": 50 },
  "traceId": "从 invoke 中获取"
}
```

### 3.4 MockMiniAppClient (`mocks/mock_miniapp.py`)

**角色**：WebSocket Client，模拟 MiniApp 前端连接 SS。

**行为**：
- 连接 `/ws/skill/stream`，携带 `Cookie: userId={userId}`
- 接收 SS 推送的 StreamMessage
- 识别 `tool_done` 类型消息，通知对应会话循环可以发下一条
- 记录收到消息的时间戳用于延迟计算

## 4. 压测场景设计

### 4.1 SS 压测 (`scenarios/ss_bench.py`)

**依赖组件**：MockGW + MockMiniAppClient + 真实 SS（含 MySQL + Redis）

**前置条件**：
- 在 DB 中预建 N 个 SkillSession（已有 toolSessionId，状态就绪）
- 每个 session 对应一个 userId

**子场景 A：连接容量**
- 渐进创建 MockMiniAppClient WebSocket 连接到 `/ws/skill/stream`
- 不发消息，仅观察连接成功率和 SS 资源消耗
- 记录：成功连接数、连接建立耗时、失败率

**子场景 B：吞吐量 + 延迟**
- 每个并发 client 运行一个同步循环：
  1. `POST /api/inbound/messages`（携带预建 session 对应的参数）
  2. SS 处理 → invoke 发给 MockGW
  3. MockGW 自动回复 tool_done
  4. SS 接收 tool_done → 推送给 MockMiniAppClient
  5. MockMiniAppClient 收到推送 → 通知循环发下一条
- 渐进增加并发 client 数
- 记录：QPS、往返延迟（P50/P95/P99/Max）、错误率

**HTTP 请求格式**：
```
POST /api/inbound/messages
Content-Type: application/json

{
  "businessDomain": "im",
  "sessionType": "direct",
  "sessionId": "bench_session_{n}",
  "assistantAccount": "bench_bot",
  "senderUserAccount": "bench_user_{n}",
  "content": "benchmark test message",
  "msgType": "text"
}
```

### 4.2 GW 压测 (`scenarios/gw_bench.py`)

**依赖组件**：MockSS + MockAgent + 真实 GW（含 MySQL + Redis）

**前置条件**：
- 准备 M 个 AK/SK 对（从 ak_sk_pairs.csv 加载）
- GW 数据库中已有对应的 agent_connection 和 device_binding 记录

**子场景 A：连接容量**
- 渐进创建 MockAgent WebSocket 连接到 `/ws/agent`
- 每个连接完成 AK/SK 认证 + register 流程
- 记录：成功注册数、认证耗时、失败率

**子场景 B：吞吐量 + 延迟**
- 预先注册 M 个 MockAgent 并保持心跳
- MockSS 建立 8 条 WebSocket 连接到 `/ws/skill`
- 渐进增加并发会话数，每个会话跑同步循环：
  1. MockSS 发 invoke(ak=agent_x) → GW 路由
  2. MockAgent_x 收到 invoke → 回复 tool_done
  3. GW 路由 tool_done → MockSS 收到
  4. 该会话发下一条 invoke
- 记录：QPS、往返延迟（P50/P95/P99/Max）、错误率

### 4.3 端到端压测 (`scenarios/e2e_bench.py`)

**依赖组件**：MockAgent + MockMiniAppClient + 真实 SS + 真实 GW

**前置条件**：
- M 个 MockAgent 注册到 GW
- N 个 SkillSession 预建到 SS 数据库
- N 个 MockMiniAppClient 连接到 SS

**流程**：
- 渐进增加并发 client 数，每个 client 跑同步循环：
  1. `POST /api/inbound/messages` → SS → GW → MockAgent
  2. MockAgent 回 tool_done → GW → SS → MockMiniAppClient
  3. 收到后发下一条
- 记录：全链路 QPS、端到端延迟（P50/P95/P99/Max）、各段分段耗时、错误率

## 5. 加压梯度

```yaml
stages:
  - concurrency: 100
    duration: 30s
  - concurrency: 500
    duration: 30s
  - concurrency: 1000
    duration: 60s
  - concurrency: 3000
    duration: 60s
  - concurrency: 5000
    duration: 60s
  - concurrency: 10000
    duration: 60s
```

每个梯度之间有 5 秒的 ramp-up 时间，用于渐进创建新连接/会话。

## 6. 指标与报告

### 6.1 实时控制台输出（每秒刷新）

```
[10:30:45] Stage 3/6 | Clients: 1000 | QPS: 2340 | P50: 12ms P95: 45ms P99: 89ms | Err: 0 (0.0%) | Conns: 1000/1000
```

### 6.2 最终报告

**JSON 报告** (`reports/ss_bench_20260418_103000.json`)：
```json
{
  "scenario": "ss_throughput",
  "target": "localhost:8082",
  "started_at": "2026-04-18T10:30:00Z",
  "finished_at": "2026-04-18T10:35:30Z",
  "stages": [
    {
      "concurrency": 100,
      "duration_sec": 30,
      "total_requests": 12000,
      "qps": 400,
      "latency_ms": {
        "p50": 8,
        "p95": 32,
        "p99": 55,
        "max": 120,
        "avg": 15
      },
      "errors": 0,
      "error_rate": 0.0
    }
  ]
}
```

**CSV 报告** (`reports/ss_bench_20260418_103000.csv`)：
```csv
stage,concurrency,duration_sec,total_requests,qps,p50_ms,p95_ms,p99_ms,max_ms,avg_ms,errors,error_rate
1,100,30,12000,400,8,32,55,120,15,0,0.0
2,500,30,45000,1500,12,48,92,210,22,3,0.007
```

## 7. 配置文件 (`config/default.yaml`)

```yaml
# 目标环境
ss:
  http_url: http://localhost:8082
  ws_url: ws://localhost:8082/ws/skill/stream
  inbound_token: e2e-test-token

gw:
  ws_skill_url: ws://localhost:8081/ws/skill
  ws_agent_url: ws://localhost:8081/ws/agent
  internal_token: changeme

# MockGW 配置（SS 压测时启动）
mock_gw:
  host: 0.0.0.0
  port: 8081
  reply_delay_ms: 5          # 模拟 Agent 处理延迟

# 加压梯度
stages:
  - concurrency: 100
    duration: 30s
  - concurrency: 500
    duration: 30s
  - concurrency: 1000
    duration: 60s
  - concurrency: 3000
    duration: 60s
  - concurrency: 5000
    duration: 60s
  - concurrency: 10000
    duration: 60s

ramp_up_seconds: 5            # 梯度间 ramp-up 时间

# MockSS 连接配置
mock_ss:
  connection_count: 8         # 连接池大小，与真实 SS 一致

# 报告输出
reports:
  dir: ./reports
  format:
    - json
    - csv
```

## 8. CLI 入口 (`run.py`)

```
# SS 吞吐量压测
python run.py ss --sub throughput --config config/default.yaml

# SS 连接容量压测
python run.py ss --sub connection --config config/default.yaml

# GW 吞吐量压测
python run.py gw --sub throughput --config config/default.yaml --credentials config/ak_sk_pairs.csv

# GW 连接容量压测
python run.py gw --sub connection --config config/default.yaml --credentials config/ak_sk_pairs.csv

# 端到端压测
python run.py e2e --config config/default.yaml --credentials config/ak_sk_pairs.csv

# 指定远程环境
python run.py ss --sub throughput --config config/remote.yaml
```

## 9. 依赖

```
aiohttp>=3.9
websockets>=12.0
pyyaml>=6.0
```

## 10. 认证实现

### 10.1 AK/SK 签名 (GW Agent 认证)

```python
import hmac, hashlib, base64, json, time, uuid

def build_agent_auth_protocol(ak: str, sk: str) -> str:
    ts = str(int(time.time() * 1000))
    nonce = uuid.uuid4().hex
    sign_str = ak + ts + nonce
    sign = hmac.new(sk.encode(), sign_str.encode(), hashlib.sha256).hexdigest()
    payload = json.dumps({"ak": ak, "ts": ts, "nonce": nonce, "sign": sign})
    return f"auth.{base64.b64encode(payload.encode()).decode()}"
```

### 10.2 SS Internal Token (SS→GW / 外部→SS 认证)

```python
def build_skill_auth_protocol(token: str, source: str, instance_id: str) -> str:
    payload = json.dumps({"token": token, "source": source, "instanceId": instance_id})
    return f"auth.{base64.b64encode(payload.encode()).decode()}"
```

### 10.3 MiniApp Cookie 认证

```python
# WebSocket 连接时携带 Cookie
headers = {"Cookie": f"userId={user_id}"}
```

## 11. AK/SK 对文件格式 (`config/ak_sk_pairs.csv`)

```csv
ak,sk,user_id
app_key_001,secret_key_001,user_001
app_key_002,secret_key_002,user_002
...
```
