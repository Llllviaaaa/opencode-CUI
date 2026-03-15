import { describe, test } from 'node:test';
import assert from 'node:assert/strict';

import { EventFilter } from '../../src/event/EventFilter.ts';

describe('EventFilter defaults', () => {
  test('default allowlist keeps exact supported events and rejects unsupported variants', () => {
    const filter = new EventFilter();

    assert.strictEqual(filter.isAllowed('question.asked'), true);
    assert.strictEqual(filter.isAllowed('question.updated'), false);
    assert.strictEqual(filter.isAllowed('requestion.created'), false);
  });
});
