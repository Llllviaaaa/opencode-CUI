import { existsSync, readFileSync } from "node:fs";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";
import type { BridgeLogger } from "../types.js";

export const MESSAGE_BRIDGE_TOOL_TYPE = "openclaw";

export interface RegisterMetadata {
  deviceName: string;
  toolType: string;
  toolVersion: string;
  macAddress: string;
}

export interface RegisterMetadataDeps {
  hostname?: () => string;
  networkInterfaces?: typeof os.networkInterfaces;
  toolVersion?: string;
}

const EMPTY_MAC_ADDRESS = "";
const UNKNOWN_TOOL_VERSION = "unknown";
const ZERO_MAC_ADDRESS = "00:00:00:00:00:00";
const MAC_ADDRESS_PATTERN = /^([0-9a-f]{2}[:-]){5}[0-9a-f]{2}$/i;

function normalizeMacAddress(macAddress: string): string {
  return macAddress.trim().replace(/-/g, ":").toLowerCase();
}

function isUsableMacAddress(macAddress: string | undefined): macAddress is string {
  if (!macAddress) {
    return false;
  }

  const normalized = normalizeMacAddress(macAddress);
  return MAC_ADDRESS_PATTERN.test(normalized) && normalized !== ZERO_MAC_ADDRESS;
}

function resolveMacAddress(logger: BridgeLogger, networkInterfaces: typeof os.networkInterfaces): string {
  const interfaces = networkInterfaces();
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

  logger.warn("runtime.mac_address.unavailable", {
    platform: os.platform(),
    interfaceCount,
  });
  return EMPTY_MAC_ADDRESS;
}

function resolvePackageVersion(logger: BridgeLogger): string {
  const moduleFile = fileURLToPath(import.meta.url);
  let currentDir = path.dirname(moduleFile);

  for (let depth = 0; depth < 6; depth += 1) {
    const packageJsonPath = path.join(currentDir, "package.json");
    if (existsSync(packageJsonPath)) {
      try {
        const packageJson = JSON.parse(readFileSync(packageJsonPath, "utf8")) as { version?: unknown };
        if (typeof packageJson.version === "string" && packageJson.version.trim()) {
          return packageJson.version.trim();
        }
      } catch (error) {
        logger.warn("runtime.tool_version.read_failed", {
          packageJsonPath,
          error: error instanceof Error ? error.message : String(error),
        });
      }
    }

    const parentDir = path.dirname(currentDir);
    if (parentDir === currentDir) {
      break;
    }
    currentDir = parentDir;
  }

  logger.warn("runtime.tool_version.unavailable");
  return UNKNOWN_TOOL_VERSION;
}

export function resolveRegisterMetadata(
  logger: BridgeLogger,
  deps: RegisterMetadataDeps = {},
): RegisterMetadata {
  return {
    deviceName: deps.hostname?.() ?? os.hostname(),
    toolType: MESSAGE_BRIDGE_TOOL_TYPE,
    toolVersion: deps.toolVersion?.trim() || resolvePackageVersion(logger),
    macAddress: resolveMacAddress(logger, deps.networkInterfaces ?? os.networkInterfaces),
  };
}
