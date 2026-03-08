import { randomUUID } from 'crypto';
import { ToolErrorPayload, Envelope, MessageSource, PROTOCOL_VERSION } from '../types';
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
): ToolErrorPayload {
  const envelope: Envelope = {
    version: PROTOCOL_VERSION,
    messageId: randomUUID(),
    timestamp: new Date().toISOString(),
    source,
    agentId,
    sessionId,
    sequenceNumber: sequenceNumber ?? 1,
    sequenceScope: sessionId ? 'session' : 'agent'
  };

  return {
    type: 'tool_error',
    sessionId,
    error,
    envelope
  };
}

export { FastFailDetector, ErrorMapper };
