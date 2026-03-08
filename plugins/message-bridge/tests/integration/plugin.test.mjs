import { describe, test, expect, beforeEach } from 'bun:test';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

import { MessageBridgePlugin, default as DefaultPlugin } from '../../dist/index.js';
import { __resetRuntimeForTests, getOrCreateRuntime, getRuntime, stopRuntime } from '../../dist/runtime/singleton.js';

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
    expect(typeof MessageBridgePlugin).toBe('function');
    expect(DefaultPlugin).toBe(MessageBridgePlugin);
  });

  test('PluginInput -> Hooks', async () => {
    const hooks = await MessageBridgePlugin(mockInput());
    expect(hooks).toBeObject();
    expect(typeof hooks.event).toBe('function');
  });

  test('singleton runtime is idempotent across repeated init', async () => {
    const hooks1 = await MessageBridgePlugin(mockInput());
    const runtime1 = getRuntime();
    const hooks2 = await MessageBridgePlugin(mockInput());
    const runtime2 = getRuntime();

    expect(runtime1).toBeDefined();
    expect(runtime2).toBe(runtime1);
    expect(typeof hooks1.event).toBe('function');
    expect(typeof hooks2.event).toBe('function');
  });

  test('loader semantics: Object.entries + duplicate function references only init once', async () => {
    const mod = await import('../../dist/index.js');
    const seen = new Set();
    const hooks = [];

    for (const [, fn] of Object.entries(mod)) {
      if (typeof fn !== 'function') continue;
      if (seen.has(fn)) continue;
      seen.add(fn);
      hooks.push(await fn(mockInput()));
    }

    expect(hooks.length).toBe(1);
    expect(getRuntime()).toBeDefined();
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
      await expect(MessageBridgePlugin(isolatedInput)).rejects.toBeDefined();
      expect(getRuntime()).toBeNull();

      process.env.BRIDGE_ENABLED = 'false';
      delete process.env.BRIDGE_GATEWAY_URL;
      const hooks = await MessageBridgePlugin(isolatedInput);
      expect(typeof hooks.event).toBe('function');
      expect(getRuntime()).toBeDefined();
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
      const initializingPromise = getOrCreateRuntime(mockInput());
      stopRuntime();
      await expect(initializingPromise).rejects.toBeDefined();
      await new Promise((r) => setTimeout(r, 80));
      expect(getRuntime()).toBeNull();
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
