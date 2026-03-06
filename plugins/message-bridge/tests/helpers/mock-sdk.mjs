export class MockOpenCodeSDK {
  constructor(options = {}) {
    this.delayMs = options.delayMs || 0;
    this.calls = {
      sessionCreate: [],
      sessionChat: [],
      sessionPrompt: [],
      sessionAbort: [],
      sessionDelete: [],
      permissionReply: [],
      health: [],
    };

    this.session = {
      create: async (params = {}) => {
        await this._simulateDelay();
        return this._mockSessionCreate(params);
      },
      prompt: async (params) => {
        await this._simulateDelay();
        return this._mockSessionPrompt(params);
      },
      abort: async (params) => {
        await this._simulateDelay();
        return this._mockSessionAbort(params);
      },
      delete: async (params) => {
        await this._simulateDelay();
        return this._mockSessionDelete(params);
      },
      // Legacy compatibility helper used by a few tests.
      chat: async (sessionId, message) => {
        await this._simulateDelay();
        return this._mockSessionPrompt({ sessionId, message: message?.text ?? String(message ?? '') });
      },
    };

    this.postSessionIdPermissionsPermissionId = async (options) => {
      await this._simulateDelay();
      return this._mockPermissionReply(options);
    };

    this.global = {
      health: async () => {
        await this._simulateDelay();
        return this._mockHealth();
      },
    };
  }

  async _simulateDelay() {
    if (this.delayMs > 0) {
      await new Promise((resolve) => setTimeout(resolve, this.delayMs));
    }
  }

  _mockSessionCreate(params) {
    this.calls.sessionCreate.push({ params, timestamp: Date.now() });
    return {
      data: {
        sessionId: params.sessionId || `sess-${crypto.randomUUID().slice(0, 8)}`,
      },
    };
  }

  _mockSessionPrompt(params) {
    this.calls.sessionChat.push({ params, timestamp: Date.now() });
    this.calls.sessionPrompt.push({ params, timestamp: Date.now() });
    return {
      data: {
        sessionId: params.sessionId,
        response: `Mock response to: ${params.message}`,
      },
    };
  }

  _mockSessionAbort(params) {
    this.calls.sessionAbort.push({ params, timestamp: Date.now() });
    return {
      data: {
        sessionId: params.sessionId,
        aborted: true,
      },
    };
  }

  _mockSessionDelete(params) {
    this.calls.sessionDelete.push({ params, timestamp: Date.now() });
    return {
      data: {
        sessionId: params.sessionId,
        deleted: true,
      },
    };
  }

  _mockPermissionReply(options) {
    this.calls.permissionReply.push({ options, timestamp: Date.now() });
    return {
      data: {
        permissionId: options.permissionId,
        decision: options.request.decision,
      },
    };
  }

  _mockHealth() {
    this.calls.health.push({ timestamp: Date.now() });
    return {
      online: true,
      timestamp: Date.now(),
    };
  }

  resetCalls() {
    for (const key of Object.keys(this.calls)) {
      this.calls[key] = [];
    }
  }

  getCallCount(method) {
    return this.calls[method]?.length || 0;
  }

  getLastCall(method) {
    const calls = this.calls[method];
    if (!calls || calls.length === 0) {
      return null;
    }
    return calls[calls.length - 1];
  }
}

export function createMockSDK(options = {}) {
  return new MockOpenCodeSDK(options);
}

export function createMockSDKWithTimeout() {
  return new MockOpenCodeSDK();
}

export function createMockSDKWithDelay(delayMs) {
  return new MockOpenCodeSDK({ delayMs });
}
