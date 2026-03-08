import { afterEach, describe, expect, test } from 'bun:test';
import { mkdtemp, mkdir, writeFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

import {
  ConfigValidationAggregateError,
  loadConfig,
  validateConfig,
} from '../../dist/config/index.js';
import { AppLogger } from '../../dist/runtime/AppLogger.js';

const originalHome = process.env.HOME;

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
        allowlist: ['message.*'],
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
        allowlist: ['message.*'],
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
          allowlist: ['message.*'],
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
          allowlist: ['message.*'],
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
          allowlist: ['message.*'],
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
