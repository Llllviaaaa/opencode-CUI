import { describe, test, expect } from 'bun:test';
import { mkdtemp, mkdir, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

import { BridgeRuntime } from '../../dist/runtime/BridgeRuntime.js';
import { EnvelopeBuilder } from '../../dist/event/EnvelopeBuilder.js';
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
    runtime.envelopeBuilder = new EnvelopeBuilder('agent-1');
    runtime.stateManager.setState('READY');

    await runtime.handleDownstreamMessage({
      type: 'invoke',
      sessionId: '42',
      payload: {
        action: 'chat',
        payload: { toolSessionId: 'tool-1', text: 'hello' },
      },
    });

    expect(sent).toHaveLength(1);
    expect(sent[0].type).toBe('tool_error');
    expect(sent[0].sessionId).toBe('42');
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
    runtime.envelopeBuilder = new EnvelopeBuilder('agent-1');
    runtime.stateManager.setState('READY');

    await runtime.handleDownstreamMessage({
      type: 'invoke',
      sessionId: '100',
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

  test('rejects permission_reply payloads with legacy response values', async () => {
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
    runtime.envelopeBuilder = new EnvelopeBuilder('agent-1');
    runtime.stateManager.setState('READY');

    await runtime.handleDownstreamMessage({
      type: 'invoke',
      sessionId: 'perm-42',
      action: 'permission_reply',
      payload: { toolSessionId: 'tool-42', permissionId: 'perm-1', response: 'allow' },
    });

    expect(sent).toHaveLength(1);
    expect(sent[0].type).toBe('tool_error');
    expect(sent[0].sessionId).toBe('perm-42');
  });

  test('accepts question_reply invoke shape and routes answer via session.prompt', async () => {
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
    runtime.envelopeBuilder = new EnvelopeBuilder('agent-1');
    runtime.stateManager.setState('READY');

    await runtime.handleDownstreamMessage({
      type: 'invoke',
      sessionId: 'q-42',
      action: 'question_reply',
      payload: { toolSessionId: 'tool-42', toolCallId: 'call-42', answer: 'Vite' },
    });

    expect(prompts).toHaveLength(1);
    expect(prompts[0]).toEqual({
      path: { id: 'tool-42' },
      body: { parts: [{ type: 'text', text: 'Vite' }] },
    });
    expect(sent).toHaveLength(0);
  });

  test('rejects question_reply payloads missing toolCallId', async () => {
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
    runtime.envelopeBuilder = new EnvelopeBuilder('agent-1');
    runtime.stateManager.setState('READY');

    await runtime.handleDownstreamMessage({
      type: 'invoke',
      sessionId: 'q-43',
      action: 'question_reply',
      payload: { toolSessionId: 'tool-43', answer: 'Vite' },
    });

    expect(sent).toHaveLength(1);
    expect(sent[0].type).toBe('tool_error');
    expect(sent[0].sessionId).toBe('q-43');
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
    runtime.envelopeBuilder = new EnvelopeBuilder('agent-1');
    runtime.stateManager.setState('READY');

    await runtime.handleDownstreamMessage({
      type: 'invoke',
      sessionId: 'q-44',
      action: 'question_reply',
      payload: { toolCallId: 'call-44', answer: 'Vite' },
    });

    expect(sent).toHaveLength(1);
    expect(sent[0].type).toBe('tool_error');
    expect(sent[0].sessionId).toBe('q-44');
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
    runtime.envelopeBuilder = new EnvelopeBuilder('agent-1');
    runtime.stateManager.setState('READY');

    await runtime.handleDownstreamMessage({
      type: 'invoke',
      sessionId: 'q-45',
      action: 'question_reply',
      payload: { toolSessionId: 'tool-45', toolCallId: 'call-45' },
    });

    expect(sent).toHaveLength(1);
    expect(sent[0].type).toBe('tool_error');
    expect(sent[0].sessionId).toBe('q-45');
  });

  test('rejects invoke status_query variant', async () => {
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
    runtime.envelopeBuilder = new EnvelopeBuilder('agent-1');
    runtime.stateManager.setState('READY');

    await runtime.handleDownstreamMessage({
      type: 'invoke',
      sessionId: 'status-42',
      action: 'status_query',
      payload: { sessionId: 'status-42' },
    });

    expect(sent).toHaveLength(1);
    expect(sent[0].type).toBe('tool_error');
    expect(sent[0].sessionId).toBe('status-42');
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
          allowlist: ['message.*'],
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
    runtime.envelopeBuilder = new EnvelopeBuilder('agent-1');
    runtime.eventFilter = new EventFilter(['message.*']);
    runtime.stateManager.setState('READY');
    runtime.toolToSkillSessionMap.set('tool-1', 'skill-1');

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

    expect(forwardingLog.extra.sessionId).toBe('skill-1');
    expect(forwardingLog.extra.traceId).toBeDefined();
    expect('bridgeMessageId' in forwardingLog.extra).toBe(false);
    expect(forwardingLog.extra.opencodeMessageId).toBe('op-msg-1');
    expect(forwardingLog.extra.opencodePartId).toBe('part-1');
    expect(forwardedLog.extra.traceId).toBe(forwardingLog.extra.traceId);
    expect('bridgeMessageId' in forwardedLog.extra).toBe(false);

    expect(sent).toHaveLength(1);
    expect(sent[0].context.traceId).toBe(forwardingLog.extra.traceId);
    expect(sent[0].context.bridgeMessageId).toBe(forwardingLog.extra.traceId);
    expect(sent[0].context.opencodeMessageId).toBe('op-msg-1');
    expect(sent[0].context.opencodePartId).toBe('part-1');
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
    runtime.envelopeBuilder = new EnvelopeBuilder('agent-1');
    runtime.stateManager.setState('READY');

    await runtime.handleDownstreamMessage({
      type: 'invoke',
      action: 'chat',
      sessionId: 'skill-42',
      payload: {
        toolSessionId: 'tool-42',
        text: 'hello',
      },
      envelope: {
        messageId: 'gw-msg-1',
        sessionId: 'skill-42',
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
