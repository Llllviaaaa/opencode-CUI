#!/usr/bin/env node
import { spawnSync } from 'node:child_process';
import { existsSync, readFileSync, rmSync } from 'node:fs';
import { join } from 'node:path';

const cwd = process.cwd();
const coverageDir = join(cwd, 'coverage');
const lcovPath = join(coverageDir, 'lcov.info');

if (existsSync(coverageDir)) {
  rmSync(coverageDir, { recursive: true, force: true });
}

const run = spawnSync('bun', ['test', '--coverage', '--coverage-reporter=lcov'], {
  cwd,
  encoding: 'utf8',
  stdio: 'pipe',
});

process.stdout.write(run.stdout || '');
process.stderr.write(run.stderr || '');

if (run.status !== 0) {
  process.exit(run.status ?? 1);
}

if (!existsSync(lcovPath)) {
  console.error('coverage_parse_error: missing coverage/lcov.info');
  process.exit(1);
}

const lcov = readFileSync(lcovPath, 'utf8');
let linesFound = 0;
let linesHit = 0;
let branchesFound = 0;
let branchesHit = 0;

for (const block of lcov.split('end_of_record')) {
  const sourceFile = block.match(/^SF:(.+)$/m)?.[1];
  if (!sourceFile?.startsWith('src/')) {
    continue;
  }

  linesFound += Number(block.match(/^LF:(\d+)$/m)?.[1] || 0);
  linesHit += Number(block.match(/^LH:(\d+)$/m)?.[1] || 0);
  branchesFound += Number(block.match(/^BRF:(\d+)$/m)?.[1] || 0);
  branchesHit += Number(block.match(/^BRH:(\d+)$/m)?.[1] || 0);
}

if (linesFound === 0) {
  console.error(`coverage_parse_error: invalid line totals lines=${linesFound}`);
  process.exit(1);
}

const linePct = (linesHit / linesFound) * 100;
const minLines = 80;
const minBranches = 70;
const hasBranchCoverage = branchesFound > 0;
const branchPct = hasBranchCoverage ? (branchesHit / branchesFound) * 100 : null;

if (!hasBranchCoverage) {
  console.warn(
    `coverage_branch_unavailable: branches_found=${branchesFound}, branches_hit=${branchesHit}, expected_min=${minBranches}%`,
  );
}

if (linePct < minLines) {
  console.error(
    `coverage_threshold_failed: lines=${linePct.toFixed(2)}% (min ${minLines}%)`,
  );
  process.exit(1);
}

if (!hasBranchCoverage) {
  console.log(`coverage_threshold_passed: lines=${linePct.toFixed(2)}%, branches=unavailable`);
  process.exit(0);
}

if (branchPct < minBranches) {
  console.warn(
    `coverage_branch_observation_below_target: branches=${branchPct.toFixed(2)}% (target ${minBranches}%, non-blocking)`,
  );
}

console.log(`coverage_threshold_passed: lines=${linePct.toFixed(2)}%, branches=${branchPct.toFixed(2)}%`);
