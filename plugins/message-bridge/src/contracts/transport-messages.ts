import type {
  CreateSessionResultData,
} from './downstream-messages';
import type { SupportedUpstreamEvent } from './upstream-events';

export const TRANSPORT_UPSTREAM_MESSAGE_TYPES = [
  'register',
  'heartbeat',
  'tool_event',
  'tool_done',
  'tool_error',
  'session_created',
  'status_response',
] as const;

export type UpstreamMessageType = typeof TRANSPORT_UPSTREAM_MESSAGE_TYPES[number];

export const TOOL_ERROR_REASON = {
  SESSION_NOT_FOUND: 'session_not_found',
} as const;

export const TOOL_ERROR_REASONS = [
  TOOL_ERROR_REASON.SESSION_NOT_FOUND,
] as const;

export type ToolErrorReason = typeof TOOL_ERROR_REASONS[number];

export interface RegisterMessage {
  type: 'register';
  deviceName: string;
  macAddress: string;
  os: string;
  toolType: string;
  toolVersion: string;
}

export interface HeartbeatMessage {
  type: 'heartbeat';
  timestamp: string;
}

export interface ToolEventMessage {
  type: 'tool_event';
  toolSessionId: string;
  event: SupportedUpstreamEvent;
}

export interface ToolDoneMessage {
  type: 'tool_done';
  toolSessionId: string;
  welinkSessionId?: string;
  usage?: unknown;
}

export interface ToolErrorMessage {
  type: 'tool_error';
  welinkSessionId?: string;
  toolSessionId?: string;
  error: string;
  reason?: ToolErrorReason;
}

export interface SessionCreatedMessage {
  type: 'session_created';
  welinkSessionId?: string;
  toolSessionId?: string;
  session?: CreateSessionResultData;
}

export interface StatusResponseMessage {
  type: 'status_response';
  opencodeOnline: boolean;
}

export type UpstreamMessage =
  | RegisterMessage
  | HeartbeatMessage
  | ToolEventMessage
  | ToolDoneMessage
  | ToolErrorMessage
  | SessionCreatedMessage
  | StatusResponseMessage;

export function isUpstreamMessage(message: unknown): message is UpstreamMessage {
  return (
    typeof message === 'object' &&
    message !== null &&
    'type' in message &&
    typeof (message as { type: unknown }).type === 'string' &&
    TRANSPORT_UPSTREAM_MESSAGE_TYPES.includes((message as { type: string }).type as UpstreamMessageType)
  );
}
