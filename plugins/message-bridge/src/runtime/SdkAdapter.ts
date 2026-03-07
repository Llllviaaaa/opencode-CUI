import type { OpencodeClient } from '../types';

export function createSdkAdapter(client: unknown): unknown {
  if (!client || typeof client !== 'object') {
    return client;
  }

  const c = client as Partial<OpencodeClient>;
  if (!c.session || !c.postSessionIdPermissionsPermissionId) {
    return client;
  }

  return {
    session: {
      create: async (options: { sessionId?: string; metadata?: Record<string, unknown> } & Record<string, unknown>) => {
        return c.session!.create(options);
      },
      abort: async (options: { sessionId: string } & Record<string, unknown>) => {
        return c.session!.abort(options);
      },
      prompt: async (options: { sessionId: string; message: string; meta?: Record<string, string> } & Record<string, unknown>) => {
        return c.session!.prompt(options);
      },
    },
    postSessionIdPermissionsPermissionId: async (options: {
      sessionId: string;
      permissionId: string;
      request: { decision: 'allow' | 'always' | 'deny' | 'once' | 'reject' };
    } & Record<string, unknown>) => {
      return c.postSessionIdPermissionsPermissionId!(options);
    },
  } as OpencodeClient;
}
