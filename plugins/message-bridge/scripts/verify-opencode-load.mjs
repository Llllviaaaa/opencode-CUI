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
const summaryJson = path.join(logDir, 'summary.json');
const requestedGatewayPort = Number(process.env.MB_GATEWAY_PORT ?? '18081');
const timeoutMs = Number(process.env.MB_LOAD_VERIFY_TIMEOUT_MS ?? '300000');

let tmpHome = '';
let tmpWorkspace = '';
let gatewayProc;
let opencodeProc;
let resolvedGatewayUrl = '';
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
        reject(createFailure('ENV_PORT_UNAVAILABLE', 'Unable to find an available port'));
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

function createFailure(failureCategory, message, failureCode = failureCategory) {
  const error = new Error(message);
  error.failureCategory = failureCategory;
  error.failureCode = failureCode;
  return error;
}

async function withTimeout(task, ms, label, category, timeoutCode) {
  let timer = null;
  try {
    return await Promise.race([
      task(),
      new Promise((_, reject) => {
        timer = setTimeout(() => {
          reject(createFailure(category, `${label} timed out after ${ms}ms`, timeoutCode));
        }, ms);
      }),
    ]);
  } finally {
    if (timer) clearTimeout(timer);
  }
}

async function writeSummary(failureCategory = 'NONE', failureCode = 'NONE', failureMessage = '') {
  await mkdir(logDir, { recursive: true });
  const opencodeLogText = await readFile(opencodeLog, 'utf8').catch(() => '');
  const summary = [
    '=== verify-opencode-load summary ===',
    `failure_category=${failureCategory}`,
    `failure_code=${failureCode}`,
    `failure_message=${failureMessage}`,
    `plugin_ref=${pluginRef}`,
    `workspace=${tmpWorkspace}`,
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
  await writeFile(
    summaryJson,
    `${JSON.stringify({
      failure_category: failureCategory,
      failure_code: failureCode,
      failure_message: failureMessage,
      plugin_ref: pluginRef,
      workspace: tmpWorkspace,
      gateway_url: resolvedGatewayUrl,
      opencode_log: opencodeLog,
      gateway_log: gatewayLog,
      summary_log: summaryLog,
      generated_at: new Date().toISOString(),
    }, null, 2)}\n`,
    'utf8',
  );
}

async function main() {
  for (const cmd of ['node', 'opencode']) {
    try {
      ensureCommand(cmd);
    } catch {
      throw createFailure('ENV_MISSING_CMD', `Missing required command: ${cmd}`);
    }
  }

  const gatewayPort = await resolvePort(requestedGatewayPort);
  const bridgeGatewayUrl = `ws://127.0.0.1:${gatewayPort}/ws/agent`;
  resolvedGatewayUrl = bridgeGatewayUrl;

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
  gatewayProc = spawnLoggedProcess(process.execPath, ['./scripts/mock-gateway-server.mjs', String(gatewayPort)], gatewayLog);
  if (!(await waitForPattern(gatewayLog, /listening on/, 50))) {
    throw createFailure('LOAD_VERIFICATION_FAILED', `Mock gateway failed to start. Check ${gatewayLog}`);
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
    throw createFailure('LOAD_VERIFICATION_FAILED', `Plugin package-root loading log not found. Check ${opencodeLog}`);
  }

  console.log('[6/7] Validating load result...');
  const initialized = await waitForPattern(opencodeLog, /service=message-bridge.*runtime\.singleton\.initialized/, 80);
  const failed = await waitForPattern(opencodeLog, new RegExp(`failed to load plugin.*${escapedPluginRef}|${escapedPluginRef}.*failed to load plugin`), 1);
  if (failed || !initialized) {
    throw createFailure('LOAD_VERIFICATION_FAILED', `Plugin initialization failed. Check ${opencodeLog}`);
  }

  opencodeProc.kill();
  await writeSummary();

  console.log('[7/7] OpenCode package-load verification passed');
  console.log(`summary=${summaryLog}`);
  console.log(`summary_json=${summaryJson}`);
  console.log(`logs=${logDir}`);
}

withTimeout(
  () => main(),
  timeoutMs,
  'verify-opencode-load',
  'LOAD_VERIFICATION_FAILED',
  'LOAD_TIMEOUT',
)
  .catch(async (err) => {
    const failureCategory = err && typeof err === 'object' && 'failureCategory' in err
      ? err.failureCategory
      : 'LOAD_VERIFICATION_FAILED';
    const failureCode = err && typeof err === 'object' && 'failureCode' in err
      ? err.failureCode
      : failureCategory;
    const failureMessage = err instanceof Error ? err.message : String(err);
    await writeSummary(failureCategory, failureCode, failureMessage).catch(() => {});
    console.error(`failure_category=${failureCategory}`);
    console.error(`failure_code=${failureCode}`);
    console.error(failureMessage);
    process.exit(1);
  })
  .finally(cleanup);
