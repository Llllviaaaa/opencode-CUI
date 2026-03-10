import { randomUUID } from 'crypto';
import type { Envelope } from '../contracts/envelope';

export class EnvelopeBuilder {
  private globalSequence = 0;
  private readonly sessionSequences = new Map<string, number>();

  constructor(private readonly agentId: string) {}

  build(sessionId?: string): Envelope {
    const sequenceNumber = sessionId
      ? this.nextSessionSequence(sessionId)
      : this.nextGlobalSequence();

    return {
      version: '1.0',
      messageId: randomUUID(),
      timestamp: new Date().toISOString(),
      source: 'message-bridge',
      agentId: this.agentId,
      sessionId,
      sequenceNumber,
      sequenceScope: sessionId ? 'session' : 'global',
    };
  }

  private nextGlobalSequence(): number {
    this.globalSequence += 1;
    return this.globalSequence;
  }

  private nextSessionSequence(sessionId: string): number {
    const next = (this.sessionSequences.get(sessionId) ?? 0) + 1;
    this.sessionSequences.set(sessionId, next);
    return next;
  }
}

export type { Envelope } from '../contracts/envelope';
