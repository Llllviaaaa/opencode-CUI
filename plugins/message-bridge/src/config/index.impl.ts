import { BridgeConfig, ConfigValidationError } from '../types';
import { ConfigResolver } from './ConfigResolver';
import { ConfigValidator } from './ConfigValidator';

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

/**
 * Load and validate configuration from all sources
 * @param workspacePath Optional workspace path for project config
 * @returns Validated BridgeConfig
 * @throws ConfigValidationAggregateError if validation fails
 */
export async function loadConfig(workspacePath?: string): Promise<BridgeConfig> {
  const resolver = new ConfigResolver();
  const config = await resolver.resolveConfig(workspacePath);
  
  const errors = validateConfig(config);
  if (errors.length > 0) {
    console.error('[message-bridge] Configuration validation failed:');
    errors.forEach((err) => {
      console.error(`  [${err.code}] ${err.path}: ${err.message}`);
    });
    throw new ConfigValidationAggregateError(errors);
  }
  
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