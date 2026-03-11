import { describe, expect, test } from 'bun:test';

import { normalizeDownstreamMessage } from '../../src/protocol/downstream/DownstreamMessageNormalizer.ts';

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
      },
      logger,
    );

    expect(result.ok).toBe(true);
    expect(result.value).toEqual({
      type: 'status_query',
    });
  });

  test('normalizes invoke/chat payload', () => {
    const { logger } = createLogger();
    const result = normalizeDownstreamMessage(
      {
        type: 'invoke',
        welinkSessionId: 'skill-1',
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
      welinkSessionId: 'skill-1',
      payload: {
        toolSessionId: 'tool-1',
        text: 'hello',
      },
    });
  });

  test('normalizes invoke/permission_reply payload', () => {
    const { logger } = createLogger();
    const result = normalizeDownstreamMessage(
      {
        type: 'invoke',
        welinkSessionId: 'skill-2',
        action: 'permission_reply',
        payload: {
          permissionId: 'perm-1',
          toolSessionId: 'tool-2',
          response: 'once',
        },
      },
      logger,
    );

    expect(result.ok).toBe(true);
    expect(result.value).toEqual({
      type: 'invoke',
      action: 'permission_reply',
      welinkSessionId: 'skill-2',
      payload: {
        permissionId: 'perm-1',
        toolSessionId: 'tool-2',
        response: 'once',
      },
    });
  });

  test('rejects nested invoke payload shape and logs normalization failure', () => {
    const { logger, entries } = createLogger();
    const result = normalizeDownstreamMessage(
      {
        type: 'invoke',
        welinkSessionId: '42',
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
    expect(result.error.welinkSessionId).toBe('42');
    expect(entries).toHaveLength(1);
    expect(entries[0].message).toBe('downstream.normalization_failed');
  });

  test('rejects unsupported invoke action and logs normalization failure', () => {
    const { logger, entries } = createLogger();
    const result = normalizeDownstreamMessage(
      {
        type: 'invoke',
        welinkSessionId: '77',
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
        welinkSessionId: 'skill-3',
        action: 'permission_reply',
        payload: {
          permissionId: 'perm-3',
          toolSessionId: 'tool-3',
          response: 'allow',
        },
      },
      logger,
    );

    expect(result.ok).toBe(false);
    expect(result.error.code).toBe('invalid_field_type');
    expect(result.error.field).toBe('payload.response');
  });

  test('rejects invoke/status_query compatibility shape', () => {
    const { logger } = createLogger();
    const result = normalizeDownstreamMessage(
      {
        type: 'invoke',
        welinkSessionId: 'status-1',
        action: 'status_query',
        payload: {},
      },
      logger,
    );

    expect(result.ok).toBe(false);
    expect(result.error.code).toBe('unsupported_action');
    expect(result.error.action).toBe('status_query');
  });

  test('rejects create_session without welinkSessionId', () => {
    const { logger } = createLogger();
    const result = normalizeDownstreamMessage(
      {
        type: 'invoke',
        action: 'create_session',
        payload: {},
      },
      logger,
    );

    expect(result.ok).toBe(false);
    expect(result.error.code).toBe('missing_required_field');
    expect(result.error.field).toBe('welinkSessionId');
    expect(result.error.message).toBe('create_session missing welinkSessionId');
    expect(result.error.welinkSessionId).toBeUndefined();
  });

  test('rejects create_session with blank welinkSessionId', () => {
    const { logger } = createLogger();
    const result = normalizeDownstreamMessage(
      {
        type: 'invoke',
        welinkSessionId: '   ',
        action: 'create_session',
        payload: {},
      },
      logger,
    );

    expect(result.ok).toBe(false);
    expect(result.error.code).toBe('missing_required_field');
    expect(result.error.field).toBe('welinkSessionId');
    expect(result.error.message).toBe('create_session missing welinkSessionId');
    expect(result.error.welinkSessionId).toBeUndefined();
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
      welinkSessionId: undefined,
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
      welinkSessionId: undefined,
    });
  });
});
