# E2E Performance Benchmark Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a Python asyncio-based end-to-end performance benchmark tool that measures TPS, concurrent connections, and latency distribution for Skill Server and AI Gateway.

**Architecture:** Single Python package (`bench/`) with 1:1 Agent+Client pairs. Mock Agents connect to GW via WebSocket, Clients send messages via SS REST API and receive responses via SS WebSocket stream. A coordinator orchestrates the full lifecycle and collects metrics in real-time.

**Tech Stack:** Python 3.10+, asyncio, aiohttp, websockets

---

## File Structure

```
bench/
├── __init__.py              # Package init
├── __main__.py              # CLI entry: python -m bench
├── config.py                # Configuration (CLI > env > defaults)
├── auth.py                  # AK/SK HMAC-SHA256 signing (reuse tests/utils/auth.py)
├── mock_agent.py            # Mock/Echo Agent WebSocket client
├── client.py                # Virtual user (HTTP + WS)
├── metrics.py               # Real-time metrics collection
├── report.py                # Summary report generation (terminal + JSON/CSV)
├── gen_credentials.py       # Credential CSV + SQL generator
└── credentials.csv          # Example credentials file
```

---

### Task 1: Project Skeleton + Config

**Files:**
- Create: `bench/__init__.py`
- Create: `bench/__main__.py`
- Create: `bench/config.py`
- Create: `bench/requirements.txt`

- [ ] **Step 1: Create `bench/__init__.py`**

```python
"""E2E performance benchmark for Skill Server and AI Gateway."""
```

- [ ] **Step 2: Create `bench/config.py`**

```python
"""Configuration management: CLI > environment variables > defaults."""

import argparse
import os
from dataclasses import dataclass


@dataclass
class BenchConfig:
    mode: str = "mock"
    pairs: int = 10
    duration: int = 60
    echo_delay: int = 500
    ramp_up: int = 0
    timeout: int = 30
    ss_url: str = "http://localhost:8082"
    gw_url: str = "ws://localhost:8081"
    credentials: str = "./bench/credentials.csv"
    output_dir: str = "./bench_results"
    internal_token: str = "changeme"


def parse_args() -> BenchConfig:
    parser = argparse.ArgumentParser(
        description="E2E performance benchmark for SS and GW"
    )
    parser.add_argument(
        "--mode", choices=["mock", "echo"], default=None,
        help="Agent response mode: mock (instant) or echo (delayed)"
    )
    parser.add_argument("--pairs", type=int, default=None, help="Number of Agent+Client pairs")
    parser.add_argument("--duration", type=int, default=None, help="Test duration in seconds")
    parser.add_argument("--echo-delay", type=int, default=None, help="Echo mode delay in ms")
    parser.add_argument("--ramp-up", type=int, default=None, help="Pairs added per second, 0=all at once")
    parser.add_argument("--timeout", type=int, default=None, help="Per-message response timeout in seconds")
    parser.add_argument("--ss-url", default=None, help="Skill Server base URL")
    parser.add_argument("--gw-url", default=None, help="AI Gateway WebSocket base URL")
    parser.add_argument("--credentials", default=None, help="Path to credentials CSV file")
    parser.add_argument("--output-dir", default=None, help="Report output directory")
    parser.add_argument("--internal-token", default=None, help="SS-GW internal auth token")

    args = parser.parse_args()
    config = BenchConfig()

    # Priority: CLI > env > default
    env_map = {
        "ss_url": "BENCH_SS_URL",
        "gw_url": "BENCH_GW_URL",
        "internal_token": "BENCH_INTERNAL_TOKEN",
    }

    for field_name in config.__dataclass_fields__:
        cli_val = getattr(args, field_name.replace("-", "_"), None) if hasattr(args, field_name.replace("-", "_")) else None
        # argparse uses underscores
        arg_name = field_name
        cli_val = getattr(args, arg_name, None)
        if cli_val is not None:
            setattr(config, field_name, cli_val)
        elif field_name in env_map and os.environ.get(env_map[field_name]):
            env_val = os.environ[env_map[field_name]]
            field_type = type(getattr(config, field_name))
            setattr(config, field_name, field_type(env_val))

    return config
```

- [ ] **Step 3: Create `bench/__main__.py` (minimal skeleton)**

```python
"""CLI entry point: python -m bench"""

import asyncio
import sys

from .config import parse_args


async def main():
    config = parse_args()
    print(f"Benchmark config: {config}")
    print("Benchmark not yet implemented.")


def entry():
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        print("\nBenchmark interrupted.")
        sys.exit(0)


if __name__ == "__main__":
    entry()
```

- [ ] **Step 4: Create `bench/requirements.txt`**

```
aiohttp>=3.9
websockets>=12.0
```

- [ ] **Step 5: Verify skeleton runs**

Run: `cd D:\02_Lab\Projects\sandbox\opencode-CUI && python -m bench --help`

Expected: argparse help output showing all parameters.

Run: `python -m bench --mode mock --pairs 5`

Expected: prints config and "not yet implemented" message.

- [ ] **Step 6: Commit**

```bash
git add bench/__init__.py bench/__main__.py bench/config.py bench/requirements.txt
git commit -m "feat(bench): add project skeleton and CLI config"
```

---

### Task 2: Auth Module

**Files:**
- Create: `bench/auth.py`

The project already has `tests/utils/auth.py` with a working implementation. We reuse the same logic.

- [ ] **Step 1: Create `bench/auth.py`**

```python
"""
AK/SK HMAC-SHA256 signing for WebSocket handshake.

Replicates the algorithm in ai-gateway's AkSkAuthService:
  sign = Base64(HMAC-SHA256(SK, AK + timestamp + nonce))
  subprotocol = "auth." + Base64URL(JSON({ak, ts, nonce, sign}))
"""

import base64
import hashlib
import hmac
import json
import time
import uuid


def generate_nonce() -> str:
    return uuid.uuid4().hex


def current_timestamp() -> str:
    return str(int(time.time()))


def compute_signature(ak: str, sk: str, timestamp: str, nonce: str) -> str:
    message = (ak + timestamp + nonce).encode("utf-8")
    key = sk.encode("utf-8")
    mac = hmac.new(key, message, hashlib.sha256)
    return base64.b64encode(mac.digest()).decode("utf-8")


def build_auth_subprotocol(ak: str, sk: str, timestamp: str = None,
                           nonce: str = None) -> str:
    if timestamp is None:
        timestamp = current_timestamp()
    if nonce is None:
        nonce = generate_nonce()

    signature = compute_signature(ak, sk, timestamp, nonce)
    payload = {
        "ak": ak,
        "ts": timestamp,
        "nonce": nonce,
        "sign": signature,
    }
    json_bytes = json.dumps(payload).encode("utf-8")
    encoded = base64.urlsafe_b64encode(json_bytes).decode("utf-8").rstrip("=")
    return f"auth.{encoded}"
```

- [ ] **Step 2: Verify auth module imports**

Run: `python -c "from bench.auth import build_auth_subprotocol; print(build_auth_subprotocol('test-ak', 'test-sk'))"`

Expected: prints `auth.eyJhayI6InRlc3QtYWsi...` (base64url encoded string).

- [ ] **Step 3: Commit**

```bash
git add bench/auth.py
git commit -m "feat(bench): add AK/SK HMAC-SHA256 auth module"
```

---

### Task 3: Metrics Collection

**Files:**
- Create: `bench/metrics.py`

- [ ] **Step 1: Create `bench/metrics.py`**

```python
"""Real-time metrics collection with thread-safe counters."""

import asyncio
import time
from dataclasses import dataclass, field


@dataclass
class MetricsCollector:
    """Collects benchmark metrics from all pairs."""

    start_time: float = 0.0
    _latencies: list = field(default_factory=list)
    _timestamps: list = field(default_factory=list)  # completion timestamps for TPS
    total_sent: int = 0
    total_received: int = 0
    total_failed: int = 0
    http_errors: int = 0
    ws_timeouts: int = 0
    ws_disconnects: int = 0
    agent_disconnects: int = 0
    active_agents: int = 0
    active_clients: int = 0
    _lock: asyncio.Lock = field(default_factory=asyncio.Lock)

    def start(self):
        self.start_time = time.time()

    async def record_sent(self):
        async with self._lock:
            self.total_sent += 1

    async def record_received(self, latency_ms: float):
        async with self._lock:
            self._latencies.append(latency_ms)
            self._timestamps.append(time.time())
            self.total_received += 1

    async def record_http_error(self):
        async with self._lock:
            self.total_failed += 1
            self.http_errors += 1

    async def record_ws_timeout(self):
        async with self._lock:
            self.total_failed += 1
            self.ws_timeouts += 1

    async def record_ws_disconnect(self, is_agent: bool):
        async with self._lock:
            self.ws_disconnects += 1
            if is_agent:
                self.agent_disconnects += 1

    async def set_active(self, agents: int, clients: int):
        async with self._lock:
            self.active_agents = agents
            self.active_clients = clients

    def _calc_percentile(self, sorted_data: list, p: float) -> float:
        if not sorted_data:
            return 0.0
        k = (len(sorted_data) - 1) * (p / 100.0)
        f = int(k)
        c = f + 1
        if c >= len(sorted_data):
            return sorted_data[f]
        return sorted_data[f] + (k - f) * (sorted_data[c] - sorted_data[f])

    def snapshot(self) -> dict:
        """Return a point-in-time snapshot of all metrics."""
        elapsed = time.time() - self.start_time if self.start_time else 0
        sorted_lat = sorted(self._latencies) if self._latencies else []

        # TPS: count completions in last 2 seconds
        now = time.time()
        recent = [t for t in self._timestamps if now - t <= 2.0]
        current_tps = len(recent) / 2.0 if recent else 0

        # Overall avg TPS
        avg_tps = self.total_received / elapsed if elapsed > 0 else 0

        return {
            "elapsed_s": round(elapsed, 1),
            "pairs": self.active_agents,
            "total_sent": self.total_sent,
            "total_received": self.total_received,
            "total_failed": self.total_failed,
            "current_tps": round(current_tps, 1),
            "avg_tps": round(avg_tps, 1),
            "latency_min": round(sorted_lat[0], 1) if sorted_lat else 0,
            "latency_avg": round(sum(sorted_lat) / len(sorted_lat), 1) if sorted_lat else 0,
            "latency_p50": round(self._calc_percentile(sorted_lat, 50), 1),
            "latency_p95": round(self._calc_percentile(sorted_lat, 95), 1),
            "latency_p99": round(self._calc_percentile(sorted_lat, 99), 1),
            "latency_max": round(sorted_lat[-1], 1) if sorted_lat else 0,
            "http_errors": self.http_errors,
            "ws_timeouts": self.ws_timeouts,
            "ws_disconnects": self.ws_disconnects,
            "agent_disconnects": self.agent_disconnects,
            "active_agents": self.active_agents,
            "active_clients": self.active_clients,
        }

    def format_realtime(self) -> str:
        """Format a one-line real-time status string."""
        s = self.snapshot()
        elapsed = int(s["elapsed_s"])
        mm, ss = divmod(elapsed, 60)
        return (
            f"[{mm:02d}:{ss:02d}] "
            f"pairs={s['pairs']} | "
            f"TPS={s['current_tps']} | "
            f"avg={s['latency_avg']}ms p95={s['latency_p95']}ms p99={s['latency_p99']}ms | "
            f"err={s['total_failed']} | "
            f"sent={s['total_sent']} recv={s['total_received']}"
        )
```

- [ ] **Step 2: Verify metrics module imports**

Run: `python -c "from bench.metrics import MetricsCollector; m = MetricsCollector(); print(m.snapshot())"`

Expected: prints a dict with all zero values.

- [ ] **Step 3: Commit**

```bash
git add bench/metrics.py
git commit -m "feat(bench): add real-time metrics collection"
```

---

### Task 4: Report Generation

**Files:**
- Create: `bench/report.py`

- [ ] **Step 1: Create `bench/report.py`**

```python
"""Summary report generation: terminal table + JSON + CSV."""

import csv
import json
import os
from datetime import datetime

from .metrics import MetricsCollector


def print_summary(config, metrics: MetricsCollector):
    """Print a formatted summary table to terminal."""
    s = metrics.snapshot()
    print()
    print("═" * 50)
    print(f"  Benchmark Summary ({config.mode} mode, {config.duration}s)")
    print("═" * 50)
    print(f"  Pairs:        {config.pairs}")
    print(f"  Total Sent:   {s['total_sent']}")
    print(f"  Total Recv:   {s['total_received']}")
    print(f"  Failed:       {s['total_failed']}")
    print("  " + "─" * 46)
    print(f"  TPS (avg):    {s['avg_tps']}")
    # peak TPS: scan timestamps in 1-second windows
    peak_tps = _calc_peak_tps(metrics._timestamps)
    print(f"  TPS (peak):   {peak_tps}")
    print("  " + "─" * 46)
    print("  Latency (ms):")
    print(
        f"    min={s['latency_min']}  avg={s['latency_avg']}  "
        f"P50={s['latency_p50']}  P95={s['latency_p95']}  "
        f"P99={s['latency_p99']}  max={s['latency_max']}"
    )
    print("  " + "─" * 46)
    print("  Connections:")
    print(f"    Agent disconnects: {s['agent_disconnects']}")
    print(f"    Client disconnects: {s['ws_disconnects'] - s['agent_disconnects']}")
    print(f"    WS timeouts: {s['ws_timeouts']}")
    print("═" * 50)
    print()


def _calc_peak_tps(timestamps: list) -> float:
    if not timestamps:
        return 0.0
    sorted_ts = sorted(timestamps)
    max_count = 0
    for i, t in enumerate(sorted_ts):
        count = 0
        for t2 in sorted_ts[i:]:
            if t2 - t <= 1.0:
                count += 1
            else:
                break
        max_count = max(max_count, count)
    return round(max_count, 1)


def export_json(config, metrics: MetricsCollector, output_dir: str) -> str:
    """Export full report as JSON. Returns the file path."""
    os.makedirs(output_dir, exist_ok=True)
    ts = datetime.now().strftime("%Y%m%d_%H%M%S")
    path = os.path.join(output_dir, f"bench_report_{ts}.json")

    data = {
        "config": {
            "mode": config.mode,
            "pairs": config.pairs,
            "duration": config.duration,
            "echo_delay": config.echo_delay,
            "ss_url": config.ss_url,
            "gw_url": config.gw_url,
        },
        "summary": metrics.snapshot(),
        "latencies": metrics._latencies,
        "timestamps": metrics._timestamps,
    }

    with open(path, "w", encoding="utf-8") as f:
        json.dump(data, f, indent=2, ensure_ascii=False)

    print(f"JSON report saved to: {path}")
    return path


def export_csv(config, metrics: MetricsCollector, output_dir: str) -> str:
    """Export summary as CSV. Returns the file path."""
    os.makedirs(output_dir, exist_ok=True)
    ts = datetime.now().strftime("%Y%m%d_%H%M%S")
    path = os.path.join(output_dir, f"bench_summary_{ts}.csv")

    s = metrics.snapshot()
    with open(path, "w", newline="", encoding="utf-8") as f:
        writer = csv.writer(f)
        writer.writerow(["metric", "value"])
        writer.writerow(["mode", config.mode])
        writer.writerow(["pairs", config.pairs])
        writer.writerow(["duration_s", config.duration])
        for key, val in s.items():
            writer.writerow([key, val])
        writer.writerow(["peak_tps", _calc_peak_tps(metrics._timestamps)])

    print(f"CSV summary saved to: {path}")
    return path
```

- [ ] **Step 2: Verify report module imports**

Run: `python -c "from bench.report import print_summary; print('OK')"`

Expected: prints `OK`.

- [ ] **Step 3: Commit**

```bash
git add bench/report.py
git commit -m "feat(bench): add report generation (terminal + JSON + CSV)"
```

---

### Task 5: Mock Agent

**Files:**
- Create: `bench/mock_agent.py`

- [ ] **Step 1: Create `bench/mock_agent.py`**

```python
"""Mock/Echo Agent: connects to GW /ws/agent, responds to invoke commands."""

import asyncio
import json
import logging

import websockets

from .auth import build_auth_subprotocol
from .metrics import MetricsCollector

logger = logging.getLogger(__name__)


class MockAgent:
    def __init__(
        self,
        agent_id: int,
        ak: str,
        sk: str,
        gw_url: str,
        mode: str,
        echo_delay_ms: int,
        metrics: MetricsCollector,
    ):
        self.agent_id = agent_id
        self.ak = ak
        self.sk = sk
        self.gw_url = gw_url
        self.mode = mode
        self.echo_delay_ms = echo_delay_ms
        self.metrics = metrics
        self._ws = None
        self._running = False
        self._heartbeat_task = None

    async def start(self, ready_event: asyncio.Event):
        """Connect to GW, register, then listen for invoke messages."""
        subprotocol = build_auth_subprotocol(self.ak, self.sk)
        ws_url = f"{self.gw_url}/ws/agent"

        try:
            self._ws = await websockets.connect(
                ws_url,
                subprotocols=[subprotocol],
                ping_interval=None,
            )
            self._running = True

            # Send register message
            register_msg = json.dumps({
                "type": "register",
                "deviceName": f"bench-agent-{self.agent_id}",
                "macAddress": f"00:00:00:00:00:{self.agent_id:02X}",
                "os": "benchmark",
                "toolType": "bench",
                "toolVersion": "1.0.0",
            })
            await self._ws.send(register_msg)

            # Wait for register_ok
            response = await asyncio.wait_for(self._ws.recv(), timeout=10)
            data = json.loads(response)
            if data.get("type") != "register_ok":
                logger.error(f"Agent {self.agent_id}: register failed: {data}")
                ready_event.set()
                return

            logger.info(f"Agent {self.agent_id}: registered successfully")

            # Start heartbeat
            self._heartbeat_task = asyncio.create_task(self._heartbeat_loop())

            # Signal ready
            ready_event.set()

            # Listen for messages
            await self._listen()

        except Exception as e:
            logger.error(f"Agent {self.agent_id}: connection error: {e}")
            ready_event.set()
            await self.metrics.record_ws_disconnect(is_agent=True)
        finally:
            self._running = False
            if self._heartbeat_task:
                self._heartbeat_task.cancel()
            if self._ws:
                await self._ws.close()

    async def _heartbeat_loop(self):
        """Send heartbeat every 30 seconds."""
        try:
            while self._running:
                await asyncio.sleep(30)
                if self._ws and self._running:
                    await self._ws.send(json.dumps({"type": "heartbeat"}))
        except asyncio.CancelledError:
            pass

    async def _listen(self):
        """Listen for invoke messages and respond."""
        try:
            async for raw in self._ws:
                msg = json.loads(raw)
                msg_type = msg.get("type")

                if msg_type == "invoke":
                    await self._handle_invoke(msg)
                elif msg_type == "status_query":
                    await self._ws.send(json.dumps({
                        "type": "status_response",
                        "opencodeOnline": True,
                    }))
        except websockets.ConnectionClosed:
            logger.warning(f"Agent {self.agent_id}: connection closed")
            await self.metrics.record_ws_disconnect(is_agent=True)

    async def _handle_invoke(self, msg: dict):
        """Respond to an invoke command with tool_event + tool_done."""
        tool_session_id = msg.get("toolSessionId", "")
        welink_session_id = msg.get("welinkSessionId", "")

        if self.mode == "echo":
            await asyncio.sleep(self.echo_delay_ms / 1000.0)

        # Send tool_event
        await self._ws.send(json.dumps({
            "type": "tool_event",
            "toolSessionId": tool_session_id,
            "welinkSessionId": welink_session_id,
            "event": {
                "type": "text",
                "content": f"Benchmark response from agent {self.agent_id}",
            },
        }))

        # Send tool_done
        await self._ws.send(json.dumps({
            "type": "tool_done",
            "toolSessionId": tool_session_id,
            "welinkSessionId": welink_session_id,
            "usage": {
                "input_tokens": 10,
                "output_tokens": 5,
            },
        }))

    def stop(self):
        self._running = False
```

- [ ] **Step 2: Verify mock_agent module imports**

Run: `python -c "from bench.mock_agent import MockAgent; print('OK')"`

Expected: prints `OK`.

- [ ] **Step 3: Commit**

```bash
git add bench/mock_agent.py
git commit -m "feat(bench): add Mock/Echo Agent with register + heartbeat + invoke handling"
```

---

### Task 6: Virtual Client

**Files:**
- Create: `bench/client.py`

- [ ] **Step 1: Create `bench/client.py`**

```python
"""Virtual user client: sends messages via SS REST API, receives via SS WebSocket stream."""

import asyncio
import json
import logging
import time

import aiohttp
import websockets

from .metrics import MetricsCollector

logger = logging.getLogger(__name__)


class BenchClient:
    def __init__(
        self,
        client_id: int,
        user_id: str,
        ak: str,
        ss_url: str,
        timeout: int,
        metrics: MetricsCollector,
    ):
        self.client_id = client_id
        self.user_id = user_id
        self.ak = ak
        self.ss_url = ss_url
        self.timeout = timeout
        self.metrics = metrics
        self.session_id = None
        self._running = False
        self._done_event = asyncio.Event()

    async def setup(self, http_session: aiohttp.ClientSession) -> bool:
        """Create a session on Skill Server. Returns True on success."""
        url = f"{self.ss_url}/api/skill/sessions"
        cookies = {"userId": self.user_id}
        body = {"ak": self.ak}

        try:
            async with http_session.post(url, json=body, cookies=cookies) as resp:
                if resp.status != 200:
                    text = await resp.text()
                    logger.error(f"Client {self.client_id}: session create failed: {resp.status} {text}")
                    return False
                data = await resp.json()
                if data.get("code") != 0:
                    logger.error(f"Client {self.client_id}: session create error: {data}")
                    return False
                self.session_id = data["data"]["welinkSessionId"]
                logger.info(f"Client {self.client_id}: session created: {self.session_id}")
                return True
        except Exception as e:
            logger.error(f"Client {self.client_id}: session create exception: {e}")
            return False

    async def run(self, http_session: aiohttp.ClientSession, duration: int):
        """Run the send-receive loop for the given duration."""
        self._running = True
        deadline = time.time() + duration

        # Build WS URL
        ss_ws_url = self.ss_url.replace("http://", "ws://").replace("https://", "wss://")
        ws_url = f"{ss_ws_url}/ws/skill/stream"

        try:
            ws = await websockets.connect(
                ws_url,
                additional_headers={"Cookie": f"userId={self.user_id}"},
                ping_interval=None,
            )
        except Exception as e:
            logger.error(f"Client {self.client_id}: WS connect failed: {e}")
            await self.metrics.record_ws_disconnect(is_agent=False)
            return

        # Start WS listener task
        listener_task = asyncio.create_task(self._ws_listener(ws))

        msg_count = 0
        try:
            while self._running and time.time() < deadline:
                msg_count += 1
                await self._send_and_wait(http_session, ws, msg_count)
        except Exception as e:
            logger.error(f"Client {self.client_id}: run error: {e}")
        finally:
            self._running = False
            listener_task.cancel()
            await ws.close()

    async def _send_and_wait(self, http_session: aiohttp.ClientSession, ws, msg_num: int):
        """Send one message and wait for tool_done response."""
        url = f"{self.ss_url}/api/skill/sessions/{self.session_id}/messages"
        cookies = {"userId": self.user_id}
        body = {"content": f"bench test message #{msg_num}"}

        # Record send time
        t_send = time.time()
        await self.metrics.record_sent()

        try:
            async with http_session.post(url, json=body, cookies=cookies) as resp:
                if resp.status != 200:
                    await self.metrics.record_http_error()
                    return
                data = await resp.json()
                if data.get("code") != 0:
                    await self.metrics.record_http_error()
                    return
        except Exception:
            await self.metrics.record_http_error()
            return

        # Wait for tool_done via WebSocket
        self._done_event.clear()
        try:
            await asyncio.wait_for(self._done_event.wait(), timeout=self.timeout)
            t_recv = time.time()
            latency_ms = (t_recv - t_send) * 1000
            await self.metrics.record_received(latency_ms)
        except asyncio.TimeoutError:
            await self.metrics.record_ws_timeout()

    async def _ws_listener(self, ws):
        """Listen for StreamMessages on the WS connection."""
        try:
            async for raw in ws:
                msg = json.loads(raw)
                msg_type = msg.get("type", "")
                msg_session = msg.get("welinkSessionId", "")

                # Check if this is a tool_done or session.status completed for our session
                if str(msg_session) == str(self.session_id):
                    if msg_type == "tool.done" or msg_type == "step.done":
                        self._done_event.set()
                    elif msg_type == "session.status":
                        status = msg.get("status", "")
                        if status in ("COMPLETED", "IDLE"):
                            self._done_event.set()
        except websockets.ConnectionClosed:
            logger.warning(f"Client {self.client_id}: WS connection closed")
            await self.metrics.record_ws_disconnect(is_agent=False)
        except asyncio.CancelledError:
            pass

    def stop(self):
        self._running = False
```

- [ ] **Step 2: Verify client module imports**

Run: `python -c "from bench.client import BenchClient; print('OK')"`

Expected: prints `OK`.

- [ ] **Step 3: Commit**

```bash
git add bench/client.py
git commit -m "feat(bench): add virtual client with HTTP send + WS receive"
```

---

### Task 7: Credential Generator

**Files:**
- Create: `bench/gen_credentials.py`
- Create: `bench/credentials.csv`

- [ ] **Step 1: Create `bench/gen_credentials.py`**

```python
"""Generate test AK/SK credentials: CSV for bench, SQL for database import."""

import argparse
import csv
import os
import uuid


def generate(count: int, output: str):
    """Generate credentials CSV and companion SQL file."""
    csv_path = output
    sql_path = output.rsplit(".", 1)[0] + ".sql"

    rows = []
    for i in range(count):
        ak = f"bench_agent_{i}"
        sk = f"bench_secret_{uuid.uuid4().hex[:16]}"
        user_id = f"bench_user_{i}"
        rows.append((ak, sk, user_id))

    # Write CSV
    with open(csv_path, "w", newline="", encoding="utf-8") as f:
        writer = csv.writer(f)
        writer.writerow(["ak", "sk", "user_id"])
        for row in rows:
            writer.writerow(row)

    print(f"CSV written to: {csv_path} ({count} rows)")

    # Write SQL
    with open(sql_path, "w", encoding="utf-8") as f:
        f.write("-- Auto-generated benchmark test credentials\n")
        f.write("-- Import into ai_gateway.ak_sk_credential table\n\n")
        f.write("INSERT INTO ak_sk_credential (ak, sk, user_id, status, description, created_at) VALUES\n")
        lines = []
        for ak, sk, user_id in rows:
            lines.append(f"  ('{ak}', '{sk}', '{user_id}', 'ACTIVE', 'bench test', NOW())")
        f.write(",\n".join(lines))
        f.write(";\n")

    print(f"SQL written to: {sql_path} ({count} rows)")


def main():
    parser = argparse.ArgumentParser(description="Generate benchmark test credentials")
    parser.add_argument("--count", type=int, default=100, help="Number of credential pairs")
    parser.add_argument("--output", default="./bench/credentials.csv", help="Output CSV path")
    args = parser.parse_args()

    os.makedirs(os.path.dirname(args.output) or ".", exist_ok=True)
    generate(args.count, args.output)


if __name__ == "__main__":
    main()
```

- [ ] **Step 2: Generate example credentials**

Run: `python -m bench.gen_credentials --count 10 --output ./bench/credentials.csv`

Expected: creates `bench/credentials.csv` (10 rows) and `bench/credentials.sql`.

- [ ] **Step 3: Verify CSV format**

Run: `python -c "import csv; r=list(csv.DictReader(open('bench/credentials.csv'))); print(len(r), r[0].keys())"`

Expected: `10 dict_keys(['ak', 'sk', 'user_id'])`

- [ ] **Step 4: Commit**

```bash
git add bench/gen_credentials.py bench/credentials.csv bench/credentials.sql
git commit -m "feat(bench): add credential generator (CSV + SQL)"
```

---

### Task 8: Main Coordinator (Full Integration)

**Files:**
- Modify: `bench/__main__.py`

- [ ] **Step 1: Rewrite `bench/__main__.py` with full orchestration**

```python
"""CLI entry point: python -m bench — full benchmark orchestration."""

import asyncio
import csv
import logging
import sys
import time

import aiohttp

from .config import BenchConfig, parse_args
from .metrics import MetricsCollector
from .mock_agent import MockAgent
from .client import BenchClient
from .report import export_csv, export_json, print_summary

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(levelname)s %(name)s: %(message)s",
)
logger = logging.getLogger(__name__)


def load_credentials(path: str, count: int) -> list[dict]:
    """Load credentials from CSV. Returns list of {ak, sk, user_id} dicts."""
    with open(path, newline="", encoding="utf-8") as f:
        reader = csv.DictReader(f)
        rows = list(reader)
    if len(rows) < count:
        logger.error(f"Credentials file has {len(rows)} rows, but {count} pairs requested")
        sys.exit(1)
    return rows[:count]


async def realtime_printer(metrics: MetricsCollector, interval: float = 2.0):
    """Print real-time metrics to terminal every N seconds."""
    try:
        while True:
            await asyncio.sleep(interval)
            print(f"\r{metrics.format_realtime()}", end="", flush=True)
    except asyncio.CancelledError:
        print()  # newline after last status


async def run_benchmark(config: BenchConfig):
    """Run the full benchmark."""
    # Load credentials
    creds = load_credentials(config.credentials, config.pairs)
    logger.info(f"Loaded {len(creds)} credentials from {config.credentials}")

    metrics = MetricsCollector()

    # Phase 1: Start Mock Agents
    logger.info(f"Starting {config.pairs} Mock Agents (mode={config.mode})...")
    agents = []
    ready_events = []
    agent_tasks = []

    for i, cred in enumerate(creds):
        agent = MockAgent(
            agent_id=i,
            ak=cred["ak"],
            sk=cred["sk"],
            gw_url=config.gw_url,
            mode=config.mode,
            echo_delay_ms=config.echo_delay,
            metrics=metrics,
        )
        agents.append(agent)
        ready_event = asyncio.Event()
        ready_events.append(ready_event)
        task = asyncio.create_task(agent.start(ready_event))
        agent_tasks.append(task)

    # Wait for all agents to be ready
    logger.info("Waiting for all agents to register...")
    await asyncio.gather(*[e.wait() for e in ready_events])
    active_agents = sum(1 for a in agents if a._running)
    logger.info(f"{active_agents}/{config.pairs} agents registered and ready")

    if active_agents == 0:
        logger.error("No agents connected. Aborting benchmark.")
        return

    # Phase 2: Create sessions and start clients
    logger.info("Creating sessions and starting clients...")
    clients = []
    async with aiohttp.ClientSession() as http_session:
        for i, cred in enumerate(creds):
            if not agents[i]._running:
                continue
            client = BenchClient(
                client_id=i,
                user_id=cred["user_id"],
                ak=cred["ak"],
                ss_url=config.ss_url,
                timeout=config.timeout,
                metrics=metrics,
            )
            success = await client.setup(http_session)
            if success:
                clients.append(client)

        active_clients = len(clients)
        logger.info(f"{active_clients} clients ready")
        await metrics.set_active(active_agents, active_clients)

        # Phase 3: Run benchmark
        metrics.start()
        logger.info(f"Benchmark started: {config.pairs} pairs, {config.duration}s, mode={config.mode}")

        printer_task = asyncio.create_task(realtime_printer(metrics))

        # Start clients with optional ramp-up
        client_tasks = []
        for i, client in enumerate(clients):
            if config.ramp_up > 0 and i > 0 and i % config.ramp_up == 0:
                await asyncio.sleep(1.0)
            task = asyncio.create_task(client.run(http_session, config.duration))
            client_tasks.append(task)

        # Wait for all clients to finish
        await asyncio.gather(*client_tasks, return_exceptions=True)

    # Phase 4: Cleanup
    printer_task.cancel()
    await printer_task

    for agent in agents:
        agent.stop()
    for task in agent_tasks:
        task.cancel()
    await asyncio.gather(*agent_tasks, return_exceptions=True)

    # Phase 5: Report
    print_summary(config, metrics)
    export_json(config, metrics, config.output_dir)
    export_csv(config, metrics, config.output_dir)


def entry():
    config = parse_args()
    try:
        asyncio.run(run_benchmark(config))
    except KeyboardInterrupt:
        print("\nBenchmark interrupted.")
        sys.exit(0)


if __name__ == "__main__":
    entry()
```

- [ ] **Step 2: Verify full integration compiles**

Run: `python -c "from bench.__main__ import run_benchmark; print('OK')"`

Expected: prints `OK`.

- [ ] **Step 3: Run with --help to verify CLI**

Run: `python -m bench --help`

Expected: argparse help output with all parameters.

- [ ] **Step 4: Commit**

```bash
git add bench/__main__.py
git commit -m "feat(bench): integrate full orchestration (agents + clients + metrics + reports)"
```

---

### Task 9: End-to-End Smoke Test

**Files:** No new files. This task verifies the complete system works against a running local environment.

- [ ] **Step 1: Generate test credentials**

Run: `python -m bench.gen_credentials --count 5 --output ./bench/credentials.csv`

Expected: creates CSV with 5 rows and companion SQL file.

- [ ] **Step 2: Import credentials into database**

Run the generated `bench/credentials.sql` against the GW database:

```bash
mysql -u root -p ai_gateway < bench/credentials.sql
```

Expected: 5 rows inserted into `ak_sk_credential`.

- [ ] **Step 3: Run mock mode smoke test (2 pairs, 10 seconds)**

Run: `python -m bench --mode mock --pairs 2 --duration 10 --credentials ./bench/credentials.csv`

Expected:
- 2 agents connect and register
- 2 clients create sessions and start sending
- Real-time metrics print every 2 seconds
- After 10 seconds, summary table prints
- JSON and CSV reports saved to `./bench_results/`

- [ ] **Step 4: Run echo mode smoke test (2 pairs, 10 seconds, 200ms delay)**

Run: `python -m bench --mode echo --pairs 2 --duration 10 --echo-delay 200 --credentials ./bench/credentials.csv`

Expected: same as above but TPS should be lower due to 200ms echo delay.

- [ ] **Step 5: Verify report files**

Run: `ls ./bench_results/`

Expected: `bench_report_*.json` and `bench_summary_*.csv` files present.

- [ ] **Step 6: Commit (if any fixes were needed)**

```bash
git add -A bench/
git commit -m "fix(bench): smoke test fixes"
```

---

### Task 10: Add .gitignore and README

**Files:**
- Create: `bench/.gitignore`
- Modify: `bench/credentials.csv` — ensure example file has safe dummy data

- [ ] **Step 1: Create `bench/.gitignore`**

```
# Ignore generated reports
/bench_results/

# Ignore generated SQL (contains secrets)
*.sql

# Keep example credentials but ignore user-generated ones
# credentials.csv is tracked as an example
```

- [ ] **Step 2: Regenerate example credentials with fixed seeds**

Run: `python -m bench.gen_credentials --count 3 --output ./bench/credentials.csv`

Edit `bench/credentials.csv` to use stable example values:

```csv
ak,sk,user_id
bench_agent_0,bench_secret_example_0,bench_user_0
bench_agent_1,bench_secret_example_1,bench_user_1
bench_agent_2,bench_secret_example_2,bench_user_2
```

- [ ] **Step 3: Commit**

```bash
git add bench/.gitignore bench/credentials.csv
git commit -m "chore(bench): add .gitignore and example credentials"
```
