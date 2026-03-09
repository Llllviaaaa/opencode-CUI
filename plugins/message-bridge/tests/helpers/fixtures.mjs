/**
 * Test fixtures and common utilities for tests
 */

/**
 * Generate a random UUID for testing
 */
export function generateTestId(prefix = 'test') {
  return `${prefix}-${crypto.randomUUID().slice(0, 8)}`;
}

/**
 * Create a register message for testing
 */
export function createRegisterMessage(overrides = {}) {
  return {
    type: 'register',
    deviceName: overrides.deviceName || 'test-device',
    os: overrides.os || 'darwin',
    toolType: 'OPENCODE',
    toolVersion: overrides.toolVersion || '1.0.0',
    timestamp: Date.now(),
    ...overrides
  };
}

/**
 * Create an invoke message for testing
 */
export function createInvokeMessage(overrides = {}) {
  const action = overrides.action || 'chat';
  const payload =
    overrides.payload ||
    (action === 'chat'
      ? { toolSessionId: generateTestId('oc-sess'), text: 'Hello' }
      : action === 'create_session'
        ? {}
        : action === 'close_session'
          ? { toolSessionId: generateTestId('oc-sess') }
          : action === 'question_reply'
            ? { toolSessionId: generateTestId('oc-sess'), toolCallId: generateTestId('call'), answer: 'answer' }
            : { permissionId: generateTestId('perm'), toolSessionId: generateTestId('oc-sess'), response: 'once' });

  return {
    type: 'invoke',
    welinkSessionId: overrides.welinkSessionId || generateTestId('sess'),
    action,
    payload,
    timestamp: Date.now(),
    ...overrides
  };
}

/**
 * Create a tool_error message for testing
 */
export function createToolErrorMessage(overrides = {}) {
  return {
    type: 'tool_error',
    error: overrides.error || 'Test error',
    welinkSessionId: overrides.welinkSessionId || generateTestId('sess'),
    toolSessionId: overrides.toolSessionId,
    timestamp: Date.now(),
    ...overrides
  };
}

/**
 * Create a status_query message for testing
 */
export function createStatusQueryMessage(overrides = {}) {
  return {
    type: 'status_query',
    timestamp: Date.now(),
    ...overrides
  };
}

/**
 * Create a status_response message for testing
 */
export function createStatusResponseMessage(overrides = {}) {
  return {
    type: 'status_response',
    opencodeOnline: overrides.opencodeOnline !== undefined ? overrides.opencodeOnline : true,
    timestamp: Date.now(),
    ...overrides
  };
}

/**
 * Create a chat payload for testing
 */
export function createChatPayload(overrides = {}) {
  return {
    text: 'Hello OpenCode',
    toolSessionId: overrides.toolSessionId || generateTestId('oc-sess'),
    ...overrides
  };
}

/**
 * Create a create_session payload for testing
 */
export function createCreateSessionPayload(overrides = {}) {
  return {
    userId: 'user-123',
    ...overrides
  };
}

/**
 * Create a close_session payload for testing
 */
export function createCloseSessionPayload(overrides = {}) {
  return {
    toolSessionId: overrides.toolSessionId || generateTestId('oc-sess'),
    ...overrides
  };
}

/**
 * Create a permission_reply payload for testing
 */
export function createPermissionReplyPayload(overrides = {}) {
  return {
    permissionId: overrides.permissionId || generateTestId('perm'),
    toolSessionId: overrides.toolSessionId || generateTestId('oc-sess'),
    response: overrides.response || 'once',
    ...overrides
  };
}

/**
 * Wait for a specified duration
 */
export async function wait(ms) {
  return new Promise(resolve => setTimeout(resolve, ms));
}

/**
 * Wait for a condition to be true
 */
export async function waitFor(condition, timeout = 5000, interval = 50) {
  const startTime = Date.now();

  while (Date.now() - startTime < timeout) {
    if (await condition()) {
      return true;
    }
    await wait(interval);
  }

  throw new Error(`Condition not met within ${timeout}ms`);
}

/**
 * Measure execution time of a function
 */
export async function measureTime(fn) {
  const start = Date.now();
  try {
    const result = await fn();
    const duration = Date.now() - start;
    return { result, duration };
  } catch (error) {
    const duration = Date.now() - start;
    throw { error, duration };
  }
}

/**
 * Assert execution time is within bounds
 */
export function assertTiming(actual, minMs, maxMs, message) {
  if (actual < minMs || actual > maxMs) {
    throw new Error(
      `${message || 'Timing assertion failed'}: expected ${minMs}-${maxMs}ms, got ${actual}ms`
    );
  }
}

/**
 * Create a mock config for testing
 */
export function createTestConfig(overrides = {}) {
  return {
    config_version: 1,
    enabled: true,
    gateway: {
      url: 'ws://localhost:8888/ws/agent',
      heartbeatIntervalMs: 30000,
      reconnect: {
        baseMs: 1000,
        maxMs: 30000
      }
    },
    sdk: {
      timeoutMs: 10000
    },
    auth: {
      ak: 'test-ak',
      sk: 'test-sk'
    },
    events: {
      allowlist: [
        'message.*',
        'permission.*',
        'question.*',
        'session.*',
        'file.edited',
        'todo.updated',
        'command.executed'
      ]
    },
    ...overrides
  };
}

/**
 * Sanitize sensitive data for logging
 */
export function sanitizeLog(data) {
  if (typeof data === 'string') {
    return data.replace(/sk=["']([^"']+)["']/gi, 'sk="***"');
  }
  if (typeof data === 'object' && data !== null) {
    const sanitized = { ...data };
    if (sanitized.sk) {
      sanitized.sk = '***';
    }
    if (sanitized.secret) {
      sanitized.secret = '***';
    }
    return sanitized;
  }
  return data;
}
