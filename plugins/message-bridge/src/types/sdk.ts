import { getErrorMessage } from '../utils/error';

export interface OpencodeHealthResult {
  healthy: true;
  version?: string;
}

export interface HostClientLike {
  global?: {
    health?: (options?: Record<string, unknown>) => Promise<OpencodeHealthResult> | OpencodeHealthResult;
  };
  app?: {
    log?: (options?: {
      body?: {
        service: string;
        level: 'debug' | 'info' | 'warn' | 'error';
        message: string;
        extra?: Record<string, unknown>;
      };
    }) => Promise<unknown> | unknown;
  };
}

export interface OpencodeSessionClient {
  create(options?: { body?: Record<string, unknown> }): Promise<unknown>;
  abort(options: { path: { id: string } }): Promise<unknown>;
  delete(options: { path: { id: string } }): Promise<unknown>;
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
  _client: {
    get: (options: Record<string, unknown>) => Promise<unknown>;
    post: (options: Record<string, unknown>) => Promise<unknown>;
  };
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
      : getErrorMessage(error);
    return { success: false, error: errorMessage };
  }
}

export function hasError(result: unknown): result is { error: unknown } {
  return result !== null && typeof result === 'object' && 'error' in result && (result as { error?: unknown }).error !== undefined;
}
