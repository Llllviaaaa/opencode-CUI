import { 
  Action, 
  PermissionReplyPayload, 
  PermissionReplyPayloadTarget, 
  PermissionReplyPayloadCompat, 
  ValidationResult, 
  ActionResult, 
  ActionContext, 
  ErrorCode,
  isPermissionReplyTarget,
  mapApprovedToResponse,
  isOpencodeClient,
  hasError,
  safeExecute,
  stateToErrorCode
} from '../types';

/**
 * Concrete implementation of permission_reply action with PRD §FR-MB-06 dual format compatibility:
 * - Target format: { permissionId, response: 'allow'|'always'|'deny' }
 * - Compat format: { permissionId, approved: boolean } → maps to response
 */
export class PermissionReplyAction implements Action<PermissionReplyPayload> {
  name: string = 'permission_reply';

  private mapResponseToDecision(response: 'allow' | 'always' | 'deny'): 'once' | 'always' | 'reject' {
    if (response === 'allow') {
      return 'once';
    }
    if (response === 'deny') {
      return 'reject';
    }
    return 'always';
  }

  /**
   * Validate permission reply payload (supports both target and compat formats)
   */
  validate(payload: unknown): ValidationResult {
    if (!payload || typeof payload !== 'object') {
      return {
        valid: false,
        error: 'Permission reply payload must be an object'
      };
    }

    const typedPayload = payload as PermissionReplyPayload;
    
    if (typeof typedPayload.permissionId !== 'string' || !typedPayload.permissionId.trim()) {
      return {
        valid: false,
        error: 'permissionId is required and must be a non-empty string'
      };
    }

    // Validate based on format
    if (isPermissionReplyTarget(typedPayload)) {
      // Target format - validate response field
      const targetPayload = typedPayload as PermissionReplyPayloadTarget;
      const allowedResponses = ['allow', 'always', 'deny'] as const;
      const isValidResponse = allowedResponses.includes(targetPayload.response);
      
      if (!isValidResponse) {
        return {
          valid: false,
          error: `response must be one of: '${allowedResponses.join("', '")}'`
        };
      }
    } else {
      // Compatibility format - validate approved field
      const compatPayload = typedPayload as PermissionReplyPayloadCompat;
      if (typeof compatPayload.approved !== 'boolean') {
        return {
          valid: false,
          error: 'approved must be a boolean when using compatibility format'
        };
      }
    }

    // Validate optional toolSessionId if present
    if (typedPayload.toolSessionId !== undefined && 
        (typeof typedPayload.toolSessionId !== 'string' || !typedPayload.toolSessionId.trim())) {
      return {
        valid: false,
        error: 'toolSessionId must be a non-empty string if provided'
      };
    }

    return {
      valid: true
    };
  }

  /**
   * Execute permission reply action
   */
  async execute(payload: PermissionReplyPayload, context: ActionContext): Promise<ActionResult> {
    const startedAt = Date.now();
    context.logger?.info('action.permission_reply.started', {
      permissionId: payload.permissionId,
      hasToolSessionId: !!payload.toolSessionId,
      payloadFormat: isPermissionReplyTarget(payload) ? 'target' : 'compat',
    });
    try {
      // Check connection state
      if (context.connectionState !== 'READY') {
        context.logger?.warn('action.permission_reply.rejected_state', { state: context.connectionState });
        return {
          success: false,
          errorCode: stateToErrorCode(context.connectionState),
          errorMessage: `Agent not ready. Current state: ${context.connectionState}`
        };
      }

      if (!isOpencodeClient(context.client)) {
        context.logger?.error('action.permission_reply.invalid_client');
        return {
          success: false,
          errorCode: 'SDK_UNREACHABLE',
          errorMessage: 'Valid OpenCode client not available in context'
        };
      }

      const client = context.client;

      // Determine the response based on payload format
      let responseValue: 'allow' | 'always' | 'deny';
      
      if (isPermissionReplyTarget(payload)) {
        // Target format - use response field directly
        responseValue = (payload as PermissionReplyPayloadTarget).response;
      } else {
        // Compatibility format - map approved boolean to response
        const approved = (payload as PermissionReplyPayloadCompat).approved;
        responseValue = mapApprovedToResponse(approved);
      }

      // Apply the permission response
      const sessionId = payload.toolSessionId || context.sessionId || 'default';
      
      const executionResult = await safeExecute(
        client.postSessionIdPermissionsPermissionId({
          sessionId: sessionId,
          permissionId: payload.permissionId,
          request: {
            decision: this.mapResponseToDecision(responseValue)
          }
        }),
        (error) => `Permission reply failed: ${error instanceof Error ? error.message : String(error)}`
      );

      if (executionResult.success) {
        if (!hasError(executionResult.data)) {
          return {
            success: true,
            data: { 
              permissionId: payload.permissionId, 
              response: responseValue,
              applied: true 
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
          
          context.logger?.error('action.permission_reply.sdk_error_payload', {
            error: errorMessage,
            latencyMs: Date.now() - startedAt,
          });
          return {
            success: false,
            errorCode: 'SDK_UNREACHABLE',
            errorMessage: `Failed to reply to permission request: ${errorMessage}`
          };
        }
      } else {
        context.logger?.error('action.permission_reply.failed', {
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
      context.logger?.error('action.permission_reply.exception', {
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
      context.logger?.debug('action.permission_reply.finished', { latencyMs: Date.now() - startedAt });
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
      } else if (message.includes('invalid') || message.includes('permission')) {
        return 'INVALID_PAYLOAD';
      }
    }
    
    return 'SDK_UNREACHABLE';
  }
}
