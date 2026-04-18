"""配置加载 — 解析 YAML 配置文件为 dataclass。"""

import csv
import re
from dataclasses import dataclass
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
    def parse_duration(val) -> int:
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
