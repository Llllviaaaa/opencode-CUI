#!/usr/bin/env bash
set -euo pipefail

OPENCODE_SERVER_PORT="${OPENCODE_SERVER_PORT:-54321}"

kill_pid_gracefully() {
  local pid="$1"
  if ! kill -0 "${pid}" >/dev/null 2>&1; then
    return 0
  fi

  kill "${pid}" >/dev/null 2>&1 || true
  sleep 1
  if kill -0 "${pid}" >/dev/null 2>&1; then
    kill -9 "${pid}" >/dev/null 2>&1 || true
  fi
}

describe_pid() {
  local pid="$1"
  ps -p "${pid}" -o command= 2>/dev/null || true
}

collect_candidate_pids() {
  {
    ps -axo pid=,command= | awk '
      {
        pid = $1
        $1 = ""
        sub(/^[[:space:]]+/, "", $0)
        cmd = $0
        if ((cmd ~ /(^|[[:space:]\/])opencode([[:space:]]|$)/ && cmd ~ /(^|[[:space:]])serve([[:space:]]|$)/) || cmd ~ /opencode-server/) {
          print pid
        }
      }
    ' || true
    lsof -t -nP -iTCP:"${OPENCODE_SERVER_PORT}" -sTCP:LISTEN 2>/dev/null || true
  } | sort -u
}

main() {
  local matched=0
  local pid
  while IFS= read -r pid; do
    [[ -z "${pid}" ]] && continue
    if [[ "${pid}" == "$$" || "${pid}" == "${PPID:-}" ]]; then
      continue
    fi

    local command
    command="$(describe_pid "${pid}")"
    if [[ -z "${command}" ]]; then
      continue
    fi

    echo "[cleanup] stop opencode local server process (pid=${pid})"
    echo "          ${command}"
    kill_pid_gracefully "${pid}"
    matched=$((matched + 1))
  done < <(collect_candidate_pids)

  if [[ "${matched}" == "0" ]]; then
    echo "[cleanup] no stale opencode local server process found"
    return 0
  fi

  echo "[cleanup] removed ${matched} opencode local server process(es)"
}

main "$@"
