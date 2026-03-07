import { describe, test, expect } from 'bun:test';

import { DefaultActionRouter } from '../../dist/action/ActionRouter.js';
import { DefaultActionRegistry } from '../../dist/action/ActionRegistry.js';
import { createSdkAdapter } from '../../dist/runtime/SdkAdapter.js';

describe('DefaultActionRouter coverage', () => {
  const context = {
    client: {},
    connectionState: 'READY',
    agentId: 'agent-1',
    sessionId: 's1',
  };

  test('returns error when registry missing', async () => {
    const router = new DefaultActionRouter();
    const result = await router.route('chat', {}, context);
    expect(result.success).toBe(false);
    expect(result.errorCode).toBe('SDK_UNREACHABLE');
  });

  test('returns unsupported action when action not found', async () => {
    const router = new DefaultActionRouter();
    router.setRegistry(new DefaultActionRegistry());
    const result = await router.route('not_exists', {}, context);
    expect(result.success).toBe(false);
    expect(result.errorCode).toBe('UNSUPPORTED_ACTION');
  });

  test('returns invalid payload and success execution', async () => {
    const router = new DefaultActionRouter();
    const registry = new DefaultActionRegistry();
    registry.register({
      name: 'x',
      validate: () => ({ valid: false, error: 'bad input' }),
      execute: async () => ({ success: true, data: { ok: true } }),
    });
    router.setRegistry(registry);

    const invalid = await router.route('x', {}, context);
    expect(invalid.success).toBe(false);
    expect(invalid.errorCode).toBe('INVALID_PAYLOAD');

    registry.unregister('x');
    registry.register({
      name: 'x',
      validate: () => ({ valid: true }),
      execute: async (_payload, ctx) => ({ success: true, data: { id: ctx.agentId } }),
    });
    const ok = await router.route('x', { p: 1 }, context);
    expect(ok.success).toBe(true);
    expect(ok.data.id).toBe('agent-1');
  });
});

describe('createSdkAdapter coverage', () => {
  test('returns passthrough for invalid clients', () => {
    expect(createSdkAdapter(null)).toBeNull();
    const input = { session: {} };
    expect(createSdkAdapter(input)).toBe(input);
  });

  test('creates adapted sdk methods and forwards calls', async () => {
    const calls = { create: 0, abort: 0, prompt: 0, permission: 0 };
    const raw = {
      session: {
        create: async (options) => {
          calls.create += 1;
          return { data: options };
        },
        abort: async (options) => {
          calls.abort += 1;
          return { data: options };
        },
        prompt: async (options) => {
          calls.prompt += 1;
          return { data: options };
        },
      },
      postSessionIdPermissionsPermissionId: async (options) => {
        calls.permission += 1;
        return { data: options };
      },
    };

    const adapted = createSdkAdapter(raw);
    const r1 = await adapted.session.create({ sessionId: 's1', metadata: { a: 1 } });
    const r2 = await adapted.session.abort({ sessionId: 's1' });
    const r3 = await adapted.session.prompt({ sessionId: 's1', message: 'hi' });
    const r4 = await adapted.postSessionIdPermissionsPermissionId({
      sessionId: 's1',
      permissionId: 'p1',
      request: { decision: 'once' },
    });

    expect(calls).toEqual({ create: 1, abort: 1, prompt: 1, permission: 1 });
    expect(r1.data.sessionId).toBe('s1');
    expect(r2.data.sessionId).toBe('s1');
    expect(r3.data.message).toBe('hi');
    expect(r4.data.permissionId).toBe('p1');
  });
});
