import { randomUUID } from 'crypto';

export interface Envelope {
  version: string;
  messageId: string;
  timestamp: number;
  source: string;
  agentId: string;
  sessionId?: string;
  sequenceNumber: number;
  sequenceScope: 'session' | 'global';
}

export class EnvelopeBuilder {
  private sequenceCounters: Map<string, number> = new Map();
  
  constructor(private agentId: string) {}
  
  build(sessionId?: string): Envelope {
    return {
      version: '1.0',
      messageId: this.generateMessageId(),
      timestamp: Date.now(),
      source: 'message-bridge',
      agentId: this.agentId,
      sessionId,
      sequenceNumber: this.nextSequence(sessionId),
      sequenceScope: sessionId ? 'session' : 'global',
    };
  }
  
  private generateMessageId(): string {
    return randomUUID();
  }
  
  private nextSequence(scope: string | undefined): number {
    const key = scope ?? 'global';
    const current = this.sequenceCounters.get(key) ?? 0;
    const next = current + 1;
    this.sequenceCounters.set(key, next);
    return next;
  }
  
  resetSequence(scope: string | undefined): void {
    const key = scope ?? 'global';
    this.sequenceCounters.delete(key);
  }
}