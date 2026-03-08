import { randomUUID } from 'crypto';
import { PROTOCOL_VERSION, type MessageSource } from '../types';

export interface Envelope {
  version: string;
  messageId: string;
  timestamp: string;
  source: MessageSource;
  agentId: string;
  sessionId?: string;
  sequenceNumber: number;
  sequenceScope: 'session' | 'agent';
}

export class EnvelopeBuilder {
  private sequenceCounters: Map<string, number> = new Map();
  
  constructor(private agentId: string) {}
  
  build(sessionId?: string): Envelope {
    return {
      version: PROTOCOL_VERSION,
      messageId: this.generateMessageId(),
      timestamp: new Date().toISOString(),
      source: 'OPENCODE',
      agentId: this.agentId,
      sessionId,
      sequenceNumber: this.nextSequence(sessionId),
      sequenceScope: sessionId ? 'session' : 'agent',
    };
  }
  
  private generateMessageId(): string {
    return randomUUID();
  }
  
  private nextSequence(scope: string | undefined): number {
    const key = scope ?? 'agent';
    const current = this.sequenceCounters.get(key) ?? 0;
    const next = current + 1;
    this.sequenceCounters.set(key, next);
    return next;
  }
  
  resetSequence(scope: string | undefined): void {
    const key = scope ?? 'agent';
    this.sequenceCounters.delete(key);
  }
}
