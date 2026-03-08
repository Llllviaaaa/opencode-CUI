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

    if (c.enabled !== false) {
      if (!c.auth || typeof c.auth.ak !== 'string' || !c.auth.ak.trim()) {
        errors.push({ path: 'auth.ak', code: 'MISSING_REQUIRED', message: 'auth.ak is required' });
      }

      if (!c.auth || typeof c.auth.sk !== 'string' || !c.auth.sk.trim()) {
        errors.push({ path: 'auth.sk', code: 'MISSING_REQUIRED', message: 'auth.sk is required' });
      }
    }

    if (c.config_version !== undefined && c.config_version !== 1) {
      errors.push({ path: 'config_version', code: 'INVALID_VERSION', message: 'config_version must be 1' });
    }

    if (c.enabled !== undefined && typeof c.enabled !== 'boolean') {
      errors.push({ path: 'enabled', code: 'INVALID_TYPE', message: 'enabled must be boolean' });
    }

    if (c.gateway?.url !== undefined) {
      if (typeof c.gateway.url !== 'string' || !/^wss?:\/\//.test(c.gateway.url)) {
        errors.push({ path: 'gateway.url', code: 'INVALID_URL', message: 'gateway.url must start with ws:// or wss://' });
      }
    }

    if (c.gateway?.reconnect?.baseMs !== undefined) {
      this.validatePositiveInt(c.gateway.reconnect.baseMs, 'gateway.reconnect.baseMs', errors);
    }
    if (c.gateway?.reconnect?.maxMs !== undefined) {
      this.validatePositiveInt(c.gateway.reconnect.maxMs, 'gateway.reconnect.maxMs', errors);
    }
    if (c.gateway?.heartbeatIntervalMs !== undefined) {
      this.validatePositiveInt(c.gateway.heartbeatIntervalMs, 'gateway.heartbeatIntervalMs', errors);
    }
    if (c.sdk?.timeoutMs !== undefined) {
      this.validatePositiveInt(c.sdk.timeoutMs, 'sdk.timeoutMs', errors);
    }

    if (c.sdk && 'baseUrl' in c.sdk) {
      errors.push({ path: 'sdk.baseUrl', code: 'DEPRECATED_FIELD', message: 'sdk.baseUrl is deprecated and should not be used' });
    }

    if (c.events?.allowlist !== undefined) {
      if (!Array.isArray(c.events.allowlist)) {
        errors.push({ path: 'events.allowlist', code: 'INVALID_TYPE', message: 'events.allowlist must be an array' });
      }
    }

    return errors;
  }

  private validatePositiveInt(value: unknown, path: string, errors: ConfigValidationError[]): void {
    if (typeof value !== 'number' || !Number.isInteger(value) || value <= 0) {
      errors.push({ path, code: 'INVALID_NUMBER', message: `${path} must be a positive integer` });
    }
  }
}
