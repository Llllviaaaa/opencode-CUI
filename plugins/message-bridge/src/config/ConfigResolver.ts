import { homedir } from 'os';
import { join } from 'path';
import { BridgeConfig, DEFAULT_EVENT_ALLOWLIST } from '../types';
import { JsoncParser } from './JsoncParser';

export class ConfigResolver {
  private readonly jsoncParser: JsoncParser;

  constructor() {
    this.jsoncParser = new JsoncParser();
  }

  async resolveConfig(workspacePath?: string): Promise<BridgeConfig> {
    const defaultConfig: BridgeConfig = {
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
    };

    let config: Partial<BridgeConfig> = { ...defaultConfig };
    const sources: string[] = ['default'];

    const userConfigPath = join(homedir(), '.config', 'opencode', 'message-bridge.jsonc');
    const userConfig = await this.loadConfigFile(userConfigPath);
    if (userConfig) {
      config = this.mergeConfig(config, userConfig);
      sources.push(`user:${userConfigPath}`);
    }

    const workspaceRoot = workspacePath ?? process.cwd();
    const projectConfigPath = join(workspaceRoot, '.opencode', 'message-bridge.jsonc');
    const projectConfig = await this.loadConfigFile(projectConfigPath);
    if (projectConfig) {
      config = this.mergeConfig(config, projectConfig);
      sources.push(`project:${projectConfigPath}`);
    }

    const envConfig = this.loadEnvConfig();
    if (Object.keys(envConfig).length > 0) {
      config = this.mergeConfig(config, envConfig);
      sources.push('env');
    }

    console.log(`[message-bridge] Config sources: ${sources.join(' -> ')}`);

    return this.normalizeConfig(config as BridgeConfig);
  }

  private async loadConfigFile(filePath: string): Promise<Partial<BridgeConfig> | null> {
    try {
      return await this.jsoncParser.parseFile(filePath);
    } catch (error) {
      if ((error as NodeJS.ErrnoException).code === 'ENOENT') {
        return null;
      }
      throw error;
    }
  }

  private loadEnvConfig(): Partial<BridgeConfig> {
    const envConfig: Partial<BridgeConfig> = {};

    if (process.env.BRIDGE_ENABLED !== undefined) {
      envConfig.enabled = process.env.BRIDGE_ENABLED.toLowerCase() === 'true';
    }

    if (process.env.BRIDGE_CONFIG_VERSION !== undefined) {
      envConfig.config_version = parseInt(process.env.BRIDGE_CONFIG_VERSION, 10);
    }

    const gateway: Record<string, unknown> = {};
    if (process.env.BRIDGE_GATEWAY_URL) {
      gateway.url = this.substituteEnvVars(process.env.BRIDGE_GATEWAY_URL);
    }
    if (process.env.BRIDGE_GATEWAY_DEVICE_NAME) {
      gateway.deviceName = this.substituteEnvVars(process.env.BRIDGE_GATEWAY_DEVICE_NAME);
    }
    if (process.env.BRIDGE_GATEWAY_TOOL_TYPE) {
      gateway.toolType = this.substituteEnvVars(process.env.BRIDGE_GATEWAY_TOOL_TYPE);
    }
    if (process.env.BRIDGE_GATEWAY_TOOL_VERSION) {
      gateway.toolVersion = this.substituteEnvVars(process.env.BRIDGE_GATEWAY_TOOL_VERSION);
    }

    const reconnect: Record<string, unknown> = {};
    if (process.env.BRIDGE_GATEWAY_RECONNECT_BASE_MS) reconnect.baseMs = parseInt(process.env.BRIDGE_GATEWAY_RECONNECT_BASE_MS, 10);
    if (process.env.BRIDGE_GATEWAY_RECONNECT_MAX_MS) reconnect.maxMs = parseInt(process.env.BRIDGE_GATEWAY_RECONNECT_MAX_MS, 10);
    if (process.env.BRIDGE_GATEWAY_RECONNECT_EXPONENTIAL !== undefined) reconnect.exponential = process.env.BRIDGE_GATEWAY_RECONNECT_EXPONENTIAL.toLowerCase() === 'true';
    if (Object.keys(reconnect).length > 0) gateway.reconnect = reconnect;

    const hb = process.env.BRIDGE_GATEWAY_HEARTBEAT_INTERVAL_MS ?? process.env.BRIDGE_EVENT_HEARTBEAT_INTERVAL_MS;
    if (hb) gateway.heartbeatIntervalMs = parseInt(hb, 10);

    const ping: Record<string, unknown> = {};
    if (process.env.BRIDGE_GATEWAY_PING_INTERVAL_MS) {
      ping.intervalMs = parseInt(process.env.BRIDGE_GATEWAY_PING_INTERVAL_MS, 10);
    }
    if (process.env.BRIDGE_GATEWAY_PONG_TIMEOUT_MS) {
      ping.pongTimeoutMs = parseInt(process.env.BRIDGE_GATEWAY_PONG_TIMEOUT_MS, 10);
    }
    if (Object.keys(ping).length > 0) gateway.ping = ping;

    if (Object.keys(gateway).length > 0) {
      envConfig.gateway = gateway as unknown as BridgeConfig['gateway'];
    }

    const auth: Record<string, unknown> = {};
    if (process.env.BRIDGE_AUTH_AK || process.env.BRIDGE_AK) {
      auth.ak = this.substituteEnvVars(process.env.BRIDGE_AUTH_AK ?? process.env.BRIDGE_AK ?? '');
    }
    if (process.env.BRIDGE_AUTH_SK || process.env.BRIDGE_SK) {
      auth.sk = this.substituteEnvVars(process.env.BRIDGE_AUTH_SK ?? process.env.BRIDGE_SK ?? '');
    }
    if (Object.keys(auth).length > 0) {
      envConfig.auth = auth as unknown as BridgeConfig['auth'];
    }

    const sdk: Record<string, unknown> = {};
    if (process.env.BRIDGE_SDK_TIMEOUT_MS) {
      sdk.timeoutMs = parseInt(process.env.BRIDGE_SDK_TIMEOUT_MS, 10);
    }
    if (Object.keys(sdk).length > 0) {
      envConfig.sdk = sdk as unknown as BridgeConfig['sdk'];
    }

    if (process.env.BRIDGE_EVENTS_ALLOWLIST) {
      envConfig.events = {
        allowlist: process.env.BRIDGE_EVENTS_ALLOWLIST.split(',').map((item) => this.substituteEnvVars(item.trim())),
      };
    }

    return envConfig;
  }

  private substituteEnvVars(value: string): string {
    return value.replace(/\$\{([^}]+)\}/g, (match, varName) => process.env[varName] || match);
  }

  private normalizeConfig(config: BridgeConfig): BridgeConfig {
    const normalized = { ...config };

    if (!normalized.gateway) {
      normalized.gateway = {} as BridgeConfig['gateway'];
    }

    if (!normalized.gateway.url) {
      normalized.gateway.url = 'ws://localhost:8081/ws/agent';
    }

    if (!normalized.gateway.deviceName) {
      normalized.gateway.deviceName = 'Local Machine';
    }

    if (!normalized.gateway.toolType) {
      normalized.gateway.toolType = 'opencode';
    }

    if (!normalized.gateway.toolVersion) {
      normalized.gateway.toolVersion = '1.0.0';
    }

    if (!normalized.gateway.heartbeatIntervalMs) {
      normalized.gateway.heartbeatIntervalMs = 30000;
    }

    if (!normalized.gateway.reconnect) {
      normalized.gateway.reconnect = {
        baseMs: 1000,
        maxMs: 30000,
        exponential: true,
      };
    } else {
      if (!normalized.gateway.reconnect.baseMs) {
        normalized.gateway.reconnect.baseMs = 1000;
      }
      if (!normalized.gateway.reconnect.maxMs) {
        normalized.gateway.reconnect.maxMs = 30000;
      }
      if (normalized.gateway.reconnect.exponential === undefined) {
        normalized.gateway.reconnect.exponential = true;
      }
    }

    if (!normalized.gateway.ping) {
      normalized.gateway.ping = {
        intervalMs: 30000,
        pongTimeoutMs: 10000,
      };
    }

    if (!normalized.sdk) {
      normalized.sdk = { timeoutMs: 10000 };
    } else if (!normalized.sdk.timeoutMs) {
      normalized.sdk.timeoutMs = 10000;
    }

    if (!normalized.auth) {
      normalized.auth = { ak: '', sk: '' };
    }

    if (!normalized.events || !normalized.events.allowlist || normalized.events.allowlist.length === 0) {
      normalized.events = {
        allowlist: [...DEFAULT_EVENT_ALLOWLIST],
      };
    }

    if (!normalized.config_version) {
      normalized.config_version = 1;
    }

    if (normalized.enabled === undefined) {
      normalized.enabled = true;
    }

    return normalized;
  }

  private mergeConfig(target: unknown, source: unknown): any {
    if (typeof target !== 'object' || typeof source !== 'object' || target === null || source === null) {
      return source ?? target;
    }

    const result = { ...(target as Record<string, unknown>) };
    for (const key of Object.keys(source as Record<string, unknown>)) {
      const src = (source as Record<string, unknown>)[key];
      const dst = result[key];

      if (typeof src === 'object' && src !== null && !Array.isArray(src)) {
        result[key] = this.mergeConfig(dst ?? {}, src);
      } else if (Array.isArray(src)) {
        result[key] = [...src];
      } else {
        result[key] = src;
      }
    }

    return result;
  }
}
