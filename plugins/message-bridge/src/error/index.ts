import type { ToolErrorMessage, ToolErrorReason } from '../contracts/transport-messages.js';
import { FastFailDetector } from './FastFailDetector.js';
import { ErrorMapper } from './ErrorMapper.js';

export class BridgeError extends Error {
  constructor(message: string, public code?: string) {
    super(message);
    this.name = 'BridgeError';
  }
}

export function buildToolError(
  error: string,
  _agentId: string,
  welinkSessionId?: string,
  toolSessionId?: string,
  _sequenceNumber?: number,
  reason?: ToolErrorReason,
): ToolErrorMessage {
  return {
    type: 'tool_error',
    welinkSessionId,
    toolSessionId,
    error,
    reason,
  };
}

export { FastFailDetector, ErrorMapper };
