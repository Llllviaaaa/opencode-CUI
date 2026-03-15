import { describe, test } from 'node:test';
import assert from 'node:assert/strict';

import { DefaultActionRouter } from '../../src/action/ActionRouter.ts';
import { DefaultActionRegistry } from '../../src/action/ActionRegistry.ts';
import { createSdkAdapter, getMissingSdkCapabilities } from '../../src/runtime/SdkAdapter.ts';

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
    assert.strictEqual(result.success, false);
    assert.strictEqual(result.errorCode, 'SDK_UNREACHABLE');
  });

  test('returns unsupported action when action not found', async () => {
    const router = new DefaultActionRouter();
    router.setRegistry(new DefaultActionRegistry());
    const result = await router.route('not_exists', {}, context);
    assert.strictEqual(result.success, false);
    assert.strictEqual(result.errorCode, 'UNSUPPORTED_ACTION');
  });

  test('executes registered action with typed payload routing', async () => {
    const router = new DefaultActionRouter();
    const registry = new DefaultActionRegistry();
    registry.register({
      name: 'x',
      execute: async (_payload, ctx) => ({ success: true, data: { id: ctx.agentId } }),
    });
    router.setRegistry(registry);

    const ok = await router.route('x', { p: 1 }, context);
    assert.strictEqual(ok.success, true);
    assert.strictEqual(ok.data.id, 'agent-1');
  });
});

describe('createSdkAdapter coverage', () => {
  test('reports missing capabilities in fixed order', () => {
    assert.deepStrictEqual(getMissingSdkCapabilities({
      session: {
        create: async () => ({}),
      },
    }), [
      'session.prompt',
      'session.abort',
      'session.delete',
      'postSessionIdPermissionsPermissionId',
      '_client.get',
      '_client.post',
    ]);
  });

  test('returns null for invalid or incomplete clients', () => {
    assert.strictEqual(createSdkAdapter(null), null);
    assert.strictEqual(createSdkAdapter({ session: {} }), null);
  });

  test('creates adapted sdk methods and forwards calls', async () => {
    const calls = { create: 0, abort: 0, delete: 0, prompt: 0, permission: 0, get: 0, post: 0 };
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
        delete: async (options) => {
          calls.delete += 1;
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
      _client: {
        get: async (options) => {
          calls.get += 1;
          return { data: options };
        },
        post: async (options) => {
          calls.post += 1;
          return { data: options };
        },
      },
    };

    const adapted = createSdkAdapter(raw);
    const r1 = await adapted.session.create({ body: { metadata: { a: 1 } } });
    const r2 = await adapted.session.abort({ path: { id: 's1' } });
    const r3 = await adapted.session.delete({ path: { id: 's1' } });
    const r4 = await adapted.session.prompt({
      path: { id: 's1' },
      body: { parts: [{ type: 'text', text: 'hi' }] },
    });
    const r5 = await adapted.postSessionIdPermissionsPermissionId({
      path: { id: 's1', permissionID: 'p1' },
      body: { response: 'once' },
    });
    const r6 = await adapted._client.get({ url: '/question' });
    const r7 = await adapted._client.post({ url: '/question/reply' });

    assert.deepStrictEqual(calls, { create: 1, abort: 1, delete: 1, prompt: 1, permission: 1, get: 1, post: 1 });
    assert.strictEqual(r1.data.body.metadata.a, 1);
    assert.strictEqual(r2.data.path.id, 's1');
    assert.strictEqual(r3.data.path.id, 's1');
    assert.strictEqual(r4.data.body.parts[0].text, 'hi');
    assert.strictEqual(r5.data.path.permissionID, 'p1');
    assert.strictEqual(r6.data.url, '/question');
    assert.strictEqual(r7.data.url, '/question/reply');
  });
});
