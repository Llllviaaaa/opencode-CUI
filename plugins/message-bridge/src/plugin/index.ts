export interface MessageBridgePlugin {
  start(): Promise<void>;
  stop(): Promise<void>;
}

import { MessageBridgePluginClass } from './MessageBridgePlugin';

export { MessageBridgePluginClass };