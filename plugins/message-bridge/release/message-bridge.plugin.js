// @bun
// src/runtime/BridgeRuntime.ts
import { randomUUID as randomUUID4 } from "crypto";
import os from "os";

// src/types/common.ts
var CONNECTION_STATES = ["DISCONNECTED", "CONNECTING", "CONNECTED", "READY"];
function stateToErrorCode(state) {
  switch (state) {
    case CONNECTION_STATES[0]:
    case CONNECTION_STATES[1]:
      return "GATEWAY_UNREACHABLE";
    case CONNECTION_STATES[2]:
    case CONNECTION_STATES[3]:
      return "AGENT_NOT_READY";
  }
}
var AGENT_ID_PREFIX = "bridge-";
// src/utils/error.ts
function isRecord(value) {
  return value !== null && typeof value === "object";
}
function stringifyScalar(value) {
  if (typeof value === "string" && value.trim()) {
    return value;
  }
  if (typeof value === "number" || typeof value === "boolean" || typeof value === "bigint") {
    return String(value);
  }
  if (typeof value === "symbol") {
    return String(value);
  }
  return;
}
function getConstructorName(value) {
  if (!isRecord(value)) {
    return;
  }
  const ctor = value.constructor;
  if (typeof ctor === "function" && typeof ctor.name === "string" && ctor.name) {
    return ctor.name;
  }
  return;
}
function safeStringify(value) {
  if (typeof value === "string") {
    return value;
  }
  const seen = new WeakSet;
  try {
    const serialized = JSON.stringify(value, (_key, currentValue) => {
      if (typeof currentValue === "bigint") {
        return String(currentValue);
      }
      if (typeof currentValue === "symbol") {
        return String(currentValue);
      }
      if (typeof currentValue === "function") {
        return `[Function ${currentValue.name || "anonymous"}]`;
      }
      if (currentValue && typeof currentValue === "object") {
        if (seen.has(currentValue)) {
          return "[Circular]";
        }
        seen.add(currentValue);
      }
      return currentValue;
    });
    return serialized ?? String(value);
  } catch {
    return "[unserializable error object]";
  }
}
function getErrorDetails(error) {
  if (error instanceof Error) {
    const typedError = error;
    return {
      message: error.message || error.name || "Unknown error",
      name: error.name || undefined,
      code: stringifyScalar(typedError.code),
      type: getConstructorName(error),
      stack: typeof error.stack === "string" ? error.stack : undefined,
      causeMessage: typedError.cause !== undefined ? getErrorMessage(typedError.cause) : undefined,
      rawType: getConstructorName(error) ?? "Error"
    };
  }
  if (typeof error === "string") {
    return { message: error, rawType: "string" };
  }
  if (error === null) {
    return { message: "null", rawType: "null" };
  }
  if (error === undefined) {
    return { message: "undefined", rawType: "undefined" };
  }
  if (typeof error !== "object") {
    return {
      message: String(error),
      rawType: typeof error
    };
  }
  const record = error;
  const nestedError = record.error !== undefined && record.error !== error ? getErrorDetails(record.error) : undefined;
  const directMessage = stringifyScalar(record.message);
  const directName = stringifyScalar(record.name);
  const directCode = stringifyScalar(record.code);
  const directType = stringifyScalar(record.type);
  const constructorName = getConstructorName(record);
  return {
    message: directMessage ?? nestedError?.message ?? safeStringify(error),
    name: directName ?? nestedError?.name,
    code: directCode ?? nestedError?.code,
    type: directType ?? nestedError?.type ?? (constructorName !== "Object" ? constructorName : undefined),
    causeMessage: record.cause !== undefined ? getErrorMessage(record.cause) : nestedError?.causeMessage,
    rawType: constructorName ?? "object"
  };
}
function getErrorMessage(error) {
  return getErrorDetails(error).message;
}
function getErrorDetailsForLog(error) {
  const details = getErrorDetails(error);
  const logDetails = {
    errorDetail: details.message
  };
  if (details.name) {
    logDetails.errorName = details.name;
  }
  if (details.code) {
    logDetails.sourceErrorCode = details.code;
  }
  if (details.type) {
    logDetails.errorType = details.type;
  }
  if (details.causeMessage) {
    logDetails.causeMessage = details.causeMessage;
  }
  if (details.rawType) {
    logDetails.rawType = details.rawType;
  }
  return logDetails;
}

// src/types/sdk.ts
function isOpencodeClient(client) {
  if (!client || typeof client !== "object") {
    return false;
  }
  const c = client;
  return !!c.session && typeof c.session === "object" && typeof c.session.create === "function" && typeof c.session.abort === "function" && typeof c.session.prompt === "function" && typeof c.postSessionIdPermissionsPermissionId === "function";
}
async function safeExecute(promise, errorMapper) {
  try {
    const data = await promise;
    return { success: true, data };
  } catch (error) {
    const errorMessage = errorMapper ? errorMapper(error) : getErrorMessage(error);
    return { success: false, error: errorMessage };
  }
}
function hasError(result) {
  return result !== null && typeof result === "object" && "error" in result && result.error !== undefined;
}
// src/contracts/upstream-events.ts
var SUPPORTED_UPSTREAM_EVENT_TYPES = [
  "message.updated",
  "message.part.updated",
  "message.part.delta",
  "message.part.removed",
  "session.status",
  "session.idle",
  "session.updated",
  "session.error",
  "permission.updated",
  "permission.asked",
  "question.asked"
];
var SUPPORTED_UPSTREAM_EVENT_TYPE_SET = new Set(SUPPORTED_UPSTREAM_EVENT_TYPES);
function isSupportedUpstreamEventType(value) {
  return SUPPORTED_UPSTREAM_EVENT_TYPE_SET.has(value);
}
var DEFAULT_EVENT_ALLOWLIST = [...SUPPORTED_UPSTREAM_EVENT_TYPES];
// src/contracts/downstream-messages.ts
var DOWNSTREAM_MESSAGE_TYPES = ["invoke", "status_query"];
var INVOKE_ACTIONS = [
  "chat",
  "create_session",
  "close_session",
  "permission_reply",
  "abort_session",
  "question_reply"
];
var ACTION_NAMES = [...INVOKE_ACTIONS, "status_query"];
// src/action/ChatAction.ts
class ChatAction {
  name = "chat";
  formatUnknownError(error) {
    if (error instanceof Error) {
      return error.message;
    }
    if (typeof error === "string") {
      return error;
    }
    try {
      return JSON.stringify(error);
    } catch {
      return String(error);
    }
  }
  getErrorMessageFromResult(data) {
    if (data && typeof data === "object" && "error" in data) {
      const errorField = data.error;
      if (errorField && typeof errorField === "object" && "message" in errorField) {
        const messageField = errorField.message;
        if (typeof messageField === "string") {
          return messageField;
        }
        return String(messageField);
      }
      return this.formatUnknownError(errorField);
    }
    return "Unknown error";
  }
  async sendPrompt(client, toolSessionId, text) {
    const executionResult = await safeExecute(client.session.prompt({
      path: { id: toolSessionId },
      body: { parts: [{ type: "text", text }] }
    }), (error) => this.formatUnknownError(error));
    if (executionResult.success) {
      if (!hasError(executionResult.data)) {
        return { success: true, data: executionResult.data };
      }
      const sdkError = this.getErrorMessageFromResult(executionResult.data);
      return { success: false, error: `Failed to send message: ${sdkError || "Unknown error"}` };
    }
    return { success: false, error: `Failed to send message: ${executionResult.error}` };
  }
  async execute(payload, context) {
    const startedAt = Date.now();
    context.logger?.info("action.chat.started", {
      toolSessionId: payload.toolSessionId,
      messageLength: payload.text.length
    });
    if (context.connectionState !== "READY") {
      context.logger?.warn("action.chat.rejected_state", { state: context.connectionState });
      return {
        success: false,
        errorCode: stateToErrorCode(context.connectionState),
        errorMessage: `Agent not ready. Current state: ${context.connectionState}`
      };
    }
    if (!isOpencodeClient(context.client)) {
      context.logger?.error("action.chat.invalid_client");
      return {
        success: false,
        errorCode: "SDK_UNREACHABLE",
        errorMessage: "OpenCode client not available or invalid in context"
      };
    }
    const client = context.client;
    try {
      const executionResult = await this.sendPrompt(client, payload.toolSessionId, payload.text);
      if (executionResult.success) {
        return {
          success: true
        };
      }
      context.logger?.error("action.chat.failed", {
        error: executionResult.error,
        latencyMs: Date.now() - startedAt
      });
      return {
        success: false,
        errorCode: "SDK_UNREACHABLE",
        errorMessage: executionResult.error
      };
    } catch (error) {
      const errorCode = this.errorMapper(error);
      const errorMessage = error instanceof Error ? error.message : String(error);
      context.logger?.error("action.chat.exception", {
        error: errorMessage,
        errorCode,
        latencyMs: Date.now() - startedAt
      });
      return {
        success: false,
        errorCode,
        errorMessage
      };
    } finally {
      context.logger?.debug("action.chat.finished", { latencyMs: Date.now() - startedAt });
    }
  }
  errorMapper(error) {
    if (error instanceof Error) {
      const message = error.message.toLowerCase();
      if (message.includes("timeout") || message.includes("timed out")) {
        return "SDK_TIMEOUT";
      } else if (message.includes("unreachable") || message.includes("connect") || message.includes("connection")) {
        return "SDK_UNREACHABLE";
      } else if (message.includes("not found") || message.includes("session") && message.includes("not found")) {
        return "INVALID_PAYLOAD";
      } else if (message.includes("abort") || message.includes("cancelled")) {
        return "INVALID_PAYLOAD";
      }
    } else if (typeof error === "string") {
      const message = error.toLowerCase();
      if (message.includes("timeout")) {
        return "SDK_TIMEOUT";
      } else if (message.includes("unreachable") || message.includes("connect")) {
        return "SDK_UNREACHABLE";
      }
    }
    return "SDK_UNREACHABLE";
  }
}

// src/action/CreateSessionAction.ts
class CreateSessionAction {
  name = "create_session";
  async execute(payload, context) {
    const startedAt = Date.now();
    context.logger?.info("action.create_session.started", {
      payloadKeys: Object.keys(payload ?? {})
    });
    try {
      if (context.connectionState !== "READY") {
        context.logger?.warn("action.create_session.rejected_state", { state: context.connectionState });
        return {
          success: false,
          errorCode: stateToErrorCode(context.connectionState),
          errorMessage: `Agent not ready. Current state: ${context.connectionState}`
        };
      }
      if (!isOpencodeClient(context.client)) {
        context.logger?.error("action.create_session.invalid_client");
        return {
          success: false,
          errorCode: "SDK_UNREACHABLE",
          errorMessage: "Valid OpenCode client not available in context"
        };
      }
      const executionResult = await safeExecute(context.client.session.create({ body: payload }), (error) => `Create session failed: ${getErrorMessage(error)}`);
      if (executionResult.success) {
        if (!hasError(executionResult.data)) {
          const root = executionResult.data;
          const nested = root?.data;
          const sessionObject = nested ?? root ?? {};
          const pick = (value) => typeof value === "string" && value.trim() ? value : undefined;
          const returnedSessionId = pick(root?.sessionId) ?? pick(root?.id) ?? pick(nested?.sessionId) ?? pick(nested?.id);
          return {
            success: true,
            data: {
              sessionId: returnedSessionId,
              session: sessionObject
            }
          };
        }
        const errorField = executionResult.data && typeof executionResult.data === "object" && "error" in executionResult.data ? executionResult.data.error : undefined;
        const errorMessage = errorField !== undefined ? getErrorMessage(errorField) : "Unknown error";
        context.logger?.error("action.create_session.sdk_error_payload", {
          requestedSessionId: payload.sessionId,
          payloadKeys: Object.keys(payload ?? {}),
          error: errorMessage,
          ...errorField !== undefined ? getErrorDetailsForLog(errorField) : {},
          latencyMs: Date.now() - startedAt
        });
        return {
          success: false,
          errorCode: "SDK_UNREACHABLE",
          errorMessage: `Failed to create session: ${errorMessage}`
        };
      }
      context.logger?.error("action.create_session.failed", {
        requestedSessionId: payload.sessionId,
        payloadKeys: Object.keys(payload ?? {}),
        error: executionResult.error,
        latencyMs: Date.now() - startedAt
      });
      return {
        success: false,
        errorCode: "SDK_UNREACHABLE",
        errorMessage: executionResult.error
      };
    } catch (error) {
      const errorCode = this.errorMapper(error);
      const errorMessage = getErrorMessage(error);
      context.logger?.error("action.create_session.exception", {
        requestedSessionId: payload.sessionId,
        payloadKeys: Object.keys(payload ?? {}),
        error: errorMessage,
        errorCode,
        ...getErrorDetailsForLog(error),
        latencyMs: Date.now() - startedAt
      });
      return {
        success: false,
        errorCode,
        errorMessage
      };
    } finally {
      context.logger?.debug("action.create_session.finished", { latencyMs: Date.now() - startedAt });
    }
  }
  errorMapper(error) {
    if (error instanceof Error) {
      const message = error.message.toLowerCase();
      if (message.includes("timeout") || message.includes("network")) {
        return "SDK_TIMEOUT";
      } else if (message.includes("unreachable") || message.includes("connect")) {
        return "SDK_UNREACHABLE";
      } else if (message.includes("invalid") || message.includes("bad request")) {
        return "INVALID_PAYLOAD";
      }
    }
    return "SDK_UNREACHABLE";
  }
}

// src/action/CloseSessionAction.ts
class CloseSessionAction {
  name = "close_session";
  async execute(payload, context) {
    const startedAt = Date.now();
    context.logger?.info("action.close_session.started", {
      toolSessionId: payload.toolSessionId
    });
    try {
      if (context.connectionState !== "READY") {
        context.logger?.warn("action.close_session.rejected_state", { state: context.connectionState });
        return {
          success: false,
          errorCode: stateToErrorCode(context.connectionState),
          errorMessage: `Agent not ready. Current state: ${context.connectionState}`
        };
      }
      if (!isOpencodeClient(context.client)) {
        context.logger?.error("action.close_session.invalid_client");
        return {
          success: false,
          errorCode: "SDK_UNREACHABLE",
          errorMessage: "Valid OpenCode client not available in context"
        };
      }
      const client = context.client;
      if (typeof client.session.delete !== "function") {
        context.logger?.error("action.close_session.delete_unavailable");
        return {
          success: false,
          errorCode: "SDK_UNREACHABLE",
          errorMessage: "SDK session.delete is not available"
        };
      }
      const executionResult = await safeExecute(client.session.delete({
        path: { id: payload.toolSessionId }
      }), (error) => `Close session failed: ${error instanceof Error ? error.message : String(error)}`);
      if (executionResult.success) {
        if (!hasError(executionResult.data)) {
          return {
            success: true,
            data: { sessionId: payload.toolSessionId, closed: true }
          };
        }
        let errorMessage = "Unknown error";
        if (executionResult.data && typeof executionResult.data === "object" && "error" in executionResult.data) {
          const errorField = executionResult.data.error;
          if (errorField && typeof errorField === "object" && errorField !== null && "message" in errorField) {
            const messageField = errorField.message;
            errorMessage = typeof messageField === "string" ? messageField : String(messageField) || "Unknown error";
          } else {
            errorMessage = String(errorField) || "Unknown error";
          }
        }
        context.logger?.error("action.close_session.sdk_error_payload", {
          error: errorMessage,
          latencyMs: Date.now() - startedAt
        });
        return {
          success: false,
          errorCode: "SDK_UNREACHABLE",
          errorMessage: `Failed to close session: ${errorMessage}`
        };
      }
      context.logger?.error("action.close_session.failed", {
        error: executionResult.error,
        latencyMs: Date.now() - startedAt
      });
      return {
        success: false,
        errorCode: "SDK_UNREACHABLE",
        errorMessage: executionResult.error
      };
    } catch (error) {
      const errorCode = this.errorMapper(error);
      const errorMessage = error instanceof Error ? error.message : String(error);
      context.logger?.error("action.close_session.exception", {
        error: errorMessage,
        errorCode,
        latencyMs: Date.now() - startedAt
      });
      return {
        success: false,
        errorCode,
        errorMessage
      };
    } finally {
      context.logger?.debug("action.close_session.finished", { latencyMs: Date.now() - startedAt });
    }
  }
  errorMapper(error) {
    if (error instanceof Error) {
      const message = error.message.toLowerCase();
      if (message.includes("timeout") || message.includes("network")) {
        return "SDK_TIMEOUT";
      } else if (message.includes("unreachable") || message.includes("connect")) {
        return "SDK_UNREACHABLE";
      } else if (message.includes("not found") || message.includes("session")) {
        return "INVALID_PAYLOAD";
      }
    }
    return "SDK_UNREACHABLE";
  }
}

// src/action/PermissionReplyAction.ts
class PermissionReplyAction {
  name = "permission_reply";
  async execute(payload, context) {
    const startedAt = Date.now();
    context.logger?.info("action.permission_reply.started", {
      permissionId: payload.permissionId,
      toolSessionId: payload.toolSessionId,
      response: payload.response
    });
    try {
      if (context.connectionState !== "READY") {
        context.logger?.warn("action.permission_reply.rejected_state", { state: context.connectionState });
        return {
          success: false,
          errorCode: stateToErrorCode(context.connectionState),
          errorMessage: `Agent not ready. Current state: ${context.connectionState}`
        };
      }
      if (!isOpencodeClient(context.client)) {
        context.logger?.error("action.permission_reply.invalid_client");
        return {
          success: false,
          errorCode: "SDK_UNREACHABLE",
          errorMessage: "Valid OpenCode client not available in context"
        };
      }
      const executionResult = await safeExecute(context.client.postSessionIdPermissionsPermissionId({
        path: { id: payload.toolSessionId, permissionID: payload.permissionId },
        body: { response: payload.response }
      }), (error) => `Permission reply failed: ${error instanceof Error ? error.message : String(error)}`);
      if (executionResult.success) {
        if (!hasError(executionResult.data)) {
          return {
            success: true,
            data: {
              permissionId: payload.permissionId,
              response: payload.response,
              applied: true
            }
          };
        }
        let errorMessage = "Unknown error";
        if (executionResult.data && typeof executionResult.data === "object" && "error" in executionResult.data) {
          const errorField = executionResult.data.error;
          if (errorField && typeof errorField === "object" && errorField !== null && "message" in errorField) {
            const messageField = errorField.message;
            errorMessage = typeof messageField === "string" ? messageField : String(messageField) || "Unknown error";
          } else {
            errorMessage = String(errorField) || "Unknown error";
          }
        }
        context.logger?.error("action.permission_reply.sdk_error_payload", {
          error: errorMessage,
          latencyMs: Date.now() - startedAt
        });
        return {
          success: false,
          errorCode: "SDK_UNREACHABLE",
          errorMessage: `Failed to reply to permission request: ${errorMessage}`
        };
      }
      context.logger?.error("action.permission_reply.failed", {
        error: executionResult.error,
        latencyMs: Date.now() - startedAt
      });
      return {
        success: false,
        errorCode: "SDK_UNREACHABLE",
        errorMessage: executionResult.error
      };
    } catch (error) {
      const errorCode = this.errorMapper(error);
      const errorMessage = error instanceof Error ? error.message : String(error);
      context.logger?.error("action.permission_reply.exception", {
        error: errorMessage,
        errorCode,
        latencyMs: Date.now() - startedAt
      });
      return {
        success: false,
        errorCode,
        errorMessage
      };
    } finally {
      context.logger?.debug("action.permission_reply.finished", { latencyMs: Date.now() - startedAt });
    }
  }
  errorMapper(error) {
    if (error instanceof Error) {
      const message = error.message.toLowerCase();
      if (message.includes("timeout") || message.includes("network")) {
        return "SDK_TIMEOUT";
      } else if (message.includes("unreachable") || message.includes("connect")) {
        return "SDK_UNREACHABLE";
      } else if (message.includes("invalid") || message.includes("permission")) {
        return "INVALID_PAYLOAD";
      }
    }
    return "SDK_UNREACHABLE";
  }
}

// src/action/StatusQueryAction.ts
class StatusQueryAction {
  name = "status_query";
  async execute(payload, context) {
    const startedAt = Date.now();
    context.logger?.debug("action.status_query.started", {
      state: context.connectionState
    });
    try {
      let opencodeOnline = false;
      const app = context.client?.app;
      if (app?.health) {
        try {
          await app.health();
          opencodeOnline = true;
        } catch {
          opencodeOnline = false;
        }
      }
      return {
        success: true,
        data: {
          opencodeOnline
        }
      };
    } catch (error) {
      const errorCode = this.errorMapper(error);
      const errorMessage = error instanceof Error ? error.message : String(error);
      context.logger?.error("action.status_query.exception", {
        error: errorMessage,
        errorCode,
        latencyMs: Date.now() - startedAt
      });
      return {
        success: false,
        errorCode,
        errorMessage
      };
    } finally {
      context.logger?.debug("action.status_query.finished", { latencyMs: Date.now() - startedAt });
    }
  }
  errorMapper(error) {
    if (error instanceof Error) {
      const message = error.message.toLowerCase();
      if (message.includes("timeout") || message.includes("network")) {
        return "SDK_TIMEOUT";
      } else if (message.includes("unreachable") || message.includes("connect")) {
        return "SDK_UNREACHABLE";
      }
    }
    return "SDK_UNREACHABLE";
  }
}

// src/action/AbortSessionAction.ts
class AbortSessionAction {
  name = "abort_session";
  async execute(payload, context) {
    const startedAt = Date.now();
    context.logger?.info("action.abort_session.started", {
      toolSessionId: payload.toolSessionId
    });
    try {
      if (context.connectionState !== "READY") {
        context.logger?.warn("action.abort_session.rejected_state", { state: context.connectionState });
        return {
          success: false,
          errorCode: stateToErrorCode(context.connectionState),
          errorMessage: `Agent not ready. Current state: ${context.connectionState}`
        };
      }
      if (!isOpencodeClient(context.client)) {
        context.logger?.error("action.abort_session.invalid_client");
        return {
          success: false,
          errorCode: "SDK_UNREACHABLE",
          errorMessage: "Valid OpenCode client not available in context"
        };
      }
      const executionResult = await safeExecute(context.client.session.abort({
        path: { id: payload.toolSessionId }
      }), (error) => `Abort session failed: ${getErrorMessage(error)}`);
      if (executionResult.success) {
        if (!hasError(executionResult.data)) {
          return {
            success: true,
            data: { sessionId: payload.toolSessionId, aborted: true }
          };
        }
        const errorField = executionResult.data && typeof executionResult.data === "object" && "error" in executionResult.data ? executionResult.data.error : undefined;
        const errorMessage = errorField !== undefined ? getErrorMessage(errorField) : "Unknown error";
        context.logger?.error("action.abort_session.sdk_error_payload", {
          toolSessionId: payload.toolSessionId,
          error: errorMessage,
          ...errorField !== undefined ? getErrorDetailsForLog(errorField) : {},
          latencyMs: Date.now() - startedAt
        });
        return {
          success: false,
          errorCode: "SDK_UNREACHABLE",
          errorMessage: `Failed to abort session: ${errorMessage}`
        };
      }
      context.logger?.error("action.abort_session.failed", {
        toolSessionId: payload.toolSessionId,
        error: executionResult.error,
        latencyMs: Date.now() - startedAt
      });
      return {
        success: false,
        errorCode: "SDK_UNREACHABLE",
        errorMessage: executionResult.error
      };
    } catch (error) {
      const errorCode = this.errorMapper(error);
      const errorMessage = getErrorMessage(error);
      context.logger?.error("action.abort_session.exception", {
        toolSessionId: payload.toolSessionId,
        error: errorMessage,
        errorCode,
        ...getErrorDetailsForLog(error),
        latencyMs: Date.now() - startedAt
      });
      return {
        success: false,
        errorCode,
        errorMessage
      };
    } finally {
      context.logger?.debug("action.abort_session.finished", { latencyMs: Date.now() - startedAt });
    }
  }
  errorMapper(error) {
    if (error instanceof Error) {
      const message = error.message.toLowerCase();
      if (message.includes("timeout") || message.includes("network")) {
        return "SDK_TIMEOUT";
      }
      if (message.includes("unreachable") || message.includes("connect")) {
        return "SDK_UNREACHABLE";
      }
      if (message.includes("not found") || message.includes("session")) {
        return "INVALID_PAYLOAD";
      }
    }
    return "SDK_UNREACHABLE";
  }
}

// src/action/QuestionReplyAction.ts
class QuestionReplyAction {
  name = "question_reply";
  readRecord(value) {
    return value !== null && typeof value === "object" ? value : undefined;
  }
  readString(value) {
    return typeof value === "string" && value.trim() ? value : undefined;
  }
  extractResultData(result) {
    const resultRecord = this.readRecord(result);
    if (!resultRecord) {
      return;
    }
    if ("data" in resultRecord) {
      return resultRecord.data;
    }
    return result;
  }
  async findPendingQuestionRequestId(context, toolSessionId, toolCallId) {
    const rawClient = this.readRecord(context.client?._client);
    const getFn = rawClient?.get;
    if (!getFn) {
      throw new Error("raw client GET unavailable on client");
    }
    const listResult = await getFn({ url: "/question" });
    const pendingQuestions = this.extractResultData(listResult);
    const requests = Array.isArray(pendingQuestions) ? pendingQuestions.filter((item) => item !== null && typeof item === "object") : [];
    const matchedRequests = requests.filter((request) => {
      const sessionID = this.readString(request.sessionID);
      if (sessionID !== toolSessionId) {
        return false;
      }
      if (!toolCallId) {
        return true;
      }
      const tool = this.readRecord(request.tool);
      return this.readString(tool?.callID) === toolCallId;
    });
    if (toolCallId) {
      return this.readString(matchedRequests[0]?.id);
    }
    if (matchedRequests.length !== 1) {
      return;
    }
    return this.readString(matchedRequests[0]?.id);
  }
  async execute(payload, context) {
    const startedAt = Date.now();
    context.logger?.info("action.question_reply.started", {
      toolSessionId: payload.toolSessionId,
      toolCallId: payload.toolCallId,
      answerLength: payload.answer.length
    });
    if (context.connectionState !== "READY") {
      context.logger?.warn("action.question_reply.rejected_state", { state: context.connectionState });
      return {
        success: false,
        errorCode: stateToErrorCode(context.connectionState),
        errorMessage: `Agent not ready. Current state: ${context.connectionState}`
      };
    }
    if (!isOpencodeClient(context.client)) {
      context.logger?.error("action.question_reply.invalid_client");
      return {
        success: false,
        errorCode: "SDK_UNREACHABLE",
        errorMessage: "OpenCode client not available or invalid in context"
      };
    }
    const client = context.client;
    try {
      const requestId = await this.findPendingQuestionRequestId(context, payload.toolSessionId, payload.toolCallId);
      if (!requestId) {
        return {
          success: false,
          errorCode: "INVALID_PAYLOAD",
          errorMessage: payload.toolCallId ? `Unable to resolve pending question request for toolSessionId=${payload.toolSessionId}, toolCallId=${payload.toolCallId}` : `Unable to resolve a unique pending question request for toolSessionId=${payload.toolSessionId}`
        };
      }
      const rawClient = this.readRecord(client._client);
      const postFn = rawClient?.post;
      if (!postFn) {
        return {
          success: false,
          errorCode: "SDK_UNREACHABLE",
          errorMessage: "raw client POST unavailable on client"
        };
      }
      await postFn({
        url: "/question/{requestID}/reply",
        path: { requestID: requestId },
        body: { answers: [[payload.answer]] },
        headers: {
          "Content-Type": "application/json"
        }
      });
      return {
        success: true,
        data: {
          requestId,
          replied: true
        }
      };
    } catch (error) {
      const errorCode = this.errorMapper(error);
      const errorMessage = getErrorMessage(error);
      context.logger?.error("action.question_reply.exception", {
        toolSessionId: payload.toolSessionId,
        toolCallId: payload.toolCallId,
        error: errorMessage,
        errorCode,
        ...getErrorDetailsForLog(error),
        latencyMs: Date.now() - startedAt
      });
      return {
        success: false,
        errorCode,
        errorMessage
      };
    } finally {
      context.logger?.debug("action.question_reply.finished", { latencyMs: Date.now() - startedAt });
    }
  }
  errorMapper(error) {
    if (error instanceof Error) {
      const message = error.message.toLowerCase();
      if (message.includes("timeout") || message.includes("timed out")) {
        return "SDK_TIMEOUT";
      }
      if (message.includes("unreachable") || message.includes("connect") || message.includes("connection")) {
        return "SDK_UNREACHABLE";
      }
      if (message.includes("not found") || message.includes("session") && message.includes("not found")) {
        return "INVALID_PAYLOAD";
      }
      if (message.includes("abort") || message.includes("cancelled")) {
        return "INVALID_PAYLOAD";
      }
    } else if (typeof error === "string") {
      const message = error.toLowerCase();
      if (message.includes("timeout") || message.includes("timed out")) {
        return "SDK_TIMEOUT";
      }
      if (message.includes("unreachable") || message.includes("connect")) {
        return "SDK_UNREACHABLE";
      }
    }
    return "SDK_UNREACHABLE";
  }
}

// src/action/ActionRouter.ts
class DefaultActionRouter {
  registry = null;
  setRegistry(registry) {
    this.registry = registry;
  }
  getRegistry() {
    return this.registry;
  }
  async route(actionType, payload, context) {
    const startedAt = Date.now();
    context.logger?.info("router.route.received", {
      action: actionType,
      welinkSessionId: context.welinkSessionId,
      state: context.connectionState,
      payloadType: Array.isArray(payload) ? "array" : typeof payload
    });
    if (!this.registry) {
      context.logger?.error("router.route.failed_registry_missing", { action: actionType });
      return {
        success: false,
        errorCode: "SDK_UNREACHABLE",
        errorMessage: "ActionRegistry not set. Cannot route action."
      };
    }
    const action = this.registry.get(actionType);
    if (!action) {
      context.logger?.warn("router.route.unsupported_action", { action: actionType });
      return {
        success: false,
        errorCode: "UNSUPPORTED_ACTION",
        errorMessage: `Action not found: ${actionType}`
      };
    }
    const result = await action.execute(payload, context);
    context.logger?.info("router.route.completed", {
      action: actionType,
      success: result.success,
      errorCode: result.success ? undefined : result.errorCode,
      latencyMs: Date.now() - startedAt
    });
    return result;
  }
}

// src/action/ActionRegistry.ts
class DefaultActionRegistry {
  actions = new Map;
  register(action) {
    this.actions.set(action.name, action);
  }
  unregister(name) {
    this.actions.delete(name);
  }
  get(name) {
    return this.actions.get(name);
  }
  has(name) {
    return this.actions.has(name);
  }
  list() {
    return Array.from(this.actions.keys());
  }
  getAllActions() {
    return new Map(this.actions);
  }
}
// src/config/ConfigResolver.ts
import { homedir } from "os";
import { dirname, join, resolve } from "path";
import { promises } from "fs";

// node_modules/jsonc-parser/lib/esm/impl/scanner.js
function createScanner(text, ignoreTrivia = false) {
  const len = text.length;
  let pos = 0, value = "", tokenOffset = 0, token = 16, lineNumber = 0, lineStartOffset = 0, tokenLineStartOffset = 0, prevTokenLineStartOffset = 0, scanError = 0;
  function scanHexDigits(count, exact) {
    let digits = 0;
    let value2 = 0;
    while (digits < count || !exact) {
      let ch = text.charCodeAt(pos);
      if (ch >= 48 && ch <= 57) {
        value2 = value2 * 16 + ch - 48;
      } else if (ch >= 65 && ch <= 70) {
        value2 = value2 * 16 + ch - 65 + 10;
      } else if (ch >= 97 && ch <= 102) {
        value2 = value2 * 16 + ch - 97 + 10;
      } else {
        break;
      }
      pos++;
      digits++;
    }
    if (digits < count) {
      value2 = -1;
    }
    return value2;
  }
  function setPosition(newPosition) {
    pos = newPosition;
    value = "";
    tokenOffset = 0;
    token = 16;
    scanError = 0;
  }
  function scanNumber() {
    let start = pos;
    if (text.charCodeAt(pos) === 48) {
      pos++;
    } else {
      pos++;
      while (pos < text.length && isDigit(text.charCodeAt(pos))) {
        pos++;
      }
    }
    if (pos < text.length && text.charCodeAt(pos) === 46) {
      pos++;
      if (pos < text.length && isDigit(text.charCodeAt(pos))) {
        pos++;
        while (pos < text.length && isDigit(text.charCodeAt(pos))) {
          pos++;
        }
      } else {
        scanError = 3;
        return text.substring(start, pos);
      }
    }
    let end = pos;
    if (pos < text.length && (text.charCodeAt(pos) === 69 || text.charCodeAt(pos) === 101)) {
      pos++;
      if (pos < text.length && text.charCodeAt(pos) === 43 || text.charCodeAt(pos) === 45) {
        pos++;
      }
      if (pos < text.length && isDigit(text.charCodeAt(pos))) {
        pos++;
        while (pos < text.length && isDigit(text.charCodeAt(pos))) {
          pos++;
        }
        end = pos;
      } else {
        scanError = 3;
      }
    }
    return text.substring(start, end);
  }
  function scanString() {
    let result = "", start = pos;
    while (true) {
      if (pos >= len) {
        result += text.substring(start, pos);
        scanError = 2;
        break;
      }
      const ch = text.charCodeAt(pos);
      if (ch === 34) {
        result += text.substring(start, pos);
        pos++;
        break;
      }
      if (ch === 92) {
        result += text.substring(start, pos);
        pos++;
        if (pos >= len) {
          scanError = 2;
          break;
        }
        const ch2 = text.charCodeAt(pos++);
        switch (ch2) {
          case 34:
            result += '"';
            break;
          case 92:
            result += "\\";
            break;
          case 47:
            result += "/";
            break;
          case 98:
            result += "\b";
            break;
          case 102:
            result += "\f";
            break;
          case 110:
            result += `
`;
            break;
          case 114:
            result += "\r";
            break;
          case 116:
            result += "\t";
            break;
          case 117:
            const ch3 = scanHexDigits(4, true);
            if (ch3 >= 0) {
              result += String.fromCharCode(ch3);
            } else {
              scanError = 4;
            }
            break;
          default:
            scanError = 5;
        }
        start = pos;
        continue;
      }
      if (ch >= 0 && ch <= 31) {
        if (isLineBreak(ch)) {
          result += text.substring(start, pos);
          scanError = 2;
          break;
        } else {
          scanError = 6;
        }
      }
      pos++;
    }
    return result;
  }
  function scanNext() {
    value = "";
    scanError = 0;
    tokenOffset = pos;
    lineStartOffset = lineNumber;
    prevTokenLineStartOffset = tokenLineStartOffset;
    if (pos >= len) {
      tokenOffset = len;
      return token = 17;
    }
    let code = text.charCodeAt(pos);
    if (isWhiteSpace(code)) {
      do {
        pos++;
        value += String.fromCharCode(code);
        code = text.charCodeAt(pos);
      } while (isWhiteSpace(code));
      return token = 15;
    }
    if (isLineBreak(code)) {
      pos++;
      value += String.fromCharCode(code);
      if (code === 13 && text.charCodeAt(pos) === 10) {
        pos++;
        value += `
`;
      }
      lineNumber++;
      tokenLineStartOffset = pos;
      return token = 14;
    }
    switch (code) {
      case 123:
        pos++;
        return token = 1;
      case 125:
        pos++;
        return token = 2;
      case 91:
        pos++;
        return token = 3;
      case 93:
        pos++;
        return token = 4;
      case 58:
        pos++;
        return token = 6;
      case 44:
        pos++;
        return token = 5;
      case 34:
        pos++;
        value = scanString();
        return token = 10;
      case 47:
        const start = pos - 1;
        if (text.charCodeAt(pos + 1) === 47) {
          pos += 2;
          while (pos < len) {
            if (isLineBreak(text.charCodeAt(pos))) {
              break;
            }
            pos++;
          }
          value = text.substring(start, pos);
          return token = 12;
        }
        if (text.charCodeAt(pos + 1) === 42) {
          pos += 2;
          const safeLength = len - 1;
          let commentClosed = false;
          while (pos < safeLength) {
            const ch = text.charCodeAt(pos);
            if (ch === 42 && text.charCodeAt(pos + 1) === 47) {
              pos += 2;
              commentClosed = true;
              break;
            }
            pos++;
            if (isLineBreak(ch)) {
              if (ch === 13 && text.charCodeAt(pos) === 10) {
                pos++;
              }
              lineNumber++;
              tokenLineStartOffset = pos;
            }
          }
          if (!commentClosed) {
            pos++;
            scanError = 1;
          }
          value = text.substring(start, pos);
          return token = 13;
        }
        value += String.fromCharCode(code);
        pos++;
        return token = 16;
      case 45:
        value += String.fromCharCode(code);
        pos++;
        if (pos === len || !isDigit(text.charCodeAt(pos))) {
          return token = 16;
        }
      case 48:
      case 49:
      case 50:
      case 51:
      case 52:
      case 53:
      case 54:
      case 55:
      case 56:
      case 57:
        value += scanNumber();
        return token = 11;
      default:
        while (pos < len && isUnknownContentCharacter(code)) {
          pos++;
          code = text.charCodeAt(pos);
        }
        if (tokenOffset !== pos) {
          value = text.substring(tokenOffset, pos);
          switch (value) {
            case "true":
              return token = 8;
            case "false":
              return token = 9;
            case "null":
              return token = 7;
          }
          return token = 16;
        }
        value += String.fromCharCode(code);
        pos++;
        return token = 16;
    }
  }
  function isUnknownContentCharacter(code) {
    if (isWhiteSpace(code) || isLineBreak(code)) {
      return false;
    }
    switch (code) {
      case 125:
      case 93:
      case 123:
      case 91:
      case 34:
      case 58:
      case 44:
      case 47:
        return false;
    }
    return true;
  }
  function scanNextNonTrivia() {
    let result;
    do {
      result = scanNext();
    } while (result >= 12 && result <= 15);
    return result;
  }
  return {
    setPosition,
    getPosition: () => pos,
    scan: ignoreTrivia ? scanNextNonTrivia : scanNext,
    getToken: () => token,
    getTokenValue: () => value,
    getTokenOffset: () => tokenOffset,
    getTokenLength: () => pos - tokenOffset,
    getTokenStartLine: () => lineStartOffset,
    getTokenStartCharacter: () => tokenOffset - prevTokenLineStartOffset,
    getTokenError: () => scanError
  };
}
function isWhiteSpace(ch) {
  return ch === 32 || ch === 9;
}
function isLineBreak(ch) {
  return ch === 10 || ch === 13;
}
function isDigit(ch) {
  return ch >= 48 && ch <= 57;
}
var CharacterCodes;
(function(CharacterCodes2) {
  CharacterCodes2[CharacterCodes2["lineFeed"] = 10] = "lineFeed";
  CharacterCodes2[CharacterCodes2["carriageReturn"] = 13] = "carriageReturn";
  CharacterCodes2[CharacterCodes2["space"] = 32] = "space";
  CharacterCodes2[CharacterCodes2["_0"] = 48] = "_0";
  CharacterCodes2[CharacterCodes2["_1"] = 49] = "_1";
  CharacterCodes2[CharacterCodes2["_2"] = 50] = "_2";
  CharacterCodes2[CharacterCodes2["_3"] = 51] = "_3";
  CharacterCodes2[CharacterCodes2["_4"] = 52] = "_4";
  CharacterCodes2[CharacterCodes2["_5"] = 53] = "_5";
  CharacterCodes2[CharacterCodes2["_6"] = 54] = "_6";
  CharacterCodes2[CharacterCodes2["_7"] = 55] = "_7";
  CharacterCodes2[CharacterCodes2["_8"] = 56] = "_8";
  CharacterCodes2[CharacterCodes2["_9"] = 57] = "_9";
  CharacterCodes2[CharacterCodes2["a"] = 97] = "a";
  CharacterCodes2[CharacterCodes2["b"] = 98] = "b";
  CharacterCodes2[CharacterCodes2["c"] = 99] = "c";
  CharacterCodes2[CharacterCodes2["d"] = 100] = "d";
  CharacterCodes2[CharacterCodes2["e"] = 101] = "e";
  CharacterCodes2[CharacterCodes2["f"] = 102] = "f";
  CharacterCodes2[CharacterCodes2["g"] = 103] = "g";
  CharacterCodes2[CharacterCodes2["h"] = 104] = "h";
  CharacterCodes2[CharacterCodes2["i"] = 105] = "i";
  CharacterCodes2[CharacterCodes2["j"] = 106] = "j";
  CharacterCodes2[CharacterCodes2["k"] = 107] = "k";
  CharacterCodes2[CharacterCodes2["l"] = 108] = "l";
  CharacterCodes2[CharacterCodes2["m"] = 109] = "m";
  CharacterCodes2[CharacterCodes2["n"] = 110] = "n";
  CharacterCodes2[CharacterCodes2["o"] = 111] = "o";
  CharacterCodes2[CharacterCodes2["p"] = 112] = "p";
  CharacterCodes2[CharacterCodes2["q"] = 113] = "q";
  CharacterCodes2[CharacterCodes2["r"] = 114] = "r";
  CharacterCodes2[CharacterCodes2["s"] = 115] = "s";
  CharacterCodes2[CharacterCodes2["t"] = 116] = "t";
  CharacterCodes2[CharacterCodes2["u"] = 117] = "u";
  CharacterCodes2[CharacterCodes2["v"] = 118] = "v";
  CharacterCodes2[CharacterCodes2["w"] = 119] = "w";
  CharacterCodes2[CharacterCodes2["x"] = 120] = "x";
  CharacterCodes2[CharacterCodes2["y"] = 121] = "y";
  CharacterCodes2[CharacterCodes2["z"] = 122] = "z";
  CharacterCodes2[CharacterCodes2["A"] = 65] = "A";
  CharacterCodes2[CharacterCodes2["B"] = 66] = "B";
  CharacterCodes2[CharacterCodes2["C"] = 67] = "C";
  CharacterCodes2[CharacterCodes2["D"] = 68] = "D";
  CharacterCodes2[CharacterCodes2["E"] = 69] = "E";
  CharacterCodes2[CharacterCodes2["F"] = 70] = "F";
  CharacterCodes2[CharacterCodes2["G"] = 71] = "G";
  CharacterCodes2[CharacterCodes2["H"] = 72] = "H";
  CharacterCodes2[CharacterCodes2["I"] = 73] = "I";
  CharacterCodes2[CharacterCodes2["J"] = 74] = "J";
  CharacterCodes2[CharacterCodes2["K"] = 75] = "K";
  CharacterCodes2[CharacterCodes2["L"] = 76] = "L";
  CharacterCodes2[CharacterCodes2["M"] = 77] = "M";
  CharacterCodes2[CharacterCodes2["N"] = 78] = "N";
  CharacterCodes2[CharacterCodes2["O"] = 79] = "O";
  CharacterCodes2[CharacterCodes2["P"] = 80] = "P";
  CharacterCodes2[CharacterCodes2["Q"] = 81] = "Q";
  CharacterCodes2[CharacterCodes2["R"] = 82] = "R";
  CharacterCodes2[CharacterCodes2["S"] = 83] = "S";
  CharacterCodes2[CharacterCodes2["T"] = 84] = "T";
  CharacterCodes2[CharacterCodes2["U"] = 85] = "U";
  CharacterCodes2[CharacterCodes2["V"] = 86] = "V";
  CharacterCodes2[CharacterCodes2["W"] = 87] = "W";
  CharacterCodes2[CharacterCodes2["X"] = 88] = "X";
  CharacterCodes2[CharacterCodes2["Y"] = 89] = "Y";
  CharacterCodes2[CharacterCodes2["Z"] = 90] = "Z";
  CharacterCodes2[CharacterCodes2["asterisk"] = 42] = "asterisk";
  CharacterCodes2[CharacterCodes2["backslash"] = 92] = "backslash";
  CharacterCodes2[CharacterCodes2["closeBrace"] = 125] = "closeBrace";
  CharacterCodes2[CharacterCodes2["closeBracket"] = 93] = "closeBracket";
  CharacterCodes2[CharacterCodes2["colon"] = 58] = "colon";
  CharacterCodes2[CharacterCodes2["comma"] = 44] = "comma";
  CharacterCodes2[CharacterCodes2["dot"] = 46] = "dot";
  CharacterCodes2[CharacterCodes2["doubleQuote"] = 34] = "doubleQuote";
  CharacterCodes2[CharacterCodes2["minus"] = 45] = "minus";
  CharacterCodes2[CharacterCodes2["openBrace"] = 123] = "openBrace";
  CharacterCodes2[CharacterCodes2["openBracket"] = 91] = "openBracket";
  CharacterCodes2[CharacterCodes2["plus"] = 43] = "plus";
  CharacterCodes2[CharacterCodes2["slash"] = 47] = "slash";
  CharacterCodes2[CharacterCodes2["formFeed"] = 12] = "formFeed";
  CharacterCodes2[CharacterCodes2["tab"] = 9] = "tab";
})(CharacterCodes || (CharacterCodes = {}));

// node_modules/jsonc-parser/lib/esm/impl/string-intern.js
var cachedSpaces = new Array(20).fill(0).map((_, index) => {
  return " ".repeat(index);
});
var maxCachedValues = 200;
var cachedBreakLinesWithSpaces = {
  " ": {
    "\n": new Array(maxCachedValues).fill(0).map((_, index) => {
      return `
` + " ".repeat(index);
    }),
    "\r": new Array(maxCachedValues).fill(0).map((_, index) => {
      return "\r" + " ".repeat(index);
    }),
    "\r\n": new Array(maxCachedValues).fill(0).map((_, index) => {
      return `\r
` + " ".repeat(index);
    })
  },
  "\t": {
    "\n": new Array(maxCachedValues).fill(0).map((_, index) => {
      return `
` + "\t".repeat(index);
    }),
    "\r": new Array(maxCachedValues).fill(0).map((_, index) => {
      return "\r" + "\t".repeat(index);
    }),
    "\r\n": new Array(maxCachedValues).fill(0).map((_, index) => {
      return `\r
` + "\t".repeat(index);
    })
  }
};

// node_modules/jsonc-parser/lib/esm/impl/parser.js
var ParseOptions;
(function(ParseOptions2) {
  ParseOptions2.DEFAULT = {
    allowTrailingComma: false
  };
})(ParseOptions || (ParseOptions = {}));
function parse(text, errors = [], options = ParseOptions.DEFAULT) {
  let currentProperty = null;
  let currentParent = [];
  const previousParents = [];
  function onValue(value) {
    if (Array.isArray(currentParent)) {
      currentParent.push(value);
    } else if (currentProperty !== null) {
      currentParent[currentProperty] = value;
    }
  }
  const visitor = {
    onObjectBegin: () => {
      const object = {};
      onValue(object);
      previousParents.push(currentParent);
      currentParent = object;
      currentProperty = null;
    },
    onObjectProperty: (name) => {
      currentProperty = name;
    },
    onObjectEnd: () => {
      currentParent = previousParents.pop();
    },
    onArrayBegin: () => {
      const array = [];
      onValue(array);
      previousParents.push(currentParent);
      currentParent = array;
      currentProperty = null;
    },
    onArrayEnd: () => {
      currentParent = previousParents.pop();
    },
    onLiteralValue: onValue,
    onError: (error, offset, length) => {
      errors.push({ error, offset, length });
    }
  };
  visit(text, visitor, options);
  return currentParent[0];
}
function visit(text, visitor, options = ParseOptions.DEFAULT) {
  const _scanner = createScanner(text, false);
  const _jsonPath = [];
  let suppressedCallbacks = 0;
  function toNoArgVisit(visitFunction) {
    return visitFunction ? () => suppressedCallbacks === 0 && visitFunction(_scanner.getTokenOffset(), _scanner.getTokenLength(), _scanner.getTokenStartLine(), _scanner.getTokenStartCharacter()) : () => true;
  }
  function toOneArgVisit(visitFunction) {
    return visitFunction ? (arg) => suppressedCallbacks === 0 && visitFunction(arg, _scanner.getTokenOffset(), _scanner.getTokenLength(), _scanner.getTokenStartLine(), _scanner.getTokenStartCharacter()) : () => true;
  }
  function toOneArgVisitWithPath(visitFunction) {
    return visitFunction ? (arg) => suppressedCallbacks === 0 && visitFunction(arg, _scanner.getTokenOffset(), _scanner.getTokenLength(), _scanner.getTokenStartLine(), _scanner.getTokenStartCharacter(), () => _jsonPath.slice()) : () => true;
  }
  function toBeginVisit(visitFunction) {
    return visitFunction ? () => {
      if (suppressedCallbacks > 0) {
        suppressedCallbacks++;
      } else {
        let cbReturn = visitFunction(_scanner.getTokenOffset(), _scanner.getTokenLength(), _scanner.getTokenStartLine(), _scanner.getTokenStartCharacter(), () => _jsonPath.slice());
        if (cbReturn === false) {
          suppressedCallbacks = 1;
        }
      }
    } : () => true;
  }
  function toEndVisit(visitFunction) {
    return visitFunction ? () => {
      if (suppressedCallbacks > 0) {
        suppressedCallbacks--;
      }
      if (suppressedCallbacks === 0) {
        visitFunction(_scanner.getTokenOffset(), _scanner.getTokenLength(), _scanner.getTokenStartLine(), _scanner.getTokenStartCharacter());
      }
    } : () => true;
  }
  const onObjectBegin = toBeginVisit(visitor.onObjectBegin), onObjectProperty = toOneArgVisitWithPath(visitor.onObjectProperty), onObjectEnd = toEndVisit(visitor.onObjectEnd), onArrayBegin = toBeginVisit(visitor.onArrayBegin), onArrayEnd = toEndVisit(visitor.onArrayEnd), onLiteralValue = toOneArgVisitWithPath(visitor.onLiteralValue), onSeparator = toOneArgVisit(visitor.onSeparator), onComment = toNoArgVisit(visitor.onComment), onError = toOneArgVisit(visitor.onError);
  const disallowComments = options && options.disallowComments;
  const allowTrailingComma = options && options.allowTrailingComma;
  function scanNext() {
    while (true) {
      const token = _scanner.scan();
      switch (_scanner.getTokenError()) {
        case 4:
          handleError(14);
          break;
        case 5:
          handleError(15);
          break;
        case 3:
          handleError(13);
          break;
        case 1:
          if (!disallowComments) {
            handleError(11);
          }
          break;
        case 2:
          handleError(12);
          break;
        case 6:
          handleError(16);
          break;
      }
      switch (token) {
        case 12:
        case 13:
          if (disallowComments) {
            handleError(10);
          } else {
            onComment();
          }
          break;
        case 16:
          handleError(1);
          break;
        case 15:
        case 14:
          break;
        default:
          return token;
      }
    }
  }
  function handleError(error, skipUntilAfter = [], skipUntil = []) {
    onError(error);
    if (skipUntilAfter.length + skipUntil.length > 0) {
      let token = _scanner.getToken();
      while (token !== 17) {
        if (skipUntilAfter.indexOf(token) !== -1) {
          scanNext();
          break;
        } else if (skipUntil.indexOf(token) !== -1) {
          break;
        }
        token = scanNext();
      }
    }
  }
  function parseString(isValue) {
    const value = _scanner.getTokenValue();
    if (isValue) {
      onLiteralValue(value);
    } else {
      onObjectProperty(value);
      _jsonPath.push(value);
    }
    scanNext();
    return true;
  }
  function parseLiteral() {
    switch (_scanner.getToken()) {
      case 11:
        const tokenValue = _scanner.getTokenValue();
        let value = Number(tokenValue);
        if (isNaN(value)) {
          handleError(2);
          value = 0;
        }
        onLiteralValue(value);
        break;
      case 7:
        onLiteralValue(null);
        break;
      case 8:
        onLiteralValue(true);
        break;
      case 9:
        onLiteralValue(false);
        break;
      default:
        return false;
    }
    scanNext();
    return true;
  }
  function parseProperty() {
    if (_scanner.getToken() !== 10) {
      handleError(3, [], [2, 5]);
      return false;
    }
    parseString(false);
    if (_scanner.getToken() === 6) {
      onSeparator(":");
      scanNext();
      if (!parseValue()) {
        handleError(4, [], [2, 5]);
      }
    } else {
      handleError(5, [], [2, 5]);
    }
    _jsonPath.pop();
    return true;
  }
  function parseObject() {
    onObjectBegin();
    scanNext();
    let needsComma = false;
    while (_scanner.getToken() !== 2 && _scanner.getToken() !== 17) {
      if (_scanner.getToken() === 5) {
        if (!needsComma) {
          handleError(4, [], []);
        }
        onSeparator(",");
        scanNext();
        if (_scanner.getToken() === 2 && allowTrailingComma) {
          break;
        }
      } else if (needsComma) {
        handleError(6, [], []);
      }
      if (!parseProperty()) {
        handleError(4, [], [2, 5]);
      }
      needsComma = true;
    }
    onObjectEnd();
    if (_scanner.getToken() !== 2) {
      handleError(7, [2], []);
    } else {
      scanNext();
    }
    return true;
  }
  function parseArray() {
    onArrayBegin();
    scanNext();
    let isFirstElement = true;
    let needsComma = false;
    while (_scanner.getToken() !== 4 && _scanner.getToken() !== 17) {
      if (_scanner.getToken() === 5) {
        if (!needsComma) {
          handleError(4, [], []);
        }
        onSeparator(",");
        scanNext();
        if (_scanner.getToken() === 4 && allowTrailingComma) {
          break;
        }
      } else if (needsComma) {
        handleError(6, [], []);
      }
      if (isFirstElement) {
        _jsonPath.push(0);
        isFirstElement = false;
      } else {
        _jsonPath[_jsonPath.length - 1]++;
      }
      if (!parseValue()) {
        handleError(4, [], [4, 5]);
      }
      needsComma = true;
    }
    onArrayEnd();
    if (!isFirstElement) {
      _jsonPath.pop();
    }
    if (_scanner.getToken() !== 4) {
      handleError(8, [4], []);
    } else {
      scanNext();
    }
    return true;
  }
  function parseValue() {
    switch (_scanner.getToken()) {
      case 3:
        return parseArray();
      case 1:
        return parseObject();
      case 10:
        return parseString(true);
      default:
        return parseLiteral();
    }
  }
  scanNext();
  if (_scanner.getToken() === 17) {
    if (options.allowEmptyContent) {
      return true;
    }
    handleError(4, [], []);
    return false;
  }
  if (!parseValue()) {
    handleError(4, [], []);
    return false;
  }
  if (_scanner.getToken() !== 17) {
    handleError(9, [], []);
  }
  return true;
}

// node_modules/jsonc-parser/lib/esm/main.js
var ScanError;
(function(ScanError2) {
  ScanError2[ScanError2["None"] = 0] = "None";
  ScanError2[ScanError2["UnexpectedEndOfComment"] = 1] = "UnexpectedEndOfComment";
  ScanError2[ScanError2["UnexpectedEndOfString"] = 2] = "UnexpectedEndOfString";
  ScanError2[ScanError2["UnexpectedEndOfNumber"] = 3] = "UnexpectedEndOfNumber";
  ScanError2[ScanError2["InvalidUnicode"] = 4] = "InvalidUnicode";
  ScanError2[ScanError2["InvalidEscapeCharacter"] = 5] = "InvalidEscapeCharacter";
  ScanError2[ScanError2["InvalidCharacter"] = 6] = "InvalidCharacter";
})(ScanError || (ScanError = {}));
var SyntaxKind;
(function(SyntaxKind2) {
  SyntaxKind2[SyntaxKind2["OpenBraceToken"] = 1] = "OpenBraceToken";
  SyntaxKind2[SyntaxKind2["CloseBraceToken"] = 2] = "CloseBraceToken";
  SyntaxKind2[SyntaxKind2["OpenBracketToken"] = 3] = "OpenBracketToken";
  SyntaxKind2[SyntaxKind2["CloseBracketToken"] = 4] = "CloseBracketToken";
  SyntaxKind2[SyntaxKind2["CommaToken"] = 5] = "CommaToken";
  SyntaxKind2[SyntaxKind2["ColonToken"] = 6] = "ColonToken";
  SyntaxKind2[SyntaxKind2["NullKeyword"] = 7] = "NullKeyword";
  SyntaxKind2[SyntaxKind2["TrueKeyword"] = 8] = "TrueKeyword";
  SyntaxKind2[SyntaxKind2["FalseKeyword"] = 9] = "FalseKeyword";
  SyntaxKind2[SyntaxKind2["StringLiteral"] = 10] = "StringLiteral";
  SyntaxKind2[SyntaxKind2["NumericLiteral"] = 11] = "NumericLiteral";
  SyntaxKind2[SyntaxKind2["LineCommentTrivia"] = 12] = "LineCommentTrivia";
  SyntaxKind2[SyntaxKind2["BlockCommentTrivia"] = 13] = "BlockCommentTrivia";
  SyntaxKind2[SyntaxKind2["LineBreakTrivia"] = 14] = "LineBreakTrivia";
  SyntaxKind2[SyntaxKind2["Trivia"] = 15] = "Trivia";
  SyntaxKind2[SyntaxKind2["Unknown"] = 16] = "Unknown";
  SyntaxKind2[SyntaxKind2["EOF"] = 17] = "EOF";
})(SyntaxKind || (SyntaxKind = {}));
var parse2 = parse;
var ParseErrorCode;
(function(ParseErrorCode2) {
  ParseErrorCode2[ParseErrorCode2["InvalidSymbol"] = 1] = "InvalidSymbol";
  ParseErrorCode2[ParseErrorCode2["InvalidNumberFormat"] = 2] = "InvalidNumberFormat";
  ParseErrorCode2[ParseErrorCode2["PropertyNameExpected"] = 3] = "PropertyNameExpected";
  ParseErrorCode2[ParseErrorCode2["ValueExpected"] = 4] = "ValueExpected";
  ParseErrorCode2[ParseErrorCode2["ColonExpected"] = 5] = "ColonExpected";
  ParseErrorCode2[ParseErrorCode2["CommaExpected"] = 6] = "CommaExpected";
  ParseErrorCode2[ParseErrorCode2["CloseBraceExpected"] = 7] = "CloseBraceExpected";
  ParseErrorCode2[ParseErrorCode2["CloseBracketExpected"] = 8] = "CloseBracketExpected";
  ParseErrorCode2[ParseErrorCode2["EndOfFileExpected"] = 9] = "EndOfFileExpected";
  ParseErrorCode2[ParseErrorCode2["InvalidCommentToken"] = 10] = "InvalidCommentToken";
  ParseErrorCode2[ParseErrorCode2["UnexpectedEndOfComment"] = 11] = "UnexpectedEndOfComment";
  ParseErrorCode2[ParseErrorCode2["UnexpectedEndOfString"] = 12] = "UnexpectedEndOfString";
  ParseErrorCode2[ParseErrorCode2["UnexpectedEndOfNumber"] = 13] = "UnexpectedEndOfNumber";
  ParseErrorCode2[ParseErrorCode2["InvalidUnicode"] = 14] = "InvalidUnicode";
  ParseErrorCode2[ParseErrorCode2["InvalidEscapeCharacter"] = 15] = "InvalidEscapeCharacter";
  ParseErrorCode2[ParseErrorCode2["InvalidCharacter"] = 16] = "InvalidCharacter";
})(ParseErrorCode || (ParseErrorCode = {}));

// src/config/JsoncParser.ts
import { promises as fs } from "fs";
class JsoncParser {
  parse(content) {
    try {
      return parse2(content);
    } catch (error) {
      throw new Error(`Failed to parse JSONC: ${getErrorMessage(error)}`);
    }
  }
  async parseFile(filePath) {
    try {
      const content = await fs.readFile(filePath, "utf-8");
      return this.parse(content);
    } catch (error) {
      if (error.code === "ENOENT") {
        return null;
      }
      throw new Error(`Failed to read or parse JSONC file ${filePath}: ${getErrorMessage(error)}`);
    }
  }
}

// src/config/default-config.ts
var DEFAULT_BRIDGE_CONFIG = {
  enabled: true,
  config_version: 1,
  gateway: {
    url: "ws://localhost:8081/ws/agent",
    toolType: "OPENCODE",
    toolVersion: "1.0.0",
    deviceName: "Local Machine",
    heartbeatIntervalMs: 30000,
    reconnect: {
      baseMs: 1000,
      maxMs: 30000,
      exponential: true
    },
    ping: {
      intervalMs: 30000
    }
  },
  sdk: {
    timeoutMs: 1e4
  },
  auth: {
    ak: "",
    sk: ""
  },
  events: {
    allowlist: [...DEFAULT_EVENT_ALLOWLIST]
  }
};

// src/config/ConfigResolver.ts
var CONFIG_FILE_NAMES = ["message-bridge.jsonc", "message-bridge.json"];

class ConfigResolver {
  jsoncParser;
  logger;
  constructor(logger) {
    this.jsoncParser = new JsoncParser;
    this.logger = logger;
  }
  async resolveConfig(workspacePath) {
    let config = this.mergeConfig({}, DEFAULT_BRIDGE_CONFIG);
    const sources = ["default"];
    const workspaceRoot = workspacePath ?? process.cwd();
    this.logger?.info("config.resolve.started", { workspacePath: workspaceRoot });
    const userConfigHome = process.env.HOME || homedir();
    const userConfigPath = await this.findFirstExistingPath(this.getConfigCandidatePaths(join(userConfigHome, ".config", "opencode")));
    if (userConfigPath) {
      const userConfig = await this.loadConfigFile(userConfigPath);
      if (userConfig) {
        config = this.mergeConfig(config, userConfig);
        sources.push(`user:${userConfigPath}`);
        this.logger?.info("config.source.loaded", {
          source: "user",
          path: userConfigPath
        });
      }
    }
    const projectConfigPath = await this.findProjectConfig(workspaceRoot);
    if (projectConfigPath) {
      const projectConfig = await this.loadConfigFile(projectConfigPath);
      if (projectConfig) {
        config = this.mergeConfig(config, projectConfig);
        sources.push(`project:${projectConfigPath}`);
        this.logger?.info("config.source.loaded", {
          source: "project",
          path: projectConfigPath
        });
      }
    }
    const envConfig = this.loadEnvConfig();
    if (Object.keys(envConfig).length > 0) {
      config = this.mergeConfig(config, envConfig);
      sources.push("env");
      this.logger?.info("config.source.loaded", {
        source: "env",
        overrideCount: Object.keys(envConfig).length
      });
    }
    const normalized = this.normalizeConfig(config);
    this.logger?.info("config.resolve.completed", {
      workspacePath: workspaceRoot,
      sources,
      allowlistSize: normalized.events.allowlist.length,
      debugEnabled: !!normalized.debug,
      projectConfigPath
    });
    return normalized;
  }
  async loadConfigFile(filePath) {
    try {
      return await this.jsoncParser.parseFile(filePath);
    } catch (error) {
      if (error.code === "ENOENT") {
        return null;
      }
      this.logger?.error("config.source.load_failed", {
        path: filePath,
        error: getErrorMessage(error),
        ...getErrorDetailsForLog(error)
      });
      throw error;
    }
  }
  async findProjectConfig(startDir) {
    const configDirName = ".opencode";
    let current = resolve(startDir);
    while (true) {
      const configPath = await this.findFirstExistingPath(this.getConfigCandidatePaths(join(current, configDirName)));
      if (configPath) {
        return configPath;
      }
      const parent = dirname(current);
      if (parent === current) {
        break;
      }
      current = parent;
    }
    return null;
  }
  getConfigCandidatePaths(configDir) {
    return CONFIG_FILE_NAMES.map((fileName) => join(configDir, fileName));
  }
  async findFirstExistingPath(paths) {
    for (const path of paths) {
      try {
        await promises.access(path);
        return path;
      } catch {}
    }
    return null;
  }
  loadEnvConfig() {
    const envConfig = {};
    if (process.env.BRIDGE_ENABLED !== undefined) {
      envConfig.enabled = process.env.BRIDGE_ENABLED.toLowerCase() === "true";
    }
    if (process.env.BRIDGE_DEBUG !== undefined) {
      envConfig.debug = process.env.BRIDGE_DEBUG.toLowerCase() === "true";
    }
    if (process.env.BRIDGE_CONFIG_VERSION !== undefined) {
      envConfig.config_version = parseInt(process.env.BRIDGE_CONFIG_VERSION, 10);
    }
    const gateway = {};
    if (process.env.BRIDGE_GATEWAY_URL) {
      gateway.url = this.substituteEnvVars(process.env.BRIDGE_GATEWAY_URL);
    }
    if (process.env.BRIDGE_GATEWAY_DEVICE_NAME) {
      gateway.deviceName = this.substituteEnvVars(process.env.BRIDGE_GATEWAY_DEVICE_NAME);
    }
    if (process.env.BRIDGE_GATEWAY_MAC_ADDRESS) {
      gateway.macAddress = this.substituteEnvVars(process.env.BRIDGE_GATEWAY_MAC_ADDRESS);
    }
    if (process.env.BRIDGE_GATEWAY_TOOL_TYPE) {
      gateway.toolType = this.substituteEnvVars(process.env.BRIDGE_GATEWAY_TOOL_TYPE);
    }
    if (process.env.BRIDGE_GATEWAY_TOOL_VERSION) {
      gateway.toolVersion = this.substituteEnvVars(process.env.BRIDGE_GATEWAY_TOOL_VERSION);
    }
    const reconnect = {};
    if (process.env.BRIDGE_GATEWAY_RECONNECT_BASE_MS)
      reconnect.baseMs = parseInt(process.env.BRIDGE_GATEWAY_RECONNECT_BASE_MS, 10);
    if (process.env.BRIDGE_GATEWAY_RECONNECT_MAX_MS)
      reconnect.maxMs = parseInt(process.env.BRIDGE_GATEWAY_RECONNECT_MAX_MS, 10);
    if (process.env.BRIDGE_GATEWAY_RECONNECT_EXPONENTIAL !== undefined)
      reconnect.exponential = process.env.BRIDGE_GATEWAY_RECONNECT_EXPONENTIAL.toLowerCase() === "true";
    if (Object.keys(reconnect).length > 0)
      gateway.reconnect = reconnect;
    const hb = process.env.BRIDGE_GATEWAY_HEARTBEAT_INTERVAL_MS ?? process.env.BRIDGE_EVENT_HEARTBEAT_INTERVAL_MS;
    if (hb)
      gateway.heartbeatIntervalMs = parseInt(hb, 10);
    const ping = {};
    if (process.env.BRIDGE_GATEWAY_PING_INTERVAL_MS) {
      ping.intervalMs = parseInt(process.env.BRIDGE_GATEWAY_PING_INTERVAL_MS, 10);
    }
    if (Object.keys(ping).length > 0)
      gateway.ping = ping;
    if (Object.keys(gateway).length > 0) {
      envConfig.gateway = gateway;
    }
    const auth = {};
    if (process.env.BRIDGE_AUTH_AK || process.env.BRIDGE_AK) {
      auth.ak = this.substituteEnvVars(process.env.BRIDGE_AUTH_AK ?? process.env.BRIDGE_AK ?? "");
    }
    if (process.env.BRIDGE_AUTH_SK || process.env.BRIDGE_SK) {
      auth.sk = this.substituteEnvVars(process.env.BRIDGE_AUTH_SK ?? process.env.BRIDGE_SK ?? "");
    }
    if (Object.keys(auth).length > 0) {
      envConfig.auth = auth;
    }
    const sdk2 = {};
    if (process.env.BRIDGE_SDK_TIMEOUT_MS) {
      sdk2.timeoutMs = parseInt(process.env.BRIDGE_SDK_TIMEOUT_MS, 10);
    }
    if (Object.keys(sdk2).length > 0) {
      envConfig.sdk = sdk2;
    }
    if (process.env.BRIDGE_EVENTS_ALLOWLIST) {
      envConfig.events = {
        allowlist: process.env.BRIDGE_EVENTS_ALLOWLIST.split(",").map((item) => this.substituteEnvVars(item.trim()))
      };
    }
    return envConfig;
  }
  substituteEnvVars(value) {
    return value.replace(/\$\{([^}]+)\}/g, (match, varName) => process.env[varName] || match);
  }
  normalizeConfig(config) {
    const normalized = { ...config };
    if (!normalized.gateway) {
      normalized.gateway = {};
    }
    if (!normalized.gateway.url) {
      normalized.gateway.url = "ws://localhost:8081/ws/agent";
    }
    if (!normalized.gateway.deviceName) {
      normalized.gateway.deviceName = "Local Machine";
    }
    if (typeof normalized.gateway.macAddress === "string") {
      normalized.gateway.macAddress = normalized.gateway.macAddress.trim() || undefined;
    }
    if (!normalized.gateway.toolType) {
      normalized.gateway.toolType = "OPENCODE";
    } else {
      normalized.gateway.toolType = normalized.gateway.toolType.trim().toUpperCase();
    }
    if (!normalized.gateway.toolVersion) {
      normalized.gateway.toolVersion = "1.0.0";
    }
    if (!normalized.gateway.heartbeatIntervalMs) {
      normalized.gateway.heartbeatIntervalMs = 30000;
    }
    if (!normalized.gateway.reconnect) {
      normalized.gateway.reconnect = {
        baseMs: 1000,
        maxMs: 30000,
        exponential: true
      };
    } else {
      if (!normalized.gateway.reconnect.baseMs) {
        normalized.gateway.reconnect.baseMs = 1000;
      }
      if (!normalized.gateway.reconnect.maxMs) {
        normalized.gateway.reconnect.maxMs = 30000;
      }
      if (normalized.gateway.reconnect.exponential === undefined) {
        normalized.gateway.reconnect.exponential = true;
      }
    }
    if (!normalized.gateway.ping) {
      normalized.gateway.ping = {
        intervalMs: 30000
      };
    }
    if (!normalized.sdk) {
      normalized.sdk = { timeoutMs: 1e4 };
    } else if (!normalized.sdk.timeoutMs) {
      normalized.sdk.timeoutMs = 1e4;
    }
    if (!normalized.auth) {
      normalized.auth = { ak: "", sk: "" };
    }
    if (!normalized.events || !normalized.events.allowlist || normalized.events.allowlist.length === 0) {
      normalized.events = {
        allowlist: [...DEFAULT_EVENT_ALLOWLIST]
      };
    }
    if (!normalized.config_version) {
      normalized.config_version = 1;
    }
    if (normalized.enabled === undefined) {
      normalized.enabled = true;
    }
    return normalized;
  }
  mergeConfig(target, source) {
    if (typeof target !== "object" || typeof source !== "object" || target === null || source === null) {
      return source ?? target;
    }
    const result = { ...target };
    for (const key of Object.keys(source)) {
      const src = source[key];
      const dst = result[key];
      if (typeof src === "object" && src !== null && !Array.isArray(src)) {
        result[key] = this.mergeConfig(dst ?? {}, src);
      } else if (Array.isArray(src)) {
        result[key] = [...src];
      } else {
        result[key] = src;
      }
    }
    return result;
  }
}
// src/config/ConfigValidator.ts
class ConfigValidator {
  validate(config) {
    const errors = [];
    if (!config || typeof config !== "object") {
      errors.push({ path: "", code: "INVALID_CONFIG", message: "Configuration must be an object" });
      return errors;
    }
    const c = config;
    if (c.enabled !== false) {
      if (!c.auth || typeof c.auth.ak !== "string" || !c.auth.ak.trim()) {
        errors.push({ path: "auth.ak", code: "MISSING_REQUIRED", message: "auth.ak is required" });
      }
      if (!c.auth || typeof c.auth.sk !== "string" || !c.auth.sk.trim()) {
        errors.push({ path: "auth.sk", code: "MISSING_REQUIRED", message: "auth.sk is required" });
      }
    }
    if (c.config_version !== undefined && c.config_version !== 1) {
      errors.push({ path: "config_version", code: "INVALID_VERSION", message: "config_version must be 1" });
    }
    if (c.enabled !== undefined && typeof c.enabled !== "boolean") {
      errors.push({ path: "enabled", code: "INVALID_TYPE", message: "enabled must be boolean" });
    }
    if (c.gateway?.url !== undefined) {
      if (typeof c.gateway.url !== "string" || !/^wss?:\/\//.test(c.gateway.url)) {
        errors.push({ path: "gateway.url", code: "INVALID_URL", message: "gateway.url must start with ws:// or wss://" });
      }
    }
    if (c.gateway?.reconnect?.baseMs !== undefined) {
      this.validatePositiveInt(c.gateway.reconnect.baseMs, "gateway.reconnect.baseMs", errors);
    }
    if (c.gateway?.reconnect?.maxMs !== undefined) {
      this.validatePositiveInt(c.gateway.reconnect.maxMs, "gateway.reconnect.maxMs", errors);
    }
    if (c.gateway?.heartbeatIntervalMs !== undefined) {
      this.validatePositiveInt(c.gateway.heartbeatIntervalMs, "gateway.heartbeatIntervalMs", errors);
    }
    if (c.sdk?.timeoutMs !== undefined) {
      this.validatePositiveInt(c.sdk.timeoutMs, "sdk.timeoutMs", errors);
    }
    if (c.sdk && "baseUrl" in c.sdk) {
      errors.push({ path: "sdk.baseUrl", code: "DEPRECATED_FIELD", message: "sdk.baseUrl is deprecated and should not be used" });
    }
    if (c.events?.allowlist !== undefined) {
      if (!Array.isArray(c.events.allowlist)) {
        errors.push({ path: "events.allowlist", code: "INVALID_TYPE", message: "events.allowlist must be an array" });
      } else {
        const supported = new Set(SUPPORTED_UPSTREAM_EVENT_TYPES);
        c.events.allowlist.forEach((item, index) => {
          if (typeof item !== "string") {
            errors.push({
              path: `events.allowlist[${index}]`,
              code: "INVALID_TYPE",
              message: "events.allowlist entries must be strings"
            });
            return;
          }
          if (!supported.has(item)) {
            errors.push({
              path: `events.allowlist[${index}]`,
              code: "UNSUPPORTED_EVENT",
              message: `Unsupported event type: ${item}`
            });
          }
        });
      }
    }
    return errors;
  }
  validatePositiveInt(value, path, errors) {
    if (typeof value !== "number" || !Number.isInteger(value) || value <= 0) {
      errors.push({ path, code: "INVALID_NUMBER", message: `${path} must be a positive integer` });
    }
  }
}
// src/runtime/AppLogger.ts
import { randomUUID } from "crypto";
function isRecord2(value) {
  return value !== null && typeof value === "object";
}
function redact(value) {
  if (Array.isArray(value)) {
    return value.map((item) => redact(item));
  }
  if (!isRecord2(value)) {
    return value;
  }
  const sensitive = ["ak", "sk", "token", "authorization", "cookie", "secret", "password"];
  const output = {};
  for (const [k, v] of Object.entries(value)) {
    const lower = k.toLowerCase();
    if (sensitive.some((key) => lower.includes(key))) {
      output[k] = "***";
      continue;
    }
    output[k] = redact(v);
  }
  return output;
}
function getAppLog(client) {
  if (!isRecord2(client)) {
    return null;
  }
  const app = client.app;
  if (!isRecord2(app) || typeof app.log !== "function") {
    return null;
  }
  return app.log.bind(app);
}

class AppLogger {
  baseExtra;
  appLog;
  debugEnabled;
  traceId;
  constructor(client, baseExtra = {}, traceId, appLog, debug) {
    this.baseExtra = baseExtra;
    this.appLog = appLog ?? getAppLog(client);
    const envDebugEnabled = ["1", "true", "yes", "on"].includes(String(process.env.BRIDGE_DEBUG || "").toLowerCase());
    this.debugEnabled = debug ?? envDebugEnabled;
    this.traceId = traceId ?? randomUUID();
  }
  child(extra) {
    return new AppLogger({}, { ...this.baseExtra, ...extra }, this.traceId, this.appLog, this.debugEnabled);
  }
  getTraceId() {
    return this.traceId;
  }
  debug(message, extra) {
    this.write("debug", message, extra);
  }
  info(message, extra) {
    this.write("info", message, extra);
  }
  warn(message, extra) {
    this.write("warn", message, extra);
  }
  error(message, extra) {
    this.write("error", message, extra);
  }
  write(level, message, extra) {
    const enriched = {
      runtimeTraceId: this.traceId,
      traceId: this.traceId,
      ...this.baseExtra,
      ...extra || {}
    };
    const payload = redact(enriched);
    if (!this.appLog) {
      if (this.debugEnabled) {
        console.debug("[message-bridge][log-fallback]", level, message, payload);
      }
      return;
    }
    Promise.resolve().then(() => this.appLog?.({
      body: {
        service: "message-bridge",
        level,
        message,
        extra: payload
      }
    })).catch((err) => {
      if (this.debugEnabled) {
        const reason = err instanceof Error ? err.message : String(err);
        console.debug("[message-bridge][log-send-failed]", reason, { level, message });
      }
    });
  }
}

// src/config/index.impl.ts
class ConfigValidationAggregateError extends Error {
  errors;
  constructor(errors) {
    super("Configuration validation failed");
    this.errors = errors;
    Object.setPrototypeOf(this, ConfigValidationAggregateError.prototype);
  }
}
function createConsoleBackedLogger() {
  return new AppLogger({}, { component: "config" }, undefined, (options) => {
    const body = options?.body;
    if (!body) {
      return;
    }
    const prefix = `[message-bridge] ${body.message}`;
    const extra = body.extra ?? {};
    switch (body.level) {
      case "error":
        console.error(prefix, extra);
        break;
      case "warn":
        console.warn(prefix, extra);
        break;
      case "info":
        console.info(prefix, extra);
        break;
      default:
        console.debug(prefix, extra);
        break;
    }
  });
}
async function loadConfig(workspacePath, logger) {
  const configLogger = logger?.child({ component: "config" }) ?? createConsoleBackedLogger();
  const resolver = new ConfigResolver(configLogger);
  const config = await resolver.resolveConfig(workspacePath);
  const errors = validateConfig(config);
  if (errors.length > 0) {
    configLogger.error("config.validation.failed", {
      workspacePath,
      errorCount: errors.length,
      errors: errors.map((err) => ({
        code: err.code,
        path: err.path,
        message: err.message
      }))
    });
    throw new ConfigValidationAggregateError(errors);
  }
  configLogger.info("config.validation.passed", {
    workspacePath,
    configVersion: config.config_version,
    enabled: config.enabled
  });
  return config;
}
function validateConfig(config) {
  const validator = new ConfigValidator;
  return validator.validate(config);
}
// src/connection/AkSkAuth.ts
import { createHmac, randomUUID as randomUUID2 } from "crypto";

class DefaultAkSkAuth {
  accessKey;
  secretKey;
  constructor(accessKey, secretKey) {
    this.accessKey = accessKey;
    this.secretKey = secretKey;
  }
  generateAuthPayload() {
    const ts = Math.floor(Date.now() / 1000).toString();
    const nonce = randomUUID2();
    const sign = createHmac("sha256", this.secretKey).update(`${this.accessKey}${ts}${nonce}`).digest("base64");
    return {
      ak: this.accessKey,
      ts,
      nonce,
      sign
    };
  }
}

// src/connection/GatewayConnection.ts
import { EventEmitter } from "events";
function buildGatewaySendLogExtra(messageType, payloadBytes, logContext) {
  if (!logContext) {
    return { messageType, payloadBytes };
  }
  const { bridgeMessageId: _bridgeMessageId, ...rest } = logContext;
  return {
    messageType,
    payloadBytes,
    ...rest
  };
}
var GATEWAY_REJECTION_CLOSE_CODES = new Set([4403, 4408, 4409]);
function isRecord3(value) {
  return value !== null && typeof value === "object";
}
function extractWebSocketErrorDetails(event) {
  const details = {};
  if (!isRecord3(event)) {
    return {
      ...getErrorDetailsForLog(event)
    };
  }
  const baseError = event.error !== undefined && event.error !== event ? getErrorDetailsForLog(event.error) : getErrorDetailsForLog(event);
  Object.assign(details, baseError);
  if (typeof event.type === "string") {
    details.eventType = event.type;
  }
  if (!details.errorDetail && typeof event.message === "string" && event.message.trim()) {
    details.errorDetail = event.message;
  }
  const target = event.target;
  if (isRecord3(target) && typeof target.readyState === "number") {
    details.readyState = target.readyState;
  }
  return details;
}
function encodeBase64Url(value) {
  return Buffer.from(value, "utf8").toString("base64").replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/g, "");
}
function buildAuthSubprotocol(payload) {
  return `auth.${encodeBase64Url(JSON.stringify(payload))}`;
}
function isGatewayControlMessage(message) {
  if (!isRecord3(message) || typeof message.type !== "string") {
    return false;
  }
  return message.type === "register_ok" || message.type === "register_rejected";
}
function isGatewayRejectedCloseCode(code) {
  return typeof code === "number" && GATEWAY_REJECTION_CLOSE_CODES.has(code);
}

class DefaultGatewayConnection extends EventEmitter {
  options;
  ws = null;
  reconnectAttempts = 0;
  heartbeatTimer = null;
  reconnectTimer = null;
  manuallyDisconnected = false;
  state = "DISCONNECTED";
  lastMessageSummary = null;
  constructor(options) {
    super();
    this.options = options;
  }
  async connect() {
    this.options.logger?.info("gateway.connect.started", { url: this.options.url, state: this.state });
    if (this.options.abortSignal?.aborted) {
      this.manuallyDisconnected = true;
      this.setState("DISCONNECTED");
      this.options.logger?.warn("gateway.connect.aborted_precheck");
      throw new Error("gateway_connection_aborted");
    }
    this.setState("CONNECTING");
    return new Promise((resolve2, reject) => {
      let settled = false;
      let opened = false;
      const finalizeResolve = () => {
        if (settled) {
          return;
        }
        settled = true;
        cleanupAbortListener();
        resolve2();
      };
      const finalizeReject = (error) => {
        if (settled) {
          return;
        }
        settled = true;
        cleanupAbortListener();
        reject(error);
      };
      const abortHandler = () => {
        this.manuallyDisconnected = true;
        if (this.ws) {
          this.ws.close();
          this.ws = null;
        }
        this.teardownTimers();
        this.setState("DISCONNECTED");
        this.options.logger?.warn("gateway.connect.aborted");
        finalizeReject(new Error("gateway_connection_aborted"));
      };
      const cleanupAbortListener = () => {
        this.options.abortSignal?.removeEventListener("abort", abortHandler);
      };
      if (this.options.abortSignal) {
        this.options.abortSignal.addEventListener("abort", abortHandler, { once: true });
      }
      try {
        const url = new URL(this.options.url);
        const authPayload = this.options.authPayloadProvider?.();
        const protocols = authPayload ? [buildAuthSubprotocol(authPayload)] : undefined;
        const ws = protocols ? new WebSocket(url.toString(), protocols) : new WebSocket(url.toString());
        this.ws = ws;
        this.manuallyDisconnected = false;
        ws.onopen = () => {
          opened = true;
          this.reconnectAttempts = 0;
          this.options.logger?.info("gateway.open");
          this.setState("CONNECTED");
          this.send(this.options.registerMessage);
          this.options.logger?.info("gateway.register.sent", {
            toolType: this.options.registerMessage.toolType,
            toolVersion: this.options.registerMessage.toolVersion
          });
          finalizeResolve();
        };
        ws.onclose = (event) => {
          const rejected = isGatewayRejectedCloseCode(event?.code);
          this.options.logger?.warn("gateway.close", {
            opened,
            manuallyDisconnected: this.manuallyDisconnected,
            aborted: !!this.options.abortSignal?.aborted,
            rejected,
            code: event?.code,
            reason: event?.reason,
            wasClean: event?.wasClean,
            lastMessageDirection: this.lastMessageSummary?.direction,
            lastMessageType: this.lastMessageSummary?.messageType,
            lastMessageId: this.lastMessageSummary?.messageId,
            lastPayloadBytes: this.lastMessageSummary?.payloadBytes,
            lastEventType: this.lastMessageSummary?.eventType,
            lastOpencodeMessageId: this.lastMessageSummary?.opencodeMessageId
          });
          if (!opened) {
            finalizeReject(new Error("gateway_websocket_closed_before_open"));
          }
          this.teardownTimers();
          this.setState("DISCONNECTED");
          if (rejected) {
            this.options.logger?.warn("gateway.close.rejected", {
              code: event?.code,
              reason: event?.reason,
              rejected: true
            });
            return;
          }
          if (opened && !this.manuallyDisconnected && !this.options.abortSignal?.aborted) {
            this.attemptReconnect();
          }
        };
        ws.onerror = (event) => {
          const error = new Error("gateway_websocket_error");
          const errorDetails = extractWebSocketErrorDetails(event);
          this.options.logger?.error("gateway.error", {
            error: error.message,
            state: this.state,
            ...errorDetails
          });
          this.emit("error", error);
          if (this.state !== "DISCONNECTED") {
            this.setState("DISCONNECTED");
          }
          finalizeReject(error);
        };
        ws.onmessage = (event) => {
          this.handleMessage(event).catch((error) => {
            this.emit("error", error instanceof Error ? error : new Error(getErrorMessage(error)));
          });
        };
      } catch (error) {
        this.options.logger?.error("gateway.connect.failed", {
          error: getErrorMessage(error),
          ...getErrorDetailsForLog(error)
        });
        finalizeReject(error instanceof Error ? error : new Error(getErrorMessage(error)));
      }
    });
  }
  disconnect() {
    this.options.logger?.info("gateway.disconnect.requested", { state: this.state });
    this.manuallyDisconnected = true;
    if (this.reconnectTimer) {
      clearTimeout(this.reconnectTimer);
      this.reconnectTimer = null;
    }
    if (this.ws) {
      this.ws.close();
      this.ws = null;
    }
    this.teardownTimers();
    this.setState("DISCONNECTED");
  }
  send(message, logContext) {
    if (!this.isConnected() || !this.ws) {
      this.options.logger?.warn("gateway.send.rejected_not_connected", {
        state: this.state,
        messageType: message && typeof message === "object" && "type" in message ? String(message.type ?? "") : "unknown"
      });
      throw new Error("WebSocket is not connected. Cannot send message.");
    }
    const messageType = message && typeof message === "object" && "type" in message ? String(message.type ?? "") : "unknown";
    const isControlMessage = messageType === "register" || messageType === "heartbeat";
    if (this.state !== "READY" && !isControlMessage) {
      this.options.logger?.warn("gateway.send.rejected_not_ready", {
        state: this.state,
        messageType
      });
      throw new Error("Gateway connection is not ready. Cannot send business message.");
    }
    const serialized = JSON.stringify(message);
    const payloadBytes = Buffer.byteLength(serialized, "utf8");
    this.lastMessageSummary = {
      direction: "sent",
      messageType,
      messageId: logContext?.bridgeMessageId ?? logContext?.gatewayMessageId,
      payloadBytes,
      eventType: logContext?.eventType,
      opencodeMessageId: logContext?.opencodeMessageId
    };
    this.options.logger?.debug("gateway.send", {
      ...buildGatewaySendLogExtra(messageType, payloadBytes, logContext)
    });
    this.ws.send(serialized);
  }
  isConnected() {
    return this.ws !== null && this.ws.readyState === WebSocket.OPEN;
  }
  getState() {
    return this.state;
  }
  setupHeartbeat() {
    this.teardownTimers();
    const heartbeatIntervalMs = this.options.heartbeatIntervalMs ?? 30000;
    this.heartbeatTimer = setInterval(() => {
      if (!this.isConnected() || !this.ws) {
        return;
      }
      const heartbeat = {
        type: "heartbeat",
        timestamp: new Date().toISOString()
      };
      this.ws.send(JSON.stringify(heartbeat));
      this.options.logger?.debug("gateway.heartbeat.sent");
    }, heartbeatIntervalMs);
  }
  teardownTimers() {
    if (this.heartbeatTimer) {
      clearInterval(this.heartbeatTimer);
      this.heartbeatTimer = null;
    }
  }
  attemptReconnect() {
    if (this.options.abortSignal?.aborted) {
      return;
    }
    this.reconnectAttempts += 1;
    const base = this.options.reconnectBaseMs ?? 1000;
    const cap = this.options.reconnectMaxMs ?? 30000;
    const exp = this.options.reconnectExponential ?? true;
    const delay = exp ? Math.min(base * Math.pow(2, this.reconnectAttempts - 1), cap) : Math.min(base, cap);
    this.options.logger?.warn("gateway.reconnect.scheduled", {
      attempt: this.reconnectAttempts,
      delayMs: delay
    });
    this.reconnectTimer = setTimeout(async () => {
      if (this.manuallyDisconnected || this.options.abortSignal?.aborted) {
        return;
      }
      try {
        this.options.logger?.info("gateway.reconnect.attempt", {
          attempt: this.reconnectAttempts
        });
        await this.connect();
      } catch (error) {
        this.options.logger?.warn("gateway.reconnect.failed", {
          attempt: this.reconnectAttempts,
          error: getErrorMessage(error),
          ...getErrorDetailsForLog(error)
        });
        if (!this.manuallyDisconnected) {
          this.attemptReconnect();
        }
      }
    }, delay);
  }
  async handleMessage(event) {
    let text;
    if (typeof event.data === "string") {
      text = event.data;
    } else if (event.data instanceof Uint8Array) {
      text = new TextDecoder().decode(event.data);
    } else if (event.data instanceof ArrayBuffer) {
      text = new TextDecoder().decode(new Uint8Array(event.data));
    } else {
      text = await event.data.text();
    }
    const frameBytes = Buffer.byteLength(text, "utf8");
    try {
      const message = JSON.parse(text);
      const messageType = message && typeof message === "object" && "type" in message ? String(message.type ?? "") : "unknown";
      const gatewayMessageId = this.extractGatewayMessageId(message);
      this.lastMessageSummary = {
        direction: "received",
        messageType,
        messageId: gatewayMessageId,
        payloadBytes: frameBytes
      };
      this.options.logger?.debug("gateway.message.received", { messageType, frameBytes, gatewayMessageId });
      if (isGatewayControlMessage(message)) {
        this.handleControlMessage(message);
        return;
      }
      if (this.state !== "READY") {
        this.options.logger?.warn("gateway.message.ignored_not_ready", {
          state: this.state,
          messageType,
          gatewayMessageId
        });
        return;
      }
      this.emit("message", message);
    } catch {
      this.options.logger?.debug("gateway.message.ignored_non_json", {
        payloadLength: text.length,
        frameBytes
      });
    }
  }
  extractGatewayMessageId(message) {
    if (!isRecord3(message)) {
      return;
    }
    return typeof message.messageId === "string" ? message.messageId : undefined;
  }
  handleControlMessage(message) {
    if (message.type === "register_ok") {
      if (this.state === "READY") {
        this.options.logger?.warn("gateway.register.duplicate_ok");
        return;
      }
      this.setState("READY");
      this.options.logger?.info("gateway.register.accepted");
      this.setupHeartbeat();
      this.options.logger?.info("gateway.ready");
      return;
    }
    const reason = typeof message.reason === "string" ? message.reason : undefined;
    this.options.logger?.error("gateway.register.rejected", { reason });
    this.manuallyDisconnected = true;
    if (this.ws) {
      this.ws.close();
    }
  }
  setState(next) {
    this.state = next;
    this.emit("stateChange", next);
  }
}

// src/connection/StateManager.ts
import { randomUUID as randomUUID3 } from "crypto";
class DefaultStateManager {
  state = "DISCONNECTED";
  agentId = null;
  isReady() {
    return this.state === "READY";
  }
  getAgentId() {
    return this.agentId;
  }
  getState() {
    return this.state;
  }
  setState(state) {
    this.state = state;
  }
  generateAndBindAgentId() {
    const id = `${AGENT_ID_PREFIX}${randomUUID3()}`;
    this.agentId = id;
    return id;
  }
  resetForReconnect() {
    return this.generateAndBindAgentId();
  }
}

// src/event/EventFilter.ts
class EventFilter {
  exactPatterns = new Set;
  constructor(allowlist = DEFAULT_EVENT_ALLOWLIST) {
    for (const pattern of allowlist) {
      this.exactPatterns.add(pattern);
    }
  }
  isAllowed(eventType) {
    return this.exactPatterns.has(eventType);
  }
}
// src/protocol/upstream/UpstreamEventExtractor.ts
var EXTRACTION_LOG_EVENT = "event.extraction_failed";
function ok(value) {
  return { ok: true, value };
}
function fail(error) {
  return { ok: false, error };
}
function missingRequiredField(eventType, stage, field, messageId, toolSessionId) {
  return fail({
    stage,
    code: "missing_required_field",
    eventType,
    field,
    message: `${field} is required`,
    messageId,
    toolSessionId
  });
}
function invalidFieldType(eventType, stage, field, expectedType, messageId, toolSessionId) {
  return fail({
    stage,
    code: "invalid_field_type",
    eventType,
    field,
    message: `${field} must be ${expectedType}`,
    messageId,
    toolSessionId
  });
}
function requireNonEmptyString(value, eventType, stage, field, messageId, toolSessionId) {
  if (value === undefined) {
    return missingRequiredField(eventType, stage, field, messageId, toolSessionId);
  }
  if (typeof value !== "string") {
    return invalidFieldType(eventType, stage, field, "a non-empty string", messageId, toolSessionId);
  }
  const normalized = value.trim();
  if (!normalized) {
    return missingRequiredField(eventType, stage, field, messageId, toolSessionId);
  }
  return ok(normalized);
}
function requireMessageRole(value, eventType, messageId, toolSessionId) {
  const roleResult = requireNonEmptyString(value, eventType, "extra", "properties.info.role", messageId, toolSessionId);
  if (!roleResult.ok) {
    return roleResult;
  }
  if (roleResult.value !== "user" && roleResult.value !== "assistant") {
    return invalidFieldType(eventType, "extra", "properties.info.role", '"user" or "assistant"', messageId, toolSessionId);
  }
  return ok(roleResult.value);
}
function noExtra(_event) {
  return ok(undefined);
}
function buildCommon(eventType, toolSessionId) {
  return { eventType, toolSessionId };
}
function extractMessageUpdatedCommon(event) {
  const sessionResult = requireNonEmptyString(event.properties.info.sessionID, event.type, "common", "properties.info.sessionID");
  if (!sessionResult.ok)
    return sessionResult;
  return ok(buildCommon(event.type, sessionResult.value));
}
function extractMessageUpdatedExtra(event, common2) {
  const messageIdResult = requireNonEmptyString(event.properties.info.id, event.type, "extra", "properties.info.id", undefined, common2.toolSessionId);
  if (!messageIdResult.ok)
    return messageIdResult;
  const roleResult = requireMessageRole(event.properties.info.role, event.type, messageIdResult.value, common2.toolSessionId);
  if (!roleResult.ok)
    return roleResult;
  return ok({
    kind: "message.updated",
    messageId: messageIdResult.value,
    role: roleResult.value
  });
}
function extractMessagePartUpdatedCommon(event) {
  const sessionResult = requireNonEmptyString(event.properties.part.sessionID, event.type, "common", "properties.part.sessionID");
  if (!sessionResult.ok)
    return sessionResult;
  return ok(buildCommon(event.type, sessionResult.value));
}
function extractMessagePartUpdatedExtra(event, common2) {
  const messageIdResult = requireNonEmptyString(event.properties.part.messageID, event.type, "extra", "properties.part.messageID", undefined, common2.toolSessionId);
  if (!messageIdResult.ok)
    return messageIdResult;
  const partIdResult = requireNonEmptyString(event.properties.part.id, event.type, "extra", "properties.part.id", messageIdResult.value, common2.toolSessionId);
  if (!partIdResult.ok)
    return partIdResult;
  return ok({
    kind: "message.part.updated",
    messageId: messageIdResult.value,
    partId: partIdResult.value
  });
}
function extractMessagePartDeltaCommon(event) {
  const sessionResult = requireNonEmptyString(event.properties.sessionID, event.type, "common", "properties.sessionID");
  if (!sessionResult.ok)
    return sessionResult;
  return ok(buildCommon(event.type, sessionResult.value));
}
function extractMessagePartDeltaExtra(event, common2) {
  const messageIdResult = requireNonEmptyString(event.properties.messageID, event.type, "extra", "properties.messageID", undefined, common2.toolSessionId);
  if (!messageIdResult.ok)
    return messageIdResult;
  const partIdResult = requireNonEmptyString(event.properties.partID, event.type, "extra", "properties.partID", messageIdResult.value, common2.toolSessionId);
  if (!partIdResult.ok)
    return partIdResult;
  return ok({
    kind: "message.part.delta",
    messageId: messageIdResult.value,
    partId: partIdResult.value
  });
}
function extractMessagePartRemovedCommon(event) {
  const sessionResult = requireNonEmptyString(event.properties.sessionID, event.type, "common", "properties.sessionID");
  if (!sessionResult.ok)
    return sessionResult;
  return ok(buildCommon(event.type, sessionResult.value));
}
function extractMessagePartRemovedExtra(event, common2) {
  const messageIdResult = requireNonEmptyString(event.properties.messageID, event.type, "extra", "properties.messageID", undefined, common2.toolSessionId);
  if (!messageIdResult.ok)
    return messageIdResult;
  const partIdResult = requireNonEmptyString(event.properties.partID, event.type, "extra", "properties.partID", messageIdResult.value, common2.toolSessionId);
  if (!partIdResult.ok)
    return partIdResult;
  return ok({
    kind: "message.part.removed",
    messageId: messageIdResult.value,
    partId: partIdResult.value
  });
}
function extractSessionStatusCommon(event) {
  const sessionResult = requireNonEmptyString(event.properties.sessionID, event.type, "common", "properties.sessionID");
  if (!sessionResult.ok)
    return sessionResult;
  return ok(buildCommon(event.type, sessionResult.value));
}
function extractSessionStatusExtra(event, common2) {
  const statusResult = requireNonEmptyString(event.properties.status?.type, event.type, "extra", "properties.status.type", undefined, common2.toolSessionId);
  if (!statusResult.ok)
    return statusResult;
  return ok({
    kind: "session.status",
    status: statusResult.value
  });
}
function extractSessionIdleCommon(event) {
  const sessionResult = requireNonEmptyString(event.properties.sessionID, event.type, "common", "properties.sessionID");
  if (!sessionResult.ok)
    return sessionResult;
  return ok(buildCommon(event.type, sessionResult.value));
}
function extractSessionUpdatedCommon(event) {
  const sessionResult = requireNonEmptyString(event.properties.info.id, event.type, "common", "properties.info.id");
  if (!sessionResult.ok)
    return sessionResult;
  return ok(buildCommon(event.type, sessionResult.value));
}
function extractSessionErrorCommon(event) {
  const sessionResult = requireNonEmptyString(event.properties.sessionID, event.type, "common", "properties.sessionID");
  if (!sessionResult.ok)
    return sessionResult;
  return ok(buildCommon(event.type, sessionResult.value));
}
function extractPermissionCommon(event) {
  const sessionResult = requireNonEmptyString(event.properties.sessionID, event.type, "common", "properties.sessionID");
  if (!sessionResult.ok)
    return sessionResult;
  return ok(buildCommon(event.type, sessionResult.value));
}
function extractQuestionAskedCommon(event) {
  const sessionResult = requireNonEmptyString(event.properties.sessionID, event.type, "common", "properties.sessionID");
  if (!sessionResult.ok)
    return sessionResult;
  return ok(buildCommon(event.type, sessionResult.value));
}
var UPSTREAM_EVENT_EXTRACTORS = {
  "message.updated": { extractCommon: extractMessageUpdatedCommon, extractExtra: extractMessageUpdatedExtra },
  "message.part.updated": { extractCommon: extractMessagePartUpdatedCommon, extractExtra: extractMessagePartUpdatedExtra },
  "message.part.delta": { extractCommon: extractMessagePartDeltaCommon, extractExtra: extractMessagePartDeltaExtra },
  "message.part.removed": { extractCommon: extractMessagePartRemovedCommon, extractExtra: extractMessagePartRemovedExtra },
  "session.status": { extractCommon: extractSessionStatusCommon, extractExtra: extractSessionStatusExtra },
  "session.idle": { extractCommon: extractSessionIdleCommon, extractExtra: noExtra },
  "session.updated": { extractCommon: extractSessionUpdatedCommon, extractExtra: noExtra },
  "session.error": { extractCommon: extractSessionErrorCommon, extractExtra: noExtra },
  "permission.updated": { extractCommon: extractPermissionCommon, extractExtra: noExtra },
  "permission.asked": { extractCommon: extractPermissionCommon, extractExtra: noExtra },
  "question.asked": { extractCommon: extractQuestionAskedCommon, extractExtra: noExtra }
};
function buildEventPreview(event) {
  const hasProperties = typeof event === "object" && event !== null && "properties" in event && typeof event.properties === "object" && event.properties !== null;
  return {
    type: event.type,
    hasProperties,
    propertyKeys: hasProperties ? Object.keys(event.properties).slice(0, 8) : []
  };
}
function logExtractionFailure(logger, event, error) {
  logger.warn(EXTRACTION_LOG_EVENT, {
    eventType: error.eventType,
    stage: error.stage,
    errorCode: error.code,
    field: error.field,
    message: error.message,
    messageId: error.messageId,
    toolSessionId: error.toolSessionId,
    eventPreview: buildEventPreview(event)
  });
}
function extractUpstreamEvent(event, logger) {
  if (!isSupportedUpstreamEventType(event.type)) {
    const error = {
      stage: "common",
      code: "unsupported_event",
      eventType: event.type,
      field: "type",
      message: `Unsupported upstream event type: ${event.type}`
    };
    logExtractionFailure(logger, event, error);
    return fail(error);
  }
  const typedEvent = event;
  const extractor = UPSTREAM_EVENT_EXTRACTORS[event.type];
  const commonResult = extractor.extractCommon(typedEvent);
  if (!commonResult.ok) {
    logExtractionFailure(logger, event, commonResult.error);
    return commonResult;
  }
  const extraResult = extractor.extractExtra(typedEvent, commonResult.value);
  if (!extraResult.ok) {
    logExtractionFailure(logger, event, extraResult.error);
    return extraResult;
  }
  return ok({
    common: commonResult.value,
    extra: extraResult.value,
    raw: typedEvent
  });
}
// src/protocol/downstream/SupportedDownstreamMessages.ts
var SUPPORTED_DOWNSTREAM_MESSAGE_TYPES = DOWNSTREAM_MESSAGE_TYPES;
var SUPPORTED_INVOKE_ACTIONS = INVOKE_ACTIONS;
var SUPPORTED_DOWNSTREAM_MESSAGE_TYPE_SET = new Set(SUPPORTED_DOWNSTREAM_MESSAGE_TYPES);
var SUPPORTED_INVOKE_ACTION_SET = new Set(SUPPORTED_INVOKE_ACTIONS);
function isSupportedDownstreamMessageType(value) {
  return SUPPORTED_DOWNSTREAM_MESSAGE_TYPE_SET.has(value);
}
function isSupportedInvokeAction(value) {
  return SUPPORTED_INVOKE_ACTION_SET.has(value);
}
// src/protocol/downstream/DownstreamMessageNormalizer.ts
var DOWNSTREAM_NORMALIZATION_LOG_EVENT = "downstream.normalization_failed";
function isRecord4(value) {
  return typeof value === "object" && value !== null;
}
function ok2(value) {
  return { ok: true, value };
}
function fail2(error) {
  return { ok: false, error };
}
function missingRequiredField2(stage, field, messageType, action, welinkSessionId) {
  return fail2({
    stage,
    code: "missing_required_field",
    field,
    message: `${field} is required`,
    messageType,
    action,
    welinkSessionId
  });
}
function invalidFieldType2(stage, field, expectedType, messageType, action, welinkSessionId) {
  return fail2({
    stage,
    code: "invalid_field_type",
    field,
    message: `${field} must be ${expectedType}`,
    messageType,
    action,
    welinkSessionId
  });
}
function unsupportedMessage(messageType) {
  return fail2({
    stage: "message",
    code: "unsupported_message",
    field: "type",
    message: `Unsupported downstream message type: ${messageType}`,
    messageType
  });
}
function unsupportedAction(action, welinkSessionId) {
  return fail2({
    stage: "payload",
    code: "unsupported_action",
    field: "action",
    message: `Unsupported invoke action: ${action}`,
    messageType: "invoke",
    action,
    welinkSessionId
  });
}
function errorOf(result) {
  if (result.ok) {
    throw new Error("Expected failed normalization result");
  }
  return result.error;
}
function requireNonEmptyString2(value, stage, field, messageType, action, welinkSessionId) {
  if (value === undefined) {
    return missingRequiredField2(stage, field, messageType, action, welinkSessionId);
  }
  if (typeof value !== "string") {
    return invalidFieldType2(stage, field, "a non-empty string", messageType, action, welinkSessionId);
  }
  if (!value.trim()) {
    return missingRequiredField2(stage, field, messageType, action, welinkSessionId);
  }
  return ok2(value);
}
function buildEventPreview2(raw) {
  if (!isRecord4(raw)) {
    return { kind: typeof raw };
  }
  return {
    type: typeof raw.type === "string" ? raw.type : undefined,
    keys: Object.keys(raw).slice(0, 8)
  };
}
function logDownstreamNormalizationFailure(logger, raw, error) {
  logger.warn(DOWNSTREAM_NORMALIZATION_LOG_EVENT, {
    stage: error.stage,
    errorCode: error.code,
    field: error.field,
    message: error.message,
    messageType: error.messageType,
    action: error.action,
    welinkSessionId: error.welinkSessionId,
    messagePreview: buildEventPreview2(raw)
  });
}
function normalizeChatPayload(payload, welinkSessionId) {
  if (!isRecord4(payload)) {
    return invalidFieldType2("payload", "payload", "an object", "invoke", "chat", welinkSessionId);
  }
  const toolSessionId = requireNonEmptyString2(payload.toolSessionId, "payload", "payload.toolSessionId", "invoke", "chat", welinkSessionId);
  if (!toolSessionId.ok)
    return toolSessionId;
  const text = requireNonEmptyString2(payload.text, "payload", "payload.text", "invoke", "chat", welinkSessionId);
  if (!text.ok)
    return text;
  return ok2({
    toolSessionId: toolSessionId.value,
    text: text.value
  });
}
function normalizeCreateSessionPayload(payload, welinkSessionId) {
  if (!isRecord4(payload)) {
    return invalidFieldType2("payload", "payload", "an object", "invoke", "create_session", welinkSessionId);
  }
  return ok2({
    sessionId: typeof payload.sessionId === "string" ? payload.sessionId : undefined,
    metadata: isRecord4(payload.metadata) ? payload.metadata : undefined
  });
}
function normalizeCloseSessionPayload(payload, welinkSessionId) {
  if (!isRecord4(payload)) {
    return invalidFieldType2("payload", "payload", "an object", "invoke", "close_session", welinkSessionId);
  }
  const toolSessionId = requireNonEmptyString2(payload.toolSessionId, "payload", "payload.toolSessionId", "invoke", "close_session", welinkSessionId);
  if (!toolSessionId.ok)
    return toolSessionId;
  return ok2({ toolSessionId: toolSessionId.value });
}
function normalizePermissionReplyPayload(payload, welinkSessionId) {
  if (!isRecord4(payload)) {
    return invalidFieldType2("payload", "payload", "an object", "invoke", "permission_reply", welinkSessionId);
  }
  const permissionId = requireNonEmptyString2(payload.permissionId, "payload", "payload.permissionId", "invoke", "permission_reply", welinkSessionId);
  if (!permissionId.ok)
    return permissionId;
  const toolSessionId = requireNonEmptyString2(payload.toolSessionId, "payload", "payload.toolSessionId", "invoke", "permission_reply", welinkSessionId);
  if (!toolSessionId.ok)
    return toolSessionId;
  if (payload.response !== "once" && payload.response !== "always" && payload.response !== "reject") {
    return invalidFieldType2("payload", "payload.response", '"once", "always", or "reject"', "invoke", "permission_reply", welinkSessionId);
  }
  return ok2({
    permissionId: permissionId.value,
    toolSessionId: toolSessionId.value,
    response: payload.response
  });
}
function normalizeAbortSessionPayload(payload, welinkSessionId) {
  if (!isRecord4(payload)) {
    return invalidFieldType2("payload", "payload", "an object", "invoke", "abort_session", welinkSessionId);
  }
  const toolSessionId = requireNonEmptyString2(payload.toolSessionId, "payload", "payload.toolSessionId", "invoke", "abort_session", welinkSessionId);
  if (!toolSessionId.ok)
    return toolSessionId;
  return ok2({ toolSessionId: toolSessionId.value });
}
function normalizeQuestionReplyPayload(payload, welinkSessionId) {
  if (!isRecord4(payload)) {
    return invalidFieldType2("payload", "payload", "an object", "invoke", "question_reply", welinkSessionId);
  }
  const toolSessionId = requireNonEmptyString2(payload.toolSessionId, "payload", "payload.toolSessionId", "invoke", "question_reply", welinkSessionId);
  if (!toolSessionId.ok)
    return toolSessionId;
  const answer = requireNonEmptyString2(payload.answer, "payload", "payload.answer", "invoke", "question_reply", welinkSessionId);
  if (!answer.ok)
    return answer;
  if (payload.toolCallId !== undefined) {
    const toolCallId = requireNonEmptyString2(payload.toolCallId, "payload", "payload.toolCallId", "invoke", "question_reply", welinkSessionId);
    if (!toolCallId.ok)
      return toolCallId;
    return ok2({
      toolSessionId: toolSessionId.value,
      answer: answer.value,
      toolCallId: toolCallId.value
    });
  }
  return ok2({
    toolSessionId: toolSessionId.value,
    answer: answer.value
  });
}
function normalizeInvokePayload(action, payload, welinkSessionId) {
  switch (action) {
    case "chat": {
      const normalized = normalizeChatPayload(payload, welinkSessionId);
      if (!normalized.ok)
        return normalized;
      return ok2({ type: "invoke", action, payload: normalized.value, welinkSessionId });
    }
    case "create_session": {
      const normalized = normalizeCreateSessionPayload(payload, welinkSessionId);
      if (!normalized.ok)
        return normalized;
      return ok2({ type: "invoke", action, payload: normalized.value, welinkSessionId });
    }
    case "close_session": {
      const normalized = normalizeCloseSessionPayload(payload, welinkSessionId);
      if (!normalized.ok)
        return normalized;
      return ok2({ type: "invoke", action, payload: normalized.value, welinkSessionId });
    }
    case "permission_reply": {
      const normalized = normalizePermissionReplyPayload(payload, welinkSessionId);
      if (!normalized.ok)
        return normalized;
      return ok2({ type: "invoke", action, payload: normalized.value, welinkSessionId });
    }
    case "abort_session": {
      const normalized = normalizeAbortSessionPayload(payload, welinkSessionId);
      if (!normalized.ok)
        return normalized;
      return ok2({ type: "invoke", action, payload: normalized.value, welinkSessionId });
    }
    case "question_reply": {
      const normalized = normalizeQuestionReplyPayload(payload, welinkSessionId);
      if (!normalized.ok)
        return normalized;
      return ok2({ type: "invoke", action, payload: normalized.value, welinkSessionId });
    }
  }
}
function normalizeDownstreamMessage(raw, logger) {
  if (!isRecord4(raw)) {
    const error = errorOf(invalidFieldType2("message", "message", "an object"));
    logDownstreamNormalizationFailure(logger, raw, error);
    return fail2(error);
  }
  const messageTypeValue = raw.type;
  if (typeof messageTypeValue !== "string") {
    const error = errorOf(missingRequiredField2("message", "type"));
    logDownstreamNormalizationFailure(logger, raw, error);
    return fail2(error);
  }
  if (!isSupportedDownstreamMessageType(messageTypeValue)) {
    const error = errorOf(unsupportedMessage(messageTypeValue));
    logDownstreamNormalizationFailure(logger, raw, error);
    return fail2(error);
  }
  const welinkSessionId = typeof raw.welinkSessionId === "string" ? raw.welinkSessionId : undefined;
  if (messageTypeValue === "status_query") {
    return ok2({
      type: "status_query"
    });
  }
  const actionValue = raw.action;
  if (typeof actionValue !== "string") {
    const error = errorOf(missingRequiredField2("payload", "action", "invoke", undefined, welinkSessionId));
    logDownstreamNormalizationFailure(logger, raw, error);
    return fail2(error);
  }
  if (!isSupportedInvokeAction(actionValue)) {
    const error = errorOf(unsupportedAction(actionValue, welinkSessionId));
    logDownstreamNormalizationFailure(logger, raw, error);
    return fail2(error);
  }
  const normalized = normalizeInvokePayload(actionValue, raw.payload, welinkSessionId);
  if (!normalized.ok) {
    logDownstreamNormalizationFailure(logger, raw, normalized.error);
    return normalized;
  }
  return ok2(normalized.value);
}
// src/runtime/SdkAdapter.ts
function createSdkAdapter(client) {
  if (!client || typeof client !== "object") {
    return client;
  }
  const c = client;
  if (!c.session || !c.postSessionIdPermissionsPermissionId) {
    return client;
  }
  const rawClient = c._client && typeof c._client === "object" ? {
    get: typeof c._client.get === "function" ? c._client.get.bind(c._client) : undefined,
    post: typeof c._client.post === "function" ? c._client.post.bind(c._client) : undefined
  } : undefined;
  return {
    session: {
      create: async (options) => {
        return c.session.create(options);
      },
      abort: async (options) => {
        return c.session.abort(options);
      },
      delete: async (options) => {
        if (!c.session.delete) {
          throw new Error("SDK session.delete is not available");
        }
        return c.session.delete(options);
      },
      prompt: async (options) => {
        return c.session.prompt(options);
      }
    },
    postSessionIdPermissionsPermissionId: async (options) => {
      return c.postSessionIdPermissionsPermissionId(options);
    },
    _client: rawClient,
    app: c.app
  };
}

// src/runtime/BridgeRuntime.ts
var UNKNOWN_MAC_ADDRESS = "unknown";
function isUsableMacAddress(macAddress) {
  if (!macAddress) {
    return false;
  }
  const normalized = macAddress.trim().toLowerCase();
  return normalized.length > 0 && normalized !== "00:00:00:00:00:00" && normalized !== "00-00-00-00-00-00";
}
function resolveMacAddress(configuredMacAddress, logger) {
  if (isUsableMacAddress(configuredMacAddress)) {
    return configuredMacAddress;
  }
  const interfaces = os.networkInterfaces();
  let interfaceCount = 0;
  for (const entries of Object.values(interfaces)) {
    if (!entries) {
      continue;
    }
    interfaceCount += entries.length;
    for (const entry of entries) {
      if (entry.internal || !isUsableMacAddress(entry.mac)) {
        continue;
      }
      return entry.mac.trim().toLowerCase();
    }
  }
  logger.warn("runtime.mac_address.fallback_unknown", {
    platform: os.platform(),
    interfaceCount
  });
  return UNKNOWN_MAC_ADDRESS;
}

class BridgeRuntime {
  options;
  actionRouter = new DefaultActionRouter;
  stateManager = new DefaultStateManager;
  registry = new DefaultActionRegistry;
  gatewayConnection = null;
  eventFilter = null;
  started = false;
  sdkClient;
  logger;
  constructor(options) {
    this.options = options;
    this.logger = new AppLogger(options.client, { component: "runtime" }, undefined, undefined, options.debug);
    this.sdkClient = createSdkAdapter(options.client);
    this.registerActions();
    this.actionRouter.setRegistry(this.registry);
  }
  async start(options = {}) {
    this.logger.info("runtime.start.requested", { workspacePath: this.options.workspacePath });
    if (this.started) {
      this.logger.debug("runtime.start.skipped_already_started");
      return;
    }
    if (options.abortSignal?.aborted) {
      this.logger.warn("runtime.start.aborted_precheck");
      throw new Error("runtime_start_aborted");
    }
    let config;
    try {
      this.logger.info("runtime.config.loading", { workspacePath: this.options.workspacePath });
      config = await loadConfig(this.options.workspacePath, this.logger);
      if (this.options.debug === undefined && typeof config.debug === "boolean") {
        this.logger = new AppLogger(this.options.client, { component: "runtime" }, this.logger.getTraceId(), undefined, config.debug);
      }
      this.logger.info("runtime.config.loaded_successfully", {
        config_version: config.config_version,
        enabled: config.enabled,
        gateway_url: config.gateway.url
      });
    } catch (error) {
      const errorMessage = error instanceof Error ? error.message : String(error);
      this.logger.error("runtime.config.loading_failed", {
        error: errorMessage,
        workspacePath: this.options.workspacePath
      });
      throw error;
    }
    if (!config.enabled) {
      this.logger.info("runtime.start.disabled_by_config");
      this.started = true;
      return;
    }
    const agentId = this.stateManager.generateAndBindAgentId();
    this.eventFilter = new EventFilter(config.events.allowlist);
    const auth = new DefaultAkSkAuth(config.auth.ak, config.auth.sk);
    const authPayloadProvider = () => auth.generateAuthPayload();
    const connection = new DefaultGatewayConnection({
      url: config.gateway.url,
      reconnectBaseMs: config.gateway.reconnect.baseMs,
      reconnectMaxMs: config.gateway.reconnect.maxMs,
      reconnectExponential: config.gateway.reconnect.exponential,
      heartbeatIntervalMs: config.gateway.heartbeatIntervalMs,
      abortSignal: options.abortSignal,
      authPayloadProvider,
      registerMessage: {
        type: "register",
        deviceName: config.gateway.deviceName,
        macAddress: resolveMacAddress(config.gateway.macAddress, this.logger),
        os: os.platform(),
        toolType: config.gateway.toolType,
        toolVersion: config.gateway.toolVersion
      },
      logger: this.logger.child({ component: "gateway" })
    });
    connection.on("stateChange", (state) => {
      this.stateManager.setState(state);
      this.logger.info("gateway.state.changed", { state });
      if (state === "CONNECTING") {
        const nextAgentId = this.stateManager.resetForReconnect();
        this.logger.info("runtime.agent.rebound", { agentId: nextAgentId });
      }
    });
    connection.on("message", (message) => {
      const messageType = message && typeof message === "object" && "type" in message ? String(message.type ?? "") : "unknown";
      this.logger.debug("gateway.message.received", { messageType });
      this.handleDownstreamMessage(message).catch((error) => {
        this.logger.error("runtime.downstream_message_error", {
          error: error instanceof Error ? error.message : String(error)
        });
      });
    });
    this.gatewayConnection = connection;
    if (options.abortSignal?.aborted) {
      this.gatewayConnection.disconnect();
      this.gatewayConnection = null;
      this.logger.warn("runtime.start.aborted_before_connect");
      throw new Error("runtime_start_aborted");
    }
    await connection.connect();
    if (options.abortSignal?.aborted) {
      this.gatewayConnection.disconnect();
      this.gatewayConnection = null;
      this.logger.warn("runtime.start.aborted_after_connect");
      throw new Error("runtime_start_aborted");
    }
    this.started = true;
    this.logger.info("runtime.start.completed", { agentId: this.stateManager.getAgentId() });
  }
  stop() {
    this.logger.info("runtime.stop.requested");
    if (this.gatewayConnection) {
      this.gatewayConnection.disconnect();
      this.gatewayConnection = null;
    }
    this.started = false;
    this.logger.info("runtime.stop.completed");
  }
  async handleEvent(event) {
    const extraction = extractUpstreamEvent(event, this.logger);
    if (!extraction.ok) {
      return;
    }
    const normalized = extraction.value;
    const eventFields = this.buildEventLogFields(normalized);
    const eventTraceId = eventFields.opencodeMessageId ?? this.logger.getTraceId();
    const eventLogger = this.createMessageLogger(eventFields, eventTraceId);
    eventLogger.debug("event.received");
    if (!this.stateManager.isReady() || !this.gatewayConnection || !this.eventFilter) {
      eventLogger.debug("event.ignored_not_ready", {
        state: this.stateManager.getState()
      });
      return;
    }
    if (!this.eventFilter.isAllowed(event.type)) {
      eventLogger.warn("event.rejected_allowlist");
      return;
    }
    const bridgeMessageId = randomUUID4();
    const forwardingLogger = this.createMessageLogger(eventFields, bridgeMessageId);
    this.logEventForwardingDetail(normalized, forwardingLogger);
    forwardingLogger.info("event.forwarding");
    this.gatewayConnection.send({
      type: "tool_event",
      toolSessionId: normalized.common.toolSessionId,
      event: normalized.raw
    }, {
      traceId: bridgeMessageId,
      runtimeTraceId: this.logger.getTraceId(),
      gatewayMessageId: bridgeMessageId,
      toolSessionId: normalized.common.toolSessionId,
      eventType: normalized.common.eventType,
      opencodeMessageId: eventFields.opencodeMessageId,
      opencodePartId: eventFields.opencodePartId,
      toolCallId: eventFields.toolCallId ?? undefined
    });
    forwardingLogger.debug("event.forwarded");
  }
  getStarted() {
    return this.started;
  }
  registerActions() {
    const actions2 = [
      new ChatAction,
      new CreateSessionAction,
      new CloseSessionAction,
      new PermissionReplyAction,
      new StatusQueryAction,
      new AbortSessionAction,
      new QuestionReplyAction
    ];
    for (const action of actions2) {
      this.registry.register(action);
    }
  }
  async handleDownstreamMessage(raw) {
    if (!this.gatewayConnection) {
      this.logger.warn("runtime.downstream_ignored_no_connection");
      return;
    }
    const startedAt = Date.now();
    const downstreamFields = this.extractDownstreamLogFields(raw);
    const traceId = downstreamFields.gatewayMessageId ?? this.logger.getTraceId();
    const messageLogger = this.createMessageLogger(downstreamFields, traceId);
    const normalized = normalizeDownstreamMessage(raw, this.logger);
    if (!normalized.ok) {
      messageLogger.warn("runtime.downstream_ignored_non_protocol", {
        messageType: normalized.error.messageType ?? "unknown",
        hasWelinkSessionId: !!normalized.error.welinkSessionId
      });
      if (normalized.error.messageType === "invoke") {
        this.sendToolError({ success: false, errorCode: "INVALID_PAYLOAD", errorMessage: "Invalid invoke payload shape" }, normalized.error.welinkSessionId, {
          logger: messageLogger,
          traceId,
          gatewayMessageId: downstreamFields.gatewayMessageId,
          action: downstreamFields.action,
          toolSessionId: downstreamFields.toolSessionId
        });
      }
      return;
    }
    const message = normalized.value;
    if (message.type === "status_query") {
      const statusLogger = this.createMessageLogger({ ...downstreamFields }, traceId);
      statusLogger.info("runtime.status_query.received");
      const payload = {};
      const result2 = await this.actionRouter.route("status_query", payload, this.buildActionContext(undefined, statusLogger));
      if (!result2.success) {
        this.sendToolError(result2, undefined, {
          logger: statusLogger,
          traceId,
          gatewayMessageId: downstreamFields.gatewayMessageId,
          action: "status_query"
        });
        return;
      }
      this.gatewayConnection.send({
        type: "status_response",
        opencodeOnline: result2.data.opencodeOnline
      }, {
        traceId,
        runtimeTraceId: this.logger.getTraceId(),
        gatewayMessageId: downstreamFields.gatewayMessageId,
        action: "status_query"
      });
      statusLogger.info("runtime.status_query.responded", {
        latencyMs: Date.now() - startedAt
      });
      return;
    }
    const welinkSessionId = message.welinkSessionId;
    const toolSessionId = "payload" in message && message.payload && typeof message.payload === "object" && "toolSessionId" in message.payload && typeof message.payload.toolSessionId === "string" ? message.payload.toolSessionId : undefined;
    const invokeLogger = this.createMessageLogger({
      ...downstreamFields,
      welinkSessionId,
      action: message.action,
      toolSessionId
    }, traceId);
    invokeLogger.info("runtime.invoke.received");
    if (message.action === "create_session") {
      const result2 = await this.actionRouter.route(message.action, message.payload, this.buildActionContext(welinkSessionId, invokeLogger));
      if (!result2.success) {
        this.sendToolError(result2, welinkSessionId, {
          logger: invokeLogger,
          traceId,
          gatewayMessageId: downstreamFields.gatewayMessageId,
          action: message.action
        });
        return;
      }
      const toolSessionId2 = result2.data.sessionId;
      if (!toolSessionId2) {
        this.sendToolError({ success: false, errorCode: "SDK_UNREACHABLE", errorMessage: "create_session returned without sessionId" }, welinkSessionId, {
          logger: invokeLogger,
          traceId,
          gatewayMessageId: downstreamFields.gatewayMessageId,
          action: message.action
        });
        return;
      }
      if (!welinkSessionId) {
        this.sendToolError({ success: false, errorCode: "INVALID_PAYLOAD", errorMessage: "create_session missing welinkSessionId" }, undefined, {
          logger: invokeLogger,
          traceId,
          gatewayMessageId: downstreamFields.gatewayMessageId,
          action: message.action
        });
        return;
      }
      this.gatewayConnection.send({
        type: "session_created",
        welinkSessionId,
        toolSessionId: toolSessionId2,
        session: result2.data
      }, {
        traceId,
        runtimeTraceId: this.logger.getTraceId(),
        gatewayMessageId: downstreamFields.gatewayMessageId,
        welinkSessionId,
        toolSessionId: toolSessionId2,
        action: message.action
      });
      invokeLogger.info("runtime.invoke.completed", {
        action: message.action,
        welinkSessionId,
        toolSessionId: toolSessionId2,
        latencyMs: Date.now() - startedAt
      });
      return;
    }
    const result = await this.actionRouter.route(message.action, message.payload, this.buildActionContext(welinkSessionId, invokeLogger));
    if (!result.success) {
      this.sendToolError(result, welinkSessionId, {
        logger: invokeLogger,
        traceId,
        gatewayMessageId: downstreamFields.gatewayMessageId,
        action: message.action,
        toolSessionId
      });
      return;
    }
    invokeLogger.info("runtime.invoke.completed", {
      action: message.action,
      welinkSessionId,
      toolSessionId,
      latencyMs: Date.now() - startedAt
    });
  }
  buildActionContext(welinkSessionId, logger = this.logger) {
    return {
      client: this.sdkClient,
      connectionState: this.stateManager.getState(),
      agentId: this.stateManager.getAgentId() ?? "unknown-agent",
      welinkSessionId,
      logger: logger.child({
        component: "action",
        agentId: this.stateManager.getAgentId() ?? "unknown-agent",
        welinkSessionId
      })
    };
  }
  logEventForwardingDetail(normalized, logger = this.logger) {
    const detail = this.buildEventForwardingDetail(normalized);
    logger.debug("event.forwarding.detail", detail);
  }
  buildEventForwardingDetail(normalized) {
    const extra = normalized.extra;
    const raw = normalized.raw;
    return {
      eventType: normalized.common.eventType,
      toolSessionId: normalized.common.toolSessionId,
      opencodeMessageId: this.getMessageId(extra) ?? undefined,
      opencodePartId: this.getPartId(extra) ?? undefined,
      role: this.getRole(extra),
      status: this.getStatus(extra),
      partType: typeof raw.properties?.part?.type === "string" ? raw.properties.part.type : null,
      toolCallId: typeof raw.properties?.part?.callID === "string" ? raw.properties.part.callID : undefined,
      deltaBytes: typeof raw.properties?.delta === "string" ? Buffer.byteLength(raw.properties.delta, "utf8") : null
    };
  }
  getMessageId(extra) {
    if (!extra) {
      return null;
    }
    if (extra.kind === "message.updated" || extra.kind === "message.part.updated" || extra.kind === "message.part.delta" || extra.kind === "message.part.removed") {
      return extra.messageId;
    }
    return null;
  }
  getPartId(extra) {
    if (!extra) {
      return null;
    }
    if (extra.kind === "message.part.updated" || extra.kind === "message.part.delta" || extra.kind === "message.part.removed") {
      return extra.partId;
    }
    return null;
  }
  getRole(extra) {
    return extra && extra.kind === "message.updated" ? extra.role : null;
  }
  getStatus(extra) {
    return extra && extra.kind === "session.status" ? extra.status : null;
  }
  buildEventLogFields(normalized) {
    return this.buildEventForwardingDetail(normalized);
  }
  extractDownstreamLogFields(raw) {
    if (!raw || typeof raw !== "object") {
      return {};
    }
    const message = raw;
    const payload = typeof message.payload === "object" && message.payload ? message.payload : undefined;
    return {
      messageType: typeof message.type === "string" ? message.type : undefined,
      gatewayMessageId: typeof message.messageId === "string" ? message.messageId : undefined,
      action: typeof message.action === "string" ? message.action : undefined,
      welinkSessionId: typeof message.welinkSessionId === "string" ? message.welinkSessionId : undefined,
      toolSessionId: typeof payload?.toolSessionId === "string" ? payload.toolSessionId : undefined
    };
  }
  createMessageLogger(baseFields, traceId) {
    const baseLogger = this.logger.child(baseFields);
    const withTrace = (method) => (message, extra) => baseLogger[method](message, { traceId, ...extra ?? {} });
    return {
      debug: withTrace("debug"),
      info: withTrace("info"),
      warn: withTrace("warn"),
      error: withTrace("error"),
      child: (extra) => this.createMessageLogger({ ...baseFields, ...extra }, traceId),
      getTraceId: () => traceId
    };
  }
  sendToolError(result, welinkSessionId, logOptions) {
    if (!this.gatewayConnection) {
      this.logger.warn("runtime.tool_error.skipped_no_connection", { welinkSessionId });
      return;
    }
    const error = result.success ? "Unknown error" : result.errorMessage ?? "Unknown error";
    const logger = logOptions?.logger ?? this.logger;
    logger.error("runtime.tool_error.sending", { welinkSessionId, error });
    this.gatewayConnection.send({
      type: "tool_error",
      welinkSessionId,
      toolSessionId: logOptions?.toolSessionId,
      error
    }, {
      traceId: logOptions?.traceId,
      runtimeTraceId: this.logger.getTraceId(),
      gatewayMessageId: logOptions?.gatewayMessageId,
      welinkSessionId,
      action: logOptions?.action,
      toolSessionId: logOptions?.toolSessionId
    });
  }
}

// src/runtime/singleton.ts
var runtime = null;
var initializing = null;
var lifecycleAbortController = null;
var generation = 0;
async function getOrCreateRuntime(input) {
  const logger = new AppLogger(input.client, { component: "singleton" });
  if (runtime) {
    logger.debug("runtime.singleton.reuse_existing");
    return runtime;
  }
  if (initializing) {
    logger.debug("runtime.singleton.await_initializing");
    return initializing;
  }
  const candidate = new BridgeRuntime({
    workspacePath: input.worktree || input.directory,
    client: input.client
  });
  const token = ++generation;
  lifecycleAbortController = new AbortController;
  initializing = candidate.start({ abortSignal: lifecycleAbortController.signal }).then(() => {
    if (token !== generation || lifecycleAbortController?.signal.aborted) {
      candidate.stop();
      logger.warn("runtime.singleton.initialization_cancelled");
      throw new Error("runtime_initialization_cancelled");
    }
    runtime = candidate;
    logger.info("runtime.singleton.initialized");
    return candidate;
  }).catch((error) => {
    runtime = null;
    logger.error("runtime.singleton.initialization_failed", {
      error: getErrorMessage(error),
      ...getErrorDetailsForLog(error)
    });
    throw error;
  }).finally(() => {
    initializing = null;
  });
  return initializing;
}

// src/index.ts
var MessageBridgePlugin = async (input) => {
  const runtime2 = await getOrCreateRuntime(input);
  return {
    event: async ({ event }) => {
      await runtime2.handleEvent(event);
    }
  };
};
var src_default = MessageBridgePlugin;
export {
  src_default as default,
  MessageBridgePlugin
};

//# debugId=BA18E60660CF2CF464756E2164756E21
//# sourceMappingURL=message-bridge.plugin.js.map