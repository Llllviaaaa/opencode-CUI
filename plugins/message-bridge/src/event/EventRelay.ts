import { GatewayConnection, StateManager } from '../connection';
import { EventFilter } from './EventFilter';
import { EnvelopeBuilder } from './EnvelopeBuilder';

export interface OpenCodeEvent {
  type: string;
  properties?: Record<string, unknown>;
  sessionId?: string;
  [key: string]: unknown;
}

export interface EventRelayOptions {
  eventFilter?: EventFilter;
  allowlist?: readonly string[];
}

export class EventRelay {
  private readonly eventFilter: EventFilter;
  private subscription: (() => void) | null = null;
  private isRunning = false;

  private currentAgentId: string | null = null;
  private envelopeBuilder: EnvelopeBuilder | null = null;

  constructor(
    private readonly opencode: { event: { subscribe: (listener: (event: OpenCodeEvent) => void) => () => void } },
    private readonly gateway: GatewayConnection,
    private readonly stateManager: StateManager,
    options: EventRelayOptions = {},
  ) {
    this.eventFilter = options.eventFilter ?? new EventFilter(options.allowlist);
  }

  async start(): Promise<void> {
    if (this.isRunning) {
      return;
    }

    this.isRunning = true;
    this.subscription = this.opencode.event.subscribe((event: OpenCodeEvent) => {
      this.handleEvent(event).catch((error) => {
        console.error('event_relay_error', { error: error instanceof Error ? error.message : String(error) });
      });
    });
  }

  stop(): void {
    if (!this.isRunning) {
      return;
    }

    this.isRunning = false;
    if (this.subscription) {
      this.subscription();
      this.subscription = null;
    }
  }

  private async handleEvent(event: OpenCodeEvent): Promise<void> {
    if (!this.stateManager.isReady()) {
      return;
    }

    if (!this.eventFilter.isAllowed(event.type)) {
      console.warn('unsupported_event', { eventType: event.type });
      return;
    }

    const sessionId = this.extractSessionId(event);
    const envelope = this.getEnvelopeBuilder().build(sessionId);

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
