import { describe, test } from 'node:test';
import assert from 'node:assert/strict';

import { ChatAction } from '../../src/action/ChatAction.ts';
import { CreateSessionAction } from '../../src/action/CreateSessionAction.ts';
import { CloseSessionAction } from '../../src/action/CloseSessionAction.ts';
import { PermissionReplyAction } from '../../src/action/PermissionReplyAction.ts';
import { StatusQueryAction } from '../../src/action/StatusQueryAction.ts';
import { AbortSessionAction } from '../../src/action/AbortSessionAction.ts';
import { QuestionReplyAction } from '../../src/action/QuestionReplyAction.ts';
import { toHostClientLike } from '../../src/runtime/SdkAdapter.ts';

function readyContext(client, overrides = {}) {
  return {
    client,
    hostClient: overrides.hostClient ?? client,
    connectionState: 'READY',
    agentId: 'agent-1',
    sessionId: 'ctx-session',
    ...overrides,
  };
}

describe('ChatAction coverage', () => {
  test('execute success path with strict prompt shape', async () => {
    const action = new ChatAction();
    const calls = [];
    const client = {
      session: {
        create: async () => ({}),
        abort: async () => ({}),
        prompt: async (options) => {
          calls.push(options);
          return { data: { ok: true } };
        },
      },
      postSessionIdPermissionsPermissionId: async () => ({}),
    };
    const result = await action.execute({ toolSessionId: 's-1', text: 'hi' }, readyContext(client));
    assert.strictEqual(result.success, true);
    assert.deepStrictEqual(calls[0], {
      path: { id: 's-1' },
      body: { parts: [{ type: 'text', text: 'hi' }] },
    });
  });

  test('execute handles sdk error object', async () => {
    const action = new ChatAction();
    const client = {
      session: {
        create: async () => ({}),
        abort: async () => ({}),
        prompt: async () => ({ error: { message: 'boom' } }),
      },
      postSessionIdPermissionsPermissionId: async () => ({}),
    };
    const result = await action.execute({ toolSessionId: 's-1', text: 'hi' }, readyContext(client));
    assert.strictEqual(result.success, false);
    assert.strictEqual(result.errorCode, 'SDK_UNREACHABLE');
    assert.ok(result.errorMessage.includes('boom'));
  });

  test('execute handles promise rejection and sync throw', async () => {
    const action = new ChatAction();
    const rejectClient = {
      session: {
        create: async () => ({}),
        abort: async () => ({}),
        prompt: async () => {
          throw new Error('transport down');
        },
      },
      postSessionIdPermissionsPermissionId: async () => ({}),
    };
    const rejectResult = await action.execute({ toolSessionId: 's-1', text: 'hi' }, readyContext(rejectClient));
    assert.strictEqual(rejectResult.success, false);
    assert.strictEqual(rejectResult.errorCode, 'SDK_UNREACHABLE');
    assert.ok(rejectResult.errorMessage.includes('Failed to send message'));

    const throwClient = {
      session: {
        create: async () => ({}),
        abort: async () => ({}),
        prompt: () => {
          throw new Error('timeout now');
        },
      },
      postSessionIdPermissionsPermissionId: async () => ({}),
    };
    const throwResult = await action.execute({ toolSessionId: 's-1', text: 'hi' }, readyContext(throwClient));
    assert.strictEqual(throwResult.success, false);
    assert.strictEqual(throwResult.errorCode, 'SDK_TIMEOUT');
  });

  test('errorMapper variants', () => {
    const action = new ChatAction();
    assert.strictEqual(action.errorMapper(new Error('connection refused')), 'SDK_UNREACHABLE');
    assert.strictEqual(action.errorMapper(new Error('session not found')), 'INVALID_PAYLOAD');
    assert.strictEqual(action.errorMapper('timeout'), 'SDK_TIMEOUT');
  });
});

describe('CreateSessionAction coverage', () => {
  test('execute success with returned sessionId', async () => {
    const action = new CreateSessionAction();
    const calls = [];
    const client = {
      session: {
        create: async (options) => {
          calls.push(options);
          return { data: { sessionId: 'new-1' } };
        },
        abort: async () => ({}),
        prompt: async () => ({}),
      },
      postSessionIdPermissionsPermissionId: async () => ({}),
    };
    const result = await action.execute({ metadata: { source: 'x' } }, readyContext(client));
    assert.strictEqual(result.success, true);
    assert.strictEqual(result.data.sessionId, 'new-1');
    assert.deepStrictEqual(calls[0], { body: { metadata: { source: 'x' } } });
  });

  test('execute handles sdk failures', async () => {
    const action = new CreateSessionAction();
    const client = {
      session: {
        create: async () => ({ error: { message: 'blocked' } }),
        abort: async () => ({}),
        prompt: async () => ({}),
      },
      postSessionIdPermissionsPermissionId: async () => ({}),
    };
    const failed = await action.execute({}, readyContext(client));
    assert.strictEqual(failed.success, false);
    assert.ok(failed.errorMessage.includes('blocked'));
  });
});

describe('CloseSessionAction coverage', () => {
  test('execute success path', async () => {
    const action = new CloseSessionAction();
    const calls = [];
    const okClient = {
      session: {
        create: async () => ({}),
        abort: async () => ({ data: { aborted: true } }),
        delete: async (options) => {
          calls.push(options);
          return { data: { deleted: true } };
        },
        prompt: async () => ({}),
      },
      postSessionIdPermissionsPermissionId: async () => ({}),
    };
    const ok = await action.execute({ toolSessionId: 's1' }, readyContext(okClient));
    assert.strictEqual(ok.success, true);
    assert.strictEqual(ok.data.closed, true);
    assert.deepStrictEqual(calls[0], { path: { id: 's1' } });
  });
});

describe('PermissionReplyAction coverage', () => {
  test('execute maps response to sdk path/body', async () => {
    const calls = [];
    const action = new PermissionReplyAction();
    const client = {
      session: {
        create: async () => ({}),
        abort: async () => ({}),
        prompt: async () => ({}),
      },
      postSessionIdPermissionsPermissionId: async (opts) => {
        calls.push(opts);
        return { data: { ok: true } };
      },
    };

    const allow = await action.execute(
      { permissionId: 'p1', toolSessionId: 's-tool', response: 'once' },
      readyContext(client),
    );

    assert.strictEqual(allow.success, true);
    assert.deepStrictEqual(calls[0], {
      path: { id: 's-tool', permissionID: 'p1' },
      body: { response: 'once' },
    });
  });
});

describe('StatusQueryAction coverage', () => {
  test('execute uses global.health when available and raw health fallback otherwise', async () => {
    const action = new StatusQueryAction();
    const ready = await action.execute({}, readyContext({
      global: {
        health: async () => ({ healthy: true, version: '9.9.9' }),
      },
      _client: {
        get: async () => {
          throw new Error('raw fallback should not be called');
        },
      },
    }));
    assert.strictEqual(ready.success, true);
    assert.strictEqual(ready.data.opencodeOnline, true);

    const readyViaRawFallback = await action.execute({}, readyContext({
      _client: {
        get: async (options) => {
          assert.deepStrictEqual(options, { url: '/global/health' });
          return { data: { healthy: true, version: '9.9.9' } };
        },
      },
    }, {
      hostClient: toHostClientLike({
        _client: {
          get: async (options) => {
            assert.deepStrictEqual(options, { url: '/global/health' });
            return { data: { healthy: true, version: '9.9.9' } };
          },
        },
      }),
    }));
    assert.strictEqual(readyViaRawFallback.success, true);
    assert.strictEqual(readyViaRawFallback.data.opencodeOnline, true);

    const downViaHealthFalse = await action.execute({}, readyContext({
      global: {
        health: async () => ({ healthy: false, version: '9.9.9' }),
      },
    }));
    assert.strictEqual(downViaHealthFalse.success, true);
    assert.strictEqual(downViaHealthFalse.data.opencodeOnline, false);

    const downViaRawError = await action.execute({}, readyContext({
      _client: {
        get: async () => ({ error: { message: 'bad gateway' } }),
      },
    }, {
      hostClient: toHostClientLike({
        _client: {
          get: async () => ({ error: { message: 'bad gateway' } }),
        },
      }),
    }));
    assert.strictEqual(downViaRawError.success, true);
    assert.strictEqual(downViaRawError.data.opencodeOnline, false);

    const down = await action.execute(
      {},
      readyContext({
        _client: {
          get: async () => {
            throw new Error('global unavailable');
          },
        },
      }, {
        connectionState: 'READY',
        hostClient: toHostClientLike({
          _client: {
            get: async () => {
              throw new Error('global unavailable');
            },
          },
        }),
      }),
    );
    assert.strictEqual(down.success, true);
    assert.strictEqual(down.data.opencodeOnline, false);
  });
});

describe('AbortSessionAction coverage', () => {
  test('execute success path', async () => {
    const action = new AbortSessionAction();
    const calls = [];
    const client = {
      session: {
        create: async () => ({}),
        abort: async (options) => {
          calls.push(options);
          return { data: { aborted: true } };
        },
        prompt: async () => ({}),
      },
      postSessionIdPermissionsPermissionId: async () => ({}),
    };

    const result = await action.execute({ toolSessionId: 'abort-1' }, readyContext(client));
    assert.strictEqual(result.success, true);
    assert.deepStrictEqual(result.data, { sessionId: 'abort-1', aborted: true });
    assert.deepStrictEqual(calls[0], { path: { id: 'abort-1' } });
  });
});

describe('QuestionReplyAction coverage', () => {
  test('execute resolves pending request and replies through raw question API', async () => {
    const action = new QuestionReplyAction();
    const getCalls = [];
    const postCalls = [];
    const client = {
      session: {
        create: async () => ({}),
        abort: async () => ({}),
        prompt: async () => ({}),
      },
      _client: {
        get: async (options) => {
          getCalls.push(options);
          return {
            data: [
              {
                id: 'req-1',
                sessionID: 'tool-9',
                tool: { callID: 'call-1' },
              },
            ],
          };
        },
        post: async (options) => {
          postCalls.push(options);
          return { data: { ok: true } };
        },
      },
      postSessionIdPermissionsPermissionId: async () => ({}),
    };

    const result = await action.execute(
      { toolSessionId: 'tool-9', toolCallId: 'call-1', answer: 'ship it' },
      readyContext(client),
    );

    assert.strictEqual(result.success, true);
    assert.deepStrictEqual(result.data, { requestId: 'req-1', replied: true });
    assert.deepStrictEqual(getCalls[0], { url: '/question' });
    assert.deepStrictEqual(postCalls[0], {
      url: '/question/{requestID}/reply',
      path: { requestID: 'req-1' },
      body: { answers: [['ship it']] },
      headers: { 'Content-Type': 'application/json' },
    });
  });
});
