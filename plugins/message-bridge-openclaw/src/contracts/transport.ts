export interface RegisterMessage {
  type: "register";
  deviceName: string;
  macAddress: string;
  os: string;
  toolType: string;
  toolVersion: string;
}

export interface HeartbeatMessage {
  type: "heartbeat";
  timestamp: string;
}

export interface ToolEventMessage {
  type: "tool_event";
  toolSessionId: string;
  event: Record<string, unknown>;
}

export interface ToolDoneMessage {
  type: "tool_done";
  toolSessionId: string;
  welinkSessionId?: string;
}

export interface ToolErrorMessage {
  type: "tool_error";
  welinkSessionId?: string;
  toolSessionId?: string;
  error: string;
}

export interface SessionCreatedMessage {
  type: "session_created";
  welinkSessionId?: string;
  toolSessionId?: string;
  session?: {
    sessionId: string;
  };
}

export interface StatusResponseMessage {
  type: "status_response";
  opencodeOnline: boolean;
}

export type UpstreamMessage =
  | RegisterMessage
  | HeartbeatMessage
  | ToolEventMessage
  | ToolDoneMessage
  | ToolErrorMessage
  | SessionCreatedMessage
  | StatusResponseMessage;
