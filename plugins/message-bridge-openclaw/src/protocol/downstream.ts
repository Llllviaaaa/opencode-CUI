import type {
  AbortSessionPayload,
  ChatPayload,
  CloseSessionPayload,
  CreateSessionPayload,
  DownstreamMessage,
  InvokeAction,
  InvokeMessage,
  PermissionReplyPayload,
  QuestionReplyPayload,
} from "../contracts/downstream.js";
import { DOWNSTREAM_MESSAGE_TYPES, INVOKE_ACTIONS } from "../contracts/downstream.js";
import type { BridgeLogger } from "../types.js";

function isRecord(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === "object";
}

function asString(value: unknown): string | undefined {
  return typeof value === "string" && value.trim() ? value : undefined;
}

function hasKey(record: Record<string, unknown>, key: string): boolean {
  return Object.prototype.hasOwnProperty.call(record, key);
}

export interface DownstreamNormalizationError {
  code: string;
  message: string;
  stage: "message" | "payload";
  field: string;
  messageType?: string;
  action?: string;
  welinkSessionId?: string;
}

export type NormalizeResult<T> = { ok: true; value: T } | { ok: false; error: DownstreamNormalizationError };

function ok<T>(value: T): NormalizeResult<T> {
  return { ok: true, value };
}

function fail(params: {
  message: string;
  code: string;
  stage: "message" | "payload";
  field: string;
  messageType?: string;
  action?: string;
  welinkSessionId?: string;
}): NormalizeResult<never> {
  return {
    ok: false,
    error: {
      code: params.code,
      message: params.message,
      stage: params.stage,
      field: params.field,
      messageType: params.messageType,
      action: params.action,
      welinkSessionId: params.welinkSessionId,
    },
  };
}

function logDebug(logger: BridgeLogger | undefined, message: string, meta?: Record<string, unknown>): void {
  if (!logger) {
    return;
  }
  if (logger.debug) {
    logger.debug(message, meta);
    return;
  }
  logger.info(message, meta);
}

function buildMessagePreview(raw: unknown): Record<string, unknown> {
  if (!isRecord(raw)) {
    return { kind: typeof raw };
  }
  return {
    type: typeof raw.type === "string" ? raw.type : undefined,
    keys: Object.keys(raw).slice(0, 8),
  };
}

function extractToolSessionId(payload: unknown): string | undefined {
  return isRecord(payload) ? asString(payload.toolSessionId) : undefined;
}

function logDownstreamNormalizationFailure(
  logger: BridgeLogger | undefined,
  raw: unknown,
  error: DownstreamNormalizationError,
): void {
  if (!logger) {
    return;
  }
  logger.warn("downstream.normalization_failed", {
    stage: error.stage,
    errorCode: error.code,
    field: error.field,
    message: error.message,
    messageType: error.messageType,
    action: error.action,
    welinkSessionId: error.welinkSessionId,
    messagePreview: buildMessagePreview(raw),
  });
}

function normalizeChatPayload(payload: unknown): NormalizeResult<ChatPayload> {
  if (!isRecord(payload)) {
    return fail({
      message: "payload must be an object",
      code: "invalid_payload",
      stage: "payload",
      field: "payload",
      messageType: "invoke",
      action: "chat",
    });
  }
  const toolSessionId = asString(payload.toolSessionId);
  const text = asString(payload.text);
  if (!toolSessionId || !text) {
    return fail({
      message: "chat requires toolSessionId and text",
      code: "missing_required_field",
      stage: "payload",
      field: !toolSessionId ? "payload.toolSessionId" : "payload.text",
      messageType: "invoke",
      action: "chat",
    });
  }
  return ok({ toolSessionId, text });
}

function normalizeCreateSessionPayload(payload: unknown): NormalizeResult<CreateSessionPayload> {
  if (!isRecord(payload)) {
    return fail({
      message: "payload must be an object",
      code: "invalid_payload",
      stage: "payload",
      field: "payload",
      messageType: "invoke",
      action: "create_session",
    });
  }
  return ok({
    sessionId: asString(payload.sessionId),
    metadata: isRecord(payload.metadata) ? payload.metadata : undefined,
  });
}

function normalizeCloseSessionPayload(payload: unknown): NormalizeResult<CloseSessionPayload> {
  if (!isRecord(payload)) {
    return fail({
      message: "payload must be an object",
      code: "invalid_payload",
      stage: "payload",
      field: "payload",
      messageType: "invoke",
      action: "close_session",
    });
  }
  const toolSessionId = asString(payload.toolSessionId);
  if (!toolSessionId) {
    return fail({
      message: "close_session requires toolSessionId",
      code: "missing_required_field",
      stage: "payload",
      field: "payload.toolSessionId",
      messageType: "invoke",
      action: "close_session",
    });
  }
  return ok({ toolSessionId });
}

function normalizeAbortSessionPayload(payload: unknown): NormalizeResult<AbortSessionPayload> {
  if (!isRecord(payload)) {
    return fail({
      message: "payload must be an object",
      code: "invalid_payload",
      stage: "payload",
      field: "payload",
      messageType: "invoke",
      action: "abort_session",
    });
  }
  const toolSessionId = asString(payload.toolSessionId);
  if (!toolSessionId) {
    return fail({
      message: "abort_session requires toolSessionId",
      code: "missing_required_field",
      stage: "payload",
      field: "payload.toolSessionId",
      messageType: "invoke",
      action: "abort_session",
    });
  }
  return ok({ toolSessionId });
}

function normalizePermissionReplyPayload(payload: unknown): NormalizeResult<PermissionReplyPayload> {
  if (!isRecord(payload)) {
    return fail({
      message: "payload must be an object",
      code: "invalid_payload",
      stage: "payload",
      field: "payload",
      messageType: "invoke",
      action: "permission_reply",
    });
  }
  const toolSessionId = asString(payload.toolSessionId);
  const permissionId = asString(payload.permissionId);
  if (!toolSessionId || !permissionId || !hasKey(payload, "response")) {
    return fail({
      message: "permission_reply requires toolSessionId, permissionId, response",
      code: "missing_required_field",
      stage: "payload",
      field: !toolSessionId ? "payload.toolSessionId" : !permissionId ? "payload.permissionId" : "payload.response",
      messageType: "invoke",
      action: "permission_reply",
    });
  }
  const response = payload.response;
  if (response !== "once" && response !== "always" && response !== "reject") {
    return fail({
      message: 'permission_reply response must be "once", "always", or "reject"',
      code: "invalid_payload",
      stage: "payload",
      field: "payload.response",
      messageType: "invoke",
      action: "permission_reply",
    });
  }
  return ok({ toolSessionId, permissionId, response });
}

function normalizeQuestionReplyPayload(payload: unknown): NormalizeResult<QuestionReplyPayload> {
  if (!isRecord(payload)) {
    return fail({
      message: "payload must be an object",
      code: "invalid_payload",
      stage: "payload",
      field: "payload",
      messageType: "invoke",
      action: "question_reply",
    });
  }
  const toolSessionId = asString(payload.toolSessionId);
  const answer = asString(payload.answer);
  if (!toolSessionId || !answer) {
    return fail({
      message: "question_reply requires toolSessionId and answer",
      code: "missing_required_field",
      stage: "payload",
      field: !toolSessionId ? "payload.toolSessionId" : "payload.answer",
      messageType: "invoke",
      action: "question_reply",
    });
  }
  if (hasKey(payload, "toolCallId") && !asString(payload.toolCallId)) {
    return fail({
      message: "question_reply toolCallId must be a non-empty string when provided",
      code: "invalid_payload",
      stage: "payload",
      field: "payload.toolCallId",
      messageType: "invoke",
      action: "question_reply",
    });
  }
  return ok({ toolSessionId, answer, toolCallId: asString(payload.toolCallId) });
}

function normalizeInvoke(message: Record<string, unknown>): NormalizeResult<InvokeMessage> {
  const action = asString(message.action);
  const welinkSessionId = asString(message.welinkSessionId);
  if (!action || !INVOKE_ACTIONS.includes(action as InvokeAction)) {
    return fail({
      message: `unsupported action: ${String(message.action)}`,
      code: "unsupported_action",
      stage: "payload",
      field: "action",
      messageType: "invoke",
      action,
      welinkSessionId,
    });
  }

  const base = {
    type: "invoke" as const,
    welinkSessionId,
    action,
  };

  switch (action) {
    case "chat": {
      const payload = normalizeChatPayload(message.payload);
      return payload.ok ? ok({ ...base, action, payload: payload.value }) : payload;
    }
    case "create_session": {
      if (!welinkSessionId) {
        return fail({
          message: "create_session requires welinkSessionId",
          code: "missing_required_field",
          stage: "payload",
          field: "welinkSessionId",
          messageType: "invoke",
          action: "create_session",
          welinkSessionId,
        });
      }
      const payload = normalizeCreateSessionPayload(message.payload);
      return payload.ok
        ? ok({
            type: "invoke",
            welinkSessionId,
            action,
            payload: payload.value,
          })
        : payload;
    }
    case "close_session": {
      const payload = normalizeCloseSessionPayload(message.payload);
      return payload.ok ? ok({ ...base, action, payload: payload.value }) : payload;
    }
    case "abort_session": {
      const payload = normalizeAbortSessionPayload(message.payload);
      return payload.ok ? ok({ ...base, action, payload: payload.value }) : payload;
    }
    case "permission_reply": {
      const payload = normalizePermissionReplyPayload(message.payload);
      return payload.ok ? ok({ ...base, action, payload: payload.value }) : payload;
    }
    case "question_reply": {
      const payload = normalizeQuestionReplyPayload(message.payload);
      return payload.ok ? ok({ ...base, action, payload: payload.value }) : payload;
    }
  }

  return fail({
    message: `unsupported action: ${action}`,
    code: "unsupported_action",
    stage: "payload",
    field: "action",
    messageType: "invoke",
    action,
    welinkSessionId,
  });
}

export function normalizeDownstreamMessage(message: unknown, logger?: BridgeLogger): NormalizeResult<DownstreamMessage> {
  if (!isRecord(message) || !asString(message.type)) {
    const result = fail({
      message: "message type is required",
      code: "missing_required_field",
      stage: "message",
      field: "type",
    });
    if (!result.ok) {
      logDownstreamNormalizationFailure(logger, message, result.error);
    }
    return result;
  }

  const messageType = message.type as string;
  if (!DOWNSTREAM_MESSAGE_TYPES.includes(messageType as (typeof DOWNSTREAM_MESSAGE_TYPES)[number])) {
    const result = fail({
      message: `unsupported message type: ${messageType}`,
      code: "unsupported_message",
      stage: "message",
      field: "type",
      messageType,
    });
    if (!result.ok) {
      logDownstreamNormalizationFailure(logger, message, result.error);
    }
    return result;
  }

  if (messageType === "status_query") {
    logDebug(logger, "downstream.normalization_succeeded", { messageType: "status_query" });
    return ok({ type: "status_query" });
  }

  const result = normalizeInvoke(message);
  if (!result.ok) {
    const enrichedError = {
      ...result.error,
      welinkSessionId: result.error.welinkSessionId ?? asString(message.welinkSessionId),
    };
    logDownstreamNormalizationFailure(logger, message, enrichedError);
    return { ok: false, error: enrichedError };
  }
  logDebug(logger, "downstream.normalization_succeeded", {
    messageType: result.value.type,
    action: result.value.action,
    welinkSessionId: result.value.welinkSessionId,
    toolSessionId: extractToolSessionId(result.value.payload),
  });
  return result;
}
