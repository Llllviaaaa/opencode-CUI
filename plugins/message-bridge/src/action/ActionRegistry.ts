import { Action } from '../types';

export interface ActionRegistry {
  register(action: Action): void;
  unregister(name: string): void;
  get<T extends Action>(name: string): T | undefined;
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

  get<T extends Action>(name: string): T | undefined {
    return this.actions.get(name) as T | undefined;
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