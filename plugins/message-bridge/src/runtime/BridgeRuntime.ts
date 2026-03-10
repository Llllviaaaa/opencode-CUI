import { randomUUID } from 'crypto';
import os from 'os';
import {
  ActionResult,
  StatusQueryPayload,
  StatusQueryResultData,
} from '../types';
import { ChatAction } from '../action/ChatAction';
import { CreateSessionAction } from '../action/CreateSessionAction';
import { CloseSessionAction } from '../action/CloseSessionAction';
import { PermissionReplyAction } from '../action/PermissionReplyAction';
import { StatusQueryAction } from '../action/StatusQueryAction';
import { AbortSessionAction } from '../action/AbortSessionAction';
import { QuestionReplyAction } from '../action/QuestionReplyAction';
import { DefaultActionRouter } from '../action/ActionRouter';
import { DefaultActionRegistry } from '../action/ActionRegistry';
import { loadConfig } from '../config';
import { DefaultAkSkAuth } from '../connection/AkSkAuth';
import { DefaultGatewayConnection, GatewayConnection } from '../connection/GatewayConnection';
import { DefaultStateManager } from '../connection/StateManager';
import { EventFilter } from '../event/EventFilter';
import {
  extractUpstreamEvent,
  type MessagePartExtra,
  type MessageUpdatedExtra,
  type NormalizedUpstreamEvent,
  type SessionStatusExtra,
} from '../protocol/upstream';
import {
  normalizeDownstreamMessage,
} from '../protocol/downstream';
import { BridgeEvent } from './types';
import { createSdkAdapter } from './SdkAdapter';
import { AppLogger, type BridgeLogger } from './AppLogger';

export interface BridgeRuntimeOptions {
  workspacePath?: string;
  client: unknown;
  debug?: boolean;
}

export interface BridgeRuntimeStartOptions {
  abortSignal?: AbortSignal;
}

interface EventLogFields {
  eventType: string;
  toolSessionId: string;
  opencodeMessageId?: string;
  opencodePartId?: string;
  role?: string | null;
  status?: string | null;
  partType?: string | null;
  toolCallId?: string | null;
  deltaBytes?: number | null;
}

interface DownstreamLogFields {
  messageType?: string;
  gatewayMessageId?: string;
  action?: string;
  welinkSessionId?: string;
  toolSessionId?: string;
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
      const errorMessage = error instanceof Error ? error.message : String(error);
      this.logger.error('runtime.config.loading_failed', {
        error: errorMessage,
        workspacePath: this.options.workspacePath,
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
      const messageType =
        message && typeof message === 'object' && 'type' in (message as { type?: unknown })
          ? String((message as { type?: unknown }).type ?? '')
          : 'unknown';
      this.logger.debug('gateway.message.received', { messageType });
      this.handleDownstreamMessage(message).catch((error) => {
        this.logger.error('runtime.downstream_message_error', {
          error: error instanceof Error ? error.message : String(error),
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
    const extraction = extractUpstreamEvent(event, this.logger);
    if (!extraction.ok) {
      return;
    }

    const normalized = extraction.value;
    const eventFields = this.buildEventLogFields(normalized);
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

    const bridgeMessageId = randomUUID();
    const forwardingLogger = this.createMessageLogger(eventFields, bridgeMessageId);
    this.logEventForwardingDetail(normalized, forwardingLogger);
    forwardingLogger.info('event.forwarding');
    this.gatewayConnection.send(
      {
        type: 'tool_event',
        toolSessionId: normalized.common.toolSessionId,
        event: normalized.raw,
      },
      {
        traceId: bridgeMessageId,
        runtimeTraceId: this.logger.getTraceId(),
        gatewayMessageId: bridgeMessageId,
        toolSessionId: normalized.common.toolSessionId,
        eventType: normalized.common.eventType,
        opencodeMessageId: eventFields.opencodeMessageId,
        opencodePartId: eventFields.opencodePartId,
        toolCallId: eventFields.toolCallId ?? undefined,
      },
    );
    forwardingLogger.debug('event.forwarded');
  }

  getStarted(): boolean {
    return this.started;
  }

  private registerActions(): void {
    const actions = [
      new ChatAction(),
      new CreateSessionAction(),
      new CloseSessionAction(),
      new PermissionReplyAction(),
      new StatusQueryAction(),
      new AbortSessionAction(),
      new QuestionReplyAction(),
    ] as const;

    for (const action of actions) {
      this.registry.register(action);
    }
  }

  private async handleDownstreamMessage(raw: unknown): Promise<void> {
    // Runtime orchestrates protocol flow but does not own raw schema parsing.
    if (!this.gatewayConnection) {
      this.logger.warn('runtime.downstream_ignored_no_connection');
      return;
    }
    const startedAt = Date.now();
    const downstreamFields = this.extractDownstreamLogFields(raw);
    const traceId = downstreamFields.gatewayMessageId ?? this.logger.getTraceId();
    const messageLogger = this.createMessageLogger(downstreamFields, traceId);
    const normalized = normalizeDownstreamMessage(raw, this.logger);
    if (!normalized.ok) {
      messageLogger.warn('runtime.downstream_ignored_non_protocol', {
        messageType: normalized.error.messageType ?? 'unknown',
        hasWelinkSessionId: !!normalized.error.welinkSessionId,
      });

      if (normalized.error.messageType === 'invoke') {
        this.sendToolError(
          { success: false, errorCode: 'INVALID_PAYLOAD', errorMessage: 'Invalid invoke payload shape' },
          normalized.error.welinkSessionId,
          {
            logger: messageLogger,
            traceId,
            gatewayMessageId: downstreamFields.gatewayMessageId,
            action: downstreamFields.action,
            toolSessionId: downstreamFields.toolSessionId,
          },
        );
      }
      return;
    }
    const message = normalized.value;

    if (message.type === 'status_query') {
      const statusLogger = this.createMessageLogger(
        { ...downstreamFields },
        traceId,
      );
      statusLogger.info('runtime.status_query.received');
      const payload: StatusQueryPayload = {};
      const result = await this.actionRouter.route(
        'status_query',
        payload,
        this.buildActionContext(undefined, statusLogger),
      );
      if (!result.success) {
        this.sendToolError(result, undefined, {
          logger: statusLogger,
          traceId,
          gatewayMessageId: downstreamFields.gatewayMessageId,
          action: 'status_query',
        });
        return;
      }

      this.gatewayConnection.send({
        type: 'status_response',
        opencodeOnline: result.data.opencodeOnline,
      }, {
        traceId,
        runtimeTraceId: this.logger.getTraceId(),
        gatewayMessageId: downstreamFields.gatewayMessageId,
        action: 'status_query',
      });
      statusLogger.info('runtime.status_query.responded', {
        latencyMs: Date.now() - startedAt,
      });
      return;
    }

    const welinkSessionId = message.welinkSessionId;
    const toolSessionId =
      'payload' in message &&
      message.payload &&
      typeof message.payload === 'object' &&
      'toolSessionId' in message.payload &&
      typeof (message.payload as { toolSessionId?: unknown }).toolSessionId === 'string'
        ? (message.payload as { toolSessionId: string }).toolSessionId
        : undefined;
    const invokeLogger = this.createMessageLogger(
      {
        ...downstreamFields,
        welinkSessionId,
        action: message.action,
        toolSessionId,
      },
      traceId,
    );
    invokeLogger.info('runtime.invoke.received');

    if (message.action === 'create_session') {
      const result = await this.actionRouter.route(
        message.action,
        message.payload,
        this.buildActionContext(welinkSessionId, invokeLogger),
      );

      if (!result.success) {
        this.sendToolError(result, welinkSessionId, {
          logger: invokeLogger,
          traceId,
          gatewayMessageId: downstreamFields.gatewayMessageId,
          action: message.action,
        });
        return;
      }

      const toolSessionId = result.data.sessionId;
      if (!toolSessionId) {
        this.sendToolError(
          { success: false, errorCode: 'SDK_UNREACHABLE', errorMessage: 'create_session returned without sessionId' },
          welinkSessionId,
          {
            logger: invokeLogger,
            traceId,
            gatewayMessageId: downstreamFields.gatewayMessageId,
            action: message.action,
          },
        );
        return;
      }
      if (!welinkSessionId) {
        this.sendToolError(
          { success: false, errorCode: 'INVALID_PAYLOAD', errorMessage: 'create_session missing welinkSessionId' },
          undefined,
          {
            logger: invokeLogger,
            traceId,
            gatewayMessageId: downstreamFields.gatewayMessageId,
            action: message.action,
          },
        );
        return;
      }

      this.gatewayConnection.send({
        type: 'session_created',
        welinkSessionId,
        toolSessionId,
        session: result.data,
      }, {
        traceId,
        runtimeTraceId: this.logger.getTraceId(),
        gatewayMessageId: downstreamFields.gatewayMessageId,
        welinkSessionId,
        toolSessionId,
        action: message.action,
      });
      invokeLogger.info('runtime.invoke.completed', {
        action: message.action,
        welinkSessionId,
        toolSessionId,
        latencyMs: Date.now() - startedAt,
      });
      return;
    }

    const result = await this.actionRouter.route(
      message.action,
      message.payload,
      this.buildActionContext(welinkSessionId, invokeLogger),
    );

    if (!result.success) {
      this.sendToolError(result, welinkSessionId, {
        logger: invokeLogger,
        traceId,
        gatewayMessageId: downstreamFields.gatewayMessageId,
        action: message.action,
        toolSessionId,
      });
      return;
    }

    invokeLogger.info('runtime.invoke.completed', {
      action: message.action,
      welinkSessionId,
      toolSessionId,
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

  private logEventForwardingDetail(normalized: NormalizedUpstreamEvent, logger: BridgeLogger = this.logger): void {
    const detail = this.buildEventForwardingDetail(normalized);
    logger.debug('event.forwarding.detail', detail as unknown as Record<string, unknown>);
  }

  private buildEventForwardingDetail(normalized: NormalizedUpstreamEvent): EventLogFields {
    const extra = normalized.extra;
    const raw = normalized.raw as {
      properties?: {
        delta?: unknown;
        part?: { type?: unknown; callID?: unknown };
      };
    };
    return {
      eventType: normalized.common.eventType,
      toolSessionId: normalized.common.toolSessionId,
      opencodeMessageId: this.getMessageId(extra) ?? undefined,
      opencodePartId: this.getPartId(extra) ?? undefined,
      role: this.getRole(extra),
      status: this.getStatus(extra),
      partType: typeof raw.properties?.part?.type === 'string' ? raw.properties.part.type : null,
      toolCallId: typeof raw.properties?.part?.callID === 'string' ? raw.properties.part.callID : undefined,
      deltaBytes: typeof raw.properties?.delta === 'string' ? Buffer.byteLength(raw.properties.delta, 'utf8') : null,
    };
  }

  private getMessageId(extra: NormalizedUpstreamEvent['extra']): string | null {
    if (!extra) {
      return null;
    }
    if (extra.kind === 'message.updated' || extra.kind === 'message.part.updated' || extra.kind === 'message.part.delta' || extra.kind === 'message.part.removed') {
      return extra.messageId;
    }
    return null;
  }

  private getPartId(extra: NormalizedUpstreamEvent['extra']): string | null {
    if (!extra) {
      return null;
    }
    if (extra.kind === 'message.part.updated' || extra.kind === 'message.part.delta' || extra.kind === 'message.part.removed') {
      return extra.partId;
    }
    return null;
  }

  private getRole(extra: NormalizedUpstreamEvent['extra']): MessageUpdatedExtra['role'] | null {
    return extra && extra.kind === 'message.updated' ? extra.role : null;
  }

  private getStatus(extra: NormalizedUpstreamEvent['extra']): SessionStatusExtra['status'] | null {
    return extra && extra.kind === 'session.status' ? extra.status : null;
  }

  private buildEventLogFields(normalized: NormalizedUpstreamEvent): EventLogFields {
    return this.buildEventForwardingDetail(normalized);
  }

  private extractDownstreamLogFields(raw: unknown): DownstreamLogFields {
    if (!raw || typeof raw !== 'object') {
      return {};
    }
    const message = raw as Record<string, unknown>;
    const payload = typeof message.payload === 'object' && message.payload ? (message.payload as Record<string, unknown>) : undefined;

    return {
      messageType: typeof message.type === 'string' ? message.type : undefined,
      gatewayMessageId: typeof message.messageId === 'string' ? message.messageId : undefined,
      action: typeof message.action === 'string' ? message.action : undefined,
      welinkSessionId: typeof message.welinkSessionId === 'string' ? message.welinkSessionId : undefined,
      toolSessionId: typeof payload?.toolSessionId === 'string' ? payload.toolSessionId : undefined,
    };
  }

  private createMessageLogger(
    baseFields: EventLogFields | DownstreamLogFields | Record<string, unknown>,
    traceId: string,
  ): BridgeLogger {
    const baseLogger = this.logger.child(baseFields as Record<string, unknown>);
    const withTrace = (method: 'debug' | 'info' | 'warn' | 'error') =>
      (message: string, extra?: Record<string, unknown>) => baseLogger[method](message, { traceId, ...(extra ?? {}) });

    return {
      debug: withTrace('debug'),
      info: withTrace('info'),
      warn: withTrace('warn'),
      error: withTrace('error'),
      child: (extra: Record<string, unknown>) => this.createMessageLogger({ ...baseFields, ...extra }, traceId),
      getTraceId: () => traceId,
    };
  }

  private sendToolError(
    result: ActionResult,
    welinkSessionId?: string,
    logOptions?: {
      logger?: BridgeLogger;
      traceId?: string;
      gatewayMessageId?: string;
      action?: string;
      toolSessionId?: string;
    },
  ): void {
    if (!this.gatewayConnection) {
      this.logger.warn('runtime.tool_error.skipped_no_connection', { welinkSessionId });
      return;
    }

    const error = result.success ? 'Unknown error' : result.errorMessage ?? 'Unknown error';
    const logger = logOptions?.logger ?? this.logger;
    logger.error('runtime.tool_error.sending', { welinkSessionId, error });

    this.gatewayConnection.send({
      type: 'tool_error',
      welinkSessionId,
      toolSessionId: logOptions?.toolSessionId,
      error,
    }, {
      traceId: logOptions?.traceId,
      runtimeTraceId: this.logger.getTraceId(),
      gatewayMessageId: logOptions?.gatewayMessageId,
      welinkSessionId,
      action: logOptions?.action,
      toolSessionId: logOptions?.toolSessionId,
    });
  }
}
