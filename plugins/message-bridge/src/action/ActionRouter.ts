import { Action, ActionContext, ActionResult } from '../types';
import { ActionRegistry } from './ActionRegistry';

export interface ActionRouter {
  route(actionType: string, payload: unknown, context: ActionContext): Promise<ActionResult>;
  setRegistry(registry: ActionRegistry): void;
  getRegistry(): ActionRegistry | null;
}

export class DefaultActionRouter implements ActionRouter {
  private registry: ActionRegistry | null = null;

  setRegistry(registry: ActionRegistry): void {
    this.registry = registry;
  }

  getRegistry(): ActionRegistry | null {
    return this.registry;
  }

  async route(actionType: string, payload: unknown, context: ActionContext): Promise<ActionResult> {
    const startedAt = Date.now();
    context.logger?.info('router.route.received', {
      action: actionType,
      sessionId: context.sessionId,
      state: context.connectionState,
      payloadType: Array.isArray(payload) ? 'array' : typeof payload,
    });
    if (!this.registry) {
      context.logger?.error('router.route.failed_registry_missing', { action: actionType });
      return {
        success: false,
        errorCode: 'SDK_UNREACHABLE',
        errorMessage: 'ActionRegistry not set. Cannot route action.',
      };
    }

    const action = this.registry.get<Action>(actionType);
    if (!action) {
      context.logger?.warn('router.route.unsupported_action', { action: actionType });
      return {
        success: false,
        errorCode: 'UNSUPPORTED_ACTION',
        errorMessage: `Action not found: ${actionType}`,
      };
    }

    const validation = action.validate(payload);
    if (!validation.valid) {
      context.logger?.warn('router.route.invalid_payload', {
        action: actionType,
        error: validation.error,
      });
      return {
        success: false,
        errorCode: 'INVALID_PAYLOAD',
        errorMessage: validation.error ?? `Invalid payload for action: ${actionType}`,
      };
    }

    const result = await action.execute(payload, context);
    context.logger?.info('router.route.completed', {
      action: actionType,
      success: result.success,
      errorCode: result.errorCode,
      latencyMs: Date.now() - startedAt,
    });
    return result;
  }
}
