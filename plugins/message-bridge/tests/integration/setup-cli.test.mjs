import { describe, test, expect } from 'bun:test';
import { spawnSync } from 'node:child_process';
import { mkdir, mkdtemp, readFile, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';

const scriptPath = resolve('scripts/setup-message-bridge.mjs');

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
  return spawnSync('node', [scriptPath, '--scope', scope], {
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

function runSetupWithEnv({ cwd, env: extraEnv, input, scope = 'user' }) {
  return spawnSync('node', [scriptPath, '--scope', scope], {
    cwd,
    env: {
      ...process.env,
      ...extraEnv,
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
      const npmrc = await readFile(join(home, '.npmrc'), 'utf8');

      expect(bridge).toContain('"ak": "ak-test"');
      expect(bridge).toContain('"sk": "sk-test"');
      expect(opencode).toContain('"plugin": ["@opencode-cui/message-bridge"]');
      expect(npmrc).toContain('@opencode-cui:registry=');
      expect(result.stdout).toContain('- AK: ak-test');
      expect(result.stdout).toContain('- SK: sk-test');
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
        input: 'old-ak\nnew-sk\ny\n',
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

  test('adds missing sk into existing auth object without breaking json', async () => {
    await withTempDirs(async ({ home, project }) => {
      const configRoot = join(home, '.config', 'opencode');
      await mkdir(configRoot, { recursive: true });
      await writeFile(
        join(configRoot, 'message-bridge.jsonc'),
        '{\n  "auth": {\n    "ak": "only-ak"\n  }\n}\n',
        'utf8',
      );

      const result = runSetup({
        cwd: project,
        home,
        input: 'only-ak\nadded-sk\ny\n',
      });

      expect(result.status).toBe(0);

      const bridge = await readFile(join(configRoot, 'message-bridge.jsonc'), 'utf8');
      expect(bridge).toContain('"ak": "only-ak",');
      expect(bridge).toContain('"sk": "added-sk"');
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
      const npmrc = await readFile(join(project, '.npmrc'), 'utf8');

      expect(bridge).toContain('"ak": "ak-project"');
      expect(bridge).toContain('"sk": "sk-project"');
      expect(opencode).toContain('"plugin": ["@opencode-cui/message-bridge"]');
      expect(npmrc).toContain('@opencode-cui:registry=');
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
          readFile(join(home, '.npmrc'), 'utf8').then(() => true).catch(() => false),
        ]),
      ).toEqual([false, false, false]);
    });
  });

  test('supports stdin input without tty', async () => {
    await withTempDirs(async ({ home, project }) => {
      const result = runSetup({
        cwd: project,
        home,
        input: "ak-stdin\nsk-stdin\ny\n",
      });

      expect(result.status).toBe(0);

      const configRoot = join(home, '.config', 'opencode');
      const bridge = await readFile(join(configRoot, 'message-bridge.jsonc'), 'utf8');
      const opencode = await readFile(join(configRoot, 'opencode.jsonc'), 'utf8');
      const npmrc = await readFile(join(home, '.npmrc'), 'utf8');

      expect(bridge).toContain('"ak": "ak-stdin"');
      expect(bridge).toContain('"sk": "sk-stdin"');
      expect(opencode).toContain('"plugin": ["@opencode-cui/message-bridge"]');
      expect(npmrc).toContain('@opencode-cui:registry=');
    });
  });

  test('requires non-empty ak and sk before confirmation', async () => {
    await withTempDirs(async ({ home, project }) => {
      const result = runSetup({
        cwd: project,
        home,
        input: '\nak-required\n\nsk-required\ny\n',
      });

      expect(result.status).toBe(0);
      expect(result.stderr).toContain('AK 不能为空，请重新输入');
      expect(result.stderr).toContain('SK 不能为空，请重新输入');

      const configRoot = join(home, '.config', 'opencode');
      const bridge = await readFile(join(configRoot, 'message-bridge.jsonc'), 'utf8');
      expect(bridge).toContain('"ak": "ak-required"');
      expect(bridge).toContain('"sk": "sk-required"');
    });
  });

  test('uses windows-style global paths when platform is win32', async () => {
    await withTempDirs(async ({ home, project }) => {
      const appData = join(home, 'AppData', 'Roaming');
      await mkdir(appData, { recursive: true });
      const result = runSetupWithEnv({
        cwd: project,
        env: {
          HOME: home,
          USERPROFILE: home,
          APPDATA: appData,
          MB_SETUP_PLATFORM: 'win32',
        },
        input: 'ak-win\nsk-win\ny\n',
      });

      expect(result.status).toBe(0);

      const configRoot = join(appData, 'opencode');
      const bridge = await readFile(join(configRoot, 'message-bridge.jsonc'), 'utf8');
      const opencode = await readFile(join(configRoot, 'opencode.jsonc'), 'utf8');
      const npmrc = await readFile(join(home, '.npmrc'), 'utf8');

      expect(bridge).toContain('"ak": "ak-win"');
      expect(opencode).toContain('"plugin": ["@opencode-cui/message-bridge"]');
      expect(npmrc).toContain('@opencode-cui:registry=');
    });
  });

  test('prefers NPM_CONFIG_USERCONFIG for user-scope npmrc path', async () => {
    await withTempDirs(async ({ home, project }) => {
      const customNpmrc = join(home, 'custom', 'npmrc', '.npmrc');
      const result = runSetupWithEnv({
        cwd: project,
        env: {
          HOME: home,
          XDG_CONFIG_HOME: join(home, '.config'),
          NPM_CONFIG_USERCONFIG: customNpmrc,
        },
        input: 'ak-custom\nsk-custom\ny\n',
      });

      expect(result.status).toBe(0);

      const npmrc = await readFile(customNpmrc, 'utf8');
      expect(npmrc).toContain('@opencode-cui:registry=');
      expect(
        await readFile(join(home, '.npmrc'), 'utf8').then(() => true).catch(() => false),
      ).toBe(false);
    });
  });
});
