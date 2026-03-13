#!/usr/bin/env node
import { rm, mkdir, access, rename, readFile, writeFile } from 'node:fs/promises';
import { constants } from 'node:fs';
import { spawn } from 'node:child_process';
import path from 'node:path';
import process from 'node:process';

const ROOT_DIR = process.cwd();
const RELEASE_DIR = path.join(ROOT_DIR, 'release');
const OUTFILE = path.join(RELEASE_DIR, 'message-bridge.plugin.js');
const TMP_OUTFILE = path.join(RELEASE_DIR, 'index.js');
const TMP_MAP = path.join(RELEASE_DIR, 'index.js.map');
const OUTMAP = path.join(RELEASE_DIR, 'message-bridge.plugin.js.map');

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

async function ensureBunAvailable() {
  try {
    await run('bun', ['--version'], { stdio: 'ignore' });
  } catch {
    throw new Error('bun is required to build plugin distribution artifact. Please install bun first.');
  }
}

async function main() {
  await ensureBunAvailable();

  await rm(RELEASE_DIR, { recursive: true, force: true });
  await mkdir(RELEASE_DIR, { recursive: true });

  await run('bun', ['build', 'src/index.ts', '--outdir', RELEASE_DIR, '--format', 'esm', '--target', 'bun', '--sourcemap']);

  await access(TMP_OUTFILE, constants.R_OK);
  await rename(TMP_OUTFILE, OUTFILE);

  try {
    await access(TMP_MAP, constants.R_OK);
    await rename(TMP_MAP, OUTMAP);
    const bundled = await readFile(OUTFILE, 'utf8');
    const updated = bundled.replace(/\/\/# sourceMappingURL=index\.js\.map\s*$/m, '//# sourceMappingURL=message-bridge.plugin.js.map');
    if (updated !== bundled) {
      await writeFile(OUTFILE, updated, 'utf8');
    }
  } catch {
    // sourcemap can be omitted by future config changes; keep build usable.
  }

  await access(OUTFILE, constants.R_OK);

  console.log('Built plugin distribution artifact:');
  console.log(`- ${OUTFILE}`);
  console.log('Compatibility install path: copy the file into .opencode/plugins if package loading is unavailable.');
}

main().catch((err) => {
  console.error('[build-release] failed:', err instanceof Error ? err.message : String(err));
  process.exit(1);
});
