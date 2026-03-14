import test from "node:test";
import assert from "node:assert/strict";
import Ajv from "ajv";
import { messageBridgePlugin } from "../dist/index.js";
import {
  listAccountIds,
  resolveAccount,
  resolveUnconfiguredReason,
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
      if (this.mode === "connect_error") {
        this.handlers.get("error")?.(new Error("gateway_websocket_error"));
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

test("probeMessageBridgeAccount covers ready, rejected, connect_error and timeout", async () => {
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
    ],
    () => 10_000,
  );

  assert.equal(issues.some((issue) => issue.kind === "config" && /缺少必填配置/.test(issue.message)), true);
  assert.equal(issues.some((issue) => issue.kind === "config" && /accounts/.test(issue.message)), true);
  assert.equal(issues.some((issue) => issue.kind === "auth" && /鉴权被拒绝/.test(issue.message)), true);
  assert.equal(issues.some((issue) => issue.kind === "runtime" && /无法连接 ai-gateway/.test(issue.message)), true);
  assert.equal(issues.some((issue) => issue.kind === "runtime" && /探活在进入 READY 前超时/.test(issue.message)), true);
  assert.equal(issues.some((issue) => issue.kind === "runtime" && /最近一次运行错误/.test(issue.message)), true);
  assert.equal(issues.some((issue) => issue.kind === "runtime" && /心跳超过阈值未更新/.test(issue.message)), true);
  assert.equal(issues.some((issue) => issue.kind === "runtime" && /最近收发活动超过阈值未更新/.test(issue.message)), true);
  assert.equal(issues.every((issue) => typeof issue.fix === "string" && issue.fix.length > 0), true);
});
