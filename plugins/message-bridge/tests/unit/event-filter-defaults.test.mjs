import { describe, expect, test } from 'bun:test';

import { EventFilter } from '../../dist/event/EventFilter.js';

describe('EventFilter defaults', () => {
  test('default allowlist includes requestion prefix events', () => {
    const filter = new EventFilter();

    expect(filter.isAllowed('requestion.created')).toBe(true);
    expect(filter.isAllowed('requestion.updated')).toBe(true);
  });
});
