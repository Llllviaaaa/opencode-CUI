import test from "node:test";
import assert from "node:assert/strict";
import { OpenClawGatewayBridge } from "../dist/OpenClawGatewayBridge.js";

class FakeConnection {
  constructor() {
    this.handlers = new Map();
    this.sent = [];
    this.state = "DISCONNECTED";
  }

  async connect() {
    this.state = "READY";
    this.handlers.get("stateChange")?.("READY");
  }

  disconnect() {
    this.state = "DISCONNECTED";
    this.handlers.get("stateChange")?.("DISCONNECTED");
  }

  send(message) {
    this.sent.push(message);
  }

  isConnected() {
    return this.state === "READY";
  }

  getState() {
    return this.state;
  }

  on(event, listener) {
    this.handlers.set(event, listener);
    return this;
  }
}

function createBridge(runtimeOverride) {
  const connection = new FakeConnection();
  const bridge = new OpenClawGatewayBridge({
    account: {
      accountId: "default",
      enabled: true,
      gateway: {
        url: "ws://localhost:8081/ws/agent",
        toolType: "OPENCLAW",
        toolVersion: "0.1.0",
        deviceName: "test",
        heartbeatIntervalMs: 30000,
        reconnect: {
          baseMs: 1000,
          maxMs: 30000,
          exponential: true,
        },
      },
      auth: {
        ak: "ak",
        sk: "sk",
      },
      agentIdPrefix: "bridge",
      runTimeoutMs: 5000,
    },
    logger: {
      info() {},
      warn() {},
      error() {},
    },
    config: {
      agents: {
        defaults: {},
      },
      channels: {},
    },
    runtime: runtimeOverride ?? {
      subagent: {
        async run() {
          return { runId: "run_1" };
        },
        async waitForRun() {
          return { status: "ok" };
        },
        async getSessionMessages() {
          return {
            messages: [
              {
                role: "assistant",
                content: [
                  {
                    type: "text",
                    text: "hello from openclaw",
                  },
                ],
              },
            ],
          };
        },
        async deleteSession() {},
      },
      channel: {},
    },
    setStatus() {},
    connectionFactory: () => connection,
  });
  return { bridge, connection };
}

test("chat invoke produces tool_event and tool_done", async () => {
  const { bridge, connection } = createBridge();
  await bridge.start();
  await bridge.handleDownstreamMessage({
    type: "invoke",
    welinkSessionId: "wl_1",
    action: "chat",
    payload: {
      toolSessionId: "tool_1",
      text: "hello",
    },
  });

  const types = connection.sent.map((item) => item.type);
  assert.deepEqual(types, ["tool_event", "tool_event", "tool_event", "tool_event", "tool_done"]);
  assert.equal(connection.sent[1].event.type, "message.updated");
  assert.equal(connection.sent[2].event.type, "message.part.updated");
});

test("chat invoke streams delta events through OpenClaw reply dispatcher", async () => {
  const runtime = {
    channel: {
      routing: {
        resolveAgentRoute() {
          return {
            accountId: "default",
            agentId: "agent_1",
          };
        },
      },
      reply: {
        resolveEnvelopeFormatOptions() {
          return {};
        },
        formatAgentEnvelope({ body }) {
          return body;
        },
        finalizeInboundContext(ctx) {
          return ctx;
        },
        async dispatchReplyWithBufferedBlockDispatcher({ dispatcherOptions }) {
          await dispatcherOptions.deliver({ text: "hello " });
          await dispatcherOptions.deliver({ text: "from openclaw" });
        },
      },
    },
  };

  const { bridge, connection } = createBridge(runtime);
  await bridge.start();
  await bridge.handleDownstreamMessage({
    type: "invoke",
    welinkSessionId: "wl_2",
    action: "chat",
    payload: {
      toolSessionId: "tool_2",
      text: "hello",
    },
  });

  const eventTypes = connection.sent
    .filter((item) => item.type === "tool_event")
    .map((item) => item.event.type);
  assert.deepEqual(eventTypes, [
    "session.status",
    "message.updated",
    "message.part.updated",
    "message.part.delta",
    "message.part.updated",
    "session.idle",
  ]);
  assert.equal(connection.sent[2].event.properties.part.type, "text");
  assert.equal(connection.sent[2].event.properties.delta, "hello ");
  assert.equal(connection.sent[3].event.properties.delta, "from openclaw");
  assert.equal(connection.sent[4].event.properties.part.text, "hello from openclaw");
  assert.equal("delta" in connection.sent[4].event.properties, false);
  assert.equal(connection.sent.at(-1).type, "tool_done");
});

test("unsupported actions fail closed", async () => {
  const { bridge, connection } = createBridge();
  await bridge.start();
  await bridge.handleDownstreamMessage({
    type: "invoke",
    welinkSessionId: "wl_1",
    action: "permission_reply",
    payload: {
      toolSessionId: "tool_1",
      permissionId: "perm_1",
      response: "once",
    },
  });

  assert.equal(connection.sent.at(-1).type, "tool_error");
  assert.match(connection.sent.at(-1).error, /unsupported_in_openclaw_v1/);
});
