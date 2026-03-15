import { describe, test, beforeEach, afterEach } from 'node:test';
import assert from 'node:assert/strict';

import { DefaultGatewayConnection } from '../../src/connection/GatewayConnection.ts';

class ScriptedWebSocket {
  static OPEN = 1;
  static CLOSED = 3;
  static instances = [];
  static scripts = [];

  constructor(url, protocols) {
    this.url = url;
    this.protocols = protocols;
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
      if (this.script.autoRegisterOk !== false) {
        const emitRegisterOk = () => {
          this.onmessage?.({ data: JSON.stringify({ type: 'register_ok' }) });
        };
        if (this.script.registerOkDelayMs !== undefined) {
          setTimeout(emitRegisterOk, this.script.registerOkDelayMs);
        } else {
          emitRegisterOk();
        }
      }
      if (this.script.closeAfterOpenMs !== undefined) {
        setTimeout(() => {
          this.readyState = ScriptedWebSocket.CLOSED;
          this.onclose?.(this.script.closeEvent ?? {
            code: this.script.closeCode,
            reason: this.script.closeReason,
            wasClean: this.script.wasClean,
          });
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
    macAddress: 'aa:bb:cc:dd:ee:ff',
    os: 'darwin',
    toolType: 'channel',
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
    await assert.rejects(conn.connect());
    assert.strictEqual(conn.getState(), 'DISCONNECTED');
  });

  test('connect/disconnect lifecycle and send guard', async () => {
    const conn = new DefaultGatewayConnection({
      url: 'ws://localhost:8081/ws/agent',
      heartbeatIntervalMs: 5,
      registerMessage: registerMessage(),
    });
    await conn.connect();
    assert.strictEqual(conn.getState(), 'READY');
    assert.strictEqual(conn.isConnected(), true);

    assert.doesNotThrow(() => conn.send({ type: 'x', payload: 1 }));
    conn.disconnect();
    assert.strictEqual(conn.getState(), 'DISCONNECTED');
    assert.throws(() => conn.send({ type: 'x' }));
  });

  test('passes gateway auth via websocket subprotocol instead of query params', async () => {
    const conn = new DefaultGatewayConnection({
      url: 'ws://localhost:8081/ws/agent',
      authPayloadProvider: () => ({
        ak: 'test-ak-001',
        ts: '1700000000',
        nonce: 'nonce-001',
        sign: 'sig+/=',
      }),
      registerMessage: registerMessage(),
    });

    await conn.connect();

    const ws = ScriptedWebSocket.instances[0];
    assert.strictEqual(ws.url, 'ws://localhost:8081/ws/agent');
    assert.strictEqual(ws.protocols.length, 1);
    assert.ok(ws.protocols[0].startsWith('auth.'));

    const encoded = ws.protocols[0].slice('auth.'.length);
    const decoded = JSON.parse(Buffer.from(encoded, 'base64url').toString('utf8'));
    assert.deepStrictEqual(decoded, {
      ak: 'test-ak-001',
      ts: '1700000000',
      nonce: 'nonce-001',
      sign: 'sig+/=',
    });

    conn.disconnect();
  });

  test('waits for register_ok before entering READY', async () => {
    ScriptedWebSocket.scripts.push({ autoRegisterOk: false });
    const states = [];
    const messages = [];
    const conn = new DefaultGatewayConnection({
      url: 'ws://localhost:8081/ws/agent',
      registerMessage: registerMessage(),
    });
    conn.on('stateChange', (state) => states.push(state));
    conn.on('message', (message) => messages.push(message));

    await conn.connect();

    const ws = ScriptedWebSocket.instances[0];
    assert.strictEqual(conn.getState(), 'CONNECTED');
    assert.ok(ws.sent.some(e => JSON.stringify(e) === JSON.stringify(registerMessage())));
    assert.throws(() => conn.send({ type: 'tool_event', payload: 1 }));

    ws.emitMessage(JSON.stringify({ type: 'invoke', action: 'chat', payload: { toolSessionId: 's-1', text: 'hi' } }));
    assert.deepStrictEqual(messages, []);

    ws.emitMessage(JSON.stringify({ type: 'register_ok' }));
    await new Promise((r) => setTimeout(r, 0));

    assert.strictEqual(conn.getState(), 'READY');
    assert.deepStrictEqual(states, ['CONNECTING', 'CONNECTED', 'READY']);

    conn.disconnect();
  });

  test('closes on register_rejected and never becomes READY', async () => {
    ScriptedWebSocket.scripts.push({ autoRegisterOk: false });
    const { logger, entries } = createLoggerRecorder();
    const states = [];
    const conn = new DefaultGatewayConnection({
      url: 'ws://localhost:8081/ws/agent',
      registerMessage: registerMessage(),
      logger,
    });
    conn.on('stateChange', (state) => states.push(state));

    await conn.connect();

    const ws = ScriptedWebSocket.instances[0];
    ws.emitMessage(JSON.stringify({ type: 'register_rejected', reason: 'device_conflict' }));
    await new Promise((r) => setTimeout(r, 0));

    assert.strictEqual(conn.getState(), 'DISCONNECTED');
    assert.deepStrictEqual(states, ['CONNECTING', 'CONNECTED', 'DISCONNECTED']);
    assert.ok(entries.some(e => JSON.stringify(e) === JSON.stringify({
      level: 'error',
      message: 'gateway.register.rejected',
      extra: { reason: 'device_conflict' },
    })));
    conn.disconnect();
  });

  test('rejects on invalid url and websocket error', async () => {
    const badUrl = new DefaultGatewayConnection({
      url: 'not-a-valid-url',
      registerMessage: registerMessage(),
    });
    await assert.rejects(badUrl.connect());

    ScriptedWebSocket.scripts.push({ errorOnOpen: true });
    const errorConn = new DefaultGatewayConnection({
      url: 'ws://localhost:8081/ws/agent',
      registerMessage: registerMessage(),
    });
    errorConn.on('error', () => {});
    await assert.rejects(errorConn.connect());
    errorConn.disconnect();
    badUrl.disconnect();
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

    await assert.rejects(conn.connect());

    assert.ok(errorLogs.some(e => JSON.stringify(e) === JSON.stringify({
      message: 'gateway.error',
      extra: {
        error: 'gateway_websocket_error',
        state: 'CONNECTING',
        errorDetail: 'socket error',
        errorName: 'Error',
        errorType: 'Error',
        rawType: 'Error',
      },
    })));
    conn.disconnect();
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

    await assert.rejects(conn.connect());

    assert.ok(errorLogs.some(e => JSON.stringify(e) === JSON.stringify({
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
    })));
    conn.disconnect();
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
    assert.ok(ScriptedWebSocket.instances.length >= 2);
    conn.disconnect();
  });

  for (const closeCode of [4403, 4408, 4409]) {
    test(`does not reconnect on gateway rejection close code ${closeCode}`, async () => {
      const { logger, entries } = createLoggerRecorder();
      ScriptedWebSocket.scripts.push({
        closeAfterOpenMs: 0,
        closeCode,
        closeReason: `rejected-${closeCode}`,
        wasClean: true,
      });
      const conn = new DefaultGatewayConnection({
        url: 'ws://localhost:8081/ws/agent',
        reconnectBaseMs: 5,
        reconnectMaxMs: 5,
        registerMessage: registerMessage(),
        logger,
      });

      await conn.connect();
      await new Promise((r) => setTimeout(r, 30));

      assert.strictEqual(ScriptedWebSocket.instances.length, 1);
      assert.strictEqual(conn.getState(), 'DISCONNECTED');
      assert.ok(entries.some(e => JSON.stringify(e) === JSON.stringify({
        level: 'warn',
        message: 'gateway.close.rejected',
        extra: {
          code: closeCode,
          reason: `rejected-${closeCode}`,
          rejected: true,
        },
      })));
      conn.disconnect();
    });
  }

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
    assert.strictEqual(ScriptedWebSocket.instances.length, 1);
    assert.strictEqual(conn.getState(), 'READY');
    conn.disconnect();
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

    assert.deepStrictEqual(messages, [{ x: 1 }, { y: 2 }, { z: 3 }, { k: 4 }]);
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
    assert.notStrictEqual(sendLog, undefined);
    assert.strictEqual(sendLog.extra.messageType, 'tool_event');
    assert.ok(sendLog.extra.payloadBytes > 0);
    assert.strictEqual(sendLog.extra.traceId, 'bridge-1');
    assert.strictEqual('bridgeMessageId' in sendLog.extra, false);
    assert.strictEqual(sendLog.extra.opencodeMessageId, 'op-msg-1');
    assert.strictEqual(sendLog.extra.opencodePartId, 'part-1');
    assert.strictEqual(sendLog.extra.eventType, 'message.part.updated');

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
        messageId: 'gw-1',
        type: 'invoke',
        action: 'chat',
        welinkSessionId: 'skill-1',
        payload: { toolSessionId: 'tool-1', text: 'hello' },
      }),
    );
    await new Promise((r) => setTimeout(r, 10));

    const receivedLog = [...entries].reverse().find(
      (entry) => entry.message === 'gateway.message.received' && entry.extra.messageType === 'invoke',
    );
    assert.notStrictEqual(receivedLog, undefined);
    assert.strictEqual(receivedLog.extra.messageType, 'invoke');
    assert.strictEqual(receivedLog.extra.gatewayMessageId, 'gw-1');
    assert.ok(receivedLog.extra.frameBytes > 0);

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
    assert.notStrictEqual(closeLog, undefined);
    assert.strictEqual(closeLog.extra.lastMessageDirection, 'sent');
    assert.strictEqual(closeLog.extra.lastMessageType, 'tool_event');
    assert.strictEqual(closeLog.extra.lastMessageId, 'bridge-last-1');
    assert.ok(closeLog.extra.lastPayloadBytes > 0);
    assert.strictEqual(closeLog.extra.lastEventType, 'message.updated');
    assert.strictEqual(closeLog.extra.lastOpencodeMessageId, 'op-msg-last-1');
  });
});
