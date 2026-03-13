import { describe, test, expect } from 'bun:test';
import { execFileSync } from 'node:child_process';

describe('e2e smoke connect-register', () => {
  test('real stack completes register and status handshake', () => {
    const stdout = execFileSync('node', ['./scripts/e2e-smoke.mjs'], {
      cwd: process.cwd(),
      stdio: 'pipe',
      env: {
        ...process.env,
        MB_SCENARIO: 'connect-register',
      },
    }).toString();

    expect(stdout).toContain('E2E PASS');
    expect(stdout).toContain('scenario=connect-register');
  }, 20000);
});
