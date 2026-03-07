#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PID_DIR="${ROOT_DIR}/logs/local-stack/pids"

stop_by_pid_file() {
  local name="$1"
  local pid_file="${PID_DIR}/${name}.pid"
  if [[ ! -f "${pid_file}" ]]; then
    echo "[skip] ${name}: pid file not found"
    return 0
  fi

  local pid
  pid="$(cat "${pid_file}")"
  if [[ -z "${pid}" ]]; then
    echo "[skip] ${name}: empty pid file"
    rm -f "${pid_file}"
    return 0
  fi

  if kill -0 "${pid}" >/dev/null 2>&1; then
    echo "[stop] ${name} (pid=${pid})"
    kill "${pid}" >/dev/null 2>&1 || true
    sleep 1
    if kill -0 "${pid}" >/dev/null 2>&1; then
      kill -9 "${pid}" >/dev/null 2>&1 || true
    fi
  else
    echo "[skip] ${name}: process not running (pid=${pid})"
  fi

  rm -f "${pid_file}"
}

stop_by_pid_file "skill-miniapp"
stop_by_pid_file "skill-server"
stop_by_pid_file "ai-gateway"
stop_by_pid_file "test-simulator"

echo "Local stack stop command completed."

