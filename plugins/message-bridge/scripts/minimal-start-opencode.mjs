#!/usr/bin/env node

import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { spawn } from 'node:child_process';
import { join } from 'node:path';
import { tmpdir } from 'node:os';

const config = {
  ak: 'your-ak',
  sk: 'your-sk',
  gatewayUrl: 'wss://gateway.your-company.com/ws/agent',
  channel: 'opencode',
  pluginName: '@opencode-cui/message-bridge',
  npmScope: '@opencode-cui',
  npmRegistry: 'https://npm.your-company.com',
  npmToken: 'your-token',
  opencodeConfigContent: {
    $schema: 'https://opencode.ai/config.json',
    plugin: ['@opencode-cui/message-bridge'],
  },
  host: '127.0.0.1',
  port: 4096,
  serverUsername: 'opencode',
  serverPassword: 'strong-password',
};

const runtimeDir = join(tmpdir(), 'myapp-opencode-runtime');
mkdirSync(runtimeDir, { recursive: true });

const npmrcPath = join(runtimeDir, '.npmrc');

upsertNpmrc(npmrcPath, {
  scope: config.npmScope,
  registry: config.npmRegistry,
});

const child = spawn(
  'opencode',
  ['serve', '--hostname', config.host, '--port', String(config.port)],
  {
    stdio: 'inherit',
    env: {
      ...process.env,
      OPENCODE_CONFIG_CONTENT: JSON.stringify(config.opencodeConfigContent),
      NPM_CONFIG_USERCONFIG: npmrcPath,
      NPM_TOKEN: config.npmToken,
      BRIDGE_AUTH_AK: config.ak,
      BRIDGE_AUTH_SK: config.sk,
      BRIDGE_GATEWAY_URL: config.gatewayUrl,
      BRIDGE_GATEWAY_CHANNEL: config.channel,
      OPENCODE_SERVER_USERNAME: config.serverUsername,
      OPENCODE_SERVER_PASSWORD: config.serverPassword,
    },
  },
);

child.on('error', (error) => {
  console.error('[minimal-start-opencode] failed to start opencode:', error.message);
});

child.on('exit', (code, signal) => {
  console.log('[minimal-start-opencode] opencode server exited:', { code, signal });
});

function upsertNpmrc(filePath, values) {
  const existing = existsSync(filePath) ? readFileSync(filePath, 'utf8') : '';
  const lines = existing.length > 0 ? existing.split(/\r?\n/) : [];

  const next = [...lines];
  setLine(next, `${values.scope}:registry=`, `${values.scope}:registry=${values.registry}`);
  setLine(next, 'registry=', 'registry=https://registry.npmjs.org');
  setLine(next, `//${hostWithoutScheme(values.registry)}/:_authToken=`, `//${hostWithoutScheme(values.registry)}/:_authToken=\${NPM_TOKEN}`);

  const compact = next
    .map((line) => line.trimEnd())
    .filter((line, index, arr) => !(line.length === 0 && index > 0 && arr[index - 1].length === 0))
    .join('\n')
    .replace(/\s*$/, '\n');

  writeFileSync(filePath, compact, 'utf8');
}

function setLine(lines, prefix, value) {
  const idx = lines.findIndex((line) => line.trimStart().startsWith(prefix));
  if (idx >= 0) {
    lines[idx] = value;
    return;
  }
  lines.push(value);
}

function hostWithoutScheme(url) {
  return url.replace(/^https?:\/\//, '').replace(/\/+$/, '');
}
