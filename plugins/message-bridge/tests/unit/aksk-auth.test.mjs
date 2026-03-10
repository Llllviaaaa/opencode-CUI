import { createHmac } from 'crypto';
import { describe, expect, test } from 'bun:test';

import { DefaultAkSkAuth } from '../../dist/connection/AkSkAuth.js';

describe('DefaultAkSkAuth', () => {
  test('generates gateway-compatible auth payload', () => {
    const ak = 'test-ak-001';
    const sk = 'test-sk-secret-001';
    const auth = new DefaultAkSkAuth(ak, sk);

    const payload = auth.generateAuthPayload();
    const { ts, nonce, sign } = payload;

    expect(payload.ak).toBe(ak);
    expect(ts).toBeDefined();
    expect(nonce).toBeDefined();
    expect(sign).toBeDefined();

    // Gateway expects Unix timestamp in seconds.
    expect(ts).toMatch(/^\d{10}$/);

    const expectedSign = createHmac('sha256', sk)
      .update(`${ak}${ts}${nonce}`)
      .digest('base64');

    expect(sign).toBe(expectedSign);
  });

  test('auth payload can be encoded as base64url websocket subprotocol content', () => {
    const auth = new DefaultAkSkAuth('test-ak-001', 'test-sk-secret-001');
    const payload = auth.generateAuthPayload();

    const encoded = Buffer.from(JSON.stringify(payload), 'utf8')
      .toString('base64')
      .replace(/\+/g, '-')
      .replace(/\//g, '_')
      .replace(/=+$/g, '');

    expect(encoded).not.toContain('+');
    expect(encoded).not.toContain('/');
    expect(encoded).not.toContain('=');

    const decoded = JSON.parse(Buffer.from(encoded, 'base64url').toString('utf8'));
    expect(decoded).toEqual(payload);
  });
});
