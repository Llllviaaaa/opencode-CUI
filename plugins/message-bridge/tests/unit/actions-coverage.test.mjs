import { describe, test, expect } from 'bun:test';

import { ChatAction } from '../../dist/action/ChatAction.js';
import { CreateSessionAction } from '../../dist/action/CreateSessionAction.js';
import { CloseSessionAction } from '../../dist/action/CloseSessionAction.js';
import { PermissionReplyAction } from '../../dist/action/PermissionReplyAction.js';
import { StatusQueryAction } from '../../dist/action/StatusQueryAction.js';

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
  test('validate payload variants', () => {
    const action = new ChatAction();
    expect(action.validate(null).valid).toBe(false);
    expect(action.validate({ message: 'x' }).valid).toBe(false);
    expect(action.validate({ sessionId: 's1', message: 1 }).valid).toBe(false);
    expect(action.validate({ sessionId: 's1', message: 'hello' }).valid).toBe(true);
  });

  test('execute success path', async () => {
    const action = new ChatAction();
    const client = {
      session: {
        create: async () => ({}),
        abort: async () => ({}),
        prompt: async () => ({ data: { ok: true } }),
      },
      postSessionIdPermissionsPermissionId: async () => ({}),
    };
    const result = await action.execute({ sessionId: 's-1', message: 'hi' }, readyContext(client));
    expect(result.success).toBe(true);
    expect(result.data).toEqual({ data: { ok: true } });
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
    const result = await action.execute({ sessionId: 's-1', message: 'hi' }, readyContext(client));
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
    const rejectResult = await action.execute({ sessionId: 's-1', message: 'hi' }, readyContext(rejectClient));
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
    const throwResult = await action.execute({ sessionId: 's-1', message: 'hi' }, readyContext(throwClient));
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
  test('validate payload variants', () => {
    const action = new CreateSessionAction();
    expect(action.validate(null).valid).toBe(false);
    expect(action.validate({ sessionId: '' }).valid).toBe(false);
    expect(action.validate({ metadata: 1 }).valid).toBe(false);
    expect(action.validate({ sessionId: 's1', metadata: { source: 'x' } }).valid).toBe(true);
  });

  test('execute success with returned sessionId and fallback', async () => {
    const action = new CreateSessionAction();
    const client = {
      session: {
        create: async () => ({ data: { sessionId: 'new-1' } }),
        abort: async () => ({}),
        prompt: async () => ({}),
      },
      postSessionIdPermissionsPermissionId: async () => ({}),
    };
    const result = await action.execute({ sessionId: 'requested' }, readyContext(client));
    expect(result.success).toBe(true);
    expect(result.data.sessionId).toBe('new-1');

    const fallbackClient = {
      session: {
        create: async () => ({ data: {} }),
        abort: async () => ({}),
        prompt: async () => ({}),
      },
      postSessionIdPermissionsPermissionId: async () => ({}),
    };
    const fallback = await action.execute({ sessionId: 'requested' }, readyContext(fallbackClient));
    expect(fallback.success).toBe(true);
    expect(fallback.data.sessionId).toBe('requested');
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
    const failed = await action.execute({ metadata: {} }, readyContext(client));
    expect(failed.success).toBe(false);
    expect(failed.errorMessage).toContain('blocked');

    const throwClient = {
      session: {
        create: () => {
          throw new Error('network timeout');
        },
        abort: async () => ({}),
        prompt: async () => ({}),
      },
      postSessionIdPermissionsPermissionId: async () => ({}),
    };
    const thrown = await action.execute({ metadata: {} }, readyContext(throwClient));
    expect(thrown.success).toBe(false);
    expect(thrown.errorCode).toBe('SDK_TIMEOUT');
  });
});

describe('CloseSessionAction coverage', () => {
  test('validate and execute paths', async () => {
    const action = new CloseSessionAction();
    expect(action.validate(undefined).valid).toBe(false);
    expect(action.validate({ sessionId: '' }).valid).toBe(false);
    expect(action.validate({ sessionId: 's1' }).valid).toBe(true);

    const okClient = {
      session: {
        create: async () => ({}),
        abort: async () => ({ data: { aborted: true } }),
        prompt: async () => ({}),
      },
      postSessionIdPermissionsPermissionId: async () => ({}),
    };
    const ok = await action.execute({ sessionId: 's1' }, readyContext(okClient));
    expect(ok.success).toBe(true);
    expect(ok.data.closed).toBe(true);

    const errorClient = {
      session: {
        create: async () => ({}),
        abort: async () => ({ error: 'cannot abort' }),
        prompt: async () => ({}),
      },
      postSessionIdPermissionsPermissionId: async () => ({}),
    };
    const failed = await action.execute({ sessionId: 's1' }, readyContext(errorClient));
    expect(failed.success).toBe(false);
    expect(failed.errorCode).toBe('SDK_UNREACHABLE');

    const throwClient = {
      session: {
        create: async () => ({}),
        abort: () => {
          throw new Error('session not found');
        },
        prompt: async () => ({}),
      },
      postSessionIdPermissionsPermissionId: async () => ({}),
    };
    const thrown = await action.execute({ sessionId: 's1' }, readyContext(throwClient));
    expect(thrown.success).toBe(false);
    expect(thrown.errorCode).toBe('INVALID_PAYLOAD');
  });
});

describe('PermissionReplyAction coverage', () => {
  test('validate both formats and bad payloads', () => {
    const action = new PermissionReplyAction();
    expect(action.validate(null).valid).toBe(false);
    expect(action.validate({ permissionId: '' }).valid).toBe(false);
    expect(action.validate({ permissionId: 'p1', response: 'invalid' }).valid).toBe(false);
    expect(action.validate({ permissionId: 'p1', approved: 'yes' }).valid).toBe(false);
    expect(action.validate({ permissionId: 'p1', response: 'allow' }).valid).toBe(true);
    expect(action.validate({ permissionId: 'p1', approved: true }).valid).toBe(true);
  });

  test('execute maps response/approved to sdk decision', async () => {
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
      { permissionId: 'p1', toolSessionId: 's-tool', response: 'allow' },
      readyContext(client),
    );
    const always = await action.execute(
      { permissionId: 'p2', response: 'always' },
      readyContext(client),
    );
    const deny = await action.execute(
      { permissionId: 'p3', approved: false },
      readyContext(client, { sessionId: 'fallback-s' }),
    );

    expect(allow.success).toBe(true);
    expect(always.success).toBe(true);
    expect(deny.success).toBe(true);
    expect(calls[0].request.decision).toBe('once');
    expect(calls[1].request.decision).toBe('always');
    expect(calls[2].request.decision).toBe('reject');
    expect(calls[2].sessionId).toBe('fallback-s');
  });

  test('execute error path and mapper', async () => {
    const action = new PermissionReplyAction();
    const badClient = {
      session: {
        create: async () => ({}),
        abort: async () => ({}),
        prompt: async () => ({}),
      },
      postSessionIdPermissionsPermissionId: async () => ({ error: { message: 'permission denied' } }),
    };
    const failed = await action.execute({ permissionId: 'p4', response: 'deny' }, readyContext(badClient));
    expect(failed.success).toBe(false);
    expect(failed.errorCode).toBe('SDK_UNREACHABLE');

    const throwClient = {
      session: {
        create: async () => ({}),
        abort: async () => ({}),
        prompt: async () => ({}),
      },
      postSessionIdPermissionsPermissionId: () => {
        throw new Error('invalid permission');
      },
    };
    const thrown = await action.execute({ permissionId: 'p5', response: 'deny' }, readyContext(throwClient));
    expect(thrown.success).toBe(false);
    expect(thrown.errorCode).toBe('INVALID_PAYLOAD');
  });
});

describe('StatusQueryAction coverage', () => {
  test('validate and execute in ready/non-ready states', async () => {
    const action = new StatusQueryAction();
    expect(action.validate(undefined).valid).toBe(true);
    expect(action.validate({ sessionId: '' }).valid).toBe(false);
    expect(action.validate({ sessionId: 's-1' }).valid).toBe(true);

    const ready = await action.execute({ sessionId: 's-1' }, readyContext({}));
    expect(ready.success).toBe(true);
    expect(ready.data.opencodeOnline).toBe(true);

    const down = await action.execute(
      { sessionId: 's-2' },
      readyContext({}, { connectionState: 'DISCONNECTED' }),
    );
    expect(down.success).toBe(true);
    expect(down.data.opencodeOnline).toBe(false);
  });

  test('errorMapper variants', () => {
    const action = new StatusQueryAction();
    expect(action.errorMapper(new Error('timeout'))).toBe('SDK_TIMEOUT');
    expect(action.errorMapper(new Error('connection refused'))).toBe('SDK_UNREACHABLE');
  });
});
