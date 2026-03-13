import { describe, test, expect } from 'bun:test';
import { spawnSync } from 'node:child_process';
import { mkdir, mkdtemp, readFile, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';

const scriptPath = resolve('scripts/setup-message-bridge.sh');

async function withTempDirs(fn) {
  const home = await mkdtemp(join(tmpdir(), 'mb-cli-home-'));
  const project = await mkdtemp(join(tmpdir(), 'mb-cli-project-'));
  try {
    await fn({ home, project });
  } finally {
    await rm(home, { recursive: true, force: true });
    await rm(project, { recursive: true, force: true });
  }
}

function runSetup({ cwd, home, input, scope = 'user' }) {
  return spawnSync('bash', [scriptPath, '--scope', scope], {
    cwd,
    env: {
      ...process.env,
      HOME: home,
      XDG_CONFIG_HOME: join(home, '.config'),
    },
    input,
    encoding: 'utf8',
  });
}

function runPowerShellSetup({ cwd, home, input, scope = 'user' }) {
  return spawnSync('pwsh', ['-NoLogo', '-NoProfile', '-File', resolve('scripts/setup-message-bridge.ps1'), '-Scope', scope], {
    cwd,
    env: {
      ...process.env,
      HOME: home,
      XDG_CONFIG_HOME: join(home, '.config'),
    },
    input,
    encoding: 'utf8',
  });
}

describe('setup cli', () => {
  test('creates user-scope bridge and opencode config', async () => {
    await withTempDirs(async ({ home, project }) => {
      const result = runSetup({
        cwd: project,
        home,
        input: 'ak-test\nsk-test\ny\n',
      });

      expect(result.status).toBe(0);

      const configRoot = join(home, '.config', 'opencode');
      const bridge = await readFile(join(configRoot, 'message-bridge.jsonc'), 'utf8');
      const opencode = await readFile(join(configRoot, 'opencode.jsonc'), 'utf8');

      expect(bridge).toContain('"ak": "ak-test"');
      expect(bridge).toContain('"sk": "sk-test"');
      expect(opencode).toContain('"plugin": ["@opencode-cui/message-bridge"]');
      expect(result.stdout).toContain('下次启动 OpenCode 时会自动安装并加载 npm 插件。');
    });
  });

  test('preserves existing gateway url and avoids duplicate plugin entry', async () => {
    await withTempDirs(async ({ home, project }) => {
      const configRoot = join(home, '.config', 'opencode');
      await mkdir(configRoot, { recursive: true });
      await writeFile(
        join(configRoot, 'message-bridge.jsonc'),
        '{\n  "gateway": {\n    "url": "wss://gateway.example.com/ws/agent"\n  },\n  "auth": {\n    "ak": "old-ak",\n    "sk": "old-sk"\n  }\n}\n',
        'utf8',
      );
      await writeFile(
        join(configRoot, 'opencode.jsonc'),
        '{\n  "$schema": "https://opencode.ai/config.json",\n  "plugin": ["@opencode-cui/message-bridge"]\n}\n',
        'utf8',
      );

      const result = runSetup({
        cwd: project,
        home,
        input: '\nnew-sk\ny\n',
      });

      expect(result.status).toBe(0);

      const bridge = await readFile(join(configRoot, 'message-bridge.jsonc'), 'utf8');
      const opencode = await readFile(join(configRoot, 'opencode.jsonc'), 'utf8');

      expect(bridge).toContain('"url": "wss://gateway.example.com/ws/agent"');
      expect(bridge).toContain('"ak": "old-ak"');
      expect(bridge).toContain('"sk": "new-sk"');
      expect(opencode.match(/@opencode-cui\/message-bridge/g)?.length).toBe(1);
    });
  });

  test('fails fast on invalid existing bridge config', async () => {
    await withTempDirs(async ({ home, project }) => {
      const configRoot = join(home, '.config', 'opencode');
      await mkdir(configRoot, { recursive: true });
      await writeFile(join(configRoot, 'message-bridge.jsonc'), '{\n  "auth": {\n', 'utf8');

      const result = runSetup({
        cwd: project,
        home,
        input: 'ak-test\nsk-test\ny\n',
      });

      expect(result.status).not.toBe(0);
      expect(result.stderr).toContain('无法安全解析现有 bridge 配置');
    });
  });

  test('writes project-scope files when scope is project', async () => {
    await withTempDirs(async ({ home, project }) => {
      const result = runSetup({
        cwd: project,
        home,
        scope: 'project',
        input: 'ak-project\nsk-project\ny\n',
      });

      expect(result.status).toBe(0);

      const bridge = await readFile(join(project, '.opencode', 'message-bridge.jsonc'), 'utf8');
      const opencode = await readFile(join(project, 'opencode.jsonc'), 'utf8');

      expect(bridge).toContain('"ak": "ak-project"');
      expect(bridge).toContain('"sk": "sk-project"');
      expect(opencode).toContain('"plugin": ["@opencode-cui/message-bridge"]');
    });
  });

  test('does not write files when user cancels confirmation', async () => {
    await withTempDirs(async ({ home, project }) => {
      const result = runSetup({
        cwd: project,
        home,
        input: 'ak-cancel\nsk-cancel\nn\n',
      });

      expect(result.status).toBe(0);
      expect(result.stdout).toContain('已取消，未写入任何文件。');

      const configRoot = join(home, '.config', 'opencode');
      expect(
        await Promise.all([
          readFile(join(configRoot, 'message-bridge.jsonc'), 'utf8').then(() => true).catch(() => false),
          readFile(join(configRoot, 'opencode.jsonc'), 'utf8').then(() => true).catch(() => false),
        ]),
      ).toEqual([false, false]);
    });
  });

  test('powershell script creates user-scope bridge and opencode config', async () => {
    await withTempDirs(async ({ home, project }) => {
      const result = runPowerShellSetup({
        cwd: project,
        home,
        input: "ak-ps\nsk-ps\ny\n",
      });

      expect(result.status).toBe(0);

      const configRoot = join(home, '.config', 'opencode');
      const bridge = await readFile(join(configRoot, 'message-bridge.jsonc'), 'utf8');
      const opencode = await readFile(join(configRoot, 'opencode.jsonc'), 'utf8');

      expect(bridge).toContain('"ak": "ak-ps"');
      expect(bridge).toContain('"sk": "sk-ps"');
      expect(opencode).toContain('"plugin": ["@opencode-cui/message-bridge"]');
    });
  });
});
