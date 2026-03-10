import { describe, expect, test } from 'bun:test';

import { EventFilter } from '../../dist/event/EventFilter.js';

describe('EventFilter defaults', () => {
  test('default allowlist keeps exact supported events and rejects unsupported variants', () => {
    const filter = new EventFilter();

    expect(filter.isAllowed('question.asked')).toBe(true);
    expect(filter.isAllowed('question.updated')).toBe(false);
    expect(filter.isAllowed('requestion.created')).toBe(false);
  });
});
