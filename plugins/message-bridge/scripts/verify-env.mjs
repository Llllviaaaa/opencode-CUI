#!/usr/bin/env node
import { mkdir, writeFile } from 'node:fs/promises';
import net from 'node:net';
import path from 'node:path';
import process from 'node:process';
import { spawnSync } from 'node:child_process';

import { ROOT_DIR, ensureCommand } from './shared.mjs';

const env = process.env;
const runId = new Date().toISOString().replace(/[-:TZ.]/g, '').slice(0, 14);
const logDir = path.join(ROOT_DIR, 'logs');
const summaryPath = path.join(logDir, `verify-env-${runId}.json`);

const minVersions = {
  node: env.MB_MIN_NODE_VERSION ?? '24.0.0',
  pnpm: env.MB_MIN_PNPM_VERSION ?? '9.15.0',
  opencode: env.MB_MIN_OPENCODE_VERSION ?? '0.0.0',
};

const requestedPorts = {
  opencode: Number(env.MB_OPENCODE_PORT ?? '4096'),
  gateway: Number(env.MB_GATEWAY_PORT ?? '8081'),
};

function toVersion(text) {
  const match = String(text).match(/(\d+)\.(\d+)\.(\d+)/);
  if (!match) return null;
  return [Number(match[1]), Number(match[2]), Number(match[3])];
}

function compareVersion(a, b) {
  for (let i = 0; i < 3; i += 1) {
    if (a[i] > b[i]) return 1;
    if (a[i] < b[i]) return -1;
  }
  return 0;
}

function readCommandVersion(cmd, args = ['--version']) {
  const result = spawnSync(cmd, args, { encoding: 'utf8' });
  if (result.status !== 0) {
    throw new Error(`${cmd} ${args.join(' ')} failed`);
  }
  return (result.stdout || result.stderr || '').trim();
}

async function probePort(port) {
  return new Promise((resolve) => {
    const server = net.createServer();
    server.unref();
    server.once('error', () => resolve(false));
    server.listen(port, '127.0.0.1', () => {
      server.close(() => resolve(true));
    });
  });
}

async function main() {
  await mkdir(logDir, { recursive: true });

const summary = {
    generatedAt: new Date().toISOString(),
    failure_category: 'NONE',
    failure_code: 'NONE',
    checks: {
      commands: [],
      versions: {},
      ports: {},
      env: {},
    },
    warnings: [],
  };

  try {
    for (const cmd of ['node', 'pnpm', 'opencode', 'curl']) {
      try {
        ensureCommand(cmd);
        summary.checks.commands.push({ command: cmd, status: 'ok' });
      } catch {
        summary.checks.commands.push({ command: cmd, status: 'missing' });
      }
    }

    const missing = summary.checks.commands.filter((item) => item.status === 'missing');
    if (missing.length > 0) {
      summary.failure_category = 'ENV_MISSING_CMD';
      summary.failure_code = 'ENV_MISSING_CMD';
      summary.message = `Missing commands: ${missing.map((item) => item.command).join(', ')}`;
      throw new Error(summary.message);
    }

    const currentVersions = {
      node: process.version,
      pnpm: readCommandVersion('pnpm'),
      opencode: readCommandVersion('opencode'),
    };

    for (const [name, current] of Object.entries(currentVersions)) {
      const currentVer = toVersion(current);
      const minVer = toVersion(minVersions[name]);
      const ok = Boolean(currentVer && minVer && compareVersion(currentVer, minVer) >= 0);
      summary.checks.versions[name] = {
        current,
        min: minVersions[name],
        status: ok ? 'ok' : 'below_min',
      };
      if (!ok) {
        summary.failure_category = 'ENV_VERSION_MISMATCH';
        summary.failure_code = 'ENV_VERSION_MISMATCH';
      }
    }

    summary.checks.env = {
      BRIDGE_AUTH_AK: env.BRIDGE_AUTH_AK ? 'set' : 'optional_missing',
      BRIDGE_AUTH_SK: env.BRIDGE_AUTH_SK ? 'set' : 'optional_missing',
      BRIDGE_GATEWAY_URL: env.BRIDGE_GATEWAY_URL ? 'set' : 'optional_missing',
    };

    for (const [name, port] of Object.entries(requestedPorts)) {
      const preferredAvailable = await probePort(port);
      if (preferredAvailable) {
        summary.checks.ports[name] = { requested: port, status: 'available' };
        continue;
      }

      const fallbackAvailable = await probePort(0);
      if (fallbackAvailable) {
        summary.checks.ports[name] = { requested: port, status: 'occupied_fallback_available' };
        summary.warnings.push(`Port ${port} is occupied for ${name}, but fallback port is available`);
        continue;
      }

      summary.checks.ports[name] = { requested: port, status: 'occupied_no_fallback' };
      summary.failure_category = 'ENV_PORT_UNAVAILABLE';
      summary.failure_code = 'ENV_PORT_UNAVAILABLE';
    }

    if (summary.failure_category !== 'NONE') {
      summary.message = 'Environment checks failed';
      throw new Error(summary.message);
    }

    summary.message = 'Environment checks passed';
    console.log('[verify:env] PASS');
    if (summary.warnings.length > 0) {
      for (const warning of summary.warnings) {
        console.warn(`[verify:env] WARN ${warning}`);
      }
    }
    console.log(`summary=${summaryPath}`);
  } catch (error) {
    if (!summary.message) {
      summary.message = error instanceof Error ? error.message : String(error);
    }
    console.error('[verify:env] FAIL');
    console.error(`failure_category=${summary.failure_category}`);
    console.error(`failure_code=${summary.failure_code}`);
    console.error(summary.message);
    throw error;
  } finally {
    await writeFile(summaryPath, `${JSON.stringify(summary, null, 2)}\n`, 'utf8');
  }
}

main().catch(() => {
  process.exit(1);
});
