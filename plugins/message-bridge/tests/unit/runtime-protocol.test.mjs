import { describe, test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, mkdir, rm, writeFile } from 'node:fs/promises';
import os, { hostname, tmpdir } from 'node:os';
import { join } from 'node:path';

import { BridgeRuntime } from '../../src/runtime/BridgeRuntime.ts';
import { EventFilter } from '../../src/event/EventFilter.ts';

function createRuntimeClient(overrides = {}) {
  const base = {
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

  const hasOwn = (key) => Object.prototype.hasOwnProperty.call(overrides, key);

  return {
    ...base,
    ...overrides,
    app: hasOwn('app') ? (overrides.app ? { ...overrides.app } : overrides.app) : undefined,
    session: {
      ...base.session,
      ...(overrides.session ?? {}),
    },
    _client: hasOwn('_client')
      ? (overrides._client ? { ...base._client, ...overrides._client } : overrides._client)
      : base._client,
    global: hasOwn('global')
      ? (overrides.global ? { ...base.global, ...overrides.global } : overrides.global)
      : base.global,
  };
}

async function writeEnabledConfig(workspace) {
  await mkdir(join(workspace, '.opencode'), { recursive: true });
  await writeFile(
    join(workspace, '.opencode', 'message-bridge.json'),
    JSON.stringify({
      config_version: 1,
      enabled: true,
      gateway: {
        url: 'ws://localhost:8081/ws/agent',
        channel: 'opencode',
        heartbeatIntervalMs: 30000,
        reconnect: {
          baseMs: 1000,
          maxMs: 30000,
          exponential: true,
        },
      },
      sdk: {
        timeoutMs: 10000,
      },
      auth: {
        ak: 'test-ak-001',
        sk: 'test-sk-secret-001',
      },
      events: {
        allowlist: ['message.updated'],
      },
    }),
    'utf8',
  );
}

function createRegisterCaptureWebSocket() {
  return class RegisterCaptureWebSocket {
    static OPEN = 1;
    static instances = [];

    constructor() {
      this.readyState = 0;
      this.sent = [];
      RegisterCaptureWebSocket.instances.push(this);
      setTimeout(() => {
        this.readyState = RegisterCaptureWebSocket.OPEN;
        this.onopen?.();
        this.onmessage?.({ data: JSON.stringify({ type: 'register_ok' }) });
      }, 0);
    }

    send(data) {
      this.sent.push(JSON.parse(data));
    }

    close() {
      this.readyState = 3;
      this.onclose?.();
    }
  };
}

describe('runtime protocol strictness', () => {
  test('rejects non-baseline nested invoke payload and returns tool_error without code', async () => {
    const runtime = new BridgeRuntime({
      client: createRuntimeClient(),
    });

    const sent = [];
    runtime.gatewayConnection = { send: (msg) => sent.push(msg) };
    runtime.stateManager.setState('READY');

    await runtime.handleDownstreamMessage({
      type: 'invoke',
      welinkSessionId: '42',
      payload: {
        action: 'chat',
        payload: { toolSessionId: 'tool-1', text: 'hello' },
      },
    });

    assert.strictEqual((sent).length, 1);
    assert.strictEqual(sent[0].type, 'tool_error');
    assert.strictEqual(sent[0].welinkSessionId, '42');
    assert.strictEqual('code' in sent[0], false);
  });

  test('chat session-not-found failure adds tool_error reason for auto-rebuild', async () => {
    const runtime = new BridgeRuntime({
      client: createRuntimeClient(),
    });

    const sent = [];
    runtime.gatewayConnection = { send: (msg) => sent.push(msg) };
    runtime.stateManager.setState('READY');
    runtime.actionRouter = {
      route: async () => ({
        success: false,
        errorCode: 'INVALID_PAYLOAD',
        errorMessage: 'Failed to send message: session not found',
      }),
    };

    await runtime.handleDownstreamMessage({
      type: 'invoke',
      welinkSessionId: 's-rebuild',
      action: 'chat',
      payload: { toolSessionId: 'tool-rebuild', text: 'hello' },
    });

    assert.strictEqual((sent).length, 1);
    assert.deepStrictEqual(sent[0], {
      type: 'tool_error',
      welinkSessionId: 's-rebuild',
      toolSessionId: 'tool-rebuild',
      error: 'Failed to send message: session not found',
      reason: 'session_not_found',
    });
  });

  test('accepts baseline invoke shape and emits tool_done on chat success', async () => {
    const prompts = [];
    const runtime = new BridgeRuntime({
      client: createRuntimeClient({
        session: {
          prompt: async (options) => {
            prompts.push(options);
            return { data: { ok: true } };
          },
        },
      }),
    });

    const sent = [];
    runtime.gatewayConnection = { send: (msg) => sent.push(msg) };
    runtime.stateManager.setState('READY');

    await runtime.handleDownstreamMessage({
      type: 'invoke',
      welinkSessionId: '100',
      action: 'chat',
      payload: { toolSessionId: 'tool-100', text: 'hello' },
    });

    assert.strictEqual((prompts).length, 1);
    assert.deepStrictEqual(prompts[0], {
      path: { id: 'tool-100' },
      body: { parts: [{ type: 'text', text: 'hello' }] },
    });
    assert.strictEqual((sent).length, 1);
    assert.strictEqual(sent[0].type, 'tool_done');
    assert.strictEqual(sent[0].toolSessionId, 'tool-100');
    assert.strictEqual(sent[0].welinkSessionId, '100');
  });

  test('rejects permission_reply payloads with unsupported response values', async () => {
    const runtime = new BridgeRuntime({
      client: createRuntimeClient(),
    });

    const sent = [];
    runtime.gatewayConnection = { send: (msg) => sent.push(msg) };
    runtime.stateManager.setState('READY');

    await runtime.handleDownstreamMessage({
      type: 'invoke',
      welinkSessionId: 'perm-42',
      action: 'permission_reply',
      payload: { toolSessionId: 'tool-42', permissionId: 'perm-1', response: 'allow' },
    });

    assert.strictEqual((sent).length, 1);
    assert.strictEqual(sent[0].type, 'tool_error');
    assert.strictEqual(sent[0].welinkSessionId, 'perm-42');
  });

  test('accepts question_reply invoke shape and routes answer via question API', async () => {
    const getCalls = [];
    const postCalls = [];
    const runtime = new BridgeRuntime({
      client: createRuntimeClient({
        session: {
          prompt: async () => ({ data: { ok: true } }),
        },
        _client: {
          get: async (options) => {
            getCalls.push(options);
            return {
              data: [
                {
                  id: 'question-request-42',
                  sessionID: 'tool-42',
                  tool: { callID: 'call-42' },
                },
              ],
            };
          },
          post: async (options) => {
            postCalls.push(options);
            return { data: undefined };
          },
        },
      }),
    });

    const sent = [];
    runtime.gatewayConnection = { send: (msg) => sent.push(msg) };
    runtime.stateManager.setState('READY');

    await runtime.handleDownstreamMessage({
      type: 'invoke',
      welinkSessionId: 'q-42',
      action: 'question_reply',
      payload: { toolSessionId: 'tool-42', toolCallId: 'call-42', answer: 'Vite' },
    });

    assert.deepStrictEqual(getCalls, [{ url: '/question' }]);
    assert.deepStrictEqual(postCalls, [
      {
        url: '/question/{requestID}/reply',
        path: { requestID: 'question-request-42' },
        body: { answers: [['Vite']] },
        headers: { 'Content-Type': 'application/json' },
      },
    ]);
    assert.strictEqual((sent).length, 0);
  });

  test('accepts question_reply payloads missing toolCallId', async () => {
    const postCalls = [];
    const runtime = new BridgeRuntime({
      client: createRuntimeClient({
        session: {
          prompt: async () => ({ data: { ok: true } }),
        },
        _client: {
          get: async () => ({
            data: [
              {
                id: 'question-request-43',
                sessionID: 'tool-43',
              },
            ],
          }),
          post: async (options) => {
            postCalls.push(options);
            return { data: undefined };
          },
        },
      }),
    });

    const sent = [];
    runtime.gatewayConnection = { send: (msg) => sent.push(msg) };
    runtime.stateManager.setState('READY');

    await runtime.handleDownstreamMessage({
      type: 'invoke',
      welinkSessionId: 'q-43',
      action: 'question_reply',
      payload: { toolSessionId: 'tool-43', answer: 'Vite' },
    });

    assert.strictEqual((postCalls).length, 1);
    assert.strictEqual((sent).length, 0);
  });

  test('does not emit tool_done on permission_reply success', async () => {
    const permissionCalls = [];
    const runtime = new BridgeRuntime({
      client: createRuntimeClient({
        session: {
          prompt: async () => ({ data: { ok: true } }),
        },
        postSessionIdPermissionsPermissionId: async (options) => {
          permissionCalls.push(options);
          return {};
        },
      }),
    });

    const sent = [];
    runtime.gatewayConnection = { send: (msg) => sent.push(msg) };
    runtime.stateManager.setState('READY');

    await runtime.handleDownstreamMessage({
      type: 'invoke',
      welinkSessionId: 'perm-1',
      action: 'permission_reply',
      payload: { toolSessionId: 'tool-perm-1', permissionId: 'perm-a', response: 'once' },
    });

    assert.deepStrictEqual(permissionCalls, [
      {
        path: { id: 'tool-perm-1', permissionID: 'perm-a' },
        body: { response: 'once' },
      },
    ]);
    assert.strictEqual((sent).length, 0);
  });

  test('rejects question_reply when toolCallId is omitted and pending request is not unique', async () => {
    const runtime = new BridgeRuntime({
      client: createRuntimeClient({
        session: {
          prompt: async () => ({ data: { ok: true } }),
        },
        _client: {
          get: async () => ({
            data: [
              { id: 'question-request-a', sessionID: 'tool-43' },
              { id: 'question-request-b', sessionID: 'tool-43' },
            ],
          }),
          post: async () => ({ data: undefined }),
        },
      }),
    });

    const sent = [];
    runtime.gatewayConnection = { send: (msg) => sent.push(msg) };
    runtime.stateManager.setState('READY');

    await runtime.handleDownstreamMessage({
      type: 'invoke',
      welinkSessionId: 'q-43b',
      action: 'question_reply',
      payload: { toolSessionId: 'tool-43', answer: 'Vite' },
    });

    assert.strictEqual((sent).length, 1);
    assert.strictEqual(sent[0].type, 'tool_error');
    assert.ok((sent[0].error).includes('unique pending question'));
  });

  test('rejects question_reply when no pending request matches', async () => {
    const runtime = new BridgeRuntime({
      client: createRuntimeClient({
        session: {
          prompt: async () => ({ data: { ok: true } }),
        },
        _client: {
          get: async () => ({
            data: [
              {
                id: 'question-request-99',
                sessionID: 'tool-other',
                tool: { callID: 'call-other' },
              },
            ],
          }),
          post: async () => ({ data: undefined }),
        },
      }),
    });

    const sent = [];
    runtime.gatewayConnection = { send: (msg) => sent.push(msg) };
    runtime.stateManager.setState('READY');

    await runtime.handleDownstreamMessage({
      type: 'invoke',
      welinkSessionId: 'q-46',
      action: 'question_reply',
      payload: { toolSessionId: 'tool-46', toolCallId: 'call-46', answer: 'Vite' },
    });

    assert.strictEqual((sent).length, 1);
    assert.strictEqual(sent[0].type, 'tool_error');
    assert.strictEqual(sent[0].welinkSessionId, 'q-46');
  });

  test('rejects question_reply payloads missing toolSessionId', async () => {
    const runtime = new BridgeRuntime({
      client: createRuntimeClient({
        session: {
          prompt: async () => ({ data: { ok: true } }),
        },
      }),
    });

    const sent = [];
    runtime.gatewayConnection = { send: (msg) => sent.push(msg) };
    runtime.stateManager.setState('READY');

    await runtime.handleDownstreamMessage({
      type: 'invoke',
      welinkSessionId: 'q-44',
      action: 'question_reply',
      payload: { toolCallId: 'call-44', answer: 'Vite' },
    });

    assert.strictEqual((sent).length, 1);
    assert.strictEqual(sent[0].type, 'tool_error');
    assert.strictEqual(sent[0].welinkSessionId, 'q-44');
  });

  test('rejects question_reply payloads missing answer', async () => {
    const runtime = new BridgeRuntime({
      client: createRuntimeClient({
        session: {
          prompt: async () => ({ data: { ok: true } }),
        },
      }),
    });

    const sent = [];
    runtime.gatewayConnection = { send: (msg) => sent.push(msg) };
    runtime.stateManager.setState('READY');

    await runtime.handleDownstreamMessage({
      type: 'invoke',
      welinkSessionId: 'q-45',
      action: 'question_reply',
      payload: { toolSessionId: 'tool-45', toolCallId: 'call-45' },
    });

    assert.strictEqual((sent).length, 1);
    assert.strictEqual(sent[0].type, 'tool_error');
    assert.strictEqual(sent[0].welinkSessionId, 'q-45');
  });

  test('responds to standalone status_query with envelope-free status_response', async () => {
    const runtime = new BridgeRuntime({
      client: createRuntimeClient(),
    });

    const sent = [];
    runtime.gatewayConnection = { send: (msg) => sent.push(msg) };
    runtime.stateManager.setState('READY');

    await runtime.handleDownstreamMessage({
      type: 'status_query',
    });

    assert.strictEqual((sent).length, 1);
    assert.strictEqual(sent[0].type, 'status_response');
    assert.deepStrictEqual(sent[0], {
      type: 'status_response',
      opencodeOnline: true,
    });
  });

  test('rejects create_session without welinkSessionId', async () => {
    const createCalls = [];
    const runtime = new BridgeRuntime({
      client: createRuntimeClient({
        session: {
          create: async (options) => {
            createCalls.push(options);
            return { data: { id: 'created-1' } };
          },
        },
      }),
    });

    const sent = [];
    runtime.gatewayConnection = { send: (msg) => sent.push(msg) };
    runtime.stateManager.setState('READY');

    await runtime.handleDownstreamMessage({
      type: 'invoke',
      action: 'create_session',
      payload: {},
    });

    assert.strictEqual((createCalls).length, 0);
    assert.strictEqual((sent).length, 1);
    assert.strictEqual(sent[0].type, 'tool_error');
    assert.strictEqual(sent[0].welinkSessionId, undefined);
    assert.strictEqual(sent[0].error, 'welinkSessionId is required');
  });

  test('rejects blank create_session welinkSessionId', async () => {
    const createCalls = [];
    const runtime = new BridgeRuntime({
      client: createRuntimeClient({
        session: {
          create: async (options) => {
            createCalls.push(options);
            return { data: { id: 'created-1' } };
          },
        },
      }),
    });

    const sent = [];
    runtime.gatewayConnection = { send: (msg) => sent.push(msg) };
    runtime.stateManager.setState('READY');

    await runtime.handleDownstreamMessage({
      type: 'invoke',
      welinkSessionId: '   ',
      action: 'create_session',
      payload: {},
    });

    assert.strictEqual((createCalls).length, 0);
    assert.strictEqual((sent).length, 1);
    assert.strictEqual(sent[0].type, 'tool_error');
    assert.strictEqual(sent[0].welinkSessionId, '   ');
    assert.strictEqual(sent[0].error, 'welinkSessionId is required');
  });

  test('create_session missing welinkSessionId does not call SDK and does not emit warning-only success', async () => {
    const appLogs = [];
    const runtime = new BridgeRuntime({
      client: createRuntimeClient({
        app: {
          log: async (options) => {
            appLogs.push(options.body);
            return true;
          },
        },
        session: {
          create: async () => ({ data: { id: 'created-1' } }),
        },
      }),
    });

    const sent = [];
    runtime.gatewayConnection = { send: (msg) => sent.push(msg) };
    runtime.stateManager.setState('READY');

    await runtime.handleDownstreamMessage({
      type: 'invoke',
      messageId: 'gw-create-1',
      action: 'create_session',
      payload: {},
    });
    await new Promise((r) => setTimeout(r, 10));

    const warningLog = appLogs.find((entry) => entry.message === 'runtime.create_session.missing_welink_session_id');
    assert.strictEqual(warningLog, undefined);
    assert.strictEqual(sent[0].type, 'tool_error');
    assert.strictEqual(sent[0].welinkSessionId, undefined);
    assert.strictEqual(sent[0].error, 'welinkSessionId is required');
  });

  test('rejects invoke status_query compatibility variant', async () => {
    const runtime = new BridgeRuntime({
      client: createRuntimeClient(),
    });

    const sent = [];
    runtime.gatewayConnection = { send: (msg) => sent.push(msg) };
    runtime.stateManager.setState('READY');

    await runtime.handleDownstreamMessage({
      type: 'invoke',
      welinkSessionId: 'status-compat',
      action: 'status_query',
      payload: {},
    });

    assert.strictEqual((sent).length, 1);
    assert.strictEqual(sent[0].type, 'tool_error');
    assert.strictEqual(sent[0].welinkSessionId, 'status-compat');
  });

  test('applies config.debug to runtime fallback logging after config load', async () => {
    const workspace = await mkdtemp(join(tmpdir(), 'mb-runtime-'));
    await mkdir(join(workspace, '.opencode'), { recursive: true });
    await writeFile(
      join(workspace, '.opencode', 'message-bridge.jsonc'),
      JSON.stringify({
        config_version: 1,
        enabled: false,
        debug: true,
        gateway: {
          url: 'ws://localhost:8081/ws/agent',
          heartbeatIntervalMs: 30000,
          reconnect: {
            baseMs: 1000,
            maxMs: 30000,
            exponential: true,
          },
        },
        sdk: {
          timeoutMs: 10000,
        },
        auth: {
          ak: '',
          sk: '',
        },
        events: {
          allowlist: ['message.updated'],
        },
      }),
      'utf8',
    );

    const debugCalls = [];
    const originalDebug = console.debug;
    console.debug = (...args) => {
      debugCalls.push(args);
    };

    try {
      const runtime = new BridgeRuntime({
        workspacePath: workspace,
        client: {},
      });

      await runtime.start();

      assert.strictEqual(runtime.getStarted(), true);
      assert.ok(debugCalls.some((args) => args.includes('runtime.start.disabled_by_config')));
    } finally {
      console.debug = originalDebug;
      await rm(workspace, { recursive: true, force: true });
    }
  });

  test('logs event ids, delta bytes, and traceId when forwarding upstream events', async () => {
    const appLogs = [];
    const runtime = new BridgeRuntime({
      client: {
        app: {
          log: async (options) => {
            appLogs.push(options.body);
            return true;
          },
        },
      },
    });

    const sent = [];
    runtime.gatewayConnection = {
      send: (message, context) => sent.push({ message, context }),
    };
    runtime.eventFilter = new EventFilter(['message.part.updated']);
    runtime.stateManager.setState('READY');

    await runtime.handleEvent({
      type: 'message.part.updated',
      properties: {
        delta: '你好，bridge',
        part: {
          sessionID: 'tool-1',
          messageID: 'op-msg-1',
          id: 'part-1',
          type: 'text',
        },
      },
    });
    await new Promise((r) => setTimeout(r, 10));

    const receivedLog = appLogs.find((entry) => entry.message === 'event.received');
    const forwardingLog = appLogs.find((entry) => entry.message === 'event.forwarding');
    const forwardedLog = appLogs.find((entry) => entry.message === 'event.forwarded');

    assert.strictEqual(receivedLog.extra.traceId, 'op-msg-1');
    assert.notStrictEqual(receivedLog.extra.runtimeTraceId, undefined);
    assert.strictEqual(receivedLog.extra.opencodeMessageId, 'op-msg-1');
    assert.strictEqual(receivedLog.extra.opencodePartId, 'part-1');
    assert.strictEqual(receivedLog.extra.toolSessionId, 'tool-1');
    assert.strictEqual(receivedLog.extra.partType, 'text');
    assert.strictEqual(receivedLog.extra.deltaBytes, Buffer.byteLength('你好，bridge', 'utf8'));

    assert.strictEqual(forwardingLog.extra.toolSessionId, 'tool-1');
    assert.notStrictEqual(forwardingLog.extra.traceId, undefined);
    assert.strictEqual('bridgeMessageId' in forwardingLog.extra, false);
    assert.strictEqual(forwardingLog.extra.opencodeMessageId, 'op-msg-1');
    assert.strictEqual(forwardingLog.extra.opencodePartId, 'part-1');
    assert.strictEqual(forwardedLog.extra.traceId, forwardingLog.extra.traceId);
    assert.strictEqual('bridgeMessageId' in forwardedLog.extra, false);

    assert.strictEqual((sent).length, 1);
    assert.strictEqual(sent[0].context.traceId, forwardingLog.extra.traceId);
    assert.strictEqual(sent[0].context.gatewayMessageId, forwardingLog.extra.traceId);
    assert.strictEqual(sent[0].context.opencodeMessageId, 'op-msg-1');
    assert.strictEqual(sent[0].context.opencodePartId, 'part-1');
  });

  test('forwards session.idle as tool_event and emits fallback tool_done', async () => {
    const runtime = new BridgeRuntime({ client: {} });
    const sent = [];

    runtime.gatewayConnection = {
      send: (message, context) => sent.push({ message, context }),
    };
    runtime.eventFilter = new EventFilter(['session.idle']);
    runtime.stateManager.setState('READY');

    await runtime.handleEvent({
      type: 'session.idle',
      properties: {
        sessionID: 'tool-idle-1',
      },
    });

    assert.strictEqual((sent).length, 2);
    assert.strictEqual(sent[0].message.type, 'tool_event');
    assert.strictEqual(sent[0].message.toolSessionId, 'tool-idle-1');
    assert.strictEqual(sent[1].message.type, 'tool_done');
    assert.strictEqual(sent[1].message.toolSessionId, 'tool-idle-1');
  });

  test('does not emit duplicate tool_done when session.idle follows chat success', async () => {
    const runtime = new BridgeRuntime({
      client: createRuntimeClient({
        session: {
          prompt: async () => ({ data: { ok: true } }),
        },
      }),
    });
    const sent = [];

    runtime.gatewayConnection = {
      send: (message, context) => sent.push({ message, context }),
    };
    runtime.eventFilter = new EventFilter(['session.idle']);
    runtime.stateManager.setState('READY');

    await runtime.handleDownstreamMessage({
      type: 'invoke',
      welinkSessionId: '42',
      action: 'chat',
      payload: { toolSessionId: 'tool-idle-2', text: 'hello' },
    });

    await runtime.handleEvent({
      type: 'session.idle',
      properties: {
        sessionID: 'tool-idle-2',
      },
    });

    assert.strictEqual((sent.filter((entry) => entry.message.type === 'tool_done')).length, 1);
    assert.strictEqual((sent.filter((entry) => entry.message.type === 'tool_event')).length, 1);
  });

  test('defers session.idle tool_done while chat prompt is still pending', async () => {
    let resolvePrompt;
    const promptPromise = new Promise((resolve) => {
      resolvePrompt = resolve;
    });
    const runtime = new BridgeRuntime({
      client: createRuntimeClient({
        session: {
          prompt: async () => {
            await promptPromise;
            return { data: { ok: true } };
          },
        },
      }),
    });
    const sent = [];

    runtime.gatewayConnection = {
      send: (message, context) => sent.push({ message, context }),
    };
    runtime.eventFilter = new EventFilter(['session.idle']);
    runtime.stateManager.setState('READY');

    const invokeTask = runtime.handleDownstreamMessage({
      type: 'invoke',
      welinkSessionId: '43',
      action: 'chat',
      payload: { toolSessionId: 'tool-idle-3', text: 'hello' },
    });

    await runtime.handleEvent({
      type: 'session.idle',
      properties: {
        sessionID: 'tool-idle-3',
      },
    });

    assert.strictEqual((sent.filter((entry) => entry.message.type === 'tool_done')).length, 0);
    assert.strictEqual((sent.filter((entry) => entry.message.type === 'tool_event')).length, 1);

    resolvePrompt({ data: { ok: true } });
    await invokeTask;

    assert.strictEqual((sent.filter((entry) => entry.message.type === 'tool_done')).length, 1);
  });

  test('does not emit tool_done for close_session success', async () => {
    const deleteCalls = [];
    const runtime = new BridgeRuntime({
      client: createRuntimeClient({
        session: {
          delete: async (options) => {
            deleteCalls.push(options);
            return {};
          },
          prompt: async () => ({ data: { ok: true } }),
        },
      }),
    });
    const sent = [];

    runtime.gatewayConnection = { send: (msg) => sent.push(msg) };
    runtime.stateManager.setState('READY');

    await runtime.handleDownstreamMessage({
      type: 'invoke',
      welinkSessionId: 'close-1',
      action: 'close_session',
      payload: { toolSessionId: 'tool-close-1' },
    });

    assert.deepStrictEqual(deleteCalls, [{ path: { id: 'tool-close-1' } }]);
    assert.strictEqual((sent).length, 0);
  });

  test('runtime.start sends register with runtime-derived metadata', async () => {
    const workspace = await mkdtemp(join(tmpdir(), 'mb-runtime-register-'));
    const fakeHome = await mkdtemp(join(tmpdir(), 'mb-home-'));
    process.env.HOME = fakeHome;

    await mkdir(join(workspace, '.opencode'), { recursive: true });
    await writeFile(
      join(workspace, '.opencode', 'message-bridge.json'),
      JSON.stringify({
        config_version: 1,
        enabled: true,
        gateway: {
          url: 'ws://localhost:8081/ws/agent',
          deviceName: 'dev',
          macAddress: '11:22:33:44:55:66',
          channel: 'opencode',
          toolVersion: '1.2.3',
          heartbeatIntervalMs: 30000,
          reconnect: {
            baseMs: 1000,
            maxMs: 30000,
            exponential: true,
          },
        },
        sdk: {
          timeoutMs: 10000,
        },
        auth: {
          ak: 'test-ak-001',
          sk: 'test-sk-secret-001',
        },
        events: {
          allowlist: ['message.updated'],
        },
      }),
      'utf8',
    );

    const originalWebSocket = globalThis.WebSocket;
    class RegisterCaptureWebSocket {
      static OPEN = 1;
      static instances = [];

      constructor() {
        this.readyState = 0;
        this.sent = [];
        RegisterCaptureWebSocket.instances.push(this);
        setTimeout(() => {
          this.readyState = RegisterCaptureWebSocket.OPEN;
          this.onopen?.();
          this.onmessage?.({ data: JSON.stringify({ type: 'register_ok' }) });
        }, 0);
      }

      send(data) {
        this.sent.push(JSON.parse(data));
      }

      close() {
        this.readyState = 3;
        this.onclose?.();
      }
    }

    globalThis.WebSocket = RegisterCaptureWebSocket;
    const originalNetworkInterfaces = os.networkInterfaces;
    os.networkInterfaces = () => ({
      en0: [
        {
          address: '127.0.0.1',
          netmask: '255.0.0.0',
          family: 'IPv4',
          mac: '11:22:33:44:55:66',
          internal: false,
          cidr: null,
        },
      ],
    });
    try {
      const runtime = new BridgeRuntime({
        workspacePath: workspace,
        client: createRuntimeClient(),
      });

      await runtime.start();
      await new Promise((r) => setTimeout(r, 10));

      const ws = RegisterCaptureWebSocket.instances[0];
      assert.strictEqual(ws.sent[0].type, 'register');
      assert.strictEqual(ws.sent[0].deviceName, hostname());
      assert.strictEqual(ws.sent[0].macAddress, '11:22:33:44:55:66');
      assert.strictEqual(typeof ws.sent[0].os, 'string');
      assert.strictEqual(ws.sent[0].toolType, 'opencode');
      assert.strictEqual(ws.sent[0].toolVersion, '9.9.9');

      runtime.stop();
    } finally {
      os.networkInterfaces = originalNetworkInterfaces;
      globalThis.WebSocket = originalWebSocket;
      await rm(workspace, { recursive: true, force: true });
      await rm(fakeHome, { recursive: true, force: true });
    }
  });

  test('runtime.start sends empty macAddress when no usable interface is available', async () => {
    const workspace = await mkdtemp(join(tmpdir(), 'mb-runtime-register-empty-mac-'));
    const fakeHome = await mkdtemp(join(tmpdir(), 'mb-home-'));
    process.env.HOME = fakeHome;

    await mkdir(join(workspace, '.opencode'), { recursive: true });
    await writeFile(
      join(workspace, '.opencode', 'message-bridge.json'),
      JSON.stringify({
        config_version: 1,
        enabled: true,
        gateway: {
          url: 'ws://localhost:8081/ws/agent',
          channel: 'opencode',
          heartbeatIntervalMs: 30000,
          reconnect: {
            baseMs: 1000,
            maxMs: 30000,
            exponential: true,
          },
        },
        sdk: {
          timeoutMs: 10000,
        },
        auth: {
          ak: 'test-ak-001',
          sk: 'test-sk-secret-001',
        },
        events: {
          allowlist: ['message.updated'],
        },
      }),
      'utf8',
    );

    const originalWebSocket = globalThis.WebSocket;
    class RegisterCaptureWebSocket {
      static OPEN = 1;
      static instances = [];

      constructor() {
        this.readyState = 0;
        this.sent = [];
        RegisterCaptureWebSocket.instances.push(this);
        setTimeout(() => {
          this.readyState = RegisterCaptureWebSocket.OPEN;
          this.onopen?.();
          this.onmessage?.({ data: JSON.stringify({ type: 'register_ok' }) });
        }, 0);
      }

      send(data) {
        this.sent.push(JSON.parse(data));
      }

      close() {
        this.readyState = 3;
        this.onclose?.();
      }
    }

    globalThis.WebSocket = RegisterCaptureWebSocket;
    const originalNetworkInterfaces = os.networkInterfaces;
    os.networkInterfaces = () => ({
      lo0: [
        {
          address: '127.0.0.1',
          netmask: '255.0.0.0',
          family: 'IPv4',
          mac: '00:00:00:00:00:00',
          internal: true,
          cidr: null,
        },
      ],
    });
    try {
      const runtime = new BridgeRuntime({
        workspacePath: workspace,
        client: createRuntimeClient(),
      });

      await runtime.start();
      await new Promise((r) => setTimeout(r, 10));

      const ws = RegisterCaptureWebSocket.instances[0];
      assert.strictEqual(ws.sent[0].macAddress, '');

      runtime.stop();
    } finally {
      os.networkInterfaces = originalNetworkInterfaces;
      globalThis.WebSocket = originalWebSocket;
      await rm(workspace, { recursive: true, force: true });
      await rm(fakeHome, { recursive: true, force: true });
    }
  });

  test('runtime.start fails on missing sdk capability before connect', async () => {
    const workspace = await mkdtemp(join(tmpdir(), 'mb-runtime-start-missing-cap-'));
    const fakeHome = await mkdtemp(join(tmpdir(), 'mb-home-'));
    const originalHome = process.env.HOME;
    process.env.HOME = fakeHome;
    await writeEnabledConfig(workspace);

    const appLogs = [];
    const originalWebSocket = globalThis.WebSocket;
    const RegisterCaptureWebSocket = createRegisterCaptureWebSocket();
    globalThis.WebSocket = RegisterCaptureWebSocket;

    try {
      const runtime = new BridgeRuntime({
        workspacePath: workspace,
        client: createRuntimeClient({
          app: {
            log: async (options) => {
              appLogs.push(options.body);
              return true;
            },
          },
          session: {
            delete: undefined,
          },
        }),
      });

      await assert.rejects(runtime.start(), (err) => { assert.deepStrictEqual(err, {
        code: 'SDK_CLIENT_CAPABILITIES_MISSING',
        message: 'OpenCode client is missing required action capabilities',
        details: {
          missingCapabilities: ['session.delete'],
        },
      }); return true; });
      await new Promise((r) => setTimeout(r, 10));

      assert.strictEqual((RegisterCaptureWebSocket.instances).length, 0);
      const failureLog = appLogs.find((entry) => entry.message === 'runtime.start.failed_capabilities');
      assert.strictEqual(failureLog.extra.errorCode, 'SDK_CLIENT_CAPABILITIES_MISSING');
      assert.strictEqual(failureLog.extra.errorMessage, 'OpenCode client is missing required action capabilities');
      assert.deepStrictEqual(failureLog.extra.missingCapabilities, ['session.delete']);
    } finally {
      if (originalHome === undefined) {
        delete process.env.HOME;
      } else {
        process.env.HOME = originalHome;
      }
      globalThis.WebSocket = originalWebSocket;
      await rm(workspace, { recursive: true, force: true });
      await rm(fakeHome, { recursive: true, force: true });
    }
  });

  test('runtime.start falls back to raw /global/health when global.health is unavailable', async () => {
    const workspace = await mkdtemp(join(tmpdir(), 'mb-runtime-start-no-health-'));
    const fakeHome = await mkdtemp(join(tmpdir(), 'mb-home-'));
    const originalHome = process.env.HOME;
    process.env.HOME = fakeHome;
    await writeEnabledConfig(workspace);

    const appLogs = [];
    const originalWebSocket = globalThis.WebSocket;
    const RegisterCaptureWebSocket = createRegisterCaptureWebSocket();
    globalThis.WebSocket = RegisterCaptureWebSocket;

    try {
      const runtime = new BridgeRuntime({
        workspacePath: workspace,
        client: createRuntimeClient({
          app: {
            log: async (options) => {
              appLogs.push(options.body);
              return true;
            },
          },
          global: undefined,
        }),
      });

      await runtime.start();
      await new Promise((r) => setTimeout(r, 10));

      assert.strictEqual((RegisterCaptureWebSocket.instances).length, 1);
      const ws = RegisterCaptureWebSocket.instances[0];
      assert.strictEqual(ws.sent[0].toolVersion, '9.9.9');
      assert.strictEqual(appLogs.find((entry) => entry.message === 'runtime.start.failed_health'), undefined);
    } finally {
      if (originalHome === undefined) {
        delete process.env.HOME;
      } else {
        process.env.HOME = originalHome;
      }
      globalThis.WebSocket = originalWebSocket;
      await rm(workspace, { recursive: true, force: true });
      await rm(fakeHome, { recursive: true, force: true });
    }
  });

  test('runtime.start fails when raw /global/health returns without version before connect', async () => {
    const workspace = await mkdtemp(join(tmpdir(), 'mb-runtime-start-no-version-'));
    const fakeHome = await mkdtemp(join(tmpdir(), 'mb-home-'));
    const originalHome = process.env.HOME;
    process.env.HOME = fakeHome;
    await writeEnabledConfig(workspace);

    const appLogs = [];
    const originalWebSocket = globalThis.WebSocket;
    const RegisterCaptureWebSocket = createRegisterCaptureWebSocket();
    globalThis.WebSocket = RegisterCaptureWebSocket;

    try {
      const runtime = new BridgeRuntime({
        workspacePath: workspace,
        client: createRuntimeClient({
          app: {
            log: async (options) => {
              appLogs.push(options.body);
              return true;
            },
          },
          global: undefined,
          _client: {
            get: async (options) => {
              if (options?.url === '/global/health') {
                return { data: { healthy: true } };
              }
              return { data: [] };
            },
          },
        }),
      });

      await assert.rejects(runtime.start(), (err) => { assert.deepStrictEqual(err, {
        code: 'GLOBAL_HEALTH_VERSION_MISSING',
        message: 'OpenCode global.health returned without version',
        details: {
          responseShape: 'object:healthy',
        },
      }); return true; });
      await new Promise((r) => setTimeout(r, 10));

      assert.strictEqual((RegisterCaptureWebSocket.instances).length, 0);
      const failureLog = appLogs.find((entry) => entry.message === 'runtime.start.failed_health_version');
      assert.strictEqual(failureLog.extra.errorCode, 'GLOBAL_HEALTH_VERSION_MISSING');
      assert.strictEqual(failureLog.extra.errorMessage, 'OpenCode global.health returned without version');
      assert.strictEqual(failureLog.extra.responseShape, 'object:healthy');
    } finally {
      if (originalHome === undefined) {
        delete process.env.HOME;
      } else {
        process.env.HOME = originalHome;
      }
      globalThis.WebSocket = originalWebSocket;
      await rm(workspace, { recursive: true, force: true });
      await rm(fakeHome, { recursive: true, force: true });
    }
  });

  test('runtime.start fails when raw /global/health throws before register', async () => {
    const workspace = await mkdtemp(join(tmpdir(), 'mb-runtime-register-empty-version-'));
    const fakeHome = await mkdtemp(join(tmpdir(), 'mb-home-'));
    const originalHome = process.env.HOME;
    process.env.HOME = fakeHome;

    await writeEnabledConfig(workspace);

    const appLogs = [];
    const originalWebSocket = globalThis.WebSocket;
    const RegisterCaptureWebSocket = createRegisterCaptureWebSocket();

    globalThis.WebSocket = RegisterCaptureWebSocket;
    const originalNetworkInterfaces = os.networkInterfaces;
    os.networkInterfaces = () => ({
      en0: [
        {
          address: '127.0.0.1',
          netmask: '255.0.0.0',
          family: 'IPv4',
          mac: '11:22:33:44:55:66',
          internal: false,
          cidr: null,
        },
      ],
    });
    try {
      const runtime = new BridgeRuntime({
        workspacePath: workspace,
        client: createRuntimeClient({
          app: {
            log: async (options) => {
              appLogs.push(options.body);
              return true;
            },
          },
          global: undefined,
          _client: {
            get: async () => {
              throw new Error('global unavailable');
            },
          },
        }),
      });

      await assert.rejects(runtime.start(), (err) => { assert.deepStrictEqual(err, {
        code: 'GLOBAL_HEALTH_FAILED',
        message: 'OpenCode global.health check failed during startup',
        details: { cause: 'global unavailable' },
      }); return true; });
      await new Promise((r) => setTimeout(r, 10));

      assert.strictEqual((RegisterCaptureWebSocket.instances).length, 0);
      const failureLog = appLogs.find((entry) => entry.message === 'runtime.start.failed_health');
      assert.strictEqual(failureLog.extra.errorCode, 'GLOBAL_HEALTH_FAILED');
      assert.strictEqual(failureLog.extra.errorMessage, 'OpenCode global.health check failed during startup');
      assert.strictEqual(failureLog.extra.cause, 'global unavailable');
    } finally {
      if (originalHome === undefined) {
        delete process.env.HOME;
      } else {
        process.env.HOME = originalHome;
      }
      os.networkInterfaces = originalNetworkInterfaces;
      globalThis.WebSocket = originalWebSocket;
      await rm(workspace, { recursive: true, force: true });
      await rm(fakeHome, { recursive: true, force: true });
    }
  });

  test('uses gatewayMessageId as traceId across downstream invoke handling', async () => {
    const appLogs = [];
    const runtime = new BridgeRuntime({
      client: createRuntimeClient({
        app: {
          log: async (options) => {
            appLogs.push(options.body);
            return true;
          },
        },
        session: {
          prompt: async () => ({ data: { ok: true } }),
        },
      }),
    });

    runtime.gatewayConnection = { send: () => {} };
    runtime.stateManager.setState('READY');

    await runtime.handleDownstreamMessage({
      type: 'invoke',
      messageId: 'gw-msg-1',
      action: 'chat',
      welinkSessionId: 'skill-42',
      payload: {
        toolSessionId: 'tool-42',
        text: 'hello',
      },
    });
    await new Promise((r) => setTimeout(r, 10));

    const runtimeInvokeReceived = appLogs.find((entry) => entry.message === 'runtime.invoke.received');
    const routerReceived = appLogs.find((entry) => entry.message === 'router.route.received');
    const actionStarted = appLogs.find((entry) => entry.message === 'action.chat.started');
    const runtimeInvokeCompleted = appLogs.find((entry) => entry.message === 'runtime.invoke.completed');

    assert.strictEqual(runtimeInvokeReceived.extra.traceId, 'gw-msg-1');
    assert.strictEqual(routerReceived.extra.traceId, 'gw-msg-1');
    assert.strictEqual(actionStarted.extra.traceId, 'gw-msg-1');
    assert.strictEqual(runtimeInvokeCompleted.extra.traceId, 'gw-msg-1');

    assert.strictEqual(runtimeInvokeReceived.extra.gatewayMessageId, 'gw-msg-1');
    assert.strictEqual(runtimeInvokeReceived.extra.toolSessionId, 'tool-42');
    assert.strictEqual(runtimeInvokeCompleted.extra.runtimeTraceId, runtimeInvokeReceived.extra.runtimeTraceId);
  });
});
