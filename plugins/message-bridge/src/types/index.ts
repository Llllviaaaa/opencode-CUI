import { randomUUID } from 'crypto';

// ---------------------------------------------------------------------------
// Configuration Types
// ---------------------------------------------------------------------------

export interface BridgeConfig {
  enabled: boolean;
  debug?: boolean;
  config_version: number;
  gateway: GatewayConfig;
  sdk: SDKConfig;
  auth: AuthConfig;

  // Canonical config block
  events: EventConfig;
}

export interface ConfigValidationError {
  path: string;
  code: string;
  message: string;
}

export interface GatewayConfig {
  url: string;
  deviceName: string;
  toolType: string;
  toolVersion: string;
  heartbeatIntervalMs: number;
  reconnect: ReconnectConfig;
  ping?: PingConfig;
}

export interface PingConfig {
  intervalMs: number;
  pongTimeoutMs: number;
}

export interface SDKConfig {
  timeoutMs: number;
}

export interface AuthConfig {
  ak: string;
  sk: string;
}

export interface ReconnectConfig {
  baseMs: number;
  maxMs: number;
  exponential: boolean;
}

export interface EventConfig {
  allowlist: string[];
}

// ---------------------------------------------------------------------------
// Connection State
// ---------------------------------------------------------------------------

export type ConnectionState = 'DISCONNECTED' | 'CONNECTING' | 'CONNECTED' | 'READY';

// ---------------------------------------------------------------------------
// Envelope / Message Types
// ---------------------------------------------------------------------------

export type MessageSource = 'OPENCODE' | 'CURSOR' | 'WINDSURF';

export interface Envelope {
  version: string;
  messageId: string;
  timestamp: string;
  source: MessageSource;
  agentId: string;
  sessionId?: string;
  sequenceNumber: number;
  sequenceScope: 'session' | 'agent';
}

export type UpstreamMessageType =
  | 'register'
  | 'heartbeat'
  | 'tool_event'
  | 'tool_done'
  | 'tool_error'
  | 'session_created'
  | 'status_response';

export type DownstreamMessageType = 'invoke' | 'status_query';

export interface RegisterMessage {
  type: 'register';
  deviceName: string;
  os: string;
  toolType: string;
  toolVersion: string;
}

export interface HeartbeatMessage {
  type: 'heartbeat';
  timestamp: string;
}

export interface ToolEventMessage {
  type: 'tool_event';
  sessionId?: string;
  event: unknown;
  envelope: Envelope;
}

export interface ToolDoneMessage {
  type: 'tool_done';
  sessionId?: string;
  result?: unknown;
  envelope: Envelope;
}

export interface ToolErrorMessage {
  type: 'tool_error';
  sessionId?: string;
  error: string;
  envelope: Envelope;
}

export interface SessionCreatedMessage {
  type: 'session_created';
  sessionId: string;
  toolSessionId?: string;
  session?: unknown;
  envelope: Envelope;
}

export interface StatusResponseMessage {
  type: 'status_response';
  opencodeOnline: boolean;
  sessionId?: string;
  envelope: Envelope;
}

export interface InvokeMessage {
  type: 'invoke';
  sessionId?: string;
  action: InvokeAction;
  payload: unknown;
  envelope?: Envelope;
}

export interface StatusQueryMessage {
  type: 'status_query';
  sessionId?: string;
  envelope?: Envelope;
}

export type UpstreamMessage =
  | RegisterMessage
  | HeartbeatMessage
  | ToolEventMessage
  | ToolDoneMessage
  | ToolErrorMessage
  | SessionCreatedMessage
  | StatusResponseMessage;

export type DownstreamMessage = InvokeMessage | StatusQueryMessage;

// ---------------------------------------------------------------------------
// Actions
// ---------------------------------------------------------------------------

export type InvokeAction =
  | 'chat'
  | 'create_session'
  | 'close_session'
  | 'permission_reply'
  | 'status_query';

export interface ChatPayload {
  toolSessionId: string;
  text: string;
}

export interface CreateSessionPayload {
  sessionId?: string;
  metadata?: Record<string, unknown>;
}

export interface CloseSessionPayload {
  toolSessionId: string;
}

export interface PermissionReplyPayloadTarget {
  permissionId: string;
  toolSessionId: string;
  response: 'allow' | 'always' | 'deny';
}
export type PermissionReplyPayload = PermissionReplyPayloadTarget;

export interface StatusQueryPayload {
  sessionId?: string;
}

export function isPermissionReplyPayload(payload: unknown): payload is PermissionReplyPayload {
  return typeof payload === 'object' && payload !== null && 'permissionId' in payload;
}

export function isPermissionReplyTarget(
  payload: PermissionReplyPayload,
): payload is PermissionReplyPayloadTarget {
  return 'response' in payload && typeof payload.response === 'string';
}

// ---------------------------------------------------------------------------
// Errors
// ---------------------------------------------------------------------------

export type ErrorCode =
  | 'GATEWAY_UNREACHABLE'
  | 'SDK_TIMEOUT'
  | 'SDK_UNREACHABLE'
  | 'AGENT_NOT_READY'
  | 'INVALID_PAYLOAD'
  | 'UNSUPPORTED_ACTION';

export function stateToErrorCode(state: ConnectionState): ErrorCode {
  switch (state) {
    case 'DISCONNECTED':
    case 'CONNECTING':
      return 'GATEWAY_UNREACHABLE';
    case 'CONNECTED':
      return 'AGENT_NOT_READY';
    case 'READY':
      return 'AGENT_NOT_READY';
  }
}

export interface ToolErrorPayload {
  type: 'tool_error';
  sessionId?: string;
  error: string;
  envelope: Envelope;
}

// ---------------------------------------------------------------------------
// SDK interfaces
// ---------------------------------------------------------------------------

export interface OpencodeSessionClient {
  create(options?: { body?: Record<string, unknown> }): Promise<unknown>;
  abort(options: { path: { id: string } }): Promise<unknown>;
  prompt(options: {
    path: { id: string };
    body: { parts: Array<{ type: 'text'; text: string }> };
  }): Promise<unknown>;
}

export interface OpencodeClient {
  session: OpencodeSessionClient;
  postSessionIdPermissionsPermissionId: (options: {
    path: { id: string; permissionID: string };
    body: { response: 'once' | 'always' | 'reject' };
  }) => Promise<unknown>;
  app?: {
    health?: (options?: Record<string, unknown>) => Promise<unknown> | unknown;
    log: (options?: {
      body?: {
        service: string;
        level: 'debug' | 'info' | 'warn' | 'error';
        message: string;
        extra?: Record<string, unknown>;
      };
    }) => Promise<unknown> | unknown;
  };
}

export function isOpencodeClient(client: unknown): client is OpencodeClient {
  if (!client || typeof client !== 'object') {
    return false;
  }

  const c = client as Partial<OpencodeClient>;
  return (
    !!c.session &&
    typeof c.session === 'object' &&
    typeof c.session.create === 'function' &&
    typeof c.session.abort === 'function' &&
    typeof c.session.prompt === 'function' &&
    typeof c.postSessionIdPermissionsPermissionId === 'function'
  );
}

export async function safeExecute<T>(
  promise: Promise<T>,
  errorMapper?: (error: unknown) => string,
): Promise<{ success: true; data: T } | { success: false; error: string }> {
  try {
    const data = await promise;
    return { success: true, data };
  } catch (error) {
    const errorMessage = errorMapper
      ? errorMapper(error)
      : error instanceof Error
      ? error.message
      : String(error);
    return { success: false, error: errorMessage };
  }
}

export function hasError(result: unknown): result is { error: unknown } {
  return result !== null && typeof result === 'object' && 'error' in result && (result as { error?: unknown }).error !== undefined;
}

export function buildMessageId(): string {
  return randomUUID();
}

// ---------------------------------------------------------------------------
// Action interfaces
// ---------------------------------------------------------------------------

export interface ActionContext {
  client: unknown;
  connectionState: ConnectionState;
  agentId: string;
  sessionId?: string;
  logger?: {
    debug(message: string, extra?: Record<string, unknown>): void;
    info(message: string, extra?: Record<string, unknown>): void;
    warn(message: string, extra?: Record<string, unknown>): void;
    error(message: string, extra?: Record<string, unknown>): void;
    getTraceId(): string;
  };
}

export interface ValidationResult {
  valid: boolean;
  error?: string;
}

export interface ActionResult {
  success: boolean;
  data?: unknown;
  errorCode?: ErrorCode;
  errorMessage?: string;
}

export interface Action<TPayload = unknown> {
  name: string;
  validate(payload: unknown): ValidationResult;
  execute(payload: TPayload, context: ActionContext): Promise<ActionResult>;
  errorMapper(error: unknown): ErrorCode;
}

export interface ActionRegistry {
  register(action: Action): void;
  get(name: string): Action | undefined;
  has(name: string): boolean;
  list(): string[];
}

export interface MessageBridgePlugin {
  start(): Promise<void>;
  stop(): Promise<void>;
}

// ---------------------------------------------------------------------------
// Type guards / constants
// ---------------------------------------------------------------------------

export function hasEnvelope(message: unknown): message is { envelope: Envelope } {
  return (
    typeof message === 'object' &&
    message !== null &&
    'envelope' in message &&
    typeof (message as { envelope: unknown }).envelope === 'object'
  );
}

export function isUpstreamMessage(message: unknown): message is UpstreamMessage {
  return (
    typeof message === 'object' &&
    message !== null &&
    'type' in message &&
    typeof (message as { type: unknown }).type === 'string' &&
    ['register', 'heartbeat', 'tool_event', 'tool_done', 'tool_error', 'session_created', 'status_response'].includes(
      (message as { type: string }).type,
    )
  );
}

export function isDownstreamMessage(message: unknown): message is DownstreamMessage {
  return (
    typeof message === 'object' &&
    message !== null &&
    'type' in message &&
    typeof (message as { type: unknown }).type === 'string' &&
    ['invoke', 'status_query'].includes((message as { type: string }).type)
  );
}

export const DEFAULT_EVENT_ALLOWLIST = [
  'message.*',
  'permission.*',
  'session.*',
  'file.edited',
  'todo.updated',
  'command.executed',
] as const;

export const DEFAULT_CONFIG = {
  heartbeatIntervalMs: 30000,
  reconnectBaseMs: 1000,
  reconnectMaxMs: 30000,
  sdkTimeoutMs: 10000,
  configVersion: 1,
  pongTimeoutMs: 10000,
} as const;

export const AGENT_ID_PREFIX = 'bridge-';
export const PROTOCOL_VERSION = '1.0.0';
