import { randomUUID } from 'crypto';

export interface BridgeConfig {
  enabled: boolean;
  debug?: boolean;
  config_version: number;
  gateway: GatewayConfig;
  sdk: SDKConfig;
  auth: AuthConfig;
  events: EventConfig;
}

export interface ConfigValidationError {
  path: string;
  code: string;
  message: string;
}

export interface GatewayConfig {
  url: string;
  deviceName: string;
  toolType: string;
  toolVersion: string;
  heartbeatIntervalMs: number;
  reconnect: ReconnectConfig;
  ping?: PingConfig;
}

export interface PingConfig {
  intervalMs: number;
  pongTimeoutMs: number;
}

export interface SDKConfig {
  timeoutMs: number;
}

export interface AuthConfig {
  ak: string;
  sk: string;
}

export interface ReconnectConfig {
  baseMs: number;
  maxMs: number;
  exponential: boolean;
}

export interface EventConfig {
  allowlist: string[];
}

export const CONNECTION_STATES = ['DISCONNECTED', 'CONNECTING', 'CONNECTED', 'READY'] as const;

export type ConnectionState = typeof CONNECTION_STATES[number];

export const ERROR_CODES = [
  'GATEWAY_UNREACHABLE',
  'SDK_TIMEOUT',
  'SDK_UNREACHABLE',
  'AGENT_NOT_READY',
  'INVALID_PAYLOAD',
  'UNSUPPORTED_ACTION',
] as const;

export type ErrorCode = typeof ERROR_CODES[number];

export function stateToErrorCode(state: ConnectionState): ErrorCode {
  switch (state) {
    case CONNECTION_STATES[0]:
    case CONNECTION_STATES[1]:
      return 'GATEWAY_UNREACHABLE';
    case CONNECTION_STATES[2]:
    case CONNECTION_STATES[3]:
      return 'AGENT_NOT_READY';
  }
}

export function buildMessageId(): string {
  return randomUUID();
}

export interface MessageBridgePlugin {
  start(): Promise<void>;
  stop(): Promise<void>;
}

export const DEFAULT_CONFIG = {
  heartbeatIntervalMs: 30000,
  reconnectBaseMs: 1000,
  reconnectMaxMs: 30000,
  sdkTimeoutMs: 10000,
  configVersion: 1,
  pongTimeoutMs: 10000,
} as const;

export const AGENT_ID_PREFIX = 'bridge-';
export const PROTOCOL_VERSION = '1.0';
