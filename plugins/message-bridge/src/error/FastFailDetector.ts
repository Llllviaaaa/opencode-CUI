import { CONNECTION_STATES, type ConnectionState, type ErrorCode } from '../types';

export class FastFailDetector {
  static readonly connectionCheckTimeoutMs = 100;

  checkState(connectionState: ConnectionState): ErrorCode | null {
    switch (connectionState) {
      case CONNECTION_STATES[0]:
      case CONNECTION_STATES[1]:
        return 'GATEWAY_UNREACHABLE';
      case CONNECTION_STATES[2]:
        return 'AGENT_NOT_READY';
      case CONNECTION_STATES[3]:
        return null;
      default:
        return 'AGENT_NOT_READY';
    }
  }

  isGatewayReachable(state: ConnectionState): boolean {
    return state === CONNECTION_STATES[3] || state === CONNECTION_STATES[2];
  }
}
