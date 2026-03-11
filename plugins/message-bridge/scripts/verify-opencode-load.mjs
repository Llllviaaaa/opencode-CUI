#!/usr/bin/env node
import { mkdir, readFile, rm, writeFile } from 'node:fs/promises';
import net from 'node:net';
import path from 'node:path';
import process from 'node:process';
import { createTempDir, ensureCommand, ROOT_DIR, run, spawnLoggedProcess, waitForPattern, writeJson } from './shared.mjs';

const pluginDir = ROOT_DIR;
const pluginRef = `file://${pluginDir}`;
const runId = new Date().toISOString().replace(/[-:TZ.]/g, '').slice(0, 14);
const logDir = path.join(pluginDir, 'logs', `opencode-load-verify-${runId}`);
const opencodeLog = path.join(logDir, 'opencode.log');
const gatewayLog = path.join(logDir, 'mock-gateway.log');
const summaryLog = path.join(logDir, 'summary.log');
const requestedGatewayPort = Number(process.env.MB_GATEWAY_PORT ?? '18081');

let tmpHome = '';
let tmpWorkspace = '';
let gatewayProc;
let opencodeProc;
const escapedPluginRef = pluginRef.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');

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
  await rm(tmpWorkspace, { recursive: true, force: true }).catch(() => {});
}

async function main() {
  ensureCommand('node');
  ensureCommand('opencode');
  ensureCommand('bun');

  const gatewayPort = await resolvePort(requestedGatewayPort);
  const bridgeGatewayUrl = `ws://127.0.0.1:${gatewayPort}/ws/agent`;

  await mkdir(logDir, { recursive: true });
  console.log('[1/7] Building plugin artifacts...');
  await run(process.execPath, ['./scripts/build.mjs'], { cwd: pluginDir, stdio: 'ignore' });

  console.log('[2/7] Preparing isolated OpenCode home and workspace...');
  tmpHome = await createTempDir('mb-verify-home-');
  tmpWorkspace = await createTempDir('mb-verify-workspace-');
  await writeFile(path.join(tmpWorkspace, 'README.md'), '# verify workspace\n', 'utf8');
  await writeJson(path.join(tmpHome, '.config', 'opencode', 'opencode.json'), {
    $schema: 'https://opencode.ai/config.json',
    plugin: [pluginRef],
  });

  console.log('[3/7] Starting mock gateway...');
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
    message(_ws, msg) {
      try {
        const text = typeof msg === 'string' ? msg : Buffer.from(msg).toString();
        const parsed = JSON.parse(text);
        console.log('[mock-gateway] ' + (parsed?.type || 'unknown'));
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
  if (!(await waitForPattern(gatewayLog, /listening on/, 50))) {
    throw new Error(`Mock gateway failed to start. Check ${gatewayLog}`);
  }

  console.log('[4/7] Starting opencode run with package-root plugin...');
  opencodeProc = spawnLoggedProcess(
    'opencode',
    ['run', 'plugin load verify', '--print-logs', '--log-level', 'DEBUG', '--agent', 'build'],
    opencodeLog,
    {
      cwd: tmpWorkspace,
      env: {
        HOME: tmpHome,
        OPENCODE_DISABLE_DEFAULT_PLUGINS: '1',
        BRIDGE_ENABLED: 'true',
        BRIDGE_AUTH_AK: 'verify-ak',
        BRIDGE_AUTH_SK: 'verify-sk',
        BRIDGE_GATEWAY_URL: bridgeGatewayUrl,
      },
    },
  );

  console.log('[5/7] Waiting for package-root load logs...');
  if (!(await waitForPattern(opencodeLog, new RegExp(`${escapedPluginRef}|runtime\\.singleton\\.(initialized|initialization_failed)`), 120))) {
    throw new Error(`Plugin package-root loading log not found. Check ${opencodeLog}`);
  }

  console.log('[6/7] Validating load result...');
  const initialized = await waitForPattern(opencodeLog, /service=message-bridge.*runtime\.singleton\.initialized/, 80);
  const failed = await waitForPattern(opencodeLog, new RegExp(`failed to load plugin.*${escapedPluginRef}|${escapedPluginRef}.*failed to load plugin`), 1);
  if (failed || !initialized) {
    throw new Error(`Plugin initialization failed. Check ${opencodeLog}`);
  }

  opencodeProc.kill();
  const opencodeLogText = await readFile(opencodeLog, 'utf8');
  const summary = [
    '=== verify-opencode-load summary ===',
    `plugin_ref=${pluginRef}`,
    `workspace=${tmpWorkspace}`,
    `gateway_url=${bridgeGatewayUrl}`,
    `log=${opencodeLog}`,
    `gateway_log=${gatewayLog}`,
    '',
    '--- matching load lines ---',
    ...opencodeLogText.split('\n').filter((line) => line.includes(pluginRef) || /runtime\.singleton\.(initialized|initialization_failed)/.test(line)),
    '',
    '--- runtime singleton lines ---',
    ...opencodeLogText.split('\n').filter((line) => /service=message-bridge.*runtime\.singleton\.(initialized|initialization_failed)/.test(line)),
  ].join('\n');
  await writeFile(summaryLog, summary, 'utf8');

  console.log('[7/7] OpenCode package-load verification passed');
  console.log(`summary=${summaryLog}`);
  console.log(`logs=${logDir}`);
}

main()
  .finally(cleanup)
  .catch((err) => {
    console.error(err instanceof Error ? err.message : String(err));
    process.exit(1);
  });
