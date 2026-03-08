import type {
  Hooks as OpenCodeHooks,
  Plugin as OpenCodePlugin,
  PluginInput as OpenCodePluginInput,
} from '@opencode-ai/plugin' with { 'resolution-mode': 'import' };

export type PluginInput = OpenCodePluginInput;
export type Hooks = OpenCodeHooks;
export type Plugin = OpenCodePlugin;
export type BridgeEvent = Parameters<NonNullable<Hooks['event']>>[0]['event'];
