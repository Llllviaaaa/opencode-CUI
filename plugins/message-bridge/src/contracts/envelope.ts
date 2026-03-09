export type MessageSource = 'message-bridge' | 'OPENCODE' | 'CURSOR' | 'WINDSURF';

export interface Envelope {
  version: string;
  messageId: string;
  timestamp: number | string;
  source: MessageSource;
  agentId: string;
  sessionId?: string;
  sequenceNumber: number;
  sequenceScope: 'session' | 'global';
}

export function hasEnvelope(message: unknown): message is { envelope: Envelope } {
  return (
    typeof message === 'object' &&
    message !== null &&
    'envelope' in message &&
    typeof (message as { envelope: unknown }).envelope === 'object'
  );
}
