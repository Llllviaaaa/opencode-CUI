import { homedir } from 'os';
import { dirname, join, resolve } from 'path';
import { promises } from 'fs';
import type { BridgeConfig } from '../types';
import type { BridgeLogger } from '../runtime/AppLogger';
import { DEFAULT_EVENT_ALLOWLIST } from '../contracts/upstream-events';
import { getErrorDetailsForLog, getErrorMessage } from '../utils/error';
import { JsoncParser } from './JsoncParser';
import { DEFAULT_BRIDGE_CONFIG } from './default-config';

const CONFIG_FILE_NAMES = ['message-bridge.jsonc', 'message-bridge.json'] as const;

export class ConfigResolver {
  private readonly jsoncParser: JsoncParser;
  private readonly logger?: BridgeLogger;

  constructor(logger?: BridgeLogger) {
    this.jsoncParser = new JsoncParser();
    this.logger = logger;
  }

  async resolveConfig(workspacePath?: string): Promise<BridgeConfig> {
    let config: Partial<BridgeConfig> = this.mergeConfig({}, DEFAULT_BRIDGE_CONFIG);
    const sources: string[] = ['default'];
    const workspaceRoot = workspacePath ?? process.cwd();
    this.logger?.info('config.resolve.started', { workspacePath: workspaceRoot });

    const userConfigHome = process.env.HOME || homedir();
    const userConfigPath = await this.findFirstExistingPath(
      this.getConfigCandidatePaths(join(userConfigHome, '.config', 'opencode')),
    );
    if (userConfigPath) {
      const userConfig = await this.loadConfigFile(userConfigPath);
      if (userConfig) {
        config = this.mergeConfig(config, userConfig);
        sources.push(`user:${userConfigPath}`);
        this.logger?.info('config.source.loaded', {
          source: 'user',
          path: userConfigPath,
        });
      }
    }

    const projectConfigPath = await this.findProjectConfig(workspaceRoot);
    if (projectConfigPath) {
      const projectConfig = await this.loadConfigFile(projectConfigPath);
      if (projectConfig) {
        config = this.mergeConfig(config, projectConfig);
        sources.push(`project:${projectConfigPath}`);
        this.logger?.info('config.source.loaded', {
          source: 'project',
          path: projectConfigPath,
        });
      }
    }

    const envConfig = this.loadEnvConfig();
    if (Object.keys(envConfig).length > 0) {
      config = this.mergeConfig(config, envConfig);
      sources.push('env');
      this.logger?.info('config.source.loaded', {
        source: 'env',
        overrideCount: Object.keys(envConfig).length,
      });
    }

    const normalized = this.normalizeConfig(config as BridgeConfig);
    this.logger?.info('config.resolve.completed', {
      workspacePath: workspaceRoot,
      sources,
      allowlistSize: normalized.events.allowlist.length,
      debugEnabled: !!normalized.debug,
      projectConfigPath,
    });
    return normalized;
  }

  private async loadConfigFile(filePath: string): Promise<Partial<BridgeConfig> | null> {
    try {
      return await this.jsoncParser.parseFile(filePath);
    } catch (error) {
      if ((error as NodeJS.ErrnoException).code === 'ENOENT') {
        return null;
      }
      this.logger?.error('config.source.load_failed', {
        path: filePath,
        error: getErrorMessage(error),
        ...getErrorDetailsForLog(error),
      });
      throw error;
    }
  }

  private async findProjectConfig(startDir: string): Promise<string | null> {
    const configDirName = '.opencode';
    let current = resolve(startDir);

    while (true) {
      const configPath = await this.findFirstExistingPath(
        this.getConfigCandidatePaths(join(current, configDirName)),
      );
      if (configPath) {
        return configPath;
      }
      const parent = dirname(current);
      if (parent === current) {
        break;
      }
      current = parent;
    }

    return null;
  }

  private getConfigCandidatePaths(configDir: string): string[] {
    return CONFIG_FILE_NAMES.map((fileName) => join(configDir, fileName));
  }

  private async findFirstExistingPath(paths: string[]): Promise<string | null> {
    for (const path of paths) {
      try {
        await promises.access(path);
        return path;
      } catch {
        // Continue to the next candidate.
      }
    }
    return null;
  }

  private loadEnvConfig(): Partial<BridgeConfig> {
    const envConfig: Partial<BridgeConfig> = {};

    if (process.env.BRIDGE_ENABLED !== undefined) {
      envConfig.enabled = process.env.BRIDGE_ENABLED.toLowerCase() === 'true';
    }

    if (process.env.BRIDGE_DEBUG !== undefined) {
      envConfig.debug = process.env.BRIDGE_DEBUG.toLowerCase() === 'true';
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
    if (process.env.BRIDGE_GATEWAY_MAC_ADDRESS) {
      gateway.macAddress = this.substituteEnvVars(process.env.BRIDGE_GATEWAY_MAC_ADDRESS);
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

    if (typeof normalized.gateway.macAddress === 'string') {
      normalized.gateway.macAddress = normalized.gateway.macAddress.trim() || undefined;
    }

    if (!normalized.gateway.toolType) {
      normalized.gateway.toolType = 'OPENCODE';
    } else {
      normalized.gateway.toolType = normalized.gateway.toolType.trim().toUpperCase();
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
