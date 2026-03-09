import { getOrCreateRuntime } from './runtime/singleton';
import type { Plugin } from './runtime/types';

export const MessageBridgePlugin: Plugin = async (input) => {
  const runtime = await getOrCreateRuntime(input);

  return {
    event: async ({ event }) => {
      await runtime.handleEvent(event);
    },
  };
};

export default MessageBridgePlugin;
