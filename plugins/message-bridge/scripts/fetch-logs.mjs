#!/usr/bin/env node
import { createReadStream } from 'node:fs';
import { access, readdir, stat, writeFile } from 'node:fs/promises';
import path from 'node:path';
import process from 'node:process';
import readline from 'node:readline';

const args = process.argv.slice(2);
const options = {
  logDir: path.join(process.env.HOME ?? process.env.USERPROFILE ?? '.', '.local', 'share', 'opencode', 'log'),
  since: '',
  until: '',
  level: '',
  traceId: '',
  sessionId: '',
  messagePattern: '',
  service: 'message-bridge',
  limit: 10000,
  format: 'table',
  output: '',
};

function usage() {
  console.log(`Usage: bun run logs:fetch -- [options]

Options:
  --log-dir DIR
  --since ISO_OR_EPOCH_MS
  --until ISO_OR_EPOCH_MS
  --level LEVELS
  --trace-id ID
  --session-id ID
  --message-pattern REGEX
  --service NAME
  --limit N
  --format json|table|raw
  --output FILE
  -h, --help`);
}

for (let i = 0; i < args.length; i += 1) {
  const arg = args[i];
  if (arg === '-h' || arg === '--help') {
    usage();
    process.exit(0);
  }
  const next = args[i + 1];
  switch (arg) {
    case '--log-dir': options.logDir = next; i += 1; break;
    case '--since': options.since = next; i += 1; break;
    case '--until': options.until = next; i += 1; break;
    case '--level': options.level = next; i += 1; break;
    case '--trace-id': options.traceId = next; i += 1; break;
    case '--session-id': options.sessionId = next; i += 1; break;
    case '--message-pattern': options.messagePattern = next; i += 1; break;
    case '--service': options.service = next; i += 1; break;
    case '--limit': options.limit = Number(next); i += 1; break;
    case '--format': options.format = next; i += 1; break;
    case '--output': options.output = next; i += 1; break;
    default:
      throw new Error(`Unknown option: ${arg}`);
  }
}

function toTimestamp(value, fallback) {
  if (!value) return fallback;
  const numeric = Number(value);
  if (Number.isFinite(numeric) && `${numeric}` === value) return numeric;
  const parsed = Date.parse(value);
  if (Number.isNaN(parsed)) throw new Error(`Invalid datetime: ${value}`);
  return parsed;
}

function formatEntries(entries) {
  if (options.format === 'json') return JSON.stringify(entries, null, 2);
  if (options.format === 'raw') return entries.map((entry) => entry.raw).join('\n');
  return entries
    .map((entry) => `${entry.timestamp ?? '-'} ${entry.level ?? '-'} ${entry.service ?? '-'} ${entry.message ?? entry.raw}`)
    .join('\n');
}

async function collectFiles(dir) {
  const entries = await readdir(dir, { withFileTypes: true });
  const files = [];
  for (const entry of entries) {
    const filepath = path.join(dir, entry.name);
    if (entry.isDirectory()) {
      files.push(...(await collectFiles(filepath)));
    } else if (entry.isFile() && (entry.name.endsWith('.log') || entry.name.endsWith('.jsonl'))) {
      files.push(filepath);
    }
  }
  return files;
}

async function main() {
  await access(options.logDir);
  const since = toTimestamp(options.since, 0);
  const until = toTimestamp(options.until, Number.MAX_SAFE_INTEGER);
  const levelSet = options.level ? new Set(options.level.split(',').map((item) => item.trim().toUpperCase())) : null;
  const messagePattern = options.messagePattern ? new RegExp(options.messagePattern) : null;
  const files = await collectFiles(options.logDir);
  const sortedFiles = (await Promise.all(files.map(async (file) => ({ file, mtime: (await stat(file)).mtimeMs })))).sort((a, b) => b.mtime - a.mtime);
  const results = [];

  for (const { file } of sortedFiles) {
    const rl = readline.createInterface({ input: createReadStream(file), crlfDelay: Infinity });
    for await (const line of rl) {
      if (!line.trim()) continue;
      let parsed;
      try {
        parsed = JSON.parse(line);
      } catch {
        parsed = { raw: line };
      }
      const timestamp = parsed.timestamp ? Date.parse(parsed.timestamp) : parsed.time ? Date.parse(parsed.time) : 0;
      const level = parsed.level ? String(parsed.level).toUpperCase() : '';
      const service = parsed.service ?? parsed.name ?? '';
      const traceId = parsed.traceId ?? parsed.trace_id ?? '';
      const sessionId = parsed.sessionId ?? parsed.session_id ?? '';
      const message = parsed.message ?? parsed.msg ?? parsed.event ?? '';

      if (timestamp && (timestamp < since || timestamp > until)) continue;
      if (options.service && service && service !== options.service) continue;
      if (levelSet && level && !levelSet.has(level)) continue;
      if (options.traceId && traceId !== options.traceId) continue;
      if (options.sessionId && sessionId !== options.sessionId) continue;
      if (messagePattern && !messagePattern.test(message) && !messagePattern.test(line)) continue;

      results.push({
        timestamp: parsed.timestamp ?? parsed.time ?? '',
        level,
        service,
        traceId,
        sessionId,
        message,
        raw: line,
      });
      if (results.length >= options.limit) break;
    }
    if (results.length >= options.limit) break;
  }

  const output = formatEntries(results);
  if (options.output) {
    await writeFile(options.output, output, 'utf8');
  } else {
    process.stdout.write(output ? `${output}\n` : '');
  }
}

main().catch((err) => {
  console.error(err instanceof Error ? err.message : String(err));
  usage();
  process.exit(1);
});
