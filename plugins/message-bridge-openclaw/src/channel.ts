import type { ChannelPlugin } from "openclaw/plugin-sdk";
import type { OpenClawConfig } from "openclaw/plugin-sdk";
import { CHANNEL_ID, DEFAULT_ACCOUNT_ID, describeAccount, listAccountIds, resolveAccount } from "./config.js";
import { OpenClawGatewayBridge } from "./OpenClawGatewayBridge.js";
import { getPluginRuntime } from "./runtime/store.js";
import type { MessageBridgeResolvedAccount } from "./types.js";

const activeBridges = new Map<string, OpenClawGatewayBridge>();

export const messageBridgePlugin: ChannelPlugin<MessageBridgeResolvedAccount> = {
  id: CHANNEL_ID,
  meta: {
    id: CHANNEL_ID,
    label: "Message Bridge",
    selectionLabel: "Message Bridge",
    docsPath: "/channels/message-bridge",
    blurb: "Bridge ai-gateway sessions into OpenClaw.",
  },
  capabilities: {
    chatTypes: ["direct"],
    nativeCommands: false,
    blockStreaming: true,
  },
  reload: {
    configPrefixes: [`channels.${CHANNEL_ID}`],
  },
  config: {
    listAccountIds: (cfg: OpenClawConfig) => listAccountIds(cfg),
    resolveAccount: (cfg: OpenClawConfig, accountId?: string | null) => resolveAccount(cfg, accountId),
    defaultAccountId: () => DEFAULT_ACCOUNT_ID,
    isEnabled: (account) => account.enabled,
    isConfigured: (account) => Boolean(account.gateway.url && account.auth.ak && account.auth.sk),
    unconfiguredReason: () => "gateway.url, auth.ak, auth.sk are required",
    describeAccount: (account) => describeAccount(account),
  },
  status: {
    defaultRuntime: {
      accountId: DEFAULT_ACCOUNT_ID,
      running: false,
      connected: false,
      lastStartAt: null,
      lastStopAt: null,
      lastError: null,
    },
  },
  gateway: {
    startAccount: async (ctx) => {
      const account = resolveAccount(ctx.cfg, ctx.accountId);
      const bridge = new OpenClawGatewayBridge({
        account,
        config: ctx.cfg,
        runtime: getPluginRuntime(),
        logger: ctx.log ?? console,
        setStatus: (status) => ctx.setStatus(status),
      });
      activeBridges.set(account.accountId, bridge);
      await bridge.start();
      try {
        await new Promise<void>((resolve) => {
          if (ctx.abortSignal.aborted) {
            resolve();
            return;
          }
          ctx.abortSignal.addEventListener("abort", () => resolve(), { once: true });
        });
      } finally {
        activeBridges.delete(account.accountId);
        await bridge.stop();
      }
    },
    stopAccount: async (ctx) => {
      const account = resolveAccount(ctx.cfg, ctx.accountId);
      const bridge = activeBridges.get(account.accountId);
      if (!bridge) {
        return;
      }
      activeBridges.delete(account.accountId);
      await bridge.stop();
    },
  },
};
