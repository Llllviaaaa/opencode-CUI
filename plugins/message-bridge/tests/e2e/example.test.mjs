import { test, describe } from 'node:test';
import { strict as assert } from 'node:assert';
import { MessageBridgePluginClass } from '../../dist/plugin/MessageBridgePlugin.js';
import { DefaultActionRouter } from '../../dist/action/ActionRouter.js';
import { DefaultActionRegistry } from '../../dist/action/ActionRegistry.js';
import { EnvelopeBuilder } from '../../dist/event/EnvelopeBuilder.js';
import { FastFailDetector } from '../../dist/error/FastFailDetector.js';
import { createMockSDK } from '../helpers/mock-sdk.mjs';

describe('E2E Smoke Test Suite - Message Bridge Protocol Chains', () => {
  function createReadyContext() {
    const mockSDK = createMockSDK();
    return {
      client: mockSDK,
      connectionState: 'READY',
      agentId: 'test-agent-001',
      sessionId: 'ctx-session',
    };
  }

  test('action chain: create_session -> chat -> close_session', async () => {
    const registry = new DefaultActionRegistry();
    new MessageBridgePluginClass(registry);

    const router = new DefaultActionRouter();
    router.setRegistry(registry);

    const context = createReadyContext();

    const create = await router.route('create_session', {}, context);
    assert.equal(create.success, true);
    const sessionId = create.data.sessionId;

    const chat = await router.route('chat', { sessionId, message: 'hello e2e' }, context);
    assert.equal(chat.success, true);

    const close = await router.route('close_session', { sessionId }, context);
    assert.equal(close.success, true);
    assert.equal(context.client.getCallCount('sessionAbort'), 1);
    assert.equal(context.client.getCallCount('sessionDelete'), 0);
  });

  test('permission_reply dual format passes and maps to SDK decision', async () => {
    const action = registryAction('permission_reply');
    const context = createReadyContext();

    const target = await action.execute({ permissionId: 'perm-1', response: 'allow', toolSessionId: 's1' }, context);
    const compat = await action.execute({ permissionId: 'perm-2', approved: false, toolSessionId: 's1' }, context);

    assert.equal(target.success, true);
    assert.equal(compat.success, true);

    const calls = context.client.calls.permissionReply;
    assert.equal(calls[0].options.request.decision, 'once');
    assert.equal(calls[1].options.request.decision, 'reject');
  });

  test('fast-fail detector maps connection states within required codes', () => {
    const detector = new FastFailDetector();
    assert.equal(detector.checkState('DISCONNECTED'), 'GATEWAY_UNREACHABLE');
    assert.equal(detector.checkState('CONNECTING'), 'GATEWAY_UNREACHABLE');
    assert.equal(detector.checkState('CONNECTED'), 'AGENT_NOT_READY');
    assert.equal(detector.checkState('READY'), null);
  });

  test('envelope sequence increments by session and global scope', () => {
    const builder = new EnvelopeBuilder('agent-1');

    const a1 = builder.build('s1');
    const a2 = builder.build('s1');
    const b1 = builder.build('s2');
    const g1 = builder.build();

    assert.equal(a1.sequenceNumber, 1);
    assert.equal(a2.sequenceNumber, 2);
    assert.equal(b1.sequenceNumber, 1);
    assert.equal(g1.sequenceNumber, 1);
    assert.equal(a1.sequenceScope, 'session');
    assert.equal(g1.sequenceScope, 'global');
  });

  function registryAction(name) {
    const registry = new DefaultActionRegistry();
    new MessageBridgePluginClass(registry);
    return registry.get(name);
  }
});
