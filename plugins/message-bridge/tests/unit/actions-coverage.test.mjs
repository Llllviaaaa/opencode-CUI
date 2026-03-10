import { describe, test, expect } from 'bun:test';

import { ChatAction } from '../../src/action/ChatAction.ts';
import { CreateSessionAction } from '../../src/action/CreateSessionAction.ts';
import { CloseSessionAction } from '../../src/action/CloseSessionAction.ts';
import { PermissionReplyAction } from '../../src/action/PermissionReplyAction.ts';
import { StatusQueryAction } from '../../src/action/StatusQueryAction.ts';
import { AbortSessionAction } from '../../src/action/AbortSessionAction.ts';
import { QuestionReplyAction } from '../../src/action/QuestionReplyAction.ts';

function readyContext(client, overrides = {}) {
  return {
    client,
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
    expect(result.success).toBe(true);
    expect(calls[0]).toEqual({
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
    expect(result.success).toBe(false);
    expect(result.errorCode).toBe('SDK_UNREACHABLE');
    expect(result.errorMessage).toContain('boom');
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
    expect(rejectResult.success).toBe(false);
    expect(rejectResult.errorCode).toBe('SDK_UNREACHABLE');
    expect(rejectResult.errorMessage).toContain('Failed to send message');

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
    expect(throwResult.success).toBe(false);
    expect(throwResult.errorCode).toBe('SDK_TIMEOUT');
  });

  test('errorMapper variants', () => {
    const action = new ChatAction();
    expect(action.errorMapper(new Error('connection refused'))).toBe('SDK_UNREACHABLE');
    expect(action.errorMapper(new Error('session not found'))).toBe('INVALID_PAYLOAD');
    expect(action.errorMapper('timeout')).toBe('SDK_TIMEOUT');
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
    expect(result.success).toBe(true);
    expect(result.data.sessionId).toBe('new-1');
    expect(calls[0]).toEqual({ body: { metadata: { source: 'x' } } });
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
    expect(failed.success).toBe(false);
    expect(failed.errorMessage).toContain('blocked');
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
    expect(ok.success).toBe(true);
    expect(ok.data.closed).toBe(true);
    expect(calls[0]).toEqual({ path: { id: 's1' } });
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

    expect(allow.success).toBe(true);
    expect(calls[0]).toEqual({
      path: { id: 's-tool', permissionID: 'p1' },
      body: { response: 'once' },
    });
  });
});

describe('StatusQueryAction coverage', () => {
  test('execute only returns true when app.health succeeds', async () => {
    const action = new StatusQueryAction();
    const ready = await action.execute({}, readyContext({
      app: {
        health: async () => ({ ok: true }),
      },
    }));
    expect(ready.success).toBe(true);
    expect(ready.data.opencodeOnline).toBe(true);

    const down = await action.execute(
      {},
      readyContext({}, { connectionState: 'READY' }),
    );
    expect(down.success).toBe(true);
    expect(down.data.opencodeOnline).toBe(false);
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
    expect(result.success).toBe(true);
    expect(result.data).toEqual({ sessionId: 'abort-1', aborted: true });
    expect(calls[0]).toEqual({ path: { id: 'abort-1' } });
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

    expect(result.success).toBe(true);
    expect(result.data).toEqual({ requestId: 'req-1', replied: true });
    expect(getCalls[0]).toEqual({ url: '/question' });
    expect(postCalls[0]).toEqual({
      url: '/question/{requestID}/reply',
      path: { requestID: 'req-1' },
      body: { answers: [['ship it']] },
      headers: { 'Content-Type': 'application/json' },
    });
  });
});
