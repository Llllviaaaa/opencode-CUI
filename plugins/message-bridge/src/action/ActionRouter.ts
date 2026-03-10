import { ActionContext, ActionResult, ActionResultDataByName, ActionName, ActionPayloadByName } from '../types';
import { ActionRegistry } from './ActionRegistry';

export interface ActionRouter {
  route<K extends ActionName>(
    actionType: K,
    payload: ActionPayloadByName[K],
    context: ActionContext,
  ): Promise<ActionResult<ActionResultDataByName[K]>>;
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

  async route<K extends ActionName>(
    actionType: K,
    payload: ActionPayloadByName[K],
    context: ActionContext,
  ): Promise<ActionResult<ActionResultDataByName[K]>> {
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

    const action = this.registry.get(actionType);
    if (!action) {
      context.logger?.warn('router.route.unsupported_action', { action: actionType });
      return {
        success: false,
        errorCode: 'UNSUPPORTED_ACTION',
        errorMessage: `Action not found: ${actionType}`,
      };
    }

    const result = await action.execute(payload, context);
    context.logger?.info('router.route.completed', {
      action: actionType,
      success: result.success,
      errorCode: result.success ? undefined : result.errorCode,
      latencyMs: Date.now() - startedAt,
    });
    return result;
  }
}
