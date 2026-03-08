export class EventFilter {
  private readonly prefixPatterns: string[] = [];
  private readonly exactPatterns: Set<string> = new Set();

  constructor(allowlist: readonly string[] = [
    'message.*',
    'permission.*',
    'requestion.*',
    'session.*',
    'file.edited',
    'todo.updated',
    'command.executed',
  ]) {
    for (const pattern of allowlist) {
      if (pattern.endsWith('*')) {
        this.prefixPatterns.push(pattern.slice(0, -1));
      } else {
        this.exactPatterns.add(pattern);
      }
    }
  }

  isAllowed(eventType: string): boolean {
    if (this.exactPatterns.has(eventType)) {
      return true;
    }

    for (const prefix of this.prefixPatterns) {
      if (eventType.startsWith(prefix)) {
        return true;
      }
    }

    return false;
  }
}
