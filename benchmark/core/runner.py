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
                excess = active_workers[target_count:]
                active_workers = active_workers[:target_count]
                for t in excess:
                    t.cancel()
                await asyncio.gather(*excess, return_exceptions=True)

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
