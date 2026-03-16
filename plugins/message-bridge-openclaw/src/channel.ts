import {
  applyAccountNameToChannelSection,
  type ChannelConfigSchema,
  type ChannelPlugin,
  type OpenClawConfig,
} from "openclaw/plugin-sdk";
import {
  applyMessageBridgeSetupConfig,
  CHANNEL_ID,
  DEFAULT_ACCOUNT_ID,
  deleteMessageBridgeAccount,
  describeAccount,
  isAccountConfigured,
  listAccountIds,
  resolveAccount,
  resolveSupportedAccountId,
  resolveUnconfiguredReason,
  setMessageBridgeAccountEnabled,
  validateMessageBridgeSetupInput,
} from "./config.js";
import { OpenClawGatewayBridge } from "./OpenClawGatewayBridge.js";
import { messageBridgeOnboardingAdapter } from "./onboarding.js";
import { getPluginRuntime } from "./runtime/store.js";
import type { MessageBridgeResolvedAccount } from "./types.js";
import {
  buildMessageBridgeAccountSnapshot,
  buildMessageBridgeChannelSummary,
  collectMessageBridgeStatusIssues,
  createDefaultMessageBridgeRuntimeState,
  probeMessageBridgeAccount,
} from "./status.js";

const activeBridges = new Map<string, OpenClawGatewayBridge>();

const messageBridgeConfigSchema: ChannelConfigSchema = {
  schema: {
    type: "object",
    additionalProperties: false,
    properties: {
      enabled: { type: "boolean" },
      debug: { type: "boolean" },
      name: { type: "string", minLength: 1 },
      gateway: {
        type: "object",
        additionalProperties: false,
        properties: {
          url: { type: "string", minLength: 1 },
          heartbeatIntervalMs: { type: "integer", minimum: 1 },
          reconnect: {
            type: "object",
            additionalProperties: false,
            properties: {
              baseMs: { type: "integer", minimum: 1 },
              maxMs: { type: "integer", minimum: 1 },
              exponential: { type: "boolean" },
            },
          },
        },
        required: ["url"],
      },
      auth: {
        type: "object",
        additionalProperties: false,
        properties: {
          ak: { type: "string", minLength: 1 },
          sk: { type: "string", minLength: 1 },
        },
        required: ["ak", "sk"],
      },
      agentIdPrefix: { type: "string", minLength: 1 },
      runTimeoutMs: { type: "integer", minimum: 1_000 },
    },
    required: ["gateway", "auth"],
  },
  uiHints: {
    "auth.ak": {
      label: "AK",
      sensitive: true,
    },
    "auth.sk": {
      label: "SK",
      sensitive: true,
    },
  },
};

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
  onboarding: messageBridgeOnboardingAdapter,
  reload: {
    configPrefixes: [`channels.${CHANNEL_ID}`],
  },
  configSchema: messageBridgeConfigSchema,
  config: {
    listAccountIds: (cfg: OpenClawConfig) => listAccountIds(cfg),
    resolveAccount: (cfg: OpenClawConfig, accountId?: string | null) => resolveAccount(cfg, accountId),
    defaultAccountId: () => DEFAULT_ACCOUNT_ID,
    setAccountEnabled: ({ cfg, accountId, enabled }) =>
      setMessageBridgeAccountEnabled({
        cfg,
        accountId,
        enabled,
      }),
    deleteAccount: ({ cfg, accountId }) =>
      deleteMessageBridgeAccount({
        cfg,
        accountId,
      }),
    isEnabled: (account) => account.enabled,
    disabledReason: () => "disabled",
    isConfigured: (account, cfg) => isAccountConfigured(account, cfg),
    unconfiguredReason: (_account, cfg) => resolveUnconfiguredReason(cfg),
    describeAccount: (account, cfg) => describeAccount(account, cfg),
  },
  setup: {
    resolveAccountId: ({ accountId }) => resolveSupportedAccountId(accountId),
    applyAccountName: ({ cfg, accountId, name }) =>
      applyAccountNameToChannelSection({
        cfg,
        channelKey: CHANNEL_ID,
        accountId,
        name,
      }),
    validateInput: ({ cfg, accountId, input }) =>
      validateMessageBridgeSetupInput({
        cfg,
        accountId,
        input,
      }),
    applyAccountConfig: ({ cfg, accountId, input }) =>
      applyMessageBridgeSetupConfig({
        cfg,
        accountId,
        input,
      }),
  },
  status: {
    defaultRuntime: createDefaultMessageBridgeRuntimeState(),
    buildChannelSummary: ({ snapshot }) => buildMessageBridgeChannelSummary(snapshot),
    probeAccount: async ({ account, timeoutMs }) => await probeMessageBridgeAccount({ account, timeoutMs }),
    buildAccountSnapshot: ({ account, cfg, runtime, probe }) =>
      buildMessageBridgeAccountSnapshot({
        account,
        cfg,
        runtime,
        probe,
      }),
    collectStatusIssues: (accounts) => collectMessageBridgeStatusIssues(accounts),
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
