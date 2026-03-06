import os from 'os';
import {
  Action,
  ActionResult,
  DownstreamMessage,
  MessageBridgePlugin,
  stateToErrorCode,
} from '../types';
import { ChatAction } from '../action/ChatAction';
import { CreateSessionAction } from '../action/CreateSessionAction';
import { CloseSessionAction } from '../action/CloseSessionAction';
import { PermissionReplyAction } from '../action/PermissionReplyAction';
import { StatusQueryAction } from '../action/StatusQueryAction';
import { DefaultActionRouter } from '../action/ActionRouter';
import { ActionRegistry } from '../action/ActionRegistry';
import { loadConfig } from '../config';
import { DefaultAkSkAuth } from '../connection/AkSkAuth';
import { DefaultGatewayConnection, GatewayConnection } from '../connection/GatewayConnection';
import { DefaultStateManager } from '../connection/StateManager';
import { EnvelopeBuilder } from '../event/EnvelopeBuilder';
import { EventRelay } from '../event/EventRelay';

interface OpenCodeEventSource {
  event: {
    subscribe: (listener: (event: any) => void) => () => void;
  };
}

export interface MessageBridgePluginOptions {
  workspacePath?: string;
  opencodeClient?: unknown;
  opencodeEventSource?: OpenCodeEventSource;
}

export class MessageBridgePluginClass implements MessageBridgePlugin {
  private readonly actionRouter = new DefaultActionRouter();
  private readonly stateManager = new DefaultStateManager();

  private options?: MessageBridgePluginOptions;
  private gatewayConnection: GatewayConnection | null = null;
  private eventRelay: EventRelay | null = null;
  private envelopeBuilder: EnvelopeBuilder | null = null;
  private started = false;

  constructor(private readonly registry: ActionRegistry, options?: MessageBridgePluginOptions) {
    this.options = options;
    this.registerActions();
    this.actionRouter.setRegistry(registry);
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

  async start(): Promise<void> {
    if (this.started) {
      return;
    }

    const config = await loadConfig(this.options?.workspacePath);
    if (!config.enabled) {
      this.started = true;
      return;
    }

    const agentId = this.stateManager.generateAndBindAgentId();
    this.envelopeBuilder = new EnvelopeBuilder(agentId);

    const queryParams = new DefaultAkSkAuth(config.auth.ak, config.auth.sk).generateQueryParams();

    const connection = new DefaultGatewayConnection({
      url: config.gateway.url,
      reconnectBaseMs: config.gateway.reconnect.baseMs,
      reconnectMaxMs: config.gateway.reconnect.maxMs,
      reconnectExponential: config.gateway.reconnect.exponential,
      heartbeatIntervalMs: config.gateway.heartbeatIntervalMs,
      pongTimeoutMs: config.gateway.ping?.pongTimeoutMs,
      queryParams,
      registerMessage: {
        type: 'register',
        deviceName: config.gateway.deviceName,
        os: os.platform(),
        toolType: config.gateway.toolType,
        toolVersion: config.gateway.toolVersion,
      },
    });

    connection.on('stateChange', (state) => {
      this.stateManager.setState(state);
      if (state === 'CONNECTING') {
        const nextAgentId = this.stateManager.resetForReconnect();
        this.envelopeBuilder = new EnvelopeBuilder(nextAgentId);
      }
    });

    connection.on('message', (message) => {
      this.handleDownstreamMessage(message as DownstreamMessage).catch((error) => {
        console.error('downstream_message_error', {
          error: error instanceof Error ? error.message : String(error),
        });
      });
    });

    this.gatewayConnection = connection;

    if (this.options?.opencodeEventSource) {
      this.eventRelay = new EventRelay(
        this.options.opencodeEventSource,
        connection,
        this.stateManager,
        { allowlist: config.events.allowlist },
      );
      await this.eventRelay.start();
    }

    await connection.connect();
    this.started = true;
  }

  async stop(): Promise<void> {
    if (this.eventRelay) {
      this.eventRelay.stop();
      this.eventRelay = null;
    }

    if (this.gatewayConnection) {
      this.gatewayConnection.disconnect();
      this.gatewayConnection = null;
    }

    this.started = false;
  }

  private async handleDownstreamMessage(message: DownstreamMessage): Promise<void> {
    if (!this.gatewayConnection || !this.envelopeBuilder) {
      return;
    }

    if (message.type === 'status_query') {
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
      return;
    }

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
      return;
    }

    this.gatewayConnection.send({
      type: 'tool_done',
      sessionId: envelopeSessionId,
      result: result.data,
      envelope: this.envelopeBuilder.build(envelopeSessionId),
    });
  }

  private buildActionContext(sessionId?: string) {
    return {
      client: this.options?.opencodeClient,
      connectionState: this.stateManager.getState(),
      agentId: this.stateManager.getAgentId() ?? 'unknown-agent',
      sessionId,
    };
  }

  private extractSessionId(payload: unknown): string | undefined {
    if (!payload || typeof payload !== 'object') {
      return undefined;
    }

    const p = payload as { sessionId?: unknown; toolSessionId?: unknown };
    if (typeof p.sessionId === 'string' && p.sessionId.trim()) {
      return p.sessionId;
    }
    if (typeof p.toolSessionId === 'string' && p.toolSessionId.trim()) {
      return p.toolSessionId;
    }
    return undefined;
  }

  private sendToolError(result: ActionResult, sessionId?: string): void {
    if (!this.gatewayConnection || !this.envelopeBuilder) {
      return;
    }

    const code =
      result.errorCode ??
      stateToErrorCode(this.stateManager.getState());

    this.gatewayConnection.send({
      type: 'tool_error',
      sessionId,
      code,
      error: result.errorMessage ?? 'Unknown error',
      envelope: this.envelopeBuilder.build(sessionId),
    });
  }
}
