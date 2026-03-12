// src/index.ts
import { emptyPluginConfigSchema } from "openclaw/plugin-sdk";

// src/config.ts
import { homedir } from "node:os";
import path from "node:path";
var CHANNEL_ID = "message-bridge";
var DEFAULT_ACCOUNT_ID = "default";
var DEFAULT_ACCOUNT_CONFIG = {
  enabled: true,
  gateway: {
    url: "ws://localhost:8081/ws/agent",
    toolType: "OPENCLAW",
    toolVersion: "0.1.0",
    deviceName: "OpenClaw Gateway",
    heartbeatIntervalMs: 3e4,
    reconnect: {
      baseMs: 1e3,
      maxMs: 3e4,
      exponential: true
    }
  },
  auth: {
    ak: "",
    sk: ""
  },
  agentIdPrefix: "message-bridge",
  runTimeoutMs: 12e4
};
function isRecord(value) {
  return value !== null && typeof value === "object";
}
function readChannelSection(cfg) {
  const channels = cfg.channels;
  if (!isRecord(channels)) {
    return void 0;
  }
  const section = channels[CHANNEL_ID];
  return isRecord(section) ? section : void 0;
}
function deepMerge(base, override) {
  if (!override) {
    return structuredClone(base);
  }
  const next = structuredClone(base);
  for (const [key, value] of Object.entries(override)) {
    if (isRecord(next[key]) && isRecord(value)) {
      next[key] = deepMerge(next[key], value);
      continue;
    }
    next[key] = value;
  }
  return next;
}
function normalizeAccountConfig(raw) {
  return deepMerge(DEFAULT_ACCOUNT_CONFIG, raw);
}
function listAccountIds(cfg) {
  const section = readChannelSection(cfg);
  const accounts = section?.accounts;
  if (!isRecord(accounts)) {
    return [DEFAULT_ACCOUNT_ID];
  }
  const ids = Object.keys(accounts);
  return ids.length > 0 ? ids : [DEFAULT_ACCOUNT_ID];
}
function resolveAccount(cfg, accountId) {
  const normalizedAccountId = accountId?.trim() || DEFAULT_ACCOUNT_ID;
  const section = readChannelSection(cfg);
  const baseConfig = normalizeAccountConfig(section);
  const accounts = section?.accounts;
  const override = isRecord(accounts) && isRecord(accounts[normalizedAccountId]) ? accounts[normalizedAccountId] : void 0;
  const merged = normalizeAccountConfig(override ? deepMerge(baseConfig, override) : baseConfig);
  return {
    accountId: normalizedAccountId,
    ...merged
  };
}
function describeAccount(account) {
  return {
    accountId: account.accountId,
    name: account.name,
    enabled: account.enabled,
    configured: Boolean(account.gateway.url && account.auth.ak && account.auth.sk),
    tokenSource: account.auth.ak ? "config" : "none"
  };
}
function resolveConfigSearchPaths(workspaceDir) {
  const paths = [];
  if (workspaceDir) {
    paths.push(path.join(workspaceDir, ".opencode", "message-bridge-openclaw.jsonc"));
    paths.push(path.join(workspaceDir, ".opencode", "message-bridge-openclaw.json"));
  }
  paths.push(path.join(homedir(), ".config", "openclaw", "message-bridge-openclaw.jsonc"));
  paths.push(path.join(homedir(), ".config", "openclaw", "message-bridge-openclaw.json"));
  return paths;
}

// src/OpenClawGatewayBridge.ts
import { randomUUID as randomUUID3 } from "node:crypto";
import os from "node:os";
import {
  createNormalizedOutboundDeliverer,
  createReplyPrefixOptions
} from "openclaw/plugin-sdk";

// src/connection/AkSkAuth.ts
import { createHmac, randomUUID } from "node:crypto";
var DefaultAkSkAuth = class {
  constructor(accessKey, secretKey) {
    this.accessKey = accessKey;
    this.secretKey = secretKey;
  }
  generateAuthPayload() {
    const ts = Math.floor(Date.now() / 1e3).toString();
    const nonce = randomUUID();
    const sign = createHmac("sha256", this.secretKey).update(`${this.accessKey}${ts}${nonce}`).digest("base64");
    return {
      ak: this.accessKey,
      ts,
      nonce,
      sign
    };
  }
};

// src/connection/GatewayConnection.ts
import { EventEmitter } from "node:events";
function isRecord2(value) {
  return value !== null && typeof value === "object";
}
function isGatewayControlMessage(value) {
  return isRecord2(value) && (value.type === "register_ok" || value.type === "register_rejected");
}
function encodeBase64Url(value) {
  return Buffer.from(value, "utf8").toString("base64").replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/g, "");
}
function buildAuthSubprotocol(payload) {
  return `auth.${encodeBase64Url(JSON.stringify(payload))}`;
}
var DefaultGatewayConnection = class extends EventEmitter {
  constructor(options) {
    super();
    this.options = options;
  }
  ws = null;
  heartbeatTimer = null;
  reconnectTimer = null;
  manuallyDisconnected = false;
  state = "DISCONNECTED";
  reconnectAttempts = 0;
  getState() {
    return this.state;
  }
  isConnected() {
    return this.state === "CONNECTED" || this.state === "READY";
  }
  async connect() {
    this.manuallyDisconnected = false;
    this.setState("CONNECTING");
    return new Promise((resolve, reject) => {
      const authPayload = this.options.authPayloadProvider?.();
      const protocols = authPayload ? [buildAuthSubprotocol(authPayload)] : void 0;
      const ws = protocols ? new WebSocket(this.options.url, protocols) : new WebSocket(this.options.url);
      this.ws = ws;
      ws.onopen = () => {
        this.options.logger.info("gateway.open", { url: this.options.url });
        this.setState("CONNECTED");
        this.send(this.options.registerMessage);
        resolve();
      };
      ws.onmessage = async (event) => {
        const data = await this.decodeMessageData(event.data);
        if (data === null) {
          return;
        }
        let parsed;
        try {
          parsed = JSON.parse(data);
        } catch {
          return;
        }
        if (isGatewayControlMessage(parsed)) {
          if (parsed.type === "register_ok") {
            this.setState("READY");
            this.startHeartbeat();
            return;
          }
          const error = new Error(parsed.reason || "gateway_register_rejected");
          this.options.logger.error("gateway.register.rejected", { reason: parsed.reason });
          this.emit("error", error);
          reject(error);
          return;
        }
        this.emit("message", parsed);
      };
      ws.onerror = () => {
        const error = new Error("gateway_websocket_error");
        this.options.logger.error("gateway.error");
        this.emit("error", error);
      };
      ws.onclose = () => {
        this.stopHeartbeat();
        this.ws = null;
        this.setState("DISCONNECTED");
        if (!this.manuallyDisconnected) {
          this.scheduleReconnect();
        }
      };
    });
  }
  disconnect() {
    this.manuallyDisconnected = true;
    if (this.reconnectTimer) {
      clearTimeout(this.reconnectTimer);
      this.reconnectTimer = null;
    }
    this.stopHeartbeat();
    this.ws?.close();
    this.ws = null;
    this.setState("DISCONNECTED");
  }
  send(message) {
    if (!this.ws || this.ws.readyState !== WebSocket.OPEN) {
      throw new Error("gateway_not_connected");
    }
    this.ws.send(JSON.stringify(message));
  }
  setState(state) {
    this.state = state;
    this.emit("stateChange", state);
  }
  startHeartbeat() {
    this.stopHeartbeat();
    this.heartbeatTimer = setInterval(() => {
      if (!this.ws || this.ws.readyState !== WebSocket.OPEN) {
        return;
      }
      this.send({
        type: "heartbeat",
        timestamp: (/* @__PURE__ */ new Date()).toISOString()
      });
    }, this.options.heartbeatIntervalMs);
  }
  stopHeartbeat() {
    if (this.heartbeatTimer) {
      clearInterval(this.heartbeatTimer);
      this.heartbeatTimer = null;
    }
  }
  scheduleReconnect() {
    if (this.reconnectTimer) {
      return;
    }
    const delay = this.options.reconnectExponential ? Math.min(
      this.options.reconnectBaseMs * 2 ** this.reconnectAttempts,
      this.options.reconnectMaxMs
    ) : this.options.reconnectBaseMs;
    this.reconnectAttempts += 1;
    this.reconnectTimer = setTimeout(() => {
      this.reconnectTimer = null;
      this.connect().catch((error) => {
        this.options.logger.warn("gateway.reconnect.failed", {
          error: error instanceof Error ? error.message : String(error)
        });
      });
    }, delay);
  }
  async decodeMessageData(data) {
    if (typeof data === "string") {
      return data;
    }
    if (data instanceof Blob) {
      return await data.text();
    }
    if (data instanceof ArrayBuffer) {
      return Buffer.from(data).toString("utf8");
    }
    if (ArrayBuffer.isView(data)) {
      return Buffer.from(data.buffer, data.byteOffset, data.byteLength).toString("utf8");
    }
    return null;
  }
};

// src/contracts/downstream.ts
var DOWNSTREAM_MESSAGE_TYPES = ["invoke", "status_query"];
var INVOKE_ACTIONS = [
  "chat",
  "create_session",
  "close_session",
  "permission_reply",
  "abort_session",
  "question_reply"
];

// src/protocol/downstream.ts
function isRecord3(value) {
  return value !== null && typeof value === "object";
}
function asString(value) {
  return typeof value === "string" && value.trim() ? value : void 0;
}
function ok(value) {
  return { ok: true, value };
}
function fail(message, code, messageType, action) {
  return { ok: false, error: { code, message, messageType, action } };
}
function normalizeChatPayload(payload) {
  if (!isRecord3(payload)) {
    return fail("payload must be an object", "invalid_payload", "invoke", "chat");
  }
  const toolSessionId = asString(payload.toolSessionId);
  const text = asString(payload.text);
  if (!toolSessionId || !text) {
    return fail("chat requires toolSessionId and text", "missing_required_field", "invoke", "chat");
  }
  return ok({ toolSessionId, text });
}
function normalizeCreateSessionPayload(payload) {
  if (!isRecord3(payload)) {
    return fail("payload must be an object", "invalid_payload", "invoke", "create_session");
  }
  return ok({
    sessionId: asString(payload.sessionId),
    metadata: isRecord3(payload.metadata) ? payload.metadata : void 0
  });
}
function normalizeCloseSessionPayload(payload) {
  if (!isRecord3(payload)) {
    return fail("payload must be an object", "invalid_payload", "invoke", "close_session");
  }
  const toolSessionId = asString(payload.toolSessionId);
  if (!toolSessionId) {
    return fail("close_session requires toolSessionId", "missing_required_field", "invoke", "close_session");
  }
  return ok({ toolSessionId });
}
function normalizeAbortSessionPayload(payload) {
  if (!isRecord3(payload)) {
    return fail("payload must be an object", "invalid_payload", "invoke", "abort_session");
  }
  const toolSessionId = asString(payload.toolSessionId);
  if (!toolSessionId) {
    return fail("abort_session requires toolSessionId", "missing_required_field", "invoke", "abort_session");
  }
  return ok({ toolSessionId });
}
function normalizePermissionReplyPayload(payload) {
  if (!isRecord3(payload)) {
    return fail("payload must be an object", "invalid_payload", "invoke", "permission_reply");
  }
  const toolSessionId = asString(payload.toolSessionId);
  const permissionId = asString(payload.permissionId);
  const response = asString(payload.response);
  if (!toolSessionId || !permissionId || !response) {
    return fail("permission_reply requires toolSessionId, permissionId, response", "missing_required_field", "invoke", "permission_reply");
  }
  return ok({ toolSessionId, permissionId, response });
}
function normalizeQuestionReplyPayload(payload) {
  if (!isRecord3(payload)) {
    return fail("payload must be an object", "invalid_payload", "invoke", "question_reply");
  }
  const toolSessionId = asString(payload.toolSessionId);
  const answer = asString(payload.answer);
  if (!toolSessionId || !answer) {
    return fail("question_reply requires toolSessionId and answer", "missing_required_field", "invoke", "question_reply");
  }
  return ok({ toolSessionId, answer, toolCallId: asString(payload.toolCallId) });
}
function normalizeInvoke(message) {
  const action = asString(message.action);
  if (!action || !INVOKE_ACTIONS.includes(action)) {
    return fail(`unsupported action: ${String(message.action)}`, "unsupported_action", "invoke", action);
  }
  const base = {
    type: "invoke",
    welinkSessionId: asString(message.welinkSessionId),
    action
  };
  switch (action) {
    case "chat": {
      const payload = normalizeChatPayload(message.payload);
      return payload.ok ? ok({ ...base, action, payload: payload.value }) : payload;
    }
    case "create_session": {
      const payload = normalizeCreateSessionPayload(message.payload);
      return payload.ok ? ok({ ...base, action, payload: payload.value }) : payload;
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
function normalizeDownstreamMessage(message) {
  if (!isRecord3(message) || !asString(message.type)) {
    return fail("message type is required", "missing_required_field");
  }
  const messageType = message.type;
  if (!DOWNSTREAM_MESSAGE_TYPES.includes(messageType)) {
    return fail(`unsupported message type: ${messageType}`, "unsupported_message", messageType);
  }
  if (messageType === "status_query") {
    return ok({ type: "status_query" });
  }
  return normalizeInvoke(message);
}

// src/session/SessionRegistry.ts
import { randomUUID as randomUUID2 } from "node:crypto";
var SessionRegistry = class {
  constructor(sessionPrefix) {
    this.sessionPrefix = sessionPrefix;
  }
  byToolSessionId = /* @__PURE__ */ new Map();
  ensure(toolSessionId, welinkSessionId) {
    const existing = this.byToolSessionId.get(toolSessionId);
    if (existing) {
      if (welinkSessionId && !existing.welinkSessionId) {
        existing.welinkSessionId = welinkSessionId;
      }
      return existing;
    }
    const record = {
      toolSessionId,
      sessionKey: `${this.sessionPrefix}:${toolSessionId}`,
      welinkSessionId
    };
    this.byToolSessionId.set(toolSessionId, record);
    return record;
  }
  create(welinkSessionId, requestedSessionId) {
    const toolSessionId = requestedSessionId?.trim() || `tool_${randomUUID2()}`;
    return this.ensure(toolSessionId, welinkSessionId);
  }
  get(toolSessionId) {
    return this.byToolSessionId.get(toolSessionId);
  }
  delete(toolSessionId) {
    const existing = this.byToolSessionId.get(toolSessionId);
    this.byToolSessionId.delete(toolSessionId);
    return existing;
  }
};

// src/OpenClawGatewayBridge.ts
function isRecord4(value) {
  return value !== null && typeof value === "object";
}
function extractAssistantText(messages) {
  for (let index = messages.length - 1; index >= 0; index -= 1) {
    const message = messages[index];
    if (!isRecord4(message) || message.role !== "assistant") {
      continue;
    }
    if (typeof message.content === "string") {
      return message.content;
    }
    if (Array.isArray(message.content)) {
      const chunks = message.content.map((part) => {
        if (!isRecord4(part)) {
          return "";
        }
        if (part.type === "text" && typeof part.text === "string") {
          return part.text;
        }
        if (typeof part.content === "string") {
          return part.content;
        }
        return "";
      }).filter(Boolean);
      if (chunks.length > 0) {
        return chunks.join("");
      }
    }
  }
  return "";
}
function buildBusyEvent(sessionKey) {
  return {
    type: "session.status",
    properties: {
      sessionID: sessionKey,
      status: {
        type: "busy"
      }
    }
  };
}
function buildIdleEvent(sessionKey) {
  return {
    type: "session.idle",
    properties: {
      sessionID: sessionKey
    }
  };
}
function buildSessionErrorEvent(sessionKey, error) {
  return {
    type: "session.error",
    properties: {
      sessionID: sessionKey,
      error: {
        message: error
      }
    }
  };
}
function createAssistantStreamState(sessionKey) {
  return {
    messageId: `msg_${randomUUID3()}`,
    partId: `prt_${randomUUID3()}`,
    sessionKey,
    seeded: false,
    accumulatedText: "",
    chunkCount: 0,
    firstChunkAt: null
  };
}
function buildAssistantMessageUpdated(sessionKey, messageId) {
  return {
    type: "message.updated",
    properties: {
      info: {
        id: messageId,
        sessionID: sessionKey,
        role: "assistant",
        time: {
          created: Date.now()
        }
      }
    }
  };
}
function buildAssistantPartUpdated(sessionKey, messageId, partId, text, delta) {
  return {
    type: "message.part.updated",
    properties: {
      ...delta !== void 0 ? { delta } : {},
      part: {
        id: partId,
        sessionID: sessionKey,
        messageID: messageId,
        type: "text",
        text
      }
    }
  };
}
function buildAssistantPartDelta(sessionKey, messageId, partId, delta) {
  return {
    type: "message.part.delta",
    properties: {
      sessionID: sessionKey,
      messageID: messageId,
      partID: partId,
      field: "text",
      delta
    }
  };
}
var OpenClawGatewayBridge = class {
  constructor(options) {
    this.options = options;
    this.runtime = options.runtime;
    this.sessionRegistry = new SessionRegistry(`${options.account.agentIdPrefix}:${options.account.accountId}`);
    this.connection = options.connectionFactory?.(options.account, options.logger) ?? new DefaultGatewayConnection({
      url: options.account.gateway.url,
      reconnectBaseMs: options.account.gateway.reconnect.baseMs,
      reconnectMaxMs: options.account.gateway.reconnect.maxMs,
      reconnectExponential: options.account.gateway.reconnect.exponential,
      heartbeatIntervalMs: options.account.gateway.heartbeatIntervalMs,
      authPayloadProvider: () => new DefaultAkSkAuth(options.account.auth.ak, options.account.auth.sk).generateAuthPayload(),
      registerMessage: {
        type: "register",
        deviceName: options.account.gateway.deviceName,
        macAddress: options.account.gateway.macAddress || "unknown",
        os: os.platform(),
        toolType: options.account.gateway.toolType,
        toolVersion: options.account.gateway.toolVersion
      },
      logger: options.logger
    });
    this.status = {
      accountId: options.account.accountId,
      running: false,
      connected: false,
      lastStartAt: null,
      lastStopAt: null,
      lastError: null
    };
    this.connection.on("stateChange", (state) => {
      this.status.connected = state === "CONNECTED" || state === "READY";
      this.options.setStatus({ ...this.status });
    });
    this.connection.on("message", (message) => {
      this.handleDownstreamMessage(message).catch((error) => {
        this.options.logger.error("bridge.handle_downstream.failed", {
          error: error instanceof Error ? error.message : String(error)
        });
      });
    });
    this.connection.on("error", (error) => {
      this.status.lastError = error.message;
      this.options.setStatus({ ...this.status });
    });
  }
  sessionRegistry;
  connection;
  runtime;
  running = false;
  status;
  async start() {
    if (this.running) {
      return;
    }
    this.running = true;
    this.status.running = true;
    this.status.lastStartAt = Date.now();
    this.options.setStatus({ ...this.status });
    await this.connection.connect();
  }
  getSubagentRuntime() {
    return this.runtime.subagent ?? null;
  }
  async stop() {
    if (!this.running) {
      return;
    }
    this.running = false;
    this.connection.disconnect();
    this.status.running = false;
    this.status.connected = false;
    this.status.lastStopAt = Date.now();
    this.options.setStatus({ ...this.status });
  }
  async handleDownstreamMessage(raw) {
    const normalized = normalizeDownstreamMessage(raw);
    if (!normalized.ok) {
      this.sendToolError({
        type: "tool_error",
        error: normalized.error.message
      });
      return;
    }
    if (normalized.value.type === "status_query") {
      const message = {
        type: "status_response",
        opencodeOnline: this.running
      };
      this.connection.send(message);
      return;
    }
    await this.handleInvoke(normalized.value);
  }
  async handleInvoke(message) {
    switch (message.action) {
      case "chat":
        await this.handleChat(message);
        return;
      case "create_session":
        this.handleCreateSession(message);
        return;
      case "close_session":
        await this.handleCloseSession(message);
        return;
      case "abort_session":
        await this.handleAbortSession(message);
        return;
      case "permission_reply":
      case "question_reply":
        this.sendUnsupported(message.action, message.payload.toolSessionId, message.welinkSessionId);
        return;
    }
  }
  async handleChat(message) {
    const record = this.sessionRegistry.ensure(message.payload.toolSessionId, message.welinkSessionId);
    const assistantStream = createAssistantStreamState(record.sessionKey);
    const startedAt = Date.now();
    this.sendToolEvent({
      type: "tool_event",
      toolSessionId: record.toolSessionId,
      event: buildBusyEvent(record.sessionKey)
    });
    this.options.logger.info("bridge.chat.started", {
      toolSessionId: record.toolSessionId,
      welinkSessionId: record.welinkSessionId,
      sessionKey: record.sessionKey,
      textLength: message.payload.text.length,
      startedAt
    });
    if (!this.runtime.channel?.routing?.resolveAgentRoute || !this.runtime.channel?.reply) {
      await this.handleChatWithSubagentFallback(record, message.payload.text);
      return;
    }
    const route = this.runtime.channel.routing.resolveAgentRoute({
      cfg: this.options.config,
      channel: "message-bridge",
      accountId: this.options.account.accountId,
      peer: {
        kind: "direct",
        id: record.welinkSessionId || record.toolSessionId
      }
    });
    const envelopeOptions = this.runtime.channel.reply.resolveEnvelopeFormatOptions(this.options.config);
    const body = this.runtime.channel.reply.formatAgentEnvelope({
      channel: "message-bridge",
      from: `ai-gateway:${record.welinkSessionId || record.toolSessionId}`,
      timestamp: /* @__PURE__ */ new Date(),
      previousTimestamp: void 0,
      envelope: envelopeOptions,
      body: message.payload.text
    });
    const ctxPayload = this.runtime.channel.reply.finalizeInboundContext({
      Body: body,
      BodyForAgent: message.payload.text,
      RawBody: message.payload.text,
      CommandBody: message.payload.text,
      From: `message-bridge:${record.welinkSessionId || record.toolSessionId}`,
      To: `message-bridge:${record.toolSessionId}`,
      SessionKey: record.sessionKey,
      AccountId: route.accountId,
      ChatType: "direct",
      ConversationLabel: `ai-gateway:${record.welinkSessionId || record.toolSessionId}`,
      SenderName: "ai-gateway",
      SenderId: record.welinkSessionId || record.toolSessionId,
      Provider: "message-bridge",
      Surface: "message-bridge",
      Timestamp: (/* @__PURE__ */ new Date()).toISOString(),
      OriginatingChannel: "message-bridge",
      OriginatingTo: `message-bridge:${record.toolSessionId}`,
      CommandAuthorized: false
    });
    const { onModelSelected, ...prefixOptions } = createReplyPrefixOptions({
      cfg: this.options.config,
      agentId: route.agentId,
      channel: "message-bridge",
      accountId: this.options.account.accountId
    });
    const deliver = createNormalizedOutboundDeliverer(async (payload) => {
      if (typeof payload.text === "string" && payload.text.length > 0) {
        const now = Date.now();
        assistantStream.chunkCount += 1;
        if (assistantStream.firstChunkAt === null) {
          assistantStream.firstChunkAt = now;
          this.options.logger.info("bridge.chat.first_chunk", {
            toolSessionId: record.toolSessionId,
            sessionKey: record.sessionKey,
            latencyMs: now - startedAt,
            chunkLength: payload.text.length
          });
        } else {
          this.options.logger.info("bridge.chat.chunk", {
            toolSessionId: record.toolSessionId,
            sessionKey: record.sessionKey,
            chunkIndex: assistantStream.chunkCount,
            chunkLength: payload.text.length,
            sinceStartMs: now - startedAt,
            sinceFirstChunkMs: now - assistantStream.firstChunkAt
          });
        }
        this.sendAssistantStreamChunk(record.toolSessionId, assistantStream, payload.text);
      }
    });
    try {
      await this.runtime.channel.reply.dispatchReplyWithBufferedBlockDispatcher({
        ctx: ctxPayload,
        cfg: this.options.config,
        dispatcherOptions: {
          ...prefixOptions,
          deliver,
          onError: (error) => {
            throw error;
          }
        },
        replyOptions: {
          onModelSelected,
          timeoutOverrideSeconds: Math.ceil(this.options.account.runTimeoutMs / 1e3)
        }
      });
    } catch (error) {
      const errorMessage = error instanceof Error ? error.message : String(error);
      this.sendToolEvent({
        type: "tool_event",
        toolSessionId: record.toolSessionId,
        event: buildSessionErrorEvent(record.sessionKey, errorMessage)
      });
      this.sendToolError({
        type: "tool_error",
        toolSessionId: record.toolSessionId,
        welinkSessionId: record.welinkSessionId,
        error: errorMessage
      });
      return;
    }
    this.sendAssistantFinalResponse(
      record.toolSessionId,
      assistantStream,
      assistantStream.accumulatedText || "(empty response)"
    );
    this.options.logger.info("bridge.chat.completed", {
      toolSessionId: record.toolSessionId,
      sessionKey: record.sessionKey,
      totalLatencyMs: Date.now() - startedAt,
      firstChunkLatencyMs: assistantStream.firstChunkAt === null ? null : assistantStream.firstChunkAt - startedAt,
      chunkCount: assistantStream.chunkCount,
      responseLength: assistantStream.accumulatedText.length
    });
    this.sendToolEvent({
      type: "tool_event",
      toolSessionId: record.toolSessionId,
      event: buildIdleEvent(record.sessionKey)
    });
    this.sendToolDone({
      type: "tool_done",
      toolSessionId: record.toolSessionId,
      welinkSessionId: record.welinkSessionId
    });
  }
  async handleChatWithSubagentFallback(record, text) {
    const assistantStream = createAssistantStreamState(record.sessionKey);
    const subagent = this.getSubagentRuntime();
    if (!subagent) {
      this.sendToolEvent({
        type: "tool_event",
        toolSessionId: record.toolSessionId,
        event: buildSessionErrorEvent(record.sessionKey, "openclaw_runtime_missing_reply_executor")
      });
      this.sendToolError({
        type: "tool_error",
        toolSessionId: record.toolSessionId,
        welinkSessionId: record.welinkSessionId,
        error: "openclaw_runtime_missing_reply_executor"
      });
      return;
    }
    const run = await subagent.run({
      sessionKey: record.sessionKey,
      message: text,
      deliver: false,
      idempotencyKey: `${record.toolSessionId}:${text}`
    });
    const wait = await subagent.waitForRun({
      runId: run.runId,
      timeoutMs: this.options.account.runTimeoutMs
    });
    if (wait.status !== "ok") {
      const errorMessage = wait.error || `subagent_${wait.status}`;
      this.sendToolEvent({
        type: "tool_event",
        toolSessionId: record.toolSessionId,
        event: buildSessionErrorEvent(record.sessionKey, errorMessage)
      });
      this.sendToolError({
        type: "tool_error",
        toolSessionId: record.toolSessionId,
        welinkSessionId: record.welinkSessionId,
        error: errorMessage
      });
      return;
    }
    const session = await subagent.getSessionMessages({
      sessionKey: record.sessionKey,
      limit: 50
    });
    const assistantText = extractAssistantText(session.messages) || "(empty response)";
    this.sendAssistantFinalResponse(record.toolSessionId, assistantStream, assistantText);
    this.options.logger.info("bridge.chat.completed_fallback", {
      toolSessionId: record.toolSessionId,
      sessionKey: record.sessionKey,
      chunkCount: assistantStream.chunkCount,
      responseLength: assistantText.length
    });
    this.sendToolEvent({
      type: "tool_event",
      toolSessionId: record.toolSessionId,
      event: buildIdleEvent(record.sessionKey)
    });
    this.sendToolDone({
      type: "tool_done",
      toolSessionId: record.toolSessionId,
      welinkSessionId: record.welinkSessionId
    });
  }
  handleCreateSession(message) {
    const record = this.sessionRegistry.create(message.welinkSessionId, message.payload.sessionId);
    const response = {
      type: "session_created",
      welinkSessionId: message.welinkSessionId,
      toolSessionId: record.toolSessionId,
      session: {
        sessionId: record.sessionKey
      }
    };
    this.connection.send(response);
  }
  async handleCloseSession(message) {
    const record = this.sessionRegistry.delete(message.payload.toolSessionId);
    if (record) {
      await this.getSubagentRuntime()?.deleteSession({
        sessionKey: record.sessionKey
      });
    }
    this.sendToolDone({
      type: "tool_done",
      toolSessionId: message.payload.toolSessionId,
      welinkSessionId: message.welinkSessionId
    });
  }
  async handleAbortSession(message) {
    const record = this.sessionRegistry.get(message.payload.toolSessionId);
    if (!record) {
      this.sendToolError({
        type: "tool_error",
        toolSessionId: message.payload.toolSessionId,
        welinkSessionId: message.welinkSessionId,
        error: "unknown_tool_session"
      });
      return;
    }
    await this.getSubagentRuntime()?.deleteSession({
      sessionKey: record.sessionKey
    });
    this.sessionRegistry.delete(message.payload.toolSessionId);
    this.sendToolDone({
      type: "tool_done",
      toolSessionId: message.payload.toolSessionId,
      welinkSessionId: message.welinkSessionId
    });
  }
  sendUnsupported(action, toolSessionId, welinkSessionId) {
    this.sendToolError({
      type: "tool_error",
      toolSessionId,
      welinkSessionId,
      error: `unsupported_in_openclaw_v1:${action}`
    });
  }
  sendToolEvent(message) {
    this.connection.send(message);
  }
  sendToolDone(message) {
    this.connection.send(message);
  }
  sendToolError(message) {
    this.connection.send(message);
  }
  sendAssistantStreamChunk(toolSessionId, state, chunk) {
    state.accumulatedText += chunk;
    if (!state.seeded) {
      this.sendToolEvent({
        type: "tool_event",
        toolSessionId,
        event: buildAssistantMessageUpdated(state.sessionKey, state.messageId)
      });
      this.sendToolEvent({
        type: "tool_event",
        toolSessionId,
        event: buildAssistantPartUpdated(state.sessionKey, state.messageId, state.partId, chunk, chunk)
      });
      state.seeded = true;
      return;
    }
    this.sendToolEvent({
      type: "tool_event",
      toolSessionId,
      event: buildAssistantPartDelta(state.sessionKey, state.messageId, state.partId, chunk)
    });
  }
  sendAssistantFinalResponse(toolSessionId, state, text) {
    if (state.seeded) {
      this.sendToolEvent({
        type: "tool_event",
        toolSessionId,
        event: buildAssistantPartUpdated(
          state.sessionKey,
          state.messageId,
          state.partId,
          state.accumulatedText || text
        )
      });
      return;
    }
    this.sendToolEvent({
      type: "tool_event",
      toolSessionId,
      event: buildAssistantMessageUpdated(state.sessionKey, state.messageId)
    });
    this.sendToolEvent({
      type: "tool_event",
      toolSessionId,
      event: buildAssistantPartUpdated(state.sessionKey, state.messageId, state.partId, text)
    });
    state.seeded = true;
  }
};

// src/runtime/store.ts
var pluginRuntime = null;
function setPluginRuntime(runtime) {
  pluginRuntime = runtime;
}
function getPluginRuntime() {
  if (!pluginRuntime) {
    throw new Error("message_bridge_openclaw_runtime_uninitialized");
  }
  return pluginRuntime;
}

// src/channel.ts
var activeBridges = /* @__PURE__ */ new Map();
var messageBridgePlugin = {
  id: CHANNEL_ID,
  meta: {
    id: CHANNEL_ID,
    label: "Message Bridge",
    selectionLabel: "Message Bridge",
    docsPath: "/channels/message-bridge",
    blurb: "Bridge ai-gateway sessions into OpenClaw."
  },
  capabilities: {
    chatTypes: ["direct"],
    nativeCommands: false,
    blockStreaming: true
  },
  reload: {
    configPrefixes: [`channels.${CHANNEL_ID}`]
  },
  config: {
    listAccountIds: (cfg) => listAccountIds(cfg),
    resolveAccount: (cfg, accountId) => resolveAccount(cfg, accountId),
    defaultAccountId: () => DEFAULT_ACCOUNT_ID,
    isEnabled: (account) => account.enabled,
    isConfigured: (account) => Boolean(account.gateway.url && account.auth.ak && account.auth.sk),
    unconfiguredReason: () => "gateway.url, auth.ak, auth.sk are required",
    describeAccount: (account) => describeAccount(account)
  },
  status: {
    defaultRuntime: {
      accountId: DEFAULT_ACCOUNT_ID,
      running: false,
      connected: false,
      lastStartAt: null,
      lastStopAt: null,
      lastError: null
    }
  },
  gateway: {
    startAccount: async (ctx) => {
      const account = resolveAccount(ctx.cfg, ctx.accountId);
      const bridge = new OpenClawGatewayBridge({
        account,
        config: ctx.cfg,
        runtime: getPluginRuntime(),
        logger: ctx.log ?? console,
        setStatus: (status) => ctx.setStatus(status)
      });
      activeBridges.set(account.accountId, bridge);
      await bridge.start();
      try {
        await new Promise((resolve) => {
          if (ctx.abortSignal.aborted) {
            resolve();
            return;
          }
          ctx.abortSignal.addEventListener("abort", () => resolve(), { once: true });
        });
      } finally {
        activeBridges.delete(account.accountId);
        await bridge.stop();
      }
    },
    stopAccount: async (ctx) => {
      const account = resolveAccount(ctx.cfg, ctx.accountId);
      const bridge = activeBridges.get(account.accountId);
      if (!bridge) {
        return;
      }
      activeBridges.delete(account.accountId);
      await bridge.stop();
    }
  }
};

// src/index.ts
var plugin = {
  id: "message-bridge",
  name: "Message Bridge",
  description: "Bridge ai-gateway sessions into OpenClaw",
  configSchema: emptyPluginConfigSchema(),
  register(api) {
    setPluginRuntime(api.runtime);
    api.registerChannel({ plugin: messageBridgePlugin });
  }
};
var index_default = plugin;
export {
  CHANNEL_ID,
  DEFAULT_ACCOUNT_CONFIG,
  DEFAULT_ACCOUNT_ID,
  OpenClawGatewayBridge,
  index_default as default,
  describeAccount,
  listAccountIds,
  messageBridgePlugin,
  normalizeDownstreamMessage,
  resolveAccount,
  resolveConfigSearchPaths
};
