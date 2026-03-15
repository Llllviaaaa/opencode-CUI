import { describe, test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

import {
  extractUpstreamEvent,
  UPSTREAM_EVENT_EXTRACTORS,
} from '../../src/event/UpstreamEventExtractor.ts';
import { SUPPORTED_UPSTREAM_EVENT_TYPES } from '../../src/event/SupportedUpstreamEvents.ts';

const __dirname = dirname(fileURLToPath(import.meta.url));

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
  const path = join(__dirname, '..', 'fixtures', 'opencode-events', name);
  return JSON.parse(readFileSync(path, 'utf8'));
}

describe('upstream event extractor', () => {
  test('registry covers every supported upstream event type', () => {
    assert.deepStrictEqual(Object.keys(UPSTREAM_EVENT_EXTRACTORS).sort(), [...SUPPORTED_UPSTREAM_EVENT_TYPES].sort());
  });

  test('extracts message.updated common and extra fields from properties.info', () => {
    const { logger } = createLogger();
    const result = extractUpstreamEvent(loadFixture('message.updated.user.json'), logger);

    assert.strictEqual(result.ok, true);
    assert.deepStrictEqual(result.value.common, {
      eventType: 'message.updated',
      toolSessionId: 'ses_32c9fea15ffe2Rnv8tITmfmGmQ',
    });
    assert.deepStrictEqual(result.value.extra, {
      kind: 'message.updated',
      messageId: 'msg_cd3603508001ioF1UNVajFbFmX',
      role: 'user',
    });
  });

  test('extracts message.part.updated fields from part payload', () => {
    const { logger } = createLogger();
    const result = extractUpstreamEvent(loadFixture('message.part.updated.text.json'), logger);

    assert.strictEqual(result.ok, true);
    assert.strictEqual(result.value.common.toolSessionId, 'ses_32c9fea15ffe2Rnv8tITmfmGmQ');
    assert.deepStrictEqual(result.value.extra, {
      kind: 'message.part.updated',
      messageId: 'msg_cd3603508001ioF1UNVajFbFmX',
      partId: 'prt_cd3603509001EkSaJkPGfvcJSC',
    });
  });

  test('extracts message.part.delta fields from root properties', () => {
    const { logger } = createLogger();
    const result = extractUpstreamEvent(loadFixture('message.part.delta.json'), logger);

    assert.strictEqual(result.ok, true);
    assert.strictEqual(result.value.common.toolSessionId, 'ses_fixture_delta');
    assert.deepStrictEqual(result.value.extra, {
      kind: 'message.part.delta',
      messageId: 'msg_fixture_delta',
      partId: 'prt_fixture_delta',
    });
  });

  test('extracts session.status status field', () => {
    const { logger } = createLogger();
    const result = extractUpstreamEvent(loadFixture('session.status.busy.json'), logger);

    assert.strictEqual(result.ok, true);
    assert.deepStrictEqual(result.value.extra, {
      kind: 'session.status',
      status: 'busy',
    });
  });

  test('extracts permission.asked session field from fixture', () => {
    const { logger } = createLogger();
    const result = extractUpstreamEvent(loadFixture('permission.asked.json'), logger);

    assert.strictEqual(result.ok, true);
    assert.deepStrictEqual(result.value.common, {
      eventType: 'permission.asked',
      toolSessionId: 'ses_permission_1',
    });
    assert.strictEqual(result.value.extra, undefined);
  });

  test('extracts question.asked session field from fixture', () => {
    const { logger } = createLogger();
    const result = extractUpstreamEvent(loadFixture('question.asked.json'), logger);

    assert.strictEqual(result.ok, true);
    assert.deepStrictEqual(result.value.common, {
      eventType: 'question.asked',
      toolSessionId: 'ses_question_1',
    });
    assert.strictEqual(result.value.extra, undefined);
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

    assert.strictEqual(result.ok, false);
    assert.strictEqual(result.error.code, 'unsupported_event');
    assert.strictEqual(entries.length, 1);
    assert.strictEqual(entries[0].message, 'event.extraction_failed');
    assert.strictEqual(entries[0].extra.eventType, 'session.created');
    assert.strictEqual(entries[0].extra.errorCode, 'unsupported_event');
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

    assert.strictEqual(result.ok, false);
    assert.strictEqual(result.error.code, 'missing_required_field');
    assert.strictEqual(entries.length, 1);
    assert.strictEqual(entries[0].message, 'event.extraction_failed');
    assert.strictEqual(entries[0].extra.field, 'properties.info.sessionID');
  });
});
