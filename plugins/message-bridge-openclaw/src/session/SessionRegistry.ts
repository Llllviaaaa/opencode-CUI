import type { MessageBridgeSessionRecord } from "../types.js";

export class SessionRegistry {
  private readonly byToolSessionId = new Map<string, MessageBridgeSessionRecord>();

  constructor(private readonly sessionPrefix: string) {}

  ensure(toolSessionId: string, welinkSessionId?: string): MessageBridgeSessionRecord {
    const existing = this.byToolSessionId.get(toolSessionId);
    if (existing) {
      if (welinkSessionId && !existing.welinkSessionId) {
        existing.welinkSessionId = welinkSessionId;
      }
      return existing;
    }

    const record: MessageBridgeSessionRecord = {
      toolSessionId,
      sessionKey: `${this.sessionPrefix}:${toolSessionId}`,
      welinkSessionId,
    };
    this.byToolSessionId.set(toolSessionId, record);
    return record;
  }
  get(toolSessionId: string): MessageBridgeSessionRecord | undefined {
    return this.byToolSessionId.get(toolSessionId);
  }

  delete(toolSessionId: string): MessageBridgeSessionRecord | undefined {
    const existing = this.byToolSessionId.get(toolSessionId);
    this.byToolSessionId.delete(toolSessionId);
    return existing;
  }
}
