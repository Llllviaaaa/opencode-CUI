import { homedir } from "node:os";
import path from "node:path";
import type { OpenClawConfig } from "openclaw/plugin-sdk";
import type { MessageBridgeAccountConfig, MessageBridgeResolvedAccount } from "./types.js";

export const CHANNEL_ID = "message-bridge";
export const DEFAULT_ACCOUNT_ID = "default";
export const LEGACY_ACCOUNTS_MIGRATION_FIX =
  "删除 channels.message-bridge.accounts，并把唯一账号配置迁移到 channels.message-bridge 顶层。";
const NON_DEFAULT_ACCOUNT_ERROR_PREFIX = "message_bridge_single_account_only";

export const DEFAULT_ACCOUNT_CONFIG: MessageBridgeAccountConfig = {
  enabled: true,
  gateway: {
    url: "ws://localhost:8081/ws/agent",
    toolType: "OPENCLAW",
    toolVersion: "0.1.0",
    deviceName: "OpenClaw Gateway",
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

function isRecord(value: unknown): value is GenericRecord {
  return value !== null && typeof value === "object";
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

  const { accounts: _accounts, ...rest } = section;
  return rest;
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

export function getMissingRequiredConfigPaths(account: MessageBridgeAccountConfig): string[] {
  const missing: string[] = [];
  if (!account.gateway.url.trim()) {
    missing.push(`channels.${CHANNEL_ID}.gateway.url`);
  }
  if (!account.auth.ak.trim()) {
    missing.push(`channels.${CHANNEL_ID}.auth.ak`);
  }
  if (!account.auth.sk.trim()) {
    missing.push(`channels.${CHANNEL_ID}.auth.sk`);
  }
  return missing;
}

export function resolveTokenSource(account: MessageBridgeAccountConfig): "config" | "none" {
  return account.auth.ak.trim() || account.auth.sk.trim() ? "config" : "none";
}

export function isAccountConfigured(account: MessageBridgeAccountConfig, cfg: OpenClawConfig): boolean {
  return getMissingRequiredConfigPaths(account).length === 0 && !hasLegacyAccountsConfig(cfg);
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
