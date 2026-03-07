import os from 'os';
import {
  Action,
  ActionResult,
  DownstreamMessage,
  stateToErrorCode,
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
import { BridgeEvent } from './types';
import { createSdkAdapter } from './SdkAdapter';
import { AppLogger, type BridgeLogger } from './AppLogger';

export interface BridgeRuntimeOptions {
  workspacePath?: string;
  client: unknown;
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
    this.logger = new AppLogger(options.client, { component: 'runtime' });
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

    const config = await loadConfig(this.options.workspacePath);
    if (!config.enabled) {
      this.logger.info('runtime.start.disabled_by_config');
      this.started = true;
      return;
    }

    const agentId = this.stateManager.generateAndBindAgentId();
    this.envelopeBuilder = new EnvelopeBuilder(agentId);
    this.eventFilter = new EventFilter(config.events.allowlist);

    const queryParams = new DefaultAkSkAuth(config.auth.ak, config.auth.sk).generateQueryParams();

    const connection = new DefaultGatewayConnection({
      url: config.gateway.url,
      reconnectBaseMs: config.gateway.reconnect.baseMs,
      reconnectMaxMs: config.gateway.reconnect.maxMs,
      reconnectExponential: config.gateway.reconnect.exponential,
      heartbeatIntervalMs: config.gateway.heartbeatIntervalMs,
      pongTimeoutMs: config.gateway.ping?.pongTimeoutMs,
      abortSignal: options.abortSignal,
      queryParams,
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
      this.handleDownstreamMessage(message as DownstreamMessage).catch((error) => {
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
    if (!this.stateManager.isReady() || !this.gatewayConnection || !this.envelopeBuilder || !this.eventFilter) {
      this.logger.debug('event.ignored_not_ready', {
        state: this.stateManager.getState(),
      });
      return;
    }

    if (!this.eventFilter.isAllowed(event.type)) {
      this.logger.warn('event.rejected_allowlist', { eventType: event.type });
      return;
    }

    const sessionId = this.extractSessionId(event);
    this.logger.info('event.forwarding', { eventType: event.type, sessionId });
    this.gatewayConnection.send({
      type: 'tool_event',
      sessionId,
      event,
      envelope: this.envelopeBuilder.build(sessionId),
    });
    this.logger.debug('event.forwarded', { eventType: event.type, sessionId });
  }

  getStarted(): boolean {
    return this.started;
  }

  private registerActions(): void {
    const actions: Action[] = [
      new ChatAction(),
      new CreateSessionAction(),
      new CloseSessionAction(),
      new PermissionReplyAction(),
      new StatusQueryAction(),
    ];

    for (const action of actions) {
      this.registry.register(action);
    }
  }

  private async handleDownstreamMessage(message: DownstreamMessage): Promise<void> {
    if (!this.gatewayConnection || !this.envelopeBuilder) {
      this.logger.warn('runtime.downstream_ignored_no_connection');
      return;
    }
    const startedAt = Date.now();

    if (message.type === 'status_query') {
      this.logger.info('runtime.status_query.received', { sessionId: message.sessionId });
      const result = await this.actionRouter.route('status_query', { sessionId: message.sessionId }, this.buildActionContext(message.sessionId));
      if (!result.success) {
        this.sendToolError(result, message.sessionId);
        return;
      }

      this.gatewayConnection.send({
        type: 'status_response',
        opencodeOnline: !!(result.data as { opencodeOnline?: boolean } | undefined)?.opencodeOnline,
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
    const result = await this.actionRouter.route(
      message.action,
      message.payload,
      this.buildActionContext(this.extractSessionId(message.payload) ?? (message.envelope as { sessionId?: string } | undefined)?.sessionId),
    );

    const payloadSessionId = this.extractSessionId(message.payload);
    const envelopeSessionId = payloadSessionId ?? (message.envelope as { sessionId?: string } | undefined)?.sessionId;

    if (!result.success) {
      this.sendToolError(result, envelopeSessionId);
      return;
    }

    if (message.action === 'create_session') {
      const createdSessionId = (result.data as { sessionId?: string } | undefined)?.sessionId;
      if (!createdSessionId) {
        this.sendToolError(
          { success: false, errorCode: 'SDK_UNREACHABLE', errorMessage: 'create_session returned without sessionId' },
          envelopeSessionId,
        );
        return;
      }

      this.gatewayConnection.send({
        type: 'session_created',
        sessionId: createdSessionId,
        envelope: this.envelopeBuilder.build(createdSessionId),
      });
      this.logger.info('runtime.invoke.completed', {
        action: message.action,
        sessionId: createdSessionId,
        latencyMs: Date.now() - startedAt,
      });
      return;
    }

    this.gatewayConnection.send({
      type: 'tool_done',
      sessionId: envelopeSessionId,
      result: result.data,
      envelope: this.envelopeBuilder.build(envelopeSessionId),
    });
    this.logger.info('runtime.invoke.completed', {
      action: message.action,
      sessionId: envelopeSessionId,
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

  private extractSessionId(payload: unknown): string | undefined {
    if (!payload || typeof payload !== 'object') {
      return undefined;
    }

    const p = payload as {
      sessionId?: unknown;
      toolSessionId?: unknown;
      properties?: { sessionId?: unknown };
    };
    if (typeof p.sessionId === 'string' && p.sessionId.trim()) {
      return p.sessionId;
    }

    if (typeof p.toolSessionId === 'string' && p.toolSessionId.trim()) {
      return p.toolSessionId;
    }

    const fromProperties = p.properties?.sessionId;
    if (typeof fromProperties === 'string' && fromProperties.trim()) {
      return fromProperties;
    }

    return undefined;
  }

  private sendToolError(result: ActionResult, sessionId?: string): void {
    if (!this.gatewayConnection || !this.envelopeBuilder) {
      this.logger.warn('runtime.tool_error.skipped_no_connection', { sessionId });
      return;
    }

    const code = result.errorCode ?? stateToErrorCode(this.stateManager.getState());
    const error = result.errorMessage ?? 'Unknown error';
    this.logger.error('runtime.tool_error.sending', { sessionId, code, error });

    this.gatewayConnection.send({
      type: 'tool_error',
      sessionId,
      code,
      error,
      envelope: this.envelopeBuilder.build(sessionId),
    });
  }
}
