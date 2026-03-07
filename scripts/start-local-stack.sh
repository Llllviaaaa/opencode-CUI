#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LOG_DIR="${ROOT_DIR}/logs/local-stack"
PID_DIR="${LOG_DIR}/pids"
mkdir -p "${LOG_DIR}" "${PID_DIR}"

DB_HOST="${DB_HOST:-localhost}"
DB_PORT="${DB_PORT:-3306}"
DB_USER="${DB_USER:-opencode}"
DB_PASSWORD="${DB_PASSWORD:-opencode}"
AI_DB="${AI_DB:-ai_gateway}"
SKILL_DB="${SKILL_DB:-skill_server}"
MINIAPP_PORT="${MINIAPP_PORT:-3001}"
SIMULATOR_PORT="${SIMULATOR_PORT:-5173}"
START_TEST_SIMULATOR="${START_TEST_SIMULATOR:-0}"

require_cmd() {
  local cmd="$1"
  if ! command -v "${cmd}" >/dev/null 2>&1; then
    echo "Missing required command: ${cmd}" >&2
    exit 1
  fi
}

require_cmd lsof
require_cmd mysql
require_cmd mvn
require_cmd npm

MYSQL_CMD=(mysql -h "${DB_HOST}" -P "${DB_PORT}" -u"${DB_USER}")
if [[ -n "${DB_PASSWORD}" ]]; then
  MYSQL_CMD+=(-p"${DB_PASSWORD}")
fi

run_sql() {
  local sql="$1"
  printf "%s\n" "${sql}" | "${MYSQL_CMD[@]}"
}

table_exists() {
  local db="$1"
  local table="$2"
  local count
  count="$("${MYSQL_CMD[@]}" -Nse "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='${db}' AND table_name='${table}';")"
  [[ "${count}" != "0" ]]
}

wait_for_port() {
  local port="$1"
  local name="$2"
  local attempts=60
  while (( attempts > 0 )); do
    if lsof -nP -iTCP:"${port}" -sTCP:LISTEN >/dev/null 2>&1; then
      echo "[ok] ${name} is listening on :${port}"
      return 0
    fi
    sleep 1
    attempts=$((attempts - 1))
  done
  echo "[warn] ${name} did not open :${port} within timeout. Check logs in ${LOG_DIR}" >&2
  return 1
}

start_bg() {
  local name="$1"
  local port="$2"
  local pid_file="$3"
  local log_file="$4"
  local cmd="$5"

  if lsof -nP -iTCP:"${port}" -sTCP:LISTEN >/dev/null 2>&1; then
    echo "[skip] ${name} already listening on :${port}"
    return 0
  fi

  echo "[start] ${name}"
  nohup bash -lc "${cmd}" >"${log_file}" 2>&1 &
  local pid=$!
  echo "${pid}" >"${pid_file}"
  wait_for_port "${port}" "${name}" || true
}

echo "[1/4] Prepare databases"
run_sql "CREATE DATABASE IF NOT EXISTS ${AI_DB} CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
run_sql "CREATE DATABASE IF NOT EXISTS ${SKILL_DB} CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"

if ! table_exists "${AI_DB}" "agent_connection"; then
  echo "[db] Init ${AI_DB}.agent_connection"
  "${MYSQL_CMD[@]}" "${AI_DB}" < "${ROOT_DIR}/ai-gateway/src/main/resources/db/migration/V1__gateway.sql"
fi
if ! table_exists "${AI_DB}" "ak_sk_credential"; then
  echo "[db] Init ${AI_DB}.ak_sk_credential"
  "${MYSQL_CMD[@]}" "${AI_DB}" < "${ROOT_DIR}/ai-gateway/src/main/resources/db/migration/V2__ak_sk_credential.sql"
fi
if ! table_exists "${SKILL_DB}" "skill_definition"; then
  echo "[db] Init ${SKILL_DB}.skill_definition/skill_session/skill_message"
  "${MYSQL_CMD[@]}" "${SKILL_DB}" < "${ROOT_DIR}/skill-server/src/main/resources/db/migration/V1__skill.sql"
fi

echo "[2/4] Start ai-gateway"
start_bg \
  "ai-gateway" \
  "8081" \
  "${PID_DIR}/ai-gateway.pid" \
  "${LOG_DIR}/ai-gateway.log" \
  "cd '${ROOT_DIR}/ai-gateway' && DB_USERNAME='${DB_USER}' DB_PASSWORD='${DB_PASSWORD}' mvn spring-boot:run"

echo "[3/4] Start skill-server"
start_bg \
  "skill-server" \
  "8082" \
  "${PID_DIR}/skill-server.pid" \
  "${LOG_DIR}/skill-server.log" \
  "cd '${ROOT_DIR}/skill-server' && DB_USERNAME='${DB_USER}' DB_PASSWORD='${DB_PASSWORD}' mvn spring-boot:run"

echo "[4/4] Start skill-miniapp"
start_bg \
  "skill-miniapp" \
  "${MINIAPP_PORT}" \
  "${PID_DIR}/skill-miniapp.pid" \
  "${LOG_DIR}/skill-miniapp.log" \
  "cd '${ROOT_DIR}/skill-miniapp' && if [[ ! -d node_modules ]]; then npm install; fi && npm run dev -- --host 0.0.0.0 --port ${MINIAPP_PORT}"

if [[ "${START_TEST_SIMULATOR}" == "1" ]]; then
  echo "[extra] Start test-simulator"
  start_bg \
    "test-simulator" \
    "${SIMULATOR_PORT}" \
    "${PID_DIR}/test-simulator.pid" \
    "${LOG_DIR}/test-simulator.log" \
    "cd '${ROOT_DIR}/test-simulator' && if [[ ! -d node_modules ]]; then npm install; fi && npm run dev -- --host 0.0.0.0 --port ${SIMULATOR_PORT}"
fi

echo
echo "Local stack is up."
echo "  ai-gateway:    http://localhost:8081"
echo "  skill-server:  http://localhost:8082"
echo "  skill-miniapp: http://localhost:${MINIAPP_PORT}"
if [[ "${START_TEST_SIMULATOR}" == "1" ]]; then
  echo "  test-simulator: http://localhost:${SIMULATOR_PORT}"
fi
echo "Logs: ${LOG_DIR}"

