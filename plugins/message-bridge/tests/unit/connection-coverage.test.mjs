import { describe, test, expect, beforeEach, afterEach } from 'bun:test';

import { DefaultGatewayConnection } from '../../dist/connection/GatewayConnection.js';

class ScriptedWebSocket {
  static OPEN = 1;
  static CLOSED = 3;
  static instances = [];
  static scripts = [];

  constructor(url) {
    this.url = url;
    this.readyState = 0;
    this.sent = [];
    this.script = ScriptedWebSocket.scripts.shift() ?? { open: true };
    ScriptedWebSocket.instances.push(this);

    setTimeout(() => {
      if (this.script.errorOnOpen) {
        this.onerror?.(new Error('socket error'));
        return;
      }
      if (this.script.closeBeforeOpen) {
        this.readyState = ScriptedWebSocket.CLOSED;
        this.onclose?.();
        return;
      }
      this.readyState = ScriptedWebSocket.OPEN;
      this.onopen?.();
      if (this.script.closeAfterOpenMs !== undefined) {
        setTimeout(() => {
          this.readyState = ScriptedWebSocket.CLOSED;
          this.onclose?.();
        }, this.script.closeAfterOpenMs);
      }
    }, this.script.openDelayMs ?? 0);
  }

  send(raw) {
    if (this.script.sendThrows) {
      throw new Error('send failed');
    }
    this.sent.push(JSON.parse(raw));
  }

  close() {
    this.readyState = ScriptedWebSocket.CLOSED;
    this.onclose?.();
  }

  emitMessage(data) {
    this.onmessage?.({ data });
  }
}

function registerMessage() {
  return {
    type: 'register',
    deviceName: 'dev',
    os: 'darwin',
    toolType: 'opencode',
    toolVersion: '1.0.0',
  };
}

describe('DefaultGatewayConnection coverage', () => {
  const originalWebSocket = globalThis.WebSocket;

  beforeEach(() => {
    ScriptedWebSocket.instances = [];
    ScriptedWebSocket.scripts = [];
    globalThis.WebSocket = ScriptedWebSocket;
  });

  afterEach(() => {
    globalThis.WebSocket = originalWebSocket;
  });

  test('rejects on aborted signal before connect', async () => {
    const controller = new AbortController();
    controller.abort();
    const conn = new DefaultGatewayConnection({
      url: 'ws://localhost:8081/ws/agent',
      abortSignal: controller.signal,
      registerMessage: registerMessage(),
    });
    await expect(conn.connect()).rejects.toBeDefined();
    expect(conn.getState()).toBe('DISCONNECTED');
  });

  test('connect/disconnect lifecycle and send guard', async () => {
    const conn = new DefaultGatewayConnection({
      url: 'ws://localhost:8081/ws/agent',
      heartbeatIntervalMs: 5,
      registerMessage: registerMessage(),
    });
    await conn.connect();
    expect(conn.getState()).toBe('READY');
    expect(conn.isConnected()).toBe(true);

    expect(() => conn.send({ type: 'x', payload: 1 })).not.toThrow();
    conn.disconnect();
    expect(conn.getState()).toBe('DISCONNECTED');
    expect(() => conn.send({ type: 'x' })).toThrow();
  });

  test('rejects on invalid url and websocket error', async () => {
    const badUrl = new DefaultGatewayConnection({
      url: 'not-a-valid-url',
      registerMessage: registerMessage(),
    });
    await expect(badUrl.connect()).rejects.toBeDefined();

    ScriptedWebSocket.scripts.push({ errorOnOpen: true });
    const errorConn = new DefaultGatewayConnection({
      url: 'ws://localhost:8081/ws/agent',
      registerMessage: registerMessage(),
    });
    errorConn.on('error', () => {});
    await expect(errorConn.connect()).rejects.toBeDefined();
  });

  test('reconnects after opened connection closes unexpectedly', async () => {
    ScriptedWebSocket.scripts.push({ closeAfterOpenMs: 0 }, { open: true });
    const conn = new DefaultGatewayConnection({
      url: 'ws://localhost:8081/ws/agent',
      reconnectBaseMs: 5,
      reconnectMaxMs: 5,
      registerMessage: registerMessage(),
    });
    await conn.connect();
    await new Promise((r) => setTimeout(r, 30));
    expect(ScriptedWebSocket.instances.length).toBeGreaterThanOrEqual(2);
    conn.disconnect();
  });

  test('does not reconnect when aborted after open', async () => {
    const controller = new AbortController();
    ScriptedWebSocket.scripts.push({ open: true });
    const conn = new DefaultGatewayConnection({
      url: 'ws://localhost:8081/ws/agent',
      reconnectBaseMs: 5,
      reconnectMaxMs: 5,
      abortSignal: controller.signal,
      registerMessage: registerMessage(),
    });
    await conn.connect();
    controller.abort();
    await new Promise((r) => setTimeout(r, 20));
    expect(ScriptedWebSocket.instances.length).toBe(1);
    expect(conn.getState()).toBe('READY');
  });

  test('parses downstream messages and ignores non-json', async () => {
    const messages = [];
    const conn = new DefaultGatewayConnection({
      url: 'ws://localhost:8081/ws/agent',
      registerMessage: registerMessage(),
    });
    conn.on('message', (msg) => messages.push(msg));
    await conn.connect();

    const ws = ScriptedWebSocket.instances[0];
    ws.emitMessage('{"x":1}');
    ws.emitMessage(new Uint8Array([123, 34, 121, 34, 58, 50, 125])); // {"y":2}
    ws.emitMessage(Uint8Array.from([123, 34, 122, 34, 58, 51, 125]).buffer); // {"z":3}
    ws.emitMessage(new Blob(['{"k":4}']));
    ws.emitMessage('not-json');
    await new Promise((r) => setTimeout(r, 10));

    expect(messages).toEqual([{ x: 1 }, { y: 2 }, { z: 3 }, { k: 4 }]);
    conn.disconnect();
  });
});
