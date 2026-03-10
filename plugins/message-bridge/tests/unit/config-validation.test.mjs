import { afterEach, describe, expect, test } from 'bun:test';
import { mkdtemp, mkdir, writeFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

import {
  ConfigValidationAggregateError,
  loadConfig,
  validateConfig,
} from '../../src/config/index.ts';
import { AppLogger } from '../../src/runtime/AppLogger.ts';

const originalHome = process.env.HOME;

const createValidConfig = (overrides = {}) => ({
  config_version: 1,
  enabled: true,
  gateway: {
    url: 'ws://localhost:8081/ws/agent',
    heartbeatIntervalMs: 30000,
    reconnect: {
      baseMs: 1000,
      maxMs: 30000,
      exponential: true,
    },
  },
  sdk: {
    timeoutMs: 10000,
  },
  auth: {
    ak: 'test-ak-001',
    sk: 'test-sk-secret-001',
  },
  events: {
    allowlist: ['message.updated'],
  },
  ...overrides,
});

afterEach(() => {
  if (originalHome === undefined) {
    delete process.env.HOME;
    return;
  }
  process.env.HOME = originalHome;
});

describe('config validation for sdk.baseUrl removal', () => {
  test('validateConfig passes without sdk.baseUrl', () => {
    const errors = validateConfig({
      config_version: 1,
      enabled: true,
      gateway: {
        url: 'ws://localhost:8081/ws/agent',
        heartbeatIntervalMs: 30000,
        reconnect: {
          baseMs: 1000,
          maxMs: 30000,
          exponential: true,
        },
      },
      sdk: {
        timeoutMs: 10000,
      },
      auth: {
        ak: 'test-ak-001',
        sk: 'test-sk-secret-001',
      },
      events: {
        allowlist: ['message.updated'],
      },
    });

    expect(errors).toEqual([]);
  });

  test('validateConfig rejects deprecated sdk.baseUrl', () => {
    const errors = validateConfig({
      config_version: 1,
      enabled: true,
      gateway: {
        url: 'ws://localhost:8081/ws/agent',
        heartbeatIntervalMs: 30000,
        reconnect: {
          baseMs: 1000,
          maxMs: 30000,
          exponential: true,
        },
      },
      sdk: {
        timeoutMs: 10000,
        baseUrl: 'http://localhost:54321',
      },
      auth: {
        ak: 'test-ak-001',
        sk: 'test-sk-secret-001',
      },
      events: {
        allowlist: ['message.updated'],
      },
    });

    expect(errors.some((e) => e.path === 'sdk.baseUrl' && e.code === 'DEPRECATED_FIELD')).toBe(true);
  });

  test('loadConfig throws when project config still contains sdk.baseUrl', async () => {
    const workspace = await mkdtemp(join(tmpdir(), 'mb-cfg-'));
    const fakeHome = await mkdtemp(join(tmpdir(), 'mb-home-'));
    process.env.HOME = fakeHome;

    await mkdir(join(workspace, '.opencode'), { recursive: true });
    await writeFile(
      join(workspace, '.opencode', 'message-bridge.jsonc'),
      JSON.stringify({
        config_version: 1,
        enabled: true,
        gateway: {
          url: 'ws://localhost:8081/ws/agent',
          heartbeatIntervalMs: 30000,
          reconnect: {
            baseMs: 1000,
            maxMs: 30000,
            exponential: true,
          },
        },
        sdk: {
          timeoutMs: 10000,
          baseUrl: 'http://localhost:54321',
        },
        auth: {
          ak: 'test-ak-001',
          sk: 'test-sk-secret-001',
        },
        events: {
          allowlist: ['message.updated'],
        },
      }),
      'utf8',
    );

    try {
      await loadConfig(workspace);
      throw new Error('expected loadConfig to fail');
    } catch (error) {
      expect(error).toBeInstanceOf(ConfigValidationAggregateError);
      const typed = error;
      expect(typed.errors.some((e) => e.path === 'sdk.baseUrl' && e.code === 'DEPRECATED_FIELD')).toBe(true);
    } finally {
      await rm(workspace, { recursive: true, force: true });
      await rm(fakeHome, { recursive: true, force: true });
    }
  });

  test('loadConfig writes structured config logs through logger', async () => {
    const workspace = await mkdtemp(join(tmpdir(), 'mb-cfg-'));
    const fakeHome = await mkdtemp(join(tmpdir(), 'mb-home-'));
    process.env.HOME = fakeHome;

    await mkdir(join(workspace, '.opencode'), { recursive: true });
    await writeFile(
      join(workspace, '.opencode', 'message-bridge.jsonc'),
      JSON.stringify({
        config_version: 1,
        enabled: true,
        gateway: {
          url: 'ws://localhost:8081/ws/agent',
          heartbeatIntervalMs: 30000,
          reconnect: {
            baseMs: 1000,
            maxMs: 30000,
            exponential: true,
          },
        },
        sdk: {
          timeoutMs: 10000,
        },
        auth: {
          ak: 'test-ak-001',
          sk: 'test-sk-secret-001',
        },
        events: {
          allowlist: ['message.updated'],
        },
      }),
      'utf8',
    );

    const calls = [];
    const logger = new AppLogger(
      {
        app: {
          log: async (options) => {
            calls.push(options);
          },
        },
      },
      { component: 'test' },
      undefined,
      undefined,
      true,
    );

    try {
      const config = await loadConfig(workspace, logger);
      expect(config.enabled).toBe(true);
      await new Promise((r) => setTimeout(r, 10));

      const messages = calls.map((entry) => entry.body.message);
      expect(messages).toContain('config.resolve.started');
      expect(messages).toContain('config.source.loaded');
      expect(messages).toContain('config.resolve.completed');
      expect(messages).toContain('config.validation.passed');

      const completed = calls.find((entry) => entry.body.message === 'config.resolve.completed');
      expect(completed.body.extra.component).toBe('config');
      expect(completed.body.extra.sources).toContain('default');
      expect(completed.body.extra.sources).toContain(
        `project:${join(workspace, '.opencode', 'message-bridge.jsonc')}`,
      );
    } finally {
      await rm(workspace, { recursive: true, force: true });
      await rm(fakeHome, { recursive: true, force: true });
    }
  });

  test('loadConfig logs validation failures through logger', async () => {
    const workspace = await mkdtemp(join(tmpdir(), 'mb-cfg-'));
    const fakeHome = await mkdtemp(join(tmpdir(), 'mb-home-'));
    process.env.HOME = fakeHome;

    await mkdir(join(workspace, '.opencode'), { recursive: true });
    await writeFile(
      join(workspace, '.opencode', 'message-bridge.jsonc'),
      JSON.stringify({
        config_version: 1,
        enabled: true,
        gateway: {
          url: 'ws://localhost:8081/ws/agent',
          heartbeatIntervalMs: 30000,
          reconnect: {
            baseMs: 1000,
            maxMs: 30000,
            exponential: true,
          },
        },
        sdk: {
          timeoutMs: 10000,
          baseUrl: 'http://localhost:54321',
        },
        auth: {
          ak: 'test-ak-001',
          sk: 'test-sk-secret-001',
        },
        events: {
          allowlist: ['message.updated'],
        },
      }),
      'utf8',
    );

    const calls = [];
    const logger = new AppLogger(
      {
        app: {
          log: async (options) => {
            calls.push(options);
          },
        },
      },
      { component: 'test' },
      undefined,
      undefined,
      true,
    );

    try {
      await expect(loadConfig(workspace, logger)).rejects.toBeInstanceOf(ConfigValidationAggregateError);
      await new Promise((r) => setTimeout(r, 10));

      const failure = calls.find((entry) => entry.body.message === 'config.validation.failed');
      expect(failure).toBeDefined();
      expect(failure.body.extra.component).toBe('config');
      expect(failure.body.extra.errorCount).toBe(1);
      expect(failure.body.extra.errors).toEqual([
        {
          code: 'DEPRECATED_FIELD',
          path: 'sdk.baseUrl',
          message: expect.any(String),
        },
      ]);
    } finally {
      await rm(workspace, { recursive: true, force: true });
      await rm(fakeHome, { recursive: true, force: true });
    }
  });
});

describe('config suffix lookup support (.jsonc + .json)', () => {
  test('loadConfig reads project message-bridge.json when jsonc is absent', async () => {
    const workspace = await mkdtemp(join(tmpdir(), 'mb-json-project-'));
    const fakeHome = await mkdtemp(join(tmpdir(), 'mb-home-'));
    process.env.HOME = fakeHome;

    await mkdir(join(workspace, '.opencode'), { recursive: true });
    await writeFile(
      join(workspace, '.opencode', 'message-bridge.json'),
      JSON.stringify(
        createValidConfig({
          auth: {
            ak: 'project-json-ak',
            sk: 'project-json-sk',
          },
        }),
      ),
      'utf8',
    );

    try {
      const config = await loadConfig(workspace);
      expect(config.auth.ak).toBe('project-json-ak');
      expect(config.auth.sk).toBe('project-json-sk');
    } finally {
      await rm(workspace, { recursive: true, force: true });
      await rm(fakeHome, { recursive: true, force: true });
    }
  });

  test('loadConfig reads user message-bridge.json when project config is absent', async () => {
    const workspace = await mkdtemp(join(tmpdir(), 'mb-json-user-workspace-'));
    const fakeHome = await mkdtemp(join(tmpdir(), 'mb-home-'));
    process.env.HOME = fakeHome;

    await mkdir(join(fakeHome, '.config', 'opencode'), { recursive: true });
    await writeFile(
      join(fakeHome, '.config', 'opencode', 'message-bridge.json'),
      JSON.stringify(
        createValidConfig({
          auth: {
            ak: 'user-json-ak',
            sk: 'user-json-sk',
          },
        }),
      ),
      'utf8',
    );

    try {
      const config = await loadConfig(workspace);
      expect(config.auth.ak).toBe('user-json-ak');
      expect(config.auth.sk).toBe('user-json-sk');
    } finally {
      await rm(workspace, { recursive: true, force: true });
      await rm(fakeHome, { recursive: true, force: true });
    }
  });

  test('prefers message-bridge.jsonc over message-bridge.json in the same directory', async () => {
    const workspace = await mkdtemp(join(tmpdir(), 'mb-json-priority-'));
    const fakeHome = await mkdtemp(join(tmpdir(), 'mb-home-'));
    process.env.HOME = fakeHome;

    await mkdir(join(workspace, '.opencode'), { recursive: true });
    await writeFile(
      join(workspace, '.opencode', 'message-bridge.json'),
      JSON.stringify(
        createValidConfig({
          auth: {
            ak: 'project-json-ak',
            sk: 'project-json-sk',
          },
        }),
      ),
      'utf8',
    );
    await writeFile(
      join(workspace, '.opencode', 'message-bridge.jsonc'),
      JSON.stringify(
        createValidConfig({
          auth: {
            ak: 'project-jsonc-ak',
            sk: 'project-jsonc-sk',
          },
        }),
      ),
      'utf8',
    );

    try {
      const config = await loadConfig(workspace);
      expect(config.auth.ak).toBe('project-jsonc-ak');
      expect(config.auth.sk).toBe('project-jsonc-sk');
    } finally {
      await rm(workspace, { recursive: true, force: true });
      await rm(fakeHome, { recursive: true, force: true });
    }
  });

  test('supports upward lookup and finds parent message-bridge.json', async () => {
    const workspace = await mkdtemp(join(tmpdir(), 'mb-json-upward-'));
    const fakeHome = await mkdtemp(join(tmpdir(), 'mb-home-'));
    process.env.HOME = fakeHome;

    const nested = join(workspace, 'src', 'components');
    await mkdir(join(workspace, '.opencode'), { recursive: true });
    await mkdir(nested, { recursive: true });
    await writeFile(
      join(workspace, '.opencode', 'message-bridge.json'),
      JSON.stringify(
        createValidConfig({
          auth: {
            ak: 'parent-json-ak',
            sk: 'parent-json-sk',
          },
        }),
      ),
      'utf8',
    );

    try {
      const config = await loadConfig(nested);
      expect(config.auth.ak).toBe('parent-json-ak');
      expect(config.auth.sk).toBe('parent-json-sk');
    } finally {
      await rm(workspace, { recursive: true, force: true });
      await rm(fakeHome, { recursive: true, force: true });
    }
  });

  test('config.resolve.completed logs the actual loaded suffix path', async () => {
    const workspace = await mkdtemp(join(tmpdir(), 'mb-json-logs-'));
    const fakeHome = await mkdtemp(join(tmpdir(), 'mb-home-'));
    process.env.HOME = fakeHome;

    await mkdir(join(workspace, '.opencode'), { recursive: true });
    await writeFile(
      join(workspace, '.opencode', 'message-bridge.json'),
      JSON.stringify(createValidConfig()),
      'utf8',
    );

    const calls = [];
    const logger = new AppLogger(
      {
        app: {
          log: async (options) => {
            calls.push(options);
          },
        },
      },
      { component: 'test' },
      undefined,
      undefined,
      true,
    );

    try {
      await loadConfig(workspace, logger);
      await new Promise((r) => setTimeout(r, 10));

      const completed = calls.find((entry) => entry.body.message === 'config.resolve.completed');
      expect(completed).toBeDefined();
      expect(completed.body.extra.sources).toContain(
        `project:${join(workspace, '.opencode', 'message-bridge.json')}`,
      );
    } finally {
      await rm(workspace, { recursive: true, force: true });
      await rm(fakeHome, { recursive: true, force: true });
    }
  });

  test('does not fallback to .json when preferred .jsonc exists but is invalid', async () => {
    const workspace = await mkdtemp(join(tmpdir(), 'mb-json-parse-'));
    const fakeHome = await mkdtemp(join(tmpdir(), 'mb-home-'));
    process.env.HOME = fakeHome;

    await mkdir(join(workspace, '.opencode'), { recursive: true });
    await writeFile(
      join(workspace, '.opencode', 'message-bridge.jsonc'),
      '{"config_version":1,',
      'utf8',
    );
    await writeFile(
      join(workspace, '.opencode', 'message-bridge.json'),
      JSON.stringify(createValidConfig()),
      'utf8',
    );

    try {
      await expect(loadConfig(workspace)).rejects.toBeInstanceOf(ConfigValidationAggregateError);
    } finally {
      await rm(workspace, { recursive: true, force: true });
      await rm(fakeHome, { recursive: true, force: true });
    }
  });

  test('normalizes default toolType to OPENCODE', async () => {
    const workspace = await mkdtemp(join(tmpdir(), 'mb-json-defaults-'));
    const fakeHome = await mkdtemp(join(tmpdir(), 'mb-home-'));
    process.env.HOME = fakeHome;

    await mkdir(join(workspace, '.opencode'), { recursive: true });
    await writeFile(
      join(workspace, '.opencode', 'message-bridge.json'),
      JSON.stringify(createValidConfig({
        gateway: {
          url: 'ws://localhost:8081/ws/agent',
          heartbeatIntervalMs: 30000,
          reconnect: {
            baseMs: 1000,
            maxMs: 30000,
            exponential: true,
          },
          toolType: 'opencode',
        },
      })),
      'utf8',
    );

    try {
      const config = await loadConfig(workspace);
      expect(config.gateway.toolType).toBe('OPENCODE');
    } finally {
      await rm(workspace, { recursive: true, force: true });
      await rm(fakeHome, { recursive: true, force: true });
    }
  });

  test('supports overriding gateway.macAddress from environment', async () => {
    const workspace = await mkdtemp(join(tmpdir(), 'mb-json-mac-env-'));
    const fakeHome = await mkdtemp(join(tmpdir(), 'mb-home-'));
    const originalMacAddress = process.env.BRIDGE_GATEWAY_MAC_ADDRESS;
    process.env.HOME = fakeHome;
    process.env.BRIDGE_GATEWAY_MAC_ADDRESS = '11:22:33:44:55:66';

    await mkdir(join(workspace, '.opencode'), { recursive: true });
    await writeFile(
      join(workspace, '.opencode', 'message-bridge.json'),
      JSON.stringify(createValidConfig()),
      'utf8',
    );

    try {
      const config = await loadConfig(workspace);
      expect(config.gateway.macAddress).toBe('11:22:33:44:55:66');
    } finally {
      if (originalMacAddress === undefined) {
        delete process.env.BRIDGE_GATEWAY_MAC_ADDRESS;
      } else {
        process.env.BRIDGE_GATEWAY_MAC_ADDRESS = originalMacAddress;
      }
      await rm(workspace, { recursive: true, force: true });
      await rm(fakeHome, { recursive: true, force: true });
    }
  });
});
