/**
 * Mock Gateway Server for testing
 * Simulates AI-Gateway WebSocket server behavior
 */

export class MockGatewayServer {
  constructor(options = {}) {
    this.port = options.port || 8888;
    this.receivedMessages = [];
    this.receivedToolErrors = [];
    this.connected = false;
  }

  /**
   * Simulate starting the mock server
   */
  async start() {
    this.connected = true;
    return { port: this.port, connected: true };
  }

  /**
   * Simulate stopping the mock server
   */
  async stop() {
    this.connected = false;
    return { stopped: true };
  }

  /**
   * Simulate receiving a message from client
   */
  receive(message) {
    this.receivedMessages.push({
      ...message,
      timestamp: Date.now()
    });
    return { received: true };
  }

  /**
   * Simulate sending an invoke message to client
   */
  sendInvoke(payload) {
    const message = {
      type: 'invoke',
      welinkSessionId: payload.welinkSessionId || 'test-session',
      action: payload.action,
      payload: payload.payload,
      timestamp: Date.now()
    };
    return { sent: true, message };
  }

  /**
   * Simulate sending a status_query message
   */
  sendStatusQuery() {
    const message = {
      type: 'status_query',
      timestamp: Date.now()
    };
    return { sent: true, message };
  }

  /**
   * Record a tool_error message received
   */
  recordToolError(message) {
    this.receivedToolErrors.push(message);
  }

  /**
   * Wait for a specific message type
   */
  async waitForMessage(type, timeout = 5000) {
    const startTime = Date.now();

    return new Promise((resolve, reject) => {
      const check = () => {
        const elapsed = Date.now() - startTime;
        if (elapsed > timeout) {
          reject(new Error(`Timeout waiting for ${type} message`));
          return;
        }

        const message = this.receivedMessages.find(m => m.type === type);
        if (message) {
          resolve(message);
          return;
        }

        setTimeout(check, 50);
      };

      check();
    });
  }

  /**
   * Clear all recorded messages
   */
  clear() {
    this.receivedMessages = [];
    this.receivedToolErrors = [];
  }
}

/**
 * Create a mock gateway with default configuration
 */
export function createMockGateway(options = {}) {
  return new MockGatewayServer(options);
}
