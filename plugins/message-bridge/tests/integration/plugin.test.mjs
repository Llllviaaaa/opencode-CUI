import { describe, test, beforeEach } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

import { MessageBridgePlugin, default as DefaultPlugin } from '../../src/index.ts';
import { __resetRuntimeForTests, getOrCreateRuntime, getRuntime, stopRuntime } from '../../src/runtime/singleton.ts';

function createPluginClient(overrides = {}) {
  const base = {
    global: {},
    app: {},
    session: {
      create: async () => ({}),
      abort: async () => ({}),
      delete: async () => ({}),
      prompt: async () => ({}),
    },
    postSessionIdPermissionsPermissionId: async () => ({}),
    _client: {
      get: async (options) => {
        if (options?.url === '/global/health') {
          return { data: { healthy: true, version: '9.9.9' } };
        }
        return { data: [] };
      },
      post: async () => ({ data: undefined }),
    },
  };

  return {
    ...base,
    ...overrides,
    global: { ...base.global, ...(overrides.global ?? {}) },
    app: { ...base.app, ...(overrides.app ?? {}) },
    session: { ...base.session, ...(overrides.session ?? {}) },
    _client: { ...base._client, ...(overrides._client ?? {}) },
  };
}

function mockInput(overrides = {}) {
  return {
    client: {},
    project: {},
    directory: process.cwd(),
    worktree: process.cwd(),
    serverUrl: new URL('http://localhost:4096'),
    $: {},
    ...overrides,
  };
}

describe('plugin contract', () => {
  beforeEach(() => {
    __resetRuntimeForTests();
    process.env.BRIDGE_ENABLED = 'false';
  });

  test('exports named and default as same plugin function', () => {
    assert.strictEqual(typeof MessageBridgePlugin, 'function');
    assert.strictEqual(DefaultPlugin, MessageBridgePlugin);
  });

  test('PluginInput -> Hooks', async () => {
    const hooks = await MessageBridgePlugin(mockInput());
    assert.ok(hooks !== null && typeof hooks === 'object');
    assert.strictEqual(typeof hooks.event, 'function');
  });

  test('singleton runtime is idempotent across repeated init', async () => {
    const hooks1 = await MessageBridgePlugin(mockInput());
    const runtime1 = getRuntime();
    const hooks2 = await MessageBridgePlugin(mockInput());
    const runtime2 = getRuntime();

    assert.notStrictEqual(runtime1, undefined);
    assert.strictEqual(runtime2, runtime1);
    assert.strictEqual(typeof hooks1.event, 'function');
    assert.strictEqual(typeof hooks2.event, 'function');
  });

  test('logs injected client shape only once during first singleton init', async () => {
    const logs = [];
    const client = createPluginClient({
      app: {
        log: async (options) => {
          logs.push(options?.body);
          return true;
        },
      },
    });

    await MessageBridgePlugin(mockInput({ client }));
    await MessageBridgePlugin(mockInput({ client }));
    await new Promise((r) => setTimeout(r, 10));

    const shapeLogs = logs.filter((entry) => entry?.message === 'runtime.singleton.client_shape');
    assert.strictEqual(shapeLogs.length, 1);
    assert.ok(shapeLogs[0].extra.clientTopLevelKeys.includes('app'));
    assert.ok(shapeLogs[0].extra.clientTopLevelKeys.includes('global'));
    assert.ok(shapeLogs[0].extra.clientTopLevelKeys.includes('session'));
    assert.strictEqual(shapeLogs[0].extra.hasGlobalHealth, false);
    assert.strictEqual(shapeLogs[0].extra.hasAppHealth, false);
    assert.strictEqual(shapeLogs[0].extra.hasAppLog, true);
    assert.strictEqual(shapeLogs[0].extra.hasSessionCreate, true);
    assert.strictEqual(shapeLogs[0].extra.hasRawClientGet, true);
    assert.strictEqual(shapeLogs[0].extra.hasRawClientPost, true);
  });

  test('loader semantics: Object.entries + duplicate function references only init once', async () => {
    const mod = await import('../../src/index.ts');
    const seen = new Set();
    const hooks = [];

    for (const [, fn] of Object.entries(mod)) {
      if (typeof fn !== 'function') continue;
      if (seen.has(fn)) continue;
      seen.add(fn);
      hooks.push(await fn(mockInput()));
    }

    assert.strictEqual(hooks.length, 1);
    assert.notStrictEqual(getRuntime(), undefined);
  });

  test('failed initialization does not poison singleton and can recover on next init', async () => {
    const originalHome = process.env.HOME;
    const originalGatewayUrl = process.env.BRIDGE_GATEWAY_URL;
    const originalBridgeAuthAk = process.env.BRIDGE_AUTH_AK;
    const originalBridgeAuthSk = process.env.BRIDGE_AUTH_SK;
    const originalBridgeAk = process.env.BRIDGE_AK;
    const originalBridgeSk = process.env.BRIDGE_SK;
    const fakeHome = await mkdtemp(join(tmpdir(), 'mb-it-home-'));
    const fakeWorkspace = await mkdtemp(join(tmpdir(), 'mb-it-workspace-'));
    process.env.HOME = fakeHome;
    process.env.BRIDGE_ENABLED = 'true';
    delete process.env.BRIDGE_AUTH_AK;
    delete process.env.BRIDGE_AUTH_SK;
    delete process.env.BRIDGE_AK;
    delete process.env.BRIDGE_SK;
    process.env.BRIDGE_GATEWAY_URL = 'not-a-valid-url';

    try {
      const isolatedInput = mockInput({
        directory: fakeWorkspace,
        worktree: fakeWorkspace,
      });
      await assert.rejects(MessageBridgePlugin(isolatedInput));
      assert.strictEqual(getRuntime(), null);

      process.env.BRIDGE_ENABLED = 'false';
      delete process.env.BRIDGE_GATEWAY_URL;
      const hooks = await MessageBridgePlugin(isolatedInput);
      assert.strictEqual(typeof hooks.event, 'function');
      assert.notStrictEqual(getRuntime(), undefined);
    } finally {
      if (originalHome === undefined) {
        delete process.env.HOME;
      } else {
        process.env.HOME = originalHome;
      }
      if (originalBridgeAuthAk === undefined) {
        delete process.env.BRIDGE_AUTH_AK;
      } else {
        process.env.BRIDGE_AUTH_AK = originalBridgeAuthAk;
      }
      if (originalBridgeAuthSk === undefined) {
        delete process.env.BRIDGE_AUTH_SK;
      } else {
        process.env.BRIDGE_AUTH_SK = originalBridgeAuthSk;
      }
      if (originalBridgeAk === undefined) {
        delete process.env.BRIDGE_AK;
      } else {
        process.env.BRIDGE_AK = originalBridgeAk;
      }
      if (originalBridgeSk === undefined) {
        delete process.env.BRIDGE_SK;
      } else {
        process.env.BRIDGE_SK = originalBridgeSk;
      }
      if (originalGatewayUrl === undefined) {
        delete process.env.BRIDGE_GATEWAY_URL;
      } else {
        process.env.BRIDGE_GATEWAY_URL = originalGatewayUrl;
      }
      await rm(fakeHome, { recursive: true, force: true });
      await rm(fakeWorkspace, { recursive: true, force: true });
    }
  });

  test('stop during initialization does not resurrect runtime', async () => {
    process.env.BRIDGE_ENABLED = 'true';
    process.env.BRIDGE_AUTH_AK = 'ak-test';
    process.env.BRIDGE_AUTH_SK = 'sk-test';
    process.env.BRIDGE_GATEWAY_URL = 'ws://localhost:8081/ws/agent';

    const originalWebSocket = globalThis.WebSocket;
    class SlowOpenWebSocket {
      static OPEN = 1;
      constructor() {
        this.readyState = 0;
        setTimeout(() => {
          this.readyState = SlowOpenWebSocket.OPEN;
          this.onopen?.();
        }, 50);
      }
      send() {}
      close() {
        this.readyState = 3;
        this.onclose?.();
      }
    }

    globalThis.WebSocket = SlowOpenWebSocket;
    try {
      const initializingPromise = getOrCreateRuntime(mockInput({ client: createPluginClient() }));
      stopRuntime();
      await assert.rejects(initializingPromise);
      await new Promise((r) => setTimeout(r, 80));
      assert.strictEqual(getRuntime(), null);
    } finally {
      globalThis.WebSocket = originalWebSocket;
      delete process.env.BRIDGE_AUTH_AK;
      delete process.env.BRIDGE_AUTH_SK;
      delete process.env.BRIDGE_GATEWAY_URL;
      process.env.BRIDGE_ENABLED = 'false';
      __resetRuntimeForTests();
    }
  });
});
