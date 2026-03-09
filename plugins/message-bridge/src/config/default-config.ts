import { DEFAULT_EVENT_ALLOWLIST } from '../contracts/upstream-events';
import type { BridgeConfig } from '../types';

export const DEFAULT_BRIDGE_CONFIG = {
  enabled: true,
  config_version: 1,
  gateway: {
    url: 'ws://localhost:8081/ws/agent',
    toolType: 'opencode',
    toolVersion: '1.0.0',
    deviceName: 'Local Machine',
    heartbeatIntervalMs: 30000,
    reconnect: {
      baseMs: 1000,
      maxMs: 30000,
      exponential: true,
    },
    ping: {
      intervalMs: 30000,
      pongTimeoutMs: 10000,
    },
  },
  sdk: {
    timeoutMs: 10000,
  },
  auth: {
    ak: '',
    sk: '',
  },
  events: {
    allowlist: [...DEFAULT_EVENT_ALLOWLIST],
  },
} satisfies BridgeConfig;
