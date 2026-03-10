import { describe, test, expect } from 'bun:test';

import { BridgeRuntime } from '../../dist/runtime/BridgeRuntime.js';
import { EnvelopeBuilder } from '../../dist/event/EnvelopeBuilder.js';
import { EventFilter } from '../../dist/event/EventFilter.js';

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
    runtime.envelopeBuilder = new EnvelopeBuilder('agent-1');
    runtime.eventFilter = new EventFilter(['session.idle']);
    runtime.stateManager.setState('READY');

    await runtime.handleEvent({ type: 'session.created' });
    await new Promise((r) => setTimeout(r, 10));

    expect(sent).toHaveLength(0);
    const warnEntry = logs.find((item) => item?.body?.message === 'event.extraction_failed');
    expect(!!warnEntry).toBe(true);
  });

  test('allowed event sends tool_event', async () => {
    const runtime = new BridgeRuntime({ client: {} });
    const sent = [];

    runtime.gatewayConnection = { send: (msg) => sent.push(msg) };
    runtime.envelopeBuilder = new EnvelopeBuilder('agent-2');
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

    expect(sent).toHaveLength(1);
    expect(sent[0].type).toBe('tool_event');
    expect(sent[0].toolSessionId).toBe('tool-1');
    expect(sent[0].event.type).toBe('message.updated');
    expect('sessionId' in sent[0]).toBe(false);
    expect('envelope' in sent[0]).toBe(false);
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
    runtime.envelopeBuilder = new EnvelopeBuilder('agent-3');
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

    expect(sent).toHaveLength(0);
    const warnEntry = logs.find((item) => item?.body?.message === 'event.extraction_failed');
    expect(!!warnEntry).toBe(true);
  });
});
