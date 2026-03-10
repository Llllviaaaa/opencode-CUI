import type { ConnectionState, ErrorCode } from './common';
import type {
  ActionName,
  ActionPayloadByName,
  ActionResultDataByName,
} from '../contracts/downstream-messages';

export interface ActionContext {
  client: unknown;
  connectionState: ConnectionState;
  agentId: string;
  welinkSessionId?: string;
  logger?: {
    debug(message: string, extra?: Record<string, unknown>): void;
    info(message: string, extra?: Record<string, unknown>): void;
    warn(message: string, extra?: Record<string, unknown>): void;
    error(message: string, extra?: Record<string, unknown>): void;
    getTraceId(): string;
  };
}

export type ActionSuccess<TData = void> = TData extends void
  ? { success: true; data?: undefined }
  : { success: true; data: TData };

export interface ActionFailure {
  success: false;
  errorCode?: ErrorCode;
  errorMessage?: string;
}

export type ActionResult<TData = void> = ActionSuccess<TData> | ActionFailure;

export interface Action<
  TName extends ActionName = ActionName,
  TPayload extends ActionPayloadByName[TName] = ActionPayloadByName[TName],
  TData = ActionResultDataByName[TName],
> {
  name: TName;
  execute(payload: TPayload, context: ActionContext): Promise<ActionResult<TData>>;
  errorMapper(error: unknown): ErrorCode;
}
