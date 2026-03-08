import os from 'os';
import {
  Action,
  ActionResult,
  ChatInvokeMessage,
  CloseSessionInvokeMessage,
  CreateSessionInvokeMessage,
  DownstreamMessage,
  PERMISSION_REPLY_RESPONSES,
  PermissionReplyInvokeMessage,
  QuestionReplyInvokeMessage,
  StatusQueryMessage,
  hasEnvelope,
} from '../types';
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
import { DefaultGatewayConnection, GatewayConnection } from '../connection/GatewayConnection';
import { DefaultStateManager } from '../connection/StateManager';
import { EnvelopeBuilder } from '../event/EnvelopeBuilder';
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

export class BridgeRuntime {
  private readonly actionRouter = new DefaultActionRouter();
  private readonly stateManager = new DefaultStateManager();
  private readonly registry = new DefaultActionRegistry();

  private gatewayConnection: GatewayConnection | null = null;
  private envelopeBuilder: EnvelopeBuilder | null = null;
  private eventFilter: EventFilter | null = null;
  private started = false;
  private readonly sdkClient: unknown;
  private logger: BridgeLogger;
  private readonly toolToSkillSessionMap = new Map<string, string>();
  private lastSkillSessionId: string | null = null;

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
        this.toolToSkillSessionMap.clear();
        this.lastSkillSessionId = null;
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

    const toolSessionId = this.extractSessionId(event);
    const sessionId = this.resolveSkillSessionId(toolSessionId);
    this.logger.info('event.forwarding', { eventType: event.type, toolSessionId, sessionId });
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
      new QuestionReplyAction(),
      new StatusQueryAction(),
    ];

    for (const action of actions) {
      this.registry.register(action);
    }
  }

  private async handleDownstreamMessage(raw: unknown): Promise<void> {
    if (!this.gatewayConnection || !this.envelopeBuilder) {
      this.logger.warn('runtime.downstream_ignored_no_connection');
      return;
    }
    const startedAt = Date.now();
    const message = this.normalizeDownstreamMessage(raw);
    if (!message) {
      const rawType = this.extractRawDownstreamType(raw);
      const fallbackSessionId =
        raw && typeof raw === 'object' && typeof (raw as { sessionId?: unknown }).sessionId === 'string'
          ? (raw as { sessionId: string }).sessionId
          : undefined;

      this.logger.warn('runtime.downstream_ignored_non_protocol', {
        messageType: rawType ?? 'unknown',
        hasSessionId: !!fallbackSessionId,
        hasEnvelope: hasEnvelope(raw),
      });

      if (rawType === 'invoke') {
        this.sendToolError(
          { success: false, errorCode: 'INVALID_PAYLOAD', errorMessage: 'Invalid invoke payload shape' },
          fallbackSessionId,
        );
      }
      return;
    }

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

    const skillSessionId =
      message.sessionId ??
      (message.envelope as { sessionId?: string } | undefined)?.sessionId;
    const toolSessionId = this.extractToolSessionId(message.payload);
    this.logger.info('runtime.invoke.received', {
      action: message.action,
      sessionId: skillSessionId,
      toolSessionId,
      hasEnvelope: !!message.envelope,
    });
    if (toolSessionId && skillSessionId) {
      this.rememberSessionMapping(toolSessionId, skillSessionId);
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

    if (message.action === 'create_session') {
      const toolSessionId = (result.data as { sessionId?: string } | undefined)?.sessionId;
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

      this.rememberSessionMapping(toolSessionId, skillSessionId);

      this.gatewayConnection.send({
        type: 'session_created',
        // sessionId is the Skill session ID expected by skill-server.
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

    if (message.action === 'close_session' && toolSessionId) {
      this.toolToSkillSessionMap.delete(toolSessionId);
      if (this.lastSkillSessionId === skillSessionId) {
        this.lastSkillSessionId = null;
      }
    }

    this.logger.info('runtime.invoke.completed', {
      action: message.action,
      sessionId: skillSessionId,
      toolSessionId,
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

    const fromProperties = p.properties?.sessionId;
    if (typeof fromProperties === 'string' && fromProperties.trim()) {
      return fromProperties;
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

  private rememberSessionMapping(toolSessionId: string, skillSessionId: string): void {
    this.toolToSkillSessionMap.set(toolSessionId, skillSessionId);
    this.lastSkillSessionId = skillSessionId;
    this.logger.debug('runtime.session_mapping.updated', {
      toolSessionId,
      sessionId: skillSessionId,
      mappingSize: this.toolToSkillSessionMap.size,
    });
  }

  private resolveSkillSessionId(toolSessionId?: string): string | undefined {
    if (toolSessionId && this.toolToSkillSessionMap.has(toolSessionId)) {
      return this.toolToSkillSessionMap.get(toolSessionId);
    }
    return this.lastSkillSessionId ?? undefined;
  }

  private normalizeDownstreamMessage(raw: unknown): DownstreamMessage | null {
    const normalized = this.extractDownstreamRecord(raw);
    if (!normalized) {
      return null;
    }

    const { msg, envelope } = normalized;
    const type = typeof msg.type === 'string' ? msg.type : undefined;
    if (!type) {
      return null;
    }

    if (type === 'status_query') {
      return this.normalizeStatusQueryMessage(msg, envelope);
    }

    if (type !== 'invoke') {
      return null;
    }

    return this.normalizeInvokeMessage(msg, envelope);
  }

  private extractDownstreamRecord(
    raw: unknown,
  ): { msg: Record<string, unknown>; envelope?: DownstreamMessage['envelope'] } | null {
    if (!raw || typeof raw !== 'object') {
      return null;
    }

    if (hasEnvelope(raw)) {
      const envelopeMsg = raw as Record<string, unknown>;
      const payload =
        typeof envelopeMsg.payload === 'object' && envelopeMsg.payload !== null
          ? (envelopeMsg.payload as Record<string, unknown>)
          : null;

      return {
        msg: {
          ...(payload ?? {}),
          type: envelopeMsg.type,
          action: envelopeMsg.action ?? payload?.action,
          sessionId: envelopeMsg.sessionId ?? payload?.sessionId,
        },
        envelope: envelopeMsg.envelope as DownstreamMessage['envelope'],
      };
    }

    return {
      msg: raw as Record<string, unknown>,
    };
  }

  private normalizeStatusQueryMessage(
    msg: Record<string, unknown>,
    envelope?: DownstreamMessage['envelope'],
  ): StatusQueryMessage {
    return {
      type: 'status_query',
      sessionId: typeof msg.sessionId === 'string' ? msg.sessionId : undefined,
      envelope,
    };
  }

  private normalizeInvokeMessage(
    msg: Record<string, unknown>,
    envelope?: DownstreamMessage['envelope'],
  ): DownstreamMessage | null {
    const action = typeof msg.action === 'string' ? msg.action : undefined;
    if (!action) {
      return null;
    }

    switch (action) {
      case 'chat':
        return this.normalizeChatInvokeMessage(msg, envelope);
      case 'create_session':
        return this.normalizeCreateSessionInvokeMessage(msg, envelope);
      case 'close_session':
        return this.normalizeCloseSessionInvokeMessage(msg, envelope);
      case 'permission_reply':
        return this.normalizePermissionReplyInvokeMessage(msg, envelope);
      case 'question_reply':
        return this.normalizeQuestionReplyInvokeMessage(msg, envelope);
      default:
        return null;
    }
  }

  private normalizeChatInvokeMessage(
    msg: Record<string, unknown>,
    envelope?: DownstreamMessage['envelope'],
  ): ChatInvokeMessage | null {
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
      sessionId: this.readNonEmptyString(msg.sessionId),
      envelope,
    };
  }

  private normalizeCreateSessionInvokeMessage(
    msg: Record<string, unknown>,
    envelope?: DownstreamMessage['envelope'],
  ): CreateSessionInvokeMessage | null {
    const payload = this.extractInvokePayload(msg);
    if (!payload) {
      return null;
    }

    const metadata =
      typeof payload.metadata === 'object' && payload.metadata !== null
        ? (payload.metadata as Record<string, unknown>)
        : undefined;

    return {
      type: 'invoke',
      action: 'create_session',
      payload: {
        sessionId: this.readNonEmptyString(payload.sessionId),
        metadata,
      },
      sessionId: this.readNonEmptyString(msg.sessionId),
      envelope,
    };
  }

  private normalizeCloseSessionInvokeMessage(
    msg: Record<string, unknown>,
    envelope?: DownstreamMessage['envelope'],
  ): CloseSessionInvokeMessage | null {
    const payload = this.extractInvokePayload(msg);
    const toolSessionId = this.readNonEmptyString(payload?.toolSessionId);
    if (!payload || !toolSessionId) {
      return null;
    }

    return {
      type: 'invoke',
      action: 'close_session',
      payload: { toolSessionId },
      sessionId: this.readNonEmptyString(msg.sessionId),
      envelope,
    };
  }

  private normalizePermissionReplyInvokeMessage(
    msg: Record<string, unknown>,
    envelope?: DownstreamMessage['envelope'],
  ): PermissionReplyInvokeMessage | null {
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
      sessionId: this.readNonEmptyString(msg.sessionId),
      envelope,
    };
  }

  private normalizeQuestionReplyInvokeMessage(
    msg: Record<string, unknown>,
    envelope?: DownstreamMessage['envelope'],
  ): QuestionReplyInvokeMessage | null {
    const payload = this.extractInvokePayload(msg);
    const toolSessionId = this.readNonEmptyString(payload?.toolSessionId);
    const toolCallId = this.readNonEmptyString(payload?.toolCallId);
    const answer = this.readNonEmptyString(payload?.answer);
    if (!payload || !toolSessionId || !toolCallId || !answer) {
      return null;
    }

    return {
      type: 'invoke',
      action: 'question_reply',
      payload: { toolSessionId, toolCallId, answer },
      sessionId: this.readNonEmptyString(msg.sessionId),
      envelope,
    };
  }

  private extractInvokePayload(msg: Record<string, unknown>): Record<string, unknown> | null {
    if ('payload' in msg) {
      if (!msg.payload || typeof msg.payload !== 'object') {
        return null;
      }
      return msg.payload as Record<string, unknown>;
    }

    return msg;
  }

  private readNonEmptyString(value: unknown): string | undefined {
    return typeof value === 'string' && value.trim() ? value : undefined;
  }

  private extractRawDownstreamType(raw: unknown): string | undefined {
    if (!raw || typeof raw !== 'object') {
      return undefined;
    }

    if (hasEnvelope(raw)) {
      const envelopeMsg = raw as Record<string, unknown>;
      return typeof envelopeMsg.type === 'string' ? envelopeMsg.type : undefined;
    }

    const msg = raw as Record<string, unknown>;
    return typeof msg.type === 'string' ? msg.type : undefined;
  }

  private sendToolError(result: ActionResult, sessionId?: string): void {
    if (!this.gatewayConnection || !this.envelopeBuilder) {
      this.logger.warn('runtime.tool_error.skipped_no_connection', { sessionId });
      return;
    }

    const error = result.errorMessage ?? 'Unknown error';
    this.logger.error('runtime.tool_error.sending', {
      sessionId,
      error,
      errorCode: result.errorCode,
      state: this.stateManager.getState(),
    });

    this.gatewayConnection.send({
      type: 'tool_error',
      sessionId,
      error,
      envelope: this.envelopeBuilder.build(sessionId),
    });
  }
}
