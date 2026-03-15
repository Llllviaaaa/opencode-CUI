import { afterEach, describe, test } from 'node:test';
import assert from 'node:assert/strict';
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
    channel: 'opencode',
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

describe('config validation for sdk.baseUrl compatibility', () => {
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

    assert.deepStrictEqual(errors, []);
  });

  test('validateConfig allows sdk.baseUrl', () => {
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

    assert.deepStrictEqual(errors, []);
  });

  test('loadConfig keeps sdk.baseUrl when project config contains it', async () => {
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
      const config = await loadConfig(workspace);
      assert.strictEqual(config.sdk.timeoutMs, 10000);
      assert.strictEqual(config.sdk.baseUrl, 'http://localhost:54321');
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
      assert.strictEqual(config.enabled, true);
      await new Promise((r) => setTimeout(r, 10));

      const messages = calls.map((entry) => entry.body.message);
      assert.ok(messages.includes('config.resolve.started'));
      assert.ok(messages.includes('config.source.loaded'));
      assert.ok(messages.includes('config.resolve.completed'));
      assert.ok(messages.includes('config.validation.passed'));

      const completed = calls.find((entry) => entry.body.message === 'config.resolve.completed');
      assert.strictEqual(completed.body.extra.component, 'config');
      assert.ok(completed.body.extra.sources.includes('default'));
      assert.ok(completed.body.extra.sources.includes(
        `project:${join(workspace, '.opencode', 'message-bridge.jsonc')}`,
      ));
    } finally {
      await rm(workspace, { recursive: true, force: true });
      await rm(fakeHome, { recursive: true, force: true });
    }
  });

  test('loadConfig does not log validation failures for sdk.baseUrl', async () => {
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
      const config = await loadConfig(workspace, logger);
      assert.strictEqual(config.sdk.baseUrl, 'http://localhost:54321');
      await new Promise((r) => setTimeout(r, 10));

      const failure = calls.find((entry) => entry.body.message === 'config.validation.failed');
      assert.strictEqual(failure, undefined);
      assert.ok(calls.some((entry) => entry.body.message === 'config.validation.passed'));
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
      assert.strictEqual(config.auth.ak, 'project-json-ak');
      assert.strictEqual(config.auth.sk, 'project-json-sk');
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
      assert.strictEqual(config.auth.ak, 'user-json-ak');
      assert.strictEqual(config.auth.sk, 'user-json-sk');
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
      assert.strictEqual(config.auth.ak, 'project-jsonc-ak');
      assert.strictEqual(config.auth.sk, 'project-jsonc-sk');
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
      assert.strictEqual(config.auth.ak, 'parent-json-ak');
      assert.strictEqual(config.auth.sk, 'parent-json-sk');
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
      assert.notStrictEqual(completed, undefined);
      assert.ok(completed.body.extra.sources.includes(
        `project:${join(workspace, '.opencode', 'message-bridge.json')}`,
      ));
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
      await assert.rejects(
        loadConfig(workspace),
        (err) => err instanceof ConfigValidationAggregateError,
      );
    } finally {
      await rm(workspace, { recursive: true, force: true });
      await rm(fakeHome, { recursive: true, force: true });
    }
  });

  test('normalizes default channel to opencode', async () => {
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
          channel: 'opencode',
        },
      })),
      'utf8',
    );

    try {
      const config = await loadConfig(workspace);
      assert.strictEqual(config.gateway.channel, 'opencode');
    } finally {
      await rm(workspace, { recursive: true, force: true });
      await rm(fakeHome, { recursive: true, force: true });
    }
  });

  test('loads gateway.channel from BRIDGE_GATEWAY_CHANNEL', async () => {
    const workspace = await mkdtemp(join(tmpdir(), 'mb-json-env-channel-'));
    const fakeHome = await mkdtemp(join(tmpdir(), 'mb-home-'));
    const originalChannel = process.env.BRIDGE_GATEWAY_CHANNEL;
    process.env.HOME = fakeHome;
    process.env.BRIDGE_GATEWAY_CHANNEL = '  miniapp  ';

    await mkdir(join(workspace, '.opencode'), { recursive: true });
    await writeFile(
      join(workspace, '.opencode', 'message-bridge.json'),
      JSON.stringify(createValidConfig()),
      'utf8',
    );

    try {
      const config = await loadConfig(workspace);
      assert.strictEqual(config.gateway.channel, 'miniapp');
    } finally {
      if (originalChannel === undefined) {
        delete process.env.BRIDGE_GATEWAY_CHANNEL;
      } else {
        process.env.BRIDGE_GATEWAY_CHANNEL = originalChannel;
      }
      await rm(workspace, { recursive: true, force: true });
      await rm(fakeHome, { recursive: true, force: true });
    }
  });

  test('ignores removed BRIDGE_GATEWAY_TOOL_TYPE override', async () => {
    const workspace = await mkdtemp(join(tmpdir(), 'mb-json-env-tooltype-'));
    const fakeHome = await mkdtemp(join(tmpdir(), 'mb-home-'));
    const originalToolType = process.env.BRIDGE_GATEWAY_TOOL_TYPE;
    process.env.HOME = fakeHome;
    process.env.BRIDGE_GATEWAY_TOOL_TYPE = 'legacy-tool-type';

    await mkdir(join(workspace, '.opencode'), { recursive: true });
    await writeFile(
      join(workspace, '.opencode', 'message-bridge.json'),
      JSON.stringify(createValidConfig()),
      'utf8',
    );

    try {
      const config = await loadConfig(workspace);
      assert.strictEqual(config.gateway.channel, 'opencode');
    } finally {
      if (originalToolType === undefined) {
        delete process.env.BRIDGE_GATEWAY_TOOL_TYPE;
      } else {
        process.env.BRIDGE_GATEWAY_TOOL_TYPE = originalToolType;
      }
      await rm(workspace, { recursive: true, force: true });
      await rm(fakeHome, { recursive: true, force: true });
    }
  });

  test('ignores removed gateway metadata environment overrides', async () => {
    const workspace = await mkdtemp(join(tmpdir(), 'mb-json-mac-env-'));
    const fakeHome = await mkdtemp(join(tmpdir(), 'mb-home-'));
    const originalDeviceName = process.env.BRIDGE_GATEWAY_DEVICE_NAME;
    const originalMacAddress = process.env.BRIDGE_GATEWAY_MAC_ADDRESS;
    const originalToolVersion = process.env.BRIDGE_GATEWAY_TOOL_VERSION;
    process.env.HOME = fakeHome;
    process.env.BRIDGE_GATEWAY_DEVICE_NAME = 'env-device';
    process.env.BRIDGE_GATEWAY_MAC_ADDRESS = '11:22:33:44:55:66';
    process.env.BRIDGE_GATEWAY_TOOL_VERSION = '9.9.9';

    await mkdir(join(workspace, '.opencode'), { recursive: true });
    await writeFile(
      join(workspace, '.opencode', 'message-bridge.json'),
      JSON.stringify(createValidConfig()),
      'utf8',
    );

    try {
      const config = await loadConfig(workspace);
      assert.strictEqual('deviceName' in config.gateway, false);
      assert.strictEqual('macAddress' in config.gateway, false);
      assert.strictEqual('toolVersion' in config.gateway, false);
    } finally {
      if (originalDeviceName === undefined) {
        delete process.env.BRIDGE_GATEWAY_DEVICE_NAME;
      } else {
        process.env.BRIDGE_GATEWAY_DEVICE_NAME = originalDeviceName;
      }
      if (originalMacAddress === undefined) {
        delete process.env.BRIDGE_GATEWAY_MAC_ADDRESS;
      } else {
        process.env.BRIDGE_GATEWAY_MAC_ADDRESS = originalMacAddress;
      }
      if (originalToolVersion === undefined) {
        delete process.env.BRIDGE_GATEWAY_TOOL_VERSION;
      } else {
        process.env.BRIDGE_GATEWAY_TOOL_VERSION = originalToolVersion;
      }
      await rm(workspace, { recursive: true, force: true });
      await rm(fakeHome, { recursive: true, force: true });
    }
  });
});
