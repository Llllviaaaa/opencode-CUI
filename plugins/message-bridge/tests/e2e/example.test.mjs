import { describe, test, expect, beforeEach, afterEach } from 'bun:test';

import { DefaultGatewayConnection } from '../../dist/connection/GatewayConnection.js';

class FakeWebSocket {
  static OPEN = 1;
  static instances = [];
  static mode = 'open';

  constructor(url) {
    this.url = url;
    this.readyState = 0;
    this.sent = [];
    FakeWebSocket.instances.push(this);
    setTimeout(() => {
      if (FakeWebSocket.mode === 'close') {
        this.readyState = 3;
        this.onclose?.();
        return;
      }
      this.readyState = FakeWebSocket.OPEN;
      this.onopen?.();
      this.onmessage?.({ data: JSON.stringify({ type: 'register_ok' }) });
    }, 0);
  }

  send(data) {
    this.sent.push(JSON.parse(data));
  }

  close() {
    this.readyState = 3;
    this.onclose?.();
  }
}

describe('gateway connection bun-only contract', () => {
  const originalWebSocket = globalThis.WebSocket;

  beforeEach(() => {
    FakeWebSocket.instances = [];
    FakeWebSocket.mode = 'open';
    globalThis.WebSocket = FakeWebSocket;
  });

  afterEach(() => {
    globalThis.WebSocket = originalWebSocket;
  });

  test('connect sends register with required fields', async () => {
    const conn = new DefaultGatewayConnection({
      url: 'ws://localhost:8081/ws/agent',
      heartbeatIntervalMs: 1000,
      registerMessage: {
        type: 'register',
        deviceName: 'dev',
        macAddress: 'aa:bb:cc:dd:ee:ff',
        os: 'darwin',
        toolType: 'OPENCODE',
        toolVersion: '1.0.0',
      },
    });

    await conn.connect();

    const ws = FakeWebSocket.instances[0];
    expect(ws.sent[0]).toEqual({
      type: 'register',
      deviceName: 'dev',
      macAddress: 'aa:bb:cc:dd:ee:ff',
      os: 'darwin',
      toolType: 'OPENCODE',
      toolVersion: '1.0.0',
    });

    conn.disconnect();
  });

  test('heartbeat uses timestamp field', async () => {
    const conn = new DefaultGatewayConnection({
      url: 'ws://localhost:8081/ws/agent',
      heartbeatIntervalMs: 5,
      registerMessage: {
        type: 'register',
        deviceName: 'dev',
        macAddress: 'aa:bb:cc:dd:ee:ff',
        os: 'darwin',
        toolType: 'OPENCODE',
        toolVersion: '1.0.0',
      },
    });

    await conn.connect();
    await new Promise((r) => setTimeout(r, 20));

    const ws = FakeWebSocket.instances[0];
    const heartbeat = ws.sent.find((item) => item.type === 'heartbeat');
    expect(heartbeat).toBeDefined();
    expect(typeof heartbeat.timestamp).toBe('string');
    expect('ts' in heartbeat).toBe(false);

    conn.disconnect();
  });

  test('connect rejects when websocket closes before open', async () => {
    FakeWebSocket.mode = 'close';

    const conn = new DefaultGatewayConnection({
      url: 'ws://localhost:8081/ws/agent',
      reconnectBaseMs: 5,
      reconnectMaxMs: 5,
      registerMessage: {
        type: 'register',
        deviceName: 'dev',
        macAddress: 'aa:bb:cc:dd:ee:ff',
        os: 'darwin',
        toolType: 'OPENCODE',
        toolVersion: '1.0.0',
      },
    });

    await expect(conn.connect()).rejects.toBeDefined();
    await new Promise((r) => setTimeout(r, 30));
    expect(FakeWebSocket.instances.length).toBe(1);
  });
});
