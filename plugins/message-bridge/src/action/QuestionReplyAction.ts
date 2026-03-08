import {
  Action,
  QuestionReplyPayload,
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
 * Concrete implementation of question_reply action.
 * Accepts payload: { toolSessionId, toolCallId, answer }.
 *
 * Per layer4 protocol, toolCallId is used for protocol-level correlation
 * only and is not passed to SDK. The answer is sent via session.prompt().
 */
export class QuestionReplyAction implements Action<QuestionReplyPayload> {
  name: string = 'question_reply';

  private normalizePayload(payload: unknown): QuestionReplyPayload | null {
    if (!payload || typeof payload !== 'object') {
      return null;
    }

    const p = payload as {
      toolSessionId?: unknown;
      toolCallId?: unknown;
      answer?: unknown;
    };

    const toolSessionId =
      typeof p.toolSessionId === 'string' && p.toolSessionId.trim()
        ? p.toolSessionId
        : null;
    const toolCallId =
      typeof p.toolCallId === 'string' && p.toolCallId.trim()
        ? p.toolCallId
        : null;
    const answer =
      typeof p.answer === 'string' && p.answer.trim()
        ? p.answer
        : null;

    if (!toolSessionId || !toolCallId || !answer) {
      return null;
    }

    return { toolSessionId, toolCallId, answer };
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
    answer: string,
  ): Promise<{ success: true; data: unknown } | { success: false; error: string }> {
    const executionResult = await safeExecute(
      client.session.prompt({
        path: { id: toolSessionId },
        body: { parts: [{ type: 'text', text: answer }] },
      }),
      (error) => getErrorMessage(error),
    );

    if (executionResult.success) {
      if (!hasError(executionResult.data)) {
        return { success: true, data: executionResult.data };
      }

      const sdkError = this.getErrorMessageFromResult(executionResult.data);
      return { success: false, error: `Failed to send question reply: ${sdkError || 'Unknown error'}` };
    }

    return { success: false, error: `Failed to send question reply: ${executionResult.error}` };
  }

  validate(payload: unknown): ValidationResult {
    const normalized = this.normalizePayload(payload);
    if (!normalized) {
      return {
        valid: false,
        error: 'question_reply payload requires toolSessionId, toolCallId, and answer'
      };
    }

    return { valid: true };
  }

  async execute(payload: QuestionReplyPayload, context: ActionContext): Promise<ActionResult> {
    const normalized = this.normalizePayload(payload);
    if (!normalized) {
      return {
        success: false,
        errorCode: 'INVALID_PAYLOAD',
        errorMessage: 'question_reply payload requires toolSessionId, toolCallId, and answer'
      };
    }

    const startedAt = Date.now();
    context.logger?.info('action.question_reply.started', {
      toolSessionId: normalized.toolSessionId,
      toolCallId: normalized.toolCallId,
      answerLength: normalized.answer.length,
    });

    if (context.connectionState !== 'READY') {
      context.logger?.warn('action.question_reply.rejected_state', { state: context.connectionState });
      return {
        success: false,
        errorCode: stateToErrorCode(context.connectionState),
        errorMessage: `Agent not ready. Current state: ${context.connectionState}`
      };
    }

    if (!isOpencodeClient(context.client)) {
      context.logger?.error('action.question_reply.invalid_client');
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
        normalized.answer,
      );

      if (executionResult.success) {
        return {
          success: true,
          data: executionResult.data
        };
      }

      context.logger?.error('action.question_reply.failed', {
        toolSessionId: normalized.toolSessionId,
        toolCallId: normalized.toolCallId,
        error: executionResult.error,
        latencyMs: Date.now() - startedAt,
      });
      return {
        success: false,
        errorCode: this.errorMapper(executionResult.error),
        errorMessage: executionResult.error
      };
    } catch (error) {
      const errorCode = this.errorMapper(error);
      const errorMessage = getErrorMessage(error);
      context.logger?.error('action.question_reply.exception', {
        toolSessionId: normalized.toolSessionId,
        toolCallId: normalized.toolCallId,
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
      context.logger?.debug('action.question_reply.finished', { latencyMs: Date.now() - startedAt });
    }
  }

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
      if (message.includes('timeout') || message.includes('timed out')) {
        return 'SDK_TIMEOUT';
      } else if (message.includes('unreachable') || message.includes('connect')) {
        return 'SDK_UNREACHABLE';
      }
    }

    return 'SDK_UNREACHABLE';
  }
}
