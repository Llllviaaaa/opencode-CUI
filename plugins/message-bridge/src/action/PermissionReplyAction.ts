import {
  Action,
  PermissionReplyPayload,
  PermissionReplyResultData,
  ActionResult,
  ActionContext,
  ErrorCode,
  hasError,
  safeExecute,
  stateToErrorCode
} from '../types';

/**
 * Concrete implementation of permission_reply action.
 * Target format only: { permissionId, toolSessionId, response: 'once'|'always'|'reject' }
 */
export class PermissionReplyAction implements Action<'permission_reply', PermissionReplyPayload, PermissionReplyResultData> {
  name: 'permission_reply' = 'permission_reply';

  /**
   * Execute permission reply action
   */
  async execute(payload: PermissionReplyPayload, context: ActionContext): Promise<ActionResult<PermissionReplyResultData>> {
    const startedAt = Date.now();
    context.logger?.info('action.permission_reply.started', {
      permissionId: payload.permissionId,
      toolSessionId: payload.toolSessionId,
      response: payload.response,
    });

    try {
      if (context.connectionState !== 'READY') {
        context.logger?.warn('action.permission_reply.rejected_state', { state: context.connectionState });
        return {
          success: false,
          errorCode: stateToErrorCode(context.connectionState),
          errorMessage: `Agent not ready. Current state: ${context.connectionState}`
        };
      }

      const executionResult = await safeExecute(
        context.client.postSessionIdPermissionsPermissionId({
          path: { id: payload.toolSessionId, permissionID: payload.permissionId },
          body: { response: payload.response },
        }),
        (error) => `Permission reply failed: ${error instanceof Error ? error.message : String(error)}`
      );

      if (executionResult.success) {
        if (!hasError(executionResult.data)) {
          return {
              success: true,
              data: {
              permissionId: payload.permissionId,
              response: payload.response,
              applied: true
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

      context.logger?.error('action.permission_reply.failed', {
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
