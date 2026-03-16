import test from "node:test";
import assert from "node:assert/strict";
import { normalizeDownstreamMessage } from "../dist/protocol/downstream.js";

test("normalizes chat invoke message", () => {
  const result = normalizeDownstreamMessage({
    type: "invoke",
    welinkSessionId: "wl_1",
    action: "chat",
    payload: {
      toolSessionId: "tool_1",
      text: "hello",
    },
  });

  assert.equal(result.ok, true);
  assert.equal(result.value.action, "chat");
  assert.equal(result.value.payload.toolSessionId, "tool_1");
});

test("rejects unsupported message", () => {
  const result = normalizeDownstreamMessage({
    type: "unknown",
  });

  assert.equal(result.ok, false);
  assert.equal(result.error.code, "unsupported_message");
});

test("create_session requires welinkSessionId", () => {
  const result = normalizeDownstreamMessage({
    type: "invoke",
    action: "create_session",
    payload: {},
  });

  assert.equal(result.ok, false);
  assert.equal(result.error.code, "missing_required_field");
  assert.match(result.error.message, /welinkSessionId/);
});

test("permission_reply invalid payload is rejected with action context", () => {
  const result = normalizeDownstreamMessage({
    type: "invoke",
    action: "permission_reply",
    payload: {
      toolSessionId: "tool_1",
      response: "once",
    },
  });

  assert.equal(result.ok, false);
  assert.equal(result.error.code, "missing_required_field");
  assert.equal(result.error.action, "permission_reply");
});

test("permission_reply rejects unsupported response values", () => {
  const result = normalizeDownstreamMessage({
    type: "invoke",
    action: "permission_reply",
    payload: {
      toolSessionId: "tool_1",
      permissionId: "perm_1",
      response: "deny",
    },
  });

  assert.equal(result.ok, false);
  assert.equal(result.error.code, "invalid_payload");
  assert.equal(result.error.action, "permission_reply");
});

test("question_reply invalid payload is rejected with action context", () => {
  const result = normalizeDownstreamMessage({
    type: "invoke",
    action: "question_reply",
    payload: {
      toolSessionId: "tool_2",
    },
  });

  assert.equal(result.ok, false);
  assert.equal(result.error.code, "missing_required_field");
  assert.equal(result.error.action, "question_reply");
});

test("question_reply rejects blank toolCallId when provided", () => {
  const result = normalizeDownstreamMessage({
    type: "invoke",
    action: "question_reply",
    payload: {
      toolSessionId: "tool_2",
      answer: "ok",
      toolCallId: "   ",
    },
  });

  assert.equal(result.ok, false);
  assert.equal(result.error.code, "invalid_payload");
  assert.equal(result.error.action, "question_reply");
});

test("logs downstream.normalization_failed with stage and field", () => {
  const warns = [];
  const result = normalizeDownstreamMessage(
    {
      type: "invoke",
      welinkSessionId: "wl_log_1",
      action: "chat",
      payload: {
        toolSessionId: "tool_log_1",
      },
    },
    {
      info() {},
      warn(message, meta) {
        warns.push({ message, meta });
      },
      error() {},
    },
  );

  assert.equal(result.ok, false);
  assert.equal(warns.length, 1);
  assert.equal(warns[0].message, "downstream.normalization_failed");
  assert.equal(warns[0].meta.stage, "payload");
  assert.equal(warns[0].meta.field, "payload.text");
  assert.equal(warns[0].meta.errorCode, "missing_required_field");
  assert.equal(warns[0].meta.messageType, "invoke");
  assert.equal(warns[0].meta.action, "chat");
  assert.equal(warns[0].meta.welinkSessionId, "wl_log_1");
});
