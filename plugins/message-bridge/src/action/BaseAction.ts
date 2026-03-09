export interface BaseAction<T = unknown, R = unknown> {
  execute(payload: T): Promise<R>;
  validate(payload: T): boolean;
  getActionType(): string;
}

/**
 * Abstract base class for creating actions
 */
export abstract class AbstractBaseAction<T = unknown, R = unknown> implements BaseAction<T, R> {
  abstract execute(payload: T): Promise<R>;
  abstract validate(payload: T): boolean;
  abstract getActionType(): string;

  /**
   * Helper method for processing results
   */
  protected processResult(result: R): R {
    return result;
  }
}