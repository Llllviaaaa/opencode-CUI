import { describe, test, expect } from 'bun:test';
import { execFileSync } from 'node:child_process';
import { access } from 'node:fs/promises';
import { constants } from 'node:fs';
import { pathToFileURL } from 'node:url';
import { resolve } from 'node:path';

describe('plugin distribution artifact', () => {
  test('builds single-file artifact with default and named exports', async () => {
    execFileSync('node', ['./scripts/build-plugin.mjs'], {
      cwd: process.cwd(),
      stdio: 'pipe',
      env: process.env,
    });

    const artifactPath = resolve('release/message-bridge.plugin.js');
    await access(artifactPath, constants.R_OK);

    const mod = await import(pathToFileURL(artifactPath).href);

    expect(typeof mod.default).toBe('function');
    expect(typeof mod.MessageBridgePlugin).toBe('function');
    expect(mod.default).toBe(mod.MessageBridgePlugin);
  });
});
