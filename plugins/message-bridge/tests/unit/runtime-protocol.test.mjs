import { describe, test, expect } from 'bun:test';
import { mkdtemp, mkdir, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

import { BridgeRuntime } from '../../dist/runtime/BridgeRuntime.js';
import { EventFilter } from '../../dist/event/EventFilter.js';

describe('runtime protocol strictness', () => {
  test('rejects non-baseline nested invoke payload and returns tool_error without code', async () => {
    const runtime = new BridgeRuntime({
      client: {
        session: {
          create: async () => ({}),
          abort: async () => ({}),
          prompt: async () => ({}),
        },
        postSessionIdPermissionsPermissionId: async () => ({}),
      },
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

    expect(sent).toHaveLength(1);
    expect(sent[0].type).toBe('tool_error');
    expect(sent[0].welinkSessionId).toBe('42');
    expect('code' in sent[0]).toBe(false);
  });

  test('accepts baseline invoke shape and does not emit tool_done on success', async () => {
    const prompts = [];
    const runtime = new BridgeRuntime({
      client: {
        session: {
          create: async () => ({}),
          abort: async () => ({}),
          prompt: async (options) => {
            prompts.push(options);
            return { data: { ok: true } };
          },
        },
        postSessionIdPermissionsPermissionId: async () => ({}),
      },
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

    expect(prompts).toHaveLength(1);
    expect(prompts[0]).toEqual({
      path: { id: 'tool-100' },
      body: { parts: [{ type: 'text', text: 'hello' }] },
    });
    expect(sent).toHaveLength(0);
  });

  test('rejects permission_reply payloads with unsupported response values', async () => {
    const runtime = new BridgeRuntime({
      client: {
        session: {
          create: async () => ({}),
          abort: async () => ({}),
          prompt: async () => ({}),
        },
        postSessionIdPermissionsPermissionId: async () => ({}),
      },
    });

    const sent = [];
    runtime.gatewayConnection = { send: (msg) => sent.push(msg) };
    runtime.stateManager.setState('READY');

    await runtime.handleDownstreamMessage({
      type: 'invoke',
      welinkSessionId: 'perm-42',
      action: 'permission_reply',
      payload: { toolSessionId: 'tool-42', permissionId: 'perm-1', response: 'once' },
    });

    expect(sent).toHaveLength(1);
    expect(sent[0].type).toBe('tool_error');
    expect(sent[0].welinkSessionId).toBe('perm-42');
  });

  test('accepts question_reply invoke shape and routes answer via question API', async () => {
    const getCalls = [];
    const postCalls = [];
    const runtime = new BridgeRuntime({
      client: {
        session: {
          create: async () => ({}),
          abort: async () => ({}),
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
        postSessionIdPermissionsPermissionId: async () => ({}),
      },
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

    expect(getCalls).toEqual([{ url: '/question' }]);
    expect(postCalls).toEqual([
      {
        url: '/question/{requestID}/reply',
        path: { requestID: 'question-request-42' },
        body: { answers: [['Vite']] },
        headers: { 'Content-Type': 'application/json' },
      },
    ]);
    expect(sent).toHaveLength(0);
  });

  test('accepts question_reply payloads missing toolCallId', async () => {
    const postCalls = [];
    const runtime = new BridgeRuntime({
      client: {
        session: {
          create: async () => ({}),
          abort: async () => ({}),
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
        postSessionIdPermissionsPermissionId: async () => ({}),
      },
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

    expect(postCalls).toHaveLength(1);
    expect(sent).toHaveLength(0);
  });

  test('rejects question_reply when toolCallId is omitted and pending request is not unique', async () => {
    const runtime = new BridgeRuntime({
      client: {
        session: {
          create: async () => ({}),
          abort: async () => ({}),
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
        postSessionIdPermissionsPermissionId: async () => ({}),
      },
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

    expect(sent).toHaveLength(1);
    expect(sent[0].type).toBe('tool_error');
    expect(sent[0].error).toContain('unique pending question');
  });

  test('rejects question_reply when no pending request matches', async () => {
    const runtime = new BridgeRuntime({
      client: {
        session: {
          create: async () => ({}),
          abort: async () => ({}),
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
        postSessionIdPermissionsPermissionId: async () => ({}),
      },
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

    expect(sent).toHaveLength(1);
    expect(sent[0].type).toBe('tool_error');
    expect(sent[0].welinkSessionId).toBe('q-46');
  });

  test('rejects question_reply when raw client is unavailable', async () => {
    const runtime = new BridgeRuntime({
      client: {
        session: {
          create: async () => ({}),
          abort: async () => ({}),
          prompt: async () => ({ data: { ok: true } }),
        },
        postSessionIdPermissionsPermissionId: async () => ({}),
      },
    });

    const sent = [];
    runtime.gatewayConnection = { send: (msg) => sent.push(msg) };
    runtime.stateManager.setState('READY');

    await runtime.handleDownstreamMessage({
      type: 'invoke',
      welinkSessionId: 'q-47',
      action: 'question_reply',
      payload: { toolSessionId: 'tool-47', toolCallId: 'call-47', answer: 'Vite' },
    });

    expect(sent).toHaveLength(1);
    expect(sent[0].type).toBe('tool_error');
    expect(sent[0].error).toContain('raw client GET unavailable');
  });

  test('rejects question_reply payloads missing toolSessionId', async () => {
    const runtime = new BridgeRuntime({
      client: {
        session: {
          create: async () => ({}),
          abort: async () => ({}),
          prompt: async () => ({ data: { ok: true } }),
        },
        postSessionIdPermissionsPermissionId: async () => ({}),
      },
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

    expect(sent).toHaveLength(1);
    expect(sent[0].type).toBe('tool_error');
    expect(sent[0].welinkSessionId).toBe('q-44');
  });

  test('rejects question_reply payloads missing answer', async () => {
    const runtime = new BridgeRuntime({
      client: {
        session: {
          create: async () => ({}),
          abort: async () => ({}),
          prompt: async () => ({ data: { ok: true } }),
        },
        postSessionIdPermissionsPermissionId: async () => ({}),
      },
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

    expect(sent).toHaveLength(1);
    expect(sent[0].type).toBe('tool_error');
    expect(sent[0].welinkSessionId).toBe('q-45');
  });

  test('accepts invoke status_query variant and responds with status_response', async () => {
    const runtime = new BridgeRuntime({
      client: {
        session: {
          create: async () => ({}),
          abort: async () => ({}),
          prompt: async () => ({}),
        },
        postSessionIdPermissionsPermissionId: async () => ({}),
      },
    });

    const sent = [];
    runtime.gatewayConnection = { send: (msg) => sent.push(msg) };
    runtime.stateManager.setState('READY');

    await runtime.handleDownstreamMessage({
      type: 'invoke',
      welinkSessionId: 'status-42',
      action: 'status_query',
      payload: { sessionId: 'status-42' },
    });

    expect(sent).toHaveLength(1);
    expect(sent[0].type).toBe('status_response');
    expect(sent[0].welinkSessionId).toBe('status-42');
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

      expect(runtime.getStarted()).toBe(true);
      expect(debugCalls.some((args) => args.includes('runtime.start.disabled_by_config'))).toBe(true);
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

    expect(receivedLog.extra.traceId).toBe('op-msg-1');
    expect(receivedLog.extra.runtimeTraceId).toBeDefined();
    expect(receivedLog.extra.opencodeMessageId).toBe('op-msg-1');
    expect(receivedLog.extra.opencodePartId).toBe('part-1');
    expect(receivedLog.extra.toolSessionId).toBe('tool-1');
    expect(receivedLog.extra.partType).toBe('text');
    expect(receivedLog.extra.deltaBytes).toBe(Buffer.byteLength('你好，bridge', 'utf8'));

    expect(forwardingLog.extra.toolSessionId).toBe('tool-1');
    expect(forwardingLog.extra.traceId).toBeDefined();
    expect('bridgeMessageId' in forwardingLog.extra).toBe(false);
    expect(forwardingLog.extra.opencodeMessageId).toBe('op-msg-1');
    expect(forwardingLog.extra.opencodePartId).toBe('part-1');
    expect(forwardedLog.extra.traceId).toBe(forwardingLog.extra.traceId);
    expect('bridgeMessageId' in forwardedLog.extra).toBe(false);

    expect(sent).toHaveLength(1);
    expect(sent[0].context.traceId).toBe(forwardingLog.extra.traceId);
    expect(sent[0].context.gatewayMessageId).toBe(forwardingLog.extra.traceId);
    expect(sent[0].context.opencodeMessageId).toBe('op-msg-1');
    expect(sent[0].context.opencodePartId).toBe('part-1');
  });

  test('forwards session.idle as tool_event and never emits tool_done', async () => {
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

    expect(sent).toHaveLength(1);
    expect(sent[0].message.type).toBe('tool_event');
    expect(sent[0].message.toolSessionId).toBe('tool-idle-1');
    expect(sent.some((entry) => entry.message.type === 'tool_done')).toBe(false);
  });

  test('uses gatewayMessageId as traceId across downstream invoke handling', async () => {
    const appLogs = [];
    const runtime = new BridgeRuntime({
      client: {
        app: {
          log: async (options) => {
            appLogs.push(options.body);
            return true;
          },
        },
        session: {
          create: async () => ({}),
          abort: async () => ({}),
          prompt: async () => ({ data: { ok: true } }),
        },
        postSessionIdPermissionsPermissionId: async () => ({}),
      },
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

    expect(runtimeInvokeReceived.extra.traceId).toBe('gw-msg-1');
    expect(routerReceived.extra.traceId).toBe('gw-msg-1');
    expect(actionStarted.extra.traceId).toBe('gw-msg-1');
    expect(runtimeInvokeCompleted.extra.traceId).toBe('gw-msg-1');

    expect(runtimeInvokeReceived.extra.gatewayMessageId).toBe('gw-msg-1');
    expect(runtimeInvokeReceived.extra.toolSessionId).toBe('tool-42');
    expect(runtimeInvokeCompleted.extra.runtimeTraceId).toBe(runtimeInvokeReceived.extra.runtimeTraceId);
  });
});
