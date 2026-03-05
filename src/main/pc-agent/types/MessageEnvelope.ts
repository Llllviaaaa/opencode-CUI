/**
 * MessageEnvelope — Unified protocol envelope for cross-IDE event streaming.
 *
 * v0 protocol format (matching full_stack_protocol.md):
 *
 * ```json
 * {
 *   "type": "tool_event",
 *   "sessionId": "42",
 *   "event": { ... },              // type-specific payload field
 *   "envelope": {                   // platform metadata
 *     "version": "1.0.0",
 *     "messageId": "uuid",
 *     "timestamp": "ISO-8601",
 *     "source": "OPENCODE",
 *     "agentId": "agent-123",
 *     "sessionId": "42",
 *     "sequenceNumber": 1,
 *     "sequenceScope": "session"
 *   }
 * }
 * ```
 */

/**
 * Source identifier for the message origin.
 */
export type MessageSource = 'OPENCODE' | 'CURSOR' | 'WINDSURF';

/**
 * Envelope metadata attached to every protocol message.
 */
export interface EnvelopeMetadata {
  /** Protocol version (semver format, e.g., "1.0.0") */
  version: string;

  /** Unique message identifier (UUID v4) */
  messageId: string;

  /** ISO 8601 timestamp of message creation */
  timestamp: string;

  /** Source IDE/tool that generated this message */
  source: MessageSource;

  /** Agent connection ID (assigned by gateway on registration) */
  agentId: string;

  /** Session identifier (optional, for session-scoped messages) */
  sessionId?: string;

  /** Sequence number for ordering within a scope */
  sequenceNumber: number;

  /** Scope for sequence numbering: 'session' or 'agent' */
  sequenceScope: 'session' | 'agent';
}

/**
 * Complete message envelope with metadata and type-specific content.
 *
 * The message has a `type` discriminator and an `envelope` for metadata.
 * Additional fields are type-specific (e.g., `event` for tool_event,
 * `error` for tool_error, etc.)
 */
export interface MessageEnvelope<T = unknown> {
  /** Envelope metadata */
  envelope: EnvelopeMetadata;

  /** Message type discriminator (e.g., 'tool_event', 'invoke', 'tool_done') */
  type: string;

  /** Top-level session ID (matches envelope.sessionId) */
  sessionId?: string;

  /** Type-specific payload fields - spread at the top level */
  [key: string]: unknown;
}

/**
 * Type guard to check if a message has an envelope.
 */
export function hasEnvelope(msg: unknown): msg is MessageEnvelope {
  return (
    typeof msg === 'object' &&
    msg !== null &&
    'envelope' in msg &&
    typeof (msg as Record<string, unknown>).envelope === 'object' &&
    'type' in msg
  );
}

/**
 * Extract envelope metadata from a message, or return undefined.
 */
export function getEnvelope(msg: unknown): EnvelopeMetadata | undefined {
  return hasEnvelope(msg) ? msg.envelope : undefined;
}
