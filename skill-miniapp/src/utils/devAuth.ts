const USER_ID_COOKIE = 'userId';
const DEFAULT_DEV_USER_ID = '1';
const LOCAL_DEV_HOSTS = new Set(['localhost', '127.0.0.1']);

type EnvRecord = Record<string, string | boolean | undefined>;

function getEnv(): EnvRecord | undefined {
  if (typeof import.meta === 'undefined') {
    return undefined;
  }

  return (import.meta as unknown as { env?: EnvRecord }).env;
}

function isLocalDevRuntime(): boolean {
  if (typeof window === 'undefined') {
    return false;
  }

  const env = getEnv();
  const isDev = env?.DEV === true || env?.DEV === 'true';
  return isDev && LOCAL_DEV_HOSTS.has(window.location.hostname);
}

function readCookie(name: string): string | null {
  if (typeof document === 'undefined' || !document.cookie) {
    return null;
  }

  const prefix = `${name}=`;
  const entry = document.cookie
    .split(';')
    .map((cookie) => cookie.trim())
    .find((cookie) => cookie.startsWith(prefix));

  if (!entry) {
    return null;
  }

  return decodeURIComponent(entry.slice(prefix.length));
}

function resolveLocalDevUserId(): string | null {
  if (!isLocalDevRuntime()) {
    return null;
  }

  const queryUserId = typeof window !== 'undefined'
    ? new URLSearchParams(window.location.search).get('userId')
    : null;

  if (queryUserId && queryUserId.trim()) {
    return queryUserId.trim();
  }

  const envUserId = getEnv()?.VITE_SKILL_USER_ID;
  if (typeof envUserId === 'string' && envUserId.trim()) {
    return envUserId.trim();
  }

  return DEFAULT_DEV_USER_ID;
}

export function ensureDevUserIdCookie(): string | null {
  const existing = readCookie(USER_ID_COOKIE);
  const userId = resolveLocalDevUserId();

  if (existing && (!userId || existing === userId)) {
    return existing;
  }

  if (!userId || typeof document === 'undefined') {
    return null;
  }

  document.cookie = `${USER_ID_COOKIE}=${encodeURIComponent(userId)}; path=/; SameSite=Lax`;
  return userId;
}
