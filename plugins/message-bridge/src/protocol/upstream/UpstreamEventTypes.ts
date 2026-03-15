import type { MessageRole, SupportedUpstreamEvent, SupportedUpstreamEventType } from '../../contracts/upstream-events.js';

export type ExtractionStage = 'common' | 'extra';
export type ExtractionErrorCode = 'unsupported_event' | 'missing_required_field' | 'invalid_field_type';

export interface ExtractionError {
  stage: ExtractionStage;
  code: ExtractionErrorCode;
  eventType: string;
  field: string;
  message: string;
  messageId?: string;
  toolSessionId?: string;
}

export type ExtractResult<T> =
  | { ok: true; value: T }
  | { ok: false; error: ExtractionError };

export interface CommonUpstreamFields {
  eventType: SupportedUpstreamEventType;
  toolSessionId: string;
}

export interface MessageUpdatedExtra {
  kind: 'message.updated';
  messageId: string;
  role: MessageRole;
}

export interface MessagePartExtra {
  kind: 'message.part.updated' | 'message.part.delta' | 'message.part.removed';
  messageId: string;
  partId: string;
}

export interface SessionStatusExtra {
  kind: 'session.status';
  status: string;
}

export type SupportedUpstreamExtra = MessageUpdatedExtra | MessagePartExtra | SessionStatusExtra | undefined;

export interface NormalizedUpstreamEvent<TExtra = SupportedUpstreamExtra> {
  common: CommonUpstreamFields;
  extra: TExtra;
  raw: SupportedUpstreamEvent;
}
