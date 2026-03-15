import { describe, test } from 'node:test';
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';

describe('e2e smoke connect-register', () => {
  test('real stack completes register and status handshake', { timeout: 20000 }, () => {
    const stdout = execFileSync('node', ['./scripts/e2e-smoke.mjs'], {
      cwd: process.cwd(),
      stdio: 'pipe',
      env: {
        ...process.env,
        MB_SCENARIO: 'connect-register',
      },
    }).toString();

    assert.ok(stdout.includes('E2E PASS'));
    assert.ok(stdout.includes('scenario=connect-register'));
  });
});
