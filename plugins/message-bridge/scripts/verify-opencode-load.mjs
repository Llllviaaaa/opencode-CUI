#!/usr/bin/env node
import { access, copyFile, mkdir, readFile, rm, writeFile } from 'node:fs/promises';
import { constants } from 'node:fs';
import path from 'node:path';
import process from 'node:process';
import { createTempDir, ensureCommand, ROOT_DIR, run, spawnLoggedProcess, waitForPattern, writeJson } from './shared.mjs';

const pluginDir = ROOT_DIR;
const runId = new Date().toISOString().replace(/[-:TZ.]/g, '').slice(0, 14);
const logDir = path.join(pluginDir, 'logs', `opencode-load-verify-${runId}`);
const opencodeLog = path.join(logDir, 'opencode.log');
const gatewayLog = path.join(logDir, 'mock-gateway.log');
const summaryLog = path.join(logDir, 'summary.log');
const gatewayPort = process.env.MB_GATEWAY_PORT ?? '18081';
const bridgeGatewayUrl = `ws://127.0.0.1:${gatewayPort}/ws/agent`;

let tmpHome = '';
let tmpWorkspace = '';
let gatewayProc;
let opencodeProc;

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

  await mkdir(logDir, { recursive: true });
  console.log('[1/6] Building plugin artifacts...');
  await run(process.execPath, ['./scripts/build.mjs'], { cwd: pluginDir, stdio: 'ignore' });

  const artifact = path.join(pluginDir, 'release', 'message-bridge.plugin.js');
  await access(artifact, constants.R_OK);

  console.log('[2/6] Preparing isolated OpenCode home...');
  tmpHome = await createTempDir('mb-verify-home-');
  tmpWorkspace = await createTempDir('mb-verify-workspace-');
  await mkdir(path.join(tmpHome, '.config', 'opencode', 'plugins'), { recursive: true });
  await writeFile(path.join(tmpWorkspace, 'README.md'), '# verify workspace\n', 'utf8');
  await copyFile(artifact, path.join(tmpHome, '.config', 'opencode', 'plugins', 'message-bridge.plugin.js'));
  await writeJson(path.join(tmpHome, '.config', 'opencode', 'opencode.json'), {
    $schema: 'https://opencode.ai/config.json',
    plugin: [],
  });

  console.log('[3/6] Starting mock gateway + opencode run...');
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

  console.log('[4/6] Waiting for plugin load logs...');
  if (!(await waitForPattern(opencodeLog, /(loading plugin.*message-bridge\.plugin\.js|message-bridge\.plugin\.js.*loading plugin)/, 120))) {
    throw new Error(`Plugin loading log not found. Check ${opencodeLog}`);
  }

  console.log('[5/6] Validating load result...');
  const initialized = await waitForPattern(opencodeLog, /service=message-bridge.*runtime\.singleton\.initialized/, 80);
  const failed = await waitForPattern(opencodeLog, /(failed to load plugin.*message-bridge\.plugin\.js|message-bridge\.plugin\.js.*failed to load plugin)/, 1);
  if (failed || !initialized) {
    throw new Error(`Plugin initialization failed. Check ${opencodeLog}`);
  }

  opencodeProc.kill();
  const opencodeLogText = await readFile(opencodeLog, 'utf8');
  const summary = [
    '=== verify-opencode-load summary ===',
    `artifact=${artifact}`,
    `log=${opencodeLog}`,
    `gateway_log=${gatewayLog}`,
    `workspace=${tmpWorkspace}`,
    '',
    '--- matching load lines ---',
    ...opencodeLogText.split('\n').filter((line) => /loading plugin.*message-bridge\.plugin\.js|message-bridge\.plugin\.js.*loading plugin|failed to load plugin.*message-bridge\.plugin\.js|message-bridge\.plugin\.js.*failed to load plugin/.test(line)),
    '',
    '--- runtime singleton lines ---',
    ...opencodeLogText.split('\n').filter((line) => /service=message-bridge.*runtime\.singleton\.(initialized|initialization_failed)/.test(line)),
  ].join('\n');
  await writeFile(summaryLog, summary, 'utf8');

  console.log('[6/6] OpenCode load verification passed');
  console.log(`summary=${summaryLog}`);
  console.log(`logs=${logDir}`);
}

main()
  .finally(cleanup)
  .catch((err) => {
    console.error(err instanceof Error ? err.message : String(err));
    process.exit(1);
  });
