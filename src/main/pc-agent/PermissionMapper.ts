/**
 * Maps protocol permission responses to the OpenCode SDK values.
 */

export type ProtocolPermissionResponse = 'once' | 'always' | 'reject';

export type SdkPermissionResponse = 'once' | 'always' | 'reject';

const RESPONSE_MAP: Record<ProtocolPermissionResponse, SdkPermissionResponse> = {
  once: 'once',
  always: 'always',
  reject: 'reject',
};

export function mapPermissionResponse(response: string): SdkPermissionResponse {
  const mapped = RESPONSE_MAP[response as ProtocolPermissionResponse];
  if (!mapped) {
    throw new Error(
      `Unknown permission response: "${response}". Expected one of: ${Object.keys(RESPONSE_MAP).join(', ')}`,
    );
  }
  return mapped;
}
