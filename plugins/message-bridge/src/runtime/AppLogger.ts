import { randomUUID } from 'crypto';

export type BridgeLogLevel = 'debug' | 'info' | 'warn' | 'error';

export interface BridgeLogger {
  debug(message: string, extra?: Record<string, unknown>): void;
  info(message: string, extra?: Record<string, unknown>): void;
  warn(message: string, extra?: Record<string, unknown>): void;
  error(message: string, extra?: Record<string, unknown>): void;
  child(extra: Record<string, unknown>): BridgeLogger;
  getTraceId(): string;
}

type AppLogFn = (options?: {
  body?: {
    service: string;
    level: BridgeLogLevel;
    message: string;
    extra?: Record<string, unknown>;
  };
}) => Promise<unknown> | unknown;

function isRecord(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === 'object';
}

function redact(value: unknown): unknown {
  if (Array.isArray(value)) {
    return value.map((item) => redact(item));
  }
  if (!isRecord(value)) {
    return value;
  }
  const sensitive = ['ak', 'sk', 'token', 'authorization', 'cookie', 'secret', 'password'];
  const output: Record<string, unknown> = {};
  for (const [k, v] of Object.entries(value)) {
    const lower = k.toLowerCase();
    if (sensitive.some((key) => lower.includes(key))) {
      output[k] = '***';
      continue;
    }
    output[k] = redact(v);
  }
  return output;
}

function summarize(value: unknown): unknown {
  if (Array.isArray(value)) {
    return { type: 'array', length: value.length };
  }
  if (!isRecord(value)) {
    return value;
  }
  const keys = Object.keys(value);
  return {
    type: 'object',
    keys,
    size: keys.length,
  };
}

function getAppLog(client: unknown): AppLogFn | null {
  if (!isRecord(client)) {
    return null;
  }
  const app = client.app;
  if (!isRecord(app) || typeof app.log !== 'function') {
    return null;
  }
  return (app.log as AppLogFn).bind(app);
}

export class AppLogger implements BridgeLogger {
  private readonly appLog: AppLogFn | null;
  private readonly debugEnabled: boolean;
  private readonly traceId: string;

  constructor(
    client: unknown,
    private readonly baseExtra: Record<string, unknown> = {},
    traceId?: string,
    appLog?: AppLogFn | null,
    debug?: boolean,
  ) {
    this.appLog = appLog ?? getAppLog(client);
    const envDebugEnabled = ['1', 'true', 'yes', 'on'].includes(String(process.env.BRIDGE_DEBUG || '').toLowerCase());
    this.debugEnabled = debug ?? envDebugEnabled;
    this.traceId = traceId ?? randomUUID();
  }

  child(extra: Record<string, unknown>): BridgeLogger {
    return new AppLogger({}, { ...this.baseExtra, ...extra }, this.traceId, this.appLog, this.debugEnabled);
  }

  getTraceId(): string {
    return this.traceId;
  }

  debug(message: string, extra?: Record<string, unknown>): void {
    this.write('debug', message, extra);
  }

  info(message: string, extra?: Record<string, unknown>): void {
    this.write('info', message, extra);
  }

  warn(message: string, extra?: Record<string, unknown>): void {
    this.write('warn', message, extra);
  }

  error(message: string, extra?: Record<string, unknown>): void {
    this.write('error', message, extra);
  }

  private write(level: BridgeLogLevel, message: string, extra?: Record<string, unknown>): void {
    const enriched = {
      traceId: this.traceId,
      ...this.baseExtra,
      ...(extra || {}),
    };
    const payload = this.debugEnabled ? redact(enriched) : summarize(redact(enriched));

    if (!this.appLog) {
      if (this.debugEnabled) {
        console.debug('[message-bridge][log-fallback]', level, message, payload);
      }
      return;
    }

    void Promise.resolve()
      .then(() =>
        this.appLog?.({
          body: {
            service: 'message-bridge',
            level,
            message,
            extra: payload as Record<string, unknown>,
          },
        }),
      )
      .catch((err) => {
      if (this.debugEnabled) {
        const reason = err instanceof Error ? err.message : String(err);
        console.debug('[message-bridge][log-send-failed]', reason, { level, message });
      }
      });
  }
}
