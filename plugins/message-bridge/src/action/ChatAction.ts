import { 
  Action, 
  ChatPayload, 
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
 * Concrete implementation of chat action for sending messages to OpenCode
 */
export class ChatAction implements Action<ChatPayload> {
  name: string = 'chat';

  /**
   * Validate chat payload
   */
  validate(payload: unknown): ValidationResult {
    if (!payload || typeof payload !== 'object') {
      return {
        valid: false,
        error: 'Chat payload must be an object'
      };
    }

    const p = payload as Partial<ChatPayload>;

    if (typeof p.sessionId !== 'string' || !p.sessionId.trim()) {
      return {
        valid: false,
        error: 'sessionId is required and must be a non-empty string'
      };
    }

    if (typeof p.message !== 'string') {
      return {
        valid: false,
        error: 'message is required and must be a string'
      };
    }

    return {
      valid: true
    };
  }

  /**
   * Execute chat action
   */
  async execute(payload: ChatPayload, context: ActionContext): Promise<ActionResult> {
    const startedAt = Date.now();
    context.logger?.info('action.chat.started', {
      sessionId: payload.sessionId,
      messageLength: payload.message.length,
    });
    if (context.connectionState !== 'READY') {
      context.logger?.warn('action.chat.rejected_state', { state: context.connectionState });
      return {
        success: false,
        errorCode: stateToErrorCode(context.connectionState),
        errorMessage: `Agent not ready. Current state: ${context.connectionState}`
      };
    }

    if (!isOpencodeClient(context.client)) {
      context.logger?.error('action.chat.invalid_client');
      return {
        success: false,
        errorCode: 'SDK_UNREACHABLE',
        errorMessage: 'OpenCode client not available or invalid in context'
      };
    }

    try {
      const executionResult = await safeExecute(
        context.client.session.prompt({
          sessionId: payload.sessionId,
          message: payload.message,
          meta: {
            source: 'message-bridge'
          }
        }),
        (error) => `Failed to send message: ${error instanceof Error ? error.message : String(error)}`
      );

      if (executionResult.success) {
        if (hasError(executionResult.data)) {
            // Extract the error message in a type-safe way
            let errorMessage = 'Unknown error';
            
            if (executionResult.data && typeof executionResult.data === 'object' && 'error' in executionResult.data) {
              const errorField = (executionResult.data as { error: unknown }).error;
              if (errorField && typeof errorField === 'object' && errorField !== null && 'message' in errorField) {
                const messageField = (errorField as { message: unknown }).message;
                if (typeof messageField === 'string') {
                  errorMessage = messageField;
                } else {
                  errorMessage = String(messageField) || 'Unknown error';
                }
              } else {
                errorMessage = String(errorField) || 'Unknown error';
              }
            }
            
            context.logger?.error('action.chat.sdk_error_payload', {
              error: errorMessage,
              latencyMs: Date.now() - startedAt,
            });
            return {
              success: false,
              errorCode: 'SDK_UNREACHABLE',
              errorMessage: `Failed to send message: ${errorMessage}`
            };
        }
        
        return {
          success: true,
          data: executionResult.data
        };
      } else {
        context.logger?.error('action.chat.failed', {
          error: executionResult.error,
          latencyMs: Date.now() - startedAt,
        });
        return {
          success: false,
          errorCode: 'SDK_UNREACHABLE',
          errorMessage: executionResult.error
        };
      }
    } catch (error) {
      const errorCode = this.errorMapper(error);
      const errorMessage = error instanceof Error ? error.message : String(error);
      context.logger?.error('action.chat.exception', {
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
      context.logger?.debug('action.chat.finished', { latencyMs: Date.now() - startedAt });
    }
  }

  /**
   * Map SDK errors to appropriate error codes
   * Abort-safe: handles all error types without throwing
   */
  errorMapper(error: unknown): ErrorCode {
    if (error instanceof Error) {
      const message = error.message.toLowerCase();

      if (message.includes('timeout') || message.includes('timed out')) {
        return 'SDK_TIMEOUT';
      } else if (message.includes('unreachable') || message.includes('connect') || message.includes('connection')) {
        return 'SDK_UNREACHABLE';
      } else if (message.includes('not found') || message.includes('session') && message.includes('not found')) {
        return 'INVALID_PAYLOAD';
      } else if (message.includes('abort') || message.includes('cancelled')) {
        return 'INVALID_PAYLOAD';
      }
    } else if (typeof error === 'string') {
      const message = error.toLowerCase();
      if (message.includes('timeout')) {
        return 'SDK_TIMEOUT';
      } else if (message.includes('unreachable') || message.includes('connect')) {
        return 'SDK_UNREACHABLE';
      }
    }

    return 'SDK_UNREACHABLE';
  }
}
