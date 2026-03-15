import type { HostClientLike, OpencodeHealthResult, OpencodeClient } from '../types/index.js';
import { getErrorMessage } from '../utils/error.js';
import type { SdkClientCapability } from './SdkAdapter.js';

export type StartupCapability = SdkClientCapability | 'global.health';

export type BridgeStartupError =
  | {
      code: 'SDK_CLIENT_CAPABILITIES_MISSING';
      message: 'OpenCode client is missing required action capabilities';
      details: { missingCapabilities: StartupCapability[] };
    }
  | {
      code: 'GLOBAL_HEALTH_UNAVAILABLE';
      message: 'OpenCode client.global.health is not available';
      details: { missingCapability: 'global.health' };
    }
  | {
      code: 'GLOBAL_HEALTH_FAILED';
      message: 'OpenCode global.health check failed during startup';
      details: { cause: string };
    }
  | {
      code: 'GLOBAL_HEALTH_VERSION_MISSING';
      message: 'OpenCode global.health returned without version';
      details: { responseShape?: string };
    };

export interface StartupValidationResult {
  sdkClient: OpencodeClient;
  health: OpencodeHealthResult & { version: string };
}

function describeResponseShape(response: unknown): string | undefined {
  if (response === null) {
    return 'null';
  }
  if (Array.isArray(response)) {
    return 'array';
  }
  if (typeof response !== 'object') {
    return typeof response;
  }

  const keys = Object.keys(response as Record<string, unknown>).sort();
  return keys.length > 0 ? `object:${keys.join(',')}` : 'object:empty';
}

export async function validateBridgeStartup(
  rawClient: HostClientLike,
  sdkClient: OpencodeClient | null,
  missingCapabilities: SdkClientCapability[],
): Promise<StartupValidationResult> {
  if (!sdkClient) {
    throw {
      code: 'SDK_CLIENT_CAPABILITIES_MISSING',
      message: 'OpenCode client is missing required action capabilities',
      details: { missingCapabilities },
    } satisfies BridgeStartupError;
  }

  if (typeof rawClient.global?.health !== 'function') {
    throw {
      code: 'GLOBAL_HEALTH_UNAVAILABLE',
      message: 'OpenCode client.global.health is not available',
      details: { missingCapability: 'global.health' },
    } satisfies BridgeStartupError;
  }

  let health: OpencodeHealthResult;
  try {
    health = await rawClient.global.health();
  } catch (error) {
    throw {
      code: 'GLOBAL_HEALTH_FAILED',
      message: 'OpenCode global.health check failed during startup',
      details: { cause: getErrorMessage(error) },
    } satisfies BridgeStartupError;
  }

  const version =
    health && typeof health === 'object' && typeof health.version === 'string'
      ? health.version.trim()
      : '';
  if (!version) {
    const responseShape = describeResponseShape(health);
    throw {
      code: 'GLOBAL_HEALTH_VERSION_MISSING',
      message: 'OpenCode global.health returned without version',
      details: responseShape ? { responseShape } : {},
    } satisfies BridgeStartupError;
  }

  return {
    sdkClient,
    health: {
      ...health,
      version,
    },
  };
}

export function isBridgeStartupError(error: unknown): error is BridgeStartupError {
  if (!error || typeof error !== 'object') {
    return false;
  }

  const candidate = error as { code?: unknown; message?: unknown; details?: unknown };
  return typeof candidate.code === 'string' && typeof candidate.message === 'string' && !!candidate.details;
}
