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
  test('invoke/chat success -> no immediate uplink ack', async () => {
    const { runtime, sent } = createRuntimeHarness({
      routeResult: { success: true, data: { text: 'ok' } },
    });

    await runtime.handleDownstreamMessage({
      type: 'invoke',
      sessionId: 's-1',
      action: 'chat',
      payload: { toolSessionId: 'tool-1', text: 'hi' },
    });

    expect(sent).toHaveLength(0);
  });

  test('invoke/create_session -> session_created', async () => {
    const { runtime, sent } = createRuntimeHarness({
      routeResult: { success: true, data: { sessionId: 'created-1' } },
    });

    await runtime.handleDownstreamMessage({
      type: 'invoke',
      sessionId: 'skill-1',
      action: 'create_session',
      payload: {},
    });

    expect(sent).toHaveLength(1);
    expect(sent[0].type).toBe('session_created');
    expect(sent[0].sessionId).toBe('skill-1');
    expect(sent[0].toolSessionId).toBe('created-1');
  });

  test('invalid payload failure -> tool_error without code field', async () => {
    const { runtime, sent } = createRuntimeHarness({
      routeResult: { success: false, errorCode: 'INVALID_PAYLOAD', errorMessage: 'bad payload' },
    });

    await runtime.handleDownstreamMessage({
      type: 'invoke',
      sessionId: 's-err',
      action: 'chat',
      payload: { bad: true },
    });

    expect(sent).toHaveLength(1);
    expect(sent[0].type).toBe('tool_error');
    expect(sent[0].error).toBe('Invalid invoke payload shape');
    expect('code' in sent[0]).toBe(false);
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

  test('invoke/status_query variant -> tool_error', async () => {
    const { runtime, sent } = createRuntimeHarness();

    await runtime.handleDownstreamMessage({
      type: 'invoke',
      sessionId: 's-3',
      action: 'status_query',
      payload: { sessionId: 's-3' },
    });

    expect(sent).toHaveLength(1);
    expect(sent[0].type).toBe('tool_error');
    expect(sent[0].sessionId).toBe('s-3');
  });
});
