// src/index.ts
import { emptyPluginConfigSchema } from "openclaw/plugin-sdk";

// src/channel.ts
import {
  applyAccountNameToChannelSection
} from "openclaw/plugin-sdk";

// src/config.ts
import { homedir } from "node:os";
import path from "node:path";
import {
  deleteAccountFromConfigSection,
  setAccountEnabledInConfigSection
} from "openclaw/plugin-sdk";
var CHANNEL_ID = "message-bridge";
var DEFAULT_ACCOUNT_ID = "default";
var LEGACY_ACCOUNTS_MIGRATION_FIX = "\u5220\u9664 channels.message-bridge.accounts\uFF0C\u5E76\u628A\u552F\u4E00\u8D26\u53F7\u914D\u7F6E\u8FC1\u79FB\u5230 channels.message-bridge \u9876\u5C42\u3002";
var CHANNEL_ADD_FIX = "\u8FD0\u884C openclaw channels add --channel message-bridge --url <gateway-url> --token <ak> --password <sk>\u3002";
var NON_DEFAULT_ACCOUNT_ERROR_PREFIX = "message_bridge_single_account_only";
var DEFAULT_ACCOUNT_CONFIG = {
  enabled: true,
  gateway: {
    url: "ws://localhost:8081/ws/agent",
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
  runTimeoutMs: 3e5
};
var DEPRECATED_GATEWAY_FIELDS = /* @__PURE__ */ new Set(["toolType", "toolVersion", "deviceName", "macAddress"]);
function isRecord(value) {
  return value !== null && typeof value === "object";
}
function trimOrUndefined(value) {
  if (typeof value !== "string") {
    return void 0;
  }
  const trimmed = value.trim();
  return trimmed ? trimmed : void 0;
}
function readChannelSection(cfg) {
  const channels = cfg.channels;
  if (!isRecord(channels)) {
    return void 0;
  }
  const section = channels[CHANNEL_ID];
  return isRecord(section) ? section : void 0;
}
function stripLegacyAccounts(section) {
  if (!section) {
    return void 0;
  }
  const { accounts: _accounts, gateway, ...rest } = section;
  const nextGateway = isRecord(gateway) ? Object.fromEntries(Object.entries(gateway).filter(([key]) => !DEPRECATED_GATEWAY_FIELDS.has(key))) : gateway;
  return {
    ...rest,
    ...isRecord(nextGateway) ? { gateway: nextGateway } : {}
  };
}
function getSectionField(section, key) {
  const value = section?.[key];
  return isRecord(value) ? value : void 0;
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
  void cfg;
  return [DEFAULT_ACCOUNT_ID];
}
function hasLegacyAccountsConfig(cfg) {
  const section = readChannelSection(cfg);
  return isRecord(section?.accounts);
}
function resolveNonDefaultAccountError(accountId) {
  return new Error(
    `${NON_DEFAULT_ACCOUNT_ERROR_PREFIX}: Message Bridge \u53EA\u652F\u6301 default \u5355\u8D26\u53F7\uFF0C\u6536\u5230 accountId=${accountId}`
  );
}
function assertSupportedAccountId(accountId) {
  const normalizedAccountId = accountId?.trim() || DEFAULT_ACCOUNT_ID;
  if (normalizedAccountId !== DEFAULT_ACCOUNT_ID) {
    throw resolveNonDefaultAccountError(normalizedAccountId);
  }
  return normalizedAccountId;
}
function resolveSupportedAccountId(accountId) {
  return assertSupportedAccountId(accountId);
}
function getMissingRequiredConfigPaths(account, cfg) {
  const section = cfg ? stripLegacyAccounts(readChannelSection(cfg)) : void 0;
  const gatewaySection = cfg ? getSectionField(section, "gateway") : void 0;
  const authSection = cfg ? getSectionField(section, "auth") : void 0;
  const gatewayUrl = cfg ? trimOrUndefined(gatewaySection?.url) : trimOrUndefined(account.gateway.url);
  const authAk = cfg ? trimOrUndefined(authSection?.ak) : trimOrUndefined(account.auth.ak);
  const authSk = cfg ? trimOrUndefined(authSection?.sk) : trimOrUndefined(account.auth.sk);
  const missing = [];
  if (!gatewayUrl) {
    missing.push(`channels.${CHANNEL_ID}.gateway.url`);
  }
  if (!authAk) {
    missing.push(`channels.${CHANNEL_ID}.auth.ak`);
  }
  if (!authSk) {
    missing.push(`channels.${CHANNEL_ID}.auth.sk`);
  }
  return missing;
}
function resolveTokenSource(account) {
  return account.auth.ak.trim() || account.auth.sk.trim() ? "config" : "none";
}
function isAccountConfigured(account, cfg) {
  return getMissingRequiredConfigPaths(account, cfg).length === 0 && !hasLegacyAccountsConfig(cfg);
}
function resolveAccount(cfg, accountId) {
  const normalizedAccountId = assertSupportedAccountId(accountId);
  const section = readChannelSection(cfg);
  const merged = normalizeAccountConfig(stripLegacyAccounts(section));
  return {
    accountId: normalizedAccountId,
    ...merged
  };
}
function describeAccount(account, cfg) {
  return {
    accountId: account.accountId,
    name: account.name,
    enabled: account.enabled,
    configured: isAccountConfigured(account, cfg),
    tokenSource: resolveTokenSource(account)
  };
}
function resolveUnconfiguredReason(cfg) {
  if (hasLegacyAccountsConfig(cfg)) {
    return `channels.${CHANNEL_ID}.accounts \u5DF2\u5E9F\u5F03\u3002${LEGACY_ACCOUNTS_MIGRATION_FIX}`;
  }
  return `channels.${CHANNEL_ID}.gateway.url\u3001channels.${CHANNEL_ID}.auth.ak\u3001channels.${CHANNEL_ID}.auth.sk \u4E3A\u5FC5\u586B\u9879`;
}
function validateGatewayUrl(url) {
  try {
    const parsed = new URL(url);
    if (parsed.protocol !== "ws:" && parsed.protocol !== "wss:") {
      return "Message Bridge \u7684 gateway.url \u5FC5\u987B\u4F7F\u7528 ws:// \u6216 wss://\u3002";
    }
    return null;
  } catch {
    return "Message Bridge \u7684 gateway.url \u4E0D\u662F\u5408\u6CD5\u7684 WebSocket URL\u3002";
  }
}
function validateMessageBridgeSetupInput(params) {
  const { cfg, accountId, input } = params;
  try {
    resolveSupportedAccountId(accountId);
  } catch (error) {
    return error instanceof Error ? error.message : String(error);
  }
  if (hasLegacyAccountsConfig(cfg)) {
    return `\u68C0\u6D4B\u5230\u5DF2\u5E9F\u5F03\u7684 channels.${CHANNEL_ID}.accounts \u914D\u7F6E\u3002${LEGACY_ACCOUNTS_MIGRATION_FIX}`;
  }
  if (input.useEnv) {
    return "Message Bridge \u5F53\u524D\u4E0D\u652F\u6301 --use-env\uFF0C\u8BF7\u663E\u5F0F\u4F20\u5165 --url\u3001--token\u3001--password\u3002";
  }
  const nextCfg = applyMessageBridgeSetupConfig({
    cfg,
    accountId,
    input
  });
  const nextAccount = resolveAccount(nextCfg, accountId);
  const missing = getMissingRequiredConfigPaths(nextAccount, nextCfg);
  if (missing.length > 0) {
    return `Message Bridge \u7F3A\u5C11\u5FC5\u586B\u914D\u7F6E\uFF1A${missing.join("\u3001")}\u3002${CHANNEL_ADD_FIX}`;
  }
  const urlError = validateGatewayUrl(nextAccount.gateway.url);
  if (urlError) {
    return urlError;
  }
  return null;
}
function applyMessageBridgeSetupConfig(params) {
  const normalizedAccountId = resolveSupportedAccountId(params.accountId);
  const section = stripLegacyAccounts(readChannelSection(params.cfg));
  const gatewaySection = getSectionField(section, "gateway");
  const authSection = getSectionField(section, "auth");
  const nextName = params.input.name === void 0 ? section?.name : trimOrUndefined(params.input.name);
  const nextGatewayUrl = trimOrUndefined(params.input.url);
  const nextAk = trimOrUndefined(params.input.token);
  const nextSk = trimOrUndefined(params.input.password);
  void normalizedAccountId;
  return {
    ...params.cfg,
    channels: {
      ...params.cfg.channels,
      [CHANNEL_ID]: {
        ...section,
        enabled: true,
        ...nextName !== void 0 ? { name: nextName } : {},
        gateway: {
          ...gatewaySection,
          ...nextGatewayUrl !== void 0 ? { url: nextGatewayUrl } : {}
        },
        auth: {
          ...authSection,
          ...nextAk !== void 0 ? { ak: nextAk } : {},
          ...nextSk !== void 0 ? { sk: nextSk } : {}
        }
      }
    }
  };
}
function setMessageBridgeAccountEnabled(params) {
  resolveSupportedAccountId(params.accountId);
  return setAccountEnabledInConfigSection({
    cfg: params.cfg,
    sectionKey: CHANNEL_ID,
    accountId: params.accountId,
    enabled: params.enabled,
    allowTopLevel: true
  });
}
function deleteMessageBridgeAccount(params) {
  resolveSupportedAccountId(params.accountId);
  return deleteAccountFromConfigSection({
    cfg: params.cfg,
    sectionKey: CHANNEL_ID,
    accountId: params.accountId,
    clearBaseFields: ["enabled", "name", "gateway", "auth", "agentIdPrefix", "runTimeoutMs"]
  });
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
import os2 from "node:os";
import {
  createReplyPrefixOptions,
  normalizeOutboundReplyPayload
} from "openclaw/plugin-sdk";

// src/contracts/transport.ts
var TOOL_ERROR_REASON = {
  SESSION_NOT_FOUND: "session_not_found"
};

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
function asNumber(value) {
  return typeof value === "number" && Number.isFinite(value) ? value : void 0;
}
function asBoolean(value) {
  return typeof value === "boolean" ? value : void 0;
}
function asString(value) {
  return typeof value === "string" ? value : void 0;
}
function logDebug(logger, message, meta) {
  if (logger.debug) {
    logger.debug(message, meta);
    return;
  }
  logger.info(message, meta);
}
function isRecord2(value) {
  return value !== null && typeof value === "object";
}
function isGatewayControlMessage(value) {
  return isRecord2(value) && (value.type === "register_ok" || value.type === "register_rejected");
}
function extractGatewayMessageId(value) {
  if (!isRecord2(value)) {
    return void 0;
  }
  return typeof value.messageId === "string" ? value.messageId : void 0;
}
function extractMessageAction(value) {
  if (!isRecord2(value)) {
    return void 0;
  }
  return typeof value.action === "string" ? value.action : void 0;
}
function extractWelinkSessionId(value) {
  if (!isRecord2(value)) {
    return void 0;
  }
  return typeof value.welinkSessionId === "string" ? value.welinkSessionId : void 0;
}
function extractToolSessionId(value) {
  if (!isRecord2(value)) {
    return void 0;
  }
  if (typeof value.toolSessionId === "string") {
    return value.toolSessionId;
  }
  if (!isRecord2(value.payload)) {
    return void 0;
  }
  return typeof value.payload.toolSessionId === "string" ? value.payload.toolSessionId : void 0;
}
function extractEventType(value) {
  if (!isRecord2(value) || !isRecord2(value.event)) {
    return void 0;
  }
  return typeof value.event.type === "string" ? value.event.type : void 0;
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
    this.options.logger.info("gateway.connect.started", { url: this.options.url });
    return new Promise((resolve, reject) => {
      const authPayload = this.options.authPayloadProvider?.();
      const protocols = authPayload ? [buildAuthSubprotocol(authPayload)] : void 0;
      const ws = protocols ? new WebSocket(this.options.url, protocols) : new WebSocket(this.options.url);
      this.ws = ws;
      ws.onopen = () => {
        this.options.logger.info("gateway.open", { url: this.options.url });
        this.setState("CONNECTED");
        this.options.logger.info("gateway.register.sent");
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
          logDebug(this.options.logger, "gateway.message.ignored_non_json", {
            payloadLength: data.length,
            frameBytes: Buffer.byteLength(data, "utf8")
          });
          return;
        }
        logDebug(this.options.logger, "gateway.message.received", {
          messageType: isRecord2(parsed) && typeof parsed.type === "string" ? parsed.type : void 0,
          frameBytes: Buffer.byteLength(data, "utf8"),
          gatewayMessageId: extractGatewayMessageId(parsed),
          action: extractMessageAction(parsed),
          welinkSessionId: extractWelinkSessionId(parsed),
          toolSessionId: extractToolSessionId(parsed)
        });
        this.emit("inbound", parsed);
        if (isGatewayControlMessage(parsed)) {
          if (parsed.type === "register_ok") {
            this.options.logger.info("gateway.register.accepted");
            this.setState("READY");
            this.options.logger.info("gateway.ready");
            this.startHeartbeat();
            return;
          }
          const error = new Error(parsed.reason || "gateway_register_rejected");
          this.options.logger.error("gateway.register.rejected", { reason: parsed.reason });
          this.emit("error", error);
          reject(error);
          return;
        }
        if (this.state !== "READY") {
          logDebug(this.options.logger, "gateway.message.received_not_ready", {
            messageType: isRecord2(parsed) && typeof parsed.type === "string" ? parsed.type : void 0,
            state: this.state
          });
        }
        this.emit("message", parsed);
      };
      ws.onerror = () => {
        const error = new Error("gateway_websocket_error");
        this.options.logger.error("gateway.error");
        this.emit("error", error);
      };
      ws.onclose = (event) => {
        const close = event;
        const stateBeforeClose = this.state;
        const reconnectPlanned = !this.manuallyDisconnected;
        this.options.logger.warn("gateway.close", {
          code: asNumber(close.code),
          reason: asString(close.reason) ?? "",
          wasClean: asBoolean(close.wasClean),
          stateBeforeClose,
          manuallyDisconnected: this.manuallyDisconnected,
          reconnectPlanned
        });
        this.stopHeartbeat();
        this.ws = null;
        this.setState("DISCONNECTED");
        if (reconnectPlanned) {
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
  send(message, logContext) {
    if (!this.ws || this.ws.readyState !== WebSocket.OPEN) {
      throw new Error("gateway_not_connected");
    }
    const serialized = JSON.stringify(message);
    logDebug(this.options.logger, "gateway.send", {
      messageType: isRecord2(message) && typeof message.type === "string" ? message.type : void 0,
      payloadBytes: Buffer.byteLength(serialized, "utf8"),
      gatewayMessageId: logContext?.gatewayMessageId ?? extractGatewayMessageId(message),
      action: logContext?.action ?? extractMessageAction(message),
      welinkSessionId: logContext?.welinkSessionId ?? extractWelinkSessionId(message),
      toolSessionId: logContext?.toolSessionId ?? extractToolSessionId(message),
      eventType: logContext?.eventType ?? extractEventType(message)
    });
    this.ws.send(serialized);
    this.emit("outbound", message);
    if (isRecord2(message) && message.type === "heartbeat") {
      this.emit("heartbeat", message);
    }
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
    this.options.logger.info("gateway.reconnect.scheduled", {
      reconnectAttempts: this.reconnectAttempts,
      delayMs: delay
    });
    this.reconnectTimer = setTimeout(() => {
      this.reconnectTimer = null;
      this.options.logger.info("gateway.reconnect.attempt", {
        reconnectAttempts: this.reconnectAttempts
      });
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
function asString2(value) {
  return typeof value === "string" && value.trim() ? value : void 0;
}
function hasKey(record, key) {
  return Object.prototype.hasOwnProperty.call(record, key);
}
function ok(value) {
  return { ok: true, value };
}
function fail(params) {
  return {
    ok: false,
    error: {
      code: params.code,
      message: params.message,
      stage: params.stage,
      field: params.field,
      messageType: params.messageType,
      action: params.action,
      welinkSessionId: params.welinkSessionId
    }
  };
}
function logDebug2(logger, message, meta) {
  if (!logger) {
    return;
  }
  if (logger.debug) {
    logger.debug(message, meta);
    return;
  }
  logger.info(message, meta);
}
function buildMessagePreview(raw) {
  if (!isRecord3(raw)) {
    return { kind: typeof raw };
  }
  return {
    type: typeof raw.type === "string" ? raw.type : void 0,
    keys: Object.keys(raw).slice(0, 8)
  };
}
function extractToolSessionId2(payload) {
  return isRecord3(payload) ? asString2(payload.toolSessionId) : void 0;
}
function logDownstreamNormalizationFailure(logger, raw, error) {
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
    messagePreview: buildMessagePreview(raw)
  });
}
function normalizeChatPayload(payload) {
  if (!isRecord3(payload)) {
    return fail({
      message: "payload must be an object",
      code: "invalid_payload",
      stage: "payload",
      field: "payload",
      messageType: "invoke",
      action: "chat"
    });
  }
  const toolSessionId = asString2(payload.toolSessionId);
  const text = asString2(payload.text);
  if (!toolSessionId || !text) {
    return fail({
      message: "chat requires toolSessionId and text",
      code: "missing_required_field",
      stage: "payload",
      field: !toolSessionId ? "payload.toolSessionId" : "payload.text",
      messageType: "invoke",
      action: "chat"
    });
  }
  return ok({ toolSessionId, text });
}
function normalizeCreateSessionPayload(payload) {
  if (!isRecord3(payload)) {
    return fail({
      message: "payload must be an object",
      code: "invalid_payload",
      stage: "payload",
      field: "payload",
      messageType: "invoke",
      action: "create_session"
    });
  }
  return ok({
    sessionId: asString2(payload.sessionId),
    metadata: isRecord3(payload.metadata) ? payload.metadata : void 0
  });
}
function normalizeCloseSessionPayload(payload) {
  if (!isRecord3(payload)) {
    return fail({
      message: "payload must be an object",
      code: "invalid_payload",
      stage: "payload",
      field: "payload",
      messageType: "invoke",
      action: "close_session"
    });
  }
  const toolSessionId = asString2(payload.toolSessionId);
  if (!toolSessionId) {
    return fail({
      message: "close_session requires toolSessionId",
      code: "missing_required_field",
      stage: "payload",
      field: "payload.toolSessionId",
      messageType: "invoke",
      action: "close_session"
    });
  }
  return ok({ toolSessionId });
}
function normalizeAbortSessionPayload(payload) {
  if (!isRecord3(payload)) {
    return fail({
      message: "payload must be an object",
      code: "invalid_payload",
      stage: "payload",
      field: "payload",
      messageType: "invoke",
      action: "abort_session"
    });
  }
  const toolSessionId = asString2(payload.toolSessionId);
  if (!toolSessionId) {
    return fail({
      message: "abort_session requires toolSessionId",
      code: "missing_required_field",
      stage: "payload",
      field: "payload.toolSessionId",
      messageType: "invoke",
      action: "abort_session"
    });
  }
  return ok({ toolSessionId });
}
function normalizePermissionReplyPayload(payload) {
  if (!isRecord3(payload)) {
    return fail({
      message: "payload must be an object",
      code: "invalid_payload",
      stage: "payload",
      field: "payload",
      messageType: "invoke",
      action: "permission_reply"
    });
  }
  const toolSessionId = asString2(payload.toolSessionId);
  const permissionId = asString2(payload.permissionId);
  if (!toolSessionId || !permissionId || !hasKey(payload, "response")) {
    return fail({
      message: "permission_reply requires toolSessionId, permissionId, response",
      code: "missing_required_field",
      stage: "payload",
      field: !toolSessionId ? "payload.toolSessionId" : !permissionId ? "payload.permissionId" : "payload.response",
      messageType: "invoke",
      action: "permission_reply"
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
      action: "permission_reply"
    });
  }
  return ok({ toolSessionId, permissionId, response });
}
function normalizeQuestionReplyPayload(payload) {
  if (!isRecord3(payload)) {
    return fail({
      message: "payload must be an object",
      code: "invalid_payload",
      stage: "payload",
      field: "payload",
      messageType: "invoke",
      action: "question_reply"
    });
  }
  const toolSessionId = asString2(payload.toolSessionId);
  const answer = asString2(payload.answer);
  if (!toolSessionId || !answer) {
    return fail({
      message: "question_reply requires toolSessionId and answer",
      code: "missing_required_field",
      stage: "payload",
      field: !toolSessionId ? "payload.toolSessionId" : "payload.answer",
      messageType: "invoke",
      action: "question_reply"
    });
  }
  if (hasKey(payload, "toolCallId") && !asString2(payload.toolCallId)) {
    return fail({
      message: "question_reply toolCallId must be a non-empty string when provided",
      code: "invalid_payload",
      stage: "payload",
      field: "payload.toolCallId",
      messageType: "invoke",
      action: "question_reply"
    });
  }
  return ok({ toolSessionId, answer, toolCallId: asString2(payload.toolCallId) });
}
function normalizeInvoke(message) {
  const action = asString2(message.action);
  const welinkSessionId = asString2(message.welinkSessionId);
  if (!action || !INVOKE_ACTIONS.includes(action)) {
    return fail({
      message: `unsupported action: ${String(message.action)}`,
      code: "unsupported_action",
      stage: "payload",
      field: "action",
      messageType: "invoke",
      action,
      welinkSessionId
    });
  }
  const base = {
    type: "invoke",
    welinkSessionId,
    action
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
          welinkSessionId
        });
      }
      const payload = normalizeCreateSessionPayload(message.payload);
      return payload.ok ? ok({
        type: "invoke",
        welinkSessionId,
        action,
        payload: payload.value
      }) : payload;
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
    welinkSessionId
  });
}
function normalizeDownstreamMessage(message, logger) {
  if (!isRecord3(message) || !asString2(message.type)) {
    const result2 = fail({
      message: "message type is required",
      code: "missing_required_field",
      stage: "message",
      field: "type"
    });
    if (!result2.ok) {
      logDownstreamNormalizationFailure(logger, message, result2.error);
    }
    return result2;
  }
  const messageType = message.type;
  if (!DOWNSTREAM_MESSAGE_TYPES.includes(messageType)) {
    const result2 = fail({
      message: `unsupported message type: ${messageType}`,
      code: "unsupported_message",
      stage: "message",
      field: "type",
      messageType
    });
    if (!result2.ok) {
      logDownstreamNormalizationFailure(logger, message, result2.error);
    }
    return result2;
  }
  if (messageType === "status_query") {
    logDebug2(logger, "downstream.normalization_succeeded", { messageType: "status_query" });
    return ok({ type: "status_query" });
  }
  const result = normalizeInvoke(message);
  if (!result.ok) {
    const enrichedError = {
      ...result.error,
      welinkSessionId: result.error.welinkSessionId ?? asString2(message.welinkSessionId)
    };
    logDownstreamNormalizationFailure(logger, message, enrichedError);
    return { ok: false, error: enrichedError };
  }
  logDebug2(logger, "downstream.normalization_succeeded", {
    messageType: result.value.type,
    action: result.value.action,
    welinkSessionId: result.value.welinkSessionId,
    toolSessionId: extractToolSessionId2(result.value.payload)
  });
  return result;
}

// src/runtime/RegisterMetadata.ts
import { existsSync, readFileSync } from "node:fs";
import os from "node:os";
import path2 from "node:path";
import { fileURLToPath } from "node:url";
var MESSAGE_BRIDGE_TOOL_TYPE = "openclaw";
var EMPTY_MAC_ADDRESS = "";
var UNKNOWN_TOOL_VERSION = "unknown";
var ZERO_MAC_ADDRESS = "00:00:00:00:00:00";
var MAC_ADDRESS_PATTERN = /^([0-9a-f]{2}[:-]){5}[0-9a-f]{2}$/i;
function normalizeMacAddress(macAddress) {
  return macAddress.trim().replace(/-/g, ":").toLowerCase();
}
function isUsableMacAddress(macAddress) {
  if (!macAddress) {
    return false;
  }
  const normalized = normalizeMacAddress(macAddress);
  return MAC_ADDRESS_PATTERN.test(normalized) && normalized !== ZERO_MAC_ADDRESS;
}
function resolveMacAddress(logger, networkInterfaces) {
  const interfaces = networkInterfaces();
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
      return normalizeMacAddress(entry.mac);
    }
  }
  logger.warn("runtime.mac_address.unavailable", {
    platform: os.platform(),
    interfaceCount
  });
  return EMPTY_MAC_ADDRESS;
}
function resolvePackageVersion(logger) {
  const moduleFile = fileURLToPath(import.meta.url);
  let currentDir = path2.dirname(moduleFile);
  for (let depth = 0; depth < 6; depth += 1) {
    const packageJsonPath = path2.join(currentDir, "package.json");
    if (existsSync(packageJsonPath)) {
      try {
        const packageJson = JSON.parse(readFileSync(packageJsonPath, "utf8"));
        if (typeof packageJson.version === "string" && packageJson.version.trim()) {
          return packageJson.version.trim();
        }
      } catch (error) {
        logger.warn("runtime.tool_version.read_failed", {
          packageJsonPath,
          error: error instanceof Error ? error.message : String(error)
        });
      }
    }
    const parentDir = path2.dirname(currentDir);
    if (parentDir === currentDir) {
      break;
    }
    currentDir = parentDir;
  }
  logger.warn("runtime.tool_version.unavailable");
  return UNKNOWN_TOOL_VERSION;
}
function resolveRegisterMetadata(logger, deps = {}) {
  return {
    deviceName: deps.hostname?.() ?? os.hostname(),
    toolType: MESSAGE_BRIDGE_TOOL_TYPE,
    toolVersion: deps.toolVersion?.trim() || resolvePackageVersion(logger),
    macAddress: resolveMacAddress(logger, deps.networkInterfaces ?? os.networkInterfaces)
  };
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
function asString3(value) {
  return typeof value === "string" && value.trim() ? value : void 0;
}
function logDebug3(logger, message, meta) {
  if (logger.debug) {
    logger.debug(message, meta);
    return;
  }
  logger.info(message, meta);
}
function extractDownstreamLogFields(raw) {
  if (!isRecord4(raw)) {
    return {};
  }
  const payload = isRecord4(raw.payload) ? raw.payload : void 0;
  return {
    messageType: asString3(raw.type),
    action: asString3(raw.action),
    welinkSessionId: asString3(raw.welinkSessionId),
    toolSessionId: asString3(payload?.toolSessionId),
    gatewayMessageId: asString3(raw.messageId)
  };
}
function getInvokeToolSessionId(message) {
  if ("toolSessionId" in message.payload) {
    return message.payload.toolSessionId;
  }
  return void 0;
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
function createSelectedModelState() {
  return {
    provider: null,
    model: null,
    thinkLevel: null
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
function buildToolPartUpdated(state) {
  return {
    type: "message.part.updated",
    properties: {
      part: {
        id: state.partId,
        sessionID: state.sessionKey,
        messageID: state.messageId,
        type: "tool",
        tool: state.toolName,
        callID: state.toolCallId,
        state: {
          status: state.status,
          ...state.output !== void 0 ? { output: state.output } : {},
          ...state.error !== void 0 ? { error: state.error } : {},
          ...state.title !== void 0 ? { title: state.title } : {}
        }
      }
    }
  };
}
function extractToolResultTitle(meta, toolName) {
  if (!isRecord4(meta)) {
    return void 0;
  }
  const summary = meta.summary;
  if (typeof summary === "string" && summary.trim().length > 0) {
    return summary;
  }
  const title = meta.title;
  if (typeof title === "string" && title.trim().length > 0) {
    return title;
  }
  return toolName;
}
var OpenClawGatewayBridge = class {
  constructor(options) {
    this.options = options;
    this.runtime = options.runtime;
    this.registerMetadata = options.registerMetadata ?? resolveRegisterMetadata(options.logger);
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
        deviceName: this.registerMetadata.deviceName,
        macAddress: this.registerMetadata.macAddress,
        os: os2.platform(),
        toolType: this.registerMetadata.toolType,
        toolVersion: this.registerMetadata.toolVersion
      },
      logger: options.logger
    });
    this.status = {
      accountId: options.account.accountId,
      running: false,
      connected: false,
      lastStartAt: null,
      lastStopAt: null,
      lastError: null,
      lastReadyAt: null,
      lastInboundAt: null,
      lastOutboundAt: null,
      lastHeartbeatAt: null,
      probe: null,
      lastProbeAt: null
    };
    this.connection.on("stateChange", (state) => {
      const now = Date.now();
      this.options.logger.info("gateway.state.changed", { state });
      this.status.connected = state === "CONNECTED" || state === "READY";
      if (state === "READY") {
        this.status.lastReadyAt = now;
      }
      this.options.setStatus({ ...this.status });
    });
    this.connection.on("inbound", () => {
      this.status.lastInboundAt = Date.now();
      this.options.setStatus({ ...this.status });
    });
    this.connection.on("outbound", () => {
      this.status.lastOutboundAt = Date.now();
      this.options.setStatus({ ...this.status });
    });
    this.connection.on("heartbeat", () => {
      this.status.lastHeartbeatAt = Date.now();
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
  registerMetadata;
  activeToolSessions = /* @__PURE__ */ new Map();
  activeRunToSessionKey = /* @__PURE__ */ new Map();
  running = false;
  status;
  unsubscribeAgentEvents = null;
  async start() {
    this.options.logger.info("runtime.start.requested", {
      accountId: this.options.account.accountId
    });
    if (this.running) {
      this.options.logger.info("runtime.start.skipped_already_started", {
        accountId: this.options.account.accountId
      });
      return;
    }
    this.running = true;
    this.status.running = true;
    this.status.lastStartAt = Date.now();
    this.options.setStatus({ ...this.status });
    if (!this.unsubscribeAgentEvents && this.runtime.events?.onAgentEvent) {
      this.unsubscribeAgentEvents = this.runtime.events.onAgentEvent((evt) => {
        this.handleRuntimeAgentEvent(evt);
      });
    }
    await this.connection.connect();
    this.options.logger.info("runtime.start.completed", {
      accountId: this.options.account.accountId
    });
  }
  getSubagentRuntime() {
    return this.runtime.subagent ?? null;
  }
  async stop() {
    this.options.logger.info("runtime.stop.requested", {
      accountId: this.options.account.accountId
    });
    if (!this.running) {
      this.options.logger.info("runtime.stop.skipped_not_running", {
        accountId: this.options.account.accountId
      });
      return;
    }
    this.running = false;
    this.connection.disconnect();
    this.unsubscribeAgentEvents?.();
    this.unsubscribeAgentEvents = null;
    this.activeToolSessions.clear();
    this.activeRunToSessionKey.clear();
    this.status.running = false;
    this.status.connected = false;
    this.status.lastStopAt = Date.now();
    this.options.setStatus({ ...this.status });
    this.options.logger.info("runtime.stop.completed", {
      accountId: this.options.account.accountId
    });
  }
  async handleDownstreamMessage(raw) {
    if (!this.connection.isConnected()) {
      this.options.logger.warn("runtime.downstream_ignored_no_connection");
      return;
    }
    const startedAt = Date.now();
    const fields = extractDownstreamLogFields(raw);
    logDebug3(this.options.logger, "runtime.downstream.received", fields);
    const normalized = normalizeDownstreamMessage(raw, this.options.logger);
    if (!normalized.ok) {
      this.options.logger.warn("runtime.downstream_ignored_non_protocol", {
        ...fields,
        errorCode: normalized.error.code,
        stage: normalized.error.stage,
        field: normalized.error.field,
        errorMessage: normalized.error.message
      });
      this.sendToolError({
        type: "tool_error",
        welinkSessionId: fields.welinkSessionId,
        toolSessionId: fields.toolSessionId,
        error: normalized.error.message
      }, {
        gatewayMessageId: fields.gatewayMessageId,
        action: fields.action,
        welinkSessionId: fields.welinkSessionId,
        toolSessionId: fields.toolSessionId
      });
      return;
    }
    if (normalized.value.type === "status_query") {
      this.options.logger.info("runtime.status_query.received", fields);
      const message = {
        type: "status_response",
        opencodeOnline: this.running
      };
      this.connection.send(message, {
        gatewayMessageId: fields.gatewayMessageId,
        action: "status_query"
      });
      this.options.logger.info("runtime.status_query.responded", {
        ...fields,
        latencyMs: Date.now() - startedAt
      });
      return;
    }
    this.options.logger.info("runtime.invoke.received", {
      ...fields,
      action: normalized.value.action,
      welinkSessionId: normalized.value.welinkSessionId,
      toolSessionId: getInvokeToolSessionId(normalized.value)
    });
    const invokeContext = {
      gatewayMessageId: fields.gatewayMessageId,
      action: normalized.value.action,
      welinkSessionId: normalized.value.welinkSessionId,
      toolSessionId: getInvokeToolSessionId(normalized.value)
    };
    const invokeResult = await this.handleInvoke(normalized.value, invokeContext);
    if (invokeResult.success) {
      this.options.logger.info("runtime.invoke.completed", {
        ...fields,
        action: normalized.value.action,
        welinkSessionId: normalized.value.welinkSessionId,
        toolSessionId: getInvokeToolSessionId(normalized.value),
        latencyMs: Date.now() - startedAt
      });
      return;
    }
    this.options.logger.warn("runtime.invoke.failed", {
      ...fields,
      action: normalized.value.action,
      welinkSessionId: normalized.value.welinkSessionId,
      toolSessionId: getInvokeToolSessionId(normalized.value),
      latencyMs: Date.now() - startedAt,
      reason: invokeResult.reason
    });
  }
  async handleInvoke(message, context) {
    switch (message.action) {
      case "chat":
        if (await this.handleChat(message, context)) {
          return { success: true };
        }
        return { success: false, reason: "chat_failed" };
      case "create_session":
        this.handleCreateSession(message, context);
        return { success: true };
      case "close_session":
        await this.handleCloseSession(message, context);
        return { success: true };
      case "abort_session":
        if (await this.handleAbortSession(message, context)) {
          return { success: true };
        }
        return { success: false, reason: "abort_session_failed" };
      case "permission_reply":
      case "question_reply":
        this.sendUnsupported(message.action, message.payload.toolSessionId, message.welinkSessionId, context);
        return { success: false, reason: `unsupported_action:${message.action}` };
    }
  }
  async handleChat(message, context) {
    const record = this.sessionRegistry.ensure(message.payload.toolSessionId, message.welinkSessionId);
    const assistantStream = createAssistantStreamState(record.sessionKey);
    const toolStates = /* @__PURE__ */ new Map();
    const startedAt = Date.now();
    const chatRequestId = randomUUID3();
    const configuredTimeoutMs = this.options.account.runTimeoutMs;
    const selectedModel = createSelectedModelState();
    const executionPath = this.runtime.channel?.routing && this.runtime.channel?.reply ? "runtime_reply" : "subagent_fallback";
    this.activeToolSessions.set(record.sessionKey, {
      toolSessionId: record.toolSessionId,
      runId: null,
      assistantStream,
      toolStates,
      pendingToolResultTarget: null
    });
    this.sendToolEvent({
      type: "tool_event",
      toolSessionId: record.toolSessionId,
      event: buildBusyEvent(record.sessionKey)
    }, context);
    this.logChatStarted({
      toolSessionId: record.toolSessionId,
      welinkSessionId: record.welinkSessionId,
      sessionKey: record.sessionKey,
      chatText: message.payload.text,
      textLength: message.payload.text.length,
      startedAt,
      configuredTimeoutMs,
      executionPath,
      chatRequestId,
      retryAttempt: 0
    });
    let retryAttempt = 0;
    let lastErrorMessage = null;
    let lastErrorExtra;
    while (true) {
      if (!this.runtime.channel?.routing?.resolveAgentRoute || !this.runtime.channel?.reply) {
        const fallbackResult = await this.handleChatWithSubagentFallback(
          record,
          message.payload.text,
          startedAt,
          selectedModel,
          chatRequestId,
          retryAttempt,
          context
        );
        if (fallbackResult.ok) {
          return true;
        }
        lastErrorMessage = fallbackResult.errorMessage;
        lastErrorExtra = fallbackResult.extra;
        if (retryAttempt === 0 && this.shouldRetryBeforeFirstChunkTimeout(lastErrorMessage, assistantStream)) {
          retryAttempt = 1;
          this.logChatStarted({
            toolSessionId: record.toolSessionId,
            welinkSessionId: record.welinkSessionId,
            sessionKey: record.sessionKey,
            chatText: message.payload.text,
            textLength: message.payload.text.length,
            startedAt,
            configuredTimeoutMs,
            executionPath,
            chatRequestId,
            retryAttempt
          });
          continue;
        }
        break;
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
      const handleModelSelected = (selection) => {
        selectedModel.provider = selection.provider;
        selectedModel.model = selection.model;
        selectedModel.thinkLevel = selection.thinkLevel ?? null;
        this.options.logger.info("bridge.chat.model_selected", {
          toolSessionId: record.toolSessionId,
          sessionKey: record.sessionKey,
          configuredTimeoutMs,
          runTimeoutMs: configuredTimeoutMs,
          executionPath: "runtime_reply",
          chatRequestId,
          retryAttempt,
          provider: selection.provider,
          model: selection.model,
          thinkLevel: selection.thinkLevel ?? null
        });
        onModelSelected?.(selection);
      };
      const deliver = async (rawPayload, info) => {
        const payload = isRecord4(rawPayload) ? normalizeOutboundReplyPayload(rawPayload) : normalizeOutboundReplyPayload({});
        if (info.kind === "tool") {
          const activeSession = this.activeToolSessions.get(record.sessionKey);
          const toolCallId = activeSession?.pendingToolResultTarget;
          if (!toolCallId) {
            return;
          }
          const toolState = activeSession.toolStates.get(toolCallId);
          if (!toolState) {
            return;
          }
          const output = typeof payload.text === "string" ? payload.text.trim() : "";
          if (output.length === 0) {
            return;
          }
          toolState.output = output;
          this.emitToolPartUpdate(record.toolSessionId, toolState, context);
          return;
        }
        if (typeof payload.text === "string" && payload.text.length > 0) {
          const now = Date.now();
          assistantStream.chunkCount += 1;
          if (assistantStream.firstChunkAt === null) {
            assistantStream.firstChunkAt = now;
            this.options.logger.info("bridge.chat.first_chunk", {
              toolSessionId: record.toolSessionId,
              sessionKey: record.sessionKey,
              chatRequestId,
              retryAttempt,
              latencyMs: now - startedAt,
              chunkLength: payload.text.length,
              deltaText: payload.text
            });
          } else {
            this.options.logger.info("bridge.chat.chunk", {
              toolSessionId: record.toolSessionId,
              sessionKey: record.sessionKey,
              chatRequestId,
              retryAttempt,
              chunkIndex: assistantStream.chunkCount,
              chunkLength: payload.text.length,
              sinceStartMs: now - startedAt,
              sinceFirstChunkMs: now - assistantStream.firstChunkAt,
              deltaText: payload.text
            });
          }
          this.sendAssistantStreamChunk(record.toolSessionId, assistantStream, payload.text, context);
        }
      };
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
            onAgentRunStart: (runId) => {
              this.trackSessionRunId(record.sessionKey, runId);
            },
            onModelSelected: handleModelSelected,
            timeoutOverrideSeconds: Math.ceil(configuredTimeoutMs / 1e3)
          }
        });
      } catch (error) {
        const errorMessage = error instanceof Error ? error.message : String(error);
        if (retryAttempt === 0 && this.shouldRetryBeforeFirstChunkTimeout(errorMessage, assistantStream)) {
          retryAttempt = 1;
          this.logChatStarted({
            toolSessionId: record.toolSessionId,
            welinkSessionId: record.welinkSessionId,
            sessionKey: record.sessionKey,
            chatText: message.payload.text,
            textLength: message.payload.text.length,
            startedAt,
            configuredTimeoutMs,
            executionPath,
            chatRequestId,
            retryAttempt
          });
          continue;
        }
        lastErrorMessage = errorMessage;
        lastErrorExtra = void 0;
        break;
      }
      const finalText = assistantStream.accumulatedText || "(empty response)";
      this.sendAssistantFinalResponse(
        record.toolSessionId,
        assistantStream,
        finalText,
        context
      );
      this.logChatCompleted({
        toolSessionId: record.toolSessionId,
        sessionKey: record.sessionKey,
        configuredTimeoutMs,
        startedAt,
        assistantStream,
        selectedModel,
        executionPath: "runtime_reply",
        chatRequestId,
        retryAttempt,
        responseLength: finalText.length,
        finalText
      });
      this.sendToolEvent({
        type: "tool_event",
        toolSessionId: record.toolSessionId,
        event: buildIdleEvent(record.sessionKey)
      }, context);
      this.sendToolDone({
        type: "tool_done",
        toolSessionId: record.toolSessionId,
        welinkSessionId: record.welinkSessionId
      }, context);
      this.clearActiveToolSession(record.sessionKey);
      return true;
    }
    this.clearActiveToolSession(record.sessionKey);
    const finalErrorMessage = lastErrorMessage ?? "chat_failed_without_error";
    this.logChatFailed({
      toolSessionId: record.toolSessionId,
      sessionKey: record.sessionKey,
      configuredTimeoutMs,
      startedAt,
      assistantStream,
      selectedModel,
      executionPath,
      chatRequestId,
      retryAttempt,
      error: finalErrorMessage,
      extra: lastErrorExtra
    });
    this.sendToolEvent({
      type: "tool_event",
      toolSessionId: record.toolSessionId,
      event: buildSessionErrorEvent(record.sessionKey, finalErrorMessage)
    }, context);
    this.sendToolError({
      type: "tool_error",
      toolSessionId: record.toolSessionId,
      welinkSessionId: record.welinkSessionId,
      error: finalErrorMessage
    }, context);
    return false;
  }
  async handleChatWithSubagentFallback(record, text, startedAt, selectedModel, chatRequestId, retryAttempt, context) {
    const assistantStream = createAssistantStreamState(record.sessionKey);
    const configuredTimeoutMs = this.options.account.runTimeoutMs;
    const subagent = this.getSubagentRuntime();
    if (!subagent) {
      const errorMessage = "openclaw_runtime_missing_reply_executor";
      return { ok: false, errorMessage };
    }
    try {
      const run = await subagent.run({
        sessionKey: record.sessionKey,
        message: text,
        deliver: false,
        idempotencyKey: `chat:${record.toolSessionId}:${chatRequestId}`
      });
      const wait = await subagent.waitForRun({
        runId: run.runId,
        timeoutMs: configuredTimeoutMs
      });
      if (wait.status !== "ok") {
        const errorMessage = wait.error || `subagent_${wait.status}`;
        return {
          ok: false,
          errorMessage,
          extra: {
            waitStatus: wait.status,
            waitError: wait.error ?? null
          }
        };
      }
      const session = await subagent.getSessionMessages({
        sessionKey: record.sessionKey,
        limit: 50
      });
      const assistantText = extractAssistantText(session.messages) || "(empty response)";
      this.sendAssistantFinalResponse(record.toolSessionId, assistantStream, assistantText, context);
      this.logChatCompleted({
        toolSessionId: record.toolSessionId,
        sessionKey: record.sessionKey,
        configuredTimeoutMs,
        startedAt,
        assistantStream,
        selectedModel,
        executionPath: "subagent_fallback",
        chatRequestId,
        retryAttempt,
        responseLength: assistantText.length,
        finalText: assistantText,
        extra: {
          waitStatus: wait.status,
          waitError: wait.error ?? null
        }
      });
      this.sendToolEvent({
        type: "tool_event",
        toolSessionId: record.toolSessionId,
        event: buildIdleEvent(record.sessionKey)
      }, context);
      this.sendToolDone({
        type: "tool_done",
        toolSessionId: record.toolSessionId,
        welinkSessionId: record.welinkSessionId
      }, context);
      this.clearActiveToolSession(record.sessionKey);
      return { ok: true };
    } catch (error) {
      const errorMessage = error instanceof Error ? error.message : String(error);
      return { ok: false, errorMessage };
    }
  }
  shouldRetryBeforeFirstChunkTimeout(errorMessage, assistantStream) {
    const lowered = errorMessage.toLowerCase();
    const isTimeout = lowered.includes("timed out") || lowered.includes("timeout");
    return assistantStream.firstChunkAt === null && isTimeout;
  }
  handleCreateSession(message, context) {
    const record = this.sessionRegistry.create(message.welinkSessionId, message.payload.sessionId);
    const response = {
      type: "session_created",
      welinkSessionId: message.welinkSessionId,
      toolSessionId: record.toolSessionId,
      session: {
        sessionId: record.sessionKey
      }
    };
    this.connection.send(response, {
      ...context,
      toolSessionId: record.toolSessionId,
      welinkSessionId: message.welinkSessionId
    });
  }
  async handleCloseSession(message, _context) {
    const record = this.sessionRegistry.delete(message.payload.toolSessionId);
    if (record) {
      this.clearActiveToolSession(record.sessionKey);
      await this.getSubagentRuntime()?.deleteSession({
        sessionKey: record.sessionKey
      });
    }
  }
  async handleAbortSession(message, context) {
    const record = this.sessionRegistry.get(message.payload.toolSessionId);
    if (!record) {
      this.sendToolError({
        type: "tool_error",
        toolSessionId: message.payload.toolSessionId,
        welinkSessionId: message.welinkSessionId,
        error: "unknown_tool_session",
        reason: TOOL_ERROR_REASON.SESSION_NOT_FOUND
      }, context);
      return false;
    }
    this.clearActiveToolSession(record.sessionKey);
    await this.getSubagentRuntime()?.deleteSession({
      sessionKey: record.sessionKey
    });
    this.sessionRegistry.delete(message.payload.toolSessionId);
    this.sendToolDone({
      type: "tool_done",
      toolSessionId: message.payload.toolSessionId,
      welinkSessionId: message.welinkSessionId
    }, context);
    return true;
  }
  sendUnsupported(action, toolSessionId, welinkSessionId, context) {
    this.sendToolError({
      type: "tool_error",
      toolSessionId,
      welinkSessionId,
      error: `unsupported_in_openclaw_v1:${action}`
    }, context);
  }
  buildSendContext(message, context) {
    return {
      gatewayMessageId: context?.gatewayMessageId,
      action: context?.action,
      welinkSessionId: context?.welinkSessionId ?? message.welinkSessionId,
      toolSessionId: context?.toolSessionId ?? message.toolSessionId
    };
  }
  buildChatEventContext(toolSessionId) {
    const record = this.sessionRegistry.get(toolSessionId);
    return {
      action: "chat",
      toolSessionId,
      welinkSessionId: record?.welinkSessionId
    };
  }
  sendToolEvent(message, context) {
    const sendContext = this.buildSendContext(message, context);
    logDebug3(this.options.logger, "runtime.tool_event.sending", {
      gatewayMessageId: sendContext.gatewayMessageId,
      action: sendContext.action,
      welinkSessionId: sendContext.welinkSessionId,
      toolSessionId: sendContext.toolSessionId,
      eventType: isRecord4(message.event) ? asString3(message.event.type) : void 0
    });
    this.connection.send(message, {
      ...sendContext,
      eventType: isRecord4(message.event) ? asString3(message.event.type) : void 0
    });
  }
  sendToolDone(message, context) {
    const sendContext = this.buildSendContext(message, context);
    this.options.logger.info("runtime.tool_done.sending", {
      gatewayMessageId: sendContext.gatewayMessageId,
      action: sendContext.action,
      welinkSessionId: sendContext.welinkSessionId,
      toolSessionId: sendContext.toolSessionId
    });
    try {
      this.connection.send(message, sendContext);
    } catch {
      this.options.logger.warn("runtime.tool_done.skipped_no_connection", {
        gatewayMessageId: sendContext.gatewayMessageId,
        action: sendContext.action,
        welinkSessionId: sendContext.welinkSessionId,
        toolSessionId: sendContext.toolSessionId
      });
    }
  }
  sendToolError(message, context) {
    const sendContext = this.buildSendContext(message, context);
    this.options.logger.error("runtime.tool_error.sending", {
      gatewayMessageId: sendContext.gatewayMessageId,
      action: sendContext.action,
      welinkSessionId: sendContext.welinkSessionId,
      toolSessionId: sendContext.toolSessionId,
      error: message.error,
      reason: message.reason
    });
    try {
      this.connection.send(message, sendContext);
    } catch {
      this.options.logger.warn("runtime.tool_error.skipped_no_connection", {
        gatewayMessageId: sendContext.gatewayMessageId,
        action: sendContext.action,
        welinkSessionId: sendContext.welinkSessionId,
        toolSessionId: sendContext.toolSessionId
      });
    }
  }
  sendAssistantStreamChunk(toolSessionId, state, chunk, context) {
    state.accumulatedText += chunk;
    if (state.accumulatedText === chunk) {
      this.ensureAssistantMessageStarted(toolSessionId, state, context);
      this.sendToolEvent({
        type: "tool_event",
        toolSessionId,
        event: buildAssistantPartUpdated(state.sessionKey, state.messageId, state.partId, chunk, chunk)
      }, context);
      return;
    }
    this.sendToolEvent({
      type: "tool_event",
      toolSessionId,
      event: buildAssistantPartDelta(state.sessionKey, state.messageId, state.partId, chunk)
    }, context);
  }
  ensureAssistantMessageStarted(toolSessionId, state, context) {
    if (state.seeded) {
      return;
    }
    this.sendToolEvent({
      type: "tool_event",
      toolSessionId,
      event: buildAssistantMessageUpdated(state.sessionKey, state.messageId)
    }, context);
    state.seeded = true;
  }
  sendAssistantFinalResponse(toolSessionId, state, text, context) {
    if (state.seeded) {
      if (state.accumulatedText.length === 0) {
        this.sendToolEvent({
          type: "tool_event",
          toolSessionId,
          event: buildAssistantPartUpdated(state.sessionKey, state.messageId, state.partId, text)
        }, context);
        return;
      }
      this.sendToolEvent({
        type: "tool_event",
        toolSessionId,
        event: buildAssistantPartUpdated(
          state.sessionKey,
          state.messageId,
          state.partId,
          state.accumulatedText || text
        )
      }, context);
      return;
    }
    this.sendToolEvent({
      type: "tool_event",
      toolSessionId,
      event: buildAssistantMessageUpdated(state.sessionKey, state.messageId)
    }, context);
    this.sendToolEvent({
      type: "tool_event",
      toolSessionId,
      event: buildAssistantPartUpdated(state.sessionKey, state.messageId, state.partId, text)
    }, context);
    state.seeded = true;
  }
  emitToolPartUpdate(toolSessionId, toolState, context) {
    this.sendToolEvent({
      type: "tool_event",
      toolSessionId,
      event: buildToolPartUpdated(toolState)
    }, context);
  }
  trackSessionRunId(sessionKey, runId) {
    if (!runId) {
      return;
    }
    const activeSession = this.activeToolSessions.get(sessionKey);
    if (!activeSession) {
      return;
    }
    if (activeSession.runId && activeSession.runId !== runId) {
      this.activeRunToSessionKey.delete(activeSession.runId);
    }
    activeSession.runId = runId;
    this.activeRunToSessionKey.set(runId, sessionKey);
  }
  clearActiveToolSession(sessionKey) {
    const activeSession = this.activeToolSessions.get(sessionKey);
    if (activeSession?.runId) {
      this.activeRunToSessionKey.delete(activeSession.runId);
    }
    this.activeToolSessions.delete(sessionKey);
  }
  handleRuntimeAgentEvent(evt) {
    if (evt.stream !== "tool" || !isRecord4(evt.data)) {
      return;
    }
    const directSessionKey = typeof evt.sessionKey === "string" ? evt.sessionKey : void 0;
    const mappedSessionKey = typeof evt.runId === "string" && evt.runId.length > 0 ? this.activeRunToSessionKey.get(evt.runId) : void 0;
    const sessionKey = directSessionKey ?? mappedSessionKey;
    const activeSession = (directSessionKey ? this.activeToolSessions.get(directSessionKey) : void 0) ?? (mappedSessionKey ? this.activeToolSessions.get(mappedSessionKey) : void 0);
    if (!sessionKey || !activeSession) {
      return;
    }
    const phase = typeof evt.data.phase === "string" ? evt.data.phase : "";
    const toolName = typeof evt.data.name === "string" && evt.data.name.length > 0 ? evt.data.name : "tool";
    const toolCallId = typeof evt.data.toolCallId === "string" && evt.data.toolCallId.length > 0 ? evt.data.toolCallId : `tool_${randomUUID3()}`;
    const context = this.buildChatEventContext(activeSession.toolSessionId);
    this.ensureAssistantMessageStarted(activeSession.toolSessionId, activeSession.assistantStream, context);
    let toolState = activeSession.toolStates.get(toolCallId);
    if (!toolState) {
      const nextToolState = {
        toolCallId,
        toolName,
        partId: `tool_${randomUUID3()}`,
        messageId: activeSession.assistantStream.messageId,
        sessionKey,
        status: "running"
      };
      activeSession.toolStates.set(toolCallId, nextToolState);
      toolState = nextToolState;
    }
    toolState.toolName = toolName;
    toolState.title = extractToolResultTitle(evt.data.meta, toolName) ?? toolState.title;
    if (phase === "start" || phase === "update") {
      toolState.status = "running";
      this.emitToolPartUpdate(activeSession.toolSessionId, toolState, context);
      return;
    }
    if (phase === "result") {
      const isError = evt.data.isError === true;
      toolState.status = isError ? "error" : "completed";
      toolState.error = isError ? `tool_${toolName}_failed` : void 0;
      activeSession.pendingToolResultTarget = toolCallId;
      this.emitToolPartUpdate(activeSession.toolSessionId, toolState, context);
    }
  }
  logChatStarted(params) {
    this.options.logger.info("bridge.chat.started", {
      toolSessionId: params.toolSessionId,
      welinkSessionId: params.welinkSessionId,
      sessionKey: params.sessionKey,
      chatText: params.chatText,
      textLength: params.textLength,
      startedAt: params.startedAt,
      configuredTimeoutMs: params.configuredTimeoutMs,
      runTimeoutMs: params.configuredTimeoutMs,
      executionPath: params.executionPath,
      chatRequestId: params.chatRequestId,
      retryAttempt: params.retryAttempt
    });
  }
  logChatCompleted(params) {
    this.options.logger.info("bridge.chat.completed", {
      ...this.buildChatDiagnostics(params),
      responseLength: params.responseLength,
      finalText: params.finalText,
      ...params.extra ?? {}
    });
  }
  logChatFailed(params) {
    const errorLower = params.error.toLowerCase();
    const isTimeout = errorLower.includes("timed out") || errorLower.includes("timeout");
    this.options.logger.warn("bridge.chat.failed", {
      ...this.buildChatDiagnostics(params),
      error: params.error,
      failureStage: params.assistantStream.firstChunkAt === null ? "before_first_chunk" : "after_first_chunk",
      errorCategory: isTimeout ? "timeout" : "runtime_error",
      timedOut: isTimeout,
      ...params.extra ?? {}
    });
  }
  buildChatDiagnostics(params) {
    return {
      toolSessionId: params.toolSessionId,
      sessionKey: params.sessionKey,
      configuredTimeoutMs: params.configuredTimeoutMs,
      runTimeoutMs: params.configuredTimeoutMs,
      executionPath: params.executionPath,
      chatRequestId: params.chatRequestId,
      retryAttempt: params.retryAttempt,
      provider: params.selectedModel.provider,
      model: params.selectedModel.model,
      thinkLevel: params.selectedModel.thinkLevel,
      chunkCount: params.assistantStream.chunkCount,
      firstChunkLatencyMs: params.assistantStream.firstChunkAt === null ? null : params.assistantStream.firstChunkAt - params.startedAt,
      totalLatencyMs: Date.now() - params.startedAt
    };
  }
};

// src/onboarding.ts
var SETUP_TITLE = "Message Bridge setup";
var SETUP_INTRO = [
  "\u914D\u7F6E ai-gateway \u7684 WebSocket \u5730\u5740\u4EE5\u53CA\u5BF9\u5E94\u7684 AK/SK\u3002",
  "\u66F4\u65B0\u73B0\u6709\u914D\u7F6E\u65F6\uFF0C\u51ED\u8BC1\u7559\u7A7A\u4F1A\u4FDD\u7559\u5F53\u524D\u503C\u3002"
].join("\n");
function buildSelectionHint(configured, enabled, requiresMigration) {
  if (requiresMigration) {
    return "migration required";
  }
  if (!configured) {
    return "not configured";
  }
  return enabled ? "configured" : "configured \xB7 disabled";
}
function buildLegacyAccountsMessage() {
  return `\u68C0\u6D4B\u5230\u5DF2\u5E9F\u5F03\u7684 channels.${CHANNEL_ID}.accounts \u914D\u7F6E\u3002${LEGACY_ACCOUNTS_MIGRATION_FIX}`;
}
async function promptMessageBridgeSetup(params) {
  const { cfg, prompter } = params;
  if (hasLegacyAccountsConfig(cfg)) {
    await prompter.note(buildLegacyAccountsMessage(), SETUP_TITLE);
    return "skip";
  }
  const account = resolveAccount(cfg, DEFAULT_ACCOUNT_ID);
  await prompter.note(SETUP_INTRO, SETUP_TITLE);
  let draft = {
    name: account.name ?? "",
    url: account.gateway.url,
    token: account.auth.ak ?? "",
    password: account.auth.sk ?? ""
  };
  while (true) {
    const name = await prompter.text({
      message: "Account name (optional)",
      placeholder: "Message Bridge",
      initialValue: draft.name
    });
    const url = await prompter.text({
      message: "Gateway WebSocket URL",
      placeholder: "ws://localhost:8081/ws/agent",
      initialValue: draft.url
    });
    const ak = await prompter.text({
      message: account.auth.ak ? "AK (\u7559\u7A7A\u4FDD\u6301\u5F53\u524D\u503C)" : "AK",
      initialValue: draft.token
    });
    const sk = await prompter.text({
      message: account.auth.sk ? "SK (\u7559\u7A7A\u4FDD\u6301\u5F53\u524D\u503C)" : "SK",
      initialValue: draft.password
    });
    draft = {
      name,
      url,
      token: ak,
      password: sk
    };
    const input = {
      ...name !== void 0 ? { name } : {},
      ...url !== void 0 ? { url } : {},
      ...ak !== void 0 ? { token: ak } : {},
      ...sk !== void 0 ? { password: sk } : {}
    };
    const validationError = validateMessageBridgeSetupInput({
      cfg,
      accountId: DEFAULT_ACCOUNT_ID,
      input
    });
    if (!validationError) {
      return {
        cfg: applyMessageBridgeSetupConfig({
          cfg,
          accountId: DEFAULT_ACCOUNT_ID,
          input
        }),
        accountId: DEFAULT_ACCOUNT_ID
      };
    }
    await prompter.note(validationError, SETUP_TITLE);
  }
}
var messageBridgeOnboardingAdapter = {
  channel: CHANNEL_ID,
  async getStatus({ cfg }) {
    const account = resolveAccount(cfg, DEFAULT_ACCOUNT_ID);
    const requiresMigration = hasLegacyAccountsConfig(cfg);
    const configured = isAccountConfigured(account, cfg);
    const summary = describeAccount(account, cfg);
    const status = buildSelectionHint(configured, account.enabled, requiresMigration);
    return {
      channel: CHANNEL_ID,
      configured,
      selectionHint: status,
      quickstartScore: configured ? 1 : 0,
      statusLines: [
        requiresMigration ? `Message Bridge: migration required` : `Message Bridge: ${status}${configured ? ` \xB7 ${account.gateway.url}` : ""}`,
        ...summary.name ? [`name: ${summary.name}`] : [],
        ...requiresMigration ? [LEGACY_ACCOUNTS_MIGRATION_FIX] : []
      ]
    };
  },
  async configure({ cfg, prompter }) {
    const result = await promptMessageBridgeSetup({ cfg, prompter });
    if (result === "skip") {
      throw new Error(buildLegacyAccountsMessage());
    }
    return result;
  },
  async configureInteractive({ cfg, prompter }) {
    return await promptMessageBridgeSetup({ cfg, prompter });
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

// src/status.ts
import os3 from "node:os";
import {
  buildBaseAccountStatusSnapshot,
  buildProbeChannelStatusSummary,
  createDefaultChannelRuntimeState
} from "openclaw/plugin-sdk";
var HEARTBEAT_GRACE_MS = 5e3;
var silentLogger = {
  info() {
  },
  warn() {
  },
  error() {
  }
};
function elapsedMs(startedAt, now) {
  return Math.max(0, now() - startedAt);
}
function isRecord5(value) {
  return value !== null && typeof value === "object";
}
function asMessageBridgeSnapshot(value) {
  return value;
}
function getMissingConfigFields(snapshot) {
  return Array.isArray(snapshot.missingConfigFields) ? snapshot.missingConfigFields : [];
}
function isRejectedProbeError(message) {
  return message !== "gateway_websocket_error" && message !== "gateway_not_connected";
}
function isAuthRejectedReason(reason) {
  return /(ak|sk|auth|credential|forbidden|secret|signature|token|unauthor|未授权|鉴权|凭证|密钥|签名)/i.test(
    reason
  );
}
function getProbeReason(probe) {
  if (!probe || typeof probe.reason !== "string") {
    return "";
  }
  return probe.reason.trim();
}
function getHeartbeatThresholdMs(snapshot) {
  const heartbeatIntervalMs = typeof snapshot.heartbeatIntervalMs === "number" ? snapshot.heartbeatIntervalMs : 0;
  if (heartbeatIntervalMs <= 0) {
    return 0;
  }
  return heartbeatIntervalMs * 2 + HEARTBEAT_GRACE_MS;
}
function isRuntimeHealthyForDuplicateConnection(snapshot, nowAt) {
  if (snapshot.connected !== true || typeof snapshot.lastReadyAt !== "number") {
    return false;
  }
  if (typeof snapshot.lastHeartbeatAt !== "number") {
    return true;
  }
  const heartbeatThresholdMs = getHeartbeatThresholdMs(snapshot);
  if (heartbeatThresholdMs <= 0) {
    return true;
  }
  return nowAt - snapshot.lastHeartbeatAt <= heartbeatThresholdMs;
}
function createProbeConnection(account) {
  const registerMetadata = resolveRegisterMetadata(silentLogger);
  return new DefaultGatewayConnection({
    url: account.gateway.url,
    reconnectBaseMs: account.gateway.reconnect.baseMs,
    reconnectMaxMs: account.gateway.reconnect.maxMs,
    reconnectExponential: account.gateway.reconnect.exponential,
    heartbeatIntervalMs: account.gateway.heartbeatIntervalMs,
    authPayloadProvider: () => new DefaultAkSkAuth(account.auth.ak, account.auth.sk).generateAuthPayload(),
    registerMessage: {
      type: "register",
      deviceName: registerMetadata.deviceName,
      macAddress: registerMetadata.macAddress,
      os: os3.platform(),
      toolType: registerMetadata.toolType,
      toolVersion: registerMetadata.toolVersion
    },
    logger: silentLogger
  });
}
function createDefaultMessageBridgeRuntimeState() {
  return createDefaultChannelRuntimeState(DEFAULT_ACCOUNT_ID, {
    connected: false,
    lastReadyAt: null,
    lastInboundAt: null,
    lastOutboundAt: null,
    lastHeartbeatAt: null,
    probe: null,
    lastProbeAt: null
  });
}
async function probeMessageBridgeAccount(params, deps = {}) {
  const now = deps.now ?? Date.now;
  const startedAt = now();
  const connection = deps.connectionFactory?.(params.account) ?? createProbeConnection(params.account);
  return await new Promise((resolve) => {
    let settled = false;
    const finish = (result) => {
      if (settled) {
        return;
      }
      settled = true;
      clearTimeout(timer);
      try {
        connection.disconnect();
      } catch {
      }
      resolve(result);
    };
    const timer = setTimeout(() => {
      finish({
        ok: false,
        state: "timeout",
        latencyMs: elapsedMs(startedAt, now),
        reason: "probe timed out before READY"
      });
    }, params.timeoutMs);
    connection.on("stateChange", (state) => {
      if (state === "READY") {
        finish({
          ok: true,
          state: "ready",
          latencyMs: elapsedMs(startedAt, now)
        });
        return;
      }
      if (state === "DISCONNECTED") {
        finish({
          ok: false,
          state: "connect_error",
          latencyMs: elapsedMs(startedAt, now),
          reason: "probe disconnected before READY"
        });
      }
    });
    connection.on("error", (error) => {
      const message = error instanceof Error ? error.message : String(error);
      finish({
        ok: false,
        state: isRejectedProbeError(message) ? "rejected" : "connect_error",
        latencyMs: elapsedMs(startedAt, now),
        reason: message
      });
    });
    connection.connect().catch((error) => {
      const message = error instanceof Error ? error.message : String(error);
      finish({
        ok: false,
        state: "connect_error",
        latencyMs: elapsedMs(startedAt, now),
        reason: message
      });
    });
  });
}
function buildMessageBridgeAccountSnapshot(params) {
  const { account, cfg, probe } = params;
  const runtime = params.runtime;
  const registerMetadata = params.registerMetadata ?? resolveRegisterMetadata(silentLogger);
  const missingConfigFields = getMissingRequiredConfigPaths(account, cfg);
  const legacyAccountsConfigured = hasLegacyAccountsConfig(cfg);
  const configured = missingConfigFields.length === 0 && !legacyAccountsConfigured;
  return {
    ...buildBaseAccountStatusSnapshot({
      account: {
        accountId: account.accountId,
        name: account.name,
        enabled: account.enabled,
        configured
      },
      runtime,
      probe
    }),
    connected: runtime?.connected ?? false,
    gatewayUrl: account.gateway.url || null,
    toolType: registerMetadata.toolType,
    toolVersion: registerMetadata.toolVersion,
    deviceName: registerMetadata.deviceName,
    heartbeatIntervalMs: account.gateway.heartbeatIntervalMs,
    runTimeoutMs: account.runTimeoutMs,
    tokenSource: resolveTokenSource(account),
    legacyAccountsConfigured,
    missingConfigFields,
    lastInboundAt: runtime?.lastInboundAt ?? null,
    lastOutboundAt: runtime?.lastOutboundAt ?? null,
    lastReadyAt: runtime?.lastReadyAt ?? null,
    lastHeartbeatAt: runtime?.lastHeartbeatAt ?? null,
    lastProbeAt: runtime?.lastProbeAt ?? null
  };
}
function buildMessageBridgeChannelSummary(snapshot) {
  const bridgeSnapshot = asMessageBridgeSnapshot(snapshot);
  return {
    ...buildProbeChannelStatusSummary(snapshot, {
      connected: bridgeSnapshot.connected ?? false,
      lastReadyAt: bridgeSnapshot.lastReadyAt ?? null,
      lastHeartbeatAt: bridgeSnapshot.lastHeartbeatAt ?? null
    })
  };
}
function createConfigIssue(params) {
  return {
    channel: "message-bridge",
    accountId: params.accountId,
    kind: "config",
    message: params.message,
    fix: params.fix
  };
}
function createRuntimeIssue(params) {
  return {
    channel: "message-bridge",
    accountId: params.accountId,
    kind: "runtime",
    message: params.message,
    fix: params.fix
  };
}
function createAuthIssue(params) {
  return {
    channel: "message-bridge",
    accountId: params.accountId,
    kind: "auth",
    message: params.message,
    fix: params.fix
  };
}
function collectMessageBridgeStatusIssues(accounts, now = Date.now) {
  const issues = [];
  const nowAt = now();
  for (const rawSnapshot of accounts) {
    const snapshot = asMessageBridgeSnapshot(rawSnapshot);
    const probe = isRecord5(snapshot.probe) ? snapshot.probe : null;
    const probeReason = getProbeReason(probe);
    const suppressDuplicateConnectionIssue = probe?.state === "rejected" && probeReason === "duplicate_connection" && isRuntimeHealthyForDuplicateConnection(snapshot, nowAt);
    const missingConfigFields = getMissingConfigFields(snapshot);
    const heartbeatIntervalMs = typeof snapshot.heartbeatIntervalMs === "number" ? snapshot.heartbeatIntervalMs : 0;
    const runTimeoutMs = typeof snapshot.runTimeoutMs === "number" ? snapshot.runTimeoutMs : 0;
    if (snapshot.legacyAccountsConfigured) {
      issues.push(
        createConfigIssue({
          accountId: snapshot.accountId,
          message: `\u68C0\u6D4B\u5230\u5DF2\u5E9F\u5F03\u7684 channels.message-bridge.accounts \u914D\u7F6E\u3002`,
          fix: LEGACY_ACCOUNTS_MIGRATION_FIX
        })
      );
    }
    if (missingConfigFields.length > 0) {
      issues.push(
        createConfigIssue({
          accountId: snapshot.accountId,
          message: `\u7F3A\u5C11\u5FC5\u586B\u914D\u7F6E\uFF1A${missingConfigFields.join("\u3001")}`,
          fix: CHANNEL_ADD_FIX
        })
      );
    }
    if (probe && typeof probe.error === "string" && probe.error.trim()) {
      issues.push(
        createRuntimeIssue({
          accountId: snapshot.accountId,
          message: `\u63A2\u6D3B\u6267\u884C\u5931\u8D25\uFF1A${probe.error.trim()}`,
          fix: "\u68C0\u67E5 gateway.url\u3001\u8FD0\u884C\u73AF\u5883\u4E2D\u7684 WebSocket \u652F\u6301\u4E0E ai-gateway \u8FDB\u7A0B\u72B6\u6001\u3002"
        })
      );
    }
    if (probe && probe.state === "rejected" && !suppressDuplicateConnectionIssue) {
      const rawReason = probeReason;
      const reason = rawReason ? `\uFF1A${rawReason}` : "";
      if (rawReason && isAuthRejectedReason(rawReason)) {
        issues.push(
          createAuthIssue({
            accountId: snapshot.accountId,
            message: `\u7F51\u5173\u9274\u6743\u88AB\u62D2\u7EDD${reason}`,
            fix: "\u68C0\u67E5 channels.message-bridge.auth.ak / auth.sk \u662F\u5426\u4E0E ai-gateway \u4FA7\u914D\u7F6E\u4E00\u81F4\u3002"
          })
        );
      } else {
        issues.push(
          createRuntimeIssue({
            accountId: snapshot.accountId,
            message: `\u7F51\u5173\u62D2\u7EDD\u6CE8\u518C${reason}`,
            fix: "\u68C0\u67E5 ai-gateway \u7684\u6CE8\u518C\u7B56\u7565\u3001toolType/toolVersion\u3001deviceName \u4E0E\u534F\u8BAE\u517C\u5BB9\u6027\u3002"
          })
        );
      }
    }
    if (probe && probe.state === "connect_error") {
      const reason = typeof probe.reason === "string" && probe.reason.trim() ? `\uFF1A${probe.reason.trim()}` : "";
      issues.push(
        createRuntimeIssue({
          accountId: snapshot.accountId,
          message: `\u63A2\u6D3B\u65E0\u6CD5\u8FDE\u63A5 ai-gateway${reason}`,
          fix: "\u68C0\u67E5 gateway.url\u3001\u7F51\u7EDC\u8FDE\u901A\u6027\u548C ai-gateway \u8FDB\u7A0B\u72B6\u6001\u3002"
        })
      );
    }
    if (probe && probe.state === "timeout") {
      issues.push(
        createRuntimeIssue({
          accountId: snapshot.accountId,
          message: "\u63A2\u6D3B\u5728\u8FDB\u5165 READY \u524D\u8D85\u65F6\u3002",
          fix: "\u68C0\u67E5 ai-gateway \u5F53\u524D\u8D1F\u8F7D\u3001\u9274\u6743\u94FE\u8DEF\u4E0E\u7F51\u7EDC\u65F6\u5EF6\u3002"
        })
      );
    }
    if (typeof snapshot.lastError === "string" && snapshot.lastError.trim()) {
      issues.push(
        createRuntimeIssue({
          accountId: snapshot.accountId,
          message: `\u6700\u8FD1\u4E00\u6B21\u8FD0\u884C\u9519\u8BEF\uFF1A${snapshot.lastError.trim()}`,
          fix: "\u7ED3\u5408 ai-gateway \u65E5\u5FD7\u4E0E bridge.chat.failed \u8BCA\u65AD\u94FE\u8DEF\u95EE\u9898\u3002"
        })
      );
    }
    if (snapshot.running !== true) {
      continue;
    }
    const heartbeatThresholdMs = getHeartbeatThresholdMs(snapshot);
    if (heartbeatIntervalMs > 0 && typeof snapshot.lastHeartbeatAt === "number" && nowAt - snapshot.lastHeartbeatAt > heartbeatThresholdMs) {
      issues.push(
        createRuntimeIssue({
          accountId: snapshot.accountId,
          message: "\u5FC3\u8DF3\u8D85\u8FC7\u9608\u503C\u672A\u66F4\u65B0\uFF0C\u53EF\u80FD\u5DF2\u4E0E ai-gateway \u65AD\u8FDE\u3002",
          fix: "\u68C0\u67E5 gateway \u8FDE\u63A5\u72B6\u6001\u4E0E heartbeatIntervalMs \u914D\u7F6E\uFF0C\u5FC5\u8981\u65F6\u91CD\u542F channel\u3002"
        })
      );
    }
    const latestActivityAt = Math.max(snapshot.lastInboundAt ?? 0, snapshot.lastOutboundAt ?? 0);
    const activityThresholdMs = Math.max(
      runTimeoutMs,
      heartbeatIntervalMs * 3
    );
    if (activityThresholdMs > 0 && latestActivityAt > 0 && nowAt - latestActivityAt > activityThresholdMs) {
      issues.push(
        createRuntimeIssue({
          accountId: snapshot.accountId,
          message: "\u6700\u8FD1\u6536\u53D1\u6D3B\u52A8\u8D85\u8FC7\u9608\u503C\u672A\u66F4\u65B0\uFF0Cbridge \u53EF\u80FD\u5DF2\u5361\u4F4F\u3002",
          fix: "\u68C0\u67E5 ai-gateway \u94FE\u8DEF\u4E0E runTimeoutMs \u914D\u7F6E\uFF0C\u5FC5\u8981\u65F6\u91CD\u542F channel\u3002"
        })
      );
    }
  }
  return issues;
}

// src/channel.ts
var activeBridges = /* @__PURE__ */ new Map();
var messageBridgeConfigSchema = {
  schema: {
    type: "object",
    additionalProperties: false,
    properties: {
      enabled: { type: "boolean" },
      name: { type: "string", minLength: 1 },
      gateway: {
        type: "object",
        additionalProperties: false,
        properties: {
          url: { type: "string", minLength: 1 },
          heartbeatIntervalMs: { type: "integer", minimum: 1 },
          reconnect: {
            type: "object",
            additionalProperties: false,
            properties: {
              baseMs: { type: "integer", minimum: 1 },
              maxMs: { type: "integer", minimum: 1 },
              exponential: { type: "boolean" }
            }
          }
        },
        required: ["url"]
      },
      auth: {
        type: "object",
        additionalProperties: false,
        properties: {
          ak: { type: "string", minLength: 1 },
          sk: { type: "string", minLength: 1 }
        },
        required: ["ak", "sk"]
      },
      agentIdPrefix: { type: "string", minLength: 1 },
      runTimeoutMs: { type: "integer", minimum: 1e3 }
    },
    required: ["gateway", "auth"]
  },
  uiHints: {
    "auth.ak": {
      label: "AK",
      sensitive: true
    },
    "auth.sk": {
      label: "SK",
      sensitive: true
    }
  }
};
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
  onboarding: messageBridgeOnboardingAdapter,
  reload: {
    configPrefixes: [`channels.${CHANNEL_ID}`]
  },
  configSchema: messageBridgeConfigSchema,
  config: {
    listAccountIds: (cfg) => listAccountIds(cfg),
    resolveAccount: (cfg, accountId) => resolveAccount(cfg, accountId),
    defaultAccountId: () => DEFAULT_ACCOUNT_ID,
    setAccountEnabled: ({ cfg, accountId, enabled }) => setMessageBridgeAccountEnabled({
      cfg,
      accountId,
      enabled
    }),
    deleteAccount: ({ cfg, accountId }) => deleteMessageBridgeAccount({
      cfg,
      accountId
    }),
    isEnabled: (account) => account.enabled,
    disabledReason: () => "disabled",
    isConfigured: (account, cfg) => isAccountConfigured(account, cfg),
    unconfiguredReason: (_account, cfg) => resolveUnconfiguredReason(cfg),
    describeAccount: (account, cfg) => describeAccount(account, cfg)
  },
  setup: {
    resolveAccountId: ({ accountId }) => resolveSupportedAccountId(accountId),
    applyAccountName: ({ cfg, accountId, name }) => applyAccountNameToChannelSection({
      cfg,
      channelKey: CHANNEL_ID,
      accountId,
      name
    }),
    validateInput: ({ cfg, accountId, input }) => validateMessageBridgeSetupInput({
      cfg,
      accountId,
      input
    }),
    applyAccountConfig: ({ cfg, accountId, input }) => applyMessageBridgeSetupConfig({
      cfg,
      accountId,
      input
    })
  },
  status: {
    defaultRuntime: createDefaultMessageBridgeRuntimeState(),
    buildChannelSummary: ({ snapshot }) => buildMessageBridgeChannelSummary(snapshot),
    probeAccount: async ({ account, timeoutMs }) => await probeMessageBridgeAccount({ account, timeoutMs }),
    buildAccountSnapshot: ({ account, cfg, runtime, probe }) => buildMessageBridgeAccountSnapshot({
      account,
      cfg,
      runtime,
      probe
    }),
    collectStatusIssues: (accounts) => collectMessageBridgeStatusIssues(accounts)
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
  CHANNEL_ADD_FIX,
  CHANNEL_ID,
  DEFAULT_ACCOUNT_CONFIG,
  DEFAULT_ACCOUNT_ID,
  LEGACY_ACCOUNTS_MIGRATION_FIX,
  MESSAGE_BRIDGE_TOOL_TYPE,
  OpenClawGatewayBridge,
  applyMessageBridgeSetupConfig,
  index_default as default,
  deleteMessageBridgeAccount,
  describeAccount,
  getMissingRequiredConfigPaths,
  hasLegacyAccountsConfig,
  isAccountConfigured,
  listAccountIds,
  messageBridgePlugin,
  normalizeDownstreamMessage,
  resolveAccount,
  resolveConfigSearchPaths,
  resolveNonDefaultAccountError,
  resolveRegisterMetadata,
  resolveSupportedAccountId,
  resolveTokenSource,
  resolveUnconfiguredReason,
  setMessageBridgeAccountEnabled,
  validateMessageBridgeSetupInput
};
