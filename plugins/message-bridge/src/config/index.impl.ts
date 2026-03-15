import type { BridgeConfig, ConfigValidationError } from '../types/index.js';
import { AppLogger, type BridgeLogger } from '../runtime/AppLogger.js';
import { ConfigResolver } from './ConfigResolver.js';
import { ConfigValidator } from './ConfigValidator.js';

/**
 * Configuration validation error that aggregates multiple validation errors
 */
export class ConfigValidationAggregateError extends Error {
  public readonly errors: ConfigValidationError[];

  constructor(errors: ConfigValidationError[]) {
    super('Configuration validation failed');
    this.errors = errors;
    // Maintain proper prototype chain
    Object.setPrototypeOf(this, ConfigValidationAggregateError.prototype);
  }
}

function createConsoleBackedLogger(): BridgeLogger {
  return new AppLogger(
    {},
    { component: 'config' },
    undefined,
    (options) => {
      const body = options?.body;
      if (!body) {
        return;
      }

      const prefix = `[message-bridge] ${body.message}`;
      const extra = body.extra ?? {};
      switch (body.level) {
        case 'error':
          console.error(prefix, extra);
          break;
        case 'warn':
          console.warn(prefix, extra);
          break;
        case 'info':
          console.info(prefix, extra);
          break;
        default:
          console.debug(prefix, extra);
          break;
      }
    },
  );
}

/**
 * Load and validate configuration from all sources
 * @param workspacePath Optional workspace path for project config
 * @returns Validated BridgeConfig
 * @throws ConfigValidationAggregateError if validation fails
 */
export async function loadConfig(workspacePath?: string, logger?: BridgeLogger): Promise<BridgeConfig> {
  const configLogger = logger?.child({ component: 'config' }) ?? createConsoleBackedLogger();
  const resolver = new ConfigResolver(configLogger);
  const config = await resolver.resolveConfig(workspacePath);

  const errors = validateConfig(config);
  if (errors.length > 0) {
    configLogger.error('config.validation.failed', {
      workspacePath,
      errorCount: errors.length,
      errors: errors.map((err) => ({
        code: err.code,
        path: err.path,
        message: err.message,
      })),
    });
    throw new ConfigValidationAggregateError(errors);
  }

  configLogger.info('config.validation.passed', {
    workspacePath,
    configVersion: config.config_version,
    enabled: config.enabled,
  });
  return config;
}

/**
 * Validate a BridgeConfig object
 * @param config Configuration to validate
 * @returns Array of validation errors, empty if valid
 */
export function validateConfig(config: unknown): ConfigValidationError[] {
  const validator = new ConfigValidator();
  return validator.validate(config);
}
