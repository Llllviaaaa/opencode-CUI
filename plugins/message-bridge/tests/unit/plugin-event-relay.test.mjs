import { describe, test, expect, spyOn, beforeEach, afterEach } from 'bun:test';

import { BridgeRuntime } from '../../dist/runtime/BridgeRuntime.js';
import { EnvelopeBuilder } from '../../dist/event/EnvelopeBuilder.js';
import { EventFilter } from '../../dist/event/EventFilter.js';

describe('event uplink via hook boundary', () => {
  let warnSpy;

  beforeEach(() => {
    warnSpy = spyOn(console, 'warn').mockImplementation(() => {});
  });

  afterEach(() => {
    warnSpy.mockRestore();
  });

  test('allowlist reject records unsupported_event', async () => {
    const runtime = new BridgeRuntime({ client: {} });
    const sent = [];

    runtime.gatewayConnection = { send: (msg) => sent.push(msg) };
    runtime.envelopeBuilder = new EnvelopeBuilder('agent-1');
    runtime.eventFilter = new EventFilter(['session.idle']);
    runtime.stateManager.setState('READY');

    await runtime.handleEvent({ type: 'session.created' });

    expect(sent).toHaveLength(0);
    expect(warnSpy).toHaveBeenCalledTimes(1);
    expect(warnSpy.mock.calls[0][0]).toBe('unsupported_event');
  });

  test('allowed event sends tool_event', async () => {
    const runtime = new BridgeRuntime({ client: {} });
    const sent = [];

    runtime.gatewayConnection = { send: (msg) => sent.push(msg) };
    runtime.envelopeBuilder = new EnvelopeBuilder('agent-2');
    runtime.eventFilter = new EventFilter(['message.*']);
    runtime.stateManager.setState('READY');

    await runtime.handleEvent({
      type: 'message.delta',
      properties: { sessionId: 's-1' },
      text: 'hello',
    });

    expect(sent).toHaveLength(1);
    expect(sent[0].type).toBe('tool_event');
    expect(sent[0].sessionId).toBe('s-1');
  });
});
