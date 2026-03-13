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
