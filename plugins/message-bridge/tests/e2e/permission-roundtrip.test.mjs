import { describe, test } from 'node:test';
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';

describe('e2e smoke permission-roundtrip', () => {
  test('real stack forwards permission.asked and accepts permission_reply', { timeout: 30000 }, () => {
    const stdout = execFileSync('node', ['./scripts/e2e-smoke.mjs'], {
      cwd: process.cwd(),
      stdio: 'pipe',
      env: {
        ...process.env,
        MB_SCENARIO: 'permission-roundtrip',
        MB_PROMPT_TEXT:
          '请执行一个最小 bash 命令来验证环境，例如运行 pwd。在执行前如果需要权限，请等待权限回复后继续。',
      },
    }).toString();

    assert.ok(stdout.includes('E2E PASS'));
    assert.ok(stdout.includes('scenario=permission-roundtrip'));
  });
});
