import { describe, test, beforeEach, afterEach, mock } from 'node:test';
import assert from 'node:assert/strict';
import { AppLogger } from '../../src/runtime/AppLogger.ts';

describe('AppLogger coverage', () => {
  const oldDebug = process.env.BRIDGE_DEBUG;

  beforeEach(() => {
    delete process.env.BRIDGE_DEBUG;
  });

  afterEach(() => {
    if (oldDebug === undefined) {
      delete process.env.BRIDGE_DEBUG;
    } else {
      process.env.BRIDGE_DEBUG = oldDebug;
    }
  });

  test('writes logs via client.app.log with scalar fields preserved by default', async () => {
    const calls = [];
    const logger = new AppLogger({
      app: {
        log: async (options) => {
          calls.push(options);
          return true;
        },
      },
    });

    logger.info('runtime.start.completed', { sessionId: 's-1', token: 'secret' });
    await new Promise((r) => setTimeout(r, 10));

    assert.strictEqual(calls.length, 1);
    assert.strictEqual(calls[0].body.service, 'message-bridge');
    assert.strictEqual(calls[0].body.level, 'info');
    assert.strictEqual(calls[0].body.message, 'runtime.start.completed');
    assert.strictEqual(calls[0].body.extra.sessionId, 's-1');
    assert.strictEqual(calls[0].body.extra.token, '***');
    assert.strictEqual(typeof calls[0].body.extra.traceId, 'string');
    assert.strictEqual(typeof calls[0].body.extra.runtimeTraceId, 'string');
    assert.strictEqual(calls[0].body.extra.traceId, calls[0].body.extra.runtimeTraceId);
  });

  test('preserves nested objects and arrays with redaction when debug mode is disabled', async () => {
    const calls = [];
    const logger = new AppLogger({
      app: {
        log: async (options) => {
          calls.push(options);
          return true;
        },
      },
    });

    logger.info('runtime.start.completed', {
      nested: { foo: 'bar', token: 'secret' },
      items: ['a', 'b'],
    });
    await new Promise((r) => setTimeout(r, 10));

    assert.strictEqual(calls.length, 1);
    assert.deepStrictEqual(calls[0].body.extra.nested, {
      foo: 'bar',
      token: '***',
    });
    assert.deepStrictEqual(calls[0].body.extra.items, ['a', 'b']);
  });

  test('debug mode keeps the same full redacted payload shape', async () => {
    process.env.BRIDGE_DEBUG = 'true';
    const calls = [];
    const logger = new AppLogger({
      app: {
        log: async (options) => {
          calls.push(options);
          return true;
        },
      },
    });

    logger.warn('gateway.connect.started', {
      token: 'abc',
      nested: { authorization: 'x' },
      items: ['x', 'y'],
    });
    await new Promise((r) => setTimeout(r, 10));

    assert.strictEqual(calls.length, 1);
    assert.strictEqual(calls[0].body.extra.token, '***');
    assert.deepStrictEqual(calls[0].body.extra.nested, { authorization: '***' });
    assert.deepStrictEqual(calls[0].body.extra.items, ['x', 'y']);
  });

  test('swallows app.log errors and falls back to console.debug in debug mode', async () => {
    process.env.BRIDGE_DEBUG = 'true';
    const debugMock = mock.method(console, 'debug', () => {});
    const logger = new AppLogger({
      app: {
        log: async () => {
          throw new Error('log failed');
        },
      },
    });

    assert.doesNotThrow(() => logger.error('runtime.tool_error.sending', { code: 'SDK_UNREACHABLE' }));
    await new Promise((r) => setTimeout(r, 10));

    assert.ok(debugMock.mock.calls.length > 0);
    debugMock.mock.restore();
  });

  test('swallows sync app.log throws and does not block caller', async () => {
    process.env.BRIDGE_DEBUG = 'true';
    const debugMock = mock.method(console, 'debug', () => {});
    const logger = new AppLogger({
      app: {
        log: () => {
          throw new Error('sync throw');
        },
      },
    });

    assert.doesNotThrow(() => logger.info('runtime.start.requested', { foo: 'bar' }));
    await new Promise((r) => setTimeout(r, 10));
    assert.ok(debugMock.mock.calls.length > 0);
    debugMock.mock.restore();
  });

  test('child logger can override traceId while preserving runtimeTraceId', async () => {
    const calls = [];
    const logger = new AppLogger({
      app: {
        log: async (options) => {
          calls.push(options);
          return true;
        },
      },
    });

    logger.child({ traceId: 'msg-1', bridgeMessageId: 'bridge-1' }).info('event.forwarding');
    await new Promise((r) => setTimeout(r, 10));

    assert.strictEqual(calls.length, 1);
    assert.strictEqual(calls[0].body.extra.traceId, 'msg-1');
    assert.strictEqual(calls[0].body.extra.bridgeMessageId, 'bridge-1');
    assert.strictEqual(calls[0].body.extra.runtimeTraceId, logger.getTraceId());
    assert.notStrictEqual(calls[0].body.extra.runtimeTraceId, 'msg-1');
  });
});
