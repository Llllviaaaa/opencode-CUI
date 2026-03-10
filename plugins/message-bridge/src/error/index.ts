import { ToolErrorMessage } from '../types';
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
  _agentId: string,
  welinkSessionId?: string,
  toolSessionId?: string,
  _sequenceNumber?: number
): ToolErrorMessage {
  return {
    type: 'tool_error',
    welinkSessionId,
    toolSessionId,
    error,
  };
}

export { FastFailDetector, ErrorMapper };
