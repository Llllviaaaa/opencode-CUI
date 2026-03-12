function isRecord(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === 'object';
}

function listKeys(value: unknown): string[] {
  return isRecord(value) ? Object.keys(value).sort() : [];
}

export function buildClientShapeSummary(client: unknown): Record<string, unknown> {
  const root = isRecord(client) ? client : undefined;
  const global = isRecord(root?.global) ? root.global : undefined;
  const app = isRecord(root?.app) ? root.app : undefined;
  const session = isRecord(root?.session) ? root.session : undefined;
  const rawClient = isRecord(root?._client) ? root._client : undefined;

  return {
    clientTopLevelKeys: listKeys(root),
    globalKeys: listKeys(global),
    appKeys: listKeys(app),
    sessionKeys: listKeys(session),
    rawClientKeys: listKeys(rawClient),
    hasGlobalHealth: typeof global?.health === 'function',
    hasAppHealth: typeof app?.health === 'function',
    hasAppLog: typeof app?.log === 'function',
    hasSessionCreate: typeof session?.create === 'function',
    hasSessionPrompt: typeof session?.prompt === 'function',
    hasSessionAbort: typeof session?.abort === 'function',
    hasSessionDelete: typeof session?.delete === 'function',
    hasPermissionReply: typeof root?.postSessionIdPermissionsPermissionId === 'function',
    hasRawClientGet: typeof rawClient?.get === 'function',
    hasRawClientPost: typeof rawClient?.post === 'function',
  };
}
