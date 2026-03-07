import { describe, test, expect, beforeEach } from 'bun:test';

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
    process.env.BRIDGE_ENABLED = 'true';
    delete process.env.BRIDGE_AUTH_AK;
    delete process.env.BRIDGE_AUTH_SK;

    await expect(MessageBridgePlugin(mockInput())).rejects.toBeDefined();
    expect(getRuntime()).toBeNull();

    process.env.BRIDGE_ENABLED = 'false';
    const hooks = await MessageBridgePlugin(mockInput());
    expect(typeof hooks.event).toBe('function');
    expect(getRuntime()).toBeDefined();
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
