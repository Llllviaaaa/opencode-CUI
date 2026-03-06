export interface ConnectionManager {
  connect(): Promise<void>;
  disconnect(): Promise<void>;
}

export { AkSkAuth, DefaultAkSkAuth } from './AkSkAuth';

export { StateManager, DefaultStateManager } from './StateManager';

export {
  GatewayConnection,
  DefaultGatewayConnection,
  GatewayConnectionOptions,
  GatewayConnectionEvents,
} from './GatewayConnection';
