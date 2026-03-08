import { describe, test, expect, spyOn, beforeEach, afterEach } from 'bun:test';
import { AppLogger } from '../../dist/runtime/AppLogger.js';

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

    expect(calls.length).toBe(1);
    expect(calls[0].body.service).toBe('message-bridge');
    expect(calls[0].body.level).toBe('info');
    expect(calls[0].body.message).toBe('runtime.start.completed');
    expect(calls[0].body.extra.sessionId).toBe('s-1');
    expect(calls[0].body.extra.token).toBe('***');
    expect(typeof calls[0].body.extra.traceId).toBe('string');
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

    expect(calls.length).toBe(1);
    expect(calls[0].body.extra.nested).toEqual({
      foo: 'bar',
      token: '***',
    });
    expect(calls[0].body.extra.items).toEqual(['a', 'b']);
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

    expect(calls.length).toBe(1);
    expect(calls[0].body.extra.token).toBe('***');
    expect(calls[0].body.extra.nested).toEqual({ authorization: '***' });
    expect(calls[0].body.extra.items).toEqual(['x', 'y']);
  });

  test('swallows app.log errors and falls back to console.debug in debug mode', async () => {
    process.env.BRIDGE_DEBUG = 'true';
    const debugSpy = spyOn(console, 'debug').mockImplementation(() => {});
    const logger = new AppLogger({
      app: {
        log: async () => {
          throw new Error('log failed');
        },
      },
    });

    expect(() => logger.error('runtime.tool_error.sending', { code: 'SDK_UNREACHABLE' })).not.toThrow();
    await new Promise((r) => setTimeout(r, 10));

    expect(debugSpy).toHaveBeenCalled();
    debugSpy.mockRestore();
  });

  test('swallows sync app.log throws and does not block caller', async () => {
    process.env.BRIDGE_DEBUG = 'true';
    const debugSpy = spyOn(console, 'debug').mockImplementation(() => {});
    const logger = new AppLogger({
      app: {
        log: () => {
          throw new Error('sync throw');
        },
      },
    });

    expect(() => logger.info('runtime.start.requested', { foo: 'bar' })).not.toThrow();
    await new Promise((r) => setTimeout(r, 10));
    expect(debugSpy).toHaveBeenCalled();
    debugSpy.mockRestore();
  });
});
