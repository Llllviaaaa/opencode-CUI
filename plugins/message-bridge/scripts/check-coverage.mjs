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

const c8 = join(cwd, 'node_modules', 'c8', 'bin', 'c8.js');
function runCoveragePass(testGlobs, clean) {
  const args = [
    c8,
    '--reporter=none',
    `--clean=${clean ? 'true' : 'false'}`,
    process.execPath,
    '--import',
    'tsx/esm',
    '--test-isolation=none',
    '--test',
    '--test-force-exit',
    ...testGlobs,
  ];
  const result = spawnSync(process.execPath, args, {
    cwd,
    encoding: 'utf8',
    stdio: 'pipe',
  });

  process.stdout.write(result.stdout || '');
  process.stderr.write(result.stderr || '');

  return result.status ?? 1;
}

const unitStatus = runCoveragePass(['tests/unit/*.test.mjs'], true);
if (unitStatus !== 0) {
  process.exit(unitStatus);
}

const integrationStatus = runCoveragePass(['tests/integration/*.test.mjs'], false);
if (integrationStatus !== 0) {
  process.exit(integrationStatus);
}

const report = spawnSync(
  process.execPath,
  [c8, 'report', '--reporter=lcov', '--reporter=text'],
  { cwd, encoding: 'utf8', stdio: 'pipe' },
);

process.stdout.write(report.stdout || '');
process.stderr.write(report.stderr || '');
console.log('coverage_scope=unit+integration');

if ((report.status ?? 1) !== 0) {
  process.exit(report.status ?? 1);
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
