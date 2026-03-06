import { BridgeConfig } from '../types';

export interface ConfigValidationError {
  path: string;
  code: string;
  message: string;
}

export class ConfigValidator {
  validate(config: unknown): ConfigValidationError[] {
    const errors: ConfigValidationError[] = [];

    if (!config || typeof config !== 'object') {
      errors.push({ path: '', code: 'INVALID_CONFIG', message: 'Configuration must be an object' });
      return errors;
    }

    const c = config as BridgeConfig;

    if (c.config_version !== 1) {
      errors.push({ path: 'config_version', code: 'INVALID_VERSION', message: 'config_version must be 1' });
    }

    if (typeof c.enabled !== 'boolean') {
      errors.push({ path: 'enabled', code: 'INVALID_TYPE', message: 'enabled must be boolean' });
    }

    if (!c.gateway || typeof c.gateway.url !== 'string' || !/^wss?:\/\//.test(c.gateway.url)) {
      errors.push({ path: 'gateway.url', code: 'INVALID_URL', message: 'gateway.url must start with ws:// or wss://' });
    }

    if (c.enabled !== false) {
      if (!c.auth || typeof c.auth.ak !== 'string' || !c.auth.ak.trim()) {
        errors.push({ path: 'auth.ak', code: 'MISSING_REQUIRED', message: 'auth.ak is required' });
      }

      if (!c.auth || typeof c.auth.sk !== 'string' || !c.auth.sk.trim()) {
        errors.push({ path: 'auth.sk', code: 'MISSING_REQUIRED', message: 'auth.sk is required' });
      }
    }

    if (!c.gateway || !c.gateway.reconnect) {
      errors.push({ path: 'gateway.reconnect', code: 'MISSING_REQUIRED', message: 'gateway.reconnect is required' });
    } else {
      this.validatePositiveInt(c.gateway.reconnect.baseMs, 'gateway.reconnect.baseMs', errors);
      this.validatePositiveInt(c.gateway.reconnect.maxMs, 'gateway.reconnect.maxMs', errors);
    }

    this.validatePositiveInt(c.gateway?.heartbeatIntervalMs, 'gateway.heartbeatIntervalMs', errors);
    this.validatePositiveInt(c.sdk?.timeoutMs, 'sdk.timeoutMs', errors);

    if (!c.events || !Array.isArray(c.events.allowlist) || c.events.allowlist.length === 0) {
      errors.push({ path: 'events.allowlist', code: 'INVALID_TYPE', message: 'events.allowlist must be a non-empty array' });
    }

    return errors;
  }

  private validatePositiveInt(value: unknown, path: string, errors: ConfigValidationError[]): void {
    if (typeof value !== 'number' || !Number.isInteger(value) || value <= 0) {
      errors.push({ path, code: 'INVALID_NUMBER', message: `${path} must be a positive integer` });
    }
  }
}
