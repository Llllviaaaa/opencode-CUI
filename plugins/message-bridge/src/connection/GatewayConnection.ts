import { EventEmitter } from 'events';
import WebSocket from 'ws';
import { ConnectionState, HeartbeatMessage, RegisterMessage } from '../types';

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
  queryParams?: URLSearchParams;
  registerMessage: RegisterMessage;
}

export class DefaultGatewayConnection extends EventEmitter implements GatewayConnection {
  private ws: WebSocket | null = null;
  private reconnectAttempts = 0;
  private heartbeatTimer: NodeJS.Timeout | null = null;
  private pongTimeoutTimer: NodeJS.Timeout | null = null;
  private manuallyDisconnected = false;
  private state: ConnectionState = 'DISCONNECTED';

  constructor(private readonly options: GatewayConnectionOptions) {
    super();
  }

  async connect(): Promise<void> {
    this.setState('CONNECTING');

    return new Promise((resolve, reject) => {
      try {
        const url = new URL(this.options.url);
        if (this.options.queryParams) {
          for (const [key, value] of this.options.queryParams.entries()) {
            url.searchParams.set(key, value);
          }
        }

        this.ws = new WebSocket(url.toString());
        this.manuallyDisconnected = false;

        this.ws.on('open', () => {
          this.reconnectAttempts = 0;
          this.setState('CONNECTED');

          this.send(this.options.registerMessage);
          // PRD: gateway has no explicit register ack, send register then enter READY.
          this.setState('READY');

          this.setupHeartbeat();
          resolve();
        });

        this.ws.on('close', () => {
          this.teardownTimers();
          this.setState('DISCONNECTED');

          if (!this.manuallyDisconnected) {
            this.attemptReconnect();
          }
        });

        this.ws.on('error', (error) => {
          this.emit('error', error);
          if (this.state !== 'DISCONNECTED') {
            this.setState('DISCONNECTED');
          }
          reject(error);
        });

        this.ws.on('message', (data) => {
          try {
            const message = JSON.parse(data.toString());
            this.emit('message', message);
          } catch {
            // Ignore non-json messages from gateway.
          }
        });

        this.ws.on('pong', () => {
          if (this.pongTimeoutTimer) {
            clearTimeout(this.pongTimeoutTimer);
            this.pongTimeoutTimer = null;
          }
        });
      } catch (error) {
        reject(error as Error);
      }
    });
  }

  disconnect(): void {
    this.manuallyDisconnected = true;

    if (this.ws) {
      this.ws.close();
      this.ws = null;
    }

    this.teardownTimers();
    this.setState('DISCONNECTED');
  }

  send(message: unknown): void {
    if (!this.isConnected() || !this.ws) {
      throw new Error('WebSocket is not connected. Cannot send message.');
    }

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
    const pongTimeoutMs = this.options.pongTimeoutMs ?? 10000;

    this.heartbeatTimer = setInterval(() => {
      if (!this.isConnected() || !this.ws) {
        return;
      }

      const heartbeat: HeartbeatMessage = {
        type: 'heartbeat',
        timestamp: new Date().toISOString(),
      };
      this.ws.send(JSON.stringify(heartbeat));
      this.ws.ping('heartbeat');

      if (this.pongTimeoutTimer) {
        clearTimeout(this.pongTimeoutTimer);
      }

      this.pongTimeoutTimer = setTimeout(() => {
        if (this.ws && this.ws.readyState === WebSocket.OPEN) {
          this.ws.terminate();
        }
      }, pongTimeoutMs);
    }, heartbeatIntervalMs);
  }

  private teardownTimers(): void {
    if (this.heartbeatTimer) {
      clearInterval(this.heartbeatTimer);
      this.heartbeatTimer = null;
    }

    if (this.pongTimeoutTimer) {
      clearTimeout(this.pongTimeoutTimer);
      this.pongTimeoutTimer = null;
    }
  }

  private attemptReconnect(): void {
    this.reconnectAttempts += 1;

    const base = this.options.reconnectBaseMs ?? 1000;
    const cap = this.options.reconnectMaxMs ?? 30000;
    const exp = this.options.reconnectExponential ?? true;

    const delay = exp
      ? Math.min(base * Math.pow(2, this.reconnectAttempts - 1), cap)
      : Math.min(base, cap);

    setTimeout(async () => {
      if (this.manuallyDisconnected) {
        return;
      }

      try {
        await this.connect();
      } catch {
        if (!this.manuallyDisconnected) {
          this.attemptReconnect();
        }
      }
    }, delay);
  }

  private setState(next: ConnectionState): void {
    this.state = next;
    this.emit('stateChange', next);
  }
}
