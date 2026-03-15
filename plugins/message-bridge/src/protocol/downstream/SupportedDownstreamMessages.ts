import {
  DOWNSTREAM_MESSAGE_TYPES,
  INVOKE_ACTIONS,
  type DownstreamMessageType as SupportedDownstreamMessageType,
  type InvokeAction,
} from '../../contracts/downstream-messages.js';

export const SUPPORTED_DOWNSTREAM_MESSAGE_TYPES = DOWNSTREAM_MESSAGE_TYPES;

export const SUPPORTED_INVOKE_ACTIONS = INVOKE_ACTIONS satisfies readonly InvokeAction[];

const SUPPORTED_DOWNSTREAM_MESSAGE_TYPE_SET = new Set<string>(SUPPORTED_DOWNSTREAM_MESSAGE_TYPES);
const SUPPORTED_INVOKE_ACTION_SET = new Set<string>(SUPPORTED_INVOKE_ACTIONS);

export function isSupportedDownstreamMessageType(value: string): value is SupportedDownstreamMessageType {
  return SUPPORTED_DOWNSTREAM_MESSAGE_TYPE_SET.has(value);
}

export function isSupportedInvokeAction(value: string): value is InvokeAction {
  return SUPPORTED_INVOKE_ACTION_SET.has(value);
}
