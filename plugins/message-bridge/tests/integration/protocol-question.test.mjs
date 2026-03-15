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

describe('protocol question-roundtrip', () => {
  test('forwards question.asked as tool_event and routes question_reply through raw question API', async () => {
    const getCalls = [];
    const postCalls = [];
    const runtime = new BridgeRuntime({
      client: createRuntimeClient({
        _client: {
          get: async (options) => {
            getCalls.push(options);
            if (options?.url === '/global/health') {
              return { data: { healthy: true, version: '9.9.9' } };
            }
            return {
              data: [
                {
                  id: 'question-request-1',
                  sessionID: 'ses_question_1',
                  tool: { callID: 'call_question_1' },
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

    runtime.gatewayConnection = {
      send: (message) => sent.push(message),
    };
    runtime.eventFilter = new EventFilter(['question.asked']);
    runtime.stateManager.setState('READY');

    const questionAskedEvent = await loadFixture('question.asked.json');
    await runtime.handleEvent(questionAskedEvent);

    assert.deepStrictEqual(sent, [
      {
        type: 'tool_event',
        toolSessionId: 'ses_question_1',
        event: questionAskedEvent,
      },
    ]);

    await runtime.handleDownstreamMessage({
      type: 'invoke',
      welinkSessionId: 'wl-question-1',
      action: 'question_reply',
      payload: {
        toolSessionId: 'ses_question_1',
        toolCallId: 'call_question_1',
        answer: 'Vite',
      },
    });

    assert.deepStrictEqual(getCalls, [{ url: '/question' }]);
    assert.deepStrictEqual(postCalls, [
      {
        url: '/question/{requestID}/reply',
        path: { requestID: 'question-request-1' },
        body: { answers: [['Vite']] },
        headers: { 'Content-Type': 'application/json' },
      },
    ]);
    assert.strictEqual(sent.length, 1);
  });

  test('returns tool_error when question_reply cannot resolve a unique pending request', async () => {
    const runtime = new BridgeRuntime({
      client: createRuntimeClient({
        _client: {
          get: async (options) => {
            if (options?.url === '/global/health') {
              return { data: { healthy: true, version: '9.9.9' } };
            }
            return {
              data: [
                { id: 'question-request-a', sessionID: 'ses_question_1' },
                { id: 'question-request-b', sessionID: 'ses_question_1' },
              ],
            };
          },
          post: async () => ({ data: undefined }),
        },
      }),
    });
    const sent = [];

    runtime.gatewayConnection = {
      send: (message) => sent.push(message),
    };
    runtime.stateManager.setState('READY');

    await runtime.handleDownstreamMessage({
      type: 'invoke',
      welinkSessionId: 'wl-question-ambiguous',
      action: 'question_reply',
      payload: {
        toolSessionId: 'ses_question_1',
        answer: 'Vite',
      },
    });

    assert.deepStrictEqual(sent, [
      {
        type: 'tool_error',
        welinkSessionId: 'wl-question-ambiguous',
        toolSessionId: 'ses_question_1',
        error: 'Unable to resolve a unique pending question request for toolSessionId=ses_question_1',
        reason: undefined,
      },
    ]);
  });

  test('returns tool_error when question_reply cannot match any pending request', async () => {
    const runtime = new BridgeRuntime({
      client: createRuntimeClient({
        _client: {
          get: async (options) => {
            if (options?.url === '/global/health') {
              return { data: { healthy: true, version: '9.9.9' } };
            }
            return {
              data: [
                {
                  id: 'question-request-other',
                  sessionID: 'ses_other',
                  tool: { callID: 'call_other' },
                },
              ],
            };
          },
          post: async () => ({ data: undefined }),
        },
      }),
    });
    const sent = [];

    runtime.gatewayConnection = {
      send: (message) => sent.push(message),
    };
    runtime.stateManager.setState('READY');

    await runtime.handleDownstreamMessage({
      type: 'invoke',
      welinkSessionId: 'wl-question-miss',
      action: 'question_reply',
      payload: {
        toolSessionId: 'ses_question_1',
        toolCallId: 'call_question_1',
        answer: 'Vite',
      },
    });

    assert.deepStrictEqual(sent, [
      {
        type: 'tool_error',
        welinkSessionId: 'wl-question-miss',
        toolSessionId: 'ses_question_1',
        error: 'Unable to resolve pending question request for toolSessionId=ses_question_1, toolCallId=call_question_1',
        reason: undefined,
      },
    ]);
  });
});
