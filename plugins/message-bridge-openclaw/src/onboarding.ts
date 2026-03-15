import type { ChannelOnboardingAdapter } from "openclaw/plugin-sdk";
import {
  CHANNEL_ID,
  DEFAULT_ACCOUNT_ID,
  LEGACY_ACCOUNTS_MIGRATION_FIX,
  applyMessageBridgeSetupConfig,
  describeAccount,
  hasLegacyAccountsConfig,
  isAccountConfigured,
  resolveAccount,
  validateMessageBridgeSetupInput,
} from "./config.js";

const SETUP_TITLE = "Message Bridge setup";
const SETUP_INTRO = [
  "配置 ai-gateway 的 WebSocket 地址以及对应的 AK/SK。",
  "更新现有配置时，凭证留空会保留当前值。",
].join("\n");

function buildSelectionHint(configured: boolean, enabled: boolean, requiresMigration: boolean): string {
  if (requiresMigration) {
    return "migration required";
  }

  if (!configured) {
    return "not configured";
  }

  return enabled ? "configured" : "configured · disabled";
}

function buildLegacyAccountsMessage(): string {
  return `检测到已废弃的 channels.${CHANNEL_ID}.accounts 配置。${LEGACY_ACCOUNTS_MIGRATION_FIX}`;
}

async function promptMessageBridgeSetup(params: {
  cfg: Parameters<ChannelOnboardingAdapter["configure"]>[0]["cfg"];
  prompter: Parameters<ChannelOnboardingAdapter["configure"]>[0]["prompter"];
}): Promise<
  | {
      cfg: Parameters<ChannelOnboardingAdapter["configure"]>[0]["cfg"];
      accountId: string;
    }
  | "skip"
> {
  const { cfg, prompter } = params;

  if (hasLegacyAccountsConfig(cfg)) {
    await prompter.note(buildLegacyAccountsMessage(), SETUP_TITLE);
    return "skip";
  }

  const account = resolveAccount(cfg, DEFAULT_ACCOUNT_ID);
  await prompter.note(SETUP_INTRO, SETUP_TITLE);

  let draft = {
    name: account.name ?? "",
    url: account.gateway.url,
    token: account.auth.ak ?? "",
    password: account.auth.sk ?? "",
  };

  while (true) {
    const name = await prompter.text({
      message: "Account name (optional)",
      placeholder: "Message Bridge",
      initialValue: draft.name,
    });
    const url = await prompter.text({
      message: "Gateway WebSocket URL",
      placeholder: "ws://localhost:8081/ws/agent",
      initialValue: draft.url,
    });
    const ak = await prompter.text({
      message: account.auth.ak ? "AK (留空保持当前值)" : "AK",
      initialValue: draft.token,
    });
    const sk = await prompter.text({
      message: account.auth.sk ? "SK (留空保持当前值)" : "SK",
      initialValue: draft.password,
    });
    draft = {
      name,
      url,
      token: ak,
      password: sk,
    };

    const input = {
      ...(name !== undefined ? { name } : {}),
      ...(url !== undefined ? { url } : {}),
      ...(ak !== undefined ? { token: ak } : {}),
      ...(sk !== undefined ? { password: sk } : {}),
    };
    const validationError = validateMessageBridgeSetupInput({
      cfg,
      accountId: DEFAULT_ACCOUNT_ID,
      input,
    });

    if (!validationError) {
      return {
        cfg: applyMessageBridgeSetupConfig({
          cfg,
          accountId: DEFAULT_ACCOUNT_ID,
          input,
        }),
        accountId: DEFAULT_ACCOUNT_ID,
      };
    }

    await prompter.note(validationError, SETUP_TITLE);
  }
}

export const messageBridgeOnboardingAdapter: ChannelOnboardingAdapter = {
  channel: CHANNEL_ID,
  async getStatus({ cfg }) {
    const account = resolveAccount(cfg, DEFAULT_ACCOUNT_ID);
    const requiresMigration = hasLegacyAccountsConfig(cfg);
    const configured = isAccountConfigured(account, cfg);
    const summary = describeAccount(account, cfg);
    const status = buildSelectionHint(configured, account.enabled, requiresMigration);

    return {
      channel: CHANNEL_ID,
      configured,
      selectionHint: status,
      quickstartScore: configured ? 1 : 0,
      statusLines: [
        requiresMigration
          ? `Message Bridge: migration required`
          : `Message Bridge: ${status}${configured ? ` · ${account.gateway.url}` : ""}`,
        ...(summary.name ? [`name: ${summary.name}`] : []),
        ...(requiresMigration ? [LEGACY_ACCOUNTS_MIGRATION_FIX] : []),
      ],
    };
  },
  async configure({ cfg, prompter }) {
    const result = await promptMessageBridgeSetup({ cfg, prompter });
    if (result === "skip") {
      throw new Error(buildLegacyAccountsMessage());
    }

    return result;
  },
  async configureInteractive({ cfg, prompter }) {
    return await promptMessageBridgeSetup({ cfg, prompter });
  },
};
