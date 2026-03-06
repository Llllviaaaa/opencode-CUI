import { test, describe } from 'node:test';
import { strict as assert } from 'node:assert';
import { MessageBridgePluginClass } from '../../dist/plugin/MessageBridgePlugin.js';
import { DefaultActionRouter } from '../../dist/action/ActionRouter.js';
import { DefaultActionRegistry } from '../../dist/action/ActionRegistry.js';
import { createMockSDK } from '../helpers/mock-sdk.mjs';

describe('Full Integration Tests', () => {
  function createStack(connectionState = 'READY') {
    const registry = new DefaultActionRegistry();
    new MessageBridgePluginClass(registry);

    const router = new DefaultActionRouter();
    router.setRegistry(registry);

    const mockClient = createMockSDK();
    const context = {
      client: mockClient,
      connectionState,
      agentId: 'bridge-integration-test',
      sessionId: 'ctx-session',
    };

    return { registry, router, mockClient, context };
  }

  test('routes all required actions', async () => {
    const { registry, router, context } = createStack();
    for (const action of ['chat', 'create_session', 'close_session', 'permission_reply', 'status_query']) {
      assert.ok(registry.has(action), `${action} should be registered`);
    }

    const createResult = await router.route('create_session', { metadata: { test: true } }, context);
    assert.equal(createResult.success, true);

    const sessionId = createResult.data.sessionId;

    const chatResult = await router.route('chat', { sessionId, message: 'hello' }, context);
    assert.equal(chatResult.success, true);

    const closeResult = await router.route('close_session', { sessionId }, context);
    assert.equal(closeResult.success, true);

    const statusResult = await router.route('status_query', { sessionId }, context);
    assert.equal(statusResult.success, true);
    assert.equal(statusResult.data.opencodeOnline, true);

    const permResult = await router.route(
      'permission_reply',
      { permissionId: 'perm-1', response: 'allow', toolSessionId: sessionId },
      context,
    );
    assert.equal(permResult.success, true);
  });

  test('close_session uses abort and never delete', async () => {
    const { router, mockClient, context } = createStack();

    const create = await router.route('create_session', {}, context);
    const sessionId = create.data.sessionId;
    const close = await router.route('close_session', { sessionId }, context);

    assert.equal(close.success, true);
    assert.equal(mockClient.getCallCount('sessionAbort'), 1);
    assert.equal(mockClient.getCallCount('sessionDelete'), 0);
  });

  test('permission_reply maps decision correctly', async () => {
    const { router, mockClient, context } = createStack();

    const allow = await router.route('permission_reply', { permissionId: 'p1', approved: true, toolSessionId: 's1' }, context);
    const deny = await router.route('permission_reply', { permissionId: 'p2', approved: false, toolSessionId: 's1' }, context);
    const always = await router.route('permission_reply', { permissionId: 'p3', response: 'always', toolSessionId: 's1' }, context);

    assert.equal(allow.success, true);
    assert.equal(deny.success, true);
    assert.equal(always.success, true);

    const calls = mockClient.calls.permissionReply;
    assert.equal(calls[0].options.request.decision, 'once');
    assert.equal(calls[1].options.request.decision, 'reject');
    assert.equal(calls[2].options.request.decision, 'always');
  });

  test('fast-fail state mapping follows PRD', async () => {
    const { router, context } = createStack('DISCONNECTED');
    const disconnected = await router.route('chat', { sessionId: 's1', message: 'm1' }, context);
    assert.equal(disconnected.success, false);
    assert.equal(disconnected.errorCode, 'GATEWAY_UNREACHABLE');

    context.connectionState = 'CONNECTING';
    const connecting = await router.route('chat', { sessionId: 's1', message: 'm1' }, context);
    assert.equal(connecting.success, false);
    assert.equal(connecting.errorCode, 'GATEWAY_UNREACHABLE');

    context.connectionState = 'CONNECTED';
    const connected = await router.route('chat', { sessionId: 's1', message: 'm1' }, context);
    assert.equal(connected.success, false);
    assert.equal(connected.errorCode, 'AGENT_NOT_READY');
  });
});
