import { randomUUID } from 'crypto';
import { AGENT_ID_PREFIX, ConnectionState } from '../types';

export interface StateManager {
  isReady(): boolean;
  getAgentId(): string | null;
  getState(): ConnectionState;
  setState(state: ConnectionState): void;
  generateAndBindAgentId(): string;
  resetForReconnect(): string;
}

export class DefaultStateManager implements StateManager {
  private state: ConnectionState = 'DISCONNECTED';
  private agentId: string | null = null;

  isReady(): boolean {
    return this.state === 'READY';
  }

  getAgentId(): string | null {
    return this.agentId;
  }

  getState(): ConnectionState {
    return this.state;
  }

  setState(state: ConnectionState): void {
    this.state = state;
  }

  generateAndBindAgentId(): string {
    const id = `${AGENT_ID_PREFIX}${randomUUID()}`;
    this.agentId = id;
    return id;
  }

  resetForReconnect(): string {
    return this.generateAndBindAgentId();
  }
}
