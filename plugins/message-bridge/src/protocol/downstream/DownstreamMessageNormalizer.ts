import type { BridgeLogger } from '../../runtime/AppLogger';
import type {
  DownstreamMessage,
  ChatPayload,
  CloseSessionPayload,
  CreateSessionPayload,
  InvokeMessage,
  InvokeAction,
  PermissionReplyPayload,
  StatusQueryPayload,
} from '../../contracts/downstream-messages';
import type { Envelope } from '../../contracts/envelope';
import { hasEnvelope } from '../../contracts/envelope';
import { isSupportedDownstreamMessageType, isSupportedInvokeAction } from './SupportedDownstreamMessages';
import type {
  DownstreamNormalizationError,
  DownstreamNormalizationStage,
  NormalizeResult,
  NormalizedDownstreamMessage,
  NormalizedInvokeMessage,
} from './DownstreamMessageTypes';

// The downstream normalizer is the only layer allowed to read raw gateway
// message fields. Runtime and actions must use normalized commands/payloads.
const DOWNSTREAM_NORMALIZATION_LOG_EVENT = 'downstream.normalization_failed';

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null;
}

function ok<T>(value: T): NormalizeResult<T> {
  return { ok: true, value };
}

function fail<T = never>(error: DownstreamNormalizationError): NormalizeResult<T> {
  return { ok: false, error };
}

function missingRequiredField(
  stage: DownstreamNormalizationStage,
  field: string,
  messageType?: string,
  action?: string,
  sessionId?: string,
): NormalizeResult<never> {
  return fail({
    stage,
    code: 'missing_required_field',
    field,
    message: `${field} is required`,
    messageType,
    action,
    sessionId,
  });
}

function invalidFieldType(
  stage: DownstreamNormalizationStage,
  field: string,
  expectedType: string,
  messageType?: string,
  action?: string,
  sessionId?: string,
): NormalizeResult<never> {
  return fail({
    stage,
    code: 'invalid_field_type',
    field,
    message: `${field} must be ${expectedType}`,
    messageType,
    action,
    sessionId,
  });
}

function unsupportedMessage(messageType: string): NormalizeResult<never> {
  return fail({
    stage: 'message',
    code: 'unsupported_message',
    field: 'type',
    message: `Unsupported downstream message type: ${messageType}`,
    messageType,
  });
}

function unsupportedAction(action: string, sessionId?: string): NormalizeResult<never> {
  return fail({
    stage: 'payload',
    code: 'unsupported_action',
    field: 'action',
    message: `Unsupported invoke action: ${action}`,
    messageType: 'invoke',
    action,
    sessionId,
  });
}

function errorOf(result: NormalizeResult<never>): DownstreamNormalizationError {
  if (result.ok) {
    throw new Error('Expected failed normalization result');
  }
  return result.error;
}

function requireNonEmptyString(
  value: unknown,
  stage: DownstreamNormalizationStage,
  field: string,
  messageType?: string,
  action?: string,
  sessionId?: string,
): NormalizeResult<string> {
  if (value === undefined) {
    return missingRequiredField(stage, field, messageType, action, sessionId);
  }
  if (typeof value !== 'string') {
    return invalidFieldType(stage, field, 'a non-empty string', messageType, action, sessionId);
  }
  if (!value.trim()) {
    return missingRequiredField(stage, field, messageType, action, sessionId);
  }
  return ok(value);
}

function extractEnvelope(raw: Record<string, unknown>): Envelope | undefined {
  return hasEnvelope(raw) ? raw.envelope : undefined;
}

function extractSessionId(raw: Record<string, unknown>): string | undefined {
  return typeof raw.sessionId === 'string' ? raw.sessionId : undefined;
}

function unwrapMessage(raw: Record<string, unknown>): Record<string, unknown> {
  if (!hasEnvelope(raw)) {
    return raw;
  }
  const envelopeCarrier = raw as Record<string, unknown> & { envelope: Envelope };
  return {
    type: envelopeCarrier.type,
    ...(isRecord(envelopeCarrier.payload) ? envelopeCarrier.payload : { payload: envelopeCarrier.payload }),
    envelope: envelopeCarrier.envelope,
  };
}

function buildEventPreview(raw: unknown): Record<string, unknown> {
  if (!isRecord(raw)) {
    return { kind: typeof raw };
  }
  return {
    type: typeof raw.type === 'string' ? raw.type : undefined,
    keys: Object.keys(raw).slice(0, 8),
  };
}

export function logDownstreamNormalizationFailure(
  logger: BridgeLogger,
  raw: unknown,
  error: DownstreamNormalizationError,
): void {
  logger.warn(DOWNSTREAM_NORMALIZATION_LOG_EVENT, {
    stage: error.stage,
    errorCode: error.code,
    field: error.field,
    message: error.message,
    messageType: error.messageType,
    action: error.action,
    sessionId: error.sessionId,
    messagePreview: buildEventPreview(raw),
  });
}

export function normalizeChatPayload(payload: unknown, sessionId?: string): NormalizeResult<ChatPayload> {
  if (!isRecord(payload)) {
    return invalidFieldType('payload', 'payload', 'an object', 'invoke', 'chat', sessionId);
  }
  const toolSessionId = requireNonEmptyString(payload.toolSessionId, 'payload', 'payload.toolSessionId', 'invoke', 'chat', sessionId);
  if (!toolSessionId.ok) return toolSessionId;
  const text = requireNonEmptyString(payload.text, 'payload', 'payload.text', 'invoke', 'chat', sessionId);
  if (!text.ok) return text;
  return ok({
    toolSessionId: toolSessionId.value,
    text: text.value,
  });
}

export function normalizeCreateSessionPayload(payload: unknown, sessionId?: string): NormalizeResult<CreateSessionPayload> {
  if (!isRecord(payload)) {
    return invalidFieldType('payload', 'payload', 'an object', 'invoke', 'create_session', sessionId);
  }
  return ok({
    sessionId: typeof payload.sessionId === 'string' ? payload.sessionId : undefined,
    metadata: isRecord(payload.metadata) ? payload.metadata : undefined,
  });
}

export function normalizeCloseSessionPayload(payload: unknown, sessionId?: string): NormalizeResult<CloseSessionPayload> {
  if (!isRecord(payload)) {
    return invalidFieldType('payload', 'payload', 'an object', 'invoke', 'close_session', sessionId);
  }
  const toolSessionId = requireNonEmptyString(
    payload.toolSessionId,
    'payload',
    'payload.toolSessionId',
    'invoke',
    'close_session',
    sessionId,
  );
  if (!toolSessionId.ok) return toolSessionId;
  return ok({ toolSessionId: toolSessionId.value });
}

export function normalizePermissionReplyPayload(payload: unknown, sessionId?: string): NormalizeResult<PermissionReplyPayload> {
  if (!isRecord(payload)) {
    return invalidFieldType('payload', 'payload', 'an object', 'invoke', 'permission_reply', sessionId);
  }
  const permissionId = requireNonEmptyString(
    payload.permissionId,
    'payload',
    'payload.permissionId',
    'invoke',
    'permission_reply',
    sessionId,
  );
  if (!permissionId.ok) return permissionId;
  const toolSessionId = requireNonEmptyString(
    payload.toolSessionId,
    'payload',
    'payload.toolSessionId',
    'invoke',
    'permission_reply',
    sessionId,
  );
  if (!toolSessionId.ok) return toolSessionId;
  if (payload.response !== 'allow' && payload.response !== 'always' && payload.response !== 'deny') {
    return invalidFieldType('payload', 'payload.response', '"allow", "always", or "deny"', 'invoke', 'permission_reply', sessionId);
  }
  return ok({
    permissionId: permissionId.value,
    toolSessionId: toolSessionId.value,
    response: payload.response,
  });
}

export function normalizeStatusQueryPayload(payload: unknown, sessionId?: string): NormalizeResult<StatusQueryPayload> {
  if (payload === undefined) {
    return ok({ sessionId: undefined });
  }
  if (!isRecord(payload)) {
    return invalidFieldType('payload', 'payload', 'an object', 'invoke', 'status_query', sessionId);
  }
  if (payload.sessionId !== undefined) {
    const innerSessionId = requireNonEmptyString(
      payload.sessionId,
      'payload',
      'payload.sessionId',
      'invoke',
      'status_query',
      sessionId,
    );
    if (!innerSessionId.ok) return innerSessionId;
    return ok({ sessionId: innerSessionId.value });
  }
  return ok({ sessionId: undefined });
}

function normalizeInvokePayload(
  action: InvokeAction,
  payload: unknown,
  sessionId?: string,
): NormalizeResult<NormalizedInvokeMessage> {
  switch (action) {
    case 'chat': {
      const normalized = normalizeChatPayload(payload, sessionId);
      if (!normalized.ok) return normalized;
      return ok({ type: 'invoke', action, payload: normalized.value, sessionId });
    }
    case 'create_session': {
      const normalized = normalizeCreateSessionPayload(payload, sessionId);
      if (!normalized.ok) return normalized;
      return ok({ type: 'invoke', action, payload: normalized.value, sessionId });
    }
    case 'close_session': {
      const normalized = normalizeCloseSessionPayload(payload, sessionId);
      if (!normalized.ok) return normalized;
      return ok({ type: 'invoke', action, payload: normalized.value, sessionId });
    }
    case 'permission_reply': {
      const normalized = normalizePermissionReplyPayload(payload, sessionId);
      if (!normalized.ok) return normalized;
      return ok({ type: 'invoke', action, payload: normalized.value, sessionId });
    }
    case 'status_query': {
      const normalized = normalizeStatusQueryPayload(payload, sessionId);
      if (!normalized.ok) return normalized;
      return ok({ type: 'invoke', action, payload: normalized.value, sessionId });
    }
  }
}

export function normalizeDownstreamMessage(
  raw: unknown,
  logger: BridgeLogger,
): NormalizeResult<NormalizedDownstreamMessage> {
  if (!isRecord(raw)) {
    const error = errorOf(invalidFieldType('message', 'message', 'an object'));
    logDownstreamNormalizationFailure(logger, raw, error);
    return fail(error);
  }

  const envelope = extractEnvelope(raw);
  const unwrapped = unwrapMessage(raw);
  const messageTypeValue = unwrapped.type;

  if (typeof messageTypeValue !== 'string') {
    const error = errorOf(missingRequiredField('message', 'type'));
    logDownstreamNormalizationFailure(logger, raw, error);
    return fail(error);
  }

  if (!isSupportedDownstreamMessageType(messageTypeValue)) {
    const error = errorOf(unsupportedMessage(messageTypeValue));
    logDownstreamNormalizationFailure(logger, raw, error);
    return fail(error);
  }

  const sessionId = extractSessionId(unwrapped);

  if (messageTypeValue === 'status_query') {
    return ok({
      type: 'status_query',
      sessionId,
      envelope,
    });
  }

  const actionValue = unwrapped.action;
  if (typeof actionValue !== 'string') {
    const error = errorOf(missingRequiredField('payload', 'action', 'invoke', undefined, sessionId));
    logDownstreamNormalizationFailure(logger, raw, error);
    return fail(error);
  }
  if (!isSupportedInvokeAction(actionValue)) {
    const error = errorOf(unsupportedAction(actionValue, sessionId));
    logDownstreamNormalizationFailure(logger, raw, error);
    return fail(error);
  }

  const normalized = normalizeInvokePayload(actionValue, unwrapped.payload, sessionId);
  if (!normalized.ok) {
    logDownstreamNormalizationFailure(logger, raw, normalized.error);
    return normalized;
  }

  return ok({
    ...normalized.value,
    envelope,
  });
}
