import type {
  EventMessagePartRemoved,
  EventMessagePartUpdated,
  EventMessageUpdated,
  EventPermissionUpdated,
  EventSessionError,
  EventSessionIdle,
  EventSessionStatus,
  EventSessionUpdated,
} from '@opencode-ai/sdk' with { 'resolution-mode': 'import' };
import type {
  EventMessagePartDelta,
  EventPermissionAsked,
  EventQuestionAsked,
} from '@opencode-ai/sdk/v2' with { 'resolution-mode': 'import' };

export const SUPPORTED_UPSTREAM_EVENT_TYPES = [
  'message.updated',
  'message.part.updated',
  'message.part.delta',
  'message.part.removed',
  'session.status',
  'session.idle',
  'session.updated',
  'session.error',
  'permission.updated',
  'permission.asked',
  'question.asked',
] as const;

export type SupportedUpstreamEventType = typeof SUPPORTED_UPSTREAM_EVENT_TYPES[number];

export type MessageUpdatedEvent = EventMessageUpdated;
export type MessagePartUpdatedEvent = EventMessagePartUpdated;
export type MessagePartDeltaEvent = EventMessagePartDelta;
export type MessagePartRemovedEvent = EventMessagePartRemoved;
export type SessionStatusEvent = EventSessionStatus;
export type SessionIdleEvent = EventSessionIdle;
export type SessionUpdatedEvent = EventSessionUpdated;
export type SessionErrorEvent = EventSessionError;
export type PermissionUpdatedEvent = EventPermissionUpdated;
export type PermissionAskedEvent = EventPermissionAsked;
export type QuestionAskedEvent = EventQuestionAsked;

export type SupportedUpstreamEvent =
  | EventMessageUpdated
  | EventMessagePartUpdated
  | EventMessagePartDelta
  | EventMessagePartRemoved
  | EventSessionStatus
  | EventSessionIdle
  | EventSessionUpdated
  | EventSessionError
  | EventPermissionUpdated
  | EventPermissionAsked
  | EventQuestionAsked;

export type MessageRole = Extract<EventMessageUpdated['properties']['info']['role'], 'user' | 'assistant'>;

const SUPPORTED_UPSTREAM_EVENT_TYPE_SET = new Set<string>(SUPPORTED_UPSTREAM_EVENT_TYPES);

export function isSupportedUpstreamEventType(value: string): value is SupportedUpstreamEventType {
  return SUPPORTED_UPSTREAM_EVENT_TYPE_SET.has(value);
}

export const DEFAULT_EVENT_ALLOWLIST = [...SUPPORTED_UPSTREAM_EVENT_TYPES] as const;
