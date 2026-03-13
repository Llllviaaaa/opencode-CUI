import { describe, test, expect } from 'bun:test';

import { BridgeRuntime } from '../../src/runtime/BridgeRuntime.ts';

function createRuntimeClient() {
  return {
    global: {},
    session: {
      create: async () => ({}),
      abort: async () => ({}),
      delete: async () => ({}),
      prompt: async () => ({}),
    },
    postSessionIdPermissionsPermissionId: async () => ({}),
    _client: {
      get: async (options) => {
        if (options?.url === '/global/health') {
          return { data: { healthy: true, version: '9.9.9' } };
        }
        return { data: [] };
      },
      post: async () => ({ data: undefined }),
    },
  };
}

describe('protocol status-query', () => {
  test('standalone status_query returns envelope-free status_response', async () => {
    const runtime = new BridgeRuntime({
      client: createRuntimeClient(),
    });
    const sent = [];

    runtime.gatewayConnection = {
      send: (message) => sent.push(message),
    };
    runtime.stateManager.setState('READY');

    await runtime.handleDownstreamMessage({ type: 'status_query' });

    expect(sent).toEqual([
      {
        type: 'status_response',
        opencodeOnline: true,
      },
    ]);
    expect('welinkSessionId' in sent[0]).toBe(false);
    expect('sessionId' in sent[0]).toBe(false);
  });
});
