import type { OpenClawPluginApi } from "openclaw/plugin-sdk";
import { emptyPluginConfigSchema } from "openclaw/plugin-sdk";
import { messageBridgePlugin } from "./channel.js";
import { setPluginRuntime } from "./runtime/store.js";

const plugin: {
  id: string;
  name: string;
  description: string;
  configSchema: ReturnType<typeof emptyPluginConfigSchema>;
  register: (api: OpenClawPluginApi) => void;
} = {
  id: "message-bridge",
  name: "Message Bridge",
  description: "Bridge ai-gateway sessions into OpenClaw",
  configSchema: emptyPluginConfigSchema(),
  register(api: OpenClawPluginApi) {
    setPluginRuntime(api.runtime);
    api.registerChannel({ plugin: messageBridgePlugin });
  },
};

export default plugin;
export { messageBridgePlugin };
export * from "./OpenClawGatewayBridge.js";
export * from "./protocol/downstream.js";
export * from "./config.js";
export * from "./runtime/RegisterMetadata.js";
