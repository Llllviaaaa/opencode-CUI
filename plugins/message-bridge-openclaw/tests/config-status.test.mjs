import test from "node:test";
import assert from "node:assert/strict";
import Ajv from "ajv";
import { messageBridgePlugin } from "../dist/index.js";
import {
  applyMessageBridgeSetupConfig,
  deleteMessageBridgeAccount,
  isAccountConfigured,
  listAccountIds,
  resolveAccount,
  resolveSupportedAccountId,
  resolveUnconfiguredReason,
  setMessageBridgeAccountEnabled,
  validateMessageBridgeSetupInput,
} from "../dist/config.js";
import {
  buildMessageBridgeAccountSnapshot,
  buildMessageBridgeChannelSummary,
  collectMessageBridgeStatusIssues,
  probeMessageBridgeAccount,
} from "../dist/status.js";

function createConfig(overrides = {}) {
  return {
    channels: {
      "message-bridge": {
        gateway: {
          url: "ws://localhost:8081/ws/agent",
        },
        auth: {
          ak: "ak",
          sk: "sk",
        },
        ...overrides,
      },
    },
  };
}

function createAccount(overrides = {}) {
  return {
    accountId: "default",
    enabled: true,
    gateway: {
      url: "ws://localhost:8081/ws/agent",
      toolType: "OPENCLAW",
      toolVersion: "0.1.0",
      deviceName: "test-device",
      heartbeatIntervalMs: 30_000,
      reconnect: {
        baseMs: 1_000,
        maxMs: 30_000,
        exponential: true,
      },
    },
    auth: {
      ak: "ak",
      sk: "sk",
    },
    agentIdPrefix: "message-bridge",
    runTimeoutMs: 300_000,
    ...overrides,
  };
}

class FakeProbeConnection {
  constructor(mode) {
    this.mode = mode;
    this.handlers = new Map();
    this.disconnected = false;
  }

  async connect() {
    this.handlers.get("stateChange")?.("CONNECTING");
    this.handlers.get("stateChange")?.("CONNECTED");
    queueMicrotask(() => {
      if (this.mode === "ready") {
        this.handlers.get("stateChange")?.("READY");
        return;
      }
      if (this.mode === "rejected") {
        this.handlers.get("error")?.(new Error("bad ak/sk"));
        return;
      }
      if (this.mode === "rejected_runtime") {
        this.handlers.get("error")?.(new Error("unsupported tool version"));
        return;
      }
      if (this.mode === "connect_error") {
        this.handlers.get("error")?.(new Error("gateway_websocket_error"));
        return;
      }
      if (this.mode === "disconnect_before_ready") {
        this.handlers.get("stateChange")?.("DISCONNECTED");
      }
    });
  }

  disconnect() {
    this.disconnected = true;
  }

  send() {}

  getState() {
    return "DISCONNECTED";
  }

  isConnected() {
    return false;
  }

  on(event, listener) {
    this.handlers.set(event, listener);
    return this;
  }
}

test("message bridge config schema accepts single-account config", async () => {
  const ajv = new Ajv({
    allErrors: true,
    strict: false,
  });
  const validate = ajv.compile(messageBridgePlugin.configSchema.schema);

  const valid = validate({
    gateway: {
      url: "ws://localhost:8081/ws/agent",
    },
    auth: {
      ak: "ak",
      sk: "sk",
    },
  });

  assert.equal(valid, true);
});

test("message bridge config schema rejects legacy accounts config and exposes migration hint", async () => {
  const ajv = new Ajv({
    allErrors: true,
    strict: false,
  });
  const validate = ajv.compile(messageBridgePlugin.configSchema.schema);

  const valid = validate({
    gateway: {
      url: "ws://localhost:8081/ws/agent",
    },
    auth: {
      ak: "ak",
      sk: "sk",
    },
    accounts: {
      default: {
        gateway: {
          url: "ws://localhost:8081/ws/agent",
        },
        auth: {
          ak: "ak",
          sk: "sk",
        },
      },
    },
  });

  assert.equal(valid, false);
  const cfg = createConfig({
    accounts: {
      default: {
        gateway: {
          url: "ws://localhost:8081/ws/agent",
        },
        auth: {
          ak: "ak",
          sk: "sk",
        },
      },
    },
  });
  const account = resolveAccount(cfg);
  assert.match(resolveUnconfiguredReason(cfg), /accounts 已废弃/);
});

test("message bridge listAccountIds is fixed to default and rejects non-default account ids", async () => {
  const cfg = createConfig({
    accounts: {
      secondary: {
        gateway: {
          url: "ws://localhost:8081/ws/agent",
        },
        auth: {
          ak: "secondary-ak",
          sk: "secondary-sk",
        },
      },
    },
  });

  assert.deepEqual(listAccountIds(cfg), ["default"]);
  assert.throws(() => resolveAccount(cfg, "secondary"), /single_account_only/);
});

test("probeMessageBridgeAccount covers ready, rejected, connect_error, disconnect-before-ready and timeout", async () => {
  const account = createAccount();

  const ready = await probeMessageBridgeAccount(
    {
      account,
      timeoutMs: 50,
    },
    {
      connectionFactory: () => new FakeProbeConnection("ready"),
    },
  );
  assert.deepEqual(ready.ok, true);
  assert.equal(ready.state, "ready");

  const rejected = await probeMessageBridgeAccount(
    {
      account,
      timeoutMs: 50,
    },
    {
      connectionFactory: () => new FakeProbeConnection("rejected"),
    },
  );
  assert.deepEqual(rejected, {
    ok: false,
    state: "rejected",
    latencyMs: rejected.latencyMs,
    reason: "bad ak/sk",
  });

  const connectError = await probeMessageBridgeAccount(
    {
      account,
      timeoutMs: 50,
    },
    {
      connectionFactory: () => new FakeProbeConnection("connect_error"),
    },
  );
  assert.equal(connectError.ok, false);
  assert.equal(connectError.state, "connect_error");
  assert.equal(connectError.reason, "gateway_websocket_error");

  const disconnectBeforeReady = await probeMessageBridgeAccount(
    {
      account,
      timeoutMs: 50,
    },
    {
      connectionFactory: () => new FakeProbeConnection("disconnect_before_ready"),
    },
  );
  assert.equal(disconnectBeforeReady.ok, false);
  assert.equal(disconnectBeforeReady.state, "connect_error");
  assert.match(disconnectBeforeReady.reason, /disconnected before READY/);

  const timeout = await probeMessageBridgeAccount(
    {
      account,
      timeoutMs: 10,
    },
    {
      connectionFactory: () => new FakeProbeConnection("timeout"),
    },
  );
  assert.equal(timeout.ok, false);
  assert.equal(timeout.state, "timeout");
  assert.match(timeout.reason, /timed out/);
});

test("single-account config still requires an explicit gateway url and setup writes top-level config", async () => {
  const cfgWithoutUrl = {
    channels: {
      "message-bridge": {
        auth: {
          ak: "ak",
          sk: "sk",
        },
      },
    },
  };
  const unresolvedAccount = resolveAccount(cfgWithoutUrl);
  assert.equal(isAccountConfigured(unresolvedAccount, cfgWithoutUrl), false);
  assert.match(resolveUnconfiguredReason(cfgWithoutUrl), /gateway.url/);

  assert.equal(resolveSupportedAccountId("default"), "default");
  assert.throws(() => resolveSupportedAccountId("secondary"), /single_account_only/);

  const setupValidationError = validateMessageBridgeSetupInput({
    cfg: {
      channels: {},
    },
    accountId: "default",
    input: {
      url: "ws://localhost:8081/ws/agent",
      token: "ak",
      password: "sk",
      deviceName: "cli-device",
    },
  });
  assert.equal(setupValidationError, null);

  const nextCfg = applyMessageBridgeSetupConfig({
    cfg: {
      channels: {},
    },
    accountId: "default",
    input: {
      name: "Primary bridge",
      url: "ws://localhost:8081/ws/agent",
      token: "ak",
      password: "sk",
      deviceName: "cli-device",
    },
  });
  assert.equal(nextCfg.channels["message-bridge"].name, "Primary bridge");
  assert.equal(nextCfg.channels["message-bridge"].enabled, true);
  assert.equal(nextCfg.channels["message-bridge"].gateway.url, "ws://localhost:8081/ws/agent");
  assert.equal(nextCfg.channels["message-bridge"].gateway.deviceName, "cli-device");
  assert.equal(nextCfg.channels["message-bridge"].auth.ak, "ak");
  assert.equal(nextCfg.channels["message-bridge"].auth.sk, "sk");

  const configuredAccount = resolveAccount(nextCfg);
  assert.equal(isAccountConfigured(configuredAccount, nextCfg), true);

  const disabledCfg = setMessageBridgeAccountEnabled({
    cfg: nextCfg,
    accountId: "default",
    enabled: false,
  });
  assert.equal(disabledCfg.channels["message-bridge"].enabled, false);

  const renamedCfg = messageBridgePlugin.setup.applyAccountName({
    cfg: disabledCfg,
    accountId: "default",
    name: "Renamed bridge",
  });
  assert.equal(renamedCfg.channels["message-bridge"].name, "Renamed bridge");
  assert.equal(renamedCfg.channels["message-bridge"].enabled, false);

  const deletedCfg = deleteMessageBridgeAccount({
    cfg: renamedCfg,
    accountId: "default",
  });
  assert.equal(deletedCfg.channels?.["message-bridge"], undefined);
});

test("buildMessageBridgeAccountSnapshot and buildChannelSummary expose operational fields", async () => {
  const account = createAccount();
  const snapshot = buildMessageBridgeAccountSnapshot({
    account,
    cfg: createConfig(),
    runtime: {
      accountId: "default",
      running: true,
      connected: true,
      lastStartAt: 10,
      lastStopAt: 5,
      lastError: null,
      lastReadyAt: 12,
      lastInboundAt: 15,
      lastOutboundAt: 18,
      lastHeartbeatAt: 20,
      probe: null,
      lastProbeAt: 21,
    },
    probe: {
      ok: true,
      state: "ready",
      latencyMs: 42,
    },
  });

  assert.equal(snapshot.gatewayUrl, "ws://localhost:8081/ws/agent");
  assert.equal(snapshot.toolType, "OPENCLAW");
  assert.equal(snapshot.toolVersion, "0.1.0");
  assert.equal(snapshot.deviceName, "test-device");
  assert.equal(snapshot.heartbeatIntervalMs, 30_000);
  assert.equal(snapshot.runTimeoutMs, 300_000);
  assert.equal(snapshot.tokenSource, "config");
  assert.equal(snapshot.connected, true);
  assert.equal(snapshot.lastReadyAt, 12);
  assert.equal(snapshot.lastHeartbeatAt, 20);
  assert.equal(snapshot.lastProbeAt, 21);
  assert.equal(snapshot.probe.state, "ready");

  const summary = buildMessageBridgeChannelSummary(snapshot);
  assert.deepEqual(summary, {
    configured: true,
    running: true,
    lastStartAt: 10,
    lastStopAt: 5,
    lastError: null,
    connected: true,
    lastReadyAt: 12,
    lastHeartbeatAt: 20,
    probe: {
      ok: true,
      state: "ready",
      latencyMs: 42,
    },
    lastProbeAt: 21,
  });
});

test("collectMessageBridgeStatusIssues reports config, auth and runtime problems", async () => {
  const issues = collectMessageBridgeStatusIssues(
    [
      {
        accountId: "default",
        enabled: true,
        configured: false,
        running: true,
        connected: true,
        lastError: "gateway dropped",
        probe: {
          ok: false,
          state: "rejected",
          latencyMs: 12,
          reason: "bad ak/sk",
        },
        heartbeatIntervalMs: 1_000,
        runTimeoutMs: 2_000,
        lastHeartbeatAt: 1_000,
        lastInboundAt: 1_000,
        lastOutboundAt: 1_200,
        missingConfigFields: ["channels.message-bridge.auth.sk"],
        legacyAccountsConfigured: true,
      },
      {
        accountId: "default",
        enabled: true,
        configured: true,
        running: true,
        connected: false,
        lastError: null,
        probe: {
          ok: false,
          state: "connect_error",
          latencyMs: 30,
          reason: "gateway_websocket_error",
        },
        heartbeatIntervalMs: 1_000,
        runTimeoutMs: 10_000,
        lastHeartbeatAt: null,
        lastInboundAt: null,
        lastOutboundAt: null,
        missingConfigFields: [],
        legacyAccountsConfigured: false,
      },
      {
        accountId: "default",
        enabled: true,
        configured: true,
        running: true,
        connected: true,
        lastError: null,
        probe: {
          ok: false,
          state: "timeout",
          latencyMs: 50,
          reason: "probe timed out before READY",
        },
        heartbeatIntervalMs: 1_000,
        runTimeoutMs: 2_000,
        lastHeartbeatAt: 1_000,
        lastInboundAt: 1_000,
        lastOutboundAt: 1_200,
        missingConfigFields: [],
        legacyAccountsConfigured: false,
      },
      {
        accountId: "default",
        enabled: true,
        configured: true,
        running: true,
        connected: false,
        lastError: null,
        probe: {
          ok: false,
          state: "rejected",
          latencyMs: 20,
          reason: "unsupported tool version",
        },
        heartbeatIntervalMs: 1_000,
        runTimeoutMs: 2_000,
        lastHeartbeatAt: null,
        lastInboundAt: null,
        lastOutboundAt: null,
        missingConfigFields: [],
        legacyAccountsConfigured: false,
      },
      {
        accountId: "default",
        enabled: true,
        configured: true,
        running: true,
        connected: false,
        lastError: null,
        probe: {
          ok: false,
          error: "WebSocket is not defined",
        },
        heartbeatIntervalMs: 1_000,
        runTimeoutMs: 2_000,
        lastHeartbeatAt: null,
        lastInboundAt: null,
        lastOutboundAt: null,
        missingConfigFields: [],
        legacyAccountsConfigured: false,
      },
    ],
    () => 10_000,
  );

  assert.equal(issues.some((issue) => issue.kind === "config" && /缺少必填配置/.test(issue.message)), true);
  assert.equal(issues.some((issue) => issue.kind === "config" && /accounts/.test(issue.message)), true);
  assert.equal(issues.some((issue) => issue.kind === "auth" && /鉴权被拒绝/.test(issue.message)), true);
  assert.equal(issues.some((issue) => issue.kind === "auth" && /unsupported tool version/.test(issue.message)), false);
  assert.equal(issues.some((issue) => issue.kind === "runtime" && /网关拒绝注册：unsupported tool version/.test(issue.message)), true);
  assert.equal(issues.some((issue) => issue.kind === "runtime" && /无法连接 ai-gateway/.test(issue.message)), true);
  assert.equal(issues.some((issue) => issue.kind === "runtime" && /探活执行失败：WebSocket is not defined/.test(issue.message)), true);
  assert.equal(issues.some((issue) => issue.kind === "runtime" && /探活在进入 READY 前超时/.test(issue.message)), true);
  assert.equal(issues.some((issue) => issue.kind === "runtime" && /最近一次运行错误/.test(issue.message)), true);
  assert.equal(issues.some((issue) => issue.kind === "runtime" && /心跳超过阈值未更新/.test(issue.message)), true);
  assert.equal(issues.some((issue) => issue.kind === "runtime" && /最近收发活动超过阈值未更新/.test(issue.message)), true);
  assert.equal(issues.every((issue) => typeof issue.fix === "string" && issue.fix.length > 0), true);
});

test("message bridge onboarding retries invalid input until the default account is configured", async () => {
  const notes = [];
  const textAnswers = [
    "Primary bridge",
    "http://localhost:8081/ws/agent",
    "",
    "",
    "wizard-device",
    "Primary bridge",
    "ws://localhost:8081/ws/agent",
    "ak",
    "sk",
    "wizard-device",
  ];

  const result = await messageBridgePlugin.onboarding.configure({
    cfg: {
      channels: {},
    },
    runtime: {},
    prompter: {
      async note(message, title) {
        notes.push({ message, title });
      },
      async text() {
        return textAnswers.shift();
      },
    },
    options: {},
    accountOverrides: {},
    shouldPromptAccountIds: false,
    forceAllowFrom: false,
  });

  assert.equal(result.accountId, "default");
  assert.equal(result.cfg.channels["message-bridge"].name, "Primary bridge");
  assert.equal(result.cfg.channels["message-bridge"].gateway.url, "ws://localhost:8081/ws/agent");
  assert.equal(result.cfg.channels["message-bridge"].gateway.deviceName, "wizard-device");
  assert.equal(result.cfg.channels["message-bridge"].auth.ak, "ak");
  assert.equal(result.cfg.channels["message-bridge"].auth.sk, "sk");
  assert.equal(notes.some((entry) => /gateway.url/.test(entry.message) || /WebSocket URL/.test(entry.message)), true);
});

test("message bridge onboarding skips legacy accounts config instead of reporting success", async () => {
  const notes = [];
  const configureInteractive = messageBridgePlugin.onboarding.configureInteractive;
  assert.equal(typeof configureInteractive, "function");

  const result = await configureInteractive({
    cfg: {
      channels: {
        "message-bridge": {
          accounts: {
            legacy: {
              gateway: { url: "ws://legacy/ws" },
              auth: { ak: "ak", sk: "sk" },
            },
          },
        },
      },
    },
    runtime: {},
    prompter: {
      async note(message, title) {
        notes.push({ message, title });
      },
      async text() {
        throw new Error("should not prompt when legacy accounts config is present");
      },
    },
    options: {},
    accountOverrides: {},
    shouldPromptAccountIds: false,
    forceAllowFrom: false,
    configured: false,
    label: "Message Bridge",
  });

  assert.equal(result, "skip");
  assert.equal(
    notes.some((entry) => entry.message.includes("channels.message-bridge.accounts")),
    true,
  );
});
