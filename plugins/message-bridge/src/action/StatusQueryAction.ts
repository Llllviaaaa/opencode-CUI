import { Action, StatusQueryPayload, ValidationResult, ActionResult, ActionContext, ErrorCode } from '../types';

/**
 * Concrete implementation of status_query action for online status retrieval
 */
export class StatusQueryAction implements Action<StatusQueryPayload> {
  name: string = 'status_query';

  /**
   * Validate status query payload
   */
  validate(payload: unknown): ValidationResult {
    if (!payload) {
      // Payload can be empty or object containing optional sessionId
      return {
        valid: true
      };
    }

    if (typeof payload !== 'object') {
      return {
        valid: false,
        error: 'Status query payload must be an object or undefined'
      };
    }

    const typedPayload = payload as StatusQueryPayload;
    
    // Validate optional sessionId if present
    if (typedPayload.sessionId !== undefined && 
        (typeof typedPayload.sessionId !== 'string' || !typedPayload.sessionId.trim())) {
      return {
        valid: false,
        error: 'sessionId must be a non-empty string if provided'
      };
    }

    return {
      valid: true
    };
  }

  /**
   * Execute status query action
   * Returns online status from health check
   */
  async execute(payload: StatusQueryPayload, context: ActionContext): Promise<ActionResult> {
    try {
      // The primary status comes from the connection state
      // READY means OpenCode is accessible through our bridge
      const opencodeOnline = context.connectionState === 'READY';
      
      return {
        success: true,
        data: {
          opencodeOnline,
          connectionState: context.connectionState,
          sessionId: payload.sessionId,
          timestamp: new Date().toISOString()
        }
      };
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
    // Status queries are lightweight checks, so most errors would map to internal issues
    if (error instanceof Error) {
      const message = error.message.toLowerCase();
      
      if (message.includes('timeout') || message.includes('network')) {
        return 'SDK_TIMEOUT';
      } else if (message.includes('unreachable') || message.includes('connect')) {
        return 'SDK_UNREACHABLE';
      }
    }
    
    return 'SDK_UNREACHABLE';
  }
}