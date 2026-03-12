import test from "node:test";
import assert from "node:assert/strict";
import { SessionRegistry } from "../dist/session/SessionRegistry.js";

test("ensure creates stable session mapping", () => {
  const registry = new SessionRegistry("bridge:default");
  const first = registry.ensure("tool_1", "wl_1");
  const second = registry.ensure("tool_1");

  assert.equal(first.sessionKey, "bridge:default:tool_1");
  assert.equal(second.sessionKey, first.sessionKey);
  assert.equal(second.welinkSessionId, "wl_1");
});
