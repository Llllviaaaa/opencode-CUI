import os from 'os';
import {
  ActionResult,
  StatusQueryPayload,
} from '../types';
import { ChatAction } from '../action/ChatAction';
import { CreateSessionAction } from '../action/CreateSessionAction';
import { CloseSessionAction } from '../action/CloseSessionAction';
import { PermissionReplyAction } from '../action/PermissionReplyAction';
import { StatusQueryAction } from '../action/StatusQueryAction';
import { DefaultActionRouter } from '../action/ActionRouter';
import { DefaultActionRegistry } from '../action/ActionRegistry';
import { loadConfig } from '../config';
import { DefaultAkSkAuth } from '../connection/AkSkAuth';
import { DefaultGatewayConnection, GatewayConnection } from '../connection/GatewayConnection';
import { DefaultStateManager } from '../connection/StateManager';
import { EnvelopeBuilder } from '../event/EnvelopeBuilder';
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

export class BridgeRuntime {
  private readonly actionRouter = new DefaultActionRouter();
  private readonly stateManager = new DefaultStateManager();
  private readonly registry = new DefaultActionRegistry();

  private gatewayConnection: GatewayConnection | null = null;
  private envelopeBuilder: EnvelopeBuilder | null = null;
  private eventFilter: EventFilter | null = null;
  private started = false;
  private readonly sdkClient: unknown;
  private readonly logger: BridgeLogger;

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
      config = await loadConfig(this.options.workspacePath);
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
    this.envelopeBuilder = new EnvelopeBuilder(agentId);
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
        this.envelopeBuilder = new EnvelopeBuilder(nextAgentId);
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
    this.logger.debug('event.received', {
      eventType: event.type,
    });
    if (!this.stateManager.isReady() || !this.gatewayConnection || !this.eventFilter) {
      this.logger.debug('event.ignored_not_ready', {
        state: this.stateManager.getState(),
      });
      return;
    }

    if (!this.eventFilter.isAllowed(event.type)) {
      this.logger.warn('event.rejected_allowlist', { eventType: event.type });
      return;
    }

    const extraction = extractUpstreamEvent(event, this.logger);
    if (!extraction.ok) {
      return;
    }

    const normalized = extraction.value;
    this.logEventForwardingDetail(normalized);
    this.logger.info('event.forwarding', {
      eventType: normalized.common.eventType,
      toolSessionId: normalized.common.toolSessionId,
    });
    this.gatewayConnection.send({
      type: 'tool_event',
      toolSessionId: normalized.common.toolSessionId,
      event: normalized.raw,
    });
    this.logger.debug('event.forwarded', {
      eventType: normalized.common.eventType,
      toolSessionId: normalized.common.toolSessionId,
    });
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
    ] as const;

    for (const action of actions) {
      this.registry.register(action);
    }
  }

  private async handleDownstreamMessage(raw: unknown): Promise<void> {
    // Runtime orchestrates protocol flow but does not own raw schema parsing.
    if (!this.gatewayConnection || !this.envelopeBuilder) {
      this.logger.warn('runtime.downstream_ignored_no_connection');
      return;
    }
    const startedAt = Date.now();
    const normalized = normalizeDownstreamMessage(raw, this.logger);
    if (!normalized.ok) {
      this.logger.warn('runtime.downstream_ignored_non_protocol', {
        messageType: normalized.error.messageType ?? 'unknown',
        hasSessionId: !!normalized.error.sessionId,
      });

      if (normalized.error.messageType === 'invoke') {
        this.sendToolError(
          { success: false, errorCode: 'INVALID_PAYLOAD', errorMessage: 'Invalid invoke payload shape' },
          normalized.error.sessionId,
        );
      }
      return;
    }
    const message = normalized.value;

    if (message.type === 'status_query') {
      this.logger.info('runtime.status_query.received', { sessionId: message.sessionId });
      const payload: StatusQueryPayload = { sessionId: message.sessionId };
      const result = await this.actionRouter.route('status_query', payload, this.buildActionContext(message.sessionId));
      if (!result.success) {
        this.sendToolError(result, message.sessionId);
        return;
      }

      this.gatewayConnection.send({
        type: 'status_response',
        opencodeOnline: result.data.opencodeOnline,
        sessionId: message.sessionId,
        envelope: this.envelopeBuilder.build(message.sessionId),
      });
      this.logger.info('runtime.status_query.responded', {
        sessionId: message.sessionId,
        latencyMs: Date.now() - startedAt,
      });
      return;
    }

    this.logger.info('runtime.invoke.received', { action: message.action });
    const skillSessionId =
      message.sessionId ??
      (message.envelope as { sessionId?: string } | undefined)?.sessionId;

    if (message.action === 'create_session') {
      const result = await this.actionRouter.route(
        message.action,
        message.payload,
        this.buildActionContext(skillSessionId),
      );

      if (!result.success) {
        this.sendToolError(result, skillSessionId);
        return;
      }

      const toolSessionId = result.data.sessionId;
      if (!toolSessionId) {
        this.sendToolError(
          { success: false, errorCode: 'SDK_UNREACHABLE', errorMessage: 'create_session returned without sessionId' },
          skillSessionId,
        );
        return;
      }
      if (!skillSessionId) {
        this.sendToolError(
          { success: false, errorCode: 'INVALID_PAYLOAD', errorMessage: 'create_session missing skill sessionId' },
          undefined,
        );
        return;
      }

      this.gatewayConnection.send({
        type: 'session_created',
        sessionId: skillSessionId,
        toolSessionId,
        session: result.data,
        envelope: this.envelopeBuilder.build(skillSessionId),
      });
      this.logger.info('runtime.invoke.completed', {
        action: message.action,
        sessionId: skillSessionId,
        toolSessionId,
        latencyMs: Date.now() - startedAt,
      });
      return;
    }

    const result = await this.actionRouter.route(
      message.action,
      message.payload,
      this.buildActionContext(skillSessionId),
    );

    if (!result.success) {
      this.sendToolError(result, skillSessionId);
      return;
    }

    this.logger.info('runtime.invoke.completed', {
      action: message.action,
      sessionId: skillSessionId,
      latencyMs: Date.now() - startedAt,
    });
  }

  private buildActionContext(sessionId?: string) {
    return {
      client: this.sdkClient,
      connectionState: this.stateManager.getState(),
      agentId: this.stateManager.getAgentId() ?? 'unknown-agent',
      sessionId,
      logger: this.logger.child({
        component: 'action',
        agentId: this.stateManager.getAgentId() ?? 'unknown-agent',
        sessionId,
      }),
    };
  }

  private logEventForwardingDetail(normalized: NormalizedUpstreamEvent): void {
    const detail = this.buildEventForwardingDetail(normalized);
    this.logger.debug('event.forwarding.detail', detail);
  }

  private buildEventForwardingDetail(normalized: NormalizedUpstreamEvent): Record<string, unknown> {
    const extra = normalized.extra;
    return {
      eventType: normalized.common.eventType,
      toolSessionId: normalized.common.toolSessionId,
      messageId: this.getMessageId(extra),
      partId: this.getPartId(extra),
      role: this.getRole(extra),
      status: this.getStatus(extra),
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

  private sendToolError(result: ActionResult, sessionId?: string): void {
    if (!this.gatewayConnection || !this.envelopeBuilder) {
      this.logger.warn('runtime.tool_error.skipped_no_connection', { sessionId });
      return;
    }

    const error = result.success ? 'Unknown error' : result.errorMessage ?? 'Unknown error';
    this.logger.error('runtime.tool_error.sending', { sessionId, error });

    this.gatewayConnection.send({
      type: 'tool_error',
      sessionId,
      error,
      envelope: this.envelopeBuilder.build(sessionId),
    });
  }
}
