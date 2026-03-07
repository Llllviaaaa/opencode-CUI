import {
  Action,
  CreateSessionPayload,
  ValidationResult,
  ActionResult,
  ActionContext,
  ErrorCode,
  isOpencodeClient,
  hasError,
  safeExecute,
  stateToErrorCode
} from '../types';

/**
 * Concrete implementation of create_session action for creating OpenCode sessions
 */
export class CreateSessionAction implements Action<CreateSessionPayload> {
  name: string = 'create_session';

  /**
   * Validate create session payload
   */
  validate(payload: unknown): ValidationResult {
    if (!payload || typeof payload !== 'object') {
      return {
        valid: false,
        error: 'Create session payload must be an object'
      };
    }

    return {
      valid: true
    };
  }

  /**
   * Execute create session action
   */
  async execute(payload: CreateSessionPayload, context: ActionContext): Promise<ActionResult> {
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

      if (!isOpencodeClient(context.client)) {
        context.logger?.error('action.create_session.invalid_client');
        return {
          success: false,
          errorCode: 'SDK_UNREACHABLE',
          errorMessage: 'Valid OpenCode client not available in context'
        };
      }

      const executionResult = await safeExecute(
        context.client.session.create({ body: payload as Record<string, unknown> }),
        (error) => `Create session failed: ${error instanceof Error ? error.message : String(error)}`
      );

      if (executionResult.success) {
        if (!hasError(executionResult.data)) {
          const root = executionResult.data as Record<string, unknown> | undefined;
          const nested = root?.data as Record<string, unknown> | undefined;
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
              sessionId: returnedSessionId
            }
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

        context.logger?.error('action.create_session.sdk_error_payload', {
          error: errorMessage,
          latencyMs: Date.now() - startedAt,
        });
        return {
          success: false,
          errorCode: 'SDK_UNREACHABLE',
          errorMessage: `Failed to create session: ${errorMessage}`
        };
      }

      context.logger?.error('action.create_session.failed', {
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
      context.logger?.error('action.create_session.exception', {
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
