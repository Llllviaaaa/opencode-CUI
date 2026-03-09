import {
  Action,
  CloseSessionPayload,
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
 * Concrete implementation of close_session action (maps to session.delete)
 */
export class CloseSessionAction implements Action<CloseSessionPayload> {
  name: string = 'close_session';

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

  /**
   * Validate close session payload
   */
  validate(payload: unknown): ValidationResult {
    const normalized = this.normalizePayload(payload);
    if (!normalized) {
      return {
        valid: false,
        error: 'close_session payload requires toolSessionId'
      };
    }

    return {
      valid: true
    };
  }

  /**
   * Execute close session action using delete semantics.
   */
  async execute(payload: CloseSessionPayload, context: ActionContext): Promise<ActionResult> {
    const normalized = this.normalizePayload(payload);
    if (!normalized) {
      return {
        success: false,
        errorCode: 'INVALID_PAYLOAD',
        errorMessage: 'close_session payload requires toolSessionId'
      };
    }

    const startedAt = Date.now();
    context.logger?.info('action.close_session.started', {
      toolSessionId: normalized.toolSessionId,
    });
    try {
      if (context.connectionState !== 'READY') {
        context.logger?.warn('action.close_session.rejected_state', { state: context.connectionState });
        return {
          success: false,
          errorCode: stateToErrorCode(context.connectionState),
          errorMessage: `Agent not ready. Current state: ${context.connectionState}`
        };
      }

      if (!isOpencodeClient(context.client)) {
        context.logger?.error('action.close_session.invalid_client');
        return {
          success: false,
          errorCode: 'SDK_UNREACHABLE',
          errorMessage: 'Valid OpenCode client not available in context'
        };
      }

      const client = context.client;
      if (typeof client.session.delete !== 'function') {
        context.logger?.error('action.close_session.delete_unavailable');
        return {
          success: false,
          errorCode: 'SDK_UNREACHABLE',
          errorMessage: 'session.delete is not available in current OpenCode client'
        };
      }
      const executionResult = await safeExecute(
        client.session.delete({
          path: { id: normalized.toolSessionId }
        }),
        (error) => `Close session (delete) failed: ${getErrorMessage(error)}`
      );

      if (executionResult.success) {
        if (!hasError(executionResult.data)) {
          return {
            success: true,
            data: { sessionId: normalized.toolSessionId, closed: true }
          };
        }

        const errorField =
          executionResult.data && typeof executionResult.data === 'object' && 'error' in executionResult.data
            ? (executionResult.data as { error: unknown }).error
            : undefined;
        const errorMessage = errorField !== undefined ? getErrorMessage(errorField) : 'Unknown error';

        context.logger?.error('action.close_session.sdk_error_payload', {
          toolSessionId: normalized.toolSessionId,
          error: errorMessage,
          ...(errorField !== undefined ? getErrorDetailsForLog(errorField) : {}),
          latencyMs: Date.now() - startedAt,
        });
        return {
          success: false,
          errorCode: 'SDK_UNREACHABLE',
          errorMessage: `Failed to close session (delete): ${errorMessage}`
        };
      }

      context.logger?.error('action.close_session.failed', {
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
      context.logger?.error('action.close_session.exception', {
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
      context.logger?.debug('action.close_session.finished', { latencyMs: Date.now() - startedAt });
    }
  }

  /**
   * Map SDK errors to appropriate error codes
   */
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
