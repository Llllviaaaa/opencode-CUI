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

    const typedPayload = payload as CreateSessionPayload;

    if (typedPayload.sessionId !== undefined && (typeof typedPayload.sessionId !== 'string' || !typedPayload.sessionId.trim())) {
      return {
        valid: false,
        error: 'sessionId must be a non-empty string when provided'
      };
    }

    // metadata is optional, but if provided, should be an object
    if (typedPayload.metadata !== undefined && typeof typedPayload.metadata !== 'object') {
      return {
        valid: false,
        error: 'metadata must be an object if provided'
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
      
      // Create a new session
      const options: { sessionId?: string; metadata?: Record<string, unknown> } = {};
      if (payload.sessionId) {
        options.sessionId = payload.sessionId;
      }
      if (payload.metadata) {
        options.metadata = payload.metadata;
      }

      const executionResult = await safeExecute(
        client.session.create(options),
        (error) => `Create session failed: ${error instanceof Error ? error.message : String(error)}`
      );

      if (executionResult.success) {
        if (!hasError(executionResult.data)) {
          // Safely extract the session ID from result data
          let returnedSessionId: string | undefined;
          if (executionResult.data && typeof executionResult.data === 'object' && 'data' in executionResult.data) {
            const possibleData = executionResult.data.data;
            if (possibleData && typeof possibleData === 'object' && 'sessionId' in possibleData) {
              returnedSessionId = (possibleData as { sessionId?: string }).sessionId;
            }
          }
          return {
            success: true,
            data: { 
              sessionId: returnedSessionId || payload.sessionId 
            }
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
            errorMessage: `Failed to create session: ${errorMessage}`
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
      } else if (message.includes('invalid') || message.includes('bad request')) {
        return 'INVALID_PAYLOAD';
      }
    }
    
    return 'SDK_UNREACHABLE';
  }
}
