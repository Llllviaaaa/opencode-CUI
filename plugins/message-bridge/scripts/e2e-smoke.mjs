#!/usr/bin/env node
import { mkdir, readFile, rm, writeFile } from 'node:fs/promises';
import net from 'node:net';
import path from 'node:path';
import process from 'node:process';

import {
  createTempDir,
  ensureCommand,
  ROOT_DIR,
  run,
  runCapture,
  spawnLoggedProcess,
  waitForPattern,
  writeJson,
} from './shared.mjs';

const pluginDir = ROOT_DIR;
const env = process.env;
const scenario = env.MB_SCENARIO ?? 'connect-register';
const skipBuild = env.MB_SKIP_BUILD === 'true';
const opencodeHost = env.MB_OPENCODE_HOST ?? '127.0.0.1';
const requestedOpencodePort = Number(env.MB_OPENCODE_PORT ?? '4096');
const requestedGatewayPort = Number(env.MB_GATEWAY_PORT ?? '8081');
const logLevel = env.MB_LOG_LEVEL ?? 'DEBUG';
const bridgeAuthAk = env.BRIDGE_AUTH_AK ?? 'test-ak';
const bridgeAuthSk = env.BRIDGE_AUTH_SK ?? 'test-sk';
const promptText = env.MB_PROMPT_TEXT ?? 'E2E verify message-bridge protocol smoke';
const opencodePermission =
  env.OPENCODE_PERMISSION ?? '{"bash":"ask","edit":"ask","external_directory":"ask"}';
const runId = new Date().toISOString().replace(/[-:TZ.]/g, '').slice(0, 14);
const logDir = path.join(pluginDir, 'logs', `e2e-smoke-${scenario}-${runId}`);
const opencodeLog = path.join(logDir, 'opencode.log');
const gatewayLog = path.join(logDir, 'mock-gateway.log');
const summaryLog = path.join(logDir, 'summary.log');
const summaryJson = path.join(logDir, 'summary.json');
const buildLockDir = path.join(pluginDir, '.tmp', 'e2e-build.lock');
const timeoutMs = Number(env.MB_E2E_TIMEOUT_MS ?? '300000');

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
}

process.on('SIGINT', async () => {
  await cleanup();
  process.exit(130);
});
process.on('SIGTERM', async () => {
  await cleanup();
  process.exit(143);
});

async function sleep(ms) {
  await new Promise((resolve) => setTimeout(resolve, ms));
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

async function withBuildLock(task) {
  await mkdir(path.dirname(buildLockDir), { recursive: true });
  while (true) {
    try {
      await mkdir(buildLockDir, { recursive: false });
      break;
    } catch (error) {
      if (error && typeof error === 'object' && 'code' in error && error.code === 'EEXIST') {
        await sleep(200);
        continue;
      }
      throw error;
    }
  }

  try {
    return await task();
  } finally {
    await rm(buildLockDir, { recursive: true, force: true }).catch(() => {});
  }
}

async function startStack() {
  for (const cmd of ['curl', 'opencode', 'node']) {
    try {
      ensureCommand(cmd);
    } catch {
      throw createFailure('ENV_MISSING_CMD', `Missing required command: ${cmd}`);
    }
  }

  const opencodePort = await resolvePort(requestedOpencodePort);
  const gatewayPort = await resolvePort(requestedGatewayPort);
  const bridgeGatewayUrl = env.BRIDGE_GATEWAY_URL ?? `ws://${opencodeHost}:${gatewayPort}/ws/agent`;

  if (!skipBuild) {
    console.log('[0/5] Building plugin...');
    await withBuildLock(async () => {
      await run(process.execPath, ['./scripts/build.mjs'], { cwd: pluginDir, stdio: 'ignore' });
    });
  }

  tmpHome = await createTempDir('mb-smoke-home-');
  await mkdir(logDir, { recursive: true });
  await mkdir(path.join(tmpHome, '.config', 'opencode'), { recursive: true });
  await writeJson(path.join(tmpHome, '.config', 'opencode', 'opencode.json'), {
    plugin: [`file://${pluginDir}`],
  });

  console.log(`[1/5] Starting mock gateway on port ${gatewayPort}...`);
  gatewayProc = spawnLoggedProcess(process.execPath, ['./scripts/mock-gateway-server.mjs', String(gatewayPort), scenario], gatewayLog);
  if (!(await waitForPattern(gatewayLog, /listening on/, 30))) {
    throw createFailure('E2E_SCENARIO_FAILED', `Mock gateway failed to start. Check ${gatewayLog}`);
  }

  console.log(`[2/5] Starting opencode serve on ${opencodeHost}:${opencodePort}...`);
  opencodeProc = spawnLoggedProcess(
    'opencode',
    ['serve', '--hostname', opencodeHost, '--port', opencodePort, '--print-logs', '--log-level', logLevel],
    opencodeLog,
    {
      env: {
        HOME: tmpHome,
        OPENCODE_DISABLE_DEFAULT_PLUGINS: '1',
        BRIDGE_ENABLED: 'true',
        BRIDGE_AUTH_AK: bridgeAuthAk,
        BRIDGE_AUTH_SK: bridgeAuthSk,
        BRIDGE_GATEWAY_URL: bridgeGatewayUrl,
        BRIDGE_DEBUG: env.BRIDGE_DEBUG ?? 'true',
        OPENCODE_PERMISSION: opencodePermission,
      },
    },
  );
  if (!(await waitForPattern(opencodeLog, /opencode server listening/, 60))) {
    throw createFailure('E2E_SCENARIO_FAILED', `OpenCode failed to start. Check ${opencodeLog}`);
  }

  return { opencodePort };
}

async function triggerChatFlow(opencodePort) {
  console.log('[3/5] Triggering session.create + prompt_async...');
  const { stdout: sessionJson } = await runCapture(
    'curl',
    [
      '-sS',
      '-X',
      'POST',
      `http://${opencodeHost}:${opencodePort}/session`,
      '-H',
      'Content-Type: application/json',
      '-d',
      '{"title":"message-bridge-e2e-smoke"}',
    ],
  );
  const sessionId = JSON.parse(sessionJson).id;
  if (!sessionId) {
    throw createFailure('E2E_SCENARIO_FAILED', `Failed to parse session id from response: ${sessionJson}`);
  }

  await run(
    'curl',
    [
      '-sS',
      '-X',
      'POST',
      `http://${opencodeHost}:${opencodePort}/session/${sessionId}/prompt_async`,
      '-H',
      'Content-Type: application/json',
      '-d',
      JSON.stringify({
        parts: [{ type: 'text', text: promptText }],
        noReply: scenario === 'connect-register',
      }),
    ],
    { stdio: 'ignore' },
  );

  return { sessionId };
}

function assertScenario(scenarioName, opencodeLogText, gatewayLogText) {
  const baseChecks =
    opencodeLogText.includes('gateway.ready') &&
    gatewayLogText.includes('[mock-gateway] register') &&
    gatewayLogText.includes('[mock-gateway] status_response');

  if (!baseChecks) {
    return false;
  }

  if (scenarioName === 'connect-register') {
    return true;
  }

  if (scenarioName === 'chat-stream') {
    return (
      gatewayLogText.includes('[mock-gateway] tool_event:message.updated') &&
      gatewayLogText.includes('[mock-gateway] tool_event:message.part.updated')
    );
  }

  if (scenarioName === 'permission-roundtrip') {
    return (
      gatewayLogText.includes('[mock-gateway] tool_event:permission.asked') &&
      gatewayLogText.includes('[mock-gateway] invoke:permission_reply') &&
      opencodeLogText.includes('action.permission_reply.started')
    );
  }

  throw new Error(`Unsupported scenario: ${scenarioName}`);
}

async function collectEvidence(failureCategory = 'NONE', failureCode = 'NONE', failureMessage = '') {
  const opencodeLogText = await readFile(opencodeLog, 'utf8').catch(() => '');
  const gatewayLogText = await readFile(gatewayLog, 'utf8').catch(() => '');
  const summary = [
    `=== scenario: ${scenario} ===`,
    `failure_category=${failureCategory}`,
    `failure_code=${failureCode}`,
    `failure_message=${failureMessage}`,
    '',
    '=== message-bridge logs (opencode) ===',
    ...opencodeLogText.split('\n').filter((line) => line.includes('service=message-bridge')),
    '',
    '=== mock gateway events ===',
    gatewayLogText,
  ].join('\n');
  await mkdir(path.dirname(summaryLog), { recursive: true });
  await writeFile(summaryLog, summary, 'utf8');
  await writeFile(
    summaryJson,
    `${JSON.stringify({
      scenario,
      failure_category: failureCategory,
      failure_code: failureCode,
      failure_message: failureMessage,
      opencode_log: opencodeLog,
      gateway_log: gatewayLog,
      summary_log: summaryLog,
      generated_at: new Date().toISOString(),
    }, null, 2)}\n`,
    'utf8',
  );
  return { opencodeLogText, gatewayLogText };
}

async function main() {
  const { opencodePort } = await withTimeout(
    () => startStack(),
    timeoutMs,
    'startStack',
    'E2E_SCENARIO_FAILED',
    'E2E_TIMEOUT',
  );
  let sessionId = null;

  const result = await withTimeout(
    () => triggerChatFlow(opencodePort),
    timeoutMs,
    'triggerChatFlow',
    'E2E_SCENARIO_FAILED',
    'E2E_TIMEOUT',
  );
  sessionId = result.sessionId;
  console.log('[3/5] Waiting for protocol activity...');
  await sleep(scenario === 'chat-stream' ? 2000 : scenario === 'permission-roundtrip' ? 8000 : 1200);

  console.log('[4/5] Collecting evidence...');
  const { opencodeLogText, gatewayLogText } = await collectEvidence();

  console.log('[5/5] Asserting scenario...');
  const pass = assertScenario(scenario, opencodeLogText, gatewayLogText);
  console.log(pass ? 'E2E PASS' : 'E2E FAIL');
  console.log(`scenario=${scenario}`);
  console.log(`session_id=${sessionId ?? ''}`);
  console.log(`logs=${logDir}`);
  if (!pass) {
    throw createFailure('E2E_SCENARIO_FAILED', `Check ${summaryLog}`);
  }
}

withTimeout(
  () => main(),
  timeoutMs,
  'e2e-smoke',
  'E2E_SCENARIO_FAILED',
  'E2E_TIMEOUT',
)
  .catch(async (err) => {
    const failureCategory = err && typeof err === 'object' && 'failureCategory' in err
      ? err.failureCategory
      : 'E2E_SCENARIO_FAILED';
    const failureCode = err && typeof err === 'object' && 'failureCode' in err
      ? err.failureCode
      : failureCategory;
    const failureMessage = err instanceof Error ? err.message : String(err);
    await collectEvidence(failureCategory, failureCode, failureMessage).catch(() => {});
    console.error(`failure_category=${failureCategory}`);
    console.error(`failure_code=${failureCode}`);
    console.error(failureMessage);
    process.exit(1);
  })
  .finally(cleanup);
