import {
  Action,
  AbortSessionPayload,
  AbortSessionResultData,
  ActionResult,
  ActionContext,
  ErrorCode,
  hasError,
  safeExecute,
  stateToErrorCode,
} from '../types';
import { getErrorDetailsForLog, getErrorMessage } from '../utils/error';

export class AbortSessionAction implements Action<'abort_session', AbortSessionPayload, AbortSessionResultData> {
  name: 'abort_session' = 'abort_session';

  async execute(
    payload: AbortSessionPayload,
    context: ActionContext,
  ): Promise<ActionResult<AbortSessionResultData>> {
    const startedAt = Date.now();
    context.logger?.info('action.abort_session.started', {
      toolSessionId: payload.toolSessionId,
    });

    try {
      if (context.connectionState !== 'READY') {
        context.logger?.warn('action.abort_session.rejected_state', { state: context.connectionState });
        return {
          success: false,
          errorCode: stateToErrorCode(context.connectionState),
          errorMessage: `Agent not ready. Current state: ${context.connectionState}`,
        };
      }

      const executionResult = await safeExecute(
        context.client.session.abort({
          path: { id: payload.toolSessionId },
        }),
        (error) => `Abort session failed: ${getErrorMessage(error)}`,
      );

      if (executionResult.success) {
        if (!hasError(executionResult.data)) {
          return {
            success: true,
            data: { sessionId: payload.toolSessionId, aborted: true },
          };
        }

        const errorField =
          executionResult.data && typeof executionResult.data === 'object' && 'error' in executionResult.data
            ? (executionResult.data as { error: unknown }).error
            : undefined;
        const errorMessage = errorField !== undefined ? getErrorMessage(errorField) : 'Unknown error';

        context.logger?.error('action.abort_session.sdk_error_payload', {
          toolSessionId: payload.toolSessionId,
          error: errorMessage,
          ...(errorField !== undefined ? getErrorDetailsForLog(errorField) : {}),
          latencyMs: Date.now() - startedAt,
        });
        return {
          success: false,
          errorCode: 'SDK_UNREACHABLE',
          errorMessage: `Failed to abort session: ${errorMessage}`,
        };
      }

      context.logger?.error('action.abort_session.failed', {
        toolSessionId: payload.toolSessionId,
        error: executionResult.error,
        latencyMs: Date.now() - startedAt,
      });
      return {
        success: false,
        errorCode: 'SDK_UNREACHABLE',
        errorMessage: executionResult.error,
      };
    } catch (error) {
      const errorCode = this.errorMapper(error);
      const errorMessage = getErrorMessage(error);
      context.logger?.error('action.abort_session.exception', {
        toolSessionId: payload.toolSessionId,
        error: errorMessage,
        errorCode,
        ...getErrorDetailsForLog(error),
        latencyMs: Date.now() - startedAt,
      });
      return {
        success: false,
        errorCode,
        errorMessage,
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
      }
      if (message.includes('unreachable') || message.includes('connect')) {
        return 'SDK_UNREACHABLE';
      }
      if (message.includes('not found') || message.includes('session')) {
        return 'INVALID_PAYLOAD';
      }
    }

    return 'SDK_UNREACHABLE';
  }
}
