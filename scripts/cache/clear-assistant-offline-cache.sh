#!/bin/bash
# clear-assistant-offline-cache.sh
# 清除助理离线文案缓存（两个 namespace），让 SQL 改动的 sys_config 立即生效。
#
# 用法：./scripts/cache/clear-assistant-offline-cache.sh [redis_host] [redis_port]
#
# 清除的 key 空间：
#   1. ss:config:assistant_offline:*   — SysConfigService 缓存（TTL 5min）
#   2. ss:availability:*               — AvailabilityService 缓存（TTL 30s）
#
# 说明：
#   - SysConfigService 缓存 TTL 真实值为 5 分钟（不是 30s），只删 availability 不够。
#   - 两个 namespace 必须同时清，否则改完 SQL 文案仍可能等 5 分钟才生效。
#   - 脚本用 redis-cli SCAN + DEL 避免 KEYS 阻塞生产 Redis。

set -euo pipefail

REDIS_HOST="${1:-localhost}"
REDIS_PORT="${2:-6379}"

redis_cmd() {
    redis-cli -h "$REDIS_HOST" -p "$REDIS_PORT" "$@"
}

delete_by_pattern() {
    local pattern="$1"
    local deleted=0
    local cursor=0

    while true; do
        local result
        result=$(redis_cmd SCAN "$cursor" MATCH "$pattern" COUNT 100)
        cursor=$(echo "$result" | head -1)
        local keys
        keys=$(echo "$result" | tail -n +2)

        if [ -n "$keys" ]; then
            local count
            count=$(echo "$keys" | wc -l | tr -d ' ')
            redis_cmd DEL $keys > /dev/null
            deleted=$((deleted + count))
        fi

        if [ "$cursor" = "0" ]; then
            break
        fi
    done

    echo "  Deleted $deleted key(s) matching '$pattern'"
}

echo "Clearing assistant offline caches on ${REDIS_HOST}:${REDIS_PORT}..."
echo ""

delete_by_pattern "ss:config:assistant_offline:*"
delete_by_pattern "ss:availability:*"

echo ""
echo "Done. SQL sys_config changes for assistant_offline will now take immediate effect."
