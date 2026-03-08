import { describe, test, expect } from 'bun:test';
import { mkdtemp, mkdir, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

import { BridgeRuntime } from '../../dist/runtime/BridgeRuntime.js';
import { EnvelopeBuilder } from '../../dist/event/EnvelopeBuilder.js';

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
});
