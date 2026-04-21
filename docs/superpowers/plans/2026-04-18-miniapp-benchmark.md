# MiniApp 场景压测脚本 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 构建一套纯 Python 压测工具，通过 Mock 组件对 SS 和 GW 进行独立压测和端到端压测，支持渐进式加压（100→10000）、实时指标、CSV/JSON 报告。

**Architecture:** 核心层（protocol/auth/metrics/runner）提供基础能力，Mock 层（mock_gw/mock_ss/mock_agent/mock_miniapp）模拟各系统组件，场景层（ss_bench/gw_bench/e2e_bench）组合 Mock 组件实现具体压测场景。所有 Mock 和场景基于 asyncio 实现高并发。

**Tech Stack:** Python 3.11+, asyncio, websockets, aiohttp, PyYAML

**Spec:** `docs/superpowers/specs/2026-04-18-miniapp-benchmark-design.md`

---

### Task 1: 项目脚手架 + 配置文件

**Files:**
- Create: `benchmark/requirements.txt`
- Create: `benchmark/config/default.yaml`
- Create: `benchmark/config/ak_sk_pairs.csv`
- Create: `benchmark/core/__init__.py`
- Create: `benchmark/mocks/__init__.py`
- Create: `benchmark/scenarios/__init__.py`

- [ ] **Step 1: 创建目录结构和 requirements.txt**

```txt
aiohttp>=3.9
websockets>=12.0
pyyaml>=6.0
```

- [ ] **Step 2: 创建 default.yaml**

```yaml
ss:
  http_url: http://localhost:8082
  ws_url: ws://localhost:8082/ws/skill/stream
  inbound_token: e2e-test-token

gw:
  ws_skill_url: ws://localhost:8081/ws/skill
  ws_agent_url: ws://localhost:8081/ws/agent
  internal_token: changeme

mock_gw:
  host: 0.0.0.0
  port: 8081
  reply_delay_ms: 5

mock_ss:
  connection_count: 8

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

ramp_up_seconds: 5

reports:
  dir: ./reports
  format:
    - json
    - csv
```

- [ ] **Step 3: 创建示例 ak_sk_pairs.csv**

```csv
ak,sk,user_id
app_key_001,secret_key_001,user_001
app_key_002,secret_key_002,user_002
app_key_003,secret_key_003,user_003
```

- [ ] **Step 4: 创建所有 `__init__.py`（空文件）**

- [ ] **Step 5: 安装依赖验证**

Run: `cd benchmark && pip install -r requirements.txt`
Expected: 所有包安装成功

- [ ] **Step 6: Commit**

```bash
git add benchmark/
git commit -m "feat(benchmark): scaffold project structure with config files"
```

---

### Task 2: core/protocol.py — 消息协议定义

**Files:**
- Create: `benchmark/core/protocol.py`
- Create: `benchmark/tests/__init__.py`
- Create: `benchmark/tests/test_protocol.py`

- [ ] **Step 1: 编写测试**

```python
# benchmark/tests/test_protocol.py
import json
from core.protocol import (
    build_invoke_message,
    build_tool_done_message,
    build_register_message,
    build_heartbeat_message,
    build_inbound_request_body,
    parse_message,
)


def test_build_invoke_message():
    msg = build_invoke_message(
        ak="ak1",
        user_id="u1",
        welink_session_id="ws1",
        tool_session_id="ts1",
        text="hello",
        assistant_account="bot1",
        sender_account="sender1",
        message_id="m1",
        trace_id="t1",
    )
    data = json.loads(msg)
    assert data["type"] == "invoke"
    assert data["ak"] == "ak1"
    assert data["action"] == "chat"
    assert data["payload"]["toolSessionId"] == "ts1"
    assert data["payload"]["text"] == "hello"
    assert data["traceId"] == "t1"


def test_build_tool_done_message():
    msg = build_tool_done_message(
        ak="ak1",
        tool_session_id="ts1",
        welink_session_id="ws1",
        trace_id="t1",
    )
    data = json.loads(msg)
    assert data["type"] == "tool_done"
    assert data["ak"] == "ak1"
    assert data["toolSessionId"] == "ts1"
    assert data["usage"]["input_tokens"] == 100


def test_build_register_message():
    msg = build_register_message(ak="ak1", index=5)
    data = json.loads(msg)
    assert data["type"] == "register"
    assert data["ak"] == "ak1"
    assert data["macAddress"] == "00:00:00:00:00:05"


def test_build_heartbeat_message():
    msg = build_heartbeat_message(ak="ak1")
    data = json.loads(msg)
    assert data["type"] == "heartbeat"
    assert data["ak"] == "ak1"


def test_build_inbound_request_body():
    body = build_inbound_request_body(
        session_id="s1",
        assistant_account="bot1",
        sender_account="sender1",
        content="hi",
    )
    assert body["businessDomain"] == "im"
    assert body["sessionType"] == "direct"
    assert body["sessionId"] == "s1"
    assert body["msgType"] == "text"


def test_parse_message():
    raw = json.dumps({"type": "tool_done", "ak": "ak1", "toolSessionId": "ts1"})
    parsed = parse_message(raw)
    assert parsed["type"] == "tool_done"
    assert parsed["toolSessionId"] == "ts1"
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd benchmark && python -m pytest tests/test_protocol.py -v`
Expected: FAIL — ModuleNotFoundError

- [ ] **Step 3: 实现 protocol.py**

```python
# benchmark/core/protocol.py
"""消息协议定义 — 所有 JSON 消息的构建和解析。"""

import json
import uuid
import time


def _trace_id() -> str:
    return uuid.uuid4().hex


def build_invoke_message(
    ak: str,
    user_id: str,
    welink_session_id: str,
    tool_session_id: str,
    text: str,
    assistant_account: str,
    sender_account: str,
    message_id: str | None = None,
    trace_id: str | None = None,
) -> str:
    return json.dumps({
        "type": "invoke",
        "ak": ak,
        "source": "skill-server",
        "userId": user_id,
        "welinkSessionId": welink_session_id,
        "action": "chat",
        "payload": {
            "text": text,
            "toolSessionId": tool_session_id,
            "assistantAccount": assistant_account,
            "sendUserAccount": sender_account,
            "messageId": message_id or str(int(time.time() * 1000)),
        },
        "traceId": trace_id or _trace_id(),
    })


def build_tool_done_message(
    ak: str,
    tool_session_id: str,
    welink_session_id: str,
    trace_id: str | None = None,
) -> str:
    return json.dumps({
        "type": "tool_done",
        "ak": ak,
        "toolSessionId": tool_session_id,
        "welinkSessionId": welink_session_id,
        "usage": {"input_tokens": 100, "output_tokens": 50},
        "traceId": trace_id or _trace_id(),
    })


def build_register_message(ak: str, index: int = 0) -> str:
    return json.dumps({
        "type": "register",
        "ak": ak,
        "deviceName": f"benchmark-agent-{index}",
        "macAddress": f"00:00:00:00:00:{index:02d}",
        "os": "linux",
        "toolType": "benchmark",
        "toolVersion": "1.0.0",
    })


def build_heartbeat_message(ak: str) -> str:
    return json.dumps({"type": "heartbeat", "ak": ak})


def build_inbound_request_body(
    session_id: str,
    assistant_account: str,
    sender_account: str,
    content: str = "benchmark test message",
) -> dict:
    return {
        "businessDomain": "im",
        "sessionType": "direct",
        "sessionId": session_id,
        "assistantAccount": assistant_account,
        "senderUserAccount": sender_account,
        "content": content,
        "msgType": "text",
    }


def parse_message(raw: str) -> dict:
    return json.loads(raw)
```

- [ ] **Step 4: 运行测试确认通过**

Run: `cd benchmark && python -m pytest tests/test_protocol.py -v`
Expected: 6 passed

- [ ] **Step 5: Commit**

```bash
git add benchmark/core/protocol.py benchmark/tests/
git commit -m "feat(benchmark): add protocol message builders and parser"
```

---

### Task 3: core/auth.py — 认证函数

**Files:**
- Create: `benchmark/core/auth.py`
- Create: `benchmark/tests/test_auth.py`

- [ ] **Step 1: 编写测试**

```python
# benchmark/tests/test_auth.py
import base64
import json
import hmac
import hashlib
from core.auth import (
    build_agent_auth_protocol,
    build_skill_auth_protocol,
    build_miniapp_headers,
)


def test_build_agent_auth_protocol_format():
    proto = build_agent_auth_protocol("ak1", "sk1")
    assert proto.startswith("auth.")
    payload_b64 = proto[5:]
    payload = json.loads(base64.b64decode(payload_b64))
    assert payload["ak"] == "ak1"
    assert "ts" in payload
    assert "nonce" in payload
    assert "sign" in payload


def test_build_agent_auth_protocol_signature():
    proto = build_agent_auth_protocol("ak1", "sk1")
    payload = json.loads(base64.b64decode(proto[5:]))
    sign_str = payload["ak"] + payload["ts"] + payload["nonce"]
    expected = hmac.new(
        "sk1".encode(), sign_str.encode(), hashlib.sha256
    ).hexdigest()
    assert payload["sign"] == expected


def test_build_skill_auth_protocol():
    proto = build_skill_auth_protocol("token1", "skill-server", "inst-1")
    assert proto.startswith("auth.")
    payload = json.loads(base64.b64decode(proto[5:]))
    assert payload["token"] == "token1"
    assert payload["source"] == "skill-server"
    assert payload["instanceId"] == "inst-1"


def test_build_miniapp_headers():
    headers = build_miniapp_headers("user_123")
    assert headers["Cookie"] == "userId=user_123"
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd benchmark && python -m pytest tests/test_auth.py -v`
Expected: FAIL

- [ ] **Step 3: 实现 auth.py**

```python
# benchmark/core/auth.py
"""认证工具 — AK/SK 签名、SS internal token、MiniApp Cookie。"""

import base64
import hashlib
import hmac
import json
import time
import uuid


def build_agent_auth_protocol(ak: str, sk: str) -> str:
    ts = str(int(time.time() * 1000))
    nonce = uuid.uuid4().hex
    sign_str = ak + ts + nonce
    sign = hmac.new(sk.encode(), sign_str.encode(), hashlib.sha256).hexdigest()
    payload = json.dumps({"ak": ak, "ts": ts, "nonce": nonce, "sign": sign})
    return f"auth.{base64.b64encode(payload.encode()).decode()}"


def build_skill_auth_protocol(
    token: str, source: str, instance_id: str
) -> str:
    payload = json.dumps(
        {"token": token, "source": source, "instanceId": instance_id}
    )
    return f"auth.{base64.b64encode(payload.encode()).decode()}"


def build_miniapp_headers(user_id: str) -> dict:
    return {"Cookie": f"userId={user_id}"}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `cd benchmark && python -m pytest tests/test_auth.py -v`
Expected: 4 passed

- [ ] **Step 5: Commit**

```bash
git add benchmark/core/auth.py benchmark/tests/test_auth.py
git commit -m "feat(benchmark): add auth utilities for AK/SK, internal token, and cookie"
```

---

### Task 4: core/metrics.py — 指标收集与报告

**Files:**
- Create: `benchmark/core/metrics.py`
- Create: `benchmark/tests/test_metrics.py`

- [ ] **Step 1: 编写测试**

```python
# benchmark/tests/test_metrics.py
import json
import os
import tempfile
from core.metrics import MetricsCollector


def test_record_and_percentiles():
    mc = MetricsCollector()
    mc.start_stage(1, 10)
    for i in range(100):
        mc.record_latency(float(i))
    mc.record_error()
    mc.record_error()
    snap = mc.snapshot()
    assert snap["total_requests"] == 100
    assert snap["errors"] == 2
    assert snap["p50"] == 49.5  # 中位数在 49-50 之间
    assert snap["p95"] >= 94
    assert snap["p99"] >= 98
    assert snap["max"] == 99.0
    assert snap["error_rate"] == 2 / 100


def test_finalize_stage():
    mc = MetricsCollector()
    mc.start_stage(1, 50)
    for i in range(10):
        mc.record_latency(10.0)
    result = mc.finalize_stage()
    assert result["concurrency"] == 50
    assert result["total_requests"] == 10
    assert result["latency_ms"]["p50"] == 10.0


def test_console_line():
    mc = MetricsCollector()
    mc.start_stage(1, 100)
    mc.record_latency(5.0)
    line = mc.console_line(stage_index=1, total_stages=6, connections=100)
    assert "Stage 1/6" in line
    assert "Clients: 100" in line


def test_save_reports(tmp_path):
    mc = MetricsCollector()
    mc.start_stage(1, 10)
    for _ in range(5):
        mc.record_latency(10.0)
    mc.finalize_stage()
    mc.start_stage(2, 20)
    for _ in range(5):
        mc.record_latency(20.0)
    mc.finalize_stage()

    mc.save_json(str(tmp_path / "report.json"), "test_scenario", "localhost")
    mc.save_csv(str(tmp_path / "report.csv"))

    with open(tmp_path / "report.json") as f:
        data = json.load(f)
    assert data["scenario"] == "test_scenario"
    assert len(data["stages"]) == 2

    with open(tmp_path / "report.csv") as f:
        lines = f.readlines()
    assert len(lines) == 3  # header + 2 rows
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd benchmark && python -m pytest tests/test_metrics.py -v`
Expected: FAIL

- [ ] **Step 3: 实现 metrics.py**

```python
# benchmark/core/metrics.py
"""指标收集 — 实时控制台输出 + CSV/JSON 报告生成。"""

import csv
import json
import time
import threading
from dataclasses import dataclass, field
from datetime import datetime, timezone


class MetricsCollector:
    def __init__(self):
        self._latencies: list[float] = []
        self._errors: int = 0
        self._lock = threading.Lock()
        self._stage_start: float = 0.0
        self._stage_num: int = 0
        self._stage_concurrency: int = 0
        self._stages: list[dict] = []

    def start_stage(self, stage_num: int, concurrency: int):
        with self._lock:
            self._latencies.clear()
            self._errors = 0
            self._stage_start = time.monotonic()
            self._stage_num = stage_num
            self._stage_concurrency = concurrency

    def record_latency(self, latency_ms: float):
        with self._lock:
            self._latencies.append(latency_ms)

    def record_error(self):
        with self._lock:
            self._errors += 1

    def snapshot(self) -> dict:
        with self._lock:
            lats = sorted(self._latencies)
            total = len(lats)
            errors = self._errors
            elapsed = time.monotonic() - self._stage_start
        if total == 0:
            return {
                "total_requests": 0,
                "errors": errors,
                "error_rate": 0.0,
                "qps": 0.0,
                "p50": 0.0,
                "p95": 0.0,
                "p99": 0.0,
                "max": 0.0,
                "avg": 0.0,
            }
        return {
            "total_requests": total,
            "errors": errors,
            "error_rate": errors / total if total > 0 else 0.0,
            "qps": total / elapsed if elapsed > 0 else 0.0,
            "p50": self._percentile(lats, 50),
            "p95": self._percentile(lats, 95),
            "p99": self._percentile(lats, 99),
            "max": lats[-1],
            "avg": sum(lats) / total,
        }

    def finalize_stage(self) -> dict:
        snap = self.snapshot()
        elapsed = time.monotonic() - self._stage_start
        result = {
            "concurrency": self._stage_concurrency,
            "duration_sec": round(elapsed, 1),
            **{
                "total_requests": snap["total_requests"],
                "qps": round(snap["qps"], 1),
                "latency_ms": {
                    "p50": round(snap["p50"], 1),
                    "p95": round(snap["p95"], 1),
                    "p99": round(snap["p99"], 1),
                    "max": round(snap["max"], 1),
                    "avg": round(snap["avg"], 1),
                },
                "errors": snap["errors"],
                "error_rate": round(snap["error_rate"], 4),
            },
        }
        self._stages.append(result)
        return result

    def console_line(
        self, stage_index: int, total_stages: int, connections: int
    ) -> str:
        snap = self.snapshot()
        ts = datetime.now().strftime("%H:%M:%S")
        err_pct = f"{snap['error_rate'] * 100:.1f}%"
        return (
            f"[{ts}] Stage {stage_index}/{total_stages} | "
            f"Clients: {self._stage_concurrency} | "
            f"QPS: {snap['qps']:.0f} | "
            f"P50: {snap['p50']:.0f}ms P95: {snap['p95']:.0f}ms "
            f"P99: {snap['p99']:.0f}ms | "
            f"Err: {snap['errors']} ({err_pct}) | "
            f"Conns: {connections}/{self._stage_concurrency}"
        )

    def save_json(self, path: str, scenario: str, target: str):
        report = {
            "scenario": scenario,
            "target": target,
            "generated_at": datetime.now(timezone.utc).isoformat(),
            "stages": self._stages,
        }
        with open(path, "w") as f:
            json.dump(report, f, indent=2)

    def save_csv(self, path: str):
        fieldnames = [
            "stage",
            "concurrency",
            "duration_sec",
            "total_requests",
            "qps",
            "p50_ms",
            "p95_ms",
            "p99_ms",
            "max_ms",
            "avg_ms",
            "errors",
            "error_rate",
        ]
        with open(path, "w", newline="") as f:
            writer = csv.DictWriter(f, fieldnames=fieldnames)
            writer.writeheader()
            for i, s in enumerate(self._stages, 1):
                writer.writerow({
                    "stage": i,
                    "concurrency": s["concurrency"],
                    "duration_sec": s["duration_sec"],
                    "total_requests": s["total_requests"],
                    "qps": s["qps"],
                    "p50_ms": s["latency_ms"]["p50"],
                    "p95_ms": s["latency_ms"]["p95"],
                    "p99_ms": s["latency_ms"]["p99"],
                    "max_ms": s["latency_ms"]["max"],
                    "avg_ms": s["latency_ms"]["avg"],
                    "errors": s["errors"],
                    "error_rate": s["error_rate"],
                })

    @staticmethod
    def _percentile(sorted_data: list[float], pct: int) -> float:
        if not sorted_data:
            return 0.0
        k = (len(sorted_data) - 1) * pct / 100
        f = int(k)
        c = f + 1
        if c >= len(sorted_data):
            return sorted_data[-1]
        return sorted_data[f] + (k - f) * (sorted_data[c] - sorted_data[f])
```

- [ ] **Step 4: 运行测试确认通过**

Run: `cd benchmark && python -m pytest tests/test_metrics.py -v`
Expected: 4 passed

- [ ] **Step 5: Commit**

```bash
git add benchmark/core/metrics.py benchmark/tests/test_metrics.py
git commit -m "feat(benchmark): add metrics collector with percentiles, console output, and report export"
```

---

### Task 5: core/runner.py — 渐进式加压引擎

**Files:**
- Create: `benchmark/core/runner.py`
- Create: `benchmark/core/config.py`
- Create: `benchmark/tests/test_runner.py`

- [ ] **Step 1: 编写测试**

```python
# benchmark/tests/test_runner.py
import asyncio
from core.config import load_config, StageConfig
from core.runner import Runner
from core.metrics import MetricsCollector


def test_load_config(tmp_path):
    cfg_file = tmp_path / "test.yaml"
    cfg_file.write_text("""
ss:
  http_url: http://localhost:8082
  ws_url: ws://localhost:8082/ws/skill/stream
  inbound_token: test-token
gw:
  ws_skill_url: ws://localhost:8081/ws/skill
  ws_agent_url: ws://localhost:8081/ws/agent
  internal_token: test-internal
mock_gw:
  host: 0.0.0.0
  port: 8081
  reply_delay_ms: 0
mock_ss:
  connection_count: 2
stages:
  - concurrency: 10
    duration: 5s
  - concurrency: 20
    duration: 10s
ramp_up_seconds: 1
reports:
  dir: ./reports
  format:
    - json
""")
    cfg = load_config(str(cfg_file))
    assert cfg.ss.http_url == "http://localhost:8082"
    assert len(cfg.stages) == 2
    assert cfg.stages[0].concurrency == 10
    assert cfg.stages[0].duration_sec == 5
    assert cfg.stages[1].duration_sec == 10
    assert cfg.ramp_up_seconds == 1


def test_parse_duration():
    assert StageConfig.parse_duration("30s") == 30
    assert StageConfig.parse_duration("2m") == 120
    assert StageConfig.parse_duration("60") == 60


def test_runner_calls_worker():
    """验证 Runner 能按梯度调度 worker 协程。"""
    call_log = []

    async def fake_worker(worker_id: int, metrics: MetricsCollector, stop_event: asyncio.Event):
        call_log.append(worker_id)
        await stop_event.wait()

    stages = [StageConfig(concurrency=3, duration_sec=1)]
    runner = Runner(stages=stages, ramp_up_seconds=0)

    asyncio.run(runner.run(fake_worker))
    assert len(call_log) == 3
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd benchmark && python -m pytest tests/test_runner.py -v`
Expected: FAIL

- [ ] **Step 3: 实现 config.py**

```python
# benchmark/core/config.py
"""配置加载 — 解析 YAML 配置文件为 dataclass。"""

import csv
import re
from dataclasses import dataclass, field
from pathlib import Path

import yaml


@dataclass
class SSConfig:
    http_url: str
    ws_url: str
    inbound_token: str


@dataclass
class GWConfig:
    ws_skill_url: str
    ws_agent_url: str
    internal_token: str


@dataclass
class MockGWConfig:
    host: str
    port: int
    reply_delay_ms: int


@dataclass
class MockSSConfig:
    connection_count: int


@dataclass
class ReportsConfig:
    dir: str
    format: list[str]


@dataclass
class StageConfig:
    concurrency: int
    duration_sec: int

    @staticmethod
    def parse_duration(val: str | int) -> int:
        if isinstance(val, int):
            return val
        val = str(val).strip()
        m = re.match(r"^(\d+)\s*(s|m)?$", val)
        if not m:
            raise ValueError(f"Invalid duration: {val}")
        num = int(m.group(1))
        unit = m.group(2) or "s"
        return num * 60 if unit == "m" else num


@dataclass
class AkSkPair:
    ak: str
    sk: str
    user_id: str


@dataclass
class BenchConfig:
    ss: SSConfig
    gw: GWConfig
    mock_gw: MockGWConfig
    mock_ss: MockSSConfig
    stages: list[StageConfig]
    ramp_up_seconds: int
    reports: ReportsConfig


def load_config(path: str) -> BenchConfig:
    with open(path) as f:
        raw = yaml.safe_load(f)

    stages = [
        StageConfig(
            concurrency=s["concurrency"],
            duration_sec=StageConfig.parse_duration(s["duration"]),
        )
        for s in raw["stages"]
    ]

    return BenchConfig(
        ss=SSConfig(**raw["ss"]),
        gw=GWConfig(**raw["gw"]),
        mock_gw=MockGWConfig(**raw["mock_gw"]),
        mock_ss=MockSSConfig(**raw["mock_ss"]),
        stages=stages,
        ramp_up_seconds=raw.get("ramp_up_seconds", 5),
        reports=ReportsConfig(**raw["reports"]),
    )


def load_credentials(path: str) -> list[AkSkPair]:
    pairs = []
    with open(path) as f:
        reader = csv.DictReader(f)
        for row in reader:
            pairs.append(AkSkPair(ak=row["ak"], sk=row["sk"], user_id=row["user_id"]))
    return pairs
```

- [ ] **Step 4: 实现 runner.py**

```python
# benchmark/core/runner.py
"""渐进式加压引擎 — 按梯度创建 worker 协程，收集指标，输出实时状态。"""

import asyncio
import os
import sys
import time
from datetime import datetime
from typing import Callable, Awaitable

from core.config import StageConfig
from core.metrics import MetricsCollector


WorkerFn = Callable[[int, MetricsCollector, asyncio.Event], Awaitable[None]]


class Runner:
    def __init__(
        self,
        stages: list[StageConfig],
        ramp_up_seconds: int = 5,
        report_dir: str = "./reports",
        report_formats: list[str] | None = None,
        scenario_name: str = "benchmark",
        target: str = "localhost",
    ):
        self.stages = stages
        self.ramp_up_seconds = ramp_up_seconds
        self.report_dir = report_dir
        self.report_formats = report_formats or ["json", "csv"]
        self.scenario_name = scenario_name
        self.target = target
        self.metrics = MetricsCollector()

    async def run(
        self,
        worker_fn: WorkerFn,
        setup_fn: Callable[[], Awaitable[None]] | None = None,
        teardown_fn: Callable[[], Awaitable[None]] | None = None,
    ):
        if setup_fn:
            await setup_fn()

        try:
            await self._run_stages(worker_fn)
        finally:
            if teardown_fn:
                await teardown_fn()
            self._save_reports()

    async def _run_stages(self, worker_fn: WorkerFn):
        total_stages = len(self.stages)
        active_workers: list[asyncio.Task] = []
        stop_event = asyncio.Event()

        for stage_idx, stage in enumerate(self.stages, 1):
            stop_event.clear()
            self.metrics.start_stage(stage_idx, stage.concurrency)

            # Ramp up: 创建新 worker 到目标并发数
            current_count = len(active_workers)
            target_count = stage.concurrency

            if target_count > current_count:
                new_count = target_count - current_count
                ramp_interval = (
                    self.ramp_up_seconds / new_count if new_count > 0 else 0
                )
                for i in range(new_count):
                    worker_id = current_count + i
                    task = asyncio.create_task(
                        worker_fn(worker_id, self.metrics, stop_event)
                    )
                    active_workers.append(task)
                    if ramp_interval > 0 and i < new_count - 1:
                        await asyncio.sleep(ramp_interval)
            elif target_count < current_count:
                # 缩减 worker（通常不会发生，梯度递增）
                excess = active_workers[target_count:]
                active_workers = active_workers[:target_count]
                for t in excess:
                    t.cancel()
                await asyncio.gather(*excess, return_exceptions=True)

            # 运行阶段持续时间，每秒输出状态
            deadline = time.monotonic() + stage.duration_sec
            while time.monotonic() < deadline:
                line = self.metrics.console_line(
                    stage_idx, total_stages, len(active_workers)
                )
                sys.stdout.write(f"\r{line}")
                sys.stdout.flush()
                await asyncio.sleep(1.0)

            result = self.metrics.finalize_stage()
            sys.stdout.write("\n")
            print(
                f"  Stage {stage_idx} done: "
                f"QPS={result['qps']} "
                f"P99={result['latency_ms']['p99']}ms "
                f"Errors={result['errors']}"
            )

        # 停止所有 worker
        stop_event.set()
        for t in active_workers:
            t.cancel()
        await asyncio.gather(*active_workers, return_exceptions=True)

    def _save_reports(self):
        os.makedirs(self.report_dir, exist_ok=True)
        ts = datetime.now().strftime("%Y%m%d_%H%M%S")
        base = f"{self.scenario_name}_{ts}"

        if "json" in self.report_formats:
            path = os.path.join(self.report_dir, f"{base}.json")
            self.metrics.save_json(path, self.scenario_name, self.target)
            print(f"JSON report: {path}")

        if "csv" in self.report_formats:
            path = os.path.join(self.report_dir, f"{base}.csv")
            self.metrics.save_csv(path)
            print(f"CSV report: {path}")
```

- [ ] **Step 5: 运行测试确认通过**

Run: `cd benchmark && python -m pytest tests/test_runner.py -v`
Expected: 3 passed

- [ ] **Step 6: Commit**

```bash
git add benchmark/core/config.py benchmark/core/runner.py benchmark/tests/test_runner.py
git commit -m "feat(benchmark): add config loader and progressive load runner engine"
```

---

### Task 6: mocks/mock_gw.py — Mock Gateway

**Files:**
- Create: `benchmark/mocks/mock_gw.py`

- [ ] **Step 1: 实现 MockGW**

MockGW 是 WebSocket Server，模拟 GW 的 `/ws/skill` 端点。SS 的 GatewayRelayService 会主动连过来。收到 invoke 后自动回复 tool_done。

```python
# benchmark/mocks/mock_gw.py
"""Mock Gateway — WebSocket Server，模拟 /ws/skill 端点。

SS 的 GatewayRelayService 会连接到此 Server。
收到 invoke 消息后自动回复 tool_done。
"""

import asyncio
import json
import logging
from typing import Callable, Awaitable

import websockets
from websockets.server import ServerConnection

from core.protocol import build_tool_done_message, parse_message

logger = logging.getLogger(__name__)


class MockGW:
    def __init__(
        self,
        host: str = "0.0.0.0",
        port: int = 8081,
        reply_delay_ms: int = 5,
        on_invoke: Callable[[dict], Awaitable[None]] | None = None,
    ):
        self.host = host
        self.port = port
        self.reply_delay_sec = reply_delay_ms / 1000.0
        self.on_invoke = on_invoke
        self._server = None
        self._connections: set[ServerConnection] = set()
        self._invoke_count = 0

    async def start(self):
        self._server = await websockets.serve(
            self._handler,
            self.host,
            self.port,
            subprotocols=["auth"],
            process_request=self._process_request,
        )
        logger.info(f"MockGW listening on ws://{self.host}:{self.port}/ws/skill")

    async def stop(self):
        if self._server:
            self._server.close()
            await self._server.wait_closed()

    @property
    def invoke_count(self) -> int:
        return self._invoke_count

    async def _process_request(self, connection, request):
        # 接受所有路径和认证，不做验证
        pass

    async def _handler(self, ws: ServerConnection):
        self._connections.add(ws)
        try:
            async for raw in ws:
                msg = parse_message(raw)
                msg_type = msg.get("type")

                if msg_type == "invoke":
                    self._invoke_count += 1
                    if self.on_invoke:
                        await self.on_invoke(msg)

                    if self.reply_delay_sec > 0:
                        await asyncio.sleep(self.reply_delay_sec)

                    ak = msg.get("ak", "")
                    payload = msg.get("payload", {})
                    tool_session_id = payload.get("toolSessionId", "")
                    welink_session_id = msg.get("welinkSessionId", "")
                    trace_id = msg.get("traceId", "")

                    reply = build_tool_done_message(
                        ak=ak,
                        tool_session_id=tool_session_id,
                        welink_session_id=welink_session_id,
                        trace_id=trace_id,
                    )
                    await ws.send(reply)

        except websockets.ConnectionClosed:
            pass
        finally:
            self._connections.discard(ws)
```

- [ ] **Step 2: 冒烟验证（手动）**

Run:
```bash
cd benchmark && python -c "
import asyncio
from mocks.mock_gw import MockGW
async def main():
    gw = MockGW(port=19081)
    await gw.start()
    print('MockGW started, press Ctrl+C to stop')
    await asyncio.sleep(2)
    await gw.stop()
    print('MockGW stopped')
asyncio.run(main())
"
```
Expected: 输出 "MockGW started" 和 "MockGW stopped"，无报错

- [ ] **Step 3: Commit**

```bash
git add benchmark/mocks/mock_gw.py
git commit -m "feat(benchmark): add MockGW WebSocket server for SS benchmarking"
```

---

### Task 7: mocks/mock_agent.py — Mock Agent

**Files:**
- Create: `benchmark/mocks/mock_agent.py`

- [ ] **Step 1: 实现 MockAgent**

MockAgent 是 WebSocket Client，连接 GW 的 `/ws/agent`，完成 AK/SK 认证 + register + 心跳，收到 invoke 后回复 tool_done。

```python
# benchmark/mocks/mock_agent.py
"""Mock Agent — 模拟 PCAgent 连接 GW。

完成 AK/SK 认证、register、心跳保活。
收到 invoke 后自动回复 tool_done。
"""

import asyncio
import json
import logging

import websockets

from core.auth import build_agent_auth_protocol
from core.protocol import (
    build_register_message,
    build_heartbeat_message,
    build_tool_done_message,
    parse_message,
)

logger = logging.getLogger(__name__)


class MockAgent:
    def __init__(
        self,
        gw_url: str,
        ak: str,
        sk: str,
        index: int = 0,
        heartbeat_interval: float = 30.0,
    ):
        self.gw_url = gw_url
        self.ak = ak
        self.sk = sk
        self.index = index
        self.heartbeat_interval = heartbeat_interval
        self._ws = None
        self._heartbeat_task: asyncio.Task | None = None
        self._listen_task: asyncio.Task | None = None
        self._registered = asyncio.Event()
        self._stopped = False

    async def connect_and_register(self):
        proto = build_agent_auth_protocol(self.ak, self.sk)
        self._ws = await websockets.connect(
            self.gw_url,
            subprotocols=[proto],
            open_timeout=10,
        )
        # 发送 register
        reg_msg = build_register_message(self.ak, self.index)
        await self._ws.send(reg_msg)

        # 启动监听和心跳
        self._listen_task = asyncio.create_task(self._listen_loop())
        self._heartbeat_task = asyncio.create_task(self._heartbeat_loop())
        self._registered.set()
        logger.debug(f"MockAgent {self.ak} registered")

    async def wait_registered(self, timeout: float = 10.0):
        await asyncio.wait_for(self._registered.wait(), timeout)

    async def close(self):
        self._stopped = True
        if self._heartbeat_task:
            self._heartbeat_task.cancel()
        if self._listen_task:
            self._listen_task.cancel()
        if self._ws:
            await self._ws.close()

    async def _listen_loop(self):
        try:
            async for raw in self._ws:
                msg = parse_message(raw)
                msg_type = msg.get("type")

                if msg_type == "invoke":
                    payload = msg.get("payload", {})
                    reply = build_tool_done_message(
                        ak=self.ak,
                        tool_session_id=payload.get("toolSessionId", ""),
                        welink_session_id=msg.get("welinkSessionId", ""),
                        trace_id=msg.get("traceId", ""),
                    )
                    await self._ws.send(reply)
                # register_ok, heartbeat_ack 等忽略
        except (websockets.ConnectionClosed, asyncio.CancelledError):
            pass

    async def _heartbeat_loop(self):
        try:
            while not self._stopped:
                await asyncio.sleep(self.heartbeat_interval)
                if self._ws and not self._ws.closed:
                    hb = build_heartbeat_message(self.ak)
                    await self._ws.send(hb)
        except (websockets.ConnectionClosed, asyncio.CancelledError):
            pass
```

- [ ] **Step 2: Commit**

```bash
git add benchmark/mocks/mock_agent.py
git commit -m "feat(benchmark): add MockAgent with AK/SK auth, register, heartbeat, and auto-reply"
```

---

### Task 8: mocks/mock_ss.py — Mock Skill Server

**Files:**
- Create: `benchmark/mocks/mock_ss.py`

- [ ] **Step 1: 实现 MockSS**

MockSS 模拟 SS 的 GatewayRelayService，建立 WebSocket 连接池到 GW 的 `/ws/skill`，发送 invoke 并等待 tool_done。

```python
# benchmark/mocks/mock_ss.py
"""Mock Skill Server — 模拟 SS 的 GatewayRelayService。

建立连接池到 GW 的 /ws/skill，发送 invoke 并等待 tool_done。
支持多会话复用连接池，每个会话独立跑同步循环。
"""

import asyncio
import json
import logging
from collections import defaultdict

import websockets

from core.auth import build_skill_auth_protocol
from core.protocol import build_invoke_message, parse_message

logger = logging.getLogger(__name__)


class MockSS:
    def __init__(
        self,
        gw_skill_url: str,
        internal_token: str,
        connection_count: int = 8,
        instance_id: str = "mock-ss-1",
    ):
        self.gw_skill_url = gw_skill_url
        self.internal_token = internal_token
        self.connection_count = connection_count
        self.instance_id = instance_id
        self._connections: list[websockets.ClientConnection] = []
        self._listen_tasks: list[asyncio.Task] = []
        # tool_session_id -> Future，用于同步等待 tool_done
        self._pending: dict[str, asyncio.Future] = {}
        self._lock = asyncio.Lock()
        self._round_robin = 0

    async def connect(self):
        proto = build_skill_auth_protocol(
            self.internal_token, "skill-server", self.instance_id
        )
        for i in range(self.connection_count):
            ws = await websockets.connect(
                self.gw_skill_url,
                subprotocols=[proto],
                open_timeout=10,
            )
            self._connections.append(ws)
            task = asyncio.create_task(self._listen_loop(ws))
            self._listen_tasks.append(task)
        logger.info(
            f"MockSS connected {self.connection_count} sessions to {self.gw_skill_url}"
        )

    async def close(self):
        for t in self._listen_tasks:
            t.cancel()
        for ws in self._connections:
            await ws.close()
        self._connections.clear()
        self._listen_tasks.clear()

    async def send_invoke_and_wait(
        self,
        ak: str,
        user_id: str,
        welink_session_id: str,
        tool_session_id: str,
        text: str = "benchmark",
        timeout: float = 30.0,
    ) -> dict:
        """发送 invoke 并同步等待 tool_done，返回 tool_done 消息。"""
        loop = asyncio.get_event_loop()
        fut = loop.create_future()

        async with self._lock:
            self._pending[tool_session_id] = fut

        msg = build_invoke_message(
            ak=ak,
            user_id=user_id,
            welink_session_id=welink_session_id,
            tool_session_id=tool_session_id,
            text=text,
            assistant_account="bench_bot",
            sender_account=f"bench_user_{user_id}",
        )

        # Round-robin 选择连接
        ws = self._connections[self._round_robin % len(self._connections)]
        self._round_robin += 1

        await ws.send(msg)

        try:
            result = await asyncio.wait_for(fut, timeout)
            return result
        finally:
            async with self._lock:
                self._pending.pop(tool_session_id, None)

    async def _listen_loop(self, ws: websockets.ClientConnection):
        try:
            async for raw in ws:
                msg = parse_message(raw)
                msg_type = msg.get("type")

                if msg_type == "tool_done":
                    tsid = msg.get("toolSessionId", "")
                    async with self._lock:
                        fut = self._pending.get(tsid)
                    if fut and not fut.done():
                        fut.set_result(msg)
        except (websockets.ConnectionClosed, asyncio.CancelledError):
            pass
```

- [ ] **Step 2: Commit**

```bash
git add benchmark/mocks/mock_ss.py
git commit -m "feat(benchmark): add MockSS with connection pool and sync invoke-wait pattern"
```

---

### Task 9: mocks/mock_miniapp.py — Mock MiniApp Client

**Files:**
- Create: `benchmark/mocks/mock_miniapp.py`

- [ ] **Step 1: 实现 MockMiniAppClient**

MockMiniApp 连接 SS 的 `/ws/skill/stream`，接收推送消息，识别 tool_done 后通知同步循环。

```python
# benchmark/mocks/mock_miniapp.py
"""Mock MiniApp Client — 模拟前端 MiniApp 连接 SS。

连接 /ws/skill/stream，接收推送，识别 step.done/tool_done 类型消息后
通知对应会话的同步循环可以继续。
"""

import asyncio
import json
import logging
import time

import websockets

from core.auth import build_miniapp_headers
from core.protocol import parse_message

logger = logging.getLogger(__name__)


class MockMiniAppClient:
    def __init__(self, ss_ws_url: str, user_id: str):
        self.ss_ws_url = ss_ws_url
        self.user_id = user_id
        self._ws = None
        self._listen_task: asyncio.Task | None = None
        # welink_session_id -> Future，用于同步等待 tool_done 推送
        self._pending: dict[str, asyncio.Future] = {}
        self._lock = asyncio.Lock()
        self._connected = asyncio.Event()

    async def connect(self):
        headers = build_miniapp_headers(self.user_id)
        self._ws = await websockets.connect(
            self.ss_ws_url,
            additional_headers=headers,
            open_timeout=10,
        )
        self._listen_task = asyncio.create_task(self._listen_loop())
        self._connected.set()

    async def close(self):
        if self._listen_task:
            self._listen_task.cancel()
        if self._ws:
            await self._ws.close()

    async def wait_tool_done(
        self, welink_session_id: str, timeout: float = 30.0
    ) -> float:
        """等待 SS 推送 tool_done 消息，返回收到时的时间戳。"""
        loop = asyncio.get_event_loop()
        fut = loop.create_future()

        async with self._lock:
            self._pending[welink_session_id] = fut

        try:
            recv_ts = await asyncio.wait_for(fut, timeout)
            return recv_ts
        finally:
            async with self._lock:
                self._pending.pop(welink_session_id, None)

    async def _listen_loop(self):
        try:
            async for raw in self._ws:
                msg = parse_message(raw)
                msg_type = msg.get("type", "")

                # SS 推送的 tool_done 可能是 "step.done" 或 "tool_done" 类型
                if msg_type in ("step.done", "tool_done", "text.done"):
                    recv_ts = time.monotonic()
                    wsid = msg.get("welinkSessionId", "")
                    async with self._lock:
                        fut = self._pending.get(wsid)
                    if fut and not fut.done():
                        fut.set_result(recv_ts)
        except (websockets.ConnectionClosed, asyncio.CancelledError):
            pass
```

- [ ] **Step 2: Commit**

```bash
git add benchmark/mocks/mock_miniapp.py
git commit -m "feat(benchmark): add MockMiniAppClient for receiving SS WebSocket pushes"
```

---

### Task 10: scenarios/ss_bench.py — SS 压测场景

**Files:**
- Create: `benchmark/scenarios/ss_bench.py`

- [ ] **Step 1: 实现 SS 连接容量测试**

```python
# benchmark/scenarios/ss_bench.py
"""SS 压测场景 — 连接容量 + 吞吐量测试。

连接容量：渐进创建 MockMiniAppClient 连接。
吞吐量：并发 client 跑同步循环 (POST → MockGW 回复 → MiniApp 收到推送)。
"""

import asyncio
import logging
import time
import sys

import aiohttp

from core.config import BenchConfig, load_credentials, AkSkPair
from core.metrics import MetricsCollector
from core.protocol import build_inbound_request_body
from core.runner import Runner
from mocks.mock_gw import MockGW
from mocks.mock_miniapp import MockMiniAppClient

logger = logging.getLogger(__name__)


async def run_connection_test(cfg: BenchConfig):
    """SS 连接容量测试：渐进创建 MiniApp WebSocket 连接。"""
    mock_gw = MockGW(
        host=cfg.mock_gw.host,
        port=cfg.mock_gw.port,
        reply_delay_ms=cfg.mock_gw.reply_delay_ms,
    )
    await mock_gw.start()
    print(f"MockGW started on port {cfg.mock_gw.port}")

    clients: list[MockMiniAppClient] = []
    metrics = MetricsCollector()

    try:
        total_stages = len(cfg.stages)
        for stage_idx, stage in enumerate(cfg.stages, 1):
            metrics.start_stage(stage_idx, stage.concurrency)
            target = stage.concurrency
            current = len(clients)

            print(f"\nStage {stage_idx}/{total_stages}: "
                  f"connecting {target - current} new clients "
                  f"(total target: {target})...")

            # 渐进创建连接
            for i in range(current, target):
                user_id = f"conn_test_user_{i}"
                client = MockMiniAppClient(cfg.ss.ws_url, user_id)
                start = time.monotonic()
                try:
                    await client.connect()
                    latency = (time.monotonic() - start) * 1000
                    metrics.record_latency(latency)
                    clients.append(client)
                except Exception as e:
                    metrics.record_error()
                    logger.warning(f"Connection {i} failed: {e}")

                if (i - current + 1) % 100 == 0:
                    line = metrics.console_line(
                        stage_idx, total_stages, len(clients)
                    )
                    sys.stdout.write(f"\r{line}")
                    sys.stdout.flush()

            # 保持连接一段时间
            deadline = time.monotonic() + stage.duration_sec
            while time.monotonic() < deadline:
                line = metrics.console_line(
                    stage_idx, total_stages, len(clients)
                )
                sys.stdout.write(f"\r{line}")
                sys.stdout.flush()
                await asyncio.sleep(1.0)

            result = metrics.finalize_stage()
            sys.stdout.write("\n")
            print(
                f"  Stage {stage_idx}: "
                f"connected={len(clients)} "
                f"connect_p99={result['latency_ms']['p99']}ms "
                f"errors={result['errors']}"
            )
    finally:
        print(f"\nClosing {len(clients)} connections...")
        await asyncio.gather(
            *[c.close() for c in clients], return_exceptions=True
        )
        await mock_gw.stop()

    # 保存报告
    import os
    from datetime import datetime

    os.makedirs(cfg.reports.dir, exist_ok=True)
    ts = datetime.now().strftime("%Y%m%d_%H%M%S")
    if "json" in cfg.reports.format:
        path = os.path.join(cfg.reports.dir, f"ss_connection_{ts}.json")
        metrics.save_json(path, "ss_connection", cfg.ss.ws_url)
        print(f"JSON report: {path}")
    if "csv" in cfg.reports.format:
        path = os.path.join(cfg.reports.dir, f"ss_connection_{ts}.csv")
        metrics.save_csv(path)
        print(f"CSV report: {path}")


async def run_throughput_test(cfg: BenchConfig):
    """SS 吞吐量测试：并发 client 跑同步循环。"""
    # 1. 启动 MockGW
    mock_gw = MockGW(
        host=cfg.mock_gw.host,
        port=cfg.mock_gw.port,
        reply_delay_ms=cfg.mock_gw.reply_delay_ms,
    )
    await mock_gw.start()
    print(f"MockGW started on port {cfg.mock_gw.port}")

    # 2. 预创建 MiniApp client 池（按最大并发数）
    max_concurrency = max(s.concurrency for s in cfg.stages)
    miniapp_clients: list[MockMiniAppClient] = []

    # 共享 HTTP session
    http_session = aiohttp.ClientSession()

    async def setup():
        print(f"Pre-connecting {max_concurrency} MiniApp clients...")
        for i in range(max_concurrency):
            user_id = f"bench_user_{i}"
            client = MockMiniAppClient(cfg.ss.ws_url, user_id)
            try:
                await client.connect()
                miniapp_clients.append(client)
            except Exception as e:
                logger.warning(f"MiniApp client {i} connect failed: {e}")
                miniapp_clients.append(None)

            if (i + 1) % 500 == 0:
                print(f"  Connected {i + 1}/{max_concurrency}")
        print(f"MiniApp clients ready: {sum(1 for c in miniapp_clients if c)}")

    async def teardown():
        await http_session.close()
        await asyncio.gather(
            *[c.close() for c in miniapp_clients if c],
            return_exceptions=True,
        )
        await mock_gw.stop()

    async def worker(
        worker_id: int,
        metrics: MetricsCollector,
        stop_event: asyncio.Event,
    ):
        client = miniapp_clients[worker_id] if worker_id < len(miniapp_clients) else None
        if client is None:
            return

        session_id = f"bench_session_{worker_id}"
        welink_session_id = session_id
        user_id = f"bench_user_{worker_id}"
        url = f"{cfg.ss.http_url}/api/inbound/messages"

        body = build_inbound_request_body(
            session_id=session_id,
            assistant_account="bench_bot",
            sender_account=user_id,
        )

        while not stop_event.is_set():
            start = time.monotonic()
            try:
                # 1. POST 请求
                async with http_session.post(url, json=body) as resp:
                    if resp.status != 200:
                        metrics.record_error()
                        await asyncio.sleep(0.1)
                        continue

                # 2. 等待 MiniApp 收到 tool_done 推送
                await client.wait_tool_done(welink_session_id, timeout=30.0)

                latency = (time.monotonic() - start) * 1000
                metrics.record_latency(latency)

            except asyncio.TimeoutError:
                metrics.record_error()
            except Exception as e:
                metrics.record_error()
                logger.debug(f"Worker {worker_id} error: {e}")
                await asyncio.sleep(0.1)

    runner = Runner(
        stages=cfg.stages,
        ramp_up_seconds=cfg.ramp_up_seconds,
        report_dir=cfg.reports.dir,
        report_formats=cfg.reports.format,
        scenario_name="ss_throughput",
        target=cfg.ss.http_url,
    )
    await runner.run(worker, setup_fn=setup, teardown_fn=teardown)
```

- [ ] **Step 2: Commit**

```bash
git add benchmark/scenarios/ss_bench.py
git commit -m "feat(benchmark): add SS benchmark scenarios (connection + throughput)"
```

---

### Task 11: scenarios/gw_bench.py — GW 压测场景

**Files:**
- Create: `benchmark/scenarios/gw_bench.py`

- [ ] **Step 1: 实现 GW 压测场景**

```python
# benchmark/scenarios/gw_bench.py
"""GW 压测场景 — 连接容量 + 吞吐量测试。

连接容量：渐进创建 MockAgent 连接（AK/SK 认证 + register）。
吞吐量：MockSS 并发发送 invoke，MockAgent 自动回复，测同步往返。
"""

import asyncio
import logging
import time
import sys

from core.config import BenchConfig, AkSkPair, load_credentials
from core.metrics import MetricsCollector
from core.runner import Runner
from mocks.mock_agent import MockAgent
from mocks.mock_ss import MockSS

logger = logging.getLogger(__name__)


async def run_connection_test(cfg: BenchConfig, credentials: list[AkSkPair]):
    """GW 连接容量测试：渐进创建 MockAgent 连接。"""
    agents: list[MockAgent] = []
    metrics = MetricsCollector()
    total_stages = len(cfg.stages)

    try:
        for stage_idx, stage in enumerate(cfg.stages, 1):
            metrics.start_stage(stage_idx, stage.concurrency)
            target = min(stage.concurrency, len(credentials))
            current = len(agents)

            if target <= current:
                print(f"\nStage {stage_idx}: already at {current} agents "
                      f"(credentials exhausted)")
                await asyncio.sleep(stage.duration_sec)
                metrics.finalize_stage()
                continue

            print(f"\nStage {stage_idx}/{total_stages}: "
                  f"connecting {target - current} new agents "
                  f"(total target: {target})...")

            for i in range(current, target):
                cred = credentials[i]
                agent = MockAgent(
                    gw_url=cfg.gw.ws_agent_url,
                    ak=cred.ak,
                    sk=cred.sk,
                    index=i,
                )
                start = time.monotonic()
                try:
                    await agent.connect_and_register()
                    latency = (time.monotonic() - start) * 1000
                    metrics.record_latency(latency)
                    agents.append(agent)
                except Exception as e:
                    metrics.record_error()
                    logger.warning(f"Agent {i} ({cred.ak}) connect failed: {e}")

                if (i - current + 1) % 100 == 0:
                    line = metrics.console_line(
                        stage_idx, total_stages, len(agents)
                    )
                    sys.stdout.write(f"\r{line}")
                    sys.stdout.flush()

            # 保持连接
            deadline = time.monotonic() + stage.duration_sec
            while time.monotonic() < deadline:
                line = metrics.console_line(
                    stage_idx, total_stages, len(agents)
                )
                sys.stdout.write(f"\r{line}")
                sys.stdout.flush()
                await asyncio.sleep(1.0)

            result = metrics.finalize_stage()
            sys.stdout.write("\n")
            print(
                f"  Stage {stage_idx}: "
                f"agents={len(agents)} "
                f"auth_p99={result['latency_ms']['p99']}ms "
                f"errors={result['errors']}"
            )
    finally:
        print(f"\nClosing {len(agents)} agents...")
        await asyncio.gather(
            *[a.close() for a in agents], return_exceptions=True
        )

    # 保存报告
    import os
    from datetime import datetime

    os.makedirs(cfg.reports.dir, exist_ok=True)
    ts = datetime.now().strftime("%Y%m%d_%H%M%S")
    if "json" in cfg.reports.format:
        path = os.path.join(cfg.reports.dir, f"gw_connection_{ts}.json")
        metrics.save_json(path, "gw_connection", cfg.gw.ws_agent_url)
        print(f"JSON report: {path}")
    if "csv" in cfg.reports.format:
        path = os.path.join(cfg.reports.dir, f"gw_connection_{ts}.csv")
        metrics.save_csv(path)
        print(f"CSV report: {path}")


async def run_throughput_test(cfg: BenchConfig, credentials: list[AkSkPair]):
    """GW 吞吐量测试：MockSS 并发 invoke，MockAgent 自动回复。"""
    max_concurrency = max(s.concurrency for s in cfg.stages)
    # Agent 数量 = credential 数量（每个 AK 一个 Agent）
    agent_count = min(max_concurrency, len(credentials))

    agents: list[MockAgent] = []
    mock_ss: MockSS | None = None

    async def setup():
        nonlocal mock_ss

        # 1. 注册 MockAgent
        print(f"Registering {agent_count} MockAgents...")
        for i in range(agent_count):
            cred = credentials[i]
            agent = MockAgent(
                gw_url=cfg.gw.ws_agent_url,
                ak=cred.ak,
                sk=cred.sk,
                index=i,
            )
            try:
                await agent.connect_and_register()
                agents.append(agent)
            except Exception as e:
                logger.warning(f"Agent {i} register failed: {e}")

            if (i + 1) % 500 == 0:
                print(f"  Registered {i + 1}/{agent_count}")
        print(f"MockAgents ready: {len(agents)}")

        # 2. 连接 MockSS
        mock_ss = MockSS(
            gw_skill_url=cfg.gw.ws_skill_url,
            internal_token=cfg.gw.internal_token,
            connection_count=cfg.mock_ss.connection_count,
        )
        await mock_ss.connect()
        print(f"MockSS connected ({cfg.mock_ss.connection_count} connections)")

    async def teardown():
        if mock_ss:
            await mock_ss.close()
        await asyncio.gather(
            *[a.close() for a in agents], return_exceptions=True
        )

    async def worker(
        worker_id: int,
        metrics: MetricsCollector,
        stop_event: asyncio.Event,
    ):
        # 每个 worker 对应一个会话，使用对应的 agent
        agent_idx = worker_id % len(agents)
        cred = credentials[agent_idx]
        tool_session_id = f"gw_bench_ts_{worker_id}"
        welink_session_id = f"gw_bench_ws_{worker_id}"

        while not stop_event.is_set():
            start = time.monotonic()
            try:
                await mock_ss.send_invoke_and_wait(
                    ak=cred.ak,
                    user_id=cred.user_id,
                    welink_session_id=welink_session_id,
                    tool_session_id=tool_session_id,
                    timeout=30.0,
                )
                latency = (time.monotonic() - start) * 1000
                metrics.record_latency(latency)
            except asyncio.TimeoutError:
                metrics.record_error()
            except Exception as e:
                metrics.record_error()
                logger.debug(f"GW worker {worker_id} error: {e}")
                await asyncio.sleep(0.1)

    runner = Runner(
        stages=cfg.stages,
        ramp_up_seconds=cfg.ramp_up_seconds,
        report_dir=cfg.reports.dir,
        report_formats=cfg.reports.format,
        scenario_name="gw_throughput",
        target=cfg.gw.ws_skill_url,
    )
    await runner.run(worker, setup_fn=setup, teardown_fn=teardown)
```

- [ ] **Step 2: Commit**

```bash
git add benchmark/scenarios/gw_bench.py
git commit -m "feat(benchmark): add GW benchmark scenarios (connection + throughput)"
```

---

### Task 12: scenarios/e2e_bench.py — 端到端压测

**Files:**
- Create: `benchmark/scenarios/e2e_bench.py`

- [ ] **Step 1: 实现端到端压测**

```python
# benchmark/scenarios/e2e_bench.py
"""端到端压测 — 真实 SS + GW，Mock 两端。

流程：POST /api/inbound/messages → SS → GW → MockAgent
     MockAgent 回 tool_done → GW → SS → MockMiniAppClient
"""

import asyncio
import logging
import time

import aiohttp

from core.config import BenchConfig, AkSkPair
from core.metrics import MetricsCollector
from core.protocol import build_inbound_request_body
from core.runner import Runner
from mocks.mock_agent import MockAgent
from mocks.mock_miniapp import MockMiniAppClient

logger = logging.getLogger(__name__)


async def run_e2e_test(cfg: BenchConfig, credentials: list[AkSkPair]):
    """端到端压测：真实 SS + GW，Mock Agent 和 MiniApp 两端。"""
    max_concurrency = max(s.concurrency for s in cfg.stages)
    agent_count = min(max_concurrency, len(credentials))

    agents: list[MockAgent] = []
    miniapp_clients: list[MockMiniAppClient] = []
    http_session: aiohttp.ClientSession | None = None

    async def setup():
        nonlocal http_session
        http_session = aiohttp.ClientSession()

        # 1. 注册 MockAgent
        print(f"Registering {agent_count} MockAgents to GW...")
        for i in range(agent_count):
            cred = credentials[i]
            agent = MockAgent(
                gw_url=cfg.gw.ws_agent_url,
                ak=cred.ak,
                sk=cred.sk,
                index=i,
            )
            try:
                await agent.connect_and_register()
                agents.append(agent)
            except Exception as e:
                logger.warning(f"Agent {i} register failed: {e}")

            if (i + 1) % 500 == 0:
                print(f"  Registered {i + 1}/{agent_count}")
        print(f"MockAgents ready: {len(agents)}")

        # 2. 连接 MiniApp clients
        print(f"Connecting {max_concurrency} MiniApp clients to SS...")
        for i in range(max_concurrency):
            user_id = f"e2e_user_{i}"
            client = MockMiniAppClient(cfg.ss.ws_url, user_id)
            try:
                await client.connect()
                miniapp_clients.append(client)
            except Exception as e:
                logger.warning(f"MiniApp client {i} connect failed: {e}")
                miniapp_clients.append(None)

            if (i + 1) % 500 == 0:
                print(f"  Connected {i + 1}/{max_concurrency}")
        print(
            f"MiniApp clients ready: "
            f"{sum(1 for c in miniapp_clients if c)}"
        )

    async def teardown():
        if http_session:
            await http_session.close()
        await asyncio.gather(
            *[c.close() for c in miniapp_clients if c],
            return_exceptions=True,
        )
        await asyncio.gather(
            *[a.close() for a in agents], return_exceptions=True
        )

    async def worker(
        worker_id: int,
        metrics: MetricsCollector,
        stop_event: asyncio.Event,
    ):
        client = (
            miniapp_clients[worker_id]
            if worker_id < len(miniapp_clients)
            else None
        )
        if client is None:
            return

        agent_idx = worker_id % len(agents)
        cred = credentials[agent_idx]
        session_id = f"e2e_session_{worker_id}"
        user_id = f"e2e_user_{worker_id}"
        url = f"{cfg.ss.http_url}/api/inbound/messages"

        body = build_inbound_request_body(
            session_id=session_id,
            assistant_account="bench_bot",
            sender_account=user_id,
        )

        while not stop_event.is_set():
            start = time.monotonic()
            try:
                async with http_session.post(url, json=body) as resp:
                    if resp.status != 200:
                        metrics.record_error()
                        await asyncio.sleep(0.1)
                        continue

                await client.wait_tool_done(session_id, timeout=30.0)
                latency = (time.monotonic() - start) * 1000
                metrics.record_latency(latency)

            except asyncio.TimeoutError:
                metrics.record_error()
            except Exception as e:
                metrics.record_error()
                logger.debug(f"E2E worker {worker_id} error: {e}")
                await asyncio.sleep(0.1)

    runner = Runner(
        stages=cfg.stages,
        ramp_up_seconds=cfg.ramp_up_seconds,
        report_dir=cfg.reports.dir,
        report_formats=cfg.reports.format,
        scenario_name="e2e",
        target=f"{cfg.ss.http_url} -> {cfg.gw.ws_agent_url}",
    )
    await runner.run(worker, setup_fn=setup, teardown_fn=teardown)
```

- [ ] **Step 2: Commit**

```bash
git add benchmark/scenarios/e2e_bench.py
git commit -m "feat(benchmark): add end-to-end benchmark scenario"
```

---

### Task 13: run.py — CLI 入口

**Files:**
- Create: `benchmark/run.py`

- [ ] **Step 1: 实现 CLI 入口**

```python
#!/usr/bin/env python
# benchmark/run.py
"""压测工具 CLI 入口。

用法:
  python run.py ss --sub throughput --config config/default.yaml
  python run.py ss --sub connection --config config/default.yaml
  python run.py gw --sub throughput --config config/default.yaml --credentials config/ak_sk_pairs.csv
  python run.py gw --sub connection --config config/default.yaml --credentials config/ak_sk_pairs.csv
  python run.py e2e --config config/default.yaml --credentials config/ak_sk_pairs.csv
"""

import argparse
import asyncio
import logging
import sys
import os

# 将 benchmark 目录加入 sys.path
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from core.config import load_config, load_credentials


def main():
    parser = argparse.ArgumentParser(description="MiniApp Benchmark Tool")
    parser.add_argument(
        "target",
        choices=["ss", "gw", "e2e"],
        help="压测目标: ss=Skill Server, gw=Gateway, e2e=端到端",
    )
    parser.add_argument(
        "--sub",
        choices=["throughput", "connection"],
        default="throughput",
        help="子场景: throughput=吞吐量, connection=连接容量 (e2e 忽略此参数)",
    )
    parser.add_argument(
        "--config",
        default="config/default.yaml",
        help="配置文件路径",
    )
    parser.add_argument(
        "--credentials",
        default="config/ak_sk_pairs.csv",
        help="AK/SK 凭证文件路径 (gw/e2e 必需)",
    )
    parser.add_argument(
        "--log-level",
        default="INFO",
        choices=["DEBUG", "INFO", "WARNING", "ERROR"],
        help="日志级别",
    )

    args = parser.parse_args()

    logging.basicConfig(
        level=getattr(logging, args.log_level),
        format="%(asctime)s %(levelname)s %(name)s: %(message)s",
    )

    cfg = load_config(args.config)
    print(f"Config loaded from {args.config}")
    print(f"Stages: {[s.concurrency for s in cfg.stages]}")

    if args.target == "ss":
        from scenarios.ss_bench import run_connection_test, run_throughput_test

        if args.sub == "connection":
            print("\n=== SS Connection Capacity Test ===\n")
            asyncio.run(run_connection_test(cfg))
        else:
            print("\n=== SS Throughput Test ===\n")
            asyncio.run(run_throughput_test(cfg))

    elif args.target == "gw":
        credentials = load_credentials(args.credentials)
        print(f"Loaded {len(credentials)} AK/SK pairs")

        from scenarios.gw_bench import run_connection_test, run_throughput_test

        if args.sub == "connection":
            print("\n=== GW Connection Capacity Test ===\n")
            asyncio.run(run_connection_test(cfg, credentials))
        else:
            print("\n=== GW Throughput Test ===\n")
            asyncio.run(run_throughput_test(cfg, credentials))

    elif args.target == "e2e":
        credentials = load_credentials(args.credentials)
        print(f"Loaded {len(credentials)} AK/SK pairs")

        from scenarios.e2e_bench import run_e2e_test

        print("\n=== End-to-End Benchmark Test ===\n")
        asyncio.run(run_e2e_test(cfg, credentials))


if __name__ == "__main__":
    main()
```

- [ ] **Step 2: 验证 CLI 帮助信息**

Run: `cd benchmark && python run.py --help`
Expected:
```
usage: run.py [-h] [--sub {throughput,connection}] [--config CONFIG]
              [--credentials CREDENTIALS] [--log-level {DEBUG,INFO,WARNING,ERROR}]
              {ss,gw,e2e}
```

- [ ] **Step 3: Commit**

```bash
git add benchmark/run.py
git commit -m "feat(benchmark): add CLI entry point with ss/gw/e2e subcommands"
```

---

### Task 14: 集成冒烟测试

**Files:**
- Create: `benchmark/tests/test_integration.py`

- [ ] **Step 1: 编写 MockGW + MockSS 集成测试**

验证 MockSS 发 invoke 到 MockGW，MockGW 自动回复 tool_done，MockSS 收到回复。

```python
# benchmark/tests/test_integration.py
"""集成测试 — 验证 Mock 组件间的通信。"""

import asyncio
import pytest
from mocks.mock_gw import MockGW
from mocks.mock_ss import MockSS


@pytest.mark.asyncio
async def test_mock_ss_invoke_via_mock_gw():
    """MockSS → MockGW invoke → tool_done 往返。"""
    gw = MockGW(host="127.0.0.1", port=19081, reply_delay_ms=0)
    await gw.start()

    ss = MockSS(
        gw_skill_url="ws://127.0.0.1:19081/ws/skill",
        internal_token="test-token",
        connection_count=1,
    )
    await ss.connect()

    try:
        result = await ss.send_invoke_and_wait(
            ak="test_ak",
            user_id="test_user",
            welink_session_id="ws_1",
            tool_session_id="ts_1",
            timeout=5.0,
        )
        assert result["type"] == "tool_done"
        assert result["toolSessionId"] == "ts_1"
        assert gw.invoke_count == 1
    finally:
        await ss.close()
        await gw.stop()


@pytest.mark.asyncio
async def test_mock_ss_concurrent_invokes():
    """并发多个 invoke，验证各自独立收到 tool_done。"""
    gw = MockGW(host="127.0.0.1", port=19082, reply_delay_ms=0)
    await gw.start()

    ss = MockSS(
        gw_skill_url="ws://127.0.0.1:19082/ws/skill",
        internal_token="test-token",
        connection_count=2,
    )
    await ss.connect()

    try:
        tasks = [
            ss.send_invoke_and_wait(
                ak=f"ak_{i}",
                user_id=f"user_{i}",
                welink_session_id=f"ws_{i}",
                tool_session_id=f"ts_{i}",
                timeout=5.0,
            )
            for i in range(10)
        ]
        results = await asyncio.gather(*tasks)
        assert len(results) == 10
        for i, r in enumerate(results):
            assert r["type"] == "tool_done"
            assert r["toolSessionId"] == f"ts_{i}"
    finally:
        await ss.close()
        await gw.stop()
```

- [ ] **Step 2: 安装 pytest-asyncio**

Run: `cd benchmark && pip install pytest-asyncio`

在 `requirements.txt` 末尾追加:
```
pytest>=8.0
pytest-asyncio>=0.23
```

- [ ] **Step 3: 运行集成测试**

Run: `cd benchmark && python -m pytest tests/test_integration.py -v`
Expected: 2 passed

- [ ] **Step 4: Commit**

```bash
git add benchmark/tests/test_integration.py benchmark/requirements.txt
git commit -m "test(benchmark): add integration tests for MockSS-MockGW communication"
```

---

### Task 15: 全量测试 + 最终验证

- [ ] **Step 1: 运行全部测试**

Run: `cd benchmark && python -m pytest tests/ -v`
Expected: 所有测试通过

- [ ] **Step 2: 验证 CLI 各子命令可正常解析**

Run:
```bash
cd benchmark
python run.py ss --sub throughput --config config/default.yaml --help || true
python run.py gw --sub connection --config config/default.yaml --credentials config/ak_sk_pairs.csv --help || true
python run.py e2e --config config/default.yaml --credentials config/ak_sk_pairs.csv --help || true
```
Expected: 各命令解析无报错

- [ ] **Step 3: Commit**

```bash
git add -A benchmark/
git commit -m "feat(benchmark): complete MiniApp benchmark tool with all scenarios and tests"
```
