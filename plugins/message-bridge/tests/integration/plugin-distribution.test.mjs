import { describe, test, expect } from 'bun:test';
import { execFileSync } from 'node:child_process';
import { access, mkdir, mkdtemp, rm, symlink } from 'node:fs/promises';
import { constants } from 'node:fs';
import { pathToFileURL } from 'node:url';
import { join, resolve } from 'node:path';
import { tmpdir } from 'node:os';

const PACKAGE_NAME = '@opencode-cui/message-bridge';

describe('plugin distribution artifact', () => {
  test('builds single-file artifact with default and named exports', async () => {
    execFileSync('node', ['./scripts/build.mjs'], {
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

  test('builds package entrypoint for Bun package loading', async () => {
    execFileSync('node', ['./scripts/build.mjs'], {
      cwd: process.cwd(),
      stdio: 'pipe',
      env: process.env,
    });

    const tempDir = await mkdtemp(join(tmpdir(), 'message-bridge-package-'));
    try {
      const packageScopeDir = join(tempDir, 'node_modules', '@opencode-cui');
      await mkdir(packageScopeDir, { recursive: true });
      await symlink(process.cwd(), join(packageScopeDir, 'message-bridge'), process.platform === 'win32' ? 'junction' : 'dir');

      const stdout = execFileSync(
        'bun',
        [
          '-e',
          `const mod = await import(${JSON.stringify(PACKAGE_NAME)}); console.log(typeof mod.default, typeof mod.MessageBridgePlugin, mod.default === mod.MessageBridgePlugin);`,
        ],
        {
          cwd: tempDir,
          stdio: 'pipe',
          env: process.env,
        },
      ).toString().trim();

      expect(stdout).toBe('function function true');
    } finally {
      await rm(tempDir, { recursive: true, force: true });
    }
  });
});
