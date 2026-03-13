#!/usr/bin/env node

import { createInterface } from 'node:readline/promises';
import { stdin, stdout, stderr, argv, env, cwd, exit } from 'node:process';
import { dirname, join } from 'node:path';
import { homedir } from 'node:os';
import { mkdtemp, readFile, rename, rm, writeFile, mkdir } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { platform } from 'node:process';

const PLUGIN_NAME = '@opencode-cui/message-bridge';
const NPM_SCOPE_REGISTRY_LINE = '@opencode-cui:registry=';
const NPM_SCOPE_REGISTRY_HINT = '; TODO: fill private registry url';

function writeLine(message) {
  stdout.write(`${message}\n`);
}

function writeError(message) {
  stderr.write(`错误: ${message}\n`);
}

function jsonEscape(value) {
  return value.replaceAll('\\', '\\\\').replaceAll('"', '\\"');
}

function resolvePreferredExistingPath(jsoncPath, jsonPath) {
  return Promise.any([
    readFile(jsoncPath, 'utf8').then(() => jsoncPath),
    readFile(jsonPath, 'utf8').then(() => jsonPath),
  ]).catch(() => jsoncPath);
}

async function readOptionalTextFile(path) {
  try {
    return await readFile(path, 'utf8');
  } catch (error) {
    if (error && typeof error === 'object' && 'code' in error && error.code === 'ENOENT') {
      return null;
    }
    throw error;
  }
}

function looksLikeJsonObject(content) {
  const trimmed = content.trim();
  return trimmed.startsWith('{') && trimmed.endsWith('}');
}

async function writeFileAtomically(path, content) {
  await mkdir(dirname(path), { recursive: true });
  const tempDir = await mkdtemp(join(tmpdir(), 'mb-setup-'));
  const tempPath = join(tempDir, 'config.tmp');
  try {
    await writeFile(tempPath, content, 'utf8');
    await rename(tempPath, path);
  } finally {
    await rm(tempDir, { recursive: true, force: true });
  }
}

function resolveGlobalConfigDir() {
  const currentPlatform = env.MB_SETUP_PLATFORM || platform;
  if (env.XDG_CONFIG_HOME) {
    return join(env.XDG_CONFIG_HOME, 'opencode');
  }
  if (currentPlatform === 'win32') {
    if (env.APPDATA) {
      return join(env.APPDATA, 'opencode');
    }
    if (env.USERPROFILE) {
      return join(env.USERPROFILE, '.config', 'opencode');
    }
  }
  return join(homedir(), '.config', 'opencode');
}

function resolveWindowsHomeDir() {
  if (env.USERPROFILE) {
    return env.USERPROFILE;
  }
  if (env.HOMEDRIVE && env.HOMEPATH) {
    return `${env.HOMEDRIVE}${env.HOMEPATH}`;
  }
  return homedir();
}

function resolveUserNpmrcPath() {
  if (env.NPM_CONFIG_USERCONFIG) {
    return env.NPM_CONFIG_USERCONFIG;
  }

  const currentPlatform = env.MB_SETUP_PLATFORM || platform;
  if (currentPlatform === 'win32') {
    return join(resolveWindowsHomeDir(), '.npmrc');
  }
  return join(env.HOME || homedir(), '.npmrc');
}

async function buildNextNpmrcContent(path) {
  const existing = await readOptionalTextFile(path);
  if (existing && existing.includes(NPM_SCOPE_REGISTRY_LINE)) {
    return existing;
  }

  const prefix = existing && existing.trim().length > 0 ? `${existing.replace(/\s*$/, '')}\n` : '';
  return `${prefix}${NPM_SCOPE_REGISTRY_LINE}\n${NPM_SCOPE_REGISTRY_HINT}\n`;
}

function readConfiguredCredential(content, key) {
  const match = content.match(new RegExp(`"${key}"\\s*:\\s*"([^"]*)"`, 'm'));
  return match?.[1] ?? '';
}

function upsertJsonObjectField(objectBody, key, escapedValue) {
  const existingFieldPattern = new RegExp(`("${key}"\\s*:\\s*")[^"]*(")`, 's');
  if (existingFieldPattern.test(objectBody)) {
    return objectBody.replace(existingFieldPattern, `$1${escapedValue}$2`);
  }

  const trimmedBody = objectBody.replace(/\s*$/s, '');
  const trailingWhitespace = objectBody.slice(trimmedBody.length);
  if (!trimmedBody) {
    return `\n    "${key}": "${escapedValue}"${trailingWhitespace}`;
  }

  const separator = /,\s*$/.test(trimmedBody) ? '' : ',';
  return `${trimmedBody}${separator}\n    "${key}": "${escapedValue}"${trailingWhitespace}`;
}

function buildNextBridgeConfig(content, ak, sk) {
  const escapedAk = jsonEscape(ak);
  const escapedSk = jsonEscape(sk);

  if (content === null) {
    return `{
  "auth": {
    "ak": "${escapedAk}",
    "sk": "${escapedSk}"
  }
}
`;
  }

  if (!looksLikeJsonObject(content)) {
    throw new Error('bridge config invalid');
  }

  if (/"auth"\s*:/s.test(content)) {
    const authBlockPattern = /("auth"\s*:\s*\{)([\s\S]*?)(\n\s*\})/s;
    if (!authBlockPattern.test(content)) {
      throw new Error('bridge config invalid');
    }

    return content.replace(authBlockPattern, (_match, start, objectBody, end) => {
      // 保持文本级改写，尽量只更新 auth 下的目标字段。
      let nextBody = upsertJsonObjectField(objectBody, 'ak', escapedAk);
      nextBody = upsertJsonObjectField(nextBody, 'sk', escapedSk);
      return `${start}${nextBody}${end}`;
    });
  }

  return content.replace(/\n\}\s*$/s, `,\n  "auth": {\n    "ak": "${escapedAk}",\n    "sk": "${escapedSk}"\n  }\n}\n`);
}

function buildNextOpencodeConfig(content) {
  if (content === null) {
    return `{
  "$schema": "https://opencode.ai/config.json",
  "plugin": ["${PLUGIN_NAME}"]
}
`;
  }

  if (!looksLikeJsonObject(content)) {
    throw new Error('opencode config invalid');
  }

  if (content.includes(`"${PLUGIN_NAME}"`)) {
    return content;
  }

  if (/"plugin"\s*:\s*\[/s.test(content)) {
    if (/"plugin"\s*:\s*\[\s*\]/s.test(content)) {
      return content.replace(/"plugin"\s*:\s*\[\s*\]/s, `"plugin": ["${PLUGIN_NAME}"]`);
    }
    return content.replace(/("plugin"\s*:\s*\[)([\s\S]*?)(\])/s, (_match, start, items, end) => {
      const trimmedItems = items.trimEnd();
      const separator = /\S/.test(trimmedItems) ? ', ' : '';
      return `${start}${trimmedItems}${separator}"${PLUGIN_NAME}"${end}`;
    });
  }

  return content.replace(/\n\}\s*$/s, `,\n  "plugin": ["${PLUGIN_NAME}"]\n}\n`);
}

class PromptSession {
  constructor() {
    this.queue = null;
    this.readline = null;
  }

  async init() {
    if (stdin.isTTY) {
      this.readline = createInterface({ input: stdin, output: stdout });
      return;
    }

    // 非 TTY 场景统一读取整段 stdin，便于测试和脚本管道调用。
    const piped = await new Promise((resolve, reject) => {
      let data = '';
      stdin.setEncoding('utf8');
      stdin.on('data', (chunk) => {
        data += chunk;
      });
      stdin.on('end', () => resolve(data));
      stdin.on('error', reject);
    });
    this.queue = piped.split(/\r?\n/);
  }

  async ask(promptLabel, currentValue = '') {
    if (this.queue) {
      const value = this.queue.shift() ?? '';
      return value.trim() ? value.trim() : currentValue;
    }

    const prompt = currentValue ? `${promptLabel} [${currentValue}]: ` : `${promptLabel}: `;
    const value = await this.readline.question(prompt);
    return value.trim() ? value.trim() : currentValue;
  }

  async confirmAction(promptLabel) {
    if (this.queue) {
      const value = (this.queue.shift() ?? '').trim().toLowerCase();
      return value === 'y' || value === 'yes';
    }

    const answer = await this.readline.question(`${promptLabel} [y/N]: `);
    const normalized = answer.trim().toLowerCase();
    return normalized === 'y' || normalized === 'yes';
  }

  async close() {
    await this.readline?.close();
  }
}

async function promptRequiredField(prompts, fieldName, promptLabel, currentValue = '') {
  while (true) {
    const value = await prompts.ask(promptLabel, currentValue);
    if (value) {
      return value;
    }
    writeError(`${fieldName} 不能为空，请重新输入`);
  }
}

function parseScope(args) {
  if (args[0] === '--scope') {
    if (args[1] !== 'user' && args[1] !== 'project') {
      throw new Error('--scope 仅支持 user 或 project');
    }
    return args[1];
  }
  return 'user';
}

function buildTargetPaths(scope) {
  const configDir = scope === 'user'
    ? resolveGlobalConfigDir()
    : join(cwd(), '.opencode');

  return {
    configDir,
    bridgeConfigCandidates: [
      join(configDir, 'message-bridge.jsonc'),
      join(configDir, 'message-bridge.json'),
    ],
    opencodeConfigCandidates: scope === 'user'
      ? [join(configDir, 'opencode.jsonc'), join(configDir, 'opencode.json')]
      : [join(cwd(), 'opencode.jsonc'), join(cwd(), 'opencode.json')],
    npmrcPath: scope === 'user' ? resolveUserNpmrcPath() : join(cwd(), '.npmrc'),
  };
}

async function resolveTargetFilePaths(scope) {
  const targetPaths = buildTargetPaths(scope);
  return {
    bridgeConfig: await resolvePreferredExistingPath(...targetPaths.bridgeConfigCandidates),
    opencodeConfig: await resolvePreferredExistingPath(...targetPaths.opencodeConfigCandidates),
    npmrcPath: targetPaths.npmrcPath,
  };
}

function printSetupOverview(scope, bridgeConfig, opencodeConfig, npmrcPath) {
  writeLine('Message Bridge 配置向导');
  writeLine(`配置作用域: ${scope}`);
  writeLine(`Message Bridge 配置文件: ${bridgeConfig}`);
  writeLine(`OpenCode 配置文件: ${opencodeConfig}`);
  writeLine(`npm scope 配置文件: ${npmrcPath}`);
  writeLine('');
}

function printChangePreview(ak, sk) {
  writeLine('');
  writeLine('即将写入以下配置：');
  writeLine(`- AK: ${ak}`);
  writeLine(`- SK: ${sk}`);
  writeLine(`- OpenCode plugin: ${PLUGIN_NAME}`);
  writeLine(`- npm scope: ${NPM_SCOPE_REGISTRY_LINE}`);
  writeLine('');
}

async function main() {
  const scope = parseScope(argv.slice(2));
  const { bridgeConfig, opencodeConfig, npmrcPath } = await resolveTargetFilePaths(scope);

  const existingBridge = await readOptionalTextFile(bridgeConfig);
  const currentAk = existingBridge ? readConfiguredCredential(existingBridge, 'ak') : '';
  const currentSk = existingBridge ? readConfiguredCredential(existingBridge, 'sk') : '';

  const prompts = new PromptSession();
  await prompts.init();

  try {
    printSetupOverview(scope, bridgeConfig, opencodeConfig, npmrcPath);

    const ak = await promptRequiredField(prompts, 'AK', '请输入 AK（必填）', currentAk);
    const sk = await promptRequiredField(prompts, 'SK', '请输入 SK（必填）', currentSk);

    printChangePreview(ak, sk);

    const confirmed = await prompts.confirmAction('确认写入以上配置');
    if (!confirmed) {
      writeLine('已取消，未写入任何文件。');
      return;
    }

    const existingOpencode = await readOptionalTextFile(opencodeConfig);
    const nextNpmrc = await buildNextNpmrcContent(npmrcPath);
    let nextBridge;
    let nextOpencode;

    try {
      nextBridge = buildNextBridgeConfig(existingBridge, ak, sk);
    } catch {
      throw new Error(`无法安全解析现有 bridge 配置：${bridgeConfig}`);
    }

    try {
      nextOpencode = buildNextOpencodeConfig(existingOpencode);
    } catch {
      throw new Error(`无法安全解析现有 OpenCode 配置：${opencodeConfig}`);
    }

    // 先生成所有目标内容，再统一落盘，避免部分写入成功后中途失败。
    await writeFileAtomically(bridgeConfig, nextBridge);
    await writeFileAtomically(opencodeConfig, nextOpencode);
    await writeFileAtomically(npmrcPath, nextNpmrc);

    writeLine('配置已完成。');
    writeLine(`1. Message Bridge 配置已写入 ${bridgeConfig}`);
    writeLine(`2. OpenCode 配置已更新 ${opencodeConfig}`);
    writeLine(`3. npm scope 配置已更新 ${npmrcPath}`);
    writeLine('4. 下次启动 OpenCode 时会自动安装并加载 npm 插件。');
  } finally {
    await prompts.close();
  }
}

main().catch((error) => {
  writeError(error instanceof Error ? error.message : String(error));
  exit(1);
});
