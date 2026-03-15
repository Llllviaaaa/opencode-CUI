export type { BridgeConfig, ConfigValidationError } from '../types/index.js';
export { ConfigResolver } from './ConfigResolver.js';
export { ConfigValidator } from './ConfigValidator.js';
export { JsoncParser } from './JsoncParser.js';
export { DEFAULT_BRIDGE_CONFIG } from './default-config.js';
export { ConfigValidationAggregateError } from './index.impl.js';

export { loadConfig, validateConfig } from './index.impl.js';
