import {
  Action,
  CloseSessionResultData,
  CloseSessionPayload,
  ActionResult,
  ActionContext,
  ErrorCode,
  isOpencodeClient,
  hasError,
  safeExecute,
  stateToErrorCode
} from '../types';

/**
 * Concrete implementation of close_session action (PRD §FR-MB-05: uses abort semantics, not delete)
 */
export class CloseSessionAction implements Action<'close_session', CloseSessionPayload, CloseSessionResultData> {
  name: 'close_session' = 'close_session';

  /**
   * Execute close session action (using abort semantics - PRD §FR-MB-05)
   */
  async execute(payload: CloseSessionPayload, context: ActionContext): Promise<ActionResult<CloseSessionResultData>> {
    const startedAt = Date.now();
    context.logger?.info('action.close_session.started', {
      toolSessionId: payload.toolSessionId,
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
      const executionResult = await safeExecute(
        client.session.abort({
          path: { id: payload.toolSessionId }
        }),
        (error) => `Close session (abort) failed: ${error instanceof Error ? error.message : String(error)}`
      );

      if (executionResult.success) {
        if (!hasError(executionResult.data)) {
          return {
            success: true,
            data: { sessionId: payload.toolSessionId, closed: true }
          };
        }

        let errorMessage = 'Unknown error';
        if (executionResult.data && typeof executionResult.data === 'object' && 'error' in executionResult.data) {
          const errorField = (executionResult.data as { error: unknown }).error;
          if (errorField && typeof errorField === 'object' && errorField !== null && 'message' in errorField) {
            const messageField = (errorField as { message: unknown }).message;
            errorMessage = typeof messageField === 'string' ? messageField : String(messageField) || 'Unknown error';
          } else {
            errorMessage = String(errorField) || 'Unknown error';
          }
        }

        context.logger?.error('action.close_session.sdk_error_payload', {
          error: errorMessage,
          latencyMs: Date.now() - startedAt,
        });
        return {
          success: false,
          errorCode: 'SDK_UNREACHABLE',
          errorMessage: `Failed to close session (abort): ${errorMessage}`
        };
      }

      context.logger?.error('action.close_session.failed', {
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
      const errorMessage = error instanceof Error ? error.message : String(error);
      context.logger?.error('action.close_session.exception', {
        error: errorMessage,
        errorCode,
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
