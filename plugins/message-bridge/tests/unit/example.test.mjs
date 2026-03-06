import { test, describe } from 'node:test';
import { strict as assert } from 'node:assert';
import { 
  ChatAction,
  CreateSessionAction,
  CloseSessionAction,
  PermissionReplyAction,
  StatusQueryAction
} from '../../dist/action/index.js';
import { FastFailDetector } from '../../dist/error/FastFailDetector.js';
import { ErrorMapper } from '../../dist/error/ErrorMapper.js';
import { EventFilter } from '../../dist/event/EventFilter.js';
import { EnvelopeBuilder } from '../../dist/event/EnvelopeBuilder.js';
import { MessageBridgePluginClass } from '../../dist/plugin/MessageBridgePlugin.js';
import { DefaultActionRegistry } from '../../dist/action/ActionRegistry.js';

// Mock implementations for testing
class MockSessionClient {
  create = async ({ sessionId, metadata } = {}) => {
    return { success: true, data: { sessionId: sessionId || 'mock-session-id', metadata } };
  };

  abort = async ({ sessionId }) => {
    return { success: true, data: { sessionId, aborted: true } };
  };

  prompt = async ({ sessionId, message }) => {
    return { success: true, data: { sessionId, message, response: `Response to: ${message}` } };
  };
}

class MockPermissionClient {
  postSessionIdPermissionsPermissionId = async (options) => {
    return { 
      success: true, 
      data: { 
        permissionId: options.permissionId, 
        decision: options.request.decision,
        sessionId: options.sessionId 
      } 
    };
  };
}

class MockOpencodeClient {
  session = new MockSessionClient();
  postSessionIdPermissionsPermissionId = (options) => {
    return new MockPermissionClient().postSessionIdPermissionsPermissionId(options);
  };
}

// Test the action classes
describe('Action Classes Unit Tests', () => {
  describe('ChatAction', () => {
    test('should validate valid chat payload', () => {
      const chatAction = new ChatAction();
      const validPayload = { sessionId: 'test-session', message: 'Hello' };
      const result = chatAction.validate(validPayload);
      assert.ok(result.valid, 'Valid payload should pass validation');
    });

    test('should reject invalid chat payload - missing sessionId', () => {
      const chatAction = new ChatAction();
      const invalidPayload = { message: 'Hello' };
      const result = chatAction.validate(invalidPayload);
      assert.ok(!result.valid, 'Invalid payload should fail validation');
      assert.ok(result.error, 'Should have error message');
    });

    test('should reject invalid chat payload - missing message', () => {
      const chatAction = new ChatAction();
      const invalidPayload = { sessionId: 'test-session' };
      const result = chatAction.validate(invalidPayload);
      assert.ok(!result.valid, 'Invalid payload should fail validation');
      assert.ok(result.error, 'Should have error message');
    });

    test('should fail execution when state is not READY', async () => {
      const chatAction = new ChatAction();
      const payload = { sessionId: 'test-session', message: 'Hello' };
      const context = {
        client: new MockOpencodeClient(),
        connectionState: 'CONNECTING',
        agentId: 'test-agent'
      };
      
      const result = await chatAction.execute(payload, context);
      assert.ok(!result.success, 'Should fail with non-ready state');
      assert.equal(result.errorCode, 'GATEWAY_UNREACHABLE', 'Should return GATEWAY_UNREACHABLE for CONNECTING');
    });

    test('should handle SDK error during execution', async () => {
      const chatAction = new ChatAction();
      const badClient = {
        session: {
          async prompt() {
            throw new Error('timeout occurred after 30 seconds');  
          }
        }
      };

      const payload = { sessionId: 'test-session', message: 'Hello' };
      const context = {
        client: badClient,
        connectionState: 'READY',
        agentId: 'test-agent'
      };
      
      const result = await chatAction.execute(payload, context);
      assert.ok(!result.success, 'Should fail when SDK throws error');
      assert.equal(result.errorCode, 'SDK_UNREACHABLE', 'Should return SDK_UNREACHABLE error code for SDK rejection');
    });

    test('should execute successfully with valid client and READY state', async () => {
      const chatAction = new ChatAction();
      const payload = { sessionId: 'test-session', message: 'Hello from unit test' };
      const context = {
        client: new MockOpencodeClient(),
        connectionState: 'READY',
        agentId: 'test-agent'
      };

      const result = await chatAction.execute(payload, context);
      assert.ok(result.success, 'Chat execution should succeed with valid client');
      assert.ok(result.data, 'Chat execution should return response data');
    });
  });

  describe('CreateSessionAction', () => {
    test('should validate valid create session payload', () => {
      const createSessionAction = new CreateSessionAction();
      const validPayload = { sessionId: 'new-session', metadata: { type: 'test' } };
      const result = createSessionAction.validate(validPayload);
      assert.ok(result.valid, 'Valid payload should pass validation');
    });

    test('should validate empty payload', () => {
      const createSessionAction = new CreateSessionAction();
      const validPayload = {};
      const result = createSessionAction.validate(validPayload);
      assert.ok(result.valid, 'Empty payload should pass validation');
    });

    test('should reject invalid sessionId', () => {
      const createSessionAction = new CreateSessionAction();
      const invalidPayload = { sessionId: '' };
      const result = createSessionAction.validate(invalidPayload);
      assert.ok(!result.valid, 'Invalid payload should fail validation');
    });

    test('should execute successfully with mock client', async () => {
      const createSessionAction = new CreateSessionAction();
      const payload = { sessionId: 'new-test-session', metadata: { purpose: 'testing' } };
      const context = {
        client: new MockOpencodeClient(),
        connectionState: 'READY',
        agentId: 'test-agent'
      };
      
      const result = await createSessionAction.execute(payload, context);
      assert.ok(result.success, 'Should succeed with valid input');
      assert.ok(result.data.sessionId, 'Should return session ID');
    });
  });

  describe('CloseSessionAction', () => {
    test('should validate valid close session payload', () => {
      const closeSessionAction = new CloseSessionAction();
      const validPayload = { sessionId: 'existing-session' };
      const result = closeSessionAction.validate(validPayload);
      assert.ok(result.valid, 'Valid payload should pass validation');
    });

    test('should reject invalid close session payload', () => {
      const closeSessionAction = new CloseSessionAction();
      const invalidPayload = { sessionId: '' };
      const result = closeSessionAction.validate(invalidPayload);
      assert.ok(!result.valid, 'Invalid payload should fail validation');
    });

    test('should properly execute close session with abort semantics', async () => {
      const closeSessionAction = new CloseSessionAction();
      const payload = { sessionId: 'session-to-close' };
      const context = {
        client: new MockOpencodeClient(),
        connectionState: 'READY',
        agentId: 'test-agent'
      };
      
      const result = await closeSessionAction.execute(payload, context);
      assert.ok(result.success, 'Should succeed with valid input');
      assert.ok(result.data.closed, 'Should indicate session was closed');
    });

    test('should fail execution when state is not READY', async () => {
      const closeSessionAction = new CloseSessionAction();
      const payload = { sessionId: 'session-to-close' };
      const context = {
        client: new MockOpencodeClient(),
        connectionState: 'DISCONNECTED',
        agentId: 'test-agent'
      };
      
      const result = await closeSessionAction.execute(payload, context);
      assert.ok(!result.success, 'Should fail with non-ready state');
      assert.equal(result.errorCode, 'GATEWAY_UNREACHABLE', 'Should return GATEWAY_UNREACHABLE for DISCONNECTED');
    });
  });

  describe('PermissionReplyAction', () => {
    test('should validate target format payload', () => {
      const permissionReplyAction = new PermissionReplyAction();
      const targetPayload = { permissionId: 'perm-123', response: 'allow' };
      const result = permissionReplyAction.validate(targetPayload);
      assert.ok(result.valid, 'Valid target format payload should pass validation');
    });

    test('should validate compatibility format payload', () => {
      const permissionReplyAction = new PermissionReplyAction();
      const compatPayload = { permissionId: 'perm-123', approved: true };
      const result = permissionReplyAction.validate(compatPayload);
      assert.ok(result.valid, 'Valid compat format payload should pass validation');
    });

    test('should reject invalid response value', () => {
      const permissionReplyAction = new PermissionReplyAction();
      const invalidTargetPayload = { permissionId: 'perm-123', response: 'invalid' };
      const result = permissionReplyAction.validate(invalidTargetPayload);
      assert.ok(!result.valid, 'Invalid response should fail validation');
    });

    test('should reject invalid approved value', () => {
      const permissionReplyAction = new PermissionReplyAction();
      const invalidCompatPayload = { permissionId: 'perm-123', approved: 'not-boolean' };
      const result = permissionReplyAction.validate(invalidCompatPayload);
      assert.ok(!result.valid, 'Invalid approved value should fail validation');
    });

    test('should handle target format in execution', async () => {
      const permissionReplyAction = new PermissionReplyAction();
      const targetPayload = { 
        permissionId: 'perm-123', 
        response: 'always',
        toolSessionId: 'session-456'
      };
      
      const context = {
        client: new MockOpencodeClient(),
        connectionState: 'READY',
        agentId: 'test-agent'
      };
      
      const result = await permissionReplyAction.execute(targetPayload, context);
      assert.ok(result.success, 'Should succeed with target format');
      assert.deepStrictEqual(result.data.response, 'always', 'Should reflect the target response');
    });

    test('should handle compatibility format in execution', async () => {
      const permissionReplyAction = new PermissionReplyAction();
      const compatPayload = { permissionId: 'perm-456', approved: false };
      
      const context = {
        client: new MockOpencodeClient(),
        connectionState: 'READY',
        agentId: 'test-agent'
      };
      
      const result = await permissionReplyAction.execute(compatPayload, context);
      assert.ok(result.success, 'Should succeed with compat format');
      assert.deepStrictEqual(result.data.response, 'deny', 'Should convert approved=false to response=deny');
    });
  });

  describe('StatusQueryAction', () => {
    test('should validate empty payload', () => {
      const statusQueryAction = new StatusQueryAction();
      const validPayload = {};
      const result = statusQueryAction.validate(validPayload);
      assert.ok(result.valid, 'Empty payload should pass validation');
    });

    test('should validate with optional sessionId', () => {
      const statusQueryAction = new StatusQueryAction();
      const validPayload = { sessionId: 'session-123' };
      const result = statusQueryAction.validate(validPayload);
      assert.ok(result.valid, 'Payload with sessionId should pass validation');
    });

    test('should succeed with any connection state', async () => {
      const statusQueryAction = new StatusQueryAction();
      const payload = { sessionId: 'test-session' };
      
      for (const state of ['DISCONNECTED', 'CONNECTING', 'CONNECTED', 'READY']) {
        const context = {
          client: new MockOpencodeClient(),
          connectionState: state,
          agentId: 'test-agent'
        };
        
        const result = await statusQueryAction.execute(payload, context);
        assert.ok(result.success, 'Should succeed regardless of state');
        assert.strictEqual(result.data.opencodeOnline, state === 'READY', 'Online status should depend on READY state');
        assert.strictEqual(result.data.connectionState, state, 'Should return the connection state');
      }
    });

    test('should reject invalid non-object payload', () => {
      const statusQueryAction = new StatusQueryAction();
      const result = statusQueryAction.validate('invalid');
      assert.equal(result.valid, false, 'Non-object payload should fail validation');
      assert.ok(result.error, 'Validation should return error message');
    });

    test('should map timeout and connectivity errors', () => {
      const statusQueryAction = new StatusQueryAction();
      assert.equal(statusQueryAction.errorMapper(new Error('network timeout')), 'SDK_TIMEOUT');
      assert.equal(statusQueryAction.errorMapper(new Error('connection unreachable')), 'SDK_UNREACHABLE');
    });
  });
});

describe('Utility Classes Unit Tests', () => {
  describe('FastFailDetector', () => {
    test('should detect error codes based on connection state', () => {
      const detector = new FastFailDetector();
      assert.strictEqual(detector.checkState('DISCONNECTED'), 'GATEWAY_UNREACHABLE', 'Disconnected should return GATEWAY_UNREACHABLE');
      assert.strictEqual(detector.checkState('CONNECTING'), 'GATEWAY_UNREACHABLE', 'Connecting should return GATEWAY_UNREACHABLE');
      assert.strictEqual(detector.checkState('CONNECTED'), 'AGENT_NOT_READY', 'Connected should return AGENT_NOT_READY');
      assert.strictEqual(detector.checkState('READY'), null, 'Ready should not return error');
    });

    test('should check gateway reachability properly', () => {
      const detector = new FastFailDetector();
      assert.strictEqual(detector.isGatewayReachable('DISCONNECTED'), false, 'Disconnected should not be reachable');
      assert.strictEqual(detector.isGatewayReachable('CONNECTING'), false, 'Connecting should not be reachable');
      assert.strictEqual(detector.isGatewayReachable('CONNECTED'), true, 'Connected should be reachable');
      assert.strictEqual(detector.isGatewayReachable('READY'), true, 'Ready should be reachable');
    });
  });

  describe('ErrorMapper', () => {
    test('should map timeout errors correctly', () => {
      const errorMapper = new ErrorMapper();
      const timeoutError = new Error('Request timeout occurred');
      assert.strictEqual(errorMapper.fromSDKError(timeoutError), 'SDK_TIMEOUT', 'Should map timeout errors');
    });

    test('should map connection/network errors', () => {
      const errorMapper = new ErrorMapper();
      const networkError = new Error('Network request failed');
      assert.strictEqual(errorMapper.fromSDKError(networkError), 'SDK_UNREACHABLE', 'Should map network errors');
    });

    test('should map validation errors', () => {
      const errorMapper = new ErrorMapper();
      const validationErrors = ['Invalid field', 'Missing required parameter'];
      assert.strictEqual(errorMapper.fromValidationError(validationErrors), 'INVALID_PAYLOAD', 'Should map validation errors to INVALID_PAYLOAD');
    });
  });

  describe('EventFilter', () => {
    test('should allow valid events from default allowlist', () => {
      const filter = new EventFilter();
      assert.ok(filter.isAllowed('message.start'), 'Should allow message.* pattern');
      assert.ok(filter.isAllowed('permission.changed'), 'Should allow permission.* pattern');
      assert.ok(filter.isAllowed('session.created'), 'Should allow session.* pattern');
      assert.ok(filter.isAllowed('file.edited'), 'Should allow exact match file.edited');
      assert.ok(filter.isAllowed('todo.updated'), 'Should allow exact match todo.updated');
      assert.ok(filter.isAllowed('command.executed'), 'Should allow exact match command.executed');
    });

    test('should deny events not in allowlist', () => {
      const filter = new EventFilter();
      assert.ok(!filter.isAllowed('security.alert'), 'Should deny non-matching event');
      assert.ok(!filter.isAllowed('user.login'), 'Should deny non-matching event');
    });

    test('should allow custom allowlist', () => {
      const filter = new EventFilter(['custom.event', 'api.*']);
      assert.ok(filter.isAllowed('custom.event'), 'Should allow custom exact match');
      assert.ok(filter.isAllowed('api.call'), 'Should allow custom prefix match');
      assert.ok(!filter.isAllowed('message.start'), 'Should not allow events not in custom list');
    });
  });

  describe('EnvelopeBuilder', () => {
    test('should build envelopes with proper structure', () => {
      const builder = new EnvelopeBuilder('test-agent-123');
      const envelope = builder.build();

      // Check required fields exist
      assert.ok(envelope.version, 'Should have version');
      assert.strictEqual(envelope.version, '1.0', 'Should have expected version');
      assert.ok(envelope.messageId, 'Should have message ID');
      assert.ok(envelope.timestamp, 'Should have timestamp');
      assert.strictEqual(envelope.source, 'message-bridge', 'Should have correct source');
      assert.strictEqual(envelope.agentId, 'test-agent-123', 'Should have correct agent ID');
      assert.strictEqual(envelope.sequenceNumber, 1, 'Should start with sequence number 1');
      assert.strictEqual(envelope.sequenceScope, 'global', 'Global scope when no session ID');
    });

    test('should build envelopes with session ID and scoped sequences', () => {
      const builder = new EnvelopeBuilder('test-agent-456');
      
      // Create multiple envelopes with same session ID
      const sessionEnvelope1 = builder.build('session-abc');
      const sessionEnvelope2 = builder.build('session-abc');
      const globalEnvelope = builder.build();
      
      // Check sequences are independent
      assert.strictEqual(sessionEnvelope1.sequenceNumber, 1, 'First session envelope should have sequence 1');
      assert.strictEqual(sessionEnvelope2.sequenceNumber, 2, 'Second session envelope should have sequence 2');
      assert.strictEqual(globalEnvelope.sequenceNumber, 1, 'Global envelope should have sequence 1 separately');
    });

    test('should maintain separate sequence counters per session', () => {
      const builder = new EnvelopeBuilder('test-agent-789');
      
      // Create envelopes for different sessions
      const envA1 = builder.build('sessionA');
      const envB1 = builder.build('sessionB');
      const envA2 = builder.build('sessionA');
      const envB2 = builder.build('sessionB');
      const envC1 = builder.build('sessionC');
      
      // Each session should have independent sequence numbers
      assert.strictEqual(envA1.sequenceNumber, 1, 'Session A first envelope');
      assert.strictEqual(envA2.sequenceNumber, 2, 'Session A second envelope');
      assert.strictEqual(envB1.sequenceNumber, 1, 'Session B first envelope');
      assert.strictEqual(envB2.sequenceNumber, 2, 'Session B second envelope');
      assert.strictEqual(envC1.sequenceNumber, 1, 'Session C first envelope');
    });
  });

  describe('Plugin Lifecycle', () => {
    test('should start and stop safely when disabled by env config', async () => {
      const prev = process.env.BRIDGE_ENABLED;
      process.env.BRIDGE_ENABLED = 'false';

      const registry = new DefaultActionRegistry();
      const plugin = new MessageBridgePluginClass(registry);
      await plugin.start();
      await plugin.stop();

      if (prev === undefined) {
        delete process.env.BRIDGE_ENABLED;
      } else {
        process.env.BRIDGE_ENABLED = prev;
      }
      assert.ok(true, 'Plugin start/stop should complete without external dependencies when disabled');
    });
  });
});
