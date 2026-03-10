import type {
  ActionResultData,
  CreateSessionResultData,
} from './downstream-messages';
import type { Envelope, MessageSource } from './envelope';
import type { SupportedUpstreamEvent } from './upstream-events';

export type { Envelope, MessageSource } from './envelope';

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

export interface RegisterMessage {
  type: 'register';
  deviceName: string;
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
  sessionId?: string;
  welinkSessionId?: string;
  result?: ActionResultData;
  envelope: Envelope;
}

export interface ToolErrorMessage {
  type: 'tool_error';
  sessionId?: string;
  welinkSessionId?: string;
  error: string;
  envelope: Envelope;
}

export interface SessionCreatedMessage {
  type: 'session_created';
  sessionId: string;
  welinkSessionId?: string;
  toolSessionId?: string;
  session?: CreateSessionResultData;
  envelope: Envelope;
}

export interface StatusResponseMessage {
  type: 'status_response';
  opencodeOnline: boolean;
  sessionId?: string;
  welinkSessionId?: string;
  envelope: Envelope;
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
