import json
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
    assert snap["p50"] == 49.5
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
