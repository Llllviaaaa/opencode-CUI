import {
  Action,
  StatusQueryPayload,
  StatusQueryResultData,
  ActionResult,
  ActionContext,
  ErrorCode,
} from '../types';

/**
 * Concrete implementation of status_query action for online status retrieval
 */
export class StatusQueryAction implements Action<'status_query', StatusQueryPayload, StatusQueryResultData> {
  name: 'status_query' = 'status_query';

  /**
   * Execute status query action
   * Returns online status from health check
   */
  async execute(payload: StatusQueryPayload, context: ActionContext): Promise<ActionResult<StatusQueryResultData>> {
    const startedAt = Date.now();
    context.logger?.debug('action.status_query.started', {
      state: context.connectionState,
    });
    try {
      let opencodeOnline = false;
      const app = (context.client as { app?: { health?: () => Promise<unknown> | unknown } } | null | undefined)?.app;
      if (app?.health) {
        try {
          await app.health();
          opencodeOnline = true;
        } catch {
          opencodeOnline = false;
        }
      }
      
      return {
        success: true,
        data: {
          opencodeOnline,
        }
      };
    } catch (error) {
      const errorCode = this.errorMapper(error);
      const errorMessage = error instanceof Error ? error.message : String(error);
      context.logger?.error('action.status_query.exception', {
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
      context.logger?.debug('action.status_query.finished', { latencyMs: Date.now() - startedAt });
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
