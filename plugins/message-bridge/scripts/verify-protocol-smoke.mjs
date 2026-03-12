#!/usr/bin/env node
import process from 'node:process';

import { ROOT_DIR, run } from './shared.mjs';

async function main() {
  console.log('[1/3] Running protocol integration suite...');
  await run('bun', ['test', 'tests/integration'], {
    cwd: ROOT_DIR,
    stdio: 'inherit',
  });

  console.log('[2/3] Running real-stack protocol smoke suite...');
  await run('bun', ['test', 'tests/e2e/connect-register.test.mjs', 'tests/e2e/chat-stream.test.mjs', 'tests/e2e/permission-roundtrip.test.mjs'], {
    cwd: ROOT_DIR,
    stdio: 'inherit',
  });

  console.log('[3/3] Protocol verification complete.');
}

main().catch((error) => {
  console.error(error instanceof Error ? error.message : String(error));
  process.exit(1);
});
