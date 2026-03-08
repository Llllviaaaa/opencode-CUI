import {
  Action,
  ChatPayload,
  OpencodeClient,
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
 * Concrete implementation of chat action for sending messages to OpenCode
 */
export class ChatAction implements Action<ChatPayload> {
  name: string = 'chat';

  private normalizePayload(payload: unknown): { toolSessionId: string; text: string } | null {
    if (!payload || typeof payload !== 'object') {
      return null;
    }

    const p = payload as {
      toolSessionId?: unknown;
      text?: unknown;
    };

    const toolSessionId = typeof p.toolSessionId === 'string' && p.toolSessionId.trim()
      ? p.toolSessionId
      : null;
    const text = typeof p.text === 'string' && p.text.trim().length > 0 ? p.text : null;

    if (!toolSessionId || text === null) {
      return null;
    }

    return { toolSessionId, text };
  }

  private getErrorMessageFromResult(data: unknown): string {
    if (data && typeof data === 'object' && 'error' in data) {
      const errorField = (data as { error: unknown }).error;
      if (errorField && typeof errorField === 'object' && 'message' in (errorField as Record<string, unknown>)) {
        const messageField = (errorField as { message: unknown }).message;
        if (typeof messageField === 'string') {
          return messageField;
        }
        return String(messageField);
      }
      return getErrorMessage(errorField);
    }
    return 'Unknown error';
  }

  private async sendPrompt(
    client: OpencodeClient,
    toolSessionId: string,
    text: string,
  ): Promise<{ success: true; data: unknown } | { success: false; error: string }> {
    const executionResult = await safeExecute(
      client.session.prompt({
        path: { id: toolSessionId },
        body: { parts: [{ type: 'text', text }] },
      }),
      (error) => getErrorMessage(error),
    );

    if (executionResult.success) {
      if (!hasError(executionResult.data)) {
        return { success: true, data: executionResult.data };
      }

      const sdkError = this.getErrorMessageFromResult(executionResult.data);
      return { success: false, error: `Failed to send message: ${sdkError || 'Unknown error'}` };
    }

    return { success: false, error: `Failed to send message: ${executionResult.error}` };
  }

  /**
   * Validate chat payload
   */
  validate(payload: unknown): ValidationResult {
    const normalized = this.normalizePayload(payload);
    if (!normalized) {
      return {
        valid: false,
        error: 'chat payload requires toolSessionId and text'
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
    const normalized = this.normalizePayload(payload);
    if (!normalized) {
      return {
        success: false,
        errorCode: 'INVALID_PAYLOAD',
        errorMessage: 'chat payload requires toolSessionId and text'
      };
    }

    const startedAt = Date.now();
    context.logger?.info('action.chat.started', {
      toolSessionId: normalized.toolSessionId,
      messageLength: normalized.text.length,
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
    const client = context.client;

    try {
      const executionResult = await this.sendPrompt(
        client,
        normalized.toolSessionId,
        normalized.text,
      );

      if (executionResult.success) {
        return {
          success: true,
          data: executionResult.data
        };
      }

      context.logger?.error('action.chat.failed', {
        toolSessionId: normalized.toolSessionId,
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
      context.logger?.error('action.chat.exception', {
        toolSessionId: normalized.toolSessionId,
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
