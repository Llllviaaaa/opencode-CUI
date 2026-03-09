import { GatewayConnection, StateManager } from '../connection';
import { EventFilter } from './EventFilter';
import { EnvelopeBuilder } from './EnvelopeBuilder';
import type { BridgeLogger } from '../runtime/AppLogger';
import { getErrorDetailsForLog, getErrorMessage } from '../utils/error';

export interface OpenCodeEvent {
  type: string;
  properties?: Record<string, unknown>;
  sessionId?: string;
  [key: string]: unknown;
}

export interface EventRelayOptions {
  eventFilter?: EventFilter;
  allowlist?: readonly string[];
  logger?: BridgeLogger;
}

export class EventRelay {
  private readonly eventFilter: EventFilter;
  private subscription: (() => void) | null = null;
  private isRunning = false;

  private currentAgentId: string | null = null;
  private envelopeBuilder: EnvelopeBuilder | null = null;
  private readonly logger?: BridgeLogger;

  constructor(
    private readonly opencode: { event: { subscribe: (listener: (event: OpenCodeEvent) => void) => () => void } },
    private readonly gateway: GatewayConnection,
    private readonly stateManager: StateManager,
    options: EventRelayOptions = {},
  ) {
    this.eventFilter = options.eventFilter ?? new EventFilter(options.allowlist);
    this.logger = options.logger;
  }

  async start(): Promise<void> {
    if (this.isRunning) {
      return;
    }

    this.isRunning = true;
    this.logger?.info('event.relay.started');
    this.subscription = this.opencode.event.subscribe((event: OpenCodeEvent) => {
      this.handleEvent(event).catch((error) => {
        this.logger?.error('event.relay.error', {
          error: getErrorMessage(error),
          ...getErrorDetailsForLog(error),
        });
      });
    });
  }

  stop(): void {
    if (!this.isRunning) {
      return;
    }

    this.isRunning = false;
    this.logger?.info('event.relay.stopped');
    if (this.subscription) {
      this.subscription();
      this.subscription = null;
    }
  }

  private async handleEvent(event: OpenCodeEvent): Promise<void> {
    const eventFields = this.extractEventLogFields(event);
    const eventTraceId = eventFields.opencodeMessageId ?? this.logger?.getTraceId();
    const eventLogger = this.createMessageLogger(eventFields, eventTraceId);
    if (!this.stateManager.isReady()) {
      eventLogger?.debug('event.relay.ignored_not_ready');
      return;
    }

    if (!this.eventFilter.isAllowed(event.type)) {
      eventLogger?.warn('event.relay.rejected_allowlist');
      return;
    }

    const sessionId = this.extractSessionId(event);
    const envelope = this.getEnvelopeBuilder().build(sessionId);
    const bridgeMessageId = envelope.messageId;
    const forwardingLogger = this.createMessageLogger(
      {
        ...eventFields,
        sessionId,
      },
      bridgeMessageId,
    );
    forwardingLogger?.debug('event.relay.forwarding');

    this.gateway.send(
      {
        type: 'tool_event',
        sessionId,
        event,
        envelope,
      },
      {
        traceId: bridgeMessageId,
        runtimeTraceId: this.logger?.getTraceId(),
        bridgeMessageId,
        sessionId,
        toolSessionId: eventFields.toolSessionId,
        eventType: event.type,
        opencodeMessageId: eventFields.opencodeMessageId,
        opencodePartId: eventFields.opencodePartId,
        toolCallId: eventFields.toolCallId,
      },
    );
  }

  private getEnvelopeBuilder(): EnvelopeBuilder {
    const agentId = this.stateManager.getAgentId();
    if (!agentId) {
      throw new Error('Agent ID not available from StateManager');
    }

    if (!this.envelopeBuilder || this.currentAgentId !== agentId) {
      this.currentAgentId = agentId;
      this.envelopeBuilder = new EnvelopeBuilder(agentId);
    }

    return this.envelopeBuilder;
  }

  private extractSessionId(event: OpenCodeEvent): string | undefined {
    const fromProps =
      event.properties?.sessionId ??
      event.properties?.sessionID ??
      (event.properties?.part as { sessionID?: unknown; sessionId?: unknown } | undefined)?.sessionID ??
      (event.properties?.part as { sessionID?: unknown; sessionId?: unknown } | undefined)?.sessionId;
    if (typeof fromProps === 'string' && fromProps.trim()) {
      return fromProps;
    }

    if (typeof event.sessionId === 'string' && event.sessionId.trim()) {
      return event.sessionId;
    }

    return undefined;
  }

  private createMessageLogger(extra: Record<string, unknown>, traceId?: string): BridgeLogger | undefined {
    if (!this.logger) {
      return undefined;
    }
    if (traceId && traceId !== this.logger.getTraceId()) {
      return this.logger.child({ ...extra, traceId });
    }
    return this.logger.child(extra);
  }

  private extractEventLogFields(event: OpenCodeEvent) {
    const properties = event.properties ?? {};
    const part =
      typeof properties.part === 'object' && properties.part !== null
        ? (properties.part as Record<string, unknown>)
        : undefined;
    const delta =
      typeof properties.delta === 'string'
        ? properties.delta
        : typeof part?.delta === 'string'
          ? part.delta
          : undefined;
    const diff = Array.isArray(properties.diff) ? properties.diff : undefined;

    return {
      eventType: event.type,
      toolSessionId:
        (typeof part?.sessionID === 'string' && part.sessionID.trim() ? part.sessionID : undefined) ??
        (typeof part?.sessionId === 'string' && part.sessionId.trim() ? part.sessionId : undefined) ??
        (typeof properties.sessionID === 'string' && properties.sessionID.trim() ? properties.sessionID : undefined) ??
        (typeof properties.sessionId === 'string' && properties.sessionId.trim() ? properties.sessionId : undefined),
      opencodeMessageId:
        (typeof part?.messageID === 'string' && part.messageID.trim() ? part.messageID : undefined) ??
        (typeof part?.messageId === 'string' && part.messageId.trim() ? part.messageId : undefined) ??
        (typeof properties.messageID === 'string' && properties.messageID.trim() ? properties.messageID : undefined) ??
        (typeof properties.messageId === 'string' && properties.messageId.trim() ? properties.messageId : undefined),
      opencodePartId:
        (typeof part?.id === 'string' && part.id.trim() ? part.id : undefined) ??
        (typeof properties.partID === 'string' && properties.partID.trim() ? properties.partID : undefined) ??
        (typeof properties.partId === 'string' && properties.partId.trim() ? properties.partId : undefined),
      partType: typeof part?.type === 'string' && part.type.trim() ? part.type : undefined,
      toolCallId:
        (typeof part?.callID === 'string' && part.callID.trim() ? part.callID : undefined) ??
        (typeof part?.callId === 'string' && part.callId.trim() ? part.callId : undefined) ??
        (typeof properties.toolCallId === 'string' && properties.toolCallId.trim() ? properties.toolCallId : undefined),
      deltaBytes: delta ? Buffer.byteLength(delta, 'utf8') : undefined,
      diffCount: diff?.length,
    };
  }
}
