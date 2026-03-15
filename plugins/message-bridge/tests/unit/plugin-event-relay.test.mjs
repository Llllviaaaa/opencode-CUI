import { describe, test } from 'node:test';
import assert from 'node:assert/strict';

import { BridgeRuntime } from '../../src/runtime/BridgeRuntime.ts';
import { EventFilter } from '../../src/event/EventFilter.ts';

describe('event uplink via hook boundary', () => {
  test('unsupported upstream events fail closed before forwarding', async () => {
    const logs = [];
    const runtime = new BridgeRuntime({
      debug: true,
      client: {
        app: {
          log: async (options) => {
            logs.push(options);
            return true;
          },
        },
      },
    });
    const sent = [];

    runtime.gatewayConnection = { send: (msg) => sent.push(msg) };
    runtime.eventFilter = new EventFilter(['session.idle']);
    runtime.stateManager.setState('READY');

    await runtime.handleEvent({ type: 'session.created' });
    await new Promise((r) => setTimeout(r, 10));

    assert.strictEqual(sent.length, 0);
    const warnEntry = logs.find((item) => item?.body?.message === 'event.extraction_failed');
    assert.ok(!!warnEntry);
  });

  test('allowed event sends tool_event', async () => {
    const runtime = new BridgeRuntime({ client: {} });
    const sent = [];

    runtime.gatewayConnection = { send: (msg) => sent.push(msg) };
    runtime.eventFilter = new EventFilter(['message.updated']);
    runtime.stateManager.setState('READY');

    await runtime.handleEvent({
      type: 'message.updated',
      properties: {
        info: {
          id: 'msg-1',
          sessionID: 'tool-1',
          role: 'user',
        },
      },
    });

    assert.strictEqual(sent.length, 1);
    assert.strictEqual(sent[0].type, 'tool_event');
    assert.strictEqual(sent[0].toolSessionId, 'tool-1');
    assert.strictEqual(sent[0].event.type, 'message.updated');
    assert.strictEqual('sessionId' in sent[0], false);
    assert.strictEqual('envelope' in sent[0], false);
  });

  test('missing required upstream field fails closed', async () => {
    const logs = [];
    const runtime = new BridgeRuntime({
      client: {
        app: {
          log: async (options) => {
            logs.push(options);
            return true;
          },
        },
      },
    });
    const sent = [];

    runtime.gatewayConnection = { send: (msg) => sent.push(msg) };
    runtime.eventFilter = new EventFilter(['message.updated']);
    runtime.stateManager.setState('READY');

    await runtime.handleEvent({
      type: 'message.updated',
      properties: {
        info: {
          id: 'msg-missing-session',
          role: 'user',
        },
      },
    });

    assert.strictEqual(sent.length, 0);
    const warnEntry = logs.find((item) => item?.body?.message === 'event.extraction_failed');
    assert.ok(!!warnEntry);
  });
});
