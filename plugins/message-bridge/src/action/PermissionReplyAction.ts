import {
  Action,
  PermissionReplyPayload,
  ValidationResult,
  ActionResult,
  ActionContext,
  ErrorCode,
  isOpencodeClient,
  hasError,
  safeExecute,
  stateToErrorCode
} from '../types';
import { getErrorDetailsForLog, getErrorMessage } from '../utils/error';

/**
 * Concrete implementation of permission_reply action.
 * Target format only: { permissionId, toolSessionId, response: 'allow'|'always'|'deny' }
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
   * Validate permission reply payload.
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

    if (typeof typedPayload.toolSessionId !== 'string' || !typedPayload.toolSessionId.trim()) {
      return {
        valid: false,
        error: 'toolSessionId is required and must be a non-empty string'
      };
    }

    const allowedResponses = ['allow', 'always', 'deny'] as const;
    const isValidResponse = allowedResponses.includes(typedPayload.response);
    if (!isValidResponse) {
      return {
        valid: false,
        error: `response must be one of: '${allowedResponses.join("', '")}'`
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

      if (!isOpencodeClient(context.client)) {
        context.logger?.error('action.permission_reply.invalid_client');
        return {
          success: false,
          errorCode: 'SDK_UNREACHABLE',
          errorMessage: 'Valid OpenCode client not available in context'
        };
      }

      const decision = this.mapResponseToDecision(payload.response);
      const executionResult = await safeExecute(
        context.client.postSessionIdPermissionsPermissionId({
          path: { id: payload.toolSessionId, permissionID: payload.permissionId },
          body: { response: decision },
        }),
        (error) => `Permission reply failed: ${getErrorMessage(error)}`
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

        const errorField =
          executionResult.data && typeof executionResult.data === 'object' && 'error' in executionResult.data
            ? (executionResult.data as { error: unknown }).error
            : undefined;
        const errorMessage = errorField !== undefined ? getErrorMessage(errorField) : 'Unknown error';

        context.logger?.error('action.permission_reply.sdk_error_payload', {
          permissionId: payload.permissionId,
          toolSessionId: payload.toolSessionId,
          response: payload.response,
          error: errorMessage,
          ...(errorField !== undefined ? getErrorDetailsForLog(errorField) : {}),
          latencyMs: Date.now() - startedAt,
        });
        return {
          success: false,
          errorCode: 'SDK_UNREACHABLE',
          errorMessage: `Failed to reply to permission request: ${errorMessage}`
        };
      }

      context.logger?.error('action.permission_reply.failed', {
        permissionId: payload.permissionId,
        toolSessionId: payload.toolSessionId,
        response: payload.response,
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
      context.logger?.error('action.permission_reply.exception', {
        permissionId: payload.permissionId,
        toolSessionId: payload.toolSessionId,
        response: payload.response,
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
