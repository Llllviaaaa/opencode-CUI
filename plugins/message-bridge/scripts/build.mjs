#!/usr/bin/env node
import { rm } from 'node:fs/promises';
import { spawn } from 'node:child_process';
import path from 'node:path';
import process from 'node:process';

const ROOT_DIR = process.cwd();

function run(cmd, args, opts = {}) {
  return new Promise((resolve, reject) => {
    const child = spawn(cmd, args, {
      cwd: opts.cwd ?? ROOT_DIR,
      stdio: opts.stdio ?? 'inherit',
      env: { ...process.env, ...(opts.env ?? {}) },
    });

    child.on('error', reject);
    child.on('exit', (code) => {
      if (code === 0) {
        resolve();
        return;
      }
      reject(new Error(`${cmd} ${args.join(' ')} failed with code ${code}`));
    });
  });
}

async function main() {
  await rm(path.join(ROOT_DIR, 'dist'), { recursive: true, force: true, maxRetries: 10, retryDelay: 100 });
  await rm(path.join(ROOT_DIR, 'release'), { recursive: true, force: true, maxRetries: 10, retryDelay: 100 });
  await run(process.execPath, ['./node_modules/typescript/bin/tsc'], { cwd: ROOT_DIR });
  await run(process.execPath, ['./scripts/build-plugin.mjs'], { cwd: ROOT_DIR });
}

main().catch((err) => {
  console.error('[build] failed:', err instanceof Error ? err.message : String(err));
  process.exit(1);
});
