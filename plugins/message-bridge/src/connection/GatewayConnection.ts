import { EventEmitter } from 'events';
import { ConnectionState } from '../types/index.js';
import type { HeartbeatMessage, RegisterMessage } from '../contracts/transport-messages.js';
import type { BridgeLogger } from '../runtime/AppLogger.js';
import type { AkSkAuthPayload } from './AkSkAuth.js';
import { getErrorDetailsForLog, getErrorMessage } from '../utils/error.js';

export interface GatewayConnectionEvents {
  stateChange: (state: ConnectionState) => void;
  message: (message: unknown) => void;
  error: (error: Error) => void;
}

export interface GatewayConnection {
  connect(): Promise<void>;
  disconnect(): void;
  send(message: unknown, logContext?: GatewaySendLogContext): void;
  isConnected(): boolean;
  getState(): ConnectionState;
  on<E extends keyof GatewayConnectionEvents>(event: E, listener: GatewayConnectionEvents[E]): this;
}

export interface GatewaySendLogContext {
  traceId?: string;
  runtimeTraceId?: string;
  bridgeMessageId?: string;
  gatewayMessageId?: string;
  sessionId?: string;
  welinkSessionId?: string;
  toolSessionId?: string;
  source?: string;
  eventType?: string;
  action?: string;
  opencodeMessageId?: string;
  opencodePartId?: string;
  toolCallId?: string;
}

function buildGatewaySendLogExtra(messageType: string, payloadBytes: number, logContext?: GatewaySendLogContext) {
  if (!logContext) {
    return { messageType, payloadBytes };
  }

  const { bridgeMessageId: _bridgeMessageId, ...rest } = logContext;
  return {
    messageType,
    payloadBytes,
    ...rest,
  };
}

export interface GatewayConnectionOptions {
  url: string;
  reconnectBaseMs?: number;
  reconnectMaxMs?: number;
  reconnectExponential?: boolean;
  heartbeatIntervalMs?: number;
  abortSignal?: AbortSignal;
  authPayloadProvider?: () => AkSkAuthPayload;
  registerMessage: RegisterMessage;
  logger?: BridgeLogger;
}

type WsMessageEvent = { data: string | ArrayBuffer | Blob | Uint8Array };
type GatewayControlMessage = RegisterOkMessage | RegisterRejectedMessage;
const GATEWAY_REJECTION_CLOSE_CODES = new Set([4403, 4408, 4409]);

interface RegisterOkMessage {
  type: 'register_ok';
}

interface RegisterRejectedMessage {
  type: 'register_rejected';
  reason?: string;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === 'object';
}

function extractWebSocketErrorDetails(event: unknown): Record<string, unknown> {
  const details: Record<string, unknown> = {};

  if (!isRecord(event)) {
    return {
      ...getErrorDetailsForLog(event),
    };
  }

  const baseError =
    event.error !== undefined && event.error !== event
      ? getErrorDetailsForLog(event.error)
      : getErrorDetailsForLog(event);
  Object.assign(details, baseError);

  if (typeof event.type === 'string') {
    details.eventType = event.type;
  }

  if (!details.errorDetail && typeof event.message === 'string' && event.message.trim()) {
    details.errorDetail = event.message;
  }

  const target = event.target;
  if (isRecord(target) && typeof target.readyState === 'number') {
    details.readyState = target.readyState;
  }

  return details;
}

function encodeBase64Url(value: string): string {
  return Buffer.from(value, 'utf8')
    .toString('base64')
    .replace(/\+/g, '-')
    .replace(/\//g, '_')
    .replace(/=+$/g, '');
}

function buildAuthSubprotocol(payload: AkSkAuthPayload): string {
  return `auth.${encodeBase64Url(JSON.stringify(payload))}`;
}

function isGatewayControlMessage(message: unknown): message is GatewayControlMessage {
  if (!isRecord(message) || typeof message.type !== 'string') {
    return false;
  }
  return message.type === 'register_ok' || message.type === 'register_rejected';
}

function isGatewayRejectedCloseCode(code: number | undefined): boolean {
  return typeof code === 'number' && GATEWAY_REJECTION_CLOSE_CODES.has(code);
}

export class DefaultGatewayConnection extends EventEmitter implements GatewayConnection {
  private ws: WebSocket | null = null;
  private reconnectAttempts = 0;
  private heartbeatTimer: ReturnType<typeof setInterval> | null = null;
  private reconnectTimer: ReturnType<typeof setTimeout> | null = null;
  private manuallyDisconnected = false;
  private state: ConnectionState = 'DISCONNECTED';
  private lastMessageSummary: {
    direction: 'sent' | 'received';
    messageType?: string;
    messageId?: string;
    payloadBytes?: number;
    eventType?: string;
    opencodeMessageId?: string;
  } | null = null;

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
        const authPayload = this.options.authPayloadProvider?.();
        const protocols = authPayload ? [buildAuthSubprotocol(authPayload)] : undefined;
        const ws = protocols ? new WebSocket(url.toString(), protocols) : new WebSocket(url.toString());
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
          finalizeResolve();
        };

        ws.onclose = (event?: CloseEvent) => {
          const rejected = isGatewayRejectedCloseCode(event?.code);
          this.options.logger?.warn('gateway.close', {
            opened,
            manuallyDisconnected: this.manuallyDisconnected,
            aborted: !!this.options.abortSignal?.aborted,
            rejected,
            code: event?.code,
            reason: event?.reason,
            wasClean: event?.wasClean,
            lastMessageDirection: this.lastMessageSummary?.direction,
            lastMessageType: this.lastMessageSummary?.messageType,
            lastMessageId: this.lastMessageSummary?.messageId,
            lastPayloadBytes: this.lastMessageSummary?.payloadBytes,
            lastEventType: this.lastMessageSummary?.eventType,
            lastOpencodeMessageId: this.lastMessageSummary?.opencodeMessageId,
          });
          if (!opened) {
            finalizeReject(new Error('gateway_websocket_closed_before_open'));
          }
          this.teardownTimers();
          this.setState('DISCONNECTED');

          if (rejected) {
            this.options.logger?.warn('gateway.close.rejected', {
              code: event?.code,
              reason: event?.reason,
              rejected: true,
            });
            return;
          }

          if (opened && !this.manuallyDisconnected && !this.options.abortSignal?.aborted) {
            this.attemptReconnect();
          }
        };

        ws.onerror = (event?: unknown) => {
          const error = new Error('gateway_websocket_error');
          const errorDetails = extractWebSocketErrorDetails(event);
          this.options.logger?.error('gateway.error', {
            error: error.message,
            state: this.state,
            ...errorDetails,
          });
          this.emit('error', error);
          if (this.state !== 'DISCONNECTED') {
            this.setState('DISCONNECTED');
          }
          finalizeReject(error);
        };

        ws.onmessage = (event: MessageEvent) => {
          this.handleMessage(event as unknown as WsMessageEvent).catch((error) => {
            this.emit('error', error instanceof Error ? error : new Error(getErrorMessage(error)));
          });
        };
      } catch (error) {
        this.options.logger?.error('gateway.connect.failed', {
          error: getErrorMessage(error),
          ...getErrorDetailsForLog(error),
        });
        finalizeReject(error instanceof Error ? error : new Error(getErrorMessage(error)));
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

  send(message: unknown, logContext?: GatewaySendLogContext): void {
    if (!this.isConnected() || !this.ws) {
      this.options.logger?.warn('gateway.send.rejected_not_connected', {
        state: this.state,
        messageType:
          message && typeof message === 'object' && 'type' in (message as { type?: unknown })
            ? String((message as { type?: unknown }).type ?? '')
            : 'unknown',
      });
      throw new Error('WebSocket is not connected. Cannot send message.');
    }

    const messageType =
      message && typeof message === 'object' && 'type' in (message as { type?: unknown })
        ? String((message as { type?: unknown }).type ?? '')
        : 'unknown';
    const isControlMessage = messageType === 'register' || messageType === 'heartbeat';

    if (this.state !== 'READY' && !isControlMessage) {
      this.options.logger?.warn('gateway.send.rejected_not_ready', {
        state: this.state,
        messageType,
      });
      throw new Error('Gateway connection is not ready. Cannot send business message.');
    }
    const serialized = JSON.stringify(message);
    const payloadBytes = Buffer.byteLength(serialized, 'utf8');
    this.lastMessageSummary = {
      direction: 'sent',
      messageType,
      messageId: logContext?.bridgeMessageId ?? logContext?.gatewayMessageId,
      payloadBytes,
      eventType: logContext?.eventType,
      opencodeMessageId: logContext?.opencodeMessageId,
    };
    this.options.logger?.debug('gateway.send', {
      ...buildGatewaySendLogExtra(messageType, payloadBytes, logContext),
    });
    this.ws.send(serialized);
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
      } catch (error) {
        this.options.logger?.warn('gateway.reconnect.failed', {
          attempt: this.reconnectAttempts,
          error: getErrorMessage(error),
          ...getErrorDetailsForLog(error),
        });
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
    const frameBytes = Buffer.byteLength(text, 'utf8');

    try {
      const message = JSON.parse(text);
      const messageType =
        message && typeof message === 'object' && 'type' in (message as { type?: unknown })
          ? String((message as { type?: unknown }).type ?? '')
          : 'unknown';
      const gatewayMessageId = this.extractGatewayMessageId(message);
      this.lastMessageSummary = {
        direction: 'received',
        messageType,
        messageId: gatewayMessageId,
        payloadBytes: frameBytes,
      };
      this.options.logger?.debug('gateway.message.received', { messageType, frameBytes, gatewayMessageId });
      if (isGatewayControlMessage(message)) {
        this.handleControlMessage(message);
        return;
      }
      if (this.state !== 'READY') {
        this.options.logger?.warn('gateway.message.ignored_not_ready', {
          state: this.state,
          messageType,
          gatewayMessageId,
        });
        return;
      }
      this.emit('message', message);
    } catch {
      this.options.logger?.debug('gateway.message.ignored_non_json', {
        payloadLength: text.length,
        frameBytes,
      });
      // Ignore non-json messages from gateway.
    }
  }

  private extractGatewayMessageId(message: unknown): string | undefined {
    if (!isRecord(message)) {
      return undefined;
    }
    return typeof message.messageId === 'string' ? message.messageId : undefined;
  }

  private handleControlMessage(message: GatewayControlMessage): void {
    if (message.type === 'register_ok') {
      if (this.state === 'READY') {
        this.options.logger?.warn('gateway.register.duplicate_ok');
        return;
      }
      this.setState('READY');
      this.options.logger?.info('gateway.register.accepted');
      this.setupHeartbeat();
      this.options.logger?.info('gateway.ready');
      return;
    }

    const reason = typeof message.reason === 'string' ? message.reason : undefined;
    this.options.logger?.error('gateway.register.rejected', { reason });
    this.manuallyDisconnected = true;
    if (this.ws) {
      this.ws.close();
    }
  }

  private setState(next: ConnectionState): void {
    this.state = next;
    this.emit('stateChange', next);
  }
}
