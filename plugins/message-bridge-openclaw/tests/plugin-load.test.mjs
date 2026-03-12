import test from "node:test";
import assert from "node:assert/strict";
import plugin from "../dist/index.js";

test("plugin registers channel and stores runtime", async () => {
  let registered = null;
  const api = {
    runtime: {
      subagent: {},
      channel: {},
    },
    registerChannel(input) {
      registered = input;
    },
  };

  plugin.register(api);

  assert.ok(registered);
  assert.equal(registered.plugin.id, "message-bridge");
});
