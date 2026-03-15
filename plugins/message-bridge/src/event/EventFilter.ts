import { DEFAULT_EVENT_ALLOWLIST } from '../contracts/upstream-events.js';

export class EventFilter {
  private readonly exactPatterns: Set<string> = new Set();

  constructor(allowlist: readonly string[] = DEFAULT_EVENT_ALLOWLIST) {
    for (const pattern of allowlist) {
      this.exactPatterns.add(pattern);
    }
  }

  isAllowed(eventType: string): boolean {
    return this.exactPatterns.has(eventType);
  }
}
