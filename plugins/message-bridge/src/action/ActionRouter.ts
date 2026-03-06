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
    if (!this.registry) {
      return {
        success: false,
        errorCode: 'SDK_UNREACHABLE',
        errorMessage: 'ActionRegistry not set. Cannot route action.',
      };
    }

    const action = this.registry.get<Action>(actionType);
    if (!action) {
      return {
        success: false,
        errorCode: 'UNSUPPORTED_ACTION',
        errorMessage: `Action not found: ${actionType}`,
      };
    }

    const validation = action.validate(payload);
    if (!validation.valid) {
      return {
        success: false,
        errorCode: 'INVALID_PAYLOAD',
        errorMessage: validation.error ?? `Invalid payload for action: ${actionType}`,
      };
    }

    return action.execute(payload, context);
  }
}
