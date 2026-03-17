import test from "node:test";
import assert from "node:assert/strict";
import { OpenClawGatewayBridge } from "../dist/OpenClawGatewayBridge.js";

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

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
    this.handlers.get("outbound")?.(message);
    if (message?.type === "heartbeat") {
      this.handlers.get("heartbeat")?.(message);
    }
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

  emitInbound(message) {
    this.handlers.get("inbound")?.(message);
  }
}

function createBridge(runtimeOverride, options = {}) {
  const connection = new FakeConnection();
  let agentEventListener = null;
  const statusSnapshots = [];
  const logs = {
    debug: [],
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
      debug(message, meta) {
        logs.debug.push({ message, meta });
      },
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
    setStatus(status) {
      statusSnapshots.push({ ...status });
    },
    connectionFactory: () => connection,
  });
  return {
    bridge,
    connection,
    logs,
    statusSnapshots,
    getLatestStatus() {
      return statusSnapshots.at(-1) ?? null;
    },
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

test("chat without welinkSessionId does not backfill from session registry", async () => {
  const { bridge, connection, logs } = createBridge();
  await bridge.start();
  await bridge.handleDownstreamMessage({
    type: "invoke",
    welinkSessionId: "wl_seed_strict_1",
    action: "create_session",
    payload: {
      sessionId: "tool_strict_1",
    },
  });
  await bridge.handleDownstreamMessage({
    type: "invoke",
    action: "chat",
    payload: {
      toolSessionId: "tool_strict_1",
      text: "hello strict",
    },
  });

  const toolDone = connection.sent.findLast(
    (message) => message.type === "tool_done" && message.toolSessionId === "tool_strict_1",
  );
  assert.equal(toolDone?.welinkSessionId, undefined);
  const toolEvents = connection.sent.filter(
    (message) => message.type === "tool_event" && message.toolSessionId === "tool_strict_1",
  );
  assert.equal(toolEvents.length > 0, true);
  assert.equal(toolEvents.every((message) => !("welinkSessionId" in message)), true);
  const chatToolEventLog = logs.debug.findLast(
    (entry) =>
      entry.message === "runtime.tool_event.sending" &&
      entry.meta?.toolSessionId === "tool_strict_1" &&
      entry.meta?.action === "chat",
  );
  assert.equal(chatToolEventLog?.meta?.welinkSessionId, undefined);
});

test("chat error without welinkSessionId does not backfill from session registry", async () => {
  const { bridge, connection, logs } = createBridge();
  await bridge.start();
  await bridge.handleDownstreamMessage({
    type: "invoke",
    welinkSessionId: "wl_seed_strict_err_1",
    action: "create_session",
    payload: {
      sessionId: "tool_strict_err_1",
    },
  });
  await bridge.handleDownstreamMessage({
    type: "invoke",
    action: "chat",
    payload: {
      toolSessionId: "tool_strict_err_1",
      text: "trigger error",
    },
  });

  const toolError = connection.sent.findLast(
    (message) => message.type === "tool_error" && message.toolSessionId === "tool_strict_err_1",
  );
  assert.equal(toolError?.welinkSessionId, undefined);
  const toolErrorLog = logs.error.findLast(
    (entry) =>
      entry.message === "runtime.tool_error.sending" &&
      entry.meta?.toolSessionId === "tool_strict_err_1" &&
      entry.meta?.action === "chat",
  );
  assert.equal(toolErrorLog?.meta?.welinkSessionId, undefined);
});

test("downstream and transport chain logs are emitted for chat and invalid payload", async () => {
  const { bridge, logs } = createBridge();
  await bridge.start();

  await bridge.handleDownstreamMessage({
    type: "invoke",
    messageId: "gw_msg_chat_1",
    welinkSessionId: "wl_logs_1",
    action: "chat",
    payload: {
      toolSessionId: "tool_logs_1",
      text: "hello logs",
    },
  });

  await bridge.handleDownstreamMessage({
    type: "invoke",
    messageId: "gw_msg_chat_2",
    welinkSessionId: "wl_logs_2",
    action: "chat",
    payload: {
      toolSessionId: "tool_logs_2",
    },
  });

  assert.equal(logs.debug.some((entry) => entry.message === "runtime.downstream.received"), true);
  assert.equal(logs.info.some((entry) => entry.message === "runtime.invoke.received"), true);
  assert.equal(logs.info.some((entry) => entry.message === "runtime.invoke.completed"), true);
  assert.equal(logs.debug.some((entry) => entry.message === "runtime.tool_event.sending"), true);
  assert.equal(logs.info.some((entry) => entry.message === "runtime.tool_done.sending"), true);
  assert.equal(logs.warn.some((entry) => entry.message === "runtime.downstream_ignored_non_protocol"), true);
  const toolEventLog = logs.debug.find((entry) => entry.message === "runtime.tool_event.sending");
  assert.equal(toolEventLog?.meta.gatewayMessageId, "gw_msg_chat_1");
  assert.equal(toolEventLog?.meta.action, "chat");
  assert.equal(toolEventLog?.meta.welinkSessionId, "wl_logs_1");
  assert.equal(toolEventLog?.meta.toolSessionId, "tool_logs_1");
  const toolErrorLog = logs.error.find((entry) => entry.message === "runtime.tool_error.sending");
  assert.equal(Boolean(toolErrorLog), true);
  assert.equal(toolErrorLog?.meta.gatewayMessageId, "gw_msg_chat_2");
  assert.equal(toolErrorLog?.meta.action, "chat");
  assert.equal(toolErrorLog?.meta.welinkSessionId, "wl_logs_2");
  assert.equal(toolErrorLog?.meta.toolSessionId, "tool_logs_2");
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
  let attempts = 0;
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
          attempts += 1;
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
  assert.equal(attempts, 2);
  assert.equal(connection.sent[1].event.type, "session.error");
  assert.equal(connection.sent.some((message) => message.type === "tool_done"), false);
  const startedLogs = logs.info.filter((entry) => entry.message === "bridge.chat.started");
  const startedLog = startedLogs[0];
  const failedLog = logs.warn.find((entry) => entry.message === "bridge.chat.failed");
  assert.equal(startedLogs.length, 2);
  assert.equal(startedLog?.meta.retryAttempt, 0);
  assert.equal(startedLogs[1]?.meta.retryAttempt, 1);
  assert.equal(failedLog?.meta.failureStage, "before_first_chunk");
  assert.equal(failedLog?.meta.errorCategory, "timeout");
  assert.equal(failedLog?.meta.retryAttempt, 1);
  assert.equal(failedLog?.meta.timedOut, true);
  assert.equal(failedLog?.meta.firstChunkLatencyMs, null);
});

test("chat fallback failed wait keeps wait diagnostics in failed log", async () => {
  const runtime = {
    subagent: {
      async run() {
        return { runId: "run_fallback_failed_wait" };
      },
      async waitForRun() {
        return { status: "failed", error: "request timed out" };
      },
      async getSessionMessages() {
        return { messages: [] };
      },
      async deleteSession() {},
    },
    channel: {},
  };

  const { bridge, logs, connection } = createBridge(runtime);
  await bridge.start();
  await bridge.handleDownstreamMessage({
    type: "invoke",
    welinkSessionId: "wl_fallback_failed_wait",
    action: "chat",
    payload: {
      toolSessionId: "tool_fallback_failed_wait",
      text: "hello",
    },
  });

  assert.equal(connection.sent.at(-1)?.type, "tool_error");
  const failedLog = logs.warn.find((entry) => entry.message === "bridge.chat.failed");
  assert.equal(failedLog?.meta.executionPath, "subagent_fallback");
  assert.equal(failedLog?.meta.waitStatus, "failed");
  assert.equal(failedLog?.meta.waitError, "request timed out");
});

test("chat invoke does not retry when timeout happens after first chunk", async () => {
  let attempts = 0;
  const runtime = {
    channel: {
      routing: {
        resolveAgentRoute() {
          return {
            accountId: "default",
            agentId: "agent_after_first_chunk_timeout",
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
          attempts += 1;
          await dispatcherOptions.deliver({ text: "hello " }, { kind: "block" });
          throw new Error("LLM request timed out.");
        },
      },
    },
  };

  const { bridge, logs } = createBridge(runtime);
  await bridge.start();
  await bridge.handleDownstreamMessage({
    type: "invoke",
    welinkSessionId: "wl_after_chunk_timeout",
    action: "chat",
    payload: {
      toolSessionId: "tool_after_chunk_timeout",
      text: "hello",
    },
  });

  assert.equal(attempts, 1);
  const failedLog = logs.warn.find((entry) => entry.message === "bridge.chat.failed");
  assert.equal(failedLog?.meta.failureStage, "after_first_chunk");
  assert.equal(failedLog?.meta.errorCategory, "timeout");
  assert.equal(failedLog?.meta.retryAttempt, 0);
});

test("chat invoke does not retry for non-timeout failures", async () => {
  let attempts = 0;
  const runtime = {
    channel: {
      routing: {
        resolveAgentRoute() {
          return {
            accountId: "default",
            agentId: "agent_runtime_error_no_retry",
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
          attempts += 1;
          throw new Error("agent execution failed");
        },
      },
    },
  };

  const { bridge, logs } = createBridge(runtime);
  await bridge.start();
  await bridge.handleDownstreamMessage({
    type: "invoke",
    welinkSessionId: "wl_runtime_error_no_retry",
    action: "chat",
    payload: {
      toolSessionId: "tool_runtime_error_no_retry",
      text: "hello",
    },
  });

  assert.equal(attempts, 1);
  const failedLog = logs.warn.find((entry) => entry.message === "bridge.chat.failed");
  assert.equal(failedLog?.meta.errorCategory, "runtime_error");
  assert.equal(failedLog?.meta.retryAttempt, 0);
});

test("chat fallback retry reuses idempotency key and records retryAttempt", async () => {
  const idempotencyKeys = [];
  let waitAttempt = 0;
  const runtime = {
    subagent: {
      async run({ idempotencyKey }) {
        idempotencyKeys.push(idempotencyKey);
        return { runId: `run_fallback_retry_${idempotencyKeys.length}` };
      },
      async waitForRun() {
        waitAttempt += 1;
        if (waitAttempt === 1) {
          return { status: "failed", error: "request timed out" };
        }
        return { status: "ok" };
      },
      async getSessionMessages() {
        return {
          messages: [
            {
              role: "assistant",
              content: "hello from fallback retry",
            },
          ],
        };
      },
      async deleteSession() {},
    },
    channel: {},
  };

  const { bridge, logs } = createBridge(runtime);
  await bridge.start();
  await bridge.handleDownstreamMessage({
    type: "invoke",
    welinkSessionId: "wl_fallback_retry",
    action: "chat",
    payload: {
      toolSessionId: "tool_fallback_retry",
      text: "hello",
    },
  });

  assert.equal(idempotencyKeys.length, 2);
  assert.equal(idempotencyKeys[0], idempotencyKeys[1]);
  const completedLog = logs.info.find(
    (entry) => entry.message === "bridge.chat.completed" && entry.meta.executionPath === "subagent_fallback",
  );
  assert.equal(completedLog?.meta.retryAttempt, 1);
});

test("bridge status tracks ready, inbound, outbound and heartbeat timestamps", async () => {
  const { bridge, connection, getLatestStatus } = createBridge();
  await bridge.start();

  const readyStatus = getLatestStatus();
  assert.equal(readyStatus?.connected, true);
  assert.equal(typeof readyStatus?.lastReadyAt, "number");

  connection.emitInbound({
    type: "status_query",
  });
  await bridge.handleDownstreamMessage({
    type: "status_query",
  });

  const ioStatus = getLatestStatus();
  assert.equal(typeof ioStatus?.lastInboundAt, "number");
  assert.equal(typeof ioStatus?.lastOutboundAt, "number");

  connection.send({
    type: "heartbeat",
  });

  const heartbeatStatus = getLatestStatus();
  assert.equal(typeof heartbeatStatus?.lastHeartbeatAt, "number");
  assert.equal(typeof heartbeatStatus?.lastOutboundAt, "number");
});

test("logs websocket state changes on start and stop", async () => {
  const { bridge, logs } = createBridge();
  await bridge.start();
  await bridge.stop();

  const stateLogs = logs.info.filter((entry) => entry.message === "gateway.state.changed");
  assert.equal(stateLogs.some((entry) => entry.meta?.state === "READY"), true);
  assert.equal(stateLogs.some((entry) => entry.meta?.state === "DISCONNECTED"), true);
});

test("status_query returns status_response", async () => {
  const { bridge, connection } = createBridge();
  await bridge.start();
  await bridge.handleDownstreamMessage({
    type: "status_query",
  });

  assert.deepEqual(connection.sent, [
    {
      type: "status_response",
      opencodeOnline: true,
    },
  ]);
});

test("status_query returns false when bridge is stopped", async () => {
  const { bridge, connection } = createBridge();
  await bridge.start();
  await bridge.stop();
  connection.sent.length = 0;
  connection.state = "READY";
  await bridge.handleDownstreamMessage({
    type: "status_query",
  });

  assert.deepEqual(connection.sent, [
    {
      type: "status_response",
      opencodeOnline: false,
    },
  ]);
});

test("create_session returns session_created with bridged session id", async () => {
  const { bridge, connection } = createBridge();
  await bridge.start();
  await bridge.handleDownstreamMessage({
    type: "invoke",
    welinkSessionId: "wl_create_1",
    action: "create_session",
    payload: {
      sessionId: "tool_create_1",
    },
  });

  assert.deepEqual(connection.sent, [
    {
      type: "session_created",
      welinkSessionId: "wl_create_1",
      toolSessionId: "tool_create_1",
      session: {
        sessionId: "tool_create_1",
      },
    },
  ]);
});

test("close_session deletes session without returning tool_done", async () => {
  const deletedSessions = [];
  const { bridge, connection } = createBridge({
    subagent: {
      async deleteSession({ sessionKey }) {
        deletedSessions.push({ sessionKey });
      },
    },
  });
  await bridge.start();
  await bridge.handleDownstreamMessage({
    type: "invoke",
    welinkSessionId: "wl_close_1",
    action: "create_session",
    payload: {
      sessionId: "tool_close_1",
    },
  });
  await bridge.handleDownstreamMessage({
    type: "invoke",
    welinkSessionId: "wl_close_1",
    action: "close_session",
    payload: {
      toolSessionId: "tool_close_1",
    },
  });

  assert.deepEqual(deletedSessions, [{ sessionKey: "bridge:default:tool_close_1" }]);
  assert.equal(connection.sent.some((message) => message.type === "tool_done"), false);
});

test("close_session returns unknown_tool_session for missing session", async () => {
  const { bridge, connection } = createBridge();
  await bridge.start();
  await bridge.handleDownstreamMessage({
    type: "invoke",
    welinkSessionId: "wl_close_missing",
    action: "close_session",
    payload: {
      toolSessionId: "tool_close_missing",
    },
  });

  assert.equal(connection.sent.at(-1).type, "tool_error");
  assert.equal(connection.sent.at(-1).toolSessionId, "tool_close_missing");
  assert.equal(connection.sent.at(-1).error, "unknown_tool_session");
  assert.equal(connection.sent.at(-1).reason, "session_not_found");
});

test("abort_session soft-terminates session and returns tool_done", async () => {
  const deletedSessions = [];
  const { bridge, connection } = createBridge({
    subagent: {
      async deleteSession({ sessionKey }) {
        deletedSessions.push({ sessionKey });
      },
    },
  });
  await bridge.start();
  await bridge.handleDownstreamMessage({
    type: "invoke",
    welinkSessionId: "wl_abort_1",
    action: "create_session",
    payload: {
      sessionId: "tool_abort_1",
    },
  });
  await bridge.handleDownstreamMessage({
    type: "invoke",
    welinkSessionId: "wl_abort_1",
    action: "abort_session",
    payload: {
      toolSessionId: "tool_abort_1",
    },
  });

  assert.deepEqual(deletedSessions, []);
  assert.equal(connection.sent.at(-1).type, "tool_done");
  assert.equal(connection.sent.at(-1).toolSessionId, "tool_abort_1");
});

test("abort_session keeps session mapping after soft termination", async () => {
  const { bridge, connection } = createBridge();
  await bridge.start();
  await bridge.handleDownstreamMessage({
    type: "invoke",
    welinkSessionId: "wl_abort_delete_1",
    action: "create_session",
    payload: {
      sessionId: "tool_abort_delete_1",
    },
  });
  await bridge.handleDownstreamMessage({
    type: "invoke",
    welinkSessionId: "wl_abort_delete_1",
    action: "abort_session",
    payload: {
      toolSessionId: "tool_abort_delete_1",
    },
  });
  await bridge.handleDownstreamMessage({
    type: "invoke",
    welinkSessionId: "wl_abort_delete_1",
    action: "chat",
    payload: {
      toolSessionId: "tool_abort_delete_1",
      text: "after abort",
    },
  });

  const trailing = connection.sent.filter((message) => message.toolSessionId === "tool_abort_delete_1");
  assert.equal(trailing.some((message) => message.type === "tool_done"), true);
  assert.equal(trailing.some((message) => message.type === "tool_error"), false);
});

test("create_session uses bridge generated session id when payload sessionId is absent", async () => {
  const { bridge, connection } = createBridge();
  await bridge.start();
  await bridge.handleDownstreamMessage({
    type: "invoke",
    welinkSessionId: "wl_create_generated",
    action: "create_session",
    payload: {},
  });

  assert.equal(connection.sent.length, 1);
  assert.equal(connection.sent[0].type, "session_created");
  assert.equal(connection.sent[0].welinkSessionId, "wl_create_generated");
  assert.equal(typeof connection.sent[0].toolSessionId, "string");
  assert.equal(connection.sent[0].toolSessionId.length > 0, true);
  assert.equal(connection.sent[0].session.sessionId, connection.sent[0].toolSessionId);
});

test("abort_session returns unknown_tool_session for missing session", async () => {
  const { bridge, connection } = createBridge();
  await bridge.start();
  await bridge.handleDownstreamMessage({
    type: "invoke",
    welinkSessionId: "wl_abort_missing",
    action: "abort_session",
    payload: {
      toolSessionId: "tool_abort_missing",
    },
  });

  assert.equal(connection.sent.at(-1).type, "tool_error");
  assert.equal(connection.sent.at(-1).toolSessionId, "tool_abort_missing");
  assert.equal(connection.sent.at(-1).error, "unknown_tool_session");
  assert.equal(connection.sent.at(-1).reason, "session_not_found");
});

test("abort_session suppresses late chat output after successful termination", async () => {
  const runtime = {
    channel: {
      routing: {
        resolveAgentRoute() {
          return {
            accountId: "default",
            agentId: "agent_abort_gate",
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
          replyOptions?.onAgentRunStart?.("run_abort_gate");
          await sleep(20);
          await dispatcherOptions.deliver({ text: "late output after abort" }, { kind: "block" });
        },
      },
    },
  };

  const { bridge, connection } = createBridge(runtime);
  await bridge.start();
  const chatPromise = bridge.handleDownstreamMessage({
    type: "invoke",
    welinkSessionId: "wl_abort_gate",
    action: "chat",
    payload: {
      toolSessionId: "tool_abort_gate",
      text: "hello",
    },
  });
  await sleep(5);
  await bridge.handleDownstreamMessage({
    type: "invoke",
    welinkSessionId: "wl_abort_gate",
    action: "abort_session",
    payload: {
      toolSessionId: "tool_abort_gate",
    },
  });
  await chatPromise;

  const trailing = connection.sent.filter((message) => message.toolSessionId === "tool_abort_gate");
  assert.deepEqual(
    trailing.map((message) => message.type),
    ["tool_event", "tool_done"],
  );
});

test("close_session failure preserves session mapping for retry", async () => {
  let shouldFail = true;
  const { bridge, connection } = createBridge({
    subagent: {
      async deleteSession() {
        if (shouldFail) {
          shouldFail = false;
          throw new Error("delete_failed_once");
        }
      },
    },
  });
  await bridge.start();
  await bridge.handleDownstreamMessage({
    type: "invoke",
    welinkSessionId: "wl_close_retry",
    action: "create_session",
    payload: {
      sessionId: "tool_close_retry",
    },
  });

  await bridge.handleDownstreamMessage({
    type: "invoke",
    welinkSessionId: "wl_close_retry",
    action: "close_session",
    payload: {
      toolSessionId: "tool_close_retry",
    },
  });

  assert.equal(connection.sent.at(-1).type, "tool_error");
  assert.equal(connection.sent.at(-1).error, "delete_failed_once");

  await bridge.handleDownstreamMessage({
    type: "invoke",
    welinkSessionId: "wl_close_retry",
    action: "close_session",
    payload: {
      toolSessionId: "tool_close_retry",
    },
  });

  assert.notEqual(connection.sent.at(-1).error, "unknown_tool_session");
});

test("unsupported permission_reply and question_reply fail closed with standard tool_error", async () => {
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
  assert.match(connection.sent.at(-1).error, /unsupported_in_openclaw_v1:permission_reply/);

  await bridge.handleDownstreamMessage({
    type: "invoke",
    welinkSessionId: "wl_2",
    action: "question_reply",
    payload: {
      toolSessionId: "tool_2",
      answer: "ok",
    },
  });

  assert.equal(connection.sent.at(-1).type, "tool_error");
  assert.match(connection.sent.at(-1).error, /unsupported_in_openclaw_v1:question_reply/);
  assert.equal(connection.sent.some((message) => message.type === "tool_done"), false);
  assert.equal(connection.sent.some((message) => message.type === "session_created"), false);
});

test("invalid payload for permission_reply and question_reply returns structured tool_error", async () => {
  const { bridge, connection } = createBridge();
  await bridge.start();
  await bridge.handleDownstreamMessage({
    type: "invoke",
    welinkSessionId: "wl_invalid_perm",
    action: "permission_reply",
    payload: {
      toolSessionId: "tool_invalid_perm",
      response: "once",
    },
  });

  assert.equal(connection.sent.at(-1).type, "tool_error");
  assert.match(connection.sent.at(-1).error, /permission_reply requires toolSessionId, permissionId, response/);

  await bridge.handleDownstreamMessage({
    type: "invoke",
    welinkSessionId: "wl_invalid_question",
    action: "question_reply",
    payload: {
      toolSessionId: "tool_invalid_question",
    },
  });

  assert.equal(connection.sent.at(-1).type, "tool_error");
  assert.match(connection.sent.at(-1).error, /question_reply requires toolSessionId and answer/);
  assert.equal(connection.sent.some((message) => "errorCode" in message), false);
  assert.equal(connection.sent.some((message) => "action" in message), false);
  assert.equal(connection.sent.some((message) => message.type === "tool_done"), false);
  assert.equal(connection.sent.some((message) => message.type === "session_created"), false);
});

test("invalid payload for permission_reply and question_reply uses baseline validation rules", async () => {
  const { bridge, connection } = createBridge();
  await bridge.start();
  await bridge.handleDownstreamMessage({
    type: "invoke",
    welinkSessionId: "wl_invalid_perm_response",
    action: "permission_reply",
    payload: {
      toolSessionId: "tool_invalid_perm_response",
      permissionId: "perm_invalid",
      response: "deny",
    },
  });

  assert.equal(connection.sent.at(-1).type, "tool_error");
  assert.match(connection.sent.at(-1).error, /response must be "once", "always", or "reject"/);

  await bridge.handleDownstreamMessage({
    type: "invoke",
    welinkSessionId: "wl_invalid_question_call",
    action: "question_reply",
    payload: {
      toolSessionId: "tool_invalid_question_call",
      answer: "ok",
      toolCallId: "",
    },
  });

  assert.equal(connection.sent.at(-1).type, "tool_error");
  assert.match(connection.sent.at(-1).error, /toolCallId must be a non-empty string/);
});
