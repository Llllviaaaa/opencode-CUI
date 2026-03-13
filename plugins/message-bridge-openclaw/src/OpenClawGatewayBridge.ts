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
import type { BridgeLogger, MessageBridgeResolvedAccount, MessageBridgeStatusSnapshot } from "./types.js";
import type { GatewayConnection } from "./connection/GatewayConnection.js";
import { DefaultAkSkAuth } from "./connection/AkSkAuth.js";
import { DefaultGatewayConnection } from "./connection/GatewayConnection.js";
import { normalizeDownstreamMessage } from "./protocol/downstream.js";
import { SessionRegistry } from "./session/SessionRegistry.js";

export interface OpenClawGatewayBridgeOptions {
  account: MessageBridgeResolvedAccount;
  config: OpenClawConfig;
  logger: BridgeLogger;
  runtime: PluginRuntime;
  setStatus: (status: MessageBridgeStatusSnapshot) => void;
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

function isRecord(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === "object";
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
  private readonly activeToolSessions = new Map<
    string,
    {
      toolSessionId: string;
      assistantStream: AssistantStreamState;
      toolStates: Map<string, ToolPartState>;
      pendingToolResultTarget: string | null;
    }
  >();
  private running = false;
  private status: MessageBridgeStatusSnapshot;
  private unsubscribeAgentEvents: (() => boolean) | null = null;

  constructor(private readonly options: OpenClawGatewayBridgeOptions) {
    this.runtime = options.runtime;
    this.sessionRegistry = new SessionRegistry(`${options.account.agentIdPrefix}:${options.account.accountId}`);
    this.connection =
      options.connectionFactory?.(options.account, options.logger) ??
      new DefaultGatewayConnection({
        url: options.account.gateway.url,
        reconnectBaseMs: options.account.gateway.reconnect.baseMs,
        reconnectMaxMs: options.account.gateway.reconnect.maxMs,
        reconnectExponential: options.account.gateway.reconnect.exponential,
        heartbeatIntervalMs: options.account.gateway.heartbeatIntervalMs,
        authPayloadProvider: () =>
          new DefaultAkSkAuth(options.account.auth.ak, options.account.auth.sk).generateAuthPayload(),
        registerMessage: {
          type: "register",
          deviceName: options.account.gateway.deviceName,
          macAddress: options.account.gateway.macAddress || "unknown",
          os: os.platform(),
          toolType: options.account.gateway.toolType,
          toolVersion: options.account.gateway.toolVersion,
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
    };

    this.connection.on("stateChange", (state) => {
      this.status.connected = state === "CONNECTED" || state === "READY";
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
    if (this.running) {
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
  }

  private getSubagentRuntime(): SubagentRuntime["subagent"] | null {
    return (this.runtime as Partial<SubagentRuntime>).subagent ?? null;
  }

  async stop(): Promise<void> {
    if (!this.running) {
      return;
    }
    this.running = false;
    this.connection.disconnect();
    this.unsubscribeAgentEvents?.();
    this.unsubscribeAgentEvents = null;
    this.activeToolSessions.clear();
    this.status.running = false;
    this.status.connected = false;
    this.status.lastStopAt = Date.now();
    this.options.setStatus({ ...this.status });
  }

  async handleDownstreamMessage(raw: unknown): Promise<void> {
    const normalized = normalizeDownstreamMessage(raw);
    if (!normalized.ok) {
      this.sendToolError({
        type: "tool_error",
        error: normalized.error.message,
      });
      return;
    }

    if (normalized.value.type === "status_query") {
      const message: StatusResponseMessage = {
        type: "status_response",
        opencodeOnline: this.running,
      };
      this.connection.send(message);
      return;
    }

    await this.handleInvoke(normalized.value);
  }

  private async handleInvoke(message: InvokeMessage): Promise<void> {
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

  private async handleChat(message: Extract<InvokeMessage, { action: "chat" }>): Promise<void> {
    const record = this.sessionRegistry.ensure(message.payload.toolSessionId, message.welinkSessionId);
    const assistantStream = createAssistantStreamState(record.sessionKey);
    const toolStates = new Map<string, ToolPartState>();
    const startedAt = Date.now();
    this.activeToolSessions.set(record.sessionKey, {
      toolSessionId: record.toolSessionId,
      assistantStream,
      toolStates,
      pendingToolResultTarget: null,
    });
    this.sendToolEvent({
      type: "tool_event",
      toolSessionId: record.toolSessionId,
      event: buildBusyEvent(record.sessionKey),
    });
    this.options.logger.info("bridge.chat.started", {
      toolSessionId: record.toolSessionId,
      welinkSessionId: record.welinkSessionId,
      sessionKey: record.sessionKey,
      textLength: message.payload.text.length,
      startedAt,
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
    const deliver = async (rawPayload: unknown, info: { kind: "tool" | "block" | "final" }) => {
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
        this.emitToolPartUpdate(record.toolSessionId, toolState);
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
            latencyMs: now - startedAt,
            chunkLength: payload.text.length,
          });
        } else {
          this.options.logger.info("bridge.chat.chunk", {
            toolSessionId: record.toolSessionId,
            sessionKey: record.sessionKey,
            chunkIndex: assistantStream.chunkCount,
            chunkLength: payload.text.length,
            sinceStartMs: now - startedAt,
            sinceFirstChunkMs: now - assistantStream.firstChunkAt,
          });
        }
        this.sendAssistantStreamChunk(record.toolSessionId, assistantStream, payload.text);
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
          onModelSelected,
          timeoutOverrideSeconds: Math.ceil(this.options.account.runTimeoutMs / 1000),
        },
      });
    } catch (error) {
      this.activeToolSessions.delete(record.sessionKey);
      const errorMessage = error instanceof Error ? error.message : String(error);
      this.sendToolEvent({
        type: "tool_event",
        toolSessionId: record.toolSessionId,
        event: buildSessionErrorEvent(record.sessionKey, errorMessage),
      });
      this.sendToolError({
        type: "tool_error",
        toolSessionId: record.toolSessionId,
        welinkSessionId: record.welinkSessionId,
        error: errorMessage,
      });
      return;
    }

    this.sendAssistantFinalResponse(
      record.toolSessionId,
      assistantStream,
      assistantStream.accumulatedText || "(empty response)",
    );
    this.options.logger.info("bridge.chat.completed", {
      toolSessionId: record.toolSessionId,
      sessionKey: record.sessionKey,
      totalLatencyMs: Date.now() - startedAt,
      firstChunkLatencyMs: assistantStream.firstChunkAt === null ? null : assistantStream.firstChunkAt - startedAt,
      chunkCount: assistantStream.chunkCount,
      responseLength: assistantStream.accumulatedText.length,
    });
    this.sendToolEvent({
      type: "tool_event",
      toolSessionId: record.toolSessionId,
      event: buildIdleEvent(record.sessionKey),
    });
    this.sendToolDone({
      type: "tool_done",
      toolSessionId: record.toolSessionId,
      welinkSessionId: record.welinkSessionId,
    });
    this.activeToolSessions.delete(record.sessionKey);
  }

  private async handleChatWithSubagentFallback(
    record: { toolSessionId: string; welinkSessionId?: string; sessionKey: string },
    text: string,
  ): Promise<void> {
    const assistantStream = createAssistantStreamState(record.sessionKey);
    const subagent = this.getSubagentRuntime();
    if (!subagent) {
      this.sendToolEvent({
        type: "tool_event",
        toolSessionId: record.toolSessionId,
        event: buildSessionErrorEvent(record.sessionKey, "openclaw_runtime_missing_reply_executor"),
      });
      this.sendToolError({
        type: "tool_error",
        toolSessionId: record.toolSessionId,
        welinkSessionId: record.welinkSessionId,
        error: "openclaw_runtime_missing_reply_executor",
      });
      return;
    }

    const run = await subagent.run({
      sessionKey: record.sessionKey,
      message: text,
      deliver: false,
      idempotencyKey: `${record.toolSessionId}:${text}`,
    });

    const wait = await subagent.waitForRun({
      runId: run.runId,
      timeoutMs: this.options.account.runTimeoutMs,
    });

    if (wait.status !== "ok") {
      const errorMessage = wait.error || `subagent_${wait.status}`;
      this.sendToolEvent({
        type: "tool_event",
        toolSessionId: record.toolSessionId,
        event: buildSessionErrorEvent(record.sessionKey, errorMessage),
      });
      this.sendToolError({
        type: "tool_error",
        toolSessionId: record.toolSessionId,
        welinkSessionId: record.welinkSessionId,
        error: errorMessage,
      });
      return;
    }

    const session = await subagent.getSessionMessages({
      sessionKey: record.sessionKey,
      limit: 50,
    });
    const assistantText = extractAssistantText(session.messages) || "(empty response)";
    this.sendAssistantFinalResponse(record.toolSessionId, assistantStream, assistantText);
    this.options.logger.info("bridge.chat.completed_fallback", {
      toolSessionId: record.toolSessionId,
      sessionKey: record.sessionKey,
      chunkCount: assistantStream.chunkCount,
      responseLength: assistantText.length,
    });
    this.sendToolEvent({
      type: "tool_event",
      toolSessionId: record.toolSessionId,
      event: buildIdleEvent(record.sessionKey),
    });
    this.sendToolDone({
      type: "tool_done",
      toolSessionId: record.toolSessionId,
      welinkSessionId: record.welinkSessionId,
    });
  }

  private handleCreateSession(message: Extract<InvokeMessage, { action: "create_session" }>): void {
    const record = this.sessionRegistry.create(message.welinkSessionId, message.payload.sessionId);
    const response: SessionCreatedMessage = {
      type: "session_created",
      welinkSessionId: message.welinkSessionId,
      toolSessionId: record.toolSessionId,
      session: {
        sessionId: record.sessionKey,
      },
    };
    this.connection.send(response);
  }

  private async handleCloseSession(message: Extract<InvokeMessage, { action: "close_session" }>): Promise<void> {
    const record = this.sessionRegistry.delete(message.payload.toolSessionId);
    if (record) {
      await this.getSubagentRuntime()?.deleteSession({
        sessionKey: record.sessionKey,
      });
    }
    this.sendToolDone({
      type: "tool_done",
      toolSessionId: message.payload.toolSessionId,
      welinkSessionId: message.welinkSessionId,
    });
  }

  private async handleAbortSession(message: Extract<InvokeMessage, { action: "abort_session" }>): Promise<void> {
    const record = this.sessionRegistry.get(message.payload.toolSessionId);
    if (!record) {
      this.sendToolError({
        type: "tool_error",
        toolSessionId: message.payload.toolSessionId,
        welinkSessionId: message.welinkSessionId,
        error: "unknown_tool_session",
      });
      return;
    }

    await this.getSubagentRuntime()?.deleteSession({
      sessionKey: record.sessionKey,
    });
    this.sessionRegistry.delete(message.payload.toolSessionId);
    this.sendToolDone({
      type: "tool_done",
      toolSessionId: message.payload.toolSessionId,
      welinkSessionId: message.welinkSessionId,
    });
  }

  private sendUnsupported(action: string, toolSessionId?: string, welinkSessionId?: string): void {
    this.sendToolError({
      type: "tool_error",
      toolSessionId,
      welinkSessionId,
      error: `unsupported_in_openclaw_v1:${action}`,
    });
  }

  private sendToolEvent(message: ToolEventMessage): void {
    this.connection.send(message);
  }

  private sendToolDone(message: ToolDoneMessage): void {
    this.connection.send(message);
  }

  private sendToolError(message: ToolErrorMessage): void {
    this.connection.send(message);
  }

  private sendAssistantStreamChunk(
    toolSessionId: string,
    state: AssistantStreamState,
    chunk: string,
  ): void {
    state.accumulatedText += chunk;

    if (state.accumulatedText === chunk) {
      this.ensureAssistantMessageStarted(toolSessionId, state);
      this.sendToolEvent({
        type: "tool_event",
        toolSessionId,
        event: buildAssistantPartUpdated(state.sessionKey, state.messageId, state.partId, chunk, chunk),
      });
      return;
    }

    this.sendToolEvent({
      type: "tool_event",
      toolSessionId,
      event: buildAssistantPartDelta(state.sessionKey, state.messageId, state.partId, chunk),
    });
  }

  private ensureAssistantMessageStarted(toolSessionId: string, state: AssistantStreamState): void {
    if (state.seeded) {
      return;
    }

    this.sendToolEvent({
      type: "tool_event",
      toolSessionId,
      event: buildAssistantMessageUpdated(state.sessionKey, state.messageId),
    });
    state.seeded = true;
  }

  private sendAssistantFinalResponse(
    toolSessionId: string,
    state: AssistantStreamState,
    text: string,
  ): void {
    if (state.seeded) {
      if (state.accumulatedText.length === 0) {
        this.sendToolEvent({
          type: "tool_event",
          toolSessionId,
          event: buildAssistantPartUpdated(state.sessionKey, state.messageId, state.partId, text),
        });
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
      });
      return;
    }

    this.sendToolEvent({
      type: "tool_event",
      toolSessionId,
      event: buildAssistantMessageUpdated(state.sessionKey, state.messageId),
    });
    this.sendToolEvent({
      type: "tool_event",
      toolSessionId,
      event: buildAssistantPartUpdated(state.sessionKey, state.messageId, state.partId, text),
    });
    state.seeded = true;
  }

  private emitToolPartUpdate(toolSessionId: string, toolState: ToolPartState): void {
    this.sendToolEvent({
      type: "tool_event",
      toolSessionId,
      event: buildToolPartUpdated(toolState),
    });
  }

  private handleRuntimeAgentEvent(evt: ToolAgentEvent): void {
    if (evt.stream !== "tool" || !isRecord(evt.data)) {
      return;
    }

    const sessionKey = typeof evt.sessionKey === "string" ? evt.sessionKey : undefined;
    if (!sessionKey) {
      return;
    }

    const activeSession = this.activeToolSessions.get(sessionKey);
    if (!activeSession) {
      return;
    }

    const phase = typeof evt.data.phase === "string" ? evt.data.phase : "";
    const toolName = typeof evt.data.name === "string" && evt.data.name.length > 0 ? evt.data.name : "tool";
    const toolCallId =
      typeof evt.data.toolCallId === "string" && evt.data.toolCallId.length > 0
        ? evt.data.toolCallId
        : `tool_${randomUUID()}`;

    this.ensureAssistantMessageStarted(activeSession.toolSessionId, activeSession.assistantStream);

    let toolState = activeSession.toolStates.get(toolCallId);
    if (!toolState) {
      toolState = {
        toolCallId,
        toolName,
        partId: `tool_${randomUUID()}`,
        messageId: activeSession.assistantStream.messageId,
        sessionKey,
        status: "running",
      };
      activeSession.toolStates.set(toolCallId, toolState);
    }

    toolState.toolName = toolName;
    toolState.title = extractToolResultTitle(evt.data.meta, toolName) ?? toolState.title;

    if (phase === "start" || phase === "update") {
      toolState.status = "running";
      this.emitToolPartUpdate(activeSession.toolSessionId, toolState);
      return;
    }

    if (phase === "result") {
      const isError = evt.data.isError === true;
      toolState.status = isError ? "error" : "completed";
      toolState.error = isError ? `tool_${toolName}_failed` : undefined;
      activeSession.pendingToolResultTarget = toolCallId;
      this.emitToolPartUpdate(activeSession.toolSessionId, toolState);
    }
  }
}
