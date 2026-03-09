import {
  Action,
  QuestionReplyPayload,
  ValidationResult,
  ActionResult,
  ActionContext,
  ErrorCode,
  isOpencodeClient,
  stateToErrorCode
} from '../types';
import { getErrorDetailsForLog, getErrorMessage } from '../utils/error';

/**
 * Concrete implementation of question_reply action.
 * Accepts payload: { toolSessionId, toolCallId?, answer }.
 * Uses raw question APIs: GET /question + POST /question/{requestID}/reply.
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
        : undefined;
    const answer =
      typeof p.answer === 'string' && p.answer.trim()
        ? p.answer
        : null;

    if (!toolSessionId || !answer) {
      return null;
    }

    return { toolSessionId, toolCallId, answer };
  }

  private readRecord(value: unknown): Record<string, unknown> | undefined {
    return value !== null && typeof value === 'object'
      ? value as Record<string, unknown>
      : undefined;
  }

  private readString(value: unknown): string | undefined {
    return typeof value === 'string' && value.trim() ? value : undefined;
  }

  private extractResultData<T>(result: unknown): T | undefined {
    const resultRecord = this.readRecord(result);
    if (!resultRecord) {
      return undefined;
    }
    if ('data' in resultRecord) {
      return resultRecord.data as T;
    }
    return result as T;
  }

  private async findPendingQuestionRequestId(
    context: ActionContext,
    toolSessionId: string,
    toolCallId?: string,
  ): Promise<string | undefined> {
    const rawClient = this.readRecord((context.client as { _client?: unknown } | undefined)?._client);
    const getFn = rawClient?.get as ((options: Record<string, unknown>) => Promise<unknown>) | undefined;
    if (!getFn) {
      throw new Error('raw client GET unavailable on client');
    }

    const listResult = await getFn({ url: '/question' });
    const pendingQuestions = this.extractResultData<unknown>(listResult);
    const requests = Array.isArray(pendingQuestions)
      ? pendingQuestions.filter((item): item is Record<string, unknown> => item !== null && typeof item === 'object')
      : [];

    const matchedRequests = requests.filter((request) => {
      const sessionID = this.readString(request.sessionID);
      if (sessionID !== toolSessionId) {
        return false;
      }

      if (!toolCallId) {
        return true;
      }

      const tool = this.readRecord(request.tool);
      return this.readString(tool?.callID) === toolCallId;
    });

    if (toolCallId) {
      return this.readString(matchedRequests[0]?.id);
    }

    if (matchedRequests.length !== 1) {
      return undefined;
    }

    return this.readString(matchedRequests[0]?.id);
  }

  validate(payload: unknown): ValidationResult {
    const normalized = this.normalizePayload(payload);
    if (!normalized) {
      return {
        valid: false,
        error: 'question_reply payload requires toolSessionId and answer'
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
        errorMessage: 'question_reply payload requires toolSessionId and answer'
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
      const requestId = await this.findPendingQuestionRequestId(
        context,
        normalized.toolSessionId,
        normalized.toolCallId,
      );
      if (!requestId) {
        return {
          success: false,
          errorCode: 'INVALID_PAYLOAD',
          errorMessage:
            normalized.toolCallId
              ? `Unable to resolve pending question request for toolSessionId=${normalized.toolSessionId}, toolCallId=${normalized.toolCallId}`
              : `Unable to resolve a unique pending question request for toolSessionId=${normalized.toolSessionId}`
        };
      }

      const rawClient = this.readRecord((client as { _client?: unknown })._client);
      const postFn = rawClient?.post as ((options: Record<string, unknown>) => Promise<unknown>) | undefined;
      if (!postFn) {
        return {
          success: false,
          errorCode: 'SDK_UNREACHABLE',
          errorMessage: 'raw client POST unavailable on client'
        };
      }

      await postFn({
        url: '/question/{requestID}/reply',
        path: { requestID: requestId },
        body: { answers: [[normalized.answer]] },
        headers: {
          'Content-Type': 'application/json',
        },
      });

      return {
        success: true,
        data: {
          requestId,
          replied: true,
        },
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
