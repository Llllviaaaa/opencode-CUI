export { EventFilter } from './EventFilter';
/**
 * @deprecated Historical envelope-based relay exports. The active plugin path
 * uses BridgeRuntime.handleEvent() and flat tool_event messages.
 */
export { EnvelopeBuilder, type Envelope } from './EnvelopeBuilder';
/**
 * @deprecated Historical envelope-based relay. Kept only for compatibility
 * with legacy tests and internal references.
 */
export { EventRelay, type EventRelayOptions } from './EventRelay';
