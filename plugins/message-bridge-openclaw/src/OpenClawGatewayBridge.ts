import { randomUUID } from "node:crypto";
import os from "node:os";
import {
  createReplyPrefixOptions,
  normalizeOutboundReplyPayload,
  type OpenClawConfig,
  type PluginRuntime,
} from "openclaw/plugin-sdk";
import type {
  DownstreamMessage,
  InvokeMessage,
} from "./contracts/downstream.js";
import type {
  SessionCreatedMessage,
  StatusResponseMessage,
  ToolDoneMessage,
  ToolErrorMessage,
  ToolEventMessage,
} from "./contracts/transport.js";
import { TOOL_ERROR_REASON } from "./contracts/transport.js";
import type { BridgeLogger, MessageBridgeResolvedAccount, MessageBridgeStatusSnapshot } from "./types.js";
import type { GatewayConnection } from "./connection/GatewayConnection.js";
import { DefaultAkSkAuth } from "./connection/AkSkAuth.js";
import { DefaultGatewayConnection } from "./connection/GatewayConnection.js";
import { normalizeDownstreamMessage } from "./protocol/downstream.js";
import { resolveRegisterMetadata, type RegisterMetadata } from "./runtime/RegisterMetadata.js";
import { SessionRegistry } from "./session/SessionRegistry.js";

export interface OpenClawGatewayBridgeOptions {
  account: MessageBridgeResolvedAccount;
  config: OpenClawConfig;
  logger: BridgeLogger;
  runtime: PluginRuntime;
  setStatus: (status: MessageBridgeStatusSnapshot) => void;
  registerMetadata?: RegisterMetadata;
  connectionFactory?: (account: MessageBridgeResolvedAccount, logger: BridgeLogger) => GatewayConnection;
}

type SubagentRuntime = PluginRuntime & {
  subagent: {
    run(params: {
      sessionKey: string;
      message: string;
      deliver: boolean;
      idempotencyKey: string;
    }): Promise<{ runId: string }>;
    waitForRun(params: { runId: string; timeoutMs: number }): Promise<{ status: string; error?: string }>;
    getSessionMessages(params: { sessionKey: string; limit: number }): Promise<{ messages: unknown[] }>;
    deleteSession(params: { sessionKey: string }): Promise<void>;
  };
};

type HostSessionRuntime = PluginRuntime & {
  channel?: PluginRuntime["channel"] & {
    session?: PluginRuntime["channel"]["session"] & {
      createSession?: (params?: { sessionId?: string; agentId?: string }) => Promise<{ sessionId: string }>;
      deleteSession?: (params: {
        sessionId?: string;
        sessionKey?: string;
        agentId?: string;
        deleteTranscript?: boolean;
      }) => Promise<void>;
      abortSession?: (params: {
        sessionId?: string;
        sessionKey?: string;
        agentId?: string;
        deleteTranscript?: boolean;
      }) => Promise<void>;
    };
  };
};

function isRecord(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === "object";
}

function asString(value: unknown): string | undefined {
  return typeof value === "string" && value.trim() ? value : undefined;
}

function logDebug(logger: BridgeLogger, message: string, meta?: Record<string, unknown>): void {
  if (logger.debug) {
    logger.debug(message, meta);
    return;
  }
  logger.info(message, meta);
}

interface DownstreamLogFields {
  messageType?: string;
  action?: string;
  welinkSessionId?: string;
  toolSessionId?: string;
  gatewayMessageId?: string;
}

interface UpstreamSendContext {
  gatewayMessageId?: string;
  action?: string;
  welinkSessionId?: string;
  toolSessionId?: string;
}

function extractDownstreamLogFields(raw: unknown): DownstreamLogFields {
  if (!isRecord(raw)) {
    return {};
  }
  const payload = isRecord(raw.payload) ? raw.payload : undefined;
  return {
    messageType: asString(raw.type),
    action: asString(raw.action),
    welinkSessionId: asString(raw.welinkSessionId),
    toolSessionId: asString(payload?.toolSessionId),
    gatewayMessageId: asString(raw.messageId),
  };
}

function getInvokeToolSessionId(message: InvokeMessage): string | undefined {
  if ("toolSessionId" in message.payload) {
    return message.payload.toolSessionId;
  }
  return undefined;
}

function extractAssistantText(messages: unknown[]): string {
  for (let index = messages.length - 1; index >= 0; index -= 1) {
    const message = messages[index];
    if (!isRecord(message) || message.role !== "assistant") {
      continue;
    }

    if (typeof message.content === "string") {
      return message.content;
    }

    if (Array.isArray(message.content)) {
      const chunks = message.content
        .map((part) => {
          if (!isRecord(part)) {
            return "";
          }
          if (part.type === "text" && typeof part.text === "string") {
            return part.text;
          }
          if (typeof part.content === "string") {
            return part.content;
          }
          return "";
        })
        .filter(Boolean);
      if (chunks.length > 0) {
        return chunks.join("");
      }
    }
  }

  return "";
}

function buildBusyEvent(sessionKey: string): Record<string, unknown> {
  return {
    type: "session.status",
    properties: {
      sessionID: sessionKey,
      status: {
        type: "busy",
      },
    },
  };
}

function buildIdleEvent(sessionKey: string): Record<string, unknown> {
  return {
    type: "session.idle",
    properties: {
      sessionID: sessionKey,
    },
  };
}

function buildSessionErrorEvent(sessionKey: string, error: string): Record<string, unknown> {
  return {
    type: "session.error",
    properties: {
      sessionID: sessionKey,
      error: {
        message: error,
      },
    },
  };
}

interface AssistantStreamState {
  messageId: string;
  partId: string;
  sessionKey: string;
  seeded: boolean;
  accumulatedText: string;
  chunkCount: number;
  firstChunkAt: number | null;
}

function createAssistantStreamState(sessionKey: string): AssistantStreamState {
  return {
    messageId: `msg_${randomUUID()}`,
    partId: `prt_${randomUUID()}`,
    sessionKey,
    seeded: false,
    accumulatedText: "",
    chunkCount: 0,
    firstChunkAt: null,
  };
}

interface ToolPartState {
  toolCallId: string;
  toolName: string;
  partId: string;
  messageId: string;
  sessionKey: string;
  status: "running" | "completed" | "error";
  output?: string;
  error?: string;
  title?: string;
}

interface ToolAgentEvent {
  runId?: string;
  sessionKey?: string;
  stream?: string;
  data?: unknown;
}

type ChatExecutionPath = "runtime_reply" | "subagent_fallback";
type RetryAttempt = 0 | 1;

interface SelectedModelState {
  provider: string | null;
  model: string | null;
  thinkLevel: string | null;
}

function createSelectedModelState(): SelectedModelState {
  return {
    provider: null,
    model: null,
    thinkLevel: null,
  };
}

function buildAssistantMessageUpdated(sessionKey: string, messageId: string): Record<string, unknown> {
  return {
    type: "message.updated",
    properties: {
      info: {
        id: messageId,
        sessionID: sessionKey,
        role: "assistant",
        time: {
          created: Date.now(),
        },
      },
    },
  };
}

function buildAssistantPartUpdated(
  sessionKey: string,
  messageId: string,
  partId: string,
  text: string,
  delta?: string,
): Record<string, unknown> {
  return {
    type: "message.part.updated",
    properties: {
      ...(delta !== undefined ? { delta } : {}),
      part: {
        id: partId,
        sessionID: sessionKey,
        messageID: messageId,
        type: "text",
        text,
      },
    },
  };
}

function buildAssistantPartDelta(
  sessionKey: string,
  messageId: string,
  partId: string,
  delta: string,
): Record<string, unknown> {
  return {
    type: "message.part.delta",
    properties: {
      sessionID: sessionKey,
      messageID: messageId,
      partID: partId,
      field: "text",
      delta,
    },
  };
}

function buildToolPartUpdated(
  state: ToolPartState,
): Record<string, unknown> {
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
          ...(state.output !== undefined ? { output: state.output } : {}),
          ...(state.error !== undefined ? { error: state.error } : {}),
          ...(state.title !== undefined ? { title: state.title } : {}),
        },
      },
    },
  };
}

function extractToolResultTitle(meta: unknown, toolName: string): string | undefined {
  if (!isRecord(meta)) {
    return undefined;
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

export class OpenClawGatewayBridge {
  private readonly sessionRegistry: SessionRegistry;
  private readonly connection: GatewayConnection;
  private readonly runtime: PluginRuntime;
  private readonly registerMetadata: RegisterMetadata;
  private readonly activeToolSessions = new Map<
    string,
    {
      toolSessionId: string;
      runId: string | null;
      assistantStream: AssistantStreamState;
      toolStates: Map<string, ToolPartState>;
      pendingToolResultTarget: string | null;
    }
  >();
  private readonly activeRunToSessionKey = new Map<string, string>();
  private readonly terminatedToolSessionIds = new Set<string>();
  private readonly terminatedSessionKeys = new Set<string>();
  private running = false;
  private status: MessageBridgeStatusSnapshot;
  private unsubscribeAgentEvents: (() => boolean) | null = null;

  constructor(private readonly options: OpenClawGatewayBridgeOptions) {
    this.runtime = options.runtime;
    this.registerMetadata = options.registerMetadata ?? resolveRegisterMetadata(options.logger);
    this.sessionRegistry = new SessionRegistry(`${options.account.agentIdPrefix}:${options.account.accountId}`);
    this.connection =
      options.connectionFactory?.(options.account, options.logger) ??
      new DefaultGatewayConnection({
        url: options.account.gateway.url,
        reconnectBaseMs: options.account.gateway.reconnect.baseMs,
        reconnectMaxMs: options.account.gateway.reconnect.maxMs,
        reconnectExponential: options.account.gateway.reconnect.exponential,
        heartbeatIntervalMs: options.account.gateway.heartbeatIntervalMs,
        debug: options.account.debug,
        authPayloadProvider: () =>
          new DefaultAkSkAuth(options.account.auth.ak, options.account.auth.sk).generateAuthPayload(),
        registerMessage: {
          type: "register",
          deviceName: this.registerMetadata.deviceName,
          macAddress: this.registerMetadata.macAddress,
          os: os.platform(),
          toolType: this.registerMetadata.toolType,
          toolVersion: this.registerMetadata.toolVersion,
        },
        logger: options.logger,
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
      lastProbeAt: null,
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
          error: error instanceof Error ? error.message : String(error),
        });
      });
    });
    this.connection.on("error", (error) => {
      this.status.lastError = error.message;
      this.options.setStatus({ ...this.status });
    });
  }

  async start(): Promise<void> {
    this.options.logger.info("runtime.start.requested", {
      accountId: this.options.account.accountId,
    });
    if (this.running) {
      this.options.logger.info("runtime.start.skipped_already_started", {
        accountId: this.options.account.accountId,
      });
      return;
    }
    this.running = true;
    this.status.running = true;
    this.status.lastStartAt = Date.now();
    this.options.setStatus({ ...this.status });
    if (!this.unsubscribeAgentEvents && this.runtime.events?.onAgentEvent) {
      this.unsubscribeAgentEvents = this.runtime.events.onAgentEvent((evt: ToolAgentEvent) => {
        this.handleRuntimeAgentEvent(evt);
      });
    }
    await this.connection.connect();
    this.options.logger.info("runtime.start.completed", {
      accountId: this.options.account.accountId,
    });
  }

  private getSubagentRuntime(): SubagentRuntime["subagent"] | null {
    return (this.runtime as Partial<SubagentRuntime>).subagent ?? null;
  }

  async stop(): Promise<void> {
    this.options.logger.info("runtime.stop.requested", {
      accountId: this.options.account.accountId,
    });
    if (!this.running) {
      this.options.logger.info("runtime.stop.skipped_not_running", {
        accountId: this.options.account.accountId,
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
      accountId: this.options.account.accountId,
    });
  }

  async handleDownstreamMessage(raw: unknown): Promise<void> {
    if (!this.connection.isConnected()) {
      this.options.logger.warn("runtime.downstream_ignored_no_connection");
      return;
    }
    const startedAt = Date.now();
    const fields = extractDownstreamLogFields(raw);
    logDebug(this.options.logger, "runtime.downstream.received", fields as Record<string, unknown>);
    const normalized = normalizeDownstreamMessage(raw, this.options.logger);
    if (!normalized.ok) {
      this.options.logger.warn("runtime.downstream_ignored_non_protocol", {
        ...fields,
        errorCode: normalized.error.code,
        stage: normalized.error.stage,
        field: normalized.error.field,
        errorMessage: normalized.error.message,
      });
      this.sendToolError({
        type: "tool_error",
        welinkSessionId: fields.welinkSessionId,
        toolSessionId: fields.toolSessionId,
        error: normalized.error.message,
      }, {
        gatewayMessageId: fields.gatewayMessageId,
        action: fields.action,
        welinkSessionId: fields.welinkSessionId,
        toolSessionId: fields.toolSessionId,
      });
      return;
    }

    if (normalized.value.type === "status_query") {
      this.options.logger.info("runtime.status_query.received", fields as Record<string, unknown>);
      const message: StatusResponseMessage = {
        type: "status_response",
        opencodeOnline: this.running,
      };
      this.connection.send(message, {
        gatewayMessageId: fields.gatewayMessageId,
        action: "status_query",
      });
      this.options.logger.info("runtime.status_query.responded", {
        ...fields,
        latencyMs: Date.now() - startedAt,
      });
      return;
    }

    this.options.logger.info("runtime.invoke.received", {
      ...fields,
      action: normalized.value.action,
      welinkSessionId: normalized.value.welinkSessionId,
      toolSessionId: getInvokeToolSessionId(normalized.value),
    });
    const invokeContext: UpstreamSendContext = {
      gatewayMessageId: fields.gatewayMessageId,
      action: normalized.value.action,
      welinkSessionId: normalized.value.welinkSessionId,
      toolSessionId: getInvokeToolSessionId(normalized.value),
    };
    const invokeResult = await this.handleInvoke(normalized.value, invokeContext);
    if (invokeResult.success) {
      this.options.logger.info("runtime.invoke.completed", {
        ...fields,
        action: normalized.value.action,
        welinkSessionId: normalized.value.welinkSessionId,
        toolSessionId: getInvokeToolSessionId(normalized.value),
        latencyMs: Date.now() - startedAt,
      });
      return;
    }
    this.options.logger.warn("runtime.invoke.failed", {
      ...fields,
      action: normalized.value.action,
      welinkSessionId: normalized.value.welinkSessionId,
      toolSessionId: getInvokeToolSessionId(normalized.value),
      latencyMs: Date.now() - startedAt,
      reason: invokeResult.reason,
    });
  }

  private async handleInvoke(
    message: InvokeMessage,
    context: UpstreamSendContext,
  ): Promise<{ success: boolean; reason?: string }> {
    switch (message.action) {
      case "chat":
        if (await this.handleChat(message, context)) {
          return { success: true };
        }
        return { success: false, reason: "chat_failed" };
      case "create_session":
        if (await this.handleCreateSession(message, context)) {
          return { success: true };
        }
        return { success: false, reason: "create_session_failed" };
      case "close_session":
        if (await this.handleCloseSession(message, context)) {
          return { success: true };
        }
        return { success: false, reason: "close_session_failed" };
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

  private async handleChat(
    message: Extract<InvokeMessage, { action: "chat" }>,
    context: UpstreamSendContext,
  ): Promise<boolean> {
    const record = this.sessionRegistry.ensure(message.payload.toolSessionId, message.welinkSessionId);
    this.clearSessionTermination(record);
    const assistantStream = createAssistantStreamState(record.sessionKey);
    const toolStates = new Map<string, ToolPartState>();
    const startedAt = Date.now();
    const chatRequestId = randomUUID();
    const configuredTimeoutMs = this.options.account.runTimeoutMs;
    const selectedModel = createSelectedModelState();
    const executionPath: ChatExecutionPath =
      this.runtime.channel?.routing && this.runtime.channel?.reply
        ? "runtime_reply"
        : "subagent_fallback";
    this.activeToolSessions.set(record.sessionKey, {
      toolSessionId: record.toolSessionId,
      runId: null,
      assistantStream,
      toolStates,
      pendingToolResultTarget: null,
    });
    this.sendToolEvent({
      type: "tool_event",
      toolSessionId: record.toolSessionId,
      event: buildBusyEvent(record.sessionKey),
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
      retryAttempt: 0,
    });
    let retryAttempt: RetryAttempt = 0;
    let lastErrorMessage: string | null = null;
    let lastErrorExtra: Record<string, unknown> | undefined;

    while (true) {
      if (!this.runtime.channel?.routing?.resolveAgentRoute || !this.runtime.channel?.reply) {
        const fallbackResult = await this.handleChatWithSubagentFallback(
          record,
          message.payload.text,
          startedAt,
          selectedModel,
          chatRequestId,
          retryAttempt,
          context,
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
            retryAttempt,
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
          id: record.welinkSessionId || record.toolSessionId,
        },
      });
      const envelopeOptions = this.runtime.channel.reply.resolveEnvelopeFormatOptions(this.options.config);
      const body = this.runtime.channel.reply.formatAgentEnvelope({
        channel: "message-bridge",
        from: `ai-gateway:${record.welinkSessionId || record.toolSessionId}`,
        timestamp: new Date(),
        previousTimestamp: undefined,
        envelope: envelopeOptions,
        body: message.payload.text,
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
        Timestamp: new Date().toISOString(),
        OriginatingChannel: "message-bridge",
        OriginatingTo: `message-bridge:${record.toolSessionId}`,
        CommandAuthorized: false,
      });
      const { onModelSelected, ...prefixOptions } = createReplyPrefixOptions({
        cfg: this.options.config,
        agentId: route.agentId,
        channel: "message-bridge",
        accountId: this.options.account.accountId,
      });
      const handleModelSelected = (selection: { provider: string; model: string; thinkLevel: string | undefined }) => {
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
          thinkLevel: selection.thinkLevel ?? null,
        });
        onModelSelected?.(selection);
      };
      const deliver = async (rawPayload: unknown, info: { kind: "tool" | "block" | "final" }) => {
        if (this.isSessionTerminated(record)) {
          return;
        }
        const payload =
          isRecord(rawPayload) ? normalizeOutboundReplyPayload(rawPayload) : normalizeOutboundReplyPayload({});

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
              deltaText: payload.text,
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
              deltaText: payload.text,
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
            },
          },
          replyOptions: {
            onAgentRunStart: (runId) => {
              this.trackSessionRunId(record.sessionKey, runId);
            },
            onModelSelected: handleModelSelected,
            timeoutOverrideSeconds: Math.ceil(configuredTimeoutMs / 1000),
          },
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
            retryAttempt,
          });
          continue;
        }
        lastErrorMessage = errorMessage;
        lastErrorExtra = undefined;
        break;
      }
      if (this.isSessionTerminated(record)) {
        this.clearActiveToolSession(record.sessionKey);
        return true;
      }
      const finalText = assistantStream.accumulatedText || "(empty response)";
      this.sendAssistantFinalResponse(
        record.toolSessionId,
        assistantStream,
        finalText,
        context,
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
        finalText,
      });
      this.sendToolEvent({
        type: "tool_event",
        toolSessionId: record.toolSessionId,
        event: buildIdleEvent(record.sessionKey),
      }, context);
      this.sendToolDone({
        type: "tool_done",
        toolSessionId: record.toolSessionId,
        welinkSessionId: context.welinkSessionId,
      }, context);
      this.clearActiveToolSession(record.sessionKey);
      return true;
    }

    if (this.isSessionTerminated(record)) {
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
      extra: lastErrorExtra,
    });
    this.sendToolEvent({
      type: "tool_event",
      toolSessionId: record.toolSessionId,
      event: buildSessionErrorEvent(record.sessionKey, finalErrorMessage),
    }, context);
    this.sendToolError({
      type: "tool_error",
      toolSessionId: record.toolSessionId,
      welinkSessionId: context.welinkSessionId,
      error: finalErrorMessage,
    }, context);
    return false;
  }

  private async handleChatWithSubagentFallback(
    record: { toolSessionId: string; welinkSessionId?: string; sessionKey: string },
    text: string,
    startedAt: number,
    selectedModel: SelectedModelState,
    chatRequestId: string,
    retryAttempt: RetryAttempt,
    context: UpstreamSendContext,
  ): Promise<{ ok: true } | { ok: false; errorMessage: string; extra?: Record<string, unknown> }> {
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
        idempotencyKey: `chat:${record.toolSessionId}:${chatRequestId}`,
      });

      const wait = await subagent.waitForRun({
        runId: run.runId,
        timeoutMs: configuredTimeoutMs,
      });

      if (this.isSessionTerminated(record)) {
        return { ok: true };
      }

      if (wait.status !== "ok") {
        const errorMessage = wait.error || `subagent_${wait.status}`;
        return {
          ok: false,
          errorMessage,
          extra: {
            waitStatus: wait.status,
            waitError: wait.error ?? null,
          },
        };
      }

      const session = await subagent.getSessionMessages({
        sessionKey: record.sessionKey,
        limit: 50,
      });
      if (this.isSessionTerminated(record)) {
        return { ok: true };
      }
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
          waitError: wait.error ?? null,
        },
      });
      this.sendToolEvent({
        type: "tool_event",
        toolSessionId: record.toolSessionId,
        event: buildIdleEvent(record.sessionKey),
      }, context);
      this.sendToolDone({
        type: "tool_done",
        toolSessionId: record.toolSessionId,
        welinkSessionId: context.welinkSessionId,
      }, context);
      this.clearActiveToolSession(record.sessionKey);
      return { ok: true };
    } catch (error) {
      const errorMessage = error instanceof Error ? error.message : String(error);
      return { ok: false, errorMessage };
    }
  }

  private shouldRetryBeforeFirstChunkTimeout(errorMessage: string, assistantStream: AssistantStreamState): boolean {
    const lowered = errorMessage.toLowerCase();
    const isTimeout = lowered.includes("timed out") || lowered.includes("timeout");
    return assistantStream.firstChunkAt === null && isTimeout;
  }

  private async createHostSession(requestedSessionId?: string): Promise<{ sessionId: string }> {
    const sessionRuntime = (this.runtime as HostSessionRuntime).channel?.session;
    if (!sessionRuntime?.createSession) {
      throw new Error("openclaw_runtime_missing_session_creator");
    }

    const created = await sessionRuntime.createSession({ sessionId: requestedSessionId });
    const sessionId = created.sessionId?.trim();
    if (!sessionId) {
      throw new Error("create_session returned without sessionId");
    }

    return { sessionId };
  }

  private async deleteHostSession(
    record: { toolSessionId: string; sessionKey: string },
    mode: "close" | "abort",
  ): Promise<void> {
    const sessionRuntime = (this.runtime as HostSessionRuntime).channel?.session;
    if (mode === "abort" && sessionRuntime?.abortSession) {
      await sessionRuntime.abortSession({
        sessionId: record.toolSessionId,
        sessionKey: record.sessionKey,
        deleteTranscript: true,
      });
      return;
    }

    if (sessionRuntime?.deleteSession) {
      await sessionRuntime.deleteSession({
        sessionId: record.toolSessionId,
        sessionKey: record.sessionKey,
        deleteTranscript: true,
      });
      return;
    }

    const subagent = this.getSubagentRuntime();
    if (subagent?.deleteSession) {
      await subagent.deleteSession({
        sessionKey: record.sessionKey,
      });
      return;
    }

    throw new Error("openclaw_runtime_missing_session_deleter");
  }

  private async handleCreateSession(
    message: Extract<InvokeMessage, { action: "create_session" }>,
    context: UpstreamSendContext,
  ): Promise<boolean> {
    try {
      const created = await this.createHostSession(message.payload.sessionId);
      const record = this.sessionRegistry.ensure(created.sessionId, message.welinkSessionId);
      const response: SessionCreatedMessage = {
        type: "session_created",
        welinkSessionId: message.welinkSessionId,
        toolSessionId: record.toolSessionId,
        session: {
          sessionId: created.sessionId,
        },
      };
      this.connection.send(response, {
        ...context,
        toolSessionId: record.toolSessionId,
        welinkSessionId: message.welinkSessionId,
      });
      return true;
    } catch (error) {
      const errorMessage = error instanceof Error ? error.message : String(error);
      this.sendToolError({
        type: "tool_error",
        toolSessionId: message.payload.sessionId,
        welinkSessionId: message.welinkSessionId,
        error: errorMessage,
      }, context);
      return false;
    }
  }

  private async handleCloseSession(
    message: Extract<InvokeMessage, { action: "close_session" }>,
    context: UpstreamSendContext,
  ): Promise<boolean> {
    const record = this.sessionRegistry.get(message.payload.toolSessionId);
    if (!record) {
      this.sendToolError({
        type: "tool_error",
        toolSessionId: message.payload.toolSessionId,
        welinkSessionId: message.welinkSessionId,
        error: "unknown_tool_session",
        reason: TOOL_ERROR_REASON.SESSION_NOT_FOUND,
      }, context);
      return false;
    }

    this.markSessionTerminated(record);
    try {
      await this.deleteHostSession(record, "close");
      this.clearActiveToolSession(record.sessionKey);
      this.sessionRegistry.delete(message.payload.toolSessionId);
      return true;
    } catch (error) {
      this.clearSessionTermination(record);
      const errorMessage = error instanceof Error ? error.message : String(error);
      this.sendToolError({
        type: "tool_error",
        toolSessionId: message.payload.toolSessionId,
        welinkSessionId: message.welinkSessionId,
        error: errorMessage,
      }, context);
      return false;
    }
  }

  private async handleAbortSession(
    message: Extract<InvokeMessage, { action: "abort_session" }>,
    context: UpstreamSendContext,
  ): Promise<boolean> {
    const record = this.sessionRegistry.get(message.payload.toolSessionId);
    if (!record) {
      this.sendToolError({
        type: "tool_error",
        toolSessionId: message.payload.toolSessionId,
        welinkSessionId: message.welinkSessionId,
        error: "unknown_tool_session",
        reason: TOOL_ERROR_REASON.SESSION_NOT_FOUND,
      }, context);
      return false;
    }

    this.markSessionTerminated(record);
    try {
      await this.deleteHostSession(record, "abort");
      this.clearActiveToolSession(record.sessionKey);
      this.sessionRegistry.delete(message.payload.toolSessionId);
      this.sendToolDone({
        type: "tool_done",
        toolSessionId: message.payload.toolSessionId,
        welinkSessionId: message.welinkSessionId,
      }, context);
      return true;
    } catch (error) {
      this.clearSessionTermination(record);
      const errorMessage = error instanceof Error ? error.message : String(error);
      this.sendToolError({
        type: "tool_error",
        toolSessionId: message.payload.toolSessionId,
        welinkSessionId: message.welinkSessionId,
        error: errorMessage,
      }, context);
      return false;
    }
  }

  private sendUnsupported(
    action: string,
    toolSessionId: string | undefined,
    welinkSessionId: string | undefined,
    context: UpstreamSendContext,
  ): void {
    this.sendToolError({
      type: "tool_error",
      toolSessionId,
      welinkSessionId,
      error: `unsupported_in_openclaw_v1:${action}`,
    }, context);
  }

  private buildSendContext(message: { toolSessionId?: string; welinkSessionId?: string }, context?: UpstreamSendContext): UpstreamSendContext {
    return {
      gatewayMessageId: context?.gatewayMessageId,
      action: context?.action,
      welinkSessionId: context?.welinkSessionId ?? message.welinkSessionId,
      toolSessionId: context?.toolSessionId ?? message.toolSessionId,
    };
  }

  private buildChatEventContext(toolSessionId: string): UpstreamSendContext {
    return {
      action: "chat",
      toolSessionId,
    };
  }

  private sendToolEvent(message: ToolEventMessage, context?: UpstreamSendContext): void {
    const sendContext = this.buildSendContext(message, context);
    logDebug(this.options.logger, "runtime.tool_event.sending", {
      gatewayMessageId: sendContext.gatewayMessageId,
      action: sendContext.action,
      welinkSessionId: sendContext.welinkSessionId,
      toolSessionId: sendContext.toolSessionId,
      eventType: isRecord(message.event) ? asString(message.event.type) : undefined,
    });
    this.connection.send(message, {
      ...sendContext,
      eventType: isRecord(message.event) ? asString(message.event.type) : undefined,
    });
  }

  private sendToolDone(message: ToolDoneMessage, context?: UpstreamSendContext): void {
    const sendContext = this.buildSendContext(message, context);
    this.options.logger.info("runtime.tool_done.sending", {
      gatewayMessageId: sendContext.gatewayMessageId,
      action: sendContext.action,
      welinkSessionId: sendContext.welinkSessionId,
      toolSessionId: sendContext.toolSessionId,
    });
    try {
      this.connection.send(message, sendContext);
    } catch {
      this.options.logger.warn("runtime.tool_done.skipped_no_connection", {
        gatewayMessageId: sendContext.gatewayMessageId,
        action: sendContext.action,
        welinkSessionId: sendContext.welinkSessionId,
        toolSessionId: sendContext.toolSessionId,
      });
    }
  }

  private sendToolError(message: ToolErrorMessage, context?: UpstreamSendContext): void {
    const sendContext = this.buildSendContext(message, context);
    this.options.logger.error("runtime.tool_error.sending", {
      gatewayMessageId: sendContext.gatewayMessageId,
      action: sendContext.action,
      welinkSessionId: sendContext.welinkSessionId,
      toolSessionId: sendContext.toolSessionId,
      error: message.error,
      reason: message.reason,
    });
    try {
      this.connection.send(message, sendContext);
    } catch {
      this.options.logger.warn("runtime.tool_error.skipped_no_connection", {
        gatewayMessageId: sendContext.gatewayMessageId,
        action: sendContext.action,
        welinkSessionId: sendContext.welinkSessionId,
        toolSessionId: sendContext.toolSessionId,
      });
    }
  }

  private sendAssistantStreamChunk(
    toolSessionId: string,
    state: AssistantStreamState,
    chunk: string,
    context: UpstreamSendContext,
  ): void {
    state.accumulatedText += chunk;

    if (state.accumulatedText === chunk) {
      this.ensureAssistantMessageStarted(toolSessionId, state, context);
      this.sendToolEvent({
        type: "tool_event",
        toolSessionId,
        event: buildAssistantPartUpdated(state.sessionKey, state.messageId, state.partId, chunk, chunk),
      }, context);
      return;
    }

    this.sendToolEvent({
      type: "tool_event",
      toolSessionId,
      event: buildAssistantPartDelta(state.sessionKey, state.messageId, state.partId, chunk),
    }, context);
  }

  private ensureAssistantMessageStarted(
    toolSessionId: string,
    state: AssistantStreamState,
    context: UpstreamSendContext,
  ): void {
    if (state.seeded) {
      return;
    }

    this.sendToolEvent({
      type: "tool_event",
      toolSessionId,
      event: buildAssistantMessageUpdated(state.sessionKey, state.messageId),
    }, context);
    state.seeded = true;
  }

  private sendAssistantFinalResponse(
    toolSessionId: string,
    state: AssistantStreamState,
    text: string,
    context: UpstreamSendContext,
  ): void {
    if (state.seeded) {
      if (state.accumulatedText.length === 0) {
        this.sendToolEvent({
          type: "tool_event",
          toolSessionId,
          event: buildAssistantPartUpdated(state.sessionKey, state.messageId, state.partId, text),
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
          state.accumulatedText || text,
        ),
      }, context);
      return;
    }

    this.sendToolEvent({
      type: "tool_event",
      toolSessionId,
      event: buildAssistantMessageUpdated(state.sessionKey, state.messageId),
    }, context);
    this.sendToolEvent({
      type: "tool_event",
      toolSessionId,
      event: buildAssistantPartUpdated(state.sessionKey, state.messageId, state.partId, text),
    }, context);
    state.seeded = true;
  }

  private emitToolPartUpdate(toolSessionId: string, toolState: ToolPartState, context: UpstreamSendContext): void {
    if (this.terminatedToolSessionIds.has(toolSessionId) || this.terminatedSessionKeys.has(toolState.sessionKey)) {
      return;
    }
    this.sendToolEvent({
      type: "tool_event",
      toolSessionId,
      event: buildToolPartUpdated(toolState),
    }, context);
  }

  private trackSessionRunId(sessionKey: string, runId: string): void {
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

  private clearActiveToolSession(sessionKey: string): void {
    const activeSession = this.activeToolSessions.get(sessionKey);
    if (activeSession?.runId) {
      this.activeRunToSessionKey.delete(activeSession.runId);
    }
    this.activeToolSessions.delete(sessionKey);
  }

  private isSessionTerminated(record: { toolSessionId: string; sessionKey: string }): boolean {
    return (
      this.terminatedToolSessionIds.has(record.toolSessionId) ||
      this.terminatedSessionKeys.has(record.sessionKey)
    );
  }

  private markSessionTerminated(record: { toolSessionId: string; sessionKey: string }): void {
    this.terminatedToolSessionIds.add(record.toolSessionId);
    this.terminatedSessionKeys.add(record.sessionKey);
  }

  private clearSessionTermination(record: { toolSessionId: string; sessionKey: string }): void {
    this.terminatedToolSessionIds.delete(record.toolSessionId);
    this.terminatedSessionKeys.delete(record.sessionKey);
  }

  private handleRuntimeAgentEvent(evt: ToolAgentEvent): void {
    if (evt.stream !== "tool" || !isRecord(evt.data)) {
      return;
    }

    const directSessionKey = typeof evt.sessionKey === "string" ? evt.sessionKey : undefined;
    const mappedSessionKey =
      typeof evt.runId === "string" && evt.runId.length > 0 ? this.activeRunToSessionKey.get(evt.runId) : undefined;
    const sessionKey = directSessionKey ?? mappedSessionKey;
    const activeSession =
      (directSessionKey ? this.activeToolSessions.get(directSessionKey) : undefined) ??
      (mappedSessionKey ? this.activeToolSessions.get(mappedSessionKey) : undefined);
    if (!sessionKey || !activeSession) {
      return;
    }
    if (
      this.terminatedSessionKeys.has(sessionKey) ||
      this.terminatedToolSessionIds.has(activeSession.toolSessionId)
    ) {
      return;
    }

    const phase = typeof evt.data.phase === "string" ? evt.data.phase : "";
    const toolName = typeof evt.data.name === "string" && evt.data.name.length > 0 ? evt.data.name : "tool";
    const toolCallId =
      typeof evt.data.toolCallId === "string" && evt.data.toolCallId.length > 0
        ? evt.data.toolCallId
        : `tool_${randomUUID()}`;

    const context = this.buildChatEventContext(activeSession.toolSessionId);
    this.ensureAssistantMessageStarted(activeSession.toolSessionId, activeSession.assistantStream, context);

    let toolState = activeSession.toolStates.get(toolCallId);
    if (!toolState) {
      const nextToolState: ToolPartState = {
        toolCallId,
        toolName,
        partId: `tool_${randomUUID()}`,
        messageId: activeSession.assistantStream.messageId,
        sessionKey,
        status: "running",
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
      toolState.error = isError ? `tool_${toolName}_failed` : undefined;
      activeSession.pendingToolResultTarget = toolCallId;
      this.emitToolPartUpdate(activeSession.toolSessionId, toolState, context);
    }
  }

  private logChatStarted(params: {
    toolSessionId: string;
    welinkSessionId?: string;
    sessionKey: string;
    chatText: string;
    textLength: number;
    startedAt: number;
    configuredTimeoutMs: number;
    executionPath: ChatExecutionPath;
    chatRequestId: string;
    retryAttempt: RetryAttempt;
  }): void {
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
      retryAttempt: params.retryAttempt,
    });
  }

  private logChatCompleted(params: {
    toolSessionId: string;
    sessionKey: string;
    configuredTimeoutMs: number;
    startedAt: number;
    assistantStream: AssistantStreamState;
    selectedModel: SelectedModelState;
    executionPath: ChatExecutionPath;
    chatRequestId: string;
    retryAttempt: RetryAttempt;
    responseLength: number;
    finalText: string;
    extra?: Record<string, unknown>;
  }): void {
    this.options.logger.info("bridge.chat.completed", {
      ...this.buildChatDiagnostics(params),
      responseLength: params.responseLength,
      finalText: params.finalText,
      ...(params.extra ?? {}),
    });
  }

  private logChatFailed(params: {
    toolSessionId: string;
    sessionKey: string;
    configuredTimeoutMs: number;
    startedAt: number;
    assistantStream: AssistantStreamState;
    selectedModel: SelectedModelState;
    executionPath: ChatExecutionPath;
    chatRequestId: string;
    retryAttempt: RetryAttempt;
    error: string;
    extra?: Record<string, unknown>;
  }): void {
    const errorLower = params.error.toLowerCase();
    const isTimeout = errorLower.includes("timed out") || errorLower.includes("timeout");
    this.options.logger.warn("bridge.chat.failed", {
      ...this.buildChatDiagnostics(params),
      error: params.error,
      failureStage: params.assistantStream.firstChunkAt === null ? "before_first_chunk" : "after_first_chunk",
      errorCategory: isTimeout ? "timeout" : "runtime_error",
      timedOut: isTimeout,
      ...(params.extra ?? {}),
    });
  }

  private buildChatDiagnostics(params: {
    toolSessionId: string;
    sessionKey: string;
    configuredTimeoutMs: number;
    startedAt: number;
    assistantStream: AssistantStreamState;
    selectedModel: SelectedModelState;
    executionPath: ChatExecutionPath;
    chatRequestId: string;
    retryAttempt: RetryAttempt;
  }): Record<string, unknown> {
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
      firstChunkLatencyMs:
        params.assistantStream.firstChunkAt === null ? null : params.assistantStream.firstChunkAt - params.startedAt,
      totalLatencyMs: Date.now() - params.startedAt,
    };
  }
}
