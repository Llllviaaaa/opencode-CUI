import os from 'os';
import type { OpencodeClient } from '../types';
import type { BridgeLogger } from './AppLogger';

export interface RegisterMetadata {
  deviceName: string;
  toolVersion: string;
  macAddress: string;
}

const EMPTY_MAC_ADDRESS = '';
const ZERO_MAC_ADDRESS = '00:00:00:00:00:00';
const MAC_ADDRESS_PATTERN = /^([0-9a-f]{2}[:-]){5}[0-9a-f]{2}$/i;

function normalizeMacAddress(macAddress: string): string {
  return macAddress.trim().replace(/-/g, ':').toLowerCase();
}

function isUsableMacAddress(macAddress: string | undefined): macAddress is string {
  if (!macAddress) {
    return false;
  }

  const normalized = normalizeMacAddress(macAddress);
  return MAC_ADDRESS_PATTERN.test(normalized) && normalized !== ZERO_MAC_ADDRESS;
}

async function resolveToolVersion(client: unknown): Promise<string> {
  const globalHealth = (client as Partial<OpencodeClient> | null | undefined)?.global?.health;
  if (!globalHealth) {
    throw new Error('opencode_global_health_unavailable');
  }

  const health = await globalHealth();
  const version =
    health && typeof health === 'object' && typeof (health as { version?: unknown }).version === 'string'
      ? (health as { version: string }).version.trim()
      : '';
  if (!version) {
    throw new Error('opencode_version_unavailable');
  }

  return version;
}

function resolveMacAddress(logger: BridgeLogger): string {
  const interfaces = os.networkInterfaces();
  let interfaceCount = 0;

  for (const entries of Object.values(interfaces)) {
    if (!entries) {
      continue;
    }

    interfaceCount += entries.length;
    for (const entry of entries) {
      if (entry.internal || !isUsableMacAddress(entry.mac)) {
        continue;
      }
      return normalizeMacAddress(entry.mac);
    }
  }

  logger.warn('runtime.mac_address.unavailable', {
    platform: os.platform(),
    interfaceCount,
  });
  return EMPTY_MAC_ADDRESS;
}

export async function resolveRegisterMetadata(client: unknown, logger: BridgeLogger): Promise<RegisterMetadata> {
  return {
    deviceName: os.hostname(),
    toolVersion: await resolveToolVersion(client),
    macAddress: resolveMacAddress(logger),
  };
}
