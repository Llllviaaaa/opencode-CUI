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

function createRuntimeHarness({ state = 'READY', routeResult } = {}) {
  const runtime = new BridgeRuntime({ client: createRuntimeClient() });
  const sent = [];

  runtime.gatewayConnection = {
    send: (message) => sent.push(message),
  };
  runtime.stateManager.setState(state);
  runtime.actionRouter = {
    route: async () => routeResult ?? { success: true, data: { ok: true } },
  };

  return { runtime, sent };
}

describe('downlink -> uplink protocol', () => {
  test('invoke/chat success -> emits tool_done compat message', async () => {
    const { runtime, sent } = createRuntimeHarness({
      routeResult: { success: true, data: { text: 'ok' } },
    });

    await runtime.handleDownstreamMessage({
      type: 'invoke',
      welinkSessionId: 's-1',
      action: 'chat',
      payload: { toolSessionId: 'tool-1', text: 'hi' },
    });

    assert.strictEqual(sent.length, 1);
    assert.strictEqual(sent[0].type, 'tool_done');
    assert.strictEqual(sent[0].toolSessionId, 'tool-1');
    assert.strictEqual(sent[0].welinkSessionId, 's-1');
  });

  test('invoke/create_session -> session_created', async () => {
    const { runtime, sent } = createRuntimeHarness({
      routeResult: { success: true, data: { sessionId: 'created-1' } },
    });

    await runtime.handleDownstreamMessage({
      type: 'invoke',
      welinkSessionId: 'skill-1',
      action: 'create_session',
      payload: {},
    });

    assert.strictEqual(sent.length, 1);
    assert.strictEqual(sent[0].type, 'session_created');
    assert.strictEqual(sent[0].welinkSessionId, 'skill-1');
    assert.strictEqual(sent[0].toolSessionId, 'created-1');
  });

  test('invalid payload failure -> tool_error without code field', async () => {
    const { runtime, sent } = createRuntimeHarness({
      routeResult: { success: false, errorCode: 'INVALID_PAYLOAD', errorMessage: 'bad payload' },
    });

    await runtime.handleDownstreamMessage({
      type: 'invoke',
      welinkSessionId: 's-err',
      action: 'chat',
      payload: { bad: true },
    });

    assert.strictEqual(sent.length, 1);
    assert.strictEqual(sent[0].type, 'tool_error');
    assert.strictEqual(sent[0].error, 'Invalid invoke payload shape');
    assert.strictEqual('code' in sent[0], false);
  });

  test('status_query -> status_response', async () => {
    const { runtime, sent } = createRuntimeHarness({
      routeResult: { success: true, data: { opencodeOnline: true } },
    });

    await runtime.handleDownstreamMessage({
      type: 'status_query',
    });

    assert.strictEqual(sent.length, 1);
    assert.strictEqual(sent[0].type, 'status_response');
    assert.strictEqual(sent[0].opencodeOnline, true);
  });

  test('invoke/status_query variant -> tool_error', async () => {
    const { runtime, sent } = createRuntimeHarness();

    await runtime.handleDownstreamMessage({
      type: 'invoke',
      welinkSessionId: 's-3',
      action: 'status_query',
      payload: { sessionId: 's-3' },
    });

    assert.strictEqual(sent.length, 1);
    assert.strictEqual(sent[0].type, 'tool_error');
    assert.strictEqual(sent[0].welinkSessionId, 's-3');
  });
});
