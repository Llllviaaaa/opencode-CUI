import type { BridgeLogger } from '../../runtime/AppLogger';
import type { BridgeEvent } from '../../runtime/types';
import {
  isSupportedUpstreamEventType,
  type MessagePartDeltaEvent,
  type MessagePartRemovedEvent,
  type MessagePartUpdatedEvent,
  type MessageRole,
  type MessageUpdatedEvent,
  type PermissionAskedEvent,
  type PermissionUpdatedEvent,
  type QuestionAskedEvent,
  type SessionErrorEvent,
  type SessionIdleEvent,
  type SessionStatusEvent,
  type SessionUpdatedEvent,
  type SupportedUpstreamEvent,
  type SupportedUpstreamEventType,
} from './SupportedUpstreamEvents';
import type {
  CommonUpstreamFields,
  ExtractResult,
  ExtractionError,
  ExtractionStage,
  MessagePartExtra,
  MessageUpdatedExtra,
  NormalizedUpstreamEvent,
  SessionStatusExtra,
} from './UpstreamEventTypes';

// The upstream extractor is the only layer allowed to read raw OpenCode event
// field paths. Runtime and actions must consume normalized output only.
interface EventExtractor<K extends SupportedUpstreamEventType, TExtra = undefined> {
  extractCommon(event: Extract<SupportedUpstreamEvent, { type: K }>): ExtractResult<CommonUpstreamFields>;
  extractExtra(
    event: Extract<SupportedUpstreamEvent, { type: K }>,
    common: CommonUpstreamFields,
  ): ExtractResult<TExtra>;
}

type UpstreamExtraByType = {
  'message.updated': MessageUpdatedExtra;
  'message.part.updated': MessagePartExtra;
  'message.part.delta': MessagePartExtra;
  'message.part.removed': MessagePartExtra;
  'session.status': SessionStatusExtra;
  'session.idle': undefined;
  'session.updated': undefined;
  'session.error': undefined;
  'permission.updated': undefined;
  'permission.asked': undefined;
  'question.asked': undefined;
};

const EXTRACTION_LOG_EVENT = 'event.extraction_failed';

function ok<T>(value: T): ExtractResult<T> {
  return { ok: true, value };
}

function fail<T = never>(error: ExtractionError): ExtractResult<T> {
  return { ok: false, error };
}

function missingRequiredField(
  eventType: string,
  stage: ExtractionStage,
  field: string,
  messageId?: string,
  toolSessionId?: string,
): ExtractResult<never> {
  return fail({
    stage,
    code: 'missing_required_field',
    eventType,
    field,
    message: `${field} is required`,
    messageId,
    toolSessionId,
  });
}

function invalidFieldType(
  eventType: string,
  stage: ExtractionStage,
  field: string,
  expectedType: string,
  messageId?: string,
  toolSessionId?: string,
): ExtractResult<never> {
  return fail({
    stage,
    code: 'invalid_field_type',
    eventType,
    field,
    message: `${field} must be ${expectedType}`,
    messageId,
    toolSessionId,
  });
}

function requireNonEmptyString(
  value: string | undefined,
  eventType: string,
  stage: ExtractionStage,
  field: string,
  messageId?: string,
  toolSessionId?: string,
): ExtractResult<string> {
  if (value === undefined) {
    return missingRequiredField(eventType, stage, field, messageId, toolSessionId);
  }
  if (typeof value !== 'string') {
    return invalidFieldType(eventType, stage, field, 'a non-empty string', messageId, toolSessionId);
  }
  const normalized = value.trim();
  if (!normalized) {
    return missingRequiredField(eventType, stage, field, messageId, toolSessionId);
  }
  return ok(normalized);
}

function requireMessageRole(
  value: string | undefined,
  eventType: string,
  messageId?: string,
  toolSessionId?: string,
): ExtractResult<MessageRole> {
  const roleResult = requireNonEmptyString(value, eventType, 'extra', 'properties.info.role', messageId, toolSessionId);
  if (!roleResult.ok) {
    return roleResult;
  }
  if (roleResult.value !== 'user' && roleResult.value !== 'assistant') {
    return invalidFieldType(eventType, 'extra', 'properties.info.role', '"user" or "assistant"', messageId, toolSessionId);
  }
  return ok(roleResult.value);
}

function noExtra<T extends SupportedUpstreamEventType>(
  _event: Extract<SupportedUpstreamEvent, { type: T }>,
): ExtractResult<undefined> {
  return ok(undefined);
}

function buildCommon(eventType: SupportedUpstreamEventType, toolSessionId: string): CommonUpstreamFields {
  return { eventType, toolSessionId };
}

function extractMessageUpdatedCommon(event: MessageUpdatedEvent): ExtractResult<CommonUpstreamFields> {
  const sessionResult = requireNonEmptyString(
    event.properties.info.sessionID,
    event.type,
    'common',
    'properties.info.sessionID',
  );
  if (!sessionResult.ok) return sessionResult;
  return ok(buildCommon(event.type, sessionResult.value));
}

function extractMessageUpdatedExtra(
  event: MessageUpdatedEvent,
  common: CommonUpstreamFields,
): ExtractResult<MessageUpdatedExtra> {
  const messageIdResult = requireNonEmptyString(
    event.properties.info.id,
    event.type,
    'extra',
    'properties.info.id',
    undefined,
    common.toolSessionId,
  );
  if (!messageIdResult.ok) return messageIdResult;
  const roleResult = requireMessageRole(event.properties.info.role, event.type, messageIdResult.value, common.toolSessionId);
  if (!roleResult.ok) return roleResult;
  return ok({
    kind: 'message.updated',
    messageId: messageIdResult.value,
    role: roleResult.value,
  });
}

function extractMessagePartUpdatedCommon(event: MessagePartUpdatedEvent): ExtractResult<CommonUpstreamFields> {
  const sessionResult = requireNonEmptyString(
    event.properties.part.sessionID,
    event.type,
    'common',
    'properties.part.sessionID',
  );
  if (!sessionResult.ok) return sessionResult;
  return ok(buildCommon(event.type, sessionResult.value));
}

function extractMessagePartUpdatedExtra(
  event: MessagePartUpdatedEvent,
  common: CommonUpstreamFields,
): ExtractResult<MessagePartExtra> {
  const messageIdResult = requireNonEmptyString(
    event.properties.part.messageID,
    event.type,
    'extra',
    'properties.part.messageID',
    undefined,
    common.toolSessionId,
  );
  if (!messageIdResult.ok) return messageIdResult;
  const partIdResult = requireNonEmptyString(
    event.properties.part.id,
    event.type,
    'extra',
    'properties.part.id',
    messageIdResult.value,
    common.toolSessionId,
  );
  if (!partIdResult.ok) return partIdResult;
  return ok({
    kind: 'message.part.updated',
    messageId: messageIdResult.value,
    partId: partIdResult.value,
  });
}

function extractMessagePartDeltaCommon(event: MessagePartDeltaEvent): ExtractResult<CommonUpstreamFields> {
  const sessionResult = requireNonEmptyString(
    event.properties.sessionID,
    event.type,
    'common',
    'properties.sessionID',
  );
  if (!sessionResult.ok) return sessionResult;
  return ok(buildCommon(event.type, sessionResult.value));
}

function extractMessagePartDeltaExtra(
  event: MessagePartDeltaEvent,
  common: CommonUpstreamFields,
): ExtractResult<MessagePartExtra> {
  const messageIdResult = requireNonEmptyString(
    event.properties.messageID,
    event.type,
    'extra',
    'properties.messageID',
    undefined,
    common.toolSessionId,
  );
  if (!messageIdResult.ok) return messageIdResult;
  const partIdResult = requireNonEmptyString(
    event.properties.partID,
    event.type,
    'extra',
    'properties.partID',
    messageIdResult.value,
    common.toolSessionId,
  );
  if (!partIdResult.ok) return partIdResult;
  return ok({
    kind: 'message.part.delta',
    messageId: messageIdResult.value,
    partId: partIdResult.value,
  });
}

function extractMessagePartRemovedCommon(event: MessagePartRemovedEvent): ExtractResult<CommonUpstreamFields> {
  const sessionResult = requireNonEmptyString(
    event.properties.sessionID,
    event.type,
    'common',
    'properties.sessionID',
  );
  if (!sessionResult.ok) return sessionResult;
  return ok(buildCommon(event.type, sessionResult.value));
}

function extractMessagePartRemovedExtra(
  event: MessagePartRemovedEvent,
  common: CommonUpstreamFields,
): ExtractResult<MessagePartExtra> {
  const messageIdResult = requireNonEmptyString(
    event.properties.messageID,
    event.type,
    'extra',
    'properties.messageID',
    undefined,
    common.toolSessionId,
  );
  if (!messageIdResult.ok) return messageIdResult;
  const partIdResult = requireNonEmptyString(
    event.properties.partID,
    event.type,
    'extra',
    'properties.partID',
    messageIdResult.value,
    common.toolSessionId,
  );
  if (!partIdResult.ok) return partIdResult;
  return ok({
    kind: 'message.part.removed',
    messageId: messageIdResult.value,
    partId: partIdResult.value,
  });
}

function extractSessionStatusCommon(event: SessionStatusEvent): ExtractResult<CommonUpstreamFields> {
  const sessionResult = requireNonEmptyString(
    event.properties.sessionID,
    event.type,
    'common',
    'properties.sessionID',
  );
  if (!sessionResult.ok) return sessionResult;
  return ok(buildCommon(event.type, sessionResult.value));
}

function extractSessionStatusExtra(
  event: SessionStatusEvent,
  common: CommonUpstreamFields,
): ExtractResult<SessionStatusExtra> {
  const statusResult = requireNonEmptyString(
    event.properties.status?.type,
    event.type,
    'extra',
    'properties.status.type',
    undefined,
    common.toolSessionId,
  );
  if (!statusResult.ok) return statusResult;
  return ok({
    kind: 'session.status',
    status: statusResult.value,
  });
}

function extractSessionIdleCommon(event: SessionIdleEvent): ExtractResult<CommonUpstreamFields> {
  const sessionResult = requireNonEmptyString(
    event.properties.sessionID,
    event.type,
    'common',
    'properties.sessionID',
  );
  if (!sessionResult.ok) return sessionResult;
  return ok(buildCommon(event.type, sessionResult.value));
}

function extractSessionUpdatedCommon(event: SessionUpdatedEvent): ExtractResult<CommonUpstreamFields> {
  const sessionResult = requireNonEmptyString(
    event.properties.info.id,
    event.type,
    'common',
    'properties.info.id',
  );
  if (!sessionResult.ok) return sessionResult;
  return ok(buildCommon(event.type, sessionResult.value));
}

function extractSessionErrorCommon(event: SessionErrorEvent): ExtractResult<CommonUpstreamFields> {
  const sessionResult = requireNonEmptyString(
    event.properties.sessionID,
    event.type,
    'common',
    'properties.sessionID',
  );
  if (!sessionResult.ok) return sessionResult;
  return ok(buildCommon(event.type, sessionResult.value));
}

function extractPermissionCommon(
  event: PermissionUpdatedEvent | PermissionAskedEvent,
): ExtractResult<CommonUpstreamFields> {
  const sessionResult = requireNonEmptyString(
    event.properties.sessionID,
    event.type,
    'common',
    'properties.sessionID',
  );
  if (!sessionResult.ok) return sessionResult;
  return ok(buildCommon(event.type, sessionResult.value));
}

function extractQuestionAskedCommon(event: QuestionAskedEvent): ExtractResult<CommonUpstreamFields> {
  const sessionResult = requireNonEmptyString(
    event.properties.sessionID,
    event.type,
    'common',
    'properties.sessionID',
  );
  if (!sessionResult.ok) return sessionResult;
  return ok(buildCommon(event.type, sessionResult.value));
}

export const UPSTREAM_EVENT_EXTRACTORS: {
  [K in SupportedUpstreamEventType]: EventExtractor<K, UpstreamExtraByType[K]>;
} = {
  'message.updated': { extractCommon: extractMessageUpdatedCommon, extractExtra: extractMessageUpdatedExtra },
  'message.part.updated': { extractCommon: extractMessagePartUpdatedCommon, extractExtra: extractMessagePartUpdatedExtra },
  'message.part.delta': { extractCommon: extractMessagePartDeltaCommon, extractExtra: extractMessagePartDeltaExtra },
  'message.part.removed': { extractCommon: extractMessagePartRemovedCommon, extractExtra: extractMessagePartRemovedExtra },
  'session.status': { extractCommon: extractSessionStatusCommon, extractExtra: extractSessionStatusExtra },
  'session.idle': { extractCommon: extractSessionIdleCommon, extractExtra: noExtra },
  'session.updated': { extractCommon: extractSessionUpdatedCommon, extractExtra: noExtra },
  'session.error': { extractCommon: extractSessionErrorCommon, extractExtra: noExtra },
  'permission.updated': { extractCommon: extractPermissionCommon, extractExtra: noExtra },
  'permission.asked': { extractCommon: extractPermissionCommon, extractExtra: noExtra },
  'question.asked': { extractCommon: extractQuestionAskedCommon, extractExtra: noExtra },
};

function buildEventPreview(event: BridgeEvent): Record<string, unknown> {
  const hasProperties =
    typeof event === 'object' && event !== null && 'properties' in event && typeof event.properties === 'object' && event.properties !== null;

  return {
    type: event.type,
    hasProperties,
    propertyKeys: hasProperties ? Object.keys(event.properties as Record<string, unknown>).slice(0, 8) : [],
  };
}

export function logExtractionFailure(logger: BridgeLogger, event: BridgeEvent, error: ExtractionError): void {
  logger.warn(EXTRACTION_LOG_EVENT, {
    eventType: error.eventType,
    stage: error.stage,
    errorCode: error.code,
    field: error.field,
    message: error.message,
    messageId: error.messageId,
    toolSessionId: error.toolSessionId,
    eventPreview: buildEventPreview(event),
  });
}

export function extractUpstreamEvent(
  event: BridgeEvent,
  logger: BridgeLogger,
): ExtractResult<NormalizedUpstreamEvent> {
  if (!isSupportedUpstreamEventType(event.type)) {
    const error: ExtractionError = {
      stage: 'common',
      code: 'unsupported_event',
      eventType: event.type,
      field: 'type',
      message: `Unsupported upstream event type: ${event.type}`,
    };
    logExtractionFailure(logger, event, error);
    return fail(error);
  }

  const typedEvent = event as SupportedUpstreamEvent;
  const extractor = UPSTREAM_EVENT_EXTRACTORS[event.type];
  const commonResult = extractor.extractCommon(typedEvent as never);
  if (!commonResult.ok) {
    logExtractionFailure(logger, event, commonResult.error);
    return commonResult;
  }

  const extraResult = extractor.extractExtra(typedEvent as never, commonResult.value);
  if (!extraResult.ok) {
    logExtractionFailure(logger, event, extraResult.error);
    return extraResult;
  }

  return ok({
    common: commonResult.value,
    extra: extraResult.value,
    raw: typedEvent,
  });
}
