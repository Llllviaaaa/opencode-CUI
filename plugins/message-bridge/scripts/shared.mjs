#!/usr/bin/env node
import { mkdir, mkdtemp, readFile, rm, writeFile } from 'node:fs/promises';
import { spawn, spawnSync } from 'node:child_process';
import os from 'node:os';
import path from 'node:path';
import process from 'node:process';

export const ROOT_DIR = process.cwd();

export function ensureCommand(cmd) {
  const checker = process.platform === 'win32' ? 'where' : 'which';
  const args = [cmd];
  const result = spawnSync(checker, args, { encoding: 'utf8' });
  if (result.status !== 0) {
    throw new Error(`Missing required command: ${cmd}`);
  }
}

export function run(cmd, args, opts = {}) {
  return new Promise((resolve, reject) => {
    const child = spawn(cmd, args, {
      cwd: opts.cwd ?? ROOT_DIR,
      stdio: opts.stdio ?? 'inherit',
      env: { ...process.env, ...(opts.env ?? {}) },
      shell: opts.shell ?? false,
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

export function runCapture(cmd, args, opts = {}) {
  return new Promise((resolve, reject) => {
    const child = spawn(cmd, args, {
      cwd: opts.cwd ?? ROOT_DIR,
      stdio: ['ignore', 'pipe', 'pipe'],
      env: { ...process.env, ...(opts.env ?? {}) },
      shell: opts.shell ?? false,
    });

    let stdout = '';
    let stderr = '';
    child.stdout?.on('data', (chunk) => {
      stdout += chunk.toString();
    });
    child.stderr?.on('data', (chunk) => {
      stderr += chunk.toString();
    });
    child.on('error', reject);
    child.on('exit', (code) => {
      if (code === 0) {
        resolve({ stdout, stderr });
        return;
      }
      reject(new Error(stderr || stdout || `${cmd} ${args.join(' ')} failed with code ${code}`));
    });
  });
}

export async function waitForPattern(file, pattern, maxTries, intervalMs = 200) {
  for (let tries = 0; tries < maxTries; tries += 1) {
    try {
      const text = await readFile(file, 'utf8');
      if (pattern.test(text)) {
        return true;
      }
    } catch {}
    await new Promise((resolve) => setTimeout(resolve, intervalMs));
  }
  return false;
}

export function spawnLoggedProcess(cmd, args, logfile, opts = {}) {
  const child = spawn(cmd, args, {
    cwd: opts.cwd ?? ROOT_DIR,
    env: { ...process.env, ...(opts.env ?? {}) },
    shell: opts.shell ?? false,
    stdio: ['ignore', 'pipe', 'pipe'],
  });

  child.stdout?.on('data', async (chunk) => {
    await appendFileCompat(logfile, chunk.toString());
  });
  child.stderr?.on('data', async (chunk) => {
    await appendFileCompat(logfile, chunk.toString());
  });
  return child;
}

export async function writeJson(filepath, value) {
  await mkdir(path.dirname(filepath), { recursive: true });
  await writeFile(filepath, JSON.stringify(value, null, 2), 'utf8');
}

async function appendFileCompat(file, text) {
  await mkdir(path.dirname(file), { recursive: true });
  await writeFile(file, text, { flag: 'a' });
}

export async function createTempDir(prefix) {
  return mkdtemp(path.join(os.tmpdir(), prefix));
}

export async function cleanupDirs(...dirs) {
  await Promise.all(dirs.filter(Boolean).map((dir) => rm(dir, { recursive: true, force: true })));
}
