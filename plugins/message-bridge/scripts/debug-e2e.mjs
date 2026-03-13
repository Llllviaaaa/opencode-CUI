#!/usr/bin/env node
import { mkdir, readFile, rm, writeFile } from 'node:fs/promises';
import net from 'node:net';
import path from 'node:path';
import process from 'node:process';
import { createTempDir, ensureCommand, ROOT_DIR, run, runCapture, spawnLoggedProcess, waitForPattern, writeJson } from './shared.mjs';

const pluginDir = ROOT_DIR;
const env = process.env;
const skipBuild = env.MB_SKIP_BUILD === 'true';
const opencodeHost = env.MB_OPENCODE_HOST ?? '127.0.0.1';
const requestedOpencodePort = Number(env.MB_OPENCODE_PORT ?? '4096');
const requestedGatewayPort = Number(env.MB_GATEWAY_PORT ?? '8081');
const logLevel = env.MB_LOG_LEVEL ?? 'DEBUG';
const bridgeAuthAk = env.BRIDGE_AUTH_AK ?? 'test-ak';
const bridgeAuthSk = env.BRIDGE_AUTH_SK ?? 'test-sk';
const runId = new Date().toISOString().replace(/[-:TZ.]/g, '').slice(0, 14);
const logDir = path.join(pluginDir, 'logs', `e2e-debug-${runId}`);
const opencodeLog = path.join(logDir, 'opencode.log');
const gatewayLog = path.join(logDir, 'mock-gateway.log');
const summaryLog = path.join(logDir, 'summary.log');

let tmpHome = '';
let gatewayProc;
let opencodeProc;

async function resolvePort(preferredPort) {
  const candidate = await findAvailablePort(preferredPort);
  if (candidate !== preferredPort) {
    console.log(`[port] ${preferredPort} is busy, using ${candidate} instead`);
  }
  return candidate;
}

function findAvailablePort(port) {
  return new Promise((resolve, reject) => {
    const server = net.createServer();
    server.unref();
    server.on('error', () => {
      if (port === 0) {
        reject(new Error('Unable to find an available port'));
        return;
      }
      resolve(findAvailablePort(0));
    });
    server.listen(port, '127.0.0.1', () => {
      const address = server.address();
      const resolvedPort = typeof address === 'object' && address ? address.port : port;
      server.close((err) => {
        if (err) reject(err);
        else resolve(resolvedPort);
      });
    });
  });
}

async function cleanup() {
  for (const proc of [opencodeProc, gatewayProc]) {
    if (!proc || proc.killed) continue;
    proc.kill();
  }
  await rm(tmpHome, { recursive: true, force: true }).catch(() => {});
}

process.on('SIGINT', async () => {
  await cleanup();
  process.exit(130);
});
process.on('SIGTERM', async () => {
  await cleanup();
  process.exit(143);
});

async function main() {
  ensureCommand('bun');
  ensureCommand('curl');
  ensureCommand('opencode');
  ensureCommand('node');

  const opencodePort = await resolvePort(requestedOpencodePort);
  const gatewayPort = await resolvePort(requestedGatewayPort);
  const bridgeGatewayUrl = env.BRIDGE_GATEWAY_URL ?? `ws://${opencodeHost}:${gatewayPort}/ws/agent`;

  if (!skipBuild) {
    console.log('[0/6] Building plugin...');
    await run(process.execPath, ['./scripts/build.mjs'], { cwd: pluginDir, stdio: 'ignore' });
  }

  tmpHome = await createTempDir('mb-debug-home-');
  await mkdir(logDir, { recursive: true });
  await mkdir(path.join(tmpHome, '.config', 'opencode'), { recursive: true });
  await writeJson(path.join(tmpHome, '.config', 'opencode', 'opencode.json'), {
    plugin: [`file://${pluginDir}`],
  });

  console.log(`[1/6] Starting mock gateway on port ${gatewayPort}...`);
  const gatewayScript = `
const host = '127.0.0.1';
const port = ${JSON.stringify(Number(gatewayPort))};
const server = Bun.serve({
  hostname: host,
  port,
  fetch(req, wsServer) {
    const u = new URL(req.url);
    if (u.pathname === '/ws/agent' && wsServer.upgrade(req)) return;
    return new Response('mock-gateway');
  },
  websocket: {
    open() { console.log('[mock-gateway] ws open'); },
    message(ws, msg) {
      try {
        const text = typeof msg === 'string' ? msg : Buffer.from(msg).toString();
        const parsed = JSON.parse(text);
        const type = parsed?.type || 'unknown';
        console.log('[mock-gateway] ' + type);
        if (type === 'register') {
          ws.send(JSON.stringify({ type: 'register_ok' }));
          setTimeout(() => {
            ws.send(JSON.stringify({ type: 'status_query' }));
          }, 50);
        }
      } catch {
        console.log('[mock-gateway] raw');
      }
    },
    close() { console.log('[mock-gateway] ws close'); }
  }
});
console.log('[mock-gateway] listening on ' + host + ':' + port);
await new Promise(() => {});
`;
  gatewayProc = spawnLoggedProcess('bun', ['-e', gatewayScript], gatewayLog);
  if (!(await waitForPattern(gatewayLog, /listening on/, 30))) {
    throw new Error(`Mock gateway failed to start. Check ${gatewayLog}`);
  }

  console.log(`[2/6] Starting opencode serve on ${opencodeHost}:${opencodePort}...`);
  opencodeProc = spawnLoggedProcess(
    'opencode',
    ['serve', '--hostname', opencodeHost, '--port', opencodePort, '--print-logs', '--log-level', logLevel],
    opencodeLog,
    {
      env: {
        HOME: tmpHome,
        OPENCODE_DISABLE_DEFAULT_PLUGINS: '1',
        BRIDGE_AUTH_AK: bridgeAuthAk,
        BRIDGE_AUTH_SK: bridgeAuthSk,
        BRIDGE_GATEWAY_URL: bridgeGatewayUrl,
        BRIDGE_DEBUG: env.BRIDGE_DEBUG ?? 'true',
      },
    },
  );
  if (!(await waitForPattern(opencodeLog, /opencode server listening/, 60))) {
    throw new Error(`OpenCode failed to start. Check ${opencodeLog}`);
  }

  console.log('[3/6] Triggering session.create + prompt_async...');
  const { stdout: sessionJson } = await runCapture(
    'curl',
    ['-sS', '-X', 'POST', `http://${opencodeHost}:${opencodePort}/session`, '-H', 'Content-Type: application/json', '-d', '{"title":"message-bridge-e2e-debug"}'],
  );
  const sessionId = JSON.parse(sessionJson).id;
  if (!sessionId) {
    throw new Error(`Failed to parse session id from response: ${sessionJson}`);
  }

  await run('curl', [
    '-sS',
    '-X',
    'POST',
    `http://${opencodeHost}:${opencodePort}/session/${sessionId}/prompt_async`,
    '-H',
    'Content-Type: application/json',
    '-d',
    '{"parts":[{"type":"text","text":"E2E verify message-bridge logging"}],"noReply":true}',
  ], { stdio: 'ignore' });

  await new Promise((resolve) => setTimeout(resolve, 2000));

  console.log('[4/6] Collecting evidence...');
  const opencodeLogText = await readFile(opencodeLog, 'utf8').catch(() => '');
  const gatewayLogText = await readFile(gatewayLog, 'utf8').catch(() => '');
  const summary = [
    '=== message-bridge logs (opencode) ===',
    ...opencodeLogText.split('\n').filter((line) => line.includes('service=message-bridge')),
    '',
    '=== mock gateway events ===',
    gatewayLogText,
  ].join('\n');
  await mkdir(path.dirname(summaryLog), { recursive: true });
  await writeFile(summaryLog, summary, 'utf8');

  console.log('[5/6] Asserting critical checkpoints...');
  const pass =
    opencodeLogText.includes('gateway.ready') &&
    gatewayLogText.includes('[mock-gateway] register') &&
    gatewayLogText.includes('[mock-gateway] status_response') &&
    gatewayLogText.includes('[mock-gateway] tool_event');

  console.log(`[6/6] Summary generated at ${summaryLog}`);
  console.log(pass ? 'E2E PASS' : 'E2E FAIL');
  console.log(`session_id=${sessionId}`);
  console.log(`logs=${logDir}`);
  if (!pass) {
    throw new Error(`Check ${summaryLog}`);
  }
}

main()
  .finally(cleanup)
  .catch((err) => {
    console.error(err instanceof Error ? err.message : String(err));
    process.exit(1);
  });
