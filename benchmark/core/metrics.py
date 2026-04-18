"""指标收集 — 实时控制台输出 + CSV/JSON 报告生成。"""

import csv
import json
import time
import threading
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
                "total_requests": 0, "errors": errors, "error_rate": 0.0,
                "qps": 0.0, "p50": 0.0, "p95": 0.0, "p99": 0.0, "max": 0.0, "avg": 0.0,
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
        }
        self._stages.append(result)
        return result

    def console_line(self, stage_index: int, total_stages: int, connections: int) -> str:
        snap = self.snapshot()
        ts = datetime.now().strftime("%H:%M:%S")
        err_pct = f"{snap['error_rate'] * 100:.1f}%"
        return (
            f"[{ts}] Stage {stage_index}/{total_stages} | "
            f"Clients: {self._stage_concurrency} | "
            f"QPS: {snap['qps']:.0f} | "
            f"P50: {snap['p50']:.0f}ms P95: {snap['p95']:.0f}ms P99: {snap['p99']:.0f}ms | "
            f"Err: {snap['errors']} ({err_pct}) | "
            f"Conns: {connections}/{self._stage_concurrency}"
        )

    def save_json(self, path: str, scenario: str, target: str):
        report = {
            "scenario": scenario, "target": target,
            "generated_at": datetime.now(timezone.utc).isoformat(),
            "stages": self._stages,
        }
        with open(path, "w") as f:
            json.dump(report, f, indent=2)

    def save_csv(self, path: str):
        fieldnames = [
            "stage", "concurrency", "duration_sec", "total_requests", "qps",
            "p50_ms", "p95_ms", "p99_ms", "max_ms", "avg_ms", "errors", "error_rate",
        ]
        with open(path, "w", newline="") as f:
            writer = csv.DictWriter(f, fieldnames=fieldnames)
            writer.writeheader()
            for i, s in enumerate(self._stages, 1):
                writer.writerow({
                    "stage": i, "concurrency": s["concurrency"],
                    "duration_sec": s["duration_sec"], "total_requests": s["total_requests"],
                    "qps": s["qps"],
                    "p50_ms": s["latency_ms"]["p50"], "p95_ms": s["latency_ms"]["p95"],
                    "p99_ms": s["latency_ms"]["p99"], "max_ms": s["latency_ms"]["max"],
                    "avg_ms": s["latency_ms"]["avg"],
                    "errors": s["errors"], "error_rate": s["error_rate"],
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
