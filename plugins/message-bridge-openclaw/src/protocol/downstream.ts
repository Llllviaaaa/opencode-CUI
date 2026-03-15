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
  messageType?: string;
  action?: string;
}

export type NormalizeResult<T> = { ok: true; value: T } | { ok: false; error: DownstreamNormalizationError };

function ok<T>(value: T): NormalizeResult<T> {
  return { ok: true, value };
}

function fail(message: string, code: string, messageType?: string, action?: string): NormalizeResult<never> {
  return { ok: false, error: { code, message, messageType, action } };
}

function normalizeChatPayload(payload: unknown): NormalizeResult<ChatPayload> {
  if (!isRecord(payload)) {
    return fail("payload must be an object", "invalid_payload", "invoke", "chat");
  }
  const toolSessionId = asString(payload.toolSessionId);
  const text = asString(payload.text);
  if (!toolSessionId || !text) {
    return fail("chat requires toolSessionId and text", "missing_required_field", "invoke", "chat");
  }
  return ok({ toolSessionId, text });
}

function normalizeCreateSessionPayload(payload: unknown): NormalizeResult<CreateSessionPayload> {
  if (!isRecord(payload)) {
    return fail("payload must be an object", "invalid_payload", "invoke", "create_session");
  }
  return ok({
    sessionId: asString(payload.sessionId),
    metadata: isRecord(payload.metadata) ? payload.metadata : undefined,
  });
}

function normalizeCloseSessionPayload(payload: unknown): NormalizeResult<CloseSessionPayload> {
  if (!isRecord(payload)) {
    return fail("payload must be an object", "invalid_payload", "invoke", "close_session");
  }
  const toolSessionId = asString(payload.toolSessionId);
  if (!toolSessionId) {
    return fail("close_session requires toolSessionId", "missing_required_field", "invoke", "close_session");
  }
  return ok({ toolSessionId });
}

function normalizeAbortSessionPayload(payload: unknown): NormalizeResult<AbortSessionPayload> {
  if (!isRecord(payload)) {
    return fail("payload must be an object", "invalid_payload", "invoke", "abort_session");
  }
  const toolSessionId = asString(payload.toolSessionId);
  if (!toolSessionId) {
    return fail("abort_session requires toolSessionId", "missing_required_field", "invoke", "abort_session");
  }
  return ok({ toolSessionId });
}

function normalizePermissionReplyPayload(payload: unknown): NormalizeResult<PermissionReplyPayload> {
  if (!isRecord(payload)) {
    return fail("payload must be an object", "invalid_payload", "invoke", "permission_reply");
  }
  const toolSessionId = asString(payload.toolSessionId);
  const permissionId = asString(payload.permissionId);
  if (!toolSessionId || !permissionId || !hasKey(payload, "response")) {
    return fail("permission_reply requires toolSessionId, permissionId, response", "missing_required_field", "invoke", "permission_reply");
  }
  const response = payload.response;
  if (response !== "once" && response !== "always" && response !== "reject") {
    return fail('permission_reply response must be "once", "always", or "reject"', "invalid_payload", "invoke", "permission_reply");
  }
  return ok({ toolSessionId, permissionId, response });
}

function normalizeQuestionReplyPayload(payload: unknown): NormalizeResult<QuestionReplyPayload> {
  if (!isRecord(payload)) {
    return fail("payload must be an object", "invalid_payload", "invoke", "question_reply");
  }
  const toolSessionId = asString(payload.toolSessionId);
  const answer = asString(payload.answer);
  if (!toolSessionId || !answer) {
    return fail("question_reply requires toolSessionId and answer", "missing_required_field", "invoke", "question_reply");
  }
  if (hasKey(payload, "toolCallId") && !asString(payload.toolCallId)) {
    return fail("question_reply toolCallId must be a non-empty string when provided", "invalid_payload", "invoke", "question_reply");
  }
  return ok({ toolSessionId, answer, toolCallId: asString(payload.toolCallId) });
}

function normalizeInvoke(message: Record<string, unknown>): NormalizeResult<InvokeMessage> {
  const action = asString(message.action);
  if (!action || !INVOKE_ACTIONS.includes(action as InvokeAction)) {
    return fail(`unsupported action: ${String(message.action)}`, "unsupported_action", "invoke", action);
  }

  const welinkSessionId = asString(message.welinkSessionId);
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
        return fail("create_session requires welinkSessionId", "missing_required_field", "invoke", "create_session");
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

  return fail(`unsupported action: ${action}`, "unsupported_action", "invoke", action);
}

export function normalizeDownstreamMessage(message: unknown): NormalizeResult<DownstreamMessage> {
  if (!isRecord(message) || !asString(message.type)) {
    return fail("message type is required", "missing_required_field");
  }

  const messageType = message.type as string;
  if (!DOWNSTREAM_MESSAGE_TYPES.includes(messageType as (typeof DOWNSTREAM_MESSAGE_TYPES)[number])) {
    return fail(`unsupported message type: ${messageType}`, "unsupported_message", messageType);
  }

  if (messageType === "status_query") {
    return ok({ type: "status_query" });
  }

  return normalizeInvoke(message);
}
