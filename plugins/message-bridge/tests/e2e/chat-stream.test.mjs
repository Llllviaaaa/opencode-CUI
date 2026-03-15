import { describe, test } from 'node:test';
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';

describe('e2e smoke chat-stream', () => {
  test('real stack forwards chat stream events to gateway', { timeout: 20000 }, () => {
    const stdout = execFileSync('node', ['./scripts/e2e-smoke.mjs'], {
      cwd: process.cwd(),
      stdio: 'pipe',
      env: {
        ...process.env,
        MB_SCENARIO: 'chat-stream',
      },
    }).toString();

    assert.ok(stdout.includes('E2E PASS'));
    assert.ok(stdout.includes('scenario=chat-stream'));
  });
});
