import { describe, test } from 'node:test';
import assert from 'node:assert/strict';

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

    assert.deepStrictEqual(sent, [
      {
        type: 'status_response',
        opencodeOnline: true,
      },
    ]);
    assert.strictEqual('welinkSessionId' in sent[0], false);
    assert.strictEqual('sessionId' in sent[0], false);
  });
});
