import { randomUUID } from 'crypto';
import { ToolErrorPayload, Envelope, MessageSource, ErrorCode } from '../types';
import { FastFailDetector } from './FastFailDetector';
import { ErrorMapper } from './ErrorMapper';

export class BridgeError extends Error {
  constructor(message: string, public code?: string) {
    super(message);
    this.name = 'BridgeError';
  }
}

export function buildToolError(
  code: ErrorCode,
  error: string,
  agentId: string,
  source: MessageSource,
  sessionId?: string,
  sequenceNumber?: number
): ToolErrorPayload {
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
    code,
    error,
    envelope
  };
}

export { FastFailDetector, ErrorMapper };