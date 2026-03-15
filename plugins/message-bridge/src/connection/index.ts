export interface ConnectionManager {
  connect(): Promise<void>;
  disconnect(): Promise<void>;
}

export { AkSkAuth, DefaultAkSkAuth } from './AkSkAuth.js';

export { StateManager, DefaultStateManager } from './StateManager.js';

export {
  GatewayConnection,
  DefaultGatewayConnection,
  GatewayConnectionOptions,
  GatewayConnectionEvents,
} from './GatewayConnection.js';
