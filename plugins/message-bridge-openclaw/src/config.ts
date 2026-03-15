import { homedir } from "node:os";
import path from "node:path";
import {
  deleteAccountFromConfigSection,
  setAccountEnabledInConfigSection,
  type ChannelSetupInput,
  type OpenClawConfig,
} from "openclaw/plugin-sdk";
import type { MessageBridgeAccountConfig, MessageBridgeResolvedAccount } from "./types.js";

export const CHANNEL_ID = "message-bridge";
export const DEFAULT_ACCOUNT_ID = "default";
export const LEGACY_ACCOUNTS_MIGRATION_FIX =
  "删除 channels.message-bridge.accounts，并把唯一账号配置迁移到 channels.message-bridge 顶层。";
export const CHANNEL_ADD_FIX =
  "运行 openclaw channels add --channel message-bridge --url <gateway-url> --token <ak> --password <sk>。";
const NON_DEFAULT_ACCOUNT_ERROR_PREFIX = "message_bridge_single_account_only";

export const DEFAULT_ACCOUNT_CONFIG: MessageBridgeAccountConfig = {
  enabled: true,
  gateway: {
    url: "ws://localhost:8081/ws/agent",
    heartbeatIntervalMs: 30_000,
    reconnect: {
      baseMs: 1_000,
      maxMs: 30_000,
      exponential: true,
    },
  },
  auth: {
    ak: "",
    sk: "",
  },
  agentIdPrefix: "message-bridge",
  runTimeoutMs: 300_000,
};

type GenericRecord = Record<string, unknown>;
type MessageBridgeSetupInput = Pick<ChannelSetupInput, "name" | "password" | "token" | "url" | "useEnv">;

const DEPRECATED_GATEWAY_FIELDS = new Set(["toolType", "toolVersion", "deviceName", "macAddress"]);

function isRecord(value: unknown): value is GenericRecord {
  return value !== null && typeof value === "object";
}

function trimOrUndefined(value: unknown): string | undefined {
  if (typeof value !== "string") {
    return undefined;
  }

  const trimmed = value.trim();
  return trimmed ? trimmed : undefined;
}

function readChannelSection(cfg: OpenClawConfig): GenericRecord | undefined {
  const channels = (cfg as GenericRecord).channels;
  if (!isRecord(channels)) {
    return undefined;
  }
  const section = channels[CHANNEL_ID];
  return isRecord(section) ? section : undefined;
}

function stripLegacyAccounts(section: GenericRecord | undefined): GenericRecord | undefined {
  if (!section) {
    return undefined;
  }

  const { accounts: _accounts, gateway, ...rest } = section;
  const nextGateway = isRecord(gateway)
    ? Object.fromEntries(Object.entries(gateway).filter(([key]) => !DEPRECATED_GATEWAY_FIELDS.has(key)))
    : gateway;
  return {
    ...rest,
    ...(isRecord(nextGateway) ? { gateway: nextGateway } : {}),
  };
}

function getSectionField(section: GenericRecord | undefined, key: string): GenericRecord | undefined {
  const value = section?.[key];
  return isRecord(value) ? value : undefined;
}

function deepMerge<T extends GenericRecord>(base: T, override: GenericRecord | undefined): T {
  if (!override) {
    return structuredClone(base);
  }

  const next: GenericRecord = structuredClone(base);
  for (const [key, value] of Object.entries(override)) {
    if (isRecord(next[key]) && isRecord(value)) {
      next[key] = deepMerge(next[key] as GenericRecord, value);
      continue;
    }
    next[key] = value;
  }
  return next as T;
}

function normalizeAccountConfig(raw: GenericRecord | undefined): MessageBridgeAccountConfig {
  return deepMerge(DEFAULT_ACCOUNT_CONFIG as unknown as GenericRecord, raw) as unknown as MessageBridgeAccountConfig;
}

export function listAccountIds(cfg: OpenClawConfig): string[] {
  void cfg;
  return [DEFAULT_ACCOUNT_ID];
}

export function hasLegacyAccountsConfig(cfg: OpenClawConfig): boolean {
  const section = readChannelSection(cfg);
  return isRecord(section?.accounts);
}

export function resolveNonDefaultAccountError(accountId: string): Error {
  return new Error(
    `${NON_DEFAULT_ACCOUNT_ERROR_PREFIX}: Message Bridge 只支持 default 单账号，收到 accountId=${accountId}`,
  );
}

function assertSupportedAccountId(accountId?: string | null): string {
  const normalizedAccountId = accountId?.trim() || DEFAULT_ACCOUNT_ID;
  if (normalizedAccountId !== DEFAULT_ACCOUNT_ID) {
    throw resolveNonDefaultAccountError(normalizedAccountId);
  }
  return normalizedAccountId;
}

export function resolveSupportedAccountId(accountId?: string | null): string {
  return assertSupportedAccountId(accountId);
}

export function getMissingRequiredConfigPaths(
  account: MessageBridgeAccountConfig,
  cfg?: OpenClawConfig,
): string[] {
  const section = cfg ? stripLegacyAccounts(readChannelSection(cfg)) : undefined;
  const gatewaySection = cfg ? getSectionField(section, "gateway") : undefined;
  const authSection = cfg ? getSectionField(section, "auth") : undefined;
  const gatewayUrl = cfg ? trimOrUndefined(gatewaySection?.url) : trimOrUndefined(account.gateway.url);
  const authAk = cfg ? trimOrUndefined(authSection?.ak) : trimOrUndefined(account.auth.ak);
  const authSk = cfg ? trimOrUndefined(authSection?.sk) : trimOrUndefined(account.auth.sk);
  const missing: string[] = [];
  if (!gatewayUrl) {
    missing.push(`channels.${CHANNEL_ID}.gateway.url`);
  }
  if (!authAk) {
    missing.push(`channels.${CHANNEL_ID}.auth.ak`);
  }
  if (!authSk) {
    missing.push(`channels.${CHANNEL_ID}.auth.sk`);
  }
  return missing;
}

export function resolveTokenSource(account: MessageBridgeAccountConfig): "config" | "none" {
  return account.auth.ak.trim() || account.auth.sk.trim() ? "config" : "none";
}

export function isAccountConfigured(account: MessageBridgeAccountConfig, cfg: OpenClawConfig): boolean {
  return getMissingRequiredConfigPaths(account, cfg).length === 0 && !hasLegacyAccountsConfig(cfg);
}

export function resolveAccount(cfg: OpenClawConfig, accountId?: string | null): MessageBridgeResolvedAccount {
  const normalizedAccountId = assertSupportedAccountId(accountId);
  const section = readChannelSection(cfg);
  const merged = normalizeAccountConfig(stripLegacyAccounts(section));
  return {
    accountId: normalizedAccountId,
    ...merged,
  };
}

export function describeAccount(account: MessageBridgeResolvedAccount, cfg: OpenClawConfig) {
  return {
    accountId: account.accountId,
    name: account.name,
    enabled: account.enabled,
    configured: isAccountConfigured(account, cfg),
    tokenSource: resolveTokenSource(account),
  };
}

export function resolveUnconfiguredReason(cfg: OpenClawConfig): string {
  if (hasLegacyAccountsConfig(cfg)) {
    return `channels.${CHANNEL_ID}.accounts 已废弃。${LEGACY_ACCOUNTS_MIGRATION_FIX}`;
  }

  return `channels.${CHANNEL_ID}.gateway.url、channels.${CHANNEL_ID}.auth.ak、channels.${CHANNEL_ID}.auth.sk 为必填项`;
}

function validateGatewayUrl(url: string): string | null {
  try {
    const parsed = new URL(url);
    if (parsed.protocol !== "ws:" && parsed.protocol !== "wss:") {
      return "Message Bridge 的 gateway.url 必须使用 ws:// 或 wss://。";
    }
    return null;
  } catch {
    return "Message Bridge 的 gateway.url 不是合法的 WebSocket URL。";
  }
}

export function validateMessageBridgeSetupInput(params: {
  cfg: OpenClawConfig;
  accountId: string;
  input: MessageBridgeSetupInput;
}): string | null {
  const { cfg, accountId, input } = params;

  try {
    resolveSupportedAccountId(accountId);
  } catch (error) {
    return error instanceof Error ? error.message : String(error);
  }

  if (hasLegacyAccountsConfig(cfg)) {
    return `检测到已废弃的 channels.${CHANNEL_ID}.accounts 配置。${LEGACY_ACCOUNTS_MIGRATION_FIX}`;
  }

  if (input.useEnv) {
    return "Message Bridge 当前不支持 --use-env，请显式传入 --url、--token、--password。";
  }

  const nextCfg = applyMessageBridgeSetupConfig({
    cfg,
    accountId,
    input,
  });
  const nextAccount = resolveAccount(nextCfg, accountId);
  const missing = getMissingRequiredConfigPaths(nextAccount, nextCfg);
  if (missing.length > 0) {
    return `Message Bridge 缺少必填配置：${missing.join("、")}。${CHANNEL_ADD_FIX}`;
  }

  const urlError = validateGatewayUrl(nextAccount.gateway.url);
  if (urlError) {
    return urlError;
  }

  return null;
}

export function applyMessageBridgeSetupConfig(params: {
  cfg: OpenClawConfig;
  accountId: string;
  input: MessageBridgeSetupInput;
}): OpenClawConfig {
  const normalizedAccountId = resolveSupportedAccountId(params.accountId);
  const section = stripLegacyAccounts(readChannelSection(params.cfg));
  const gatewaySection = getSectionField(section, "gateway");
  const authSection = getSectionField(section, "auth");
  const nextName = params.input.name === undefined ? section?.name : trimOrUndefined(params.input.name);
  const nextGatewayUrl = trimOrUndefined(params.input.url);
  const nextAk = trimOrUndefined(params.input.token);
  const nextSk = trimOrUndefined(params.input.password);

  void normalizedAccountId;

  return {
    ...params.cfg,
    channels: {
      ...params.cfg.channels,
      [CHANNEL_ID]: {
        ...section,
        enabled: true,
        ...(nextName !== undefined ? { name: nextName } : {}),
        gateway: {
          ...gatewaySection,
          ...(nextGatewayUrl !== undefined ? { url: nextGatewayUrl } : {}),
        },
        auth: {
          ...authSection,
          ...(nextAk !== undefined ? { ak: nextAk } : {}),
          ...(nextSk !== undefined ? { sk: nextSk } : {}),
        },
      },
    },
  };
}

export function setMessageBridgeAccountEnabled(params: {
  cfg: OpenClawConfig;
  accountId: string;
  enabled: boolean;
}): OpenClawConfig {
  resolveSupportedAccountId(params.accountId);
  return setAccountEnabledInConfigSection({
    cfg: params.cfg,
    sectionKey: CHANNEL_ID,
    accountId: params.accountId,
    enabled: params.enabled,
    allowTopLevel: true,
  });
}

export function deleteMessageBridgeAccount(params: {
  cfg: OpenClawConfig;
  accountId: string;
}): OpenClawConfig {
  resolveSupportedAccountId(params.accountId);
  return deleteAccountFromConfigSection({
    cfg: params.cfg,
    sectionKey: CHANNEL_ID,
    accountId: params.accountId,
    clearBaseFields: ["enabled", "name", "gateway", "auth", "agentIdPrefix", "runTimeoutMs"],
  });
}

export function resolveConfigSearchPaths(workspaceDir?: string): string[] {
  const paths: string[] = [];
  if (workspaceDir) {
    paths.push(path.join(workspaceDir, ".opencode", "message-bridge-openclaw.jsonc"));
    paths.push(path.join(workspaceDir, ".opencode", "message-bridge-openclaw.json"));
  }
  paths.push(path.join(homedir(), ".config", "openclaw", "message-bridge-openclaw.jsonc"));
  paths.push(path.join(homedir(), ".config", "openclaw", "message-bridge-openclaw.json"));
  return paths;
}
