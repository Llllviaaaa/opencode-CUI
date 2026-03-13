export interface BridgeEvent {
  type: string;
  [key: string]: unknown;
}

export interface PluginInput {
  client: unknown;
  directory?: string;
  worktree?: string;
}

export interface Hooks {
  event?: (input: { event: BridgeEvent }) => Promise<void> | void;
}

export type Plugin = (input: PluginInput) => Promise<Hooks> | Hooks;
