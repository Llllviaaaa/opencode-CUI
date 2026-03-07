import { describe, test, expect } from 'bun:test';

import { ChatAction } from '../../dist/action/ChatAction.js';
import { CreateSessionAction } from '../../dist/action/CreateSessionAction.js';
import { CloseSessionAction } from '../../dist/action/CloseSessionAction.js';
import { PermissionReplyAction } from '../../dist/action/PermissionReplyAction.js';

const actions = [
  new ChatAction(),
  new CreateSessionAction(),
  new CloseSessionAction(),
  new PermissionReplyAction(),
];

function context(state) {
  return {
    client: {},
    connectionState: state,
    agentId: 'agent-1',
    sessionId: 's-1',
  };
}

function payloadFor(actionName) {
  if (actionName === 'chat') return { sessionId: 's-1', message: 'hi' };
  if (actionName === 'create_session') return { metadata: {} };
  if (actionName === 'close_session') return { sessionId: 's-1' };
  return { permissionId: 'p1', toolSessionId: 's-1', response: 'allow' };
}

describe('fast-fail mapping', () => {
  for (const action of actions) {
    test(`${action.name}: DISCONNECTED -> GATEWAY_UNREACHABLE`, async () => {
      const result = await action.execute(payloadFor(action.name), context('DISCONNECTED'));
      expect(result.success).toBe(false);
      expect(result.errorCode).toBe('GATEWAY_UNREACHABLE');
    });

    test(`${action.name}: CONNECTING -> GATEWAY_UNREACHABLE`, async () => {
      const result = await action.execute(payloadFor(action.name), context('CONNECTING'));
      expect(result.success).toBe(false);
      expect(result.errorCode).toBe('GATEWAY_UNREACHABLE');
    });

    test(`${action.name}: CONNECTED -> AGENT_NOT_READY`, async () => {
      const result = await action.execute(payloadFor(action.name), context('CONNECTED'));
      expect(result.success).toBe(false);
      expect(result.errorCode).toBe('AGENT_NOT_READY');
    });
  }
});
