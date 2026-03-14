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

function createBridge(runtimeOverride, options = {}) {
  const connection = new FakeConnection();
  let agentEventListener = null;
  const logs = {
    info: [],
    warn: [],
    error: [],
  };
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
  const defaultAccount = {
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
  };
  const account = {
    ...defaultAccount,
    ...(options.accountOverride ?? {}),
    gateway: {
      ...defaultAccount.gateway,
      ...(options.accountOverride?.gateway ?? {}),
    },
    auth: {
      ...defaultAccount.auth,
      ...(options.accountOverride?.auth ?? {}),
    },
  };
  const bridge = new OpenClawGatewayBridge({
    account,
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
    logs,
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

test("chat invoke passes runTimeoutMs to reply dispatcher and logs model selection", async () => {
  let timeoutOverrideSeconds = null;
  const runtime = {
    channel: {
      routing: {
        resolveAgentRoute() {
          return {
            accountId: "default",
            agentId: "agent_timeout_1",
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
          timeoutOverrideSeconds = replyOptions?.timeoutOverrideSeconds ?? null;
          replyOptions?.onModelSelected?.({
            provider: "openai-codex",
            model: "gpt-5.3-codex",
            thinkLevel: "high",
          });
          await dispatcherOptions.deliver({ text: "hello from model" }, { kind: "block" });
        },
      },
    },
  };

  const { bridge, logs } = createBridge(runtime, {
    accountOverride: {
      runTimeoutMs: 5501,
    },
  });
  await bridge.start();
  await bridge.handleDownstreamMessage({
    type: "invoke",
    welinkSessionId: "wl_timeout_1",
    action: "chat",
    payload: {
      toolSessionId: "tool_timeout_1",
      text: "hello",
    },
  });

  assert.equal(timeoutOverrideSeconds, 6);
  const startedLog = logs.info.find((entry) => entry.message === "bridge.chat.started");
  const modelSelectedLog = logs.info.find((entry) => entry.message === "bridge.chat.model_selected");
  assert.equal(startedLog?.meta.configuredTimeoutMs, 5501);
  assert.equal(startedLog?.meta.executionPath, "runtime_reply");
  assert.equal(modelSelectedLog?.meta.provider, "openai-codex");
  assert.equal(modelSelectedLog?.meta.model, "gpt-5.3-codex");
  assert.equal(modelSelectedLog?.meta.thinkLevel, "high");
});

test("chat invoke falls back when routing lacks resolveAgentRoute", async () => {
  const runtime = {
    subagent: {
      async run() {
        return { runId: "run_missing_route_resolver" };
      },
      async waitForRun() {
        return { status: "ok" };
      },
      async getSessionMessages() {
        return {
          messages: [
            {
              role: "assistant",
              content: "hello from fallback",
            },
          ],
        };
      },
      async deleteSession() {},
    },
    channel: {
      routing: {},
      reply: {},
    },
  };

  const { bridge, connection } = createBridge(runtime);
  await bridge.start();
  await bridge.handleDownstreamMessage({
    type: "invoke",
    welinkSessionId: "wl_missing_route_resolver",
    action: "chat",
    payload: {
      toolSessionId: "tool_missing_route_resolver",
      text: "hello",
    },
  });

  const messageTypes = connection.sent.map((message) => message.type);
  assert.deepEqual(messageTypes, ["tool_event", "tool_event", "tool_event", "tool_event", "tool_done"]);
  assert.equal(connection.sent[1].event.type, "message.updated");
  assert.equal(connection.sent[2].event.properties.part.text, "hello from fallback");
  assert.equal(connection.sent[3].event.type, "session.idle");
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

test("chat invoke fallback uses runTimeoutMs for waitForRun and logs completion diagnostics", async () => {
  let waitTimeoutMs = null;
  const runtime = {
    subagent: {
      async run() {
        return { runId: "run_fallback_1" };
      },
      async waitForRun({ timeoutMs }) {
        waitTimeoutMs = timeoutMs;
        return { status: "ok" };
      },
      async getSessionMessages() {
        return {
          messages: [
            {
              role: "assistant",
              content: "hello from fallback",
            },
          ],
        };
      },
      async deleteSession() {},
    },
    channel: {},
  };

  const { bridge, logs } = createBridge(runtime, {
    accountOverride: {
      runTimeoutMs: 4321,
    },
  });
  await bridge.start();
  await bridge.handleDownstreamMessage({
    type: "invoke",
    welinkSessionId: "wl_fallback_1",
    action: "chat",
    payload: {
      toolSessionId: "tool_fallback_1",
      text: "hello",
    },
  });

  assert.equal(waitTimeoutMs, 4321);
  const completedLog = logs.info.find(
    (entry) => entry.message === "bridge.chat.completed" && entry.meta.executionPath === "subagent_fallback",
  );
  assert.equal(completedLog?.meta.runTimeoutMs, 4321);
  assert.equal(completedLog?.meta.waitStatus, "ok");
  assert.equal(completedLog?.meta.waitError, null);
  assert.equal(completedLog?.meta.responseLength, "hello from fallback".length);
});

test("chat invoke emits session error and tool_error when dispatcher times out before first chunk", async () => {
  const runtime = {
    channel: {
      routing: {
        resolveAgentRoute() {
          return {
            accountId: "default",
            agentId: "agent_timeout_failure",
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
        async dispatchReplyWithBufferedBlockDispatcher() {
          throw new Error("LLM request timed out.");
        },
      },
    },
  };

  const { bridge, connection, logs } = createBridge(runtime);
  await bridge.start();
  await bridge.handleDownstreamMessage({
    type: "invoke",
    welinkSessionId: "wl_timeout_fail",
    action: "chat",
    payload: {
      toolSessionId: "tool_timeout_fail",
      text: "hello",
    },
  });

  const messageTypes = connection.sent.map((message) => message.type);
  assert.deepEqual(messageTypes, ["tool_event", "tool_event", "tool_error"]);
  assert.equal(connection.sent[1].event.type, "session.error");
  assert.equal(connection.sent.some((message) => message.type === "tool_done"), false);
  const failedLog = logs.warn.find((entry) => entry.message === "bridge.chat.failed");
  assert.equal(failedLog?.meta.failureStage, "before_first_chunk");
  assert.equal(failedLog?.meta.errorCategory, "timeout");
  assert.equal(failedLog?.meta.timedOut, true);
  assert.equal(failedLog?.meta.firstChunkLatencyMs, null);
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
