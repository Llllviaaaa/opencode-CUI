"""SS 压测场景 — 连接容量 + 吞吐量测试。

连接容量：渐进创建 MockMiniAppClient 连接。
吞吐量：并发 client 跑同步循环 (POST → MockGW 回复 → MiniApp 收到推送)。
"""

import asyncio
import logging
import time
import sys
import os
from datetime import datetime

import aiohttp

from core.config import BenchConfig
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
    mock_gw = MockGW(
        host=cfg.mock_gw.host,
        port=cfg.mock_gw.port,
        reply_delay_ms=cfg.mock_gw.reply_delay_ms,
    )
    await mock_gw.start()
    print(f"MockGW started on port {cfg.mock_gw.port}")

    max_concurrency = max(s.concurrency for s in cfg.stages)
    miniapp_clients: list[MockMiniAppClient | None] = []
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
                async with http_session.post(url, json=body) as resp:
                    if resp.status != 200:
                        metrics.record_error()
                        await asyncio.sleep(0.1)
                        continue

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
