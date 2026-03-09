import {
  Action,
  AbortSessionPayload,
  ValidationResult,
  ActionResult,
  ActionContext,
  ErrorCode,
  isOpencodeClient,
  hasError,
  safeExecute,
  stateToErrorCode
} from '../types';
import { getErrorDetailsForLog, getErrorMessage } from '../utils/error';

/**
 * Concrete implementation of abort_session action.
 */
export class AbortSessionAction implements Action<AbortSessionPayload> {
  name: string = 'abort_session';

  private normalizePayload(payload: unknown): { toolSessionId: string } | null {
    if (!payload || typeof payload !== 'object') {
      return null;
    }

    const p = payload as { toolSessionId?: unknown };
    const toolSessionId =
      typeof p.toolSessionId === 'string' && p.toolSessionId.trim()
        ? p.toolSessionId
        : null;

    if (!toolSessionId) {
      return null;
    }

    return { toolSessionId };
  }

  validate(payload: unknown): ValidationResult {
    const normalized = this.normalizePayload(payload);
    if (!normalized) {
      return {
        valid: false,
        error: 'abort_session payload requires toolSessionId'
      };
    }

    return { valid: true };
  }

  async execute(payload: AbortSessionPayload, context: ActionContext): Promise<ActionResult> {
    const normalized = this.normalizePayload(payload);
    if (!normalized) {
      return {
        success: false,
        errorCode: 'INVALID_PAYLOAD',
        errorMessage: 'abort_session payload requires toolSessionId'
      };
    }

    const startedAt = Date.now();
    context.logger?.info('action.abort_session.started', {
      toolSessionId: normalized.toolSessionId,
    });

    try {
      if (context.connectionState !== 'READY') {
        context.logger?.warn('action.abort_session.rejected_state', { state: context.connectionState });
        return {
          success: false,
          errorCode: stateToErrorCode(context.connectionState),
          errorMessage: `Agent not ready. Current state: ${context.connectionState}`
        };
      }

      if (!isOpencodeClient(context.client)) {
        context.logger?.error('action.abort_session.invalid_client');
        return {
          success: false,
          errorCode: 'SDK_UNREACHABLE',
          errorMessage: 'Valid OpenCode client not available in context'
        };
      }

      const client = context.client;
      const executionResult = await safeExecute(
        client.session.abort({
          path: { id: normalized.toolSessionId }
        }),
        (error) => `Abort session failed: ${getErrorMessage(error)}`
      );

      if (executionResult.success) {
        if (!hasError(executionResult.data)) {
          return {
            success: true,
            data: { sessionId: normalized.toolSessionId, aborted: true }
          };
        }

        const errorField =
          executionResult.data && typeof executionResult.data === 'object' && 'error' in executionResult.data
            ? (executionResult.data as { error: unknown }).error
            : undefined;
        const errorMessage = errorField !== undefined ? getErrorMessage(errorField) : 'Unknown error';

        context.logger?.error('action.abort_session.sdk_error_payload', {
          toolSessionId: normalized.toolSessionId,
          error: errorMessage,
          ...(errorField !== undefined ? getErrorDetailsForLog(errorField) : {}),
          latencyMs: Date.now() - startedAt,
        });
        return {
          success: false,
          errorCode: 'SDK_UNREACHABLE',
          errorMessage: `Failed to abort session: ${errorMessage}`
        };
      }

      context.logger?.error('action.abort_session.failed', {
        toolSessionId: normalized.toolSessionId,
        error: executionResult.error,
        latencyMs: Date.now() - startedAt,
      });
      return {
        success: false,
        errorCode: 'SDK_UNREACHABLE',
        errorMessage: executionResult.error
      };
    } catch (error) {
      const errorCode = this.errorMapper(error);
      const errorMessage = getErrorMessage(error);
      context.logger?.error('action.abort_session.exception', {
        toolSessionId: normalized.toolSessionId,
        error: errorMessage,
        errorCode,
        ...getErrorDetailsForLog(error),
        latencyMs: Date.now() - startedAt,
      });

      return {
        success: false,
        errorCode,
        errorMessage
      };
    } finally {
      context.logger?.debug('action.abort_session.finished', { latencyMs: Date.now() - startedAt });
    }
  }

  errorMapper(error: unknown): ErrorCode {
    if (error instanceof Error) {
      const message = error.message.toLowerCase();

      if (message.includes('timeout') || message.includes('network')) {
        return 'SDK_TIMEOUT';
      } else if (message.includes('unreachable') || message.includes('connect')) {
        return 'SDK_UNREACHABLE';
      } else if (message.includes('not found') || message.includes('session')) {
        return 'INVALID_PAYLOAD';
      }
    }

    return 'SDK_UNREACHABLE';
  }
}
