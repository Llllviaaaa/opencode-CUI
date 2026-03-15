import {
  Action,
  QuestionReplyPayload,
  QuestionReplyResultData,
  ActionResult,
  ActionContext,
  ErrorCode,
  stateToErrorCode,
} from '../types/index.js';
import { getErrorDetailsForLog, getErrorMessage } from '../utils/error.js';

export class QuestionReplyAction implements Action<'question_reply', QuestionReplyPayload, QuestionReplyResultData> {
  name: 'question_reply' = 'question_reply';

  private readRecord(value: unknown): Record<string, unknown> | undefined {
    return value !== null && typeof value === 'object'
      ? (value as Record<string, unknown>)
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
    const listResult = await context.client._client.get({ url: '/question' });
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

  async execute(
    payload: QuestionReplyPayload,
    context: ActionContext,
  ): Promise<ActionResult<QuestionReplyResultData>> {
    const startedAt = Date.now();
    context.logger?.info('action.question_reply.started', {
      toolSessionId: payload.toolSessionId,
      toolCallId: payload.toolCallId,
      answerLength: payload.answer.length,
    });

    if (context.connectionState !== 'READY') {
      context.logger?.warn('action.question_reply.rejected_state', { state: context.connectionState });
      return {
        success: false,
        errorCode: stateToErrorCode(context.connectionState),
        errorMessage: `Agent not ready. Current state: ${context.connectionState}`,
      };
    }

    const client = context.client;

    try {
      const requestId = await this.findPendingQuestionRequestId(
        context,
        payload.toolSessionId,
        payload.toolCallId,
      );
      if (!requestId) {
        return {
          success: false,
          errorCode: 'INVALID_PAYLOAD',
          errorMessage: payload.toolCallId
            ? `Unable to resolve pending question request for toolSessionId=${payload.toolSessionId}, toolCallId=${payload.toolCallId}`
            : `Unable to resolve a unique pending question request for toolSessionId=${payload.toolSessionId}`,
        };
      }

      await client._client.post({
        url: '/question/{requestID}/reply',
        path: { requestID: requestId },
        body: { answers: [[payload.answer]] },
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
        toolSessionId: payload.toolSessionId,
        toolCallId: payload.toolCallId,
        error: errorMessage,
        errorCode,
        ...getErrorDetailsForLog(error),
        latencyMs: Date.now() - startedAt,
      });
      return {
        success: false,
        errorCode,
        errorMessage,
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
      }
      if (message.includes('unreachable') || message.includes('connect') || message.includes('connection')) {
        return 'SDK_UNREACHABLE';
      }
      if (message.includes('not found') || (message.includes('session') && message.includes('not found'))) {
        return 'INVALID_PAYLOAD';
      }
      if (message.includes('abort') || message.includes('cancelled')) {
        return 'INVALID_PAYLOAD';
      }
    } else if (typeof error === 'string') {
      const message = error.toLowerCase();
      if (message.includes('timeout') || message.includes('timed out')) {
        return 'SDK_TIMEOUT';
      }
      if (message.includes('unreachable') || message.includes('connect')) {
        return 'SDK_UNREACHABLE';
      }
    }

    return 'SDK_UNREACHABLE';
  }
}
