export const DOWNSTREAM_MESSAGE_TYPES = ['invoke', 'status_query'] as const;
export type DownstreamMessageType = typeof DOWNSTREAM_MESSAGE_TYPES[number];

export const INVOKE_ACTIONS = [
  'chat',
  'create_session',
  'close_session',
  'permission_reply',
  'abort_session',
  'question_reply',
] as const;

export type InvokeAction = typeof INVOKE_ACTIONS[number];
export const ACTION_NAMES = [...INVOKE_ACTIONS, 'status_query'] as const;
export type ActionName = typeof ACTION_NAMES[number];

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
  response: 'once' | 'always' | 'reject';
}

export type PermissionReplyPayload = PermissionReplyPayloadTarget;

export interface StatusQueryPayload {
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
  abort_session: AbortSessionPayload;
  question_reply: QuestionReplyPayload;
}

export interface ActionPayloadByName extends InvokePayloadByAction {
  status_query: StatusQueryPayload;
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
  abort_session: AbortSessionResultData;
  question_reply: QuestionReplyResultData;
}

export interface ActionResultDataByName extends ActionResultDataByAction {
  status_query: StatusQueryResultData;
}

export interface StatusQueryMessage {
  type: 'status_query';
}

type InvokeMessageBase<K extends InvokeAction> = {
  type: 'invoke';
  action: K;
  payload: InvokePayloadByAction[K];
};

export type InvokeMessageByAction = {
  chat: InvokeMessageBase<'chat'> & { welinkSessionId?: string };
  create_session: InvokeMessageBase<'create_session'> & { welinkSessionId: string };
  close_session: InvokeMessageBase<'close_session'> & { welinkSessionId?: string };
  permission_reply: InvokeMessageBase<'permission_reply'> & { welinkSessionId?: string };
  abort_session: InvokeMessageBase<'abort_session'> & { welinkSessionId?: string };
  question_reply: InvokeMessageBase<'question_reply'> & { welinkSessionId?: string };
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
