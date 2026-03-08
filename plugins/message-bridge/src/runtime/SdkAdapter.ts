import type { OpencodeClient } from '../types';

export function createSdkAdapter(client: unknown): unknown {
  // Keep action execution on the minimal session/permission surface.
  // Logging uses the original input client (client.app.log) and does not go through this adapter.
  if (!client || typeof client !== 'object') {
    return client;
  }

  const c = client as Partial<OpencodeClient>;
  if (!c.session || !c.postSessionIdPermissionsPermissionId) {
    return client;
  }

  const rawClient =
    c._client && typeof c._client === 'object'
      ? {
          get: typeof c._client.get === 'function' ? c._client.get.bind(c._client) : undefined,
          post: typeof c._client.post === 'function' ? c._client.post.bind(c._client) : undefined,
        }
      : undefined;

  return {
    session: {
      create: async (options: { body?: Record<string, unknown> }) => {
        return c.session!.create(options);
      },
      abort: async (options: { path: { id: string } }) => {
        return c.session!.abort(options);
      },
      delete: async (options: { path: { id: string } }) => {
        if (!c.session!.delete) {
          throw new Error('SDK session.delete is not available');
        }
        return c.session!.delete(options);
      },
      prompt: async (options: {
        path: { id: string };
        body: { parts: Array<{ type: 'text'; text: string }> };
      }) => {
        return c.session!.prompt(options);
      },
    },
    postSessionIdPermissionsPermissionId: async (options: {
      path: { id: string; permissionID: string };
      body: { response: 'once' | 'always' | 'reject' };
      }) => {
      return c.postSessionIdPermissionsPermissionId!(options);
    },
    _client: rawClient,
    app: c.app,
  } as OpencodeClient;
}
