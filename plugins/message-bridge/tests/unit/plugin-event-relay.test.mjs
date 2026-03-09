import { describe, test, expect } from 'bun:test';

import { BridgeRuntime } from '../../dist/runtime/BridgeRuntime.js';
import { EventFilter } from '../../dist/event/EventFilter.js';

describe('event uplink via hook boundary', () => {
  test('allowlist reject records event.rejected_allowlist', async () => {
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
    runtime.eventFilter = new EventFilter(['session.idle']);
    runtime.stateManager.setState('READY');

    await runtime.handleEvent({ type: 'session.created' });
    await new Promise((r) => setTimeout(r, 10));

    expect(sent).toHaveLength(0);
    const warnEntry = logs.find((item) => item?.body?.message === 'event.rejected_allowlist');
    expect(!!warnEntry).toBe(true);
  });

  test('allowed event sends tool_event', async () => {
    const runtime = new BridgeRuntime({ client: {} });
    const sent = [];

    runtime.gatewayConnection = { send: (msg) => sent.push(msg) };
    runtime.eventFilter = new EventFilter(['message.*']);
    runtime.stateManager.setState('READY');

    await runtime.handleEvent({
      type: 'message.delta',
      properties: { sessionId: 's-1' },
      text: 'hello',
    });

    expect(sent).toHaveLength(1);
    expect(sent[0].type).toBe('tool_event');
    expect(sent[0].sessionId).toBeUndefined();
  });
});
