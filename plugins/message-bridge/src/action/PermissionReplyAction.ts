import {
  Action,
  PERMISSION_REPLY_RESPONSES,
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
 * Accepts the gateway protocol shape:
 * { permissionId, toolSessionId, response: 'once'|'always'|'reject' }
 */
export class PermissionReplyAction implements Action<PermissionReplyPayload> {
  name: string = 'permission_reply';

  private normalizePayload(payload: unknown): PermissionReplyPayload | null {
    if (!payload || typeof payload !== 'object') {
      return null;
    }

    const typedPayload = payload as {
      permissionId?: unknown;
      toolSessionId?: unknown;
      response?: unknown;
    };
    const permissionId =
      typeof typedPayload.permissionId === 'string' && typedPayload.permissionId.trim()
        ? typedPayload.permissionId
        : null;
    const toolSessionId =
      typeof typedPayload.toolSessionId === 'string' && typedPayload.toolSessionId.trim()
        ? typedPayload.toolSessionId
        : null;
    const response =
      typeof typedPayload.response === 'string' && PERMISSION_REPLY_RESPONSES.includes(typedPayload.response as PermissionReplyPayload['response'])
        ? (typedPayload.response as PermissionReplyPayload['response'])
        : null;

    if (!permissionId || !toolSessionId || !response) {
      return null;
    }

    return {
      permissionId,
      toolSessionId,
      response,
    };
  }

  /**
   * Validate permission reply payload.
   */
  validate(payload: unknown): ValidationResult {
    const normalized = this.normalizePayload(payload);
    if (!normalized) {
      return {
        valid: false,
        error: `permission_reply payload requires permissionId, toolSessionId, and response in '${PERMISSION_REPLY_RESPONSES.join("', '")}'`
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
    const normalized = this.normalizePayload(payload);
    if (!normalized) {
      return {
        success: false,
        errorCode: 'INVALID_PAYLOAD',
        errorMessage: `permission_reply payload requires permissionId, toolSessionId, and response in '${PERMISSION_REPLY_RESPONSES.join("', '")}'`
      };
    }

    const startedAt = Date.now();
    context.logger?.info('action.permission_reply.started', {
      permissionId: normalized.permissionId,
      toolSessionId: normalized.toolSessionId,
      response: normalized.response,
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

      const executionResult = await safeExecute(
        context.client.postSessionIdPermissionsPermissionId({
          path: { id: normalized.toolSessionId, permissionID: normalized.permissionId },
          body: { response: normalized.response },
        }),
        (error) => `Permission reply failed: ${getErrorMessage(error)}`
      );

      if (executionResult.success) {
        if (!hasError(executionResult.data)) {
          return {
            success: true,
            data: {
              permissionId: normalized.permissionId,
              response: normalized.response,
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
          permissionId: normalized.permissionId,
          toolSessionId: normalized.toolSessionId,
          response: normalized.response,
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
        permissionId: normalized.permissionId,
        toolSessionId: normalized.toolSessionId,
        response: normalized.response,
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
        permissionId: normalized.permissionId,
        toolSessionId: normalized.toolSessionId,
        response: normalized.response,
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
