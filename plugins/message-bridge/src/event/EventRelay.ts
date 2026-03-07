import { GatewayConnection, StateManager } from '../connection';
import { EventFilter } from './EventFilter';
import { EnvelopeBuilder } from './EnvelopeBuilder';
import type { BridgeLogger } from '../runtime/AppLogger';

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
        this.logger?.error('event.relay.error', { error: error instanceof Error ? error.message : String(error) });
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
    if (!this.stateManager.isReady()) {
      this.logger?.debug('event.relay.ignored_not_ready', { eventType: event.type });
      return;
    }

    if (!this.eventFilter.isAllowed(event.type)) {
      this.logger?.warn('event.relay.rejected_allowlist', { eventType: event.type });
      return;
    }

    const sessionId = this.extractSessionId(event);
    const envelope = this.getEnvelopeBuilder().build(sessionId);
    this.logger?.debug('event.relay.forwarding', { eventType: event.type, sessionId });

    this.gateway.send({
      type: 'tool_event',
      sessionId,
      event,
      envelope,
    });
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
    const fromProps = event.properties?.sessionId;
    if (typeof fromProps === 'string' && fromProps.trim()) {
      return fromProps;
    }

    if (typeof event.sessionId === 'string' && event.sessionId.trim()) {
      return event.sessionId;
    }

    return undefined;
  }
}
