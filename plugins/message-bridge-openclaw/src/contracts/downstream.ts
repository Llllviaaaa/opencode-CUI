export const DOWNSTREAM_MESSAGE_TYPES = ["invoke", "status_query"] as const;
export type DownstreamMessageType = (typeof DOWNSTREAM_MESSAGE_TYPES)[number];

export const INVOKE_ACTIONS = [
  "chat",
  "create_session",
  "close_session",
  "permission_reply",
  "abort_session",
  "question_reply",
] as const;

export type InvokeAction = (typeof INVOKE_ACTIONS)[number];

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

export interface AbortSessionPayload {
  toolSessionId: string;
}

export interface PermissionReplyPayload {
  toolSessionId: string;
  permissionId: string;
  response: "once" | "always" | "reject";
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

export type InvokeMessageByAction = {
  [K in InvokeAction]: {
    type: "invoke";
    welinkSessionId?: string;
    action: K;
    payload: InvokePayloadByAction[K];
  };
};

export type InvokeMessage = InvokeMessageByAction[InvokeAction];

export interface StatusQueryMessage {
  type: "status_query";
}

export type DownstreamMessage = InvokeMessage | StatusQueryMessage;

export interface CreateSessionResultData {
  sessionId: string;
}
