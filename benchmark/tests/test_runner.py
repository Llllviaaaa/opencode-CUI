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
    call_log = []

    async def fake_worker(worker_id: int, metrics: MetricsCollector, stop_event: asyncio.Event):
        call_log.append(worker_id)
        await stop_event.wait()

    stages = [StageConfig(concurrency=3, duration_sec=1)]
    runner = Runner(stages=stages, ramp_up_seconds=0)

    asyncio.run(runner.run(fake_worker))
    assert len(call_log) == 3
