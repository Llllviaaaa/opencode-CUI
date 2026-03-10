import { Action, ActionResultDataByName, ActionName, ActionPayloadByName } from '../types';

export interface ActionRegistry {
  register(action: Action): void;
  unregister(name: string): void;
  get<K extends ActionName>(name: K): Action<K, ActionPayloadByName[K], ActionResultDataByName[K]> | undefined;
  has(name: string): boolean;
  list(): string[];
  getAllActions(): Map<string, Action>;
}

/**
 * Concrete implementation of ActionRegistry interface
 */
export class DefaultActionRegistry implements ActionRegistry {
  private readonly actions = new Map<string, Action>();

  register(action: Action): void {
    this.actions.set(action.name, action);
  }

  unregister(name: string): void {
    this.actions.delete(name);
  }

  get<K extends ActionName>(name: K): Action<K, ActionPayloadByName[K], ActionResultDataByName[K]> | undefined {
    return this.actions.get(name) as Action<K, ActionPayloadByName[K], ActionResultDataByName[K]> | undefined;
  }

  has(name: string): boolean {
    return this.actions.has(name);
  }

  list(): string[] {
    return Array.from(this.actions.keys());
  }

  getAllActions(): Map<string, Action> {
    return new Map(this.actions);
  }
}
