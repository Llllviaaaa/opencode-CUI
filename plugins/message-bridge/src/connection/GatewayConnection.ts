import { EventEmitter } from 'events';
import { ConnectionState, HeartbeatMessage, RegisterMessage } from '../types';
import type { BridgeLogger } from '../runtime/AppLogger';

export interface GatewayConnectionEvents {
  stateChange: (state: ConnectionState) => void;
  message: (message: unknown) => void;
  error: (error: Error) => void;
}

export interface GatewayConnection {
  connect(): Promise<void>;
  disconnect(): void;
  send(message: unknown): void;
  isConnected(): boolean;
  getState(): ConnectionState;
  on<E extends keyof GatewayConnectionEvents>(event: E, listener: GatewayConnectionEvents[E]): this;
}

export interface GatewayConnectionOptions {
  url: string;
  reconnectBaseMs?: number;
  reconnectMaxMs?: number;
  reconnectExponential?: boolean;
  heartbeatIntervalMs?: number;
  pongTimeoutMs?: number;
  abortSignal?: AbortSignal;
  queryParamsProvider?: () => URLSearchParams;
  registerMessage: RegisterMessage;
  logger?: BridgeLogger;
}

type WsMessageEvent = { data: string | ArrayBuffer | Blob | Uint8Array };

export class DefaultGatewayConnection extends EventEmitter implements GatewayConnection {
  private ws: WebSocket | null = null;
  private reconnectAttempts = 0;
  private heartbeatTimer: ReturnType<typeof setInterval> | null = null;
  private reconnectTimer: ReturnType<typeof setTimeout> | null = null;
  private manuallyDisconnected = false;
  private state: ConnectionState = 'DISCONNECTED';

  constructor(private readonly options: GatewayConnectionOptions) {
    super();
  }

  async connect(): Promise<void> {
    this.options.logger?.info('gateway.connect.started', { url: this.options.url, state: this.state });
    if (this.options.abortSignal?.aborted) {
      this.manuallyDisconnected = true;
      this.setState('DISCONNECTED');
      this.options.logger?.warn('gateway.connect.aborted_precheck');
      throw new Error('gateway_connection_aborted');
    }

    this.setState('CONNECTING');

    return new Promise((resolve, reject) => {
      let settled = false;
      let opened = false;

      const finalizeResolve = () => {
        if (settled) {
          return;
        }
        settled = true;
        cleanupAbortListener();
        resolve();
      };

      const finalizeReject = (error: Error) => {
        if (settled) {
          return;
        }
        settled = true;
        cleanupAbortListener();
        reject(error);
      };

      const abortHandler = () => {
        this.manuallyDisconnected = true;
        if (this.ws) {
          this.ws.close();
          this.ws = null;
        }
        this.teardownTimers();
        this.setState('DISCONNECTED');
        this.options.logger?.warn('gateway.connect.aborted');
        finalizeReject(new Error('gateway_connection_aborted'));
      };

      const cleanupAbortListener = () => {
        this.options.abortSignal?.removeEventListener('abort', abortHandler);
      };

      if (this.options.abortSignal) {
        this.options.abortSignal.addEventListener('abort', abortHandler, { once: true });
      }

      try {
        const url = new URL(this.options.url);
        const queryParams = this.options.queryParamsProvider?.();
        if (queryParams) {
          for (const [key, value] of queryParams.entries()) {
            url.searchParams.set(key, value);
          }
        }

        const ws = new WebSocket(url.toString());
        this.ws = ws;
        this.manuallyDisconnected = false;

        ws.onopen = () => {
          opened = true;
          this.reconnectAttempts = 0;
          this.options.logger?.info('gateway.open');
          this.setState('CONNECTED');

          this.send(this.options.registerMessage);
          this.options.logger?.info('gateway.register.sent', {
            toolType: this.options.registerMessage.toolType,
            toolVersion: this.options.registerMessage.toolVersion,
          });
          this.setState('READY');
          this.options.logger?.info('gateway.ready');
          this.setupHeartbeat();
          finalizeResolve();
        };

        ws.onclose = () => {
          this.options.logger?.warn('gateway.close', {
            opened,
            manuallyDisconnected: this.manuallyDisconnected,
            aborted: !!this.options.abortSignal?.aborted,
          });
          if (!opened) {
            finalizeReject(new Error('gateway_websocket_closed_before_open'));
          }
          this.teardownTimers();
          this.setState('DISCONNECTED');

          if (opened && !this.manuallyDisconnected && !this.options.abortSignal?.aborted) {
            this.attemptReconnect();
          }
        };

        ws.onerror = () => {
          const error = new Error('gateway_websocket_error');
          this.options.logger?.error('gateway.error', { error: error.message });
          this.emit('error', error);
          if (this.state !== 'DISCONNECTED') {
            this.setState('DISCONNECTED');
          }
          finalizeReject(error);
        };

        ws.onmessage = (event: MessageEvent) => {
          this.handleMessage(event as unknown as WsMessageEvent).catch((error) => {
            this.emit('error', error instanceof Error ? error : new Error(String(error)));
          });
        };
      } catch (error) {
        this.options.logger?.error('gateway.connect.failed', {
          error: error instanceof Error ? error.message : String(error),
        });
        finalizeReject(error as Error);
      }
    });
  }

  disconnect(): void {
    this.options.logger?.info('gateway.disconnect.requested', { state: this.state });
    this.manuallyDisconnected = true;

    if (this.reconnectTimer) {
      clearTimeout(this.reconnectTimer);
      this.reconnectTimer = null;
    }

    if (this.ws) {
      this.ws.close();
      this.ws = null;
    }

    this.teardownTimers();
    this.setState('DISCONNECTED');
  }

  send(message: unknown): void {
    if (!this.isConnected() || !this.ws) {
      this.options.logger?.warn('gateway.send.rejected_not_connected');
      throw new Error('WebSocket is not connected. Cannot send message.');
    }

    const messageType =
      message && typeof message === 'object' && 'type' in (message as { type?: unknown })
        ? String((message as { type?: unknown }).type ?? '')
        : 'unknown';
    this.options.logger?.debug('gateway.send', { messageType });
    this.ws.send(JSON.stringify(message));
  }

  isConnected(): boolean {
    return this.ws !== null && this.ws.readyState === WebSocket.OPEN;
  }

  getState(): ConnectionState {
    return this.state;
  }

  private setupHeartbeat(): void {
    this.teardownTimers();

    const heartbeatIntervalMs = this.options.heartbeatIntervalMs ?? 30000;

    this.heartbeatTimer = setInterval(() => {
      if (!this.isConnected() || !this.ws) {
        return;
      }

      const heartbeat: HeartbeatMessage = {
        type: 'heartbeat',
        timestamp: new Date().toISOString(),
      };
      this.ws.send(JSON.stringify(heartbeat));
      this.options.logger?.debug('gateway.heartbeat.sent');
    }, heartbeatIntervalMs);
  }

  private teardownTimers(): void {
    if (this.heartbeatTimer) {
      clearInterval(this.heartbeatTimer);
      this.heartbeatTimer = null;
    }
  }

  private attemptReconnect(): void {
    if (this.options.abortSignal?.aborted) {
      return;
    }

    this.reconnectAttempts += 1;

    const base = this.options.reconnectBaseMs ?? 1000;
    const cap = this.options.reconnectMaxMs ?? 30000;
    const exp = this.options.reconnectExponential ?? true;

    const delay = exp
      ? Math.min(base * Math.pow(2, this.reconnectAttempts - 1), cap)
      : Math.min(base, cap);
    this.options.logger?.warn('gateway.reconnect.scheduled', {
      attempt: this.reconnectAttempts,
      delayMs: delay,
    });

    this.reconnectTimer = setTimeout(async () => {
      if (this.manuallyDisconnected || this.options.abortSignal?.aborted) {
        return;
      }

      try {
        this.options.logger?.info('gateway.reconnect.attempt', {
          attempt: this.reconnectAttempts,
        });
        await this.connect();
      } catch {
        if (!this.manuallyDisconnected) {
          this.attemptReconnect();
        }
      }
    }, delay);
  }

  private async handleMessage(event: WsMessageEvent): Promise<void> {
    let text: string;

    if (typeof event.data === 'string') {
      text = event.data;
    } else if (event.data instanceof Uint8Array) {
      text = new TextDecoder().decode(event.data);
    } else if (event.data instanceof ArrayBuffer) {
      text = new TextDecoder().decode(new Uint8Array(event.data));
    } else {
      text = await (event.data as Blob).text();
    }

    try {
      const message = JSON.parse(text);
      const messageType =
        message && typeof message === 'object' && 'type' in (message as { type?: unknown })
          ? String((message as { type?: unknown }).type ?? '')
          : 'unknown';
      this.options.logger?.debug('gateway.message.received', { messageType });
      this.emit('message', message);
    } catch {
      this.options.logger?.debug('gateway.message.ignored_non_json');
      // Ignore non-json messages from gateway.
    }
  }

  private setState(next: ConnectionState): void {
    this.state = next;
    this.emit('stateChange', next);
  }
}
