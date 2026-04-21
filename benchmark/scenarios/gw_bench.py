"""GW 压测场景 — 连接容量 + 吞吐量测试。

连接容量：渐进创建 MockAgent 连接（AK/SK 认证 + register）。
吞吐量：MockSS 并发发送 invoke，MockAgent 自动回复，测同步往返。
"""

import asyncio
import logging
import time
import sys
import os
from datetime import datetime

from core.config import BenchConfig, AkSkPair
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
    agent_count = min(max_concurrency, len(credentials))

    agents: list[MockAgent] = []
    mock_ss: MockSS | None = None

    async def setup():
        nonlocal mock_ss

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
