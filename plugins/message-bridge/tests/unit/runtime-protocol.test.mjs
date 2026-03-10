import { describe, test, expect } from 'bun:test';
import { mkdtemp, mkdir, rm, writeFile } from 'node:fs/promises';
import os, { hostname, tmpdir } from 'node:os';
import { join } from 'node:path';

import { BridgeRuntime } from '../../src/runtime/BridgeRuntime.ts';
import { EventFilter } from '../../src/event/EventFilter.ts';

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

  test('accepts baseline invoke shape and emits tool_done on chat success', async () => {
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
    expect(sent).toHaveLength(1);
    expect(sent[0].type).toBe('tool_done');
    expect(sent[0].toolSessionId).toBe('tool-100');
    expect(sent[0].welinkSessionId).toBe('100');
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
      payload: { toolSessionId: 'tool-42', permissionId: 'perm-1', response: 'allow' },
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

  test('does not emit tool_done on permission_reply success', async () => {
    const permissionCalls = [];
    const runtime = new BridgeRuntime({
      client: {
        session: {
          create: async () => ({}),
          abort: async () => ({}),
          prompt: async () => ({ data: { ok: true } }),
        },
        postSessionIdPermissionsPermissionId: async (options) => {
          permissionCalls.push(options);
          return {};
        },
      },
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

    expect(permissionCalls).toEqual([
      {
        path: { id: 'tool-perm-1', permissionID: 'perm-a' },
        body: { response: 'once' },
      },
    ]);
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

  test('responds to standalone status_query with envelope-free status_response', async () => {
    const runtime = new BridgeRuntime({
      client: {
        app: {
          health: async () => ({ ok: true }),
        },
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
      type: 'status_query',
    });

    expect(sent).toHaveLength(1);
    expect(sent[0].type).toBe('status_response');
    expect(sent[0]).toEqual({
      type: 'status_response',
      opencodeOnline: true,
    });
  });

  test('rejects invoke status_query compatibility variant', async () => {
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
      welinkSessionId: 'status-compat',
      action: 'status_query',
      payload: {},
    });

    expect(sent).toHaveLength(1);
    expect(sent[0].type).toBe('tool_error');
    expect(sent[0].welinkSessionId).toBe('status-compat');
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

    expect(sent).toHaveLength(2);
    expect(sent[0].message.type).toBe('tool_event');
    expect(sent[0].message.toolSessionId).toBe('tool-idle-1');
    expect(sent[1].message.type).toBe('tool_done');
    expect(sent[1].message.toolSessionId).toBe('tool-idle-1');
  });

  test('does not emit duplicate tool_done when session.idle follows chat success', async () => {
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

    expect(sent.filter((entry) => entry.message.type === 'tool_done')).toHaveLength(1);
    expect(sent.filter((entry) => entry.message.type === 'tool_event')).toHaveLength(1);
  });

  test('defers session.idle tool_done while chat prompt is still pending', async () => {
    let resolvePrompt;
    const promptPromise = new Promise((resolve) => {
      resolvePrompt = resolve;
    });
    const runtime = new BridgeRuntime({
      client: {
        session: {
          create: async () => ({}),
          abort: async () => ({}),
          prompt: async () => {
            await promptPromise;
            return { data: { ok: true } };
          },
        },
        postSessionIdPermissionsPermissionId: async () => ({}),
      },
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

    expect(sent.filter((entry) => entry.message.type === 'tool_done')).toHaveLength(0);
    expect(sent.filter((entry) => entry.message.type === 'tool_event')).toHaveLength(1);

    resolvePrompt({ data: { ok: true } });
    await invokeTask;

    expect(sent.filter((entry) => entry.message.type === 'tool_done')).toHaveLength(1);
  });

  test('does not emit tool_done for close_session success', async () => {
    const deleteCalls = [];
    const runtime = new BridgeRuntime({
      client: {
        session: {
          create: async () => ({}),
          abort: async () => ({}),
          delete: async (options) => {
            deleteCalls.push(options);
            return {};
          },
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
      welinkSessionId: 'close-1',
      action: 'close_session',
      payload: { toolSessionId: 'tool-close-1' },
    });

    expect(deleteCalls).toEqual([{ path: { id: 'tool-close-1' } }]);
    expect(sent).toHaveLength(0);
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
          toolType: 'opencode',
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
        client: {
          global: {
            health: async () => ({ healthy: true, version: '9.9.9' }),
          },
        },
      });

      await runtime.start();
      await new Promise((r) => setTimeout(r, 10));

      const ws = RegisterCaptureWebSocket.instances[0];
      expect(ws.sent[0]).toEqual({
        type: 'register',
        deviceName: hostname(),
        macAddress: '11:22:33:44:55:66',
        os: expect.any(String),
        toolType: 'OPENCODE',
        toolVersion: '9.9.9',
      });

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
          toolType: 'opencode',
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
        client: {
          global: {
            health: async () => ({ healthy: true, version: '9.9.9' }),
          },
        },
      });

      await runtime.start();
      await new Promise((r) => setTimeout(r, 10));

      const ws = RegisterCaptureWebSocket.instances[0];
      expect(ws.sent[0].macAddress).toBe('');

      runtime.stop();
    } finally {
      os.networkInterfaces = originalNetworkInterfaces;
      globalThis.WebSocket = originalWebSocket;
      await rm(workspace, { recursive: true, force: true });
      await rm(fakeHome, { recursive: true, force: true });
    }
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
