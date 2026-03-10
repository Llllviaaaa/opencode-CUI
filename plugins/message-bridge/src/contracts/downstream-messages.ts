import type { Envelope } from './envelope';

export const DOWNSTREAM_MESSAGE_TYPES = ['invoke', 'status_query'] as const;
export type DownstreamMessageType = typeof DOWNSTREAM_MESSAGE_TYPES[number];

export const INVOKE_ACTIONS = [
  'chat',
  'create_session',
  'close_session',
  'permission_reply',
  'status_query',
  'abort_session',
  'question_reply',
] as const;

export type InvokeAction = typeof INVOKE_ACTIONS[number];

export interface ChatPayload {
  toolSessionId: string;
  text: string;
}

export interface CreateSessionPayload {
  sessionId?: string;
  metadata?: Record<string, unknown>;
}

export interface CloseSessionPayload {
  toolSessionId: string;
}

export interface PermissionReplyPayloadTarget {
  permissionId: string;
  toolSessionId: string;
  response: 'allow' | 'always' | 'deny';
}

export type PermissionReplyPayload = PermissionReplyPayloadTarget;

export interface StatusQueryPayload {
  sessionId?: string;
}

export interface AbortSessionPayload {
  toolSessionId: string;
}

export interface QuestionReplyPayload {
  toolSessionId: string;
  answer: string;
  toolCallId?: string;
}

export interface InvokePayloadByAction {
  chat: ChatPayload;
  create_session: CreateSessionPayload;
  close_session: CloseSessionPayload;
  permission_reply: PermissionReplyPayload;
  status_query: StatusQueryPayload;
  abort_session: AbortSessionPayload;
  question_reply: QuestionReplyPayload;
}

export type InvokePayload = InvokePayloadByAction[InvokeAction];

export interface CreateSessionResultData {
  sessionId?: string;
  session?: Record<string, unknown>;
}

export interface CloseSessionResultData {
  sessionId: string;
  closed: true;
}

export interface PermissionReplyResultData {
  permissionId: string;
  response: PermissionReplyPayload['response'];
  applied: true;
}

export interface StatusQueryResultData {
  opencodeOnline: boolean;
  connectionState: string;
  sessionId?: string;
  timestamp: string;
}

export interface AbortSessionResultData {
  sessionId: string;
  aborted: true;
}

export interface QuestionReplyResultData {
  requestId: string;
  replied: true;
}

export type ActionResultData =
  | CreateSessionResultData
  | CloseSessionResultData
  | PermissionReplyResultData
  | StatusQueryResultData
  | AbortSessionResultData
  | QuestionReplyResultData;

export interface ActionResultDataByAction {
  chat: void;
  create_session: CreateSessionResultData;
  close_session: CloseSessionResultData;
  permission_reply: PermissionReplyResultData;
  status_query: StatusQueryResultData;
  abort_session: AbortSessionResultData;
  question_reply: QuestionReplyResultData;
}

export interface StatusQueryMessage {
  type: 'status_query';
  sessionId?: string;
  envelope?: Envelope;
}

export type InvokeMessageByAction = {
  [K in InvokeAction]: {
    type: 'invoke';
    sessionId?: string;
    action: K;
    payload: InvokePayloadByAction[K];
    envelope?: Envelope;
  };
};

export type InvokeMessage = InvokeMessageByAction[InvokeAction];

export type DownstreamMessage = InvokeMessage | StatusQueryMessage;

export function isDownstreamMessage(message: unknown): message is DownstreamMessage {
  return (
    typeof message === 'object' &&
    message !== null &&
    'type' in message &&
    typeof (message as { type: unknown }).type === 'string' &&
    DOWNSTREAM_MESSAGE_TYPES.includes((message as { type: string }).type as DownstreamMessageType)
  );
}
