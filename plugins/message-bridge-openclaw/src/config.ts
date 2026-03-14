import { homedir } from "node:os";
import path from "node:path";
import type { OpenClawConfig } from "openclaw/plugin-sdk";
import type { MessageBridgeAccountConfig, MessageBridgeResolvedAccount } from "./types.js";

export const CHANNEL_ID = "message-bridge";
export const DEFAULT_ACCOUNT_ID = "default";

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
  const section = readChannelSection(cfg);
  const accounts = section?.accounts;
  if (!isRecord(accounts)) {
    return [DEFAULT_ACCOUNT_ID];
  }
  const ids = Object.keys(accounts);
  return ids.length > 0 ? ids : [DEFAULT_ACCOUNT_ID];
}

export function resolveAccount(cfg: OpenClawConfig, accountId?: string | null): MessageBridgeResolvedAccount {
  const normalizedAccountId = accountId?.trim() || DEFAULT_ACCOUNT_ID;
  const section = readChannelSection(cfg);
  const baseConfig = normalizeAccountConfig(section);
  const accounts = section?.accounts;
  const override = isRecord(accounts) && isRecord(accounts[normalizedAccountId]) ? accounts[normalizedAccountId] : undefined;
  const merged = normalizeAccountConfig(override ? deepMerge(baseConfig as unknown as GenericRecord, override) : baseConfig as unknown as GenericRecord);
  return {
    accountId: normalizedAccountId,
    ...merged,
  };
}

export function describeAccount(account: MessageBridgeResolvedAccount) {
  return {
    accountId: account.accountId,
    name: account.name,
    enabled: account.enabled,
    configured: Boolean(account.gateway.url && account.auth.ak && account.auth.sk),
    tokenSource: account.auth.ak ? "config" : "none",
  };
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
