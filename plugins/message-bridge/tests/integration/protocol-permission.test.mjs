import { describe, test } from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { join } from 'node:path';

import { EventFilter } from '../../src/event/EventFilter.ts';
import { BridgeRuntime } from '../../src/runtime/BridgeRuntime.ts';

const FIXTURE_DIR = join(process.cwd(), 'tests', 'fixtures', 'opencode-events');

function createRuntimeClient(overrides = {}) {
  const base = {
    global: {},
    session: {
      create: async () => ({}),
      abort: async () => ({}),
      delete: async () => ({}),
      prompt: async () => ({ data: { ok: true } }),
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

  return {
    ...base,
    ...overrides,
    session: {
      ...base.session,
      ...(overrides.session ?? {}),
    },
    _client: {
      ...base._client,
      ...(overrides._client ?? {}),
    },
  };
}

async function loadFixture(fileName) {
  const raw = await readFile(join(FIXTURE_DIR, fileName), 'utf8');
  return JSON.parse(raw);
}

describe('protocol permission-roundtrip', () => {
  test('forwards permission.asked as tool_event and routes permission_reply to SDK', async () => {
    const permissionCalls = [];
    const runtime = new BridgeRuntime({
      client: createRuntimeClient({
        postSessionIdPermissionsPermissionId: async (options) => {
          permissionCalls.push(options);
          return {};
        },
      }),
    });
    const sent = [];

    runtime.gatewayConnection = {
      send: (message) => sent.push(message),
    };
    runtime.eventFilter = new EventFilter(['permission.asked']);
    runtime.stateManager.setState('READY');

    const permissionAskedEvent = await loadFixture('permission.asked.json');
    await runtime.handleEvent(permissionAskedEvent);

    assert.deepStrictEqual(sent, [
      {
        type: 'tool_event',
        toolSessionId: 'ses_permission_1',
        event: permissionAskedEvent,
      },
    ]);

    await runtime.handleDownstreamMessage({
      type: 'invoke',
      welinkSessionId: 'wl-perm-1',
      action: 'permission_reply',
      payload: {
        toolSessionId: 'ses_permission_1',
        permissionId: 'perm_fixture_1',
        response: 'always',
      },
    });

    assert.deepStrictEqual(permissionCalls, [
      {
        path: { id: 'ses_permission_1', permissionID: 'perm_fixture_1' },
        body: { response: 'always' },
      },
    ]);
    assert.strictEqual(sent.length, 1);
  });

  test('returns tool_error when permission_reply uses an unsupported response enum', async () => {
    const runtime = new BridgeRuntime({
      client: createRuntimeClient(),
    });
    const sent = [];

    runtime.gatewayConnection = {
      send: (message) => sent.push(message),
    };
    runtime.stateManager.setState('READY');

    await runtime.handleDownstreamMessage({
      type: 'invoke',
      welinkSessionId: 'wl-perm-invalid',
      action: 'permission_reply',
      payload: {
        toolSessionId: 'ses_permission_1',
        permissionId: 'perm_fixture_1',
        response: 'allow',
      },
    });

    assert.deepStrictEqual(sent, [
      {
        type: 'tool_error',
        welinkSessionId: 'wl-perm-invalid',
        toolSessionId: 'ses_permission_1',
        error: 'Invalid invoke payload shape',
        reason: undefined,
      },
    ]);
  });
});
