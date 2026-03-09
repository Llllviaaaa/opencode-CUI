import { randomUUID } from 'crypto';
import { ToolErrorMessage, Envelope, MessageSource } from '../types';
import { FastFailDetector } from './FastFailDetector';
import { ErrorMapper } from './ErrorMapper';

export class BridgeError extends Error {
  constructor(message: string, public code?: string) {
    super(message);
    this.name = 'BridgeError';
  }
}

export function buildToolError(
  error: string,
  agentId: string,
  source: MessageSource,
  sessionId?: string,
  sequenceNumber?: number
): ToolErrorMessage {
  const envelope: Envelope = {
    version: '1.0',
    messageId: randomUUID(),
    timestamp: new Date().toISOString(),
    source,
    agentId,
    sessionId,
    sequenceNumber: sequenceNumber ?? 1,
    sequenceScope: sessionId ? 'session' : 'global'
  };
  return {
    type: 'tool_error',
    sessionId,
    error,
    envelope,
  };
}

export { FastFailDetector, ErrorMapper };
