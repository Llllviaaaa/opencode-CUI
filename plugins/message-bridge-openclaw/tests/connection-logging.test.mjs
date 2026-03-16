import test from "node:test";
import assert from "node:assert/strict";
import { DefaultGatewayConnection } from "../dist/connection/GatewayConnection.js";

class FakeWebSocket {
  static OPEN = 1;
  static CONNECTING = 0;
  static instances = [];

  constructor(url) {
    this.url = url;
    this.readyState = FakeWebSocket.CONNECTING;
    this.sent = [];
    this.onopen = null;
    this.onmessage = null;
    this.onerror = null;
    this.onclose = null;
    FakeWebSocket.instances.push(this);
  }

  send(payload) {
    this.sent.push(payload);
  }

  close() {
    this.readyState = 3;
    this.onclose?.({ code: 1000, reason: "manual close", wasClean: true });
  }

  emitOpen(payload = { source: "fake-open-event" }) {
    this.readyState = FakeWebSocket.OPEN;
    this.onopen?.(payload);
  }

  emitMessage(payload) {
    const data = typeof payload === "string" ? payload : JSON.stringify(payload);
    this.onmessage?.({ data });
  }

  emitError(payload = { type: "error", reason: "fake-error-event" }) {
    this.onerror?.(payload);
  }

  emitClose(payload) {
    this.readyState = 3;
    this.onclose?.(payload);
  }
}

function createConnection(logs, options = {}) {
  const conn = new DefaultGatewayConnection({
    url: "ws://localhost:8081/ws/agent",
    reconnectBaseMs: 1,
    reconnectMaxMs: 4,
    reconnectExponential: true,
    heartbeatIntervalMs: 30000,
    debug: options.debug ?? false,
    registerMessage: {
      type: "register",
      deviceName: "dev",
      macAddress: "",
      os: "darwin",
      toolType: "openclaw",
      toolVersion: "1.0.0",
    },
    logger: {
      info(message, meta) {
        logs.info.push({ message, meta });
      },
      warn(message, meta) {
        logs.warn.push({ message, meta });
      },
      error(message, meta) {
        logs.error.push({ message, meta });
      },
    },
  });
  return conn;
}

test("gateway.close logs reconnectPlanned=false on manual disconnect", async (t) => {
  const originalWebSocket = globalThis.WebSocket;
  const originalSetTimeout = globalThis.setTimeout;
  const originalClearTimeout = globalThis.clearTimeout;
  const logs = { debug: [], info: [], warn: [], error: [] };
  try {
    FakeWebSocket.instances = [];
    globalThis.WebSocket = FakeWebSocket;
    globalThis.setTimeout = (() => 1);
    globalThis.clearTimeout = () => {};
    const conn = createConnection(logs);

    const connecting = conn.connect();
    const ws = FakeWebSocket.instances[0];
    ws.emitOpen();
    await connecting;
    ws.emitMessage({ type: "register_ok" });
    await Promise.resolve();
    conn.disconnect();

    const closeLog = logs.warn.find((entry) => entry.message === "gateway.close");
    assert.equal(Boolean(closeLog), true);
    assert.equal(closeLog.meta.code, 1000);
    assert.equal(closeLog.meta.reason, "manual close");
    assert.equal(closeLog.meta.wasClean, true);
    assert.equal(closeLog.meta.manuallyDisconnected, true);
    assert.equal(closeLog.meta.reconnectPlanned, false);
    conn.disconnect();
  } finally {
    globalThis.WebSocket = originalWebSocket;
    globalThis.setTimeout = originalSetTimeout;
    globalThis.clearTimeout = originalClearTimeout;
  }
  t.diagnostic("manual disconnect close logging validated");
});

test("gateway.close logs reconnectPlanned=true on unexpected close", async (t) => {
  const originalWebSocket = globalThis.WebSocket;
  const originalSetTimeout = globalThis.setTimeout;
  const originalClearTimeout = globalThis.clearTimeout;
  const logs = { debug: [], info: [], warn: [], error: [] };
  try {
    FakeWebSocket.instances = [];
    globalThis.WebSocket = FakeWebSocket;
    globalThis.setTimeout = (() => 1);
    globalThis.clearTimeout = () => {};
    const conn = createConnection(logs);

    const connecting = conn.connect();
    const ws = FakeWebSocket.instances[0];
    ws.emitOpen();
    await connecting;
    ws.emitMessage({ type: "register_ok" });
    await Promise.resolve();
    ws.emitClose({ code: 1011, reason: "upstream reset", wasClean: false });

    const closeLog = logs.warn.find((entry) => entry.message === "gateway.close");
    assert.equal(Boolean(closeLog), true);
    assert.equal(closeLog.meta.code, 1011);
    assert.equal(closeLog.meta.reason, "upstream reset");
    assert.equal(closeLog.meta.wasClean, false);
    assert.equal(closeLog.meta.manuallyDisconnected, false);
    assert.equal(closeLog.meta.reconnectPlanned, true);
    assert.equal(logs.info.some((entry) => entry.message === "gateway.reconnect.scheduled"), true);
    conn.disconnect();
  } finally {
    globalThis.WebSocket = originalWebSocket;
    globalThis.setTimeout = originalSetTimeout;
    globalThis.clearTimeout = originalClearTimeout;
  }
  t.diagnostic("unexpected close logging validated");
});

test("gateway send/receive logs include message correlation fields", async (t) => {
  const originalWebSocket = globalThis.WebSocket;
  const originalSetTimeout = globalThis.setTimeout;
  const originalClearTimeout = globalThis.clearTimeout;
  const logs = { debug: [], info: [], warn: [], error: [] };
  let conn = null;
  try {
    FakeWebSocket.instances = [];
    globalThis.WebSocket = FakeWebSocket;
    globalThis.setTimeout = (() => 1);
    globalThis.clearTimeout = () => {};
    conn = createConnection(logs);

    const connecting = conn.connect();
    const ws = FakeWebSocket.instances[0];
    ws.emitOpen();
    await connecting;
    ws.emitMessage({ type: "register_ok" });
    await Promise.resolve();

    ws.emitMessage({
      type: "invoke",
      messageId: "gw_msg_recv_1",
      action: "chat",
      welinkSessionId: "wl_recv_1",
      payload: {
        toolSessionId: "tool_recv_1",
        text: "hello",
      },
    });
    await Promise.resolve();

    conn.send(
      {
        type: "tool_event",
        toolSessionId: "tool_send_1",
        event: {
          type: "message.part.updated",
        },
      },
      {
        gatewayMessageId: "gw_msg_send_1",
        action: "chat",
        welinkSessionId: "wl_send_1",
        toolSessionId: "tool_send_1",
      },
    );

    const receivedLog = logs.info.find(
      (entry) =>
        entry.message === "gateway.message.received" &&
        entry.meta.gatewayMessageId === "gw_msg_recv_1",
    );
    assert.equal(Boolean(receivedLog), true);
    assert.equal(receivedLog.meta.gatewayMessageId, "gw_msg_recv_1");
    assert.equal(receivedLog.meta.action, "chat");
    assert.equal(receivedLog.meta.welinkSessionId, "wl_recv_1");
    assert.equal(receivedLog.meta.toolSessionId, "tool_recv_1");

    const sendLog = logs.info.find(
      (entry) => entry.message === "gateway.send" && entry.meta.gatewayMessageId === "gw_msg_send_1",
    );
    assert.equal(Boolean(sendLog), true);
    assert.equal(sendLog.meta.gatewayMessageId, "gw_msg_send_1");
    assert.equal(sendLog.meta.action, "chat");
    assert.equal(sendLog.meta.welinkSessionId, "wl_send_1");
    assert.equal(sendLog.meta.toolSessionId, "tool_send_1");
    assert.equal(sendLog.meta.eventType, "message.part.updated");
  } finally {
    conn?.disconnect();
    globalThis.WebSocket = originalWebSocket;
    globalThis.setTimeout = originalSetTimeout;
    globalThis.clearTimeout = originalClearTimeout;
  }
  t.diagnostic("gateway send/receive correlation logging validated");
});

test("raw packet logs are disabled when debug=false", async () => {
  const originalWebSocket = globalThis.WebSocket;
  const logs = { debug: [], info: [], warn: [], error: [] };
  let conn = null;
  try {
    FakeWebSocket.instances = [];
    globalThis.WebSocket = FakeWebSocket;
    conn = createConnection(logs, { debug: false });
    conn.on("error", () => {});

    const connecting = conn.connect();
    const ws = FakeWebSocket.instances[0];
    ws.emitOpen();
    await connecting;
    ws.emitMessage({ type: "register_ok" });
    await Promise.resolve();
    conn.send({ type: "heartbeat", timestamp: "2026-03-16T00:00:00.000Z" });
    ws.emitError();

    assert.equal(logs.info.some((entry) => entry.message.startsWith("「onOpen」===>")), false);
    assert.equal(logs.info.some((entry) => entry.message.startsWith("「onMessage」===>")), false);
    assert.equal(logs.info.some((entry) => entry.message.startsWith("「onError」===>")), false);
    assert.equal(logs.info.some((entry) => entry.message.startsWith("「sendMessage」===>")), false);
  } finally {
    conn?.disconnect();
    globalThis.WebSocket = originalWebSocket;
  }
});

test("raw packet logs are readable and formatted when debug=true", async () => {
  const originalWebSocket = globalThis.WebSocket;
  const logs = { debug: [], info: [], warn: [], error: [] };
  let conn = null;
  try {
    FakeWebSocket.instances = [];
    globalThis.WebSocket = FakeWebSocket;
    conn = createConnection(logs, { debug: true });
    conn.on("error", () => {});

    const connecting = conn.connect();
    const ws = FakeWebSocket.instances[0];
    ws.emitOpen({ type: "open_event", from: "fake" });
    await connecting;
    ws.emitMessage({ type: "register_ok" });
    await Promise.resolve();
    ws.emitMessage('{"type":"invoke","messageId":"gw_msg_debug_1","payload":{"text":"hello"}}');
    conn.send({ type: "heartbeat", timestamp: "2026-03-16T00:00:00.000Z" });
    ws.emitError({ type: "error_event", reason: "upstream" });

    const openLog = logs.info.find((entry) => entry.message.startsWith("「onOpen」===>"));
    const onMessageLogs = logs.info.filter((entry) => entry.message.startsWith("「onMessage」===>"));
    const sendLog = logs.info.find(
      (entry) =>
        entry.message.startsWith("「sendMessage」===>") &&
        entry.message.includes('"type":"heartbeat"'),
    );
    const errorLog = logs.info.find((entry) => entry.message.startsWith("「onError」===>"));

    assert.equal(Boolean(openLog), true);
    assert.equal(openLog.message, '「onOpen」===>「{"type":"open_event","from":"fake"}」');
    assert.equal(onMessageLogs.length > 0, true);
    assert.equal(onMessageLogs.every((entry) => !entry.message.includes("[object Object]")), true);
    assert.equal(Boolean(sendLog), true);
    assert.equal(sendLog.message, '「sendMessage」===>「{"type":"heartbeat","timestamp":"2026-03-16T00:00:00.000Z"}」');
    assert.equal(Boolean(errorLog), true);
    assert.equal(errorLog.message, '「onError」===>「{"type":"error_event","reason":"upstream"}」');
  } finally {
    conn?.disconnect();
    globalThis.WebSocket = originalWebSocket;
  }
});
