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
  let agentEventListener = null;
  const runtimeBase = runtimeOverride ?? {
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
  };
  const runtime = {
    ...runtimeBase,
    events: {
      ...(runtimeBase.events ?? {}),
      onAgentEvent(listener) {
        agentEventListener = listener;
        const unsubscribe = runtimeBase.events?.onAgentEvent?.(listener);
        return () => {
          agentEventListener = null;
          unsubscribe?.();
          return true;
        };
      },
    },
  };
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
    runtime,
    setStatus() {},
    connectionFactory: () => connection,
  });
  return {
    bridge,
    connection,
    emitAgentEvent(event) {
      return agentEventListener?.(event);
    },
  };
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
          await dispatcherOptions.deliver({ text: "hello " }, { kind: "block" });
          await dispatcherOptions.deliver({ text: "from openclaw" }, { kind: "block" });
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

test("chat invoke projects tool lifecycle into tool parts", async () => {
  let emitToolEvent = () => {};
  const runtime = {
    events: {
      onAgentEvent() {
        return () => true;
      },
    },
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
          await emitToolEvent({
            stream: "tool",
            sessionKey: "bridge:default:tool_3",
            data: {
              phase: "start",
              name: "write",
              toolCallId: "call_1",
            },
          });
          await emitToolEvent({
            stream: "tool",
            sessionKey: "bridge:default:tool_3",
            data: {
              phase: "result",
              name: "write",
              toolCallId: "call_1",
              meta: {
                summary: "write to ~/Desktop/text.txt",
              },
              isError: false,
            },
          });
          await dispatcherOptions.deliver({ text: "saved successfully" }, { kind: "tool" });
          await dispatcherOptions.deliver({ text: "done" }, { kind: "block" });
        },
      },
    },
  };

  const { bridge, connection, emitAgentEvent } = createBridge(runtime);
  emitToolEvent = emitAgentEvent;
  await bridge.start();
  await bridge.handleDownstreamMessage({
    type: "invoke",
    welinkSessionId: "wl_3",
    action: "chat",
    payload: {
      toolSessionId: "tool_3",
      text: "write file",
    },
  });

  const toolEvents = connection.sent
    .filter((item) => item.type === "tool_event")
    .map((item) => item.event);
  const toolUpdates = toolEvents.filter((event) => event.type === "message.part.updated" && event.properties.part.type === "tool");

  assert.equal(toolEvents[1].type, "message.updated");
  assert.equal(toolUpdates.length, 3);
  assert.equal(toolUpdates[0].properties.part.tool, "write");
  assert.equal(toolUpdates[0].properties.part.state.status, "running");
  assert.equal(toolUpdates[1].properties.part.state.status, "completed");
  assert.equal(toolUpdates[1].properties.part.state.title, "write to ~/Desktop/text.txt");
  assert.equal(toolUpdates[2].properties.part.state.output, "saved successfully");
  assert.equal(toolUpdates[0].properties.part.id, toolUpdates[2].properties.part.id);
});

test("chat invoke maps tool lifecycle by runId when agent events omit sessionKey", async () => {
  let emitToolEvent = () => {};
  const runtime = {
    events: {
      onAgentEvent() {
        return () => true;
      },
    },
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
        async dispatchReplyWithBufferedBlockDispatcher({ dispatcherOptions, replyOptions }) {
          replyOptions?.onAgentRunStart?.("run_tool_5");
          await emitToolEvent({
            runId: "run_tool_5",
            stream: "tool",
            data: {
              phase: "start",
              name: "write",
              toolCallId: "call_5",
            },
          });
          await emitToolEvent({
            runId: "run_tool_5",
            stream: "tool",
            data: {
              phase: "result",
              name: "write",
              toolCallId: "call_5",
              meta: {
                summary: "write via run mapping",
              },
              isError: false,
            },
          });
          await dispatcherOptions.deliver({ text: "saved via runId" }, { kind: "tool" });
          await dispatcherOptions.deliver({ text: "done" }, { kind: "block" });
        },
      },
    },
  };

  const { bridge, connection, emitAgentEvent } = createBridge(runtime);
  emitToolEvent = emitAgentEvent;
  await bridge.start();
  await bridge.handleDownstreamMessage({
    type: "invoke",
    welinkSessionId: "wl_5",
    action: "chat",
    payload: {
      toolSessionId: "tool_5",
      text: "write file",
    },
  });

  const toolUpdates = connection.sent
    .filter((item) => item.type === "tool_event")
    .map((item) => item.event)
    .filter((event) => event.type === "message.part.updated" && event.properties.part.type === "tool");

  assert.equal(toolUpdates.length, 3);
  assert.equal(toolUpdates[0].properties.part.state.status, "running");
  assert.equal(toolUpdates[1].properties.part.state.status, "completed");
  assert.equal(toolUpdates[1].properties.part.state.title, "write via run mapping");
  assert.equal(toolUpdates[2].properties.part.state.output, "saved via runId");
});

test("chat invoke projects tool errors into final error tool parts", async () => {
  let emitToolEvent = () => {};
  const runtime = {
    events: {
      onAgentEvent() {
        return () => true;
      },
    },
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
          await emitToolEvent({
            stream: "tool",
            sessionKey: "bridge:default:tool_4",
            data: {
              phase: "start",
              name: "write",
              toolCallId: "call_err_1",
            },
          });
          await emitToolEvent({
            stream: "tool",
            sessionKey: "bridge:default:tool_4",
            data: {
              phase: "result",
              name: "write",
              toolCallId: "call_err_1",
              isError: true,
            },
          });
          await dispatcherOptions.deliver({ text: "failed" }, { kind: "block" });
        },
      },
    },
  };

  const { bridge, connection, emitAgentEvent } = createBridge(runtime);
  emitToolEvent = emitAgentEvent;
  await bridge.start();
  await bridge.handleDownstreamMessage({
    type: "invoke",
    welinkSessionId: "wl_4",
    action: "chat",
    payload: {
      toolSessionId: "tool_4",
      text: "write file",
    },
  });

  const toolUpdates = connection.sent
    .filter((item) => item.type === "tool_event")
    .map((item) => item.event)
    .filter((event) => event.type === "message.part.updated" && event.properties.part.type === "tool");

  assert.equal(toolUpdates.length, 2);
  assert.equal(toolUpdates[1].properties.part.state.status, "error");
  assert.equal(toolUpdates[1].properties.part.state.error, "tool_write_failed");
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
