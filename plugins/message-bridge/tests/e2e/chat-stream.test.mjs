import { describe, test, expect } from 'bun:test';
import { execFileSync } from 'node:child_process';

describe('e2e smoke chat-stream', () => {
  test('real stack forwards chat stream events to gateway', () => {
    const stdout = execFileSync('node', ['./scripts/e2e-smoke.mjs'], {
      cwd: process.cwd(),
      stdio: 'pipe',
      env: {
        ...process.env,
        MB_SCENARIO: 'chat-stream',
      },
    }).toString();

    expect(stdout).toContain('E2E PASS');
    expect(stdout).toContain('scenario=chat-stream');
  }, 20000);
});
