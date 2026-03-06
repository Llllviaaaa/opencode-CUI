import { ConnectionState, ErrorCode } from '../types';

export class FastFailDetector {
  static readonly connectionCheckTimeoutMs = 100;

  checkState(connectionState: ConnectionState): ErrorCode | null {
    switch (connectionState) {
      case 'DISCONNECTED':
      case 'CONNECTING':
        return 'GATEWAY_UNREACHABLE';
      case 'CONNECTED':
        return 'AGENT_NOT_READY';
      case 'READY':
        return null;
      default:
        return 'AGENT_NOT_READY';
    }
  }

  isGatewayReachable(state: ConnectionState): boolean {
    return state === 'READY' || state === 'CONNECTED';
  }
}