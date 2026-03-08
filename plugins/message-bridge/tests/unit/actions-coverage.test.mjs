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
    expect(action.validate({ sessionId: 's1', message: 'hello' }).valid).toBe(false);
    expect(action.validate({ toolSessionId: 's1', text: '' }).valid).toBe(false);
    expect(action.validate({ toolSessionId: 's1', text: 'hello' }).valid).toBe(true);
  });

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

  test('execute formats unknown object errors without collapsing to [object Object]', async () => {
    const action = new ChatAction();
    const client = {
      session: {
        create: async () => ({}),
        abort: async () => ({}),
        prompt: async () => {
          throw { reason: 'transport down', code: 'E_DOWN' };
        },
      },
      postSessionIdPermissionsPermissionId: async () => ({}),
    };

    const result = await action.execute({ toolSessionId: 's-1', text: 'hi' }, readyContext(client));
    expect(result.success).toBe(false);
    expect(result.errorMessage).toContain('transport down');
    expect(result.errorMessage).not.toContain('[object Object]');
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
    expect(action.validate('x').valid).toBe(false);
    expect(action.validate({}).valid).toBe(true);
  });

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
  test('validate and execute paths', async () => {
    const action = new CloseSessionAction();
    expect(action.validate(undefined).valid).toBe(false);
    expect(action.validate({ sessionId: 's1' }).valid).toBe(false);
    expect(action.validate({ toolSessionId: 's1' }).valid).toBe(true);

    const calls = [];
    const okClient = {
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
    const ok = await action.execute({ toolSessionId: 's1' }, readyContext(okClient));
    expect(ok.success).toBe(true);
    expect(ok.data.closed).toBe(true);
    expect(calls[0]).toEqual({ path: { id: 's1' } });
  });
});

describe('PermissionReplyAction coverage', () => {
  test('validate strict format only', () => {
    const action = new PermissionReplyAction();
    expect(action.validate(null).valid).toBe(false);
    expect(action.validate({ permissionId: '' }).valid).toBe(false);
    expect(action.validate({ permissionId: 'p1', response: 'allow' }).valid).toBe(false);
    expect(action.validate({ permissionId: 'p1', toolSessionId: 's-tool', response: 'allow' }).valid).toBe(false);
    expect(action.validate({ permissionId: 'p1', toolSessionId: 's-tool', response: 'once' }).valid).toBe(true);
  });

  test('execute sends response directly to sdk path/body', async () => {
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

    for (const response of ['once', 'always', 'reject']) {
      calls.length = 0;
      const result = await action.execute(
        { permissionId: 'p1', toolSessionId: 's-tool', response },
        readyContext(client),
      );

      expect(result.success).toBe(true);
      expect(calls[0]).toEqual({
        path: { id: 's-tool', permissionID: 'p1' },
        body: { response },
      });
    }
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
});
