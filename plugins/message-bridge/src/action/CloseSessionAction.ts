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

/**
 * Concrete implementation of close_session action (PRD §FR-MB-05: uses abort semantics, not delete)
 */
export class CloseSessionAction implements Action<CloseSessionPayload> {
  name: string = 'close_session';

  /**
   * Validate close session payload
   */
  validate(payload: unknown): ValidationResult {
    if (!payload || typeof payload !== 'object') {
      return {
        valid: false,
        error: 'Close session payload must be an object'
      };
    }

    const typedPayload = payload as CloseSessionPayload;
    
    if (typeof typedPayload.sessionId !== 'string' || !typedPayload.sessionId.trim()) {
      return {
        valid: false,
        error: 'sessionId is required and must be a non-empty string'
      };
    }

    return {
      valid: true
    };
  }

  /**
   * Execute close session action (using abort semantics - PRD §FR-MB-05)
   */
  async execute(payload: CloseSessionPayload, context: ActionContext): Promise<ActionResult> {
    try {
      // Check connection state
      if (context.connectionState !== 'READY') {
        return {
          success: false,
          errorCode: stateToErrorCode(context.connectionState),
          errorMessage: `Agent not ready. Current state: ${context.connectionState}`
        };
      }

      if (!isOpencodeClient(context.client)) {
        return {
          success: false,
          errorCode: 'SDK_UNREACHABLE',
          errorMessage: 'Valid OpenCode client not available in context'
        };
      }

      const client = context.client;

      // Use abort semantics instead of delete (PRD §FR-MB-05)
      // This ends the session gracefully without deleting data
      const executionResult = await safeExecute(
        client.session.abort({
          sessionId: payload.sessionId
        }),
        (error) => `Close session (abort) failed: ${error instanceof Error ? error.message : String(error)}`
      );

      if (executionResult.success) {
        if (!hasError(executionResult.data)) {
          return {
            success: true,
            data: { sessionId: payload.sessionId, closed: true }
          };
        } else {
          // Extract error message in a type-safe way
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
          
          return {
            success: false,
            errorCode: 'SDK_UNREACHABLE',
            errorMessage: `Failed to close session (abort): ${errorMessage}`
          };
        }
      } else {
        return {
          success: false,
          errorCode: 'SDK_UNREACHABLE',
          errorMessage: executionResult.error
        };
      }
    } catch (error) {
      const errorCode = this.errorMapper(error);
      const errorMessage = error instanceof Error ? error.message : String(error);

      return {
        success: false,
        errorCode,
        errorMessage
      };
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
