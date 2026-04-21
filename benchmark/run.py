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

# Add benchmark directory to sys.path
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
