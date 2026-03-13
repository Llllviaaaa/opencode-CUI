import { DEFAULT_EVENT_ALLOWLIST } from '../contracts/upstream-events';
import type { BridgeConfig } from '../types';

export const DEFAULT_BRIDGE_CONFIG = {
  enabled: true,
  config_version: 1,
  gateway: {
    url: 'ws://localhost:8081/ws/agent',
    channel: 'opencode',
    heartbeatIntervalMs: 30000,
    reconnect: {
      baseMs: 1000,
      maxMs: 30000,
      exponential: true,
    },
    ping: {
      intervalMs: 30000,
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
