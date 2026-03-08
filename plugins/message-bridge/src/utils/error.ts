export interface ErrorDetails {
  message: string;
  name?: string;
  code?: string;
  type?: string;
  stack?: string;
  causeMessage?: string;
  rawType?: string;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === 'object';
}

function stringifyScalar(value: unknown): string | undefined {
  if (typeof value === 'string' && value.trim()) {
    return value;
  }
  if (typeof value === 'number' || typeof value === 'boolean' || typeof value === 'bigint') {
    return String(value);
  }
  if (typeof value === 'symbol') {
    return String(value);
  }
  return undefined;
}

function getConstructorName(value: unknown): string | undefined {
  if (!isRecord(value)) {
    return undefined;
  }
  const ctor = value.constructor;
  if (typeof ctor === 'function' && typeof ctor.name === 'string' && ctor.name) {
    return ctor.name;
  }
  return undefined;
}

export function safeStringify(value: unknown): string {
  if (typeof value === 'string') {
    return value;
  }

  const seen = new WeakSet<object>();

  try {
    const serialized = JSON.stringify(value, (_key, currentValue) => {
      if (typeof currentValue === 'bigint') {
        return String(currentValue);
      }
      if (typeof currentValue === 'symbol') {
        return String(currentValue);
      }
      if (typeof currentValue === 'function') {
        return `[Function ${currentValue.name || 'anonymous'}]`;
      }
      if (currentValue && typeof currentValue === 'object') {
        if (seen.has(currentValue)) {
          return '[Circular]';
        }
        seen.add(currentValue);
      }
      return currentValue;
    });

    return serialized ?? String(value);
  } catch {
    return '[unserializable error object]';
  }
}

export function getErrorDetails(error: unknown): ErrorDetails {
  if (error instanceof Error) {
    const typedError = error as Error & {
      code?: unknown;
      cause?: unknown;
    };

    return {
      message: error.message || error.name || 'Unknown error',
      name: error.name || undefined,
      code: stringifyScalar(typedError.code),
      type: getConstructorName(error),
      stack: typeof error.stack === 'string' ? error.stack : undefined,
      causeMessage: typedError.cause !== undefined ? getErrorMessage(typedError.cause) : undefined,
      rawType: getConstructorName(error) ?? 'Error',
    };
  }

  if (typeof error === 'string') {
    return { message: error, rawType: 'string' };
  }

  if (error === null) {
    return { message: 'null', rawType: 'null' };
  }

  if (error === undefined) {
    return { message: 'undefined', rawType: 'undefined' };
  }

  if (typeof error !== 'object') {
    return {
      message: String(error),
      rawType: typeof error,
    };
  }

  const record = error as Record<string, unknown>;
  const nestedError = record.error !== undefined && record.error !== error ? getErrorDetails(record.error) : undefined;
  const directMessage = stringifyScalar(record.message);
  const directName = stringifyScalar(record.name);
  const directCode = stringifyScalar(record.code);
  const directType = stringifyScalar(record.type);
  const constructorName = getConstructorName(record);

  return {
    message: directMessage ?? nestedError?.message ?? safeStringify(error),
    name: directName ?? nestedError?.name,
    code: directCode ?? nestedError?.code,
    type: directType ?? nestedError?.type ?? (constructorName !== 'Object' ? constructorName : undefined),
    causeMessage:
      record.cause !== undefined
        ? getErrorMessage(record.cause)
        : nestedError?.causeMessage,
    rawType: constructorName ?? 'object',
  };
}

export function getErrorMessage(error: unknown): string {
  return getErrorDetails(error).message;
}

export function getErrorDetailsForLog(error: unknown): Record<string, unknown> {
  const details = getErrorDetails(error);
  const logDetails: Record<string, unknown> = {
    errorDetail: details.message,
  };

  if (details.name) {
    logDetails.errorName = details.name;
  }
  if (details.code) {
    logDetails.sourceErrorCode = details.code;
  }
  if (details.type) {
    logDetails.errorType = details.type;
  }
  if (details.causeMessage) {
    logDetails.causeMessage = details.causeMessage;
  }
  if (details.rawType) {
    logDetails.rawType = details.rawType;
  }

  return logDetails;
}
