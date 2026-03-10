#!/usr/bin/env bash
set -euo pipefail
# Local stack bootstrap (restart by default):
# - proactively cleans stale listeners/pids before startup
# - avoids attaching to old JVM/node processes from previous runs

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
require_cmd curl

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

column_exists() {
  local db="$1"
  local table="$2"
  local column="$3"
  local count
  count="$("${MYSQL_CMD[@]}" -Nse "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='${db}' AND table_name='${table}' AND column_name='${column}';")"
  [[ "${count}" != "0" ]]
}

column_data_type() {
  local db="$1"
  local table="$2"
  local column="$3"
  "${MYSQL_CMD[@]}" -Nse "SELECT DATA_TYPE FROM information_schema.columns WHERE table_schema='${db}' AND table_name='${table}' AND column_name='${column}' LIMIT 1;"
}

index_exists() {
  local db="$1"
  local table="$2"
  local index_name="$3"
  local count
  count="$("${MYSQL_CMD[@]}" -Nse "SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema='${db}' AND table_name='${table}' AND index_name='${index_name}';")"
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

declare -a CLEANED_PIDS=()

is_port_listening() {
  local port="$1"
  lsof -nP -iTCP:"${port}" -sTCP:LISTEN >/dev/null 2>&1
}

pids_on_port() {
  local port="$1"
  lsof -t -nP -iTCP:"${port}" -sTCP:LISTEN 2>/dev/null | sort -u || true
}

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

cleanup_port_listeners() {
  local port="$1"
  local service="$2"
  local pids
  pids="$(pids_on_port "${port}")"
  if [[ -z "${pids}" ]]; then
    return 0
  fi

  while IFS= read -r pid; do
    [[ -z "${pid}" ]] && continue
    echo "[cleanup] stop ${service} listener on :${port} (pid=${pid})"
    kill_pid_gracefully "${pid}"
    CLEANED_PIDS+=("${service}:${pid}")
  done <<< "${pids}"
}

preflight_cleanup() {
  echo "[0/4] Preflight cleanup (restart by default)"
  if [[ -x "${ROOT_DIR}/scripts/stop-local-stack.sh" ]]; then
    echo "[cleanup] run scripts/stop-local-stack.sh (best effort)"
    "${ROOT_DIR}/scripts/stop-local-stack.sh" >/dev/null 2>&1 || true
  fi

  cleanup_port_listeners "8081" "ai-gateway"
  cleanup_port_listeners "8082" "skill-server"
  cleanup_port_listeners "${MINIAPP_PORT}" "skill-miniapp"
  if [[ "${START_TEST_SIMULATOR}" == "1" ]]; then
    cleanup_port_listeners "${SIMULATOR_PORT}" "test-simulator"
  fi

  if [[ ${#CLEANED_PIDS[@]} -eq 0 ]]; then
    echo "[cleanup] no stale listeners found"
  else
    echo "[cleanup] removed stale listeners:"
    for entry in "${CLEANED_PIDS[@]}"; do
      echo "  - ${entry}"
    done
  fi
}

print_listener_pid() {
  local service="$1"
  local port="$2"
  local pids
  pids="$(pids_on_port "${port}")"
  if [[ -n "${pids}" ]]; then
    echo "[pid] ${service} :${port} => $(echo "${pids}" | paste -sd ',' -)"
  else
    echo "[pid] ${service} :${port} => not listening"
  fi
}

post_start_self_check() {
  echo
  echo "[check] Verify skill-server /api/skill/agents response shape"
  local response
  if ! response="$(curl -sS -m 5 http://localhost:8082/api/skill/agents 2>/dev/null)"; then
    echo "[warn] Self-check request failed: http://localhost:8082/api/skill/agents"
    return 0
  fi

  if [[ "${response}" == *"\"timestamp\""* && "${response}" == *"\"error\":\"Bad Request\""* && "${response}" == *"\"path\":\"/api/skill/agents\""* ]]; then
    echo "[warn] /api/skill/agents returned Spring default 400 JSON."
    echo "[warn] This usually means stale/incorrect runtime classes are still being served."
    return 0
  fi

  if [[ "${response}" == *"\"code\":"* ]]; then
    echo "[ok] /api/skill/agents responded with ApiResponse shape."
    return 0
  fi

  echo "[warn] /api/skill/agents returned unexpected payload:"
  echo "${response}" | head -c 200
  echo
}

start_bg() {
  local name="$1"
  local port="$2"
  local pid_file="$3"
  local log_file="$4"
  local cmd="$5"

  if is_port_listening "${port}"; then
    cleanup_port_listeners "${port}" "${name}"
  fi

  if is_port_listening "${port}"; then
    echo "[error] ${name} port :${port} is still occupied after cleanup" >&2
    return 1
  fi

  echo "[start] ${name}"
  nohup bash -lc "${cmd}" >"${log_file}" 2>&1 &
  local launcher_pid=$!
  wait_for_port "${port}" "${name}" || true

  local listener_pid
  listener_pid="$(pids_on_port "${port}" | head -n 1)"
  if [[ -n "${listener_pid}" ]]; then
    echo "${listener_pid}" >"${pid_file}"
    if [[ "${listener_pid}" != "${launcher_pid}" ]]; then
      echo "[pid] ${name} launcher pid=${launcher_pid}, listener pid=${listener_pid}"
    fi
  else
    echo "${launcher_pid}" >"${pid_file}"
    echo "[warn] ${name} listener pid not found, fallback to launcher pid=${launcher_pid}"
  fi
}

preflight_cleanup

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
if ! table_exists "${SKILL_DB}" "skill_message_part"; then
  echo "[db] Init ${SKILL_DB}.skill_message_part (V2)"
  "${MYSQL_CMD[@]}" "${SKILL_DB}" < "${ROOT_DIR}/skill-server/src/main/resources/db/migration/V2__message_parts.sql"
fi

if table_exists "${SKILL_DB}" "skill_session"; then
  echo "[db] Reconcile ${SKILL_DB}.skill_session schema"

  if ! column_exists "${SKILL_DB}" "skill_session" "ak"; then
    echo "[db] Add skill_session.ak"
    run_sql "ALTER TABLE ${SKILL_DB}.skill_session ADD COLUMN ak VARCHAR(64) NULL AFTER user_id;"
    if column_exists "${SKILL_DB}" "skill_session" "agent_id"; then
      run_sql "UPDATE ${SKILL_DB}.skill_session SET ak = CAST(agent_id AS CHAR) WHERE agent_id IS NOT NULL;"
    fi
  fi

  if column_exists "${SKILL_DB}" "skill_session" "agent_id"; then
    if index_exists "${SKILL_DB}" "skill_session" "idx_agent"; then
      run_sql "ALTER TABLE ${SKILL_DB}.skill_session DROP INDEX idx_agent;"
    fi
    echo "[db] Drop legacy skill_session.agent_id"
    run_sql "ALTER TABLE ${SKILL_DB}.skill_session DROP COLUMN agent_id;"
  fi

  if ! index_exists "${SKILL_DB}" "skill_session" "idx_ak"; then
    run_sql "ALTER TABLE ${SKILL_DB}.skill_session ADD INDEX idx_ak (ak);"
  fi

  if column_exists "${SKILL_DB}" "skill_session" "im_chat_id" && ! column_exists "${SKILL_DB}" "skill_session" "im_group_id"; then
    echo "[db] Rename skill_session.im_chat_id -> im_group_id"
    run_sql "ALTER TABLE ${SKILL_DB}.skill_session CHANGE COLUMN im_chat_id im_group_id VARCHAR(128);"
  fi

  if column_exists "${SKILL_DB}" "skill_session" "skill_definition_id"; then
    echo "[db] Drop legacy skill_session.skill_definition_id"
    run_sql "ALTER TABLE ${SKILL_DB}.skill_session DROP COLUMN skill_definition_id;"
  fi

  if column_exists "${SKILL_DB}" "skill_session" "user_id"; then
    user_id_type="$(column_data_type "${SKILL_DB}" "skill_session" "user_id")"
    if [[ "${user_id_type}" != "varchar" ]]; then
      echo "[db] Align skill_session.user_id type to VARCHAR(128)"
      run_sql "ALTER TABLE ${SKILL_DB}.skill_session MODIFY COLUMN user_id VARCHAR(128) NOT NULL;"
    fi
  fi
fi

echo "[2/4] Start ai-gateway"
start_bg \
  "ai-gateway" \
  "8081" \
  "${PID_DIR}/ai-gateway.pid" \
  "${LOG_DIR}/ai-gateway.log" \
  "cd '${ROOT_DIR}/ai-gateway' && MYSQL_HOST='${DB_HOST}' MYSQL_PORT='${DB_PORT}' MYSQL_USERNAME='${DB_USER}' MYSQL_PASSWORD='${DB_PASSWORD}' MYSQL_AI_GATEWAY_DB='${AI_DB}' mvn spring-boot:run"

echo "[3/4] Start skill-server"
start_bg \
  "skill-server" \
  "8082" \
  "${PID_DIR}/skill-server.pid" \
  "${LOG_DIR}/skill-server.log" \
  "cd '${ROOT_DIR}/skill-server' && MYSQL_HOST='${DB_HOST}' MYSQL_PORT='${DB_PORT}' MYSQL_USERNAME='${DB_USER}' MYSQL_PASSWORD='${DB_PASSWORD}' MYSQL_SKILL_DB='${SKILL_DB}' mvn spring-boot:run"

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

post_start_self_check

echo
echo "Local stack is up."
echo "  ai-gateway:    http://localhost:8081"
echo "  skill-server:  http://localhost:8082"
echo "  skill-miniapp: http://localhost:${MINIAPP_PORT}"
if [[ "${START_TEST_SIMULATOR}" == "1" ]]; then
  echo "  test-simulator: http://localhost:${SIMULATOR_PORT}"
fi
echo "Logs: ${LOG_DIR}"
echo
echo "Active listener PIDs:"
print_listener_pid "ai-gateway" "8081"
print_listener_pid "skill-server" "8082"
print_listener_pid "skill-miniapp" "${MINIAPP_PORT}"
if [[ "${START_TEST_SIMULATOR}" == "1" ]]; then
  print_listener_pid "test-simulator" "${SIMULATOR_PORT}"
fi
