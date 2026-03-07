import { describe, test, expect } from 'bun:test';

import { BridgeRuntime } from '../../dist/runtime/BridgeRuntime.js';
import { EnvelopeBuilder } from '../../dist/event/EnvelopeBuilder.js';

describe('runtime protocol strictness', () => {
  test('rejects non-baseline nested invoke payload and returns tool_error without code', async () => {
    const runtime = new BridgeRuntime({
      client: {
        session: {
          create: async () => ({}),
          abort: async () => ({}),
          prompt: async () => ({}),
        },
        postSessionIdPermissionsPermissionId: async () => ({}),
      },
    });

    const sent = [];
    runtime.gatewayConnection = { send: (msg) => sent.push(msg) };
    runtime.envelopeBuilder = new EnvelopeBuilder('agent-1');
    runtime.stateManager.setState('READY');

    await runtime.handleDownstreamMessage({
      type: 'invoke',
      sessionId: '42',
      payload: {
        action: 'chat',
        payload: { toolSessionId: 'tool-1', text: 'hello' },
      },
    });

    expect(sent).toHaveLength(1);
    expect(sent[0].type).toBe('tool_error');
    expect(sent[0].sessionId).toBe('42');
    expect('code' in sent[0]).toBe(false);
  });

  test('accepts baseline invoke shape and does not emit tool_done on success', async () => {
    const prompts = [];
    const runtime = new BridgeRuntime({
      client: {
        session: {
          create: async () => ({}),
          abort: async () => ({}),
          prompt: async (options) => {
            prompts.push(options);
            return { data: { ok: true } };
          },
        },
        postSessionIdPermissionsPermissionId: async () => ({}),
      },
    });

    const sent = [];
    runtime.gatewayConnection = { send: (msg) => sent.push(msg) };
    runtime.envelopeBuilder = new EnvelopeBuilder('agent-1');
    runtime.stateManager.setState('READY');

    await runtime.handleDownstreamMessage({
      type: 'invoke',
      sessionId: '100',
      action: 'chat',
      payload: { toolSessionId: 'tool-100', text: 'hello' },
    });

    expect(prompts).toHaveLength(1);
    expect(prompts[0]).toEqual({
      path: { id: 'tool-100' },
      body: { parts: [{ type: 'text', text: 'hello' }] },
    });
    expect(sent).toHaveLength(0);
  });
});
