import os from 'os';
import {
  Action,
  AbortSessionInvokeMessage,
  ActionResult,
  ChatInvokeMessage,
  CloseSessionInvokeMessage,
  CreateSessionInvokeMessage,
  DownstreamMessage,
  PERMISSION_REPLY_RESPONSES,
  PermissionReplyInvokeMessage,
  QuestionReplyInvokeMessage,
  StatusQueryMessage,
  buildMessageId,
} from '../types';
import { AbortSessionAction } from '../action/AbortSessionAction';
import { ChatAction } from '../action/ChatAction';
import { CreateSessionAction } from '../action/CreateSessionAction';
import { CloseSessionAction } from '../action/CloseSessionAction';
import { PermissionReplyAction } from '../action/PermissionReplyAction';
import { QuestionReplyAction } from '../action/QuestionReplyAction';
import { StatusQueryAction } from '../action/StatusQueryAction';
import { DefaultActionRouter } from '../action/ActionRouter';
import { DefaultActionRegistry } from '../action/ActionRegistry';
import { loadConfig } from '../config';
import { DefaultAkSkAuth } from '../connection/AkSkAuth';
import {
  DefaultGatewayConnection,
  GatewayConnection,
} from '../connection/GatewayConnection';
import { DefaultStateManager } from '../connection/StateManager';
import { EventFilter } from '../event/EventFilter';
import { BridgeEvent } from './types';
import { createSdkAdapter } from './SdkAdapter';
import { AppLogger, type BridgeLogger } from './AppLogger';
import { getErrorDetailsForLog, getErrorMessage } from '../utils/error';

export interface BridgeRuntimeOptions {
  workspacePath?: string;
  client: unknown;
  debug?: boolean;
}

export interface BridgeRuntimeStartOptions {
  abortSignal?: AbortSignal;
}

interface EventLogFields extends Record<string, unknown> {
  eventType: string;
  toolSessionId?: string;
  opencodeMessageId?: string;
  opencodePartId?: string;
  partType?: string;
  toolCallId?: string;
  deltaField?: string;
  deltaBytes?: number;
  diffCount?: number;
}

interface DownstreamLogFields extends Record<string, unknown> {
  messageType?: string;
  gatewayMessageId?: string;
  action?: string;
  welinkSessionId?: string;
  toolSessionId?: string;
}

interface ToolErrorLogOptions {
  traceId?: string;
  gatewayMessageId?: string;
  action?: string;
  welinkSessionId?: string;
  toolSessionId?: string;
  opencodeMessageId?: string;
  logger?: BridgeLogger;
}

export class BridgeRuntime {
  private readonly actionRouter = new DefaultActionRouter();
  private readonly stateManager = new DefaultStateManager();
  private readonly registry = new DefaultActionRegistry();

  private gatewayConnection: GatewayConnection | null = null;
  private eventFilter: EventFilter | null = null;
  private started = false;
  private readonly sdkClient: unknown;
  private logger: BridgeLogger;

  constructor(private readonly options: BridgeRuntimeOptions) {
    this.logger = new AppLogger(options.client, { component: 'runtime' }, undefined, undefined, options.debug);
    this.sdkClient = createSdkAdapter(options.client);
    this.registerActions();
    this.actionRouter.setRegistry(this.registry);
  }

  async start(options: BridgeRuntimeStartOptions = {}): Promise<void> {
    this.logger.info('runtime.start.requested', { workspacePath: this.options.workspacePath });
    if (this.started) {
      this.logger.debug('runtime.start.skipped_already_started');
      return;
    }

    if (options.abortSignal?.aborted) {
      this.logger.warn('runtime.start.aborted_precheck');
      throw new Error('runtime_start_aborted');
    }

    let config;
    try {
      this.logger.info('runtime.config.loading', { workspacePath: this.options.workspacePath });
      config = await loadConfig(this.options.workspacePath, this.logger);
      if (this.options.debug === undefined && typeof config.debug === 'boolean') {
        this.logger = new AppLogger(
          this.options.client,
          { component: 'runtime' },
          this.logger.getTraceId(),
          undefined,
          config.debug,
        );
      }
      this.logger.info('runtime.config.loaded_successfully', {
        config_version: config.config_version,
        enabled: config.enabled,
        gateway_url: config.gateway.url,
      });
    } catch (error) {
      const errorMessage = getErrorMessage(error);
      this.logger.error('runtime.config.loading_failed', {
        error: errorMessage,
        workspacePath: this.options.workspacePath,
        ...getErrorDetailsForLog(error),
      });
      throw error;
    }
    if (!config.enabled) {
      this.logger.info('runtime.start.disabled_by_config');
      this.started = true;
      return;
    }

    const agentId = this.stateManager.generateAndBindAgentId();
    this.eventFilter = new EventFilter(config.events.allowlist);

    const auth = new DefaultAkSkAuth(config.auth.ak, config.auth.sk);
    const queryParamsProvider = () => auth.generateQueryParams();

    const connection = new DefaultGatewayConnection({
      url: config.gateway.url,
      reconnectBaseMs: config.gateway.reconnect.baseMs,
      reconnectMaxMs: config.gateway.reconnect.maxMs,
      reconnectExponential: config.gateway.reconnect.exponential,
      heartbeatIntervalMs: config.gateway.heartbeatIntervalMs,
      pongTimeoutMs: config.gateway.ping?.pongTimeoutMs,
      abortSignal: options.abortSignal,
      queryParamsProvider,
      registerMessage: {
        type: 'register',
        deviceName: config.gateway.deviceName,
        os: os.platform(),
        toolType: config.gateway.toolType,
        toolVersion: config.gateway.toolVersion,
      },
      logger: this.logger.child({ component: 'gateway' }),
    });

    connection.on('stateChange', (state) => {
      this.stateManager.setState(state);
      this.logger.info('gateway.state.changed', { state });
      if (state === 'CONNECTING') {
        const nextAgentId = this.stateManager.resetForReconnect();
        this.logger.info('runtime.agent.rebound', { agentId: nextAgentId });
      }
    });

    connection.on('message', (message) => {
      const downstreamFields = this.extractDownstreamLogFields(message);
      const traceId = downstreamFields.gatewayMessageId ?? this.logger.getTraceId();
      const messageLogger = this.createMessageLogger(downstreamFields, traceId);
      messageLogger.debug('gateway.message.received');
      this.handleDownstreamMessage(message, messageLogger, traceId, downstreamFields).catch((error) => {
        messageLogger.error('runtime.downstream_message_error', {
          error: getErrorMessage(error),
          ...getErrorDetailsForLog(error),
        });
      });
    });

    this.gatewayConnection = connection;
    if (options.abortSignal?.aborted) {
      this.gatewayConnection.disconnect();
      this.gatewayConnection = null;
      this.logger.warn('runtime.start.aborted_before_connect');
      throw new Error('runtime_start_aborted');
    }

    await connection.connect();
    if (options.abortSignal?.aborted) {
      this.gatewayConnection.disconnect();
      this.gatewayConnection = null;
      this.logger.warn('runtime.start.aborted_after_connect');
      throw new Error('runtime_start_aborted');
    }

    this.started = true;
    this.logger.info('runtime.start.completed', { agentId: this.stateManager.getAgentId() });
  }

  stop(): void {
    this.logger.info('runtime.stop.requested');
    if (this.gatewayConnection) {
      this.gatewayConnection.disconnect();
      this.gatewayConnection = null;
    }

    this.started = false;
    this.logger.info('runtime.stop.completed');
  }

  async handleEvent(event: BridgeEvent): Promise<void> {
    const eventFields = this.extractEventLogFields(event);
    const eventTraceId = eventFields.opencodeMessageId ?? this.logger.getTraceId();
    const eventLogger = this.createMessageLogger(eventFields, eventTraceId);
    eventLogger.debug('event.received');
    if (!this.stateManager.isReady() || !this.gatewayConnection || !this.eventFilter) {
      eventLogger.debug('event.ignored_not_ready', {
        state: this.stateManager.getState(),
      });
      return;
    }

    if (!this.eventFilter.isAllowed(event.type)) {
      eventLogger.warn('event.rejected_allowlist');
      return;
    }

    const toolSessionId = eventFields.toolSessionId;
    const bridgeMessageId = buildMessageId();
    const forwardingLogger = this.createMessageLogger(
      {
        ...eventFields,
        toolSessionId,
      },
      bridgeMessageId,
    );

    forwardingLogger.info('event.forwarding');
    this.gatewayConnection.send(
      {
        type: 'tool_event',
        toolSessionId,
        event,
      },
      {
        traceId: bridgeMessageId,
        runtimeTraceId: this.logger.getTraceId(),
        gatewayMessageId: bridgeMessageId,
        toolSessionId,
        eventType: event.type,
        opencodeMessageId: eventFields.opencodeMessageId,
        opencodePartId: eventFields.opencodePartId,
        toolCallId: eventFields.toolCallId,
      },
    );
    forwardingLogger.debug('event.forwarded');
  }

  getStarted(): boolean {
    return this.started;
  }

  private registerActions(): void {
    const actions: Action[] = [
      new ChatAction(),
      new CreateSessionAction(),
      new AbortSessionAction(),
      new CloseSessionAction(),
      new PermissionReplyAction(),
      new QuestionReplyAction(),
      new StatusQueryAction(),
    ];

    for (const action of actions) {
      this.registry.register(action);
    }
  }

  private async handleDownstreamMessage(
    raw: unknown,
    messageLogger: BridgeLogger = this.logger,
    traceId: string = this.logger.getTraceId(),
    baseFields: DownstreamLogFields = {},
  ): Promise<void> {
    if (!this.gatewayConnection) {
      messageLogger.warn('runtime.downstream_ignored_no_connection');
      return;
    }
    const startedAt = Date.now();
    const message = this.normalizeDownstreamMessage(raw);
    if (!message) {
      const rawType = this.extractRawDownstreamType(raw);
      const fallbackSessionId =
        raw && typeof raw === 'object' && typeof (raw as { welinkSessionId?: unknown }).welinkSessionId === 'string'
          ? (raw as { welinkSessionId: string }).welinkSessionId
          : undefined;

      messageLogger.warn('runtime.downstream_ignored_non_protocol', {
        messageType: rawType ?? 'unknown',
        hasWelinkSessionId: !!fallbackSessionId,
      });

      if (rawType === 'invoke') {
        this.sendToolError(
          { success: false, errorCode: 'INVALID_PAYLOAD', errorMessage: 'Invalid invoke payload shape' },
          fallbackSessionId,
          undefined,
          {
            traceId,
            gatewayMessageId: baseFields.gatewayMessageId,
            logger: messageLogger,
          },
        );
      }
      return;
    }

    const rawDownstreamFields = this.extractDownstreamLogFields(raw);
    const normalizedDownstreamFields = this.extractDownstreamLogFields(message);
    const downstreamFields = {
      ...rawDownstreamFields,
      ...baseFields,
      ...normalizedDownstreamFields,
    };
    if (!downstreamFields.gatewayMessageId) {
      downstreamFields.gatewayMessageId = rawDownstreamFields.gatewayMessageId ?? baseFields.gatewayMessageId;
    }
    const downstreamTraceId = downstreamFields.gatewayMessageId ?? traceId;
    const downstreamLogger = this.createMessageLogger(downstreamFields, downstreamTraceId);

    if (message.type === 'status_query') {
      downstreamLogger.info('runtime.status_query.received');
      const result = await this.actionRouter.route(
        'status_query',
        {},
        this.buildActionContext(undefined, downstreamLogger),
      );
      if (!result.success) {
        this.sendToolError(result, undefined, undefined, {
          traceId: downstreamTraceId,
          gatewayMessageId: downstreamFields.gatewayMessageId,
          action: 'status_query',
          logger: downstreamLogger,
        });
        return;
      }

      this.gatewayConnection.send(
        {
          type: 'status_response',
          opencodeOnline: !!(result.data as { opencodeOnline?: boolean } | undefined)?.opencodeOnline,
        },
        {
          traceId: downstreamTraceId,
          runtimeTraceId: this.logger.getTraceId(),
          gatewayMessageId: downstreamFields.gatewayMessageId,
          action: 'status_query',
        },
      );
      downstreamLogger.info('runtime.status_query.responded', {
        latencyMs: Date.now() - startedAt,
      });
      return;
    }

    const welinkSessionId = message.welinkSessionId;
    const toolSessionId = this.extractToolSessionId(message.payload);
    const invokeLogger = this.createMessageLogger(
      {
        ...downstreamFields,
        action: message.action,
        welinkSessionId,
        toolSessionId,
      },
      downstreamTraceId,
    );
    invokeLogger.info('runtime.invoke.received');

    const result = await this.actionRouter.route(
      message.action,
      message.payload,
      this.buildActionContext(welinkSessionId, invokeLogger),
    );

    if (!result.success) {
      this.sendToolError(result, welinkSessionId, toolSessionId, {
        traceId: downstreamTraceId,
        gatewayMessageId: downstreamFields.gatewayMessageId,
        action: message.action,
        logger: invokeLogger,
      });
      return;
    }

    if (message.action === 'create_session') {
      const toolSessionId = (result.data as { sessionId?: string } | undefined)?.sessionId;
      if (!toolSessionId) {
        this.sendToolError(
          { success: false, errorCode: 'SDK_UNREACHABLE', errorMessage: 'create_session returned without sessionId' },
          welinkSessionId,
          undefined,
          {
            traceId: downstreamTraceId,
            gatewayMessageId: downstreamFields.gatewayMessageId,
            action: message.action,
            logger: invokeLogger,
          },
        );
        return;
      }
      const sessionData = (result.data as { session?: unknown } | undefined)?.session ?? result.data;
      this.gatewayConnection.send(
        {
          type: 'session_created',
          welinkSessionId,
          toolSessionId,
          session: sessionData,
        },
        {
          traceId: downstreamTraceId,
          runtimeTraceId: this.logger.getTraceId(),
          gatewayMessageId: downstreamFields.gatewayMessageId,
          welinkSessionId,
          toolSessionId,
          action: message.action,
        },
      );
      invokeLogger.info('runtime.invoke.completed', {
        latencyMs: Date.now() - startedAt,
      });
      return;
    }

    invokeLogger.info('runtime.invoke.completed', {
      latencyMs: Date.now() - startedAt,
    });
  }

  private buildActionContext(sessionId?: string, logger: BridgeLogger = this.logger) {
    return {
      client: this.sdkClient,
      connectionState: this.stateManager.getState(),
      agentId: this.stateManager.getAgentId() ?? 'unknown-agent',
      sessionId,
      logger: logger.child({
        component: 'action',
        agentId: this.stateManager.getAgentId() ?? 'unknown-agent',
        sessionId,
      }),
    };
  }

  private extractSessionId(payload: unknown): string | undefined {
    if (!payload || typeof payload !== 'object') {
      return undefined;
    }

    const p = payload as {
      sessionId?: unknown;
      toolSessionId?: unknown;
      properties?: {
        sessionId?: unknown;
        sessionID?: unknown;
        part?: { sessionID?: unknown; sessionId?: unknown };
      };
    };
    if (typeof p.sessionId === 'string' && p.sessionId.trim()) {
      return p.sessionId;
    }

    const fromProperties = p.properties?.sessionId ?? p.properties?.sessionID;
    if (typeof fromProperties === 'string' && fromProperties.trim()) {
      return fromProperties;
    }

    const fromPart = p.properties?.part?.sessionID ?? p.properties?.part?.sessionId;
    if (typeof fromPart === 'string' && fromPart.trim()) {
      return fromPart;
    }

    return undefined;
  }

  private extractToolSessionId(payload: unknown): string | undefined {
    if (!payload || typeof payload !== 'object') {
      return undefined;
    }
    const p = payload as { toolSessionId?: unknown };
    if (typeof p.toolSessionId === 'string' && p.toolSessionId.trim()) {
      return p.toolSessionId;
    }
    return undefined;
  }

  private normalizeDownstreamMessage(raw: unknown): DownstreamMessage | null {
    if (!raw || typeof raw !== 'object') {
      return null;
    }
    const msg = raw as Record<string, unknown>;
    const type = typeof msg.type === 'string' ? msg.type : undefined;
    if (!type) {
      return null;
    }

    if (type === 'status_query') {
      return this.normalizeStatusQueryMessage();
    }

    if (type !== 'invoke') {
      return null;
    }

    return this.normalizeInvokeMessage(msg);
  }

  private normalizeStatusQueryMessage(): StatusQueryMessage {
    return {
      type: 'status_query',
    };
  }

  private normalizeInvokeMessage(msg: Record<string, unknown>): DownstreamMessage | null {
    const action = typeof msg.action === 'string' ? msg.action : undefined;
    if (!action) {
      return null;
    }

    switch (action) {
      case 'chat':
        return this.normalizeChatInvokeMessage(msg);
      case 'create_session':
        return this.normalizeCreateSessionInvokeMessage(msg);
      case 'abort_session':
        return this.normalizeAbortSessionInvokeMessage(msg);
      case 'close_session':
        return this.normalizeCloseSessionInvokeMessage(msg);
      case 'permission_reply':
        return this.normalizePermissionReplyInvokeMessage(msg);
      case 'question_reply':
        return this.normalizeQuestionReplyInvokeMessage(msg);
      default:
        return null;
    }
  }

  private normalizeChatInvokeMessage(msg: Record<string, unknown>): ChatInvokeMessage | null {
    const payload = this.extractInvokePayload(msg);
    const toolSessionId = this.readNonEmptyString(payload?.toolSessionId);
    const text = this.readNonEmptyString(payload?.text);
    if (!payload || !toolSessionId || !text) {
      return null;
    }

    return {
      type: 'invoke',
      action: 'chat',
      payload: { toolSessionId, text },
      welinkSessionId: this.readNonEmptyString(msg.welinkSessionId),
    };
  }

  private normalizeCreateSessionInvokeMessage(msg: Record<string, unknown>): CreateSessionInvokeMessage | null {
    const payload = this.extractInvokePayload(msg);
    if (!payload) {
      return null;
    }

    return {
      type: 'invoke',
      action: 'create_session',
      payload: { ...payload },
      welinkSessionId: this.readNonEmptyString(msg.welinkSessionId),
    };
  }

  private normalizeAbortSessionInvokeMessage(msg: Record<string, unknown>): AbortSessionInvokeMessage | null {
    const payload = this.extractInvokePayload(msg);
    const toolSessionId = this.readNonEmptyString(payload?.toolSessionId);
    if (!payload || !toolSessionId) {
      return null;
    }

    return {
      type: 'invoke',
      action: 'abort_session',
      payload: { toolSessionId },
      welinkSessionId: this.readNonEmptyString(msg.welinkSessionId),
    };
  }

  private normalizeCloseSessionInvokeMessage(msg: Record<string, unknown>): CloseSessionInvokeMessage | null {
    const payload = this.extractInvokePayload(msg);
    const toolSessionId = this.readNonEmptyString(payload?.toolSessionId);
    if (!payload || !toolSessionId) {
      return null;
    }

    return {
      type: 'invoke',
      action: 'close_session',
      payload: { toolSessionId },
      welinkSessionId: this.readNonEmptyString(msg.welinkSessionId),
    };
  }

  private normalizePermissionReplyInvokeMessage(msg: Record<string, unknown>): PermissionReplyInvokeMessage | null {
    const payload = this.extractInvokePayload(msg);
    const toolSessionId = this.readNonEmptyString(payload?.toolSessionId);
    const permissionId = this.readNonEmptyString(payload?.permissionId);
    const response = this.readNonEmptyString(payload?.response);
    if (!payload || !toolSessionId || !permissionId || !response) {
      return null;
    }
    const normalizedResponse = PERMISSION_REPLY_RESPONSES.find((candidate) => candidate === response);
    if (!normalizedResponse) {
      return null;
    }

    return {
      type: 'invoke',
      action: 'permission_reply',
      payload: { toolSessionId, permissionId, response: normalizedResponse },
      welinkSessionId: this.readNonEmptyString(msg.welinkSessionId),
    };
  }

  private normalizeQuestionReplyInvokeMessage(msg: Record<string, unknown>): QuestionReplyInvokeMessage | null {
    const payload = this.extractInvokePayload(msg);
    const toolSessionId = this.readNonEmptyString(payload?.toolSessionId);
    const toolCallId = this.readNonEmptyString(payload?.toolCallId);
    const answer = this.readNonEmptyString(payload?.answer);
    if (!payload || !toolSessionId || !answer) {
      return null;
    }

    return {
      type: 'invoke',
      action: 'question_reply',
      payload: { toolSessionId, toolCallId, answer },
      welinkSessionId: this.readNonEmptyString(msg.welinkSessionId),
    };
  }

  private extractInvokePayload(msg: Record<string, unknown>): Record<string, unknown> | null {
    if (!('payload' in msg)) {
      return null;
    }

    if (!msg.payload || typeof msg.payload !== 'object') {
      return null;
    }
    return msg.payload as Record<string, unknown>;
  }

  private readNonEmptyString(value: unknown): string | undefined {
    return typeof value === 'string' && value.trim() ? value : undefined;
  }

  private extractRawDownstreamType(raw: unknown): string | undefined {
    if (!raw || typeof raw !== 'object') {
      return undefined;
    }

    const msg = raw as Record<string, unknown>;
    return typeof msg.type === 'string' ? msg.type : undefined;
  }

  private sendToolError(
    result: ActionResult,
    welinkSessionId?: string,
    toolSessionId?: string,
    options: ToolErrorLogOptions = {},
  ): void {
    if (!this.gatewayConnection) {
      (options.logger ?? this.logger).warn('runtime.tool_error.skipped_no_connection', {
        welinkSessionId,
        toolSessionId,
      });
      return;
    }

    const error = result.errorMessage ?? 'Unknown error';
    const bridgeMessageId = buildMessageId();
    const errorLogger =
      options.logger ??
      this.createMessageLogger(
        {
          gatewayMessageId: options.gatewayMessageId,
          welinkSessionId,
          toolSessionId: toolSessionId ?? options.toolSessionId,
          action: options.action,
        },
        options.traceId ?? bridgeMessageId,
      );

    errorLogger.error('runtime.tool_error.sending', {
      welinkSessionId,
      toolSessionId: toolSessionId ?? options.toolSessionId,
      error,
      errorCode: result.errorCode,
      state: this.stateManager.getState(),
    });

    this.gatewayConnection.send(
      {
        type: 'tool_error',
        welinkSessionId,
        toolSessionId: toolSessionId ?? options.toolSessionId,
        error,
      },
      {
        traceId: options.traceId,
        runtimeTraceId: this.logger.getTraceId(),
        bridgeMessageId,
        gatewayMessageId: options.gatewayMessageId,
        welinkSessionId,
        toolSessionId: toolSessionId ?? options.toolSessionId,
        action: options.action,
        opencodeMessageId: options.opencodeMessageId,
      },
    );
  }

  private createMessageLogger(extra: Record<string, unknown>, traceId?: string): BridgeLogger {
    if (traceId && traceId !== this.logger.getTraceId()) {
      return this.logger.child({ ...extra, traceId });
    }
    return this.logger.child(extra);
  }

  private extractEventLogFields(event: BridgeEvent): EventLogFields {
    const properties = this.getRecord((event as { properties?: unknown }).properties);
    const part = this.getRecord(properties?.part);
    const eventType = typeof event.type === 'string' ? event.type : 'unknown';
    const toolSessionId =
      this.readNonEmptyString(part?.sessionID) ??
      this.readNonEmptyString(part?.sessionId) ??
      this.readNonEmptyString(properties?.sessionID) ??
      this.readNonEmptyString(properties?.sessionId) ??
      this.extractSessionId(event);
    const opencodeMessageId =
      this.readNonEmptyString(part?.messageID) ??
      this.readNonEmptyString(part?.messageId) ??
      this.readNonEmptyString(properties?.messageID) ??
      this.readNonEmptyString(properties?.messageId) ??
      this.readNonEmptyString(this.getRecord(properties?.info)?.id);
    const opencodePartId =
      this.readNonEmptyString(part?.id) ??
      this.readNonEmptyString(properties?.partID) ??
      this.readNonEmptyString(properties?.partId);
    const delta =
      this.readNonEmptyString(properties?.delta) ??
      this.readNonEmptyString(part?.delta);
    const diff = Array.isArray(properties?.diff) ? properties.diff : undefined;

    return {
      eventType,
      toolSessionId,
      opencodeMessageId,
      opencodePartId,
      partType: this.readNonEmptyString(part?.type),
      toolCallId:
        this.readNonEmptyString(part?.callID) ??
        this.readNonEmptyString(part?.callId) ??
        this.readNonEmptyString(properties?.toolCallId),
      deltaField: this.readNonEmptyString(properties?.field),
      deltaBytes: delta ? Buffer.byteLength(delta, 'utf8') : undefined,
      diffCount: diff?.length,
    };
  }

  private extractDownstreamLogFields(message: DownstreamMessage | unknown): DownstreamLogFields {
    const record = this.getRecord(message);
    const payload = this.getRecord(record?.payload);

    return {
      messageType: this.readNonEmptyString(record?.type) ?? 'unknown',
      gatewayMessageId: this.readNonEmptyString(record?.messageId),
      action: this.readNonEmptyString(record?.action),
      welinkSessionId:
        this.readNonEmptyString(record?.welinkSessionId) ??
        this.readNonEmptyString(payload?.welinkSessionId),
      toolSessionId:
        this.readNonEmptyString(payload?.toolSessionId) ??
        this.readNonEmptyString(record?.toolSessionId),
    };
  }

  private getRecord(value: unknown): Record<string, unknown> | undefined {
    return value && typeof value === 'object' ? (value as Record<string, unknown>) : undefined;
  }
}
