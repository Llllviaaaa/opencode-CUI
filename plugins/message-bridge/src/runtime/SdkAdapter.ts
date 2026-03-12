import type { HostClientLike, OpencodeClient, OpencodeHealthResult } from '../types';

export const REQUIRED_SDK_CAPABILITIES = [
  'session.create',
  'session.prompt',
  'session.abort',
  'session.delete',
  'postSessionIdPermissionsPermissionId',
  '_client.get',
  '_client.post',
] as const;

export type SdkClientCapability = typeof REQUIRED_SDK_CAPABILITIES[number];

function isRecord(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === 'object';
}

function asFunction<T extends (...args: never[]) => unknown>(value: unknown, bindTarget?: unknown): T | undefined {
  if (typeof value !== 'function') {
    return undefined;
  }

  return (bindTarget ? value.bind(bindTarget) : value) as T;
}

function normalizeHealthResponse(response: unknown): OpencodeHealthResult {
  if (isRecord(response) && 'error' in response && response.error !== undefined) {
    const error = response.error;
    const message =
      isRecord(error) && typeof error.message === 'string'
        ? error.message
        : typeof error === 'string'
          ? error
          : 'OpenCode health request failed';
    throw new Error(message);
  }

  const payload =
    isRecord(response) && 'data' in response
      ? response.data
      : response;

  if (!isRecord(payload) || typeof payload.healthy !== 'boolean') {
    throw new Error('Invalid global health response');
  }

  return payload as unknown as OpencodeHealthResult;
}

type AdaptedGlobalHealth = NonNullable<HostClientLike['global']>['health'];

function adaptGlobalHealth(root: Record<string, unknown> | undefined): AdaptedGlobalHealth {
  const global = isRecord(root?.global) ? root.global : undefined;
  const rawClient = isRecord(root?._client) ? root._client : undefined;
  const globalHealth = asFunction<(options?: Record<string, unknown>) => Promise<OpencodeHealthResult> | OpencodeHealthResult>(
    global?.health,
    global,
  );

  if (globalHealth) {
    return globalHealth;
  }

  const rawGet = asFunction<(options: Record<string, unknown>) => Promise<unknown>>(rawClient?.get, rawClient);
  if (!rawGet) {
    return undefined;
  }

  return async () => normalizeHealthResponse(await rawGet({ url: '/global/health' }));
}

export function getMissingSdkCapabilities(client: unknown): SdkClientCapability[] {
  const root = isRecord(client) ? client : undefined;
  const session = isRecord(root?.session) ? root.session : undefined;
  const rawClient = isRecord(root?._client) ? root._client : undefined;

  return REQUIRED_SDK_CAPABILITIES.filter((capability) => {
    switch (capability) {
      case 'session.create':
        return typeof session?.create !== 'function';
      case 'session.prompt':
        return typeof session?.prompt !== 'function';
      case 'session.abort':
        return typeof session?.abort !== 'function';
      case 'session.delete':
        return typeof session?.delete !== 'function';
      case 'postSessionIdPermissionsPermissionId':
        return typeof root?.postSessionIdPermissionsPermissionId !== 'function';
      case '_client.get':
        return typeof rawClient?.get !== 'function';
      case '_client.post':
        return typeof rawClient?.post !== 'function';
      default:
        return true;
    }
  });
}

export function toHostClientLike(client: unknown): HostClientLike {
  const root = isRecord(client) ? client : undefined;
  const app = isRecord(root?.app) ? root.app : undefined;

  return {
    global: {
      health: adaptGlobalHealth(root),
    },
    app: {
      log: asFunction(app?.log, app),
    },
  };
}

export function createSdkAdapter(client: unknown): OpencodeClient | null {
  if (getMissingSdkCapabilities(client).length > 0) {
    return null;
  }

  const root = client as {
    session: {
      create: OpencodeClient['session']['create'];
      prompt: OpencodeClient['session']['prompt'];
      abort: OpencodeClient['session']['abort'];
      delete: OpencodeClient['session']['delete'];
    };
    postSessionIdPermissionsPermissionId: OpencodeClient['postSessionIdPermissionsPermissionId'];
    _client: {
      get: OpencodeClient['_client']['get'];
      post: OpencodeClient['_client']['post'];
    };
  };

  return {
    session: {
      create: root.session.create.bind(root.session),
      prompt: root.session.prompt.bind(root.session),
      abort: root.session.abort.bind(root.session),
      delete: root.session.delete.bind(root.session),
    },
    postSessionIdPermissionsPermissionId: root.postSessionIdPermissionsPermissionId.bind(root),
    _client: {
      get: root._client.get.bind(root._client),
      post: root._client.post.bind(root._client),
    },
  };
}
