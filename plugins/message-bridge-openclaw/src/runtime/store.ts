import type { OpenClawPluginApi, PluginRuntime } from "openclaw/plugin-sdk";

let pluginRuntime: PluginRuntime | null = null;

export function setPluginRuntime(runtime: PluginRuntime): void {
  pluginRuntime = runtime;
}

export function getPluginRuntime(): PluginRuntime {
  if (!pluginRuntime) {
    throw new Error("message_bridge_openclaw_runtime_uninitialized");
  }
  return pluginRuntime;
}

export type PluginApi = OpenClawPluginApi;
