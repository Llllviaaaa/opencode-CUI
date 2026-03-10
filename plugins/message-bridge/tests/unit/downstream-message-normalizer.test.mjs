import { describe, expect, test } from 'bun:test';

import { normalizeDownstreamMessage } from '../../dist/protocol/downstream/DownstreamMessageNormalizer.js';

function createLogger() {
  const entries = [];
  return {
    entries,
    logger: {
      debug: () => {},
      info: () => {},
      warn: (message, extra) => entries.push({ level: 'warn', message, extra }),
      error: () => {},
      child() {
        return this;
      },
      getTraceId() {
        return 'trace-test';
      },
    },
  };
}

describe('downstream message normalizer', () => {
  test('normalizes status_query message', () => {
    const { logger } = createLogger();
    const result = normalizeDownstreamMessage(
      {
        type: 'status_query',
        sessionId: 'session-1',
      },
      logger,
    );

    expect(result.ok).toBe(true);
    expect(result.value).toEqual({
      type: 'status_query',
      sessionId: 'session-1',
      envelope: undefined,
    });
  });

  test('normalizes invoke/chat payload', () => {
    const { logger } = createLogger();
    const result = normalizeDownstreamMessage(
      {
        type: 'invoke',
        sessionId: 'skill-1',
        action: 'chat',
        payload: {
          toolSessionId: 'tool-1',
          text: 'hello',
        },
      },
      logger,
    );

    expect(result.ok).toBe(true);
    expect(result.value).toEqual({
      type: 'invoke',
      action: 'chat',
      sessionId: 'skill-1',
      envelope: undefined,
      payload: {
        toolSessionId: 'tool-1',
        text: 'hello',
      },
    });
  });

  test('normalizes enveloped invoke/permission_reply payload', () => {
    const { logger } = createLogger();
    const envelope = {
      version: '1.0',
      messageId: 'm-1',
      timestamp: Date.now(),
      source: 'message-bridge',
      agentId: 'agent-1',
      sessionId: 'skill-2',
      sequenceNumber: 1,
      sequenceScope: 'session',
    };
    const result = normalizeDownstreamMessage(
      {
        type: 'invoke',
        envelope,
        payload: {
          sessionId: 'skill-2',
          action: 'permission_reply',
          payload: {
            permissionId: 'perm-1',
            toolSessionId: 'tool-2',
            response: 'allow',
          },
        },
      },
      logger,
    );

    expect(result.ok).toBe(true);
    expect(result.value).toEqual({
      type: 'invoke',
      action: 'permission_reply',
      sessionId: 'skill-2',
      envelope,
      payload: {
        permissionId: 'perm-1',
        toolSessionId: 'tool-2',
        response: 'allow',
      },
    });
  });

  test('rejects nested invoke payload shape and logs normalization failure', () => {
    const { logger, entries } = createLogger();
    const result = normalizeDownstreamMessage(
      {
        type: 'invoke',
        sessionId: '42',
        payload: {
          action: 'chat',
          payload: { toolSessionId: 'tool-1', text: 'hello' },
        },
      },
      logger,
    );

    expect(result.ok).toBe(false);
    expect(result.error.code).toBe('missing_required_field');
    expect(result.error.messageType).toBe('invoke');
    expect(result.error.sessionId).toBe('42');
    expect(entries).toHaveLength(1);
    expect(entries[0].message).toBe('downstream.normalization_failed');
  });

  test('rejects unsupported invoke action and logs normalization failure', () => {
    const { logger, entries } = createLogger();
    const result = normalizeDownstreamMessage(
      {
        type: 'invoke',
        sessionId: '77',
        action: 'delete_session',
        payload: {},
      },
      logger,
    );

    expect(result.ok).toBe(false);
    expect(result.error.code).toBe('unsupported_action');
    expect(result.error.action).toBe('delete_session');
    expect(entries).toHaveLength(1);
    expect(entries[0].message).toBe('downstream.normalization_failed');
  });

  test('rejects invalid permission reply response', () => {
    const { logger } = createLogger();
    const result = normalizeDownstreamMessage(
      {
        type: 'invoke',
        sessionId: 'skill-3',
        action: 'permission_reply',
        payload: {
          permissionId: 'perm-3',
          toolSessionId: 'tool-3',
          response: 'maybe',
        },
      },
      logger,
    );

    expect(result.ok).toBe(false);
    expect(result.error.code).toBe('invalid_field_type');
    expect(result.error.field).toBe('payload.response');
  });

  test('normalizes invoke/abort_session payload', () => {
    const { logger } = createLogger();
    const result = normalizeDownstreamMessage(
      {
        type: 'invoke',
        action: 'abort_session',
        payload: {
          toolSessionId: 'tool-42',
        },
      },
      logger,
    );

    expect(result.ok).toBe(true);
    expect(result.value).toEqual({
      type: 'invoke',
      action: 'abort_session',
      payload: {
        toolSessionId: 'tool-42',
      },
      sessionId: undefined,
      envelope: undefined,
    });
  });

  test('normalizes invoke/question_reply payload', () => {
    const { logger } = createLogger();
    const result = normalizeDownstreamMessage(
      {
        type: 'invoke',
        action: 'question_reply',
        payload: {
          toolSessionId: 'tool-42',
          toolCallId: 'call-7',
          answer: 'approved',
        },
      },
      logger,
    );

    expect(result.ok).toBe(true);
    expect(result.value).toEqual({
      type: 'invoke',
      action: 'question_reply',
      payload: {
        toolSessionId: 'tool-42',
        toolCallId: 'call-7',
        answer: 'approved',
      },
      sessionId: undefined,
      envelope: undefined,
    });
  });
});
