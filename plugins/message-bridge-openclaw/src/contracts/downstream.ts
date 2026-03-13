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

type InvokeMessageBase<K extends InvokeAction> = {
  type: "invoke";
  action: K;
  payload: InvokePayloadByAction[K];
};

export type InvokeMessageByAction = {
  chat: InvokeMessageBase<"chat"> & { welinkSessionId?: string };
  create_session: InvokeMessageBase<"create_session"> & { welinkSessionId: string };
  close_session: InvokeMessageBase<"close_session"> & { welinkSessionId?: string };
  permission_reply: InvokeMessageBase<"permission_reply"> & { welinkSessionId?: string };
  abort_session: InvokeMessageBase<"abort_session"> & { welinkSessionId?: string };
  question_reply: InvokeMessageBase<"question_reply"> & { welinkSessionId?: string };
};

export type InvokeMessage = InvokeMessageByAction[InvokeAction];

export interface StatusQueryMessage {
  type: "status_query";
}

export type DownstreamMessage = InvokeMessage | StatusQueryMessage;

export interface CreateSessionResultData {
  sessionId: string;
}
