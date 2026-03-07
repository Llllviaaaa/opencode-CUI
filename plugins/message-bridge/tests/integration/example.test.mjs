import { describe, test, expect } from 'bun:test';

import { BridgeRuntime } from '../../dist/runtime/BridgeRuntime.js';
import { EnvelopeBuilder } from '../../dist/event/EnvelopeBuilder.js';

function createRuntimeHarness({ state = 'READY', routeResult } = {}) {
  const runtime = new BridgeRuntime({ client: {} });
  const sent = [];

  runtime.gatewayConnection = {
    send: (message) => sent.push(message),
  };
  runtime.envelopeBuilder = new EnvelopeBuilder('agent-test');
  runtime.stateManager.setState(state);
  runtime.actionRouter = {
    route: async () => routeResult ?? { success: true, data: { ok: true } },
  };

  return { runtime, sent };
}

describe('downlink -> uplink protocol', () => {
  test('invoke/chat -> tool_done', async () => {
    const { runtime, sent } = createRuntimeHarness({
      routeResult: { success: true, data: { text: 'ok' } },
    });

    await runtime.handleDownstreamMessage({
      type: 'invoke',
      action: 'chat',
      payload: { sessionId: 's-1', message: 'hi' },
      envelope: { sessionId: 's-1' },
    });

    expect(sent).toHaveLength(1);
    expect(sent[0].type).toBe('tool_done');
    expect(sent[0].sessionId).toBe('s-1');
    expect(sent[0].result).toEqual({ text: 'ok' });
  });

  test('invoke/create_session -> session_created', async () => {
    const { runtime, sent } = createRuntimeHarness({
      routeResult: { success: true, data: { sessionId: 'created-1' } },
    });

    await runtime.handleDownstreamMessage({
      type: 'invoke',
      action: 'create_session',
      payload: {},
      envelope: {},
    });

    expect(sent).toHaveLength(1);
    expect(sent[0].type).toBe('session_created');
    expect(sent[0].sessionId).toBe('created-1');
  });

  test('invalid payload failure -> tool_error(INVALID_PAYLOAD)', async () => {
    const { runtime, sent } = createRuntimeHarness({
      routeResult: { success: false, errorCode: 'INVALID_PAYLOAD', errorMessage: 'bad payload' },
    });

    await runtime.handleDownstreamMessage({
      type: 'invoke',
      action: 'chat',
      payload: { bad: true },
      envelope: { sessionId: 's-err' },
    });

    expect(sent).toHaveLength(1);
    expect(sent[0].type).toBe('tool_error');
    expect(sent[0].code).toBe('INVALID_PAYLOAD');
  });

  test('status_query -> status_response', async () => {
    const { runtime, sent } = createRuntimeHarness({
      routeResult: { success: true, data: { opencodeOnline: true } },
    });

    await runtime.handleDownstreamMessage({
      type: 'status_query',
      sessionId: 's-2',
    });

    expect(sent).toHaveLength(1);
    expect(sent[0].type).toBe('status_response');
    expect(sent[0].opencodeOnline).toBe(true);
  });
});
