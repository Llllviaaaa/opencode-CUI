// Placeholder for event-specific type definitions if needed in the future
// Currently empty as event types are imported from respective modules

export interface StateManager {
  isReady(): boolean;
  getAgentId(): string | null;
}