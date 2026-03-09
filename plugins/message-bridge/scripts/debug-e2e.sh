#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PLUGIN_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"

require_cmd() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "Missing required command: $1" >&2
    exit 1
  fi
}

require_cmd bun
require_cmd curl
require_cmd opencode
require_cmd node

if [[ "${MB_SKIP_BUILD:-false}" != "true" ]]; then
  echo "[0/6] Building plugin..."
  (cd "${PLUGIN_DIR}" && npm run build >/dev/null)
fi

MB_OPENCODE_HOST="${MB_OPENCODE_HOST:-127.0.0.1}"
MB_OPENCODE_PORT="${MB_OPENCODE_PORT:-4096}"
MB_GATEWAY_PORT="${MB_GATEWAY_PORT:-8081}"
MB_LOG_LEVEL="${MB_LOG_LEVEL:-DEBUG}"
BRIDGE_AUTH_AK="${BRIDGE_AUTH_AK:-test-ak}"
BRIDGE_AUTH_SK="${BRIDGE_AUTH_SK:-test-sk}"
BRIDGE_GATEWAY_URL="${BRIDGE_GATEWAY_URL:-ws://${MB_OPENCODE_HOST}:${MB_GATEWAY_PORT}/ws/agent}"

RUN_ID="$(date +%Y%m%d-%H%M%S)"
LOG_DIR="${PLUGIN_DIR}/logs/e2e-debug-${RUN_ID}"
mkdir -p "${LOG_DIR}"

OPENCODE_LOG="${LOG_DIR}/opencode.log"
GATEWAY_LOG="${LOG_DIR}/mock-gateway.log"
SUMMARY_LOG="${LOG_DIR}/summary.log"

TMP_HOME="$(mktemp -d)"
wait_for_pattern() {
  local file="$1"
  local pattern="$2"
  local max_tries="$3"
  local tries=0
  while [[ "${tries}" -lt "${max_tries}" ]]; do
    if grep -q "${pattern}" "${file}" 2>/dev/null; then
      return 0
    fi
    sleep 0.2
    tries=$((tries + 1))
  done
  return 1
}

cleanup() {
  if [[ -n "${OPENCODE_PID:-}" ]]; then
    kill "${OPENCODE_PID}" >/dev/null 2>&1 || true
    wait "${OPENCODE_PID}" 2>/dev/null || true
  fi
  if [[ -n "${GATEWAY_PID:-}" ]]; then
    kill "${GATEWAY_PID}" >/dev/null 2>&1 || true
    wait "${GATEWAY_PID}" 2>/dev/null || true
  fi
  rm -rf "${TMP_HOME}" >/dev/null 2>&1 || true
}
trap cleanup EXIT

mkdir -p "${TMP_HOME}/.config/opencode"
cat > "${TMP_HOME}/.config/opencode/opencode.json" <<JSON
{
  "plugin": ["file://${PLUGIN_DIR}"]
}
JSON

echo "[1/6] Starting mock gateway on port ${MB_GATEWAY_PORT}..."
bun -e "
const host = '127.0.0.1';
const port = ${MB_GATEWAY_PORT};
const server = Bun.serve({
  hostname: host,
  port,
  fetch(req, wsServer) {
    const u = new URL(req.url);
    if (u.pathname === '/ws/agent' && wsServer.upgrade(req)) return;
    return new Response('mock-gateway');
  },
  websocket: {
    open() {
      console.log('[mock-gateway] ws open');
    },
    message(ws, msg) {
      try {
        const text = typeof msg === 'string' ? msg : Buffer.from(msg).toString();
        const parsed = JSON.parse(text);
        const type = parsed?.type || 'unknown';
        console.log('[mock-gateway] ' + type);
        if (type === 'register') {
          ws.send(JSON.stringify({
            type: 'invoke',
            action: 'status_query',
            payload: {},
            envelope: { sessionId: 'mb-e2e-session' }
          }));
        }
      } catch {
        console.log('[mock-gateway] raw');
      }
    },
    close() {
      console.log('[mock-gateway] ws close');
    }
  }
});
console.log('[mock-gateway] listening on ' + host + ':' + port);
await new Promise(() => {});
" >"${GATEWAY_LOG}" 2>&1 &
GATEWAY_PID=$!

if ! wait_for_pattern "${GATEWAY_LOG}" "listening on" 30; then
  echo "Mock gateway failed to start. Check ${GATEWAY_LOG}" >&2
  exit 1
fi

echo "[2/6] Starting opencode serve on ${MB_OPENCODE_HOST}:${MB_OPENCODE_PORT}..."
HOME="${TMP_HOME}" \
OPENCODE_DISABLE_DEFAULT_PLUGINS=1 \
BRIDGE_AUTH_AK="${BRIDGE_AUTH_AK}" \
BRIDGE_AUTH_SK="${BRIDGE_AUTH_SK}" \
BRIDGE_GATEWAY_URL="${BRIDGE_GATEWAY_URL}" \
BRIDGE_DEBUG="${BRIDGE_DEBUG:-true}" \
opencode serve \
  --hostname "${MB_OPENCODE_HOST}" \
  --port "${MB_OPENCODE_PORT}" \
  --print-logs \
  --log-level "${MB_LOG_LEVEL}" >"${OPENCODE_LOG}" 2>&1 &
OPENCODE_PID=$!

if ! wait_for_pattern "${OPENCODE_LOG}" "opencode server listening" 60; then
  echo "OpenCode failed to start. Check ${OPENCODE_LOG}" >&2
  exit 1
fi

echo "[3/6] Triggering session.create + prompt_async..."
SESSION_JSON="$(curl -sS -X POST "http://${MB_OPENCODE_HOST}:${MB_OPENCODE_PORT}/session" -H 'Content-Type: application/json' -d '{"title":"message-bridge-e2e-debug"}')"
SESSION_ID="$(printf '%s' "${SESSION_JSON}" | node -e "const fs=require('fs'); const raw=fs.readFileSync(0,'utf8'); const j=JSON.parse(raw); process.stdout.write(j.id||'');")"
if [[ -z "${SESSION_ID}" ]]; then
  echo "Failed to parse session id from response: ${SESSION_JSON}" >&2
  exit 1
fi

curl -sS -X POST "http://${MB_OPENCODE_HOST}:${MB_OPENCODE_PORT}/session/${SESSION_ID}/prompt_async" \
  -H 'Content-Type: application/json' \
  -d '{"parts":[{"type":"text","text":"E2E verify message-bridge logging"}],"noReply":true}' >/dev/null

sleep 2

echo "[4/6] Collecting evidence..."
{
  echo "=== message-bridge logs (opencode) ==="
  grep "service=message-bridge" "${OPENCODE_LOG}" || true
  echo
  echo "=== mock gateway events ==="
  cat "${GATEWAY_LOG}" || true
} > "${SUMMARY_LOG}"

echo "[5/6] Asserting critical checkpoints..."
PASS=true
grep -q "gateway.ready" "${OPENCODE_LOG}" || PASS=false
grep -q "router.route.completed" "${OPENCODE_LOG}" || PASS=false
grep -q "runtime.invoke.completed" "${OPENCODE_LOG}" || PASS=false
grep -q "\[mock-gateway\] register" "${GATEWAY_LOG}" || PASS=false
grep -q "\[mock-gateway\] tool_event" "${GATEWAY_LOG}" || PASS=false

if [[ "${PASS}" == "true" ]]; then
  echo "[6/6] Summary generated at ${SUMMARY_LOG}"
  echo "E2E PASS"
  echo "session_id=${SESSION_ID}"
  echo "logs=${LOG_DIR}"
  exit 0
fi

echo "[6/6] Summary generated at ${SUMMARY_LOG}"
echo "E2E FAIL"
echo "session_id=${SESSION_ID}"
echo "logs=${LOG_DIR}"
echo "Check ${SUMMARY_LOG}"
exit 1
