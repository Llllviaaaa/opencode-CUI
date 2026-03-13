import { describe, expect, test } from 'bun:test';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';

import {
  extractUpstreamEvent,
  UPSTREAM_EVENT_EXTRACTORS,
} from '../../src/event/UpstreamEventExtractor.ts';
import { SUPPORTED_UPSTREAM_EVENT_TYPES } from '../../src/event/SupportedUpstreamEvents.ts';

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

function loadFixture(name) {
  const path = join(import.meta.dir, '..', 'fixtures', 'opencode-events', name);
  return JSON.parse(readFileSync(path, 'utf8'));
}

describe('upstream event extractor', () => {
  test('registry covers every supported upstream event type', () => {
    expect(Object.keys(UPSTREAM_EVENT_EXTRACTORS).sort()).toEqual([...SUPPORTED_UPSTREAM_EVENT_TYPES].sort());
  });

  test('extracts message.updated common and extra fields from properties.info', () => {
    const { logger } = createLogger();
    const result = extractUpstreamEvent(loadFixture('message.updated.user.json'), logger);

    expect(result.ok).toBe(true);
    expect(result.value.common).toEqual({
      eventType: 'message.updated',
      toolSessionId: 'ses_32c9fea15ffe2Rnv8tITmfmGmQ',
    });
    expect(result.value.extra).toEqual({
      kind: 'message.updated',
      messageId: 'msg_cd3603508001ioF1UNVajFbFmX',
      role: 'user',
    });
  });

  test('extracts message.part.updated fields from part payload', () => {
    const { logger } = createLogger();
    const result = extractUpstreamEvent(loadFixture('message.part.updated.text.json'), logger);

    expect(result.ok).toBe(true);
    expect(result.value.common.toolSessionId).toBe('ses_32c9fea15ffe2Rnv8tITmfmGmQ');
    expect(result.value.extra).toEqual({
      kind: 'message.part.updated',
      messageId: 'msg_cd3603508001ioF1UNVajFbFmX',
      partId: 'prt_cd3603509001EkSaJkPGfvcJSC',
    });
  });

  test('extracts message.part.delta fields from root properties', () => {
    const { logger } = createLogger();
    const result = extractUpstreamEvent(loadFixture('message.part.delta.json'), logger);

    expect(result.ok).toBe(true);
    expect(result.value.common.toolSessionId).toBe('ses_fixture_delta');
    expect(result.value.extra).toEqual({
      kind: 'message.part.delta',
      messageId: 'msg_fixture_delta',
      partId: 'prt_fixture_delta',
    });
  });

  test('extracts session.status status field', () => {
    const { logger } = createLogger();
    const result = extractUpstreamEvent(loadFixture('session.status.busy.json'), logger);

    expect(result.ok).toBe(true);
    expect(result.value.extra).toEqual({
      kind: 'session.status',
      status: 'busy',
    });
  });

  test('extracts permission.asked session field from fixture', () => {
    const { logger } = createLogger();
    const result = extractUpstreamEvent(loadFixture('permission.asked.json'), logger);

    expect(result.ok).toBe(true);
    expect(result.value.common).toEqual({
      eventType: 'permission.asked',
      toolSessionId: 'ses_permission_1',
    });
    expect(result.value.extra).toBeUndefined();
  });

  test('extracts question.asked session field from fixture', () => {
    const { logger } = createLogger();
    const result = extractUpstreamEvent(loadFixture('question.asked.json'), logger);

    expect(result.ok).toBe(true);
    expect(result.value.common).toEqual({
      eventType: 'question.asked',
      toolSessionId: 'ses_question_1',
    });
    expect(result.value.extra).toBeUndefined();
  });

  test('rejects unsupported events and records a unified extraction log', () => {
    const { logger, entries } = createLogger();
    const result = extractUpstreamEvent(
      {
        type: 'session.created',
        properties: {},
      },
      logger,
    );

    expect(result.ok).toBe(false);
    expect(result.error.code).toBe('unsupported_event');
    expect(entries).toHaveLength(1);
    expect(entries[0].message).toBe('event.extraction_failed');
    expect(entries[0].extra.eventType).toBe('session.created');
    expect(entries[0].extra.errorCode).toBe('unsupported_event');
  });

  test('rejects missing required fields and records a unified extraction log', () => {
    const { logger, entries } = createLogger();
    const result = extractUpstreamEvent(
      {
        type: 'message.updated',
        properties: {
          info: {
            id: 'msg-bad',
            role: 'user',
          },
        },
      },
      logger,
    );

    expect(result.ok).toBe(false);
    expect(result.error.code).toBe('missing_required_field');
    expect(entries).toHaveLength(1);
    expect(entries[0].message).toBe('event.extraction_failed');
    expect(entries[0].extra.field).toBe('properties.info.sessionID');
  });
});
