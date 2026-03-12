export interface MessageBridgeReconnectConfig {
  baseMs: number;
  maxMs: number;
  exponential: boolean;
}

export interface MessageBridgeGatewayConfig {
  url: string;
  toolType: string;
  toolVersion: string;
  deviceName: string;
  macAddress?: string;
  heartbeatIntervalMs: number;
  reconnect: MessageBridgeReconnectConfig;
}

export interface MessageBridgeAuthConfig {
  ak: string;
  sk: string;
}

export interface MessageBridgeAccountConfig {
  enabled: boolean;
  name?: string;
  gateway: MessageBridgeGatewayConfig;
  auth: MessageBridgeAuthConfig;
  agentIdPrefix: string;
  runTimeoutMs: number;
}

export interface MessageBridgeResolvedAccount extends MessageBridgeAccountConfig {
  accountId: string;
}

export interface MessageBridgeSessionRecord {
  toolSessionId: string;
  sessionKey: string;
  welinkSessionId?: string;
}

export interface MessageBridgeStatusSnapshot {
  accountId: string;
  running: boolean;
  connected: boolean;
  lastStartAt: number | null;
  lastStopAt: number | null;
  lastError: string | null;
}

export type BridgeLogger = {
  debug?: (message: string, meta?: Record<string, unknown>) => void;
  info: (message: string, meta?: Record<string, unknown>) => void;
  warn: (message: string, meta?: Record<string, unknown>) => void;
  error: (message: string, meta?: Record<string, unknown>) => void;
};
