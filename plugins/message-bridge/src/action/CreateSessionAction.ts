import {
  Action,
  CreateSessionResultData,
  CreateSessionPayload,
  ActionResult,
  ActionContext,
  ErrorCode,
  hasError,
  safeExecute,
  stateToErrorCode
} from '../types';
import { getErrorDetailsForLog, getErrorMessage } from '../utils/error';

/**
 * Concrete implementation of create_session action for creating OpenCode sessions
 */
export class CreateSessionAction implements Action<'create_session', CreateSessionPayload, CreateSessionResultData> {
  name: 'create_session' = 'create_session';

  /**
   * Execute create session action
   */
  async execute(payload: CreateSessionPayload, context: ActionContext): Promise<ActionResult<CreateSessionResultData>> {
    const startedAt = Date.now();
    context.logger?.info('action.create_session.started', {
      payloadKeys: Object.keys(payload ?? {}),
    });

    try {
      if (context.connectionState !== 'READY') {
        context.logger?.warn('action.create_session.rejected_state', { state: context.connectionState });
        return {
          success: false,
          errorCode: stateToErrorCode(context.connectionState),
          errorMessage: `Agent not ready. Current state: ${context.connectionState}`
        };
      }

      const executionResult = await safeExecute(
        context.client.session.create({ body: payload as Record<string, unknown> }),
        (error) => `Create session failed: ${getErrorMessage(error)}`
      );

      if (executionResult.success) {
        if (!hasError(executionResult.data)) {
          const root = executionResult.data as Record<string, unknown> | undefined;
          const nested = root?.data as Record<string, unknown> | undefined;
          const sessionObject = nested ?? root ?? {};
          const pick = (value: unknown): string | undefined =>
            typeof value === 'string' && value.trim() ? value : undefined;
          const returnedSessionId =
            pick(root?.sessionId) ??
            pick(root?.id) ??
            pick(nested?.sessionId) ??
            pick(nested?.id);

          return {
            success: true,
            data: {
              sessionId: returnedSessionId,
              session: sessionObject,
            }
          };
        }

        const errorField =
          executionResult.data && typeof executionResult.data === 'object' && 'error' in executionResult.data
            ? (executionResult.data as { error: unknown }).error
            : undefined;
        const errorMessage = errorField !== undefined ? getErrorMessage(errorField) : 'Unknown error';

        context.logger?.error('action.create_session.sdk_error_payload', {
          requestedSessionId: payload.sessionId,
          payloadKeys: Object.keys(payload ?? {}),
          error: errorMessage,
          ...(errorField !== undefined ? getErrorDetailsForLog(errorField) : {}),
          latencyMs: Date.now() - startedAt,
        });
        return {
          success: false,
          errorCode: 'SDK_UNREACHABLE',
          errorMessage: `Failed to create session: ${errorMessage}`
        };
      }

      context.logger?.error('action.create_session.failed', {
        requestedSessionId: payload.sessionId,
        payloadKeys: Object.keys(payload ?? {}),
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
      context.logger?.error('action.create_session.exception', {
        requestedSessionId: payload.sessionId,
        payloadKeys: Object.keys(payload ?? {}),
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
      context.logger?.debug('action.create_session.finished', { latencyMs: Date.now() - startedAt });
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
      } else if (message.includes('invalid') || message.includes('bad request')) {
        return 'INVALID_PAYLOAD';
      }
    }

    return 'SDK_UNREACHABLE';
  }
}
