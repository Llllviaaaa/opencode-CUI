import type {
  AbortSessionPayload,
  DownstreamMessage,
  ChatPayload,
  CloseSessionPayload,
  CreateSessionPayload,
  InvokeMessage,
  InvokeAction,
  PermissionReplyPayload,
  QuestionReplyPayload,
  StatusQueryMessage,
} from '../../contracts/downstream-messages.js';

export type DownstreamNormalizationStage = 'message' | 'payload';
export type DownstreamNormalizationErrorCode =
  | 'unsupported_message'
  | 'unsupported_action'
  | 'missing_required_field'
  | 'invalid_field_type';

export interface DownstreamNormalizationError {
  stage: DownstreamNormalizationStage;
  code: DownstreamNormalizationErrorCode;
  messageType?: string;
  action?: string;
  field: string;
  message: string;
  welinkSessionId?: string;
}

export type NormalizeResult<T> =
  | { ok: true; value: T }
  | { ok: false; error: DownstreamNormalizationError };

export interface NormalizedStatusQueryMessage extends StatusQueryMessage {
  type: 'status_query';
}

export type NormalizedInvokeMessageByAction = {
  chat: Extract<InvokeMessage, { action: 'chat' }>;
  create_session: Extract<InvokeMessage, { action: 'create_session' }>;
  close_session: Extract<InvokeMessage, { action: 'close_session' }>;
  permission_reply: Extract<InvokeMessage, { action: 'permission_reply' }>;
  abort_session: Extract<InvokeMessage, { action: 'abort_session' }>;
  question_reply: Extract<InvokeMessage, { action: 'question_reply' }>;
};

export type NormalizedInvokeMessage<K extends InvokeAction = InvokeAction> = NormalizedInvokeMessageByAction[K];
export type NormalizedDownstreamMessage = DownstreamMessage | NormalizedStatusQueryMessage;

export type NormalizedPayloadByAction = {
  chat: ChatPayload;
  create_session: CreateSessionPayload;
  close_session: CloseSessionPayload;
  permission_reply: PermissionReplyPayload;
  abort_session: AbortSessionPayload;
  question_reply: QuestionReplyPayload;
};
