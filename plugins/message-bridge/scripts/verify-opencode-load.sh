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

require_cmd node
require_cmd npm
require_cmd opencode
require_cmd bun

run_id="$(date +%Y%m%d-%H%M%S)"
log_dir="${PLUGIN_DIR}/logs/opencode-load-verify-${run_id}"
mkdir -p "${log_dir}"

opencode_log="${log_dir}/opencode.log"
gateway_log="${log_dir}/mock-gateway.log"
summary_log="${log_dir}/summary.log"

tmp_home="$(mktemp -d)"
tmp_workspace="$(mktemp -d)"
cleanup() {
  if [[ -n "${OPENCODE_PID:-}" ]]; then
    kill "${OPENCODE_PID}" >/dev/null 2>&1 || true
    wait "${OPENCODE_PID}" 2>/dev/null || true
  fi
  if [[ -n "${GATEWAY_PID:-}" ]]; then
    kill "${GATEWAY_PID}" >/dev/null 2>&1 || true
    wait "${GATEWAY_PID}" 2>/dev/null || true
  fi
  rm -rf "${tmp_home}" >/dev/null 2>&1 || true
  rm -rf "${tmp_workspace}" >/dev/null 2>&1 || true
}
trap cleanup EXIT

wait_for_pattern() {
  local file="$1"
  local pattern="$2"
  local max_tries="$3"
  local tries=0

  while [[ "${tries}" -lt "${max_tries}" ]]; do
    if grep -E -q "${pattern}" "${file}" 2>/dev/null; then
      return 0
    fi
    sleep 0.2
    tries=$((tries + 1))
  done
  return 1
}

echo "[1/6] Building single-file plugin artifact..."
(cd "${PLUGIN_DIR}" && npm run build:plugin >/dev/null)

artifact="${PLUGIN_DIR}/release/message-bridge.plugin.js"
if [[ ! -f "${artifact}" ]]; then
  echo "Artifact missing: ${artifact}" >&2
  exit 1
fi

echo "[2/6] Preparing isolated OpenCode home..."
mkdir -p "${tmp_home}/.config/opencode/plugins"
cp "${artifact}" "${tmp_home}/.config/opencode/plugins/message-bridge.plugin.js"
cat > "${tmp_workspace}/README.md" <<'MD'
# verify workspace
MD

cat > "${tmp_home}/.config/opencode/opencode.json" <<JSON
{
  "\$schema": "https://opencode.ai/config.json",
  "plugin": []
}
JSON

MB_GATEWAY_PORT="${MB_GATEWAY_PORT:-18081}"
BRIDGE_GATEWAY_URL="ws://127.0.0.1:${MB_GATEWAY_PORT}/ws/agent"

echo "[3/6] Starting mock gateway + opencode run..."
: > "${gateway_log}"
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
    message(_ws, msg) {
      try {
        const text = typeof msg === 'string' ? msg : Buffer.from(msg).toString();
        const parsed = JSON.parse(text);
        const type = parsed?.type || 'unknown';
        console.log('[mock-gateway] ' + type);
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
" >"${gateway_log}" 2>&1 &
GATEWAY_PID=$!

if ! wait_for_pattern "${gateway_log}" "listening on" 50; then
  echo "Mock gateway failed to start. Check ${gateway_log}" >&2
  exit 1
fi

: > "${opencode_log}"
(
  cd "${tmp_workspace}"
  HOME="${tmp_home}" \
  OPENCODE_DISABLE_DEFAULT_PLUGINS=1 \
  BRIDGE_ENABLED=true \
  BRIDGE_AUTH_AK=verify-ak \
  BRIDGE_AUTH_SK=verify-sk \
  BRIDGE_GATEWAY_URL="${BRIDGE_GATEWAY_URL}" \
  opencode run "plugin load verify" \
    --print-logs \
    --log-level DEBUG \
    --agent build >"${opencode_log}" 2>&1
) &
OPENCODE_PID=$!

echo "[4/6] Waiting for plugin load logs..."
if ! wait_for_pattern "${opencode_log}" "(loading plugin.*message-bridge\.plugin\.js|message-bridge\.plugin\.js.*loading plugin)" 120; then
  echo "Plugin loading log not found. Check ${opencode_log}" >&2
  exit 1
fi

echo "[5/6] Validating load result..."
for _ in $(seq 1 80); do
  if grep -E -q "(failed to load plugin.*message-bridge\.plugin\.js|message-bridge\.plugin\.js.*failed to load plugin)" "${opencode_log}"; then
    echo "Detected plugin load failure. Check ${opencode_log}" >&2
    exit 1
  fi
  if grep -E -q "service=message-bridge.*runtime\.singleton\.initialized" "${opencode_log}"; then
    break
  fi
  sleep 0.2
done

if ! grep -E -q "service=message-bridge.*runtime\.singleton\.initialized" "${opencode_log}"; then
  echo "Plugin initialized marker not found. Check ${opencode_log}" >&2
  exit 1
fi

kill "${OPENCODE_PID}" >/dev/null 2>&1 || true
wait "${OPENCODE_PID}" 2>/dev/null || true
unset OPENCODE_PID

{
  echo "=== verify-opencode-load summary ==="
  echo "artifact=${artifact}"
  echo "log=${opencode_log}"
  echo "gateway_log=${gateway_log}"
  echo "workspace=${tmp_workspace}"
  echo
  echo "--- matching load lines ---"
  grep -E "loading plugin.*message-bridge\.plugin\.js|message-bridge\.plugin\.js.*loading plugin|failed to load plugin.*message-bridge\.plugin\.js|message-bridge\.plugin\.js.*failed to load plugin" "${opencode_log}" || true
  echo
  echo "--- runtime singleton lines ---"
  grep -E "service=message-bridge.*runtime\.singleton\.(initialized|initialization_failed)" "${opencode_log}" || true
} > "${summary_log}"

echo "[6/6] OpenCode load verification passed"
echo "summary=${summary_log}"
echo "logs=${log_dir}"
