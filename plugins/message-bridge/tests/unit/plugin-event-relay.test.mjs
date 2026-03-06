import { describe, test } from 'node:test';
import { strict as assert } from 'node:assert';
import { MessageBridgePluginClass } from '../../dist/plugin/MessageBridgePlugin.js';
import { DefaultActionRegistry } from '../../dist/action/ActionRegistry.js';
import { EnvelopeBuilder } from '../../dist/event/EnvelopeBuilder.js';
import { EventRelay } from '../../dist/event/EventRelay.js';
import { DefaultStateManager } from '../../dist/connection/StateManager.js';

function createPluginHarness() {
  const registry = new DefaultActionRegistry();
  const plugin = new MessageBridgePluginClass(registry);
  const sent = [];
  plugin.gatewayConnection = {
    send: (msg) => sent.push(msg),
  };
  plugin.envelopeBuilder = new EnvelopeBuilder('agent-test');
  plugin.actionRouter = {
    route: async () => ({ success: true, data: {} }),
  };
  return { plugin, sent };
}

describe('MessageBridgePlugin downstream/uplink contracts', () => {
  test('status_query -> status_response', async () => {
    const { plugin, sent } = createPluginHarness();
    plugin.actionRouter.route = async (action, payload, context) => {
      assert.equal(action, 'status_query');
      assert.deepEqual(payload, { sessionId: 's-1' });
      assert.equal(context.sessionId, 's-1');
      return { success: true, data: { opencodeOnline: true } };
    };

    await plugin.handleDownstreamMessage({ type: 'status_query', sessionId: 's-1' });

    assert.equal(sent.length, 1);
    assert.equal(sent[0].type, 'status_response');
    assert.equal(sent[0].opencodeOnline, true);
    assert.equal(sent[0].sessionId, 's-1');
    assert.equal(sent[0].envelope.sessionId, 's-1');
  });

  test('invoke(create_session) without sessionId -> tool_error', async () => {
    const { plugin, sent } = createPluginHarness();
    plugin.actionRouter.route = async () => ({ success: true, data: {} });

    await plugin.handleDownstreamMessage({
      type: 'invoke',
      action: 'create_session',
      payload: {},
      envelope: { sessionId: 's-x', sequence: 1, timestamp: new Date().toISOString(), agentId: 'agent-test' },
    });

    assert.equal(sent.length, 1);
    assert.equal(sent[0].type, 'tool_error');
    assert.equal(sent[0].code, 'SDK_UNREACHABLE');
    assert.match(sent[0].error, /sessionId/i);
  });

  test('invoke(chat) failure falls back to state-derived code', async () => {
    const { plugin, sent } = createPluginHarness();
    plugin.stateManager.setState('CONNECTING');
    plugin.actionRouter.route = async () => ({ success: false, errorMessage: 'downstream failed' });

    await plugin.handleDownstreamMessage({
      type: 'invoke',
      action: 'chat',
      payload: { sessionId: 's-2', message: 'hello' },
      envelope: { sessionId: 's-2', sequence: 2, timestamp: new Date().toISOString(), agentId: 'agent-test' },
    });

    assert.equal(sent.length, 1);
    assert.equal(sent[0].type, 'tool_error');
    assert.equal(sent[0].code, 'GATEWAY_UNREACHABLE');
    assert.equal(sent[0].sessionId, 's-2');
  });

  test('invoke(chat) success -> tool_done', async () => {
    const { plugin, sent } = createPluginHarness();
    plugin.stateManager.setState('READY');
    plugin.actionRouter.route = async (action, payload, context) => {
      assert.equal(action, 'chat');
      assert.equal(payload.sessionId, 's-3');
      assert.equal(context.sessionId, 's-3');
      return { success: true, data: { ok: true } };
    };

    await plugin.handleDownstreamMessage({
      type: 'invoke',
      action: 'chat',
      payload: { sessionId: 's-3', message: 'hello' },
      envelope: { sessionId: 's-3', sequence: 3, timestamp: new Date().toISOString(), agentId: 'agent-test' },
    });

    assert.equal(sent.length, 1);
    assert.equal(sent[0].type, 'tool_done');
    assert.deepEqual(sent[0].result, { ok: true });
    assert.equal(sent[0].sessionId, 's-3');
  });

  test('invoke(create_session) success -> session_created', async () => {
    const { plugin, sent } = createPluginHarness();
    plugin.stateManager.setState('READY');
    plugin.actionRouter.route = async () => ({ success: true, data: { sessionId: 'created-1' } });

    await plugin.handleDownstreamMessage({
      type: 'invoke',
      action: 'create_session',
      payload: {},
      envelope: { sequence: 4, timestamp: new Date().toISOString(), agentId: 'agent-test' },
    });

    assert.equal(sent.length, 1);
    assert.equal(sent[0].type, 'session_created');
    assert.equal(sent[0].sessionId, 'created-1');
    assert.equal(sent[0].envelope.sessionId, 'created-1');
  });
});

describe('EventRelay observability and envelope behavior', () => {
  test('reject non-allowlist event and record unsupported_event', async () => {
    const handlers = [];
    const sent = [];
    const stateManager = new DefaultStateManager();
    stateManager.generateAndBindAgentId();
    stateManager.setState('READY');

    const relay = new EventRelay(
      { event: { subscribe: (fn) => { handlers.push(fn); return () => {}; } } },
      { send: (msg) => sent.push(msg) },
      stateManager,
      { allowlist: ['session.idle'] },
    );

    const warns = [];
    const oldWarn = console.warn;
    console.warn = (...args) => warns.push(args);
    try {
      await relay.start();
      await handlers[0]({ type: 'session.created' });
    } finally {
      console.warn = oldWarn;
      relay.stop();
    }

    assert.equal(sent.length, 0);
    assert.equal(warns.length, 1);
    assert.equal(warns[0][0], 'unsupported_event');
    assert.equal(warns[0][1].eventType, 'session.created');
  });

  test('agentId change rebuilds envelope sequence scope', async () => {
    const handlers = [];
    const sent = [];
    const unsubscribed = { value: false };
    const stateManager = new DefaultStateManager();
    stateManager.generateAndBindAgentId();
    stateManager.setState('READY');

    const relay = new EventRelay(
      { event: { subscribe: (fn) => { handlers.push(fn); return () => { unsubscribed.value = true; }; } } },
      { send: (msg) => sent.push(msg) },
      stateManager,
      { allowlist: ['session.idle'] },
    );

    await relay.start();
    await handlers[0]({ type: 'session.idle', properties: { sessionId: 's-1' } });
    const firstAgentId = sent[0].envelope.agentId;
    assert.equal(sent[0].envelope.sequenceNumber, 1);

    stateManager.resetForReconnect();
    stateManager.setState('READY');
    await handlers[0]({ type: 'session.idle', properties: { sessionId: 's-1' } });

    assert.equal(sent.length, 2);
    assert.notEqual(sent[1].envelope.agentId, firstAgentId);
    assert.equal(sent[1].envelope.sequenceNumber, 1);

    relay.stop();
    assert.equal(unsubscribed.value, true);
  });
});
