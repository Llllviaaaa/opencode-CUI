import { EventEmitter } from "node:events";
import type { BridgeLogger } from "../types.js";
import type { AkSkAuthPayload } from "./AkSkAuth.js";
import type { RegisterMessage } from "../contracts/transport.js";

export type ConnectionState = "DISCONNECTED" | "CONNECTING" | "CONNECTED" | "READY";

export interface GatewayConnectionEvents {
  stateChange: (state: ConnectionState) => void;
  message: (message: unknown) => void;
  error: (error: Error) => void;
}

export interface GatewayConnection {
  connect(): Promise<void>;
  disconnect(): void;
  send(message: unknown): void;
  getState(): ConnectionState;
  isConnected(): boolean;
  on<E extends keyof GatewayConnectionEvents>(event: E, listener: GatewayConnectionEvents[E]): this;
}

export interface GatewayConnectionOptions {
  url: string;
  reconnectBaseMs: number;
  reconnectMaxMs: number;
  reconnectExponential: boolean;
  heartbeatIntervalMs: number;
  authPayloadProvider?: () => AkSkAuthPayload;
  registerMessage: RegisterMessage;
  logger: BridgeLogger;
}

interface GatewayControlMessage {
  type: "register_ok" | "register_rejected";
  reason?: string;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === "object";
}

function isGatewayControlMessage(value: unknown): value is GatewayControlMessage {
  return isRecord(value) && (value.type === "register_ok" || value.type === "register_rejected");
}

function encodeBase64Url(value: string): string {
  return Buffer.from(value, "utf8")
    .toString("base64")
    .replace(/\+/g, "-")
    .replace(/\//g, "_")
    .replace(/=+$/g, "");
}

function buildAuthSubprotocol(payload: AkSkAuthPayload): string {
  return `auth.${encodeBase64Url(JSON.stringify(payload))}`;
}

export class DefaultGatewayConnection extends EventEmitter implements GatewayConnection {
  private ws: WebSocket | null = null;
  private heartbeatTimer: ReturnType<typeof setInterval> | null = null;
  private reconnectTimer: ReturnType<typeof setTimeout> | null = null;
  private manuallyDisconnected = false;
  private state: ConnectionState = "DISCONNECTED";
  private reconnectAttempts = 0;

  constructor(private readonly options: GatewayConnectionOptions) {
    super();
  }

  getState(): ConnectionState {
    return this.state;
  }

  isConnected(): boolean {
    return this.state === "CONNECTED" || this.state === "READY";
  }

  async connect(): Promise<void> {
    this.manuallyDisconnected = false;
    this.setState("CONNECTING");

    return new Promise((resolve, reject) => {
      const authPayload = this.options.authPayloadProvider?.();
      const protocols = authPayload ? [buildAuthSubprotocol(authPayload)] : undefined;
      const ws = protocols ? new WebSocket(this.options.url, protocols) : new WebSocket(this.options.url);
      this.ws = ws;

      ws.onopen = () => {
        this.options.logger.info("gateway.open", { url: this.options.url });
        this.setState("CONNECTED");
        this.send(this.options.registerMessage);
        resolve();
      };

      ws.onmessage = async (event) => {
        const data = await this.decodeMessageData(event.data);
        if (data === null) {
          return;
        }

        let parsed: unknown;
        try {
          parsed = JSON.parse(data);
        } catch {
          return;
        }

        if (isGatewayControlMessage(parsed)) {
          if (parsed.type === "register_ok") {
            this.setState("READY");
            this.startHeartbeat();
            return;
          }

          const error = new Error(parsed.reason || "gateway_register_rejected");
          this.options.logger.error("gateway.register.rejected", { reason: parsed.reason });
          this.emit("error", error);
          reject(error);
          return;
        }

        this.emit("message", parsed);
      };

      ws.onerror = () => {
        const error = new Error("gateway_websocket_error");
        this.options.logger.error("gateway.error");
        this.emit("error", error);
      };

      ws.onclose = () => {
        this.stopHeartbeat();
        this.ws = null;
        this.setState("DISCONNECTED");
        if (!this.manuallyDisconnected) {
          this.scheduleReconnect();
        }
      };
    });
  }

  disconnect(): void {
    this.manuallyDisconnected = true;
    if (this.reconnectTimer) {
      clearTimeout(this.reconnectTimer);
      this.reconnectTimer = null;
    }
    this.stopHeartbeat();
    this.ws?.close();
    this.ws = null;
    this.setState("DISCONNECTED");
  }

  send(message: unknown): void {
    if (!this.ws || this.ws.readyState !== WebSocket.OPEN) {
      throw new Error("gateway_not_connected");
    }
    this.ws.send(JSON.stringify(message));
  }

  private setState(state: ConnectionState): void {
    this.state = state;
    this.emit("stateChange", state);
  }

  private startHeartbeat(): void {
    this.stopHeartbeat();
    this.heartbeatTimer = setInterval(() => {
      if (!this.ws || this.ws.readyState !== WebSocket.OPEN) {
        return;
      }
      this.send({
        type: "heartbeat",
        timestamp: new Date().toISOString(),
      });
    }, this.options.heartbeatIntervalMs);
  }

  private stopHeartbeat(): void {
    if (this.heartbeatTimer) {
      clearInterval(this.heartbeatTimer);
      this.heartbeatTimer = null;
    }
  }

  private scheduleReconnect(): void {
    if (this.reconnectTimer) {
      return;
    }

    const delay = this.options.reconnectExponential
      ? Math.min(
          this.options.reconnectBaseMs * (2 ** this.reconnectAttempts),
          this.options.reconnectMaxMs,
        )
      : this.options.reconnectBaseMs;

    this.reconnectAttempts += 1;
    this.reconnectTimer = setTimeout(() => {
      this.reconnectTimer = null;
      this.connect().catch((error) => {
        this.options.logger.warn("gateway.reconnect.failed", {
          error: error instanceof Error ? error.message : String(error),
        });
      });
    }, delay);
  }

  private async decodeMessageData(data: string | Blob | ArrayBuffer | ArrayBufferView): Promise<string | null> {
    if (typeof data === "string") {
      return data;
    }
    if (data instanceof Blob) {
      return await data.text();
    }
    if (data instanceof ArrayBuffer) {
      return Buffer.from(data).toString("utf8");
    }
    if (ArrayBuffer.isView(data)) {
      return Buffer.from(data.buffer, data.byteOffset, data.byteLength).toString("utf8");
    }
    return null;
  }
}
