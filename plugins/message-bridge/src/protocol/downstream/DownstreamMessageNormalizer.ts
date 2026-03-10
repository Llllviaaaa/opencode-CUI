import type { BridgeLogger } from '../../runtime/AppLogger';
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
} from '../../contracts/downstream-messages';
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
  welinkSessionId?: string,
): NormalizeResult<never> {
  return fail({
    stage,
    code: 'missing_required_field',
    field,
    message: `${field} is required`,
    messageType,
    action,
    welinkSessionId,
  });
}

function invalidFieldType(
  stage: DownstreamNormalizationStage,
  field: string,
  expectedType: string,
  messageType?: string,
  action?: string,
  welinkSessionId?: string,
): NormalizeResult<never> {
  return fail({
    stage,
    code: 'invalid_field_type',
    field,
    message: `${field} must be ${expectedType}`,
    messageType,
    action,
    welinkSessionId,
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

function unsupportedAction(action: string, welinkSessionId?: string): NormalizeResult<never> {
  return fail({
    stage: 'payload',
    code: 'unsupported_action',
    field: 'action',
    message: `Unsupported invoke action: ${action}`,
    messageType: 'invoke',
    action,
    welinkSessionId,
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
  welinkSessionId?: string,
): NormalizeResult<string> {
  if (value === undefined) {
    return missingRequiredField(stage, field, messageType, action, welinkSessionId);
  }
  if (typeof value !== 'string') {
    return invalidFieldType(stage, field, 'a non-empty string', messageType, action, welinkSessionId);
  }
  if (!value.trim()) {
    return missingRequiredField(stage, field, messageType, action, welinkSessionId);
  }
  return ok(value);
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
    welinkSessionId: error.welinkSessionId,
    messagePreview: buildEventPreview(raw),
  });
}

export function normalizeChatPayload(payload: unknown, welinkSessionId?: string): NormalizeResult<ChatPayload> {
  if (!isRecord(payload)) {
    return invalidFieldType('payload', 'payload', 'an object', 'invoke', 'chat', welinkSessionId);
  }
  const toolSessionId = requireNonEmptyString(payload.toolSessionId, 'payload', 'payload.toolSessionId', 'invoke', 'chat', welinkSessionId);
  if (!toolSessionId.ok) return toolSessionId;
  const text = requireNonEmptyString(payload.text, 'payload', 'payload.text', 'invoke', 'chat', welinkSessionId);
  if (!text.ok) return text;
  return ok({
    toolSessionId: toolSessionId.value,
    text: text.value,
  });
}

export function normalizeCreateSessionPayload(payload: unknown, welinkSessionId?: string): NormalizeResult<CreateSessionPayload> {
  if (!isRecord(payload)) {
    return invalidFieldType('payload', 'payload', 'an object', 'invoke', 'create_session', welinkSessionId);
  }
  return ok({
    sessionId: typeof payload.sessionId === 'string' ? payload.sessionId : undefined,
    metadata: isRecord(payload.metadata) ? payload.metadata : undefined,
  });
}

export function normalizeCloseSessionPayload(payload: unknown, welinkSessionId?: string): NormalizeResult<CloseSessionPayload> {
  if (!isRecord(payload)) {
    return invalidFieldType('payload', 'payload', 'an object', 'invoke', 'close_session', welinkSessionId);
  }
  const toolSessionId = requireNonEmptyString(
    payload.toolSessionId,
    'payload',
    'payload.toolSessionId',
    'invoke',
    'close_session',
    welinkSessionId,
  );
  if (!toolSessionId.ok) return toolSessionId;
  return ok({ toolSessionId: toolSessionId.value });
}

export function normalizePermissionReplyPayload(payload: unknown, welinkSessionId?: string): NormalizeResult<PermissionReplyPayload> {
  if (!isRecord(payload)) {
    return invalidFieldType('payload', 'payload', 'an object', 'invoke', 'permission_reply', welinkSessionId);
  }
  const permissionId = requireNonEmptyString(
    payload.permissionId,
    'payload',
    'payload.permissionId',
    'invoke',
    'permission_reply',
    welinkSessionId,
  );
  if (!permissionId.ok) return permissionId;
  const toolSessionId = requireNonEmptyString(
    payload.toolSessionId,
    'payload',
    'payload.toolSessionId',
    'invoke',
    'permission_reply',
    welinkSessionId,
  );
  if (!toolSessionId.ok) return toolSessionId;
  if (payload.response !== 'once' && payload.response !== 'always' && payload.response !== 'reject') {
    return invalidFieldType('payload', 'payload.response', '"once", "always", or "reject"', 'invoke', 'permission_reply', welinkSessionId);
  }
  return ok({
    permissionId: permissionId.value,
    toolSessionId: toolSessionId.value,
    response: payload.response,
  });
}

export function normalizeAbortSessionPayload(payload: unknown, welinkSessionId?: string): NormalizeResult<AbortSessionPayload> {
  if (!isRecord(payload)) {
    return invalidFieldType('payload', 'payload', 'an object', 'invoke', 'abort_session', welinkSessionId);
  }
  const toolSessionId = requireNonEmptyString(
    payload.toolSessionId,
    'payload',
    'payload.toolSessionId',
    'invoke',
    'abort_session',
    welinkSessionId,
  );
  if (!toolSessionId.ok) return toolSessionId;
  return ok({ toolSessionId: toolSessionId.value });
}

export function normalizeQuestionReplyPayload(payload: unknown, welinkSessionId?: string): NormalizeResult<QuestionReplyPayload> {
  if (!isRecord(payload)) {
    return invalidFieldType('payload', 'payload', 'an object', 'invoke', 'question_reply', welinkSessionId);
  }
  const toolSessionId = requireNonEmptyString(
    payload.toolSessionId,
    'payload',
    'payload.toolSessionId',
    'invoke',
    'question_reply',
    welinkSessionId,
  );
  if (!toolSessionId.ok) return toolSessionId;
  const answer = requireNonEmptyString(
    payload.answer,
    'payload',
    'payload.answer',
    'invoke',
    'question_reply',
    welinkSessionId,
  );
  if (!answer.ok) return answer;

  if (payload.toolCallId !== undefined) {
    const toolCallId = requireNonEmptyString(
      payload.toolCallId,
      'payload',
      'payload.toolCallId',
      'invoke',
      'question_reply',
      welinkSessionId,
    );
    if (!toolCallId.ok) return toolCallId;
    return ok({
      toolSessionId: toolSessionId.value,
      answer: answer.value,
      toolCallId: toolCallId.value,
    });
  }

  return ok({
    toolSessionId: toolSessionId.value,
    answer: answer.value,
  });
}

function normalizeInvokePayload(
  action: InvokeAction,
  payload: unknown,
  welinkSessionId?: string,
): NormalizeResult<NormalizedInvokeMessage> {
  switch (action) {
    case 'chat': {
      const normalized = normalizeChatPayload(payload, welinkSessionId);
      if (!normalized.ok) return normalized;
      return ok({ type: 'invoke', action, payload: normalized.value, welinkSessionId });
    }
    case 'create_session': {
      const normalized = normalizeCreateSessionPayload(payload, welinkSessionId);
      if (!normalized.ok) return normalized;
      return ok({ type: 'invoke', action, payload: normalized.value, welinkSessionId });
    }
    case 'close_session': {
      const normalized = normalizeCloseSessionPayload(payload, welinkSessionId);
      if (!normalized.ok) return normalized;
      return ok({ type: 'invoke', action, payload: normalized.value, welinkSessionId });
    }
    case 'permission_reply': {
      const normalized = normalizePermissionReplyPayload(payload, welinkSessionId);
      if (!normalized.ok) return normalized;
      return ok({ type: 'invoke', action, payload: normalized.value, welinkSessionId });
    }
    case 'abort_session': {
      const normalized = normalizeAbortSessionPayload(payload, welinkSessionId);
      if (!normalized.ok) return normalized;
      return ok({ type: 'invoke', action, payload: normalized.value, welinkSessionId });
    }
    case 'question_reply': {
      const normalized = normalizeQuestionReplyPayload(payload, welinkSessionId);
      if (!normalized.ok) return normalized;
      return ok({ type: 'invoke', action, payload: normalized.value, welinkSessionId });
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

  const messageTypeValue = raw.type;

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

  const welinkSessionId = typeof raw.welinkSessionId === 'string' ? raw.welinkSessionId : undefined;

  if (messageTypeValue === 'status_query') {
    return ok({
      type: 'status_query',
    });
  }

  const actionValue = raw.action;
  if (typeof actionValue !== 'string') {
    const error = errorOf(missingRequiredField('payload', 'action', 'invoke', undefined, welinkSessionId));
    logDownstreamNormalizationFailure(logger, raw, error);
    return fail(error);
  }
  if (!isSupportedInvokeAction(actionValue)) {
    const error = errorOf(unsupportedAction(actionValue, welinkSessionId));
    logDownstreamNormalizationFailure(logger, raw, error);
    return fail(error);
  }

  const normalized = normalizeInvokePayload(actionValue, raw.payload, welinkSessionId);
  if (!normalized.ok) {
    logDownstreamNormalizationFailure(logger, raw, normalized.error);
    return normalized;
  }

  return ok(normalized.value as NormalizedDownstreamMessage);
}
