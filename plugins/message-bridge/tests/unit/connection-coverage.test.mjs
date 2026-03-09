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
        this.onerror?.(this.script.errorEvent ?? new Error('socket error'));
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

function createLoggerRecorder() {
  const entries = [];
  const logger = {
    debug(message, extra) {
      entries.push({ level: 'debug', message, extra });
    },
    info(message, extra) {
      entries.push({ level: 'info', message, extra });
    },
    warn(message, extra) {
      entries.push({ level: 'warn', message, extra });
    },
    error(message, extra) {
      entries.push({ level: 'error', message, extra });
    },
    child() {
      return logger;
    },
    getTraceId() {
      return 'runtime-trace-1';
    },
  };

  return { logger, entries };
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

  test('logs websocket error details when the runtime provides them', async () => {
    const errorLogs = [];
    ScriptedWebSocket.scripts.push({ errorOnOpen: true });
    const conn = new DefaultGatewayConnection({
      url: 'ws://localhost:8081/ws/agent',
      registerMessage: registerMessage(),
      logger: {
        debug() {},
        info() {},
        warn() {},
        error(message, extra) {
          errorLogs.push({ message, extra });
        },
        child() {
          return this;
        },
        getTraceId() {
          return 'trace-test';
        },
      },
    });
    conn.on('error', () => {});

    await expect(conn.connect()).rejects.toBeDefined();

    expect(errorLogs).toContainEqual({
      message: 'gateway.error',
      extra: {
        error: 'gateway_websocket_error',
        state: 'CONNECTING',
        errorDetail: 'socket error',
        errorName: 'Error',
        errorType: 'Error',
        rawType: 'Error',
      },
    });
  });

  test('logs websocket event metadata when onerror receives an event object', async () => {
    const errorLogs = [];
    ScriptedWebSocket.scripts.push({
      errorOnOpen: true,
      errorEvent: {
        type: 'error',
        message: 'socket error',
        target: { readyState: 0 },
      },
    });
    const conn = new DefaultGatewayConnection({
      url: 'ws://localhost:8081/ws/agent',
      registerMessage: registerMessage(),
      logger: {
        debug() {},
        info() {},
        warn() {},
        error(message, extra) {
          errorLogs.push({ message, extra });
        },
        child() {
          return this;
        },
        getTraceId() {
          return 'trace-test';
        },
      },
    });
    conn.on('error', () => {});

    await expect(conn.connect()).rejects.toBeDefined();

    expect(errorLogs).toContainEqual({
      message: 'gateway.error',
      extra: {
        error: 'gateway_websocket_error',
        state: 'CONNECTING',
        errorDetail: 'socket error',
        errorType: 'error',
        rawType: 'Object',
        eventType: 'error',
        readyState: 0,
      },
    });
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

  test('logs payload bytes and message ids when sending', async () => {
    const { logger, entries } = createLoggerRecorder();
    const conn = new DefaultGatewayConnection({
      url: 'ws://localhost:8081/ws/agent',
      registerMessage: registerMessage(),
      logger,
    });
    await conn.connect();

    conn.send(
      {
        type: 'tool_event',
        sessionId: 'skill-1',
        event: { type: 'message.part.updated' },
        envelope: { messageId: 'bridge-1' },
      },
      {
        traceId: 'bridge-1',
        runtimeTraceId: 'runtime-trace-1',
        bridgeMessageId: 'bridge-1',
        sessionId: 'skill-1',
        toolSessionId: 'tool-1',
        eventType: 'message.part.updated',
        opencodeMessageId: 'op-msg-1',
        opencodePartId: 'part-1',
      },
    );

    const sendLog = entries.find(
      (entry) => entry.message === 'gateway.send' && entry.extra.traceId === 'bridge-1',
    );
    expect(sendLog).toBeDefined();
    expect(sendLog.extra.messageType).toBe('tool_event');
    expect(sendLog.extra.payloadBytes).toBeGreaterThan(0);
    expect(sendLog.extra.traceId).toBe('bridge-1');
    expect('bridgeMessageId' in sendLog.extra).toBe(false);
    expect(sendLog.extra.opencodeMessageId).toBe('op-msg-1');
    expect(sendLog.extra.opencodePartId).toBe('part-1');
    expect(sendLog.extra.eventType).toBe('message.part.updated');

    conn.disconnect();
  });

  test('logs frame bytes and gatewayMessageId for received frames', async () => {
    const { logger, entries } = createLoggerRecorder();
    const conn = new DefaultGatewayConnection({
      url: 'ws://localhost:8081/ws/agent',
      registerMessage: registerMessage(),
      logger,
    });
    await conn.connect();

    const ws = ScriptedWebSocket.instances[0];
    ws.emitMessage(
      JSON.stringify({
        type: 'invoke',
        action: 'chat',
        sessionId: 'skill-1',
        payload: { toolSessionId: 'tool-1', text: 'hello' },
        envelope: { messageId: 'gw-1' },
      }),
    );
    await new Promise((r) => setTimeout(r, 10));

    const receivedLog = entries.find((entry) => entry.message === 'gateway.message.received');
    expect(receivedLog).toBeDefined();
    expect(receivedLog.extra.messageType).toBe('invoke');
    expect(receivedLog.extra.gatewayMessageId).toBe('gw-1');
    expect(receivedLog.extra.frameBytes).toBeGreaterThan(0);

    conn.disconnect();
  });

  test('logs minimal last-message summary on close', async () => {
    const { logger, entries } = createLoggerRecorder();
    const conn = new DefaultGatewayConnection({
      url: 'ws://localhost:8081/ws/agent',
      registerMessage: registerMessage(),
      logger,
    });
    await conn.connect();

    conn.send(
      {
        type: 'tool_event',
        sessionId: 'skill-1',
        event: { type: 'message.updated' },
        envelope: { messageId: 'bridge-last-1' },
      },
      {
        traceId: 'bridge-last-1',
        runtimeTraceId: 'runtime-trace-1',
        bridgeMessageId: 'bridge-last-1',
        sessionId: 'skill-1',
        eventType: 'message.updated',
        opencodeMessageId: 'op-msg-last-1',
      },
    );
    conn.disconnect();

    const closeLog = entries.find((entry) => entry.message === 'gateway.close');
    expect(closeLog).toBeDefined();
    expect(closeLog.extra.lastMessageDirection).toBe('sent');
    expect(closeLog.extra.lastMessageType).toBe('tool_event');
    expect(closeLog.extra.lastMessageId).toBe('bridge-last-1');
    expect(closeLog.extra.lastPayloadBytes).toBeGreaterThan(0);
    expect(closeLog.extra.lastEventType).toBe('message.updated');
    expect(closeLog.extra.lastOpencodeMessageId).toBe('op-msg-last-1');
  });
});
