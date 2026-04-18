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
    miniapp_clients: list[MockMiniAppClient | None] = []
    http_session: aiohttp.ClientSession | None = None

    async def setup():
        nonlocal http_session
        http_session = aiohttp.ClientSession()

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
