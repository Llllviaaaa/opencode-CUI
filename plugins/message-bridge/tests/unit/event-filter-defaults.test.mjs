import { describe, expect, test } from 'bun:test';

import { EventFilter } from '../../dist/event/EventFilter.js';

describe('EventFilter defaults', () => {
  test('default allowlist includes question prefix events and rejects legacy typo', () => {
    const filter = new EventFilter();

    expect(filter.isAllowed('question.asked')).toBe(true);
    expect(filter.isAllowed('question.updated')).toBe(true);
    expect(filter.isAllowed('requestion.created')).toBe(false);
  });
});
