#!/usr/bin/env node

import { randomUUID } from "node:crypto";

function printUsage() {
  console.log(`用法：
  npm run validate:skill-relay -- --ak <ak> --user-id <userId> [选项]

必填参数：
  --ak                目标 agent 的 AK
  --user-id           目标 agent 绑定的 userId

可选参数：
  --internal-token    /ws/skill 握手 token，默认读取 SKILL_SERVER_INTERNAL_TOKEN
  --ws-url            skill relay WebSocket 地址，默认 ws://127.0.0.1:8081/ws/skill
  --source            上游 source，默认 skill-server
  --tool-session-id   验证用 toolSessionId，默认自动生成
  --welink-session-id 验证用 welinkSessionId，默认自动生成
  --chat-text         chat 文本，默认精确回复校验文本
  --timeout-ms        单步等待超时，默认 15000
  --skip-unsupported  跳过 unsupported fail-closed 校验
  --help              显示帮助
`);
}

function parseArgs(argv) {
  const result = {};
  for (let index = 0; index < argv.length; index += 1) {
    const token = argv[index];
    if (!token.startsWith("--")) {
      throw new Error(`无法解析参数：${token}`);
    }
    const key = token.slice(2);
    const next = argv[index + 1];
    if (!next || next.startsWith("--")) {
      result[key] = "true";
      continue;
    }
    result[key] = next;
    index += 1;
  }
  return result;
}

function requireGlobalWebSocket() {
  if (typeof WebSocket !== "function") {
    throw new Error("当前 Node 运行时缺少全局 WebSocket，请使用 Node 22+。");
  }
}

function encodeAuthProtocol(token, source) {
  const payload = JSON.stringify({ token, source });
  return `auth.${Buffer.from(payload, "utf8").toString("base64url")}`;
}

function logLine(message, data) {
  const now = new Date().toISOString();
  if (data === undefined) {
    console.log(`[${now}] ${message}`);
    return;
  }
  console.log(`[${now}] ${message}`);
  console.log(JSON.stringify(data, null, 2));
}

async function messageDataToString(data) {
  if (typeof data === "string") {
    return data;
  }
  if (data instanceof ArrayBuffer) {
    return Buffer.from(data).toString("utf8");
  }
  if (ArrayBuffer.isView(data)) {
    return Buffer.from(data.buffer, data.byteOffset, data.byteLength).toString("utf8");
  }
  if (typeof Blob !== "undefined" && data instanceof Blob) {
    return await data.text();
  }
  return String(data);
}

function createMessageQueue(ws) {
  const queue = [];
  const waiters = [];
  let terminalError = null;

  const flush = (message) => {
    const index = waiters.findIndex((waiter) => waiter.predicate(message));
    if (index >= 0) {
      const [waiter] = waiters.splice(index, 1);
      clearTimeout(waiter.timer);
      waiter.resolve(message);
      return true;
    }
    return false;
  };

  const failAll = (error) => {
    terminalError = error;
    while (waiters.length > 0) {
      const waiter = waiters.shift();
      clearTimeout(waiter.timer);
      waiter.reject(error);
    }
  };

  ws.addEventListener("message", (event) => {
    void messageDataToString(event.data)
      .then((text) => {
        let parsed;
        try {
          parsed = JSON.parse(text);
        } catch (error) {
          logLine("收到无法解析的消息", { text, error: error instanceof Error ? error.message : String(error) });
          return;
        }
        logLine("收到消息", parsed);
        if (!flush(parsed)) {
          queue.push(parsed);
        }
      })
      .catch((error) => {
        failAll(error instanceof Error ? error : new Error(String(error)));
      });
  });

  ws.addEventListener("close", (event) => {
    failAll(new Error(`WebSocket 已关闭：code=${event.code} reason=${event.reason || "none"}`));
  });

  ws.addEventListener("error", () => {
    failAll(new Error("WebSocket transport error"));
  });

  return {
    async waitFor(predicate, timeoutMs) {
      const queuedIndex = queue.findIndex(predicate);
      if (queuedIndex >= 0) {
        return queue.splice(queuedIndex, 1)[0];
      }
      if (terminalError) {
        throw terminalError;
      }
      return await new Promise((resolve, reject) => {
        const timer = setTimeout(() => {
          const waiterIndex = waiters.findIndex((entry) => entry.resolve === resolve);
          if (waiterIndex >= 0) {
            waiters.splice(waiterIndex, 1);
          }
          reject(new Error(`等待消息超时（${timeoutMs}ms）`));
        }, timeoutMs);
        waiters.push({ predicate, resolve, reject, timer });
      });
    },
  };
}

async function openWebSocket(url, protocol, timeoutMs) {
  requireGlobalWebSocket();
  return await new Promise((resolve, reject) => {
    const ws = new WebSocket(url, protocol);
    const timer = setTimeout(() => {
      try {
        ws.close();
      } catch {
        // ignore close failure on timeout
      }
      reject(new Error(`连接 /ws/skill 超时（${timeoutMs}ms）`));
    }, timeoutMs);

    const cleanup = () => {
      clearTimeout(timer);
      ws.removeEventListener("open", onOpen);
      ws.removeEventListener("error", onError);
    };

    const onOpen = () => {
      cleanup();
      resolve(ws);
    };
    const onError = () => {
      cleanup();
      reject(new Error("连接 /ws/skill 失败"));
    };

    ws.addEventListener("open", onOpen);
    ws.addEventListener("error", onError);
  });
}

function sendJson(ws, payload) {
  logLine("发送消息", payload);
  ws.send(JSON.stringify(payload));
}

function isToolMessageFor(toolSessionId, types) {
  return (message) =>
    types.includes(message?.type) &&
    message?.toolSessionId === toolSessionId;
}

async function collectUntil(queue, predicate, done, timeoutMs) {
  const messages = [];
  while (true) {
    const message = await queue.waitFor(predicate, timeoutMs);
    messages.push(message);
    if (done(message)) {
      return messages;
    }
  }
}

function assertCondition(condition, message) {
  if (!condition) {
    throw new Error(message);
  }
}

async function assertUnsupportedAction({
  ws,
  queue,
  timeoutMs,
  source,
  ak,
  userId,
  welinkSessionId,
  toolSessionId,
  action,
  payload,
}) {
  sendJson(ws, {
    type: "invoke",
    source,
    ak,
    userId,
    welinkSessionId,
    action,
    payload,
  });
  const unsupported = await queue.waitFor(
    (message) => message?.type === "tool_error" && message?.toolSessionId === toolSessionId,
    timeoutMs,
  );
  assertCondition(unsupported?.errorCode === "unsupported_in_openclaw_v1", `${action} errorCode 不符合预期：${JSON.stringify(unsupported)}`);
  assertCondition(unsupported?.action === action, `${action} action 字段不符合预期：${JSON.stringify(unsupported)}`);
  assertCondition(
    typeof unsupported?.error === "string" && unsupported.error.includes(`unsupported_in_openclaw_v1:${action}`),
    `${action} fail-closed 未命中预期：${JSON.stringify(unsupported)}`,
  );
}

async function main() {
  const rawArgs = parseArgs(process.argv.slice(2));
  if (rawArgs.help === "true") {
    printUsage();
    return;
  }

  const ak = rawArgs.ak?.trim();
  const userId = rawArgs["user-id"]?.trim();
  const internalToken = rawArgs["internal-token"]?.trim() || process.env.SKILL_SERVER_INTERNAL_TOKEN?.trim();
  const wsUrl = rawArgs["ws-url"]?.trim() || "ws://127.0.0.1:8081/ws/skill";
  const source = rawArgs.source?.trim() || "skill-server";
  const timeoutMs = Number.parseInt(rawArgs["timeout-ms"] ?? "15000", 10);
  const toolSessionId = rawArgs["tool-session-id"]?.trim() || `session-stage1-${randomUUID()}`;
  const welinkSessionId = rawArgs["welink-session-id"]?.trim() || `welink-stage1-${randomUUID()}`;
  const chatText =
    rawArgs["chat-text"]?.trim() ||
    "Reply with exactly: hello from openclaw bridge verification";
  const skipUnsupported = rawArgs["skip-unsupported"] === "true";

  if (!ak) {
    throw new Error("缺少必填参数 --ak");
  }
  if (!userId) {
    throw new Error("缺少必填参数 --user-id");
  }
  if (!internalToken) {
    throw new Error("缺少 --internal-token，且环境变量 SKILL_SERVER_INTERNAL_TOKEN 未设置。");
  }
  if (!Number.isFinite(timeoutMs) || timeoutMs <= 0) {
    throw new Error(`非法 timeout-ms：${rawArgs["timeout-ms"]}`);
  }

  logLine("开始 skill relay live 校验", {
    wsUrl,
    source,
    ak,
    userId,
    toolSessionId,
    welinkSessionId,
    timeoutMs,
    skipUnsupported,
  });

  const protocol = encodeAuthProtocol(internalToken, source);
  const ws = await openWebSocket(wsUrl, protocol, timeoutMs);
  const queue = createMessageQueue(ws);

  try {
    sendJson(ws, {
      type: "status_query",
      source,
      ak,
      userId,
    });
    const statusResponse = await queue.waitFor((message) => message?.type === "status_response", timeoutMs);
    assertCondition(typeof statusResponse.opencodeOnline === "boolean", "status_response 缺少 opencodeOnline");

    sendJson(ws, {
      type: "invoke",
      source,
      ak,
      userId,
      welinkSessionId,
      action: "create_session",
      payload: {
        sessionId: toolSessionId,
      },
    });
    const created = await queue.waitFor(
      (message) => message?.type === "session_created" && message?.toolSessionId === toolSessionId,
      timeoutMs,
    );
    assertCondition(created?.session?.sessionId, "session_created 缺少 session.sessionId");

    sendJson(ws, {
      type: "invoke",
      source,
      ak,
      userId,
      welinkSessionId,
      action: "chat",
      payload: {
        toolSessionId,
        text: chatText,
      },
    });
    const chatMessages = await collectUntil(
      queue,
      isToolMessageFor(toolSessionId, ["tool_event", "tool_done", "tool_error"]),
      (message) => message?.type === "tool_done" || message?.type === "tool_error",
      timeoutMs,
    );
    const finalChat = chatMessages.at(-1);
    assertCondition(finalChat?.type === "tool_done", `chat 未成功结束：${JSON.stringify(finalChat)}`);

    sendJson(ws, {
      type: "invoke",
      source,
      ak,
      userId,
      welinkSessionId,
      action: "close_session",
      payload: {
        toolSessionId,
      },
    });
    const closeDone = await queue.waitFor(
      (message) => message?.type === "tool_done" && message?.toolSessionId === toolSessionId,
      timeoutMs,
    );
    assertCondition(closeDone?.type === "tool_done", "close_session 未返回 tool_done");

    if (!skipUnsupported) {
      await assertUnsupportedAction({
        ws,
        queue,
        timeoutMs,
        source,
        ak,
        userId,
        welinkSessionId: `${welinkSessionId}-unsupported-permission`,
        toolSessionId,
        action: "permission_reply",
        payload: {
          toolSessionId,
          permissionId: "perm-stage1-001",
          response: "once",
        },
      });
      await assertUnsupportedAction({
        ws,
        queue,
        timeoutMs,
        source,
        ak,
        userId,
        welinkSessionId: `${welinkSessionId}-unsupported-question`,
        toolSessionId,
        action: "question_reply",
        payload: {
          toolSessionId,
          answer: "ok",
        },
      });
    }

    logLine("skill relay live 校验通过", {
      statusQuery: "ok",
      createSession: "ok",
      chat: "ok",
      closeSession: "ok",
      unsupportedPermissionReply: skipUnsupported ? "skipped" : "ok",
      unsupportedQuestionReply: skipUnsupported ? "skipped" : "ok",
    });
  } finally {
    try {
      ws.close();
    } catch {
      // ignore close failure
    }
  }
}

main().catch((error) => {
  console.error(error instanceof Error ? error.message : String(error));
  process.exitCode = 1;
});
