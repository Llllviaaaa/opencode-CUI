import type {
  CreateSessionResultData,
} from './downstream-messages';
import type { SupportedUpstreamEvent } from './upstream-events';

export const TRANSPORT_UPSTREAM_MESSAGE_TYPES = [
  'register',
  'heartbeat',
  'tool_event',
  'tool_error',
  'session_created',
  'status_response',
] as const;

export type UpstreamMessageType = typeof TRANSPORT_UPSTREAM_MESSAGE_TYPES[number];

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

export interface ToolErrorMessage {
  type: 'tool_error';
  welinkSessionId?: string;
  toolSessionId?: string;
  error: string;
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
