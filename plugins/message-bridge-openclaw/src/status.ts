import os from "node:os";
import {
  buildBaseAccountStatusSnapshot,
  buildProbeChannelStatusSummary,
  createDefaultChannelRuntimeState,
  type ChannelAccountSnapshot,
  type ChannelStatusIssue,
} from "openclaw/plugin-sdk";
import type { OpenClawConfig } from "openclaw/plugin-sdk";
import { DefaultAkSkAuth } from "./connection/AkSkAuth.js";
import { DefaultGatewayConnection, type GatewayConnection } from "./connection/GatewayConnection.js";
import {
  CHANNEL_ADD_FIX,
  DEFAULT_ACCOUNT_ID,
  LEGACY_ACCOUNTS_MIGRATION_FIX,
  getMissingRequiredConfigPaths,
  hasLegacyAccountsConfig,
  resolveTokenSource,
} from "./config.js";
import type {
  BridgeLogger,
  MessageBridgeProbeResult,
  MessageBridgeResolvedAccount,
  MessageBridgeStatusSnapshot,
} from "./types.js";
import { resolveRegisterMetadata, type RegisterMetadata } from "./runtime/RegisterMetadata.js";

const HEARTBEAT_GRACE_MS = 5_000;

const silentLogger: BridgeLogger = {
  info() {},
  warn() {},
  error() {},
};

type ProbeConnectionFactory = (account: MessageBridgeResolvedAccount) => GatewayConnection;

export type MessageBridgeAccountSnapshot = ChannelAccountSnapshot & {
  connected: boolean;
  gatewayUrl: string | null;
  toolType: string;
  toolVersion: string;
  deviceName: string;
  heartbeatIntervalMs: number;
  runTimeoutMs: number;
  tokenSource: "config" | "none";
  legacyAccountsConfigured: boolean;
  missingConfigFields: string[];
  lastReadyAt: number | null;
  lastHeartbeatAt: number | null;
};

function elapsedMs(startedAt: number, now: () => number): number {
  return Math.max(0, now() - startedAt);
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === "object";
}

function asMessageBridgeSnapshot(value: ChannelAccountSnapshot): MessageBridgeAccountSnapshot {
  return value as MessageBridgeAccountSnapshot;
}

function getMissingConfigFields(snapshot: MessageBridgeAccountSnapshot): string[] {
  return Array.isArray(snapshot.missingConfigFields) ? snapshot.missingConfigFields : [];
}

function isRejectedProbeError(message: string): boolean {
  return message !== "gateway_websocket_error" && message !== "gateway_not_connected";
}

function isAuthRejectedReason(reason: string): boolean {
  return /(ak|sk|auth|credential|forbidden|secret|signature|token|unauthor|未授权|鉴权|凭证|密钥|签名)/i.test(
    reason,
  );
}

function getProbeReason(probe: Record<string, unknown> | null): string {
  if (!probe || typeof probe.reason !== "string") {
    return "";
  }
  return probe.reason.trim();
}

function getHeartbeatThresholdMs(snapshot: MessageBridgeAccountSnapshot): number {
  const heartbeatIntervalMs =
    typeof snapshot.heartbeatIntervalMs === "number" ? snapshot.heartbeatIntervalMs : 0;
  if (heartbeatIntervalMs <= 0) {
    return 0;
  }
  return heartbeatIntervalMs * 2 + HEARTBEAT_GRACE_MS;
}

function isRuntimeHealthyForDuplicateConnection(
  snapshot: MessageBridgeAccountSnapshot,
  nowAt: number,
): boolean {
  if (snapshot.connected !== true || typeof snapshot.lastReadyAt !== "number") {
    return false;
  }

  if (typeof snapshot.lastHeartbeatAt !== "number") {
    return true;
  }

  const heartbeatThresholdMs = getHeartbeatThresholdMs(snapshot);
  if (heartbeatThresholdMs <= 0) {
    return true;
  }

  return nowAt - snapshot.lastHeartbeatAt <= heartbeatThresholdMs;
}

function createProbeConnection(account: MessageBridgeResolvedAccount): GatewayConnection {
  const registerMetadata = resolveRegisterMetadata(silentLogger);
  return new DefaultGatewayConnection({
    url: account.gateway.url,
    reconnectBaseMs: account.gateway.reconnect.baseMs,
    reconnectMaxMs: account.gateway.reconnect.maxMs,
    reconnectExponential: account.gateway.reconnect.exponential,
    heartbeatIntervalMs: account.gateway.heartbeatIntervalMs,
    authPayloadProvider: () => new DefaultAkSkAuth(account.auth.ak, account.auth.sk).generateAuthPayload(),
    registerMessage: {
      type: "register",
      deviceName: registerMetadata.deviceName,
      macAddress: registerMetadata.macAddress,
      os: os.platform(),
      toolType: registerMetadata.toolType,
      toolVersion: registerMetadata.toolVersion,
    },
    logger: silentLogger,
  });
}

export function createDefaultMessageBridgeRuntimeState(): MessageBridgeStatusSnapshot {
  return createDefaultChannelRuntimeState(DEFAULT_ACCOUNT_ID, {
    connected: false,
    lastReadyAt: null,
    lastInboundAt: null,
    lastOutboundAt: null,
    lastHeartbeatAt: null,
    probe: null,
    lastProbeAt: null,
  });
}

export async function probeMessageBridgeAccount(
  params: {
    account: MessageBridgeResolvedAccount;
    timeoutMs: number;
  },
  deps: {
    connectionFactory?: ProbeConnectionFactory;
    now?: () => number;
  } = {},
): Promise<MessageBridgeProbeResult> {
  const now = deps.now ?? Date.now;
  const startedAt = now();
  const connection = deps.connectionFactory?.(params.account) ?? createProbeConnection(params.account);

  return await new Promise((resolve) => {
    let settled = false;

    const finish = (result: MessageBridgeProbeResult) => {
      if (settled) {
        return;
      }
      settled = true;
      clearTimeout(timer);
      try {
        connection.disconnect();
      } catch {
        // ignore disconnect failures in probe teardown
      }
      resolve(result);
    };

    const timer = setTimeout(() => {
      finish({
        ok: false,
        state: "timeout",
        latencyMs: elapsedMs(startedAt, now),
        reason: "probe timed out before READY",
      });
    }, params.timeoutMs);

    connection.on("stateChange", (state) => {
      if (state === "READY") {
        finish({
          ok: true,
          state: "ready",
          latencyMs: elapsedMs(startedAt, now),
        });
        return;
      }

      if (state === "DISCONNECTED") {
        finish({
          ok: false,
          state: "connect_error",
          latencyMs: elapsedMs(startedAt, now),
          reason: "probe disconnected before READY",
        });
      }
    });

    connection.on("error", (error) => {
      const message = error instanceof Error ? error.message : String(error);
      finish({
        ok: false,
        state: isRejectedProbeError(message) ? "rejected" : "connect_error",
        latencyMs: elapsedMs(startedAt, now),
        reason: message,
      });
    });

    connection.connect().catch((error) => {
      const message = error instanceof Error ? error.message : String(error);
      finish({
        ok: false,
        state: "connect_error",
        latencyMs: elapsedMs(startedAt, now),
        reason: message,
      });
    });
  });
}

export function buildMessageBridgeAccountSnapshot(params: {
  account: MessageBridgeResolvedAccount;
  cfg: OpenClawConfig;
  runtime?: MessageBridgeStatusSnapshot | ChannelAccountSnapshot;
  probe?: unknown;
  registerMetadata?: RegisterMetadata;
}): MessageBridgeAccountSnapshot {
  const { account, cfg, probe } = params;
  const runtime = params.runtime as MessageBridgeStatusSnapshot | undefined;
  const registerMetadata = params.registerMetadata ?? resolveRegisterMetadata(silentLogger);
  const missingConfigFields = getMissingRequiredConfigPaths(account, cfg);
  const legacyAccountsConfigured = hasLegacyAccountsConfig(cfg);
  const configured = missingConfigFields.length === 0 && !legacyAccountsConfigured;

  return {
    ...buildBaseAccountStatusSnapshot({
      account: {
        accountId: account.accountId,
        name: account.name,
        enabled: account.enabled,
        configured,
      },
      runtime,
      probe,
    }),
    connected: runtime?.connected ?? false,
    gatewayUrl: account.gateway.url || null,
    toolType: registerMetadata.toolType,
    toolVersion: registerMetadata.toolVersion,
    deviceName: registerMetadata.deviceName,
    heartbeatIntervalMs: account.gateway.heartbeatIntervalMs,
    runTimeoutMs: account.runTimeoutMs,
    tokenSource: resolveTokenSource(account),
    legacyAccountsConfigured,
    missingConfigFields,
    lastInboundAt: runtime?.lastInboundAt ?? null,
    lastOutboundAt: runtime?.lastOutboundAt ?? null,
    lastReadyAt: runtime?.lastReadyAt ?? null,
    lastHeartbeatAt: runtime?.lastHeartbeatAt ?? null,
    lastProbeAt: runtime?.lastProbeAt ?? null,
  };
}

export function buildMessageBridgeChannelSummary(snapshot: ChannelAccountSnapshot): Record<string, unknown> {
  const bridgeSnapshot = asMessageBridgeSnapshot(snapshot);
  return {
    ...buildProbeChannelStatusSummary(snapshot, {
      connected: bridgeSnapshot.connected ?? false,
      lastReadyAt: bridgeSnapshot.lastReadyAt ?? null,
      lastHeartbeatAt: bridgeSnapshot.lastHeartbeatAt ?? null,
    }),
  };
}

function createConfigIssue(params: {
  accountId: string;
  message: string;
  fix: string;
}): ChannelStatusIssue {
  return {
    channel: "message-bridge",
    accountId: params.accountId,
    kind: "config",
    message: params.message,
    fix: params.fix,
  };
}

function createRuntimeIssue(params: {
  accountId: string;
  message: string;
  fix: string;
}): ChannelStatusIssue {
  return {
    channel: "message-bridge",
    accountId: params.accountId,
    kind: "runtime",
    message: params.message,
    fix: params.fix,
  };
}

function createAuthIssue(params: {
  accountId: string;
  message: string;
  fix: string;
}): ChannelStatusIssue {
  return {
    channel: "message-bridge",
    accountId: params.accountId,
    kind: "auth",
    message: params.message,
    fix: params.fix,
  };
}

export function collectMessageBridgeStatusIssues(
  accounts: ChannelAccountSnapshot[],
  now: () => number = Date.now,
): ChannelStatusIssue[] {
  const issues: ChannelStatusIssue[] = [];
  const nowAt = now();

  for (const rawSnapshot of accounts) {
    const snapshot = asMessageBridgeSnapshot(rawSnapshot);
    const probe = isRecord(snapshot.probe) ? snapshot.probe : null;
    const probeReason = getProbeReason(probe);
    const suppressDuplicateConnectionIssue =
      probe?.state === "rejected" &&
      probeReason === "duplicate_connection" &&
      isRuntimeHealthyForDuplicateConnection(snapshot, nowAt);
    const missingConfigFields = getMissingConfigFields(snapshot);
    const heartbeatIntervalMs =
      typeof snapshot.heartbeatIntervalMs === "number" ? snapshot.heartbeatIntervalMs : 0;
    const runTimeoutMs = typeof snapshot.runTimeoutMs === "number" ? snapshot.runTimeoutMs : 0;
    if (snapshot.legacyAccountsConfigured) {
      issues.push(
        createConfigIssue({
          accountId: snapshot.accountId,
          message: `检测到已废弃的 channels.message-bridge.accounts 配置。`,
          fix: LEGACY_ACCOUNTS_MIGRATION_FIX,
        }),
      );
    }

    if (missingConfigFields.length > 0) {
      issues.push(
        createConfigIssue({
          accountId: snapshot.accountId,
          message: `缺少必填配置：${missingConfigFields.join("、")}`,
          fix: CHANNEL_ADD_FIX,
        }),
      );
    }

    if (probe && typeof probe.error === "string" && probe.error.trim()) {
      issues.push(
        createRuntimeIssue({
          accountId: snapshot.accountId,
          message: `探活执行失败：${probe.error.trim()}`,
          fix: "检查 gateway.url、运行环境中的 WebSocket 支持与 ai-gateway 进程状态。",
        }),
      );
    }

    if (probe && probe.state === "rejected" && !suppressDuplicateConnectionIssue) {
      const rawReason = probeReason;
      const reason = rawReason ? `：${rawReason}` : "";
      if (rawReason && isAuthRejectedReason(rawReason)) {
        issues.push(
          createAuthIssue({
            accountId: snapshot.accountId,
            message: `网关鉴权被拒绝${reason}`,
            fix: "检查 channels.message-bridge.auth.ak / auth.sk 是否与 ai-gateway 侧配置一致。",
          }),
        );
      } else {
        issues.push(
          createRuntimeIssue({
            accountId: snapshot.accountId,
            message: `网关拒绝注册${reason}`,
            fix: "检查 ai-gateway 的注册策略、toolType/toolVersion、deviceName 与协议兼容性。",
          }),
        );
      }
    }

    if (probe && probe.state === "connect_error") {
      const reason =
        typeof probe.reason === "string" && probe.reason.trim()
          ? `：${probe.reason.trim()}`
          : "";
      issues.push(
        createRuntimeIssue({
          accountId: snapshot.accountId,
          message: `探活无法连接 ai-gateway${reason}`,
          fix: "检查 gateway.url、网络连通性和 ai-gateway 进程状态。",
        }),
      );
    }

    if (probe && probe.state === "timeout") {
      issues.push(
        createRuntimeIssue({
          accountId: snapshot.accountId,
          message: "探活在进入 READY 前超时。",
          fix: "检查 ai-gateway 当前负载、鉴权链路与网络时延。",
        }),
      );
    }

    if (typeof snapshot.lastError === "string" && snapshot.lastError.trim()) {
      issues.push(
        createRuntimeIssue({
          accountId: snapshot.accountId,
          message: `最近一次运行错误：${snapshot.lastError.trim()}`,
          fix: "结合 ai-gateway 日志与 bridge.chat.failed 诊断链路问题。",
        }),
      );
    }

    if (snapshot.running !== true) {
      continue;
    }

    const heartbeatThresholdMs = getHeartbeatThresholdMs(snapshot);
    if (
      heartbeatIntervalMs > 0 &&
      typeof snapshot.lastHeartbeatAt === "number" &&
      nowAt - snapshot.lastHeartbeatAt > heartbeatThresholdMs
    ) {
      issues.push(
        createRuntimeIssue({
          accountId: snapshot.accountId,
          message: "心跳超过阈值未更新，可能已与 ai-gateway 断连。",
          fix: "检查 gateway 连接状态与 heartbeatIntervalMs 配置，必要时重启 channel。",
        }),
      );
    }

    const latestActivityAt = Math.max(snapshot.lastInboundAt ?? 0, snapshot.lastOutboundAt ?? 0);
    const activityThresholdMs = Math.max(
      runTimeoutMs,
      heartbeatIntervalMs * 3,
    );
    if (activityThresholdMs > 0 && latestActivityAt > 0 && nowAt - latestActivityAt > activityThresholdMs) {
      issues.push(
        createRuntimeIssue({
          accountId: snapshot.accountId,
          message: "最近收发活动超过阈值未更新，bridge 可能已卡住。",
          fix: "检查 ai-gateway 链路与 runTimeoutMs 配置，必要时重启 channel。",
        }),
      );
    }
  }

  return issues;
}
