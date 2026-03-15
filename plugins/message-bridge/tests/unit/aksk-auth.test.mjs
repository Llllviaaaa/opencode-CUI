import { createHmac } from 'crypto';
import { describe, test } from 'node:test';
import assert from 'node:assert/strict';

import { DefaultAkSkAuth } from '../../src/connection/AkSkAuth.ts';

describe('DefaultAkSkAuth', () => {
  test('generates gateway-compatible auth payload', () => {
    const ak = 'test-ak-001';
    const sk = 'test-sk-secret-001';
    const auth = new DefaultAkSkAuth(ak, sk);

    const payload = auth.generateAuthPayload();
    const { ts, nonce, sign } = payload;

    assert.strictEqual(payload.ak, ak);
    assert.notStrictEqual(ts, undefined);
    assert.notStrictEqual(nonce, undefined);
    assert.notStrictEqual(sign, undefined);

    // Gateway expects Unix timestamp in seconds.
    assert.match(ts, /^\d{10}$/);

    const expectedSign = createHmac('sha256', sk)
      .update(`${ak}${ts}${nonce}`)
      .digest('base64');

    assert.strictEqual(sign, expectedSign);
  });

  test('auth payload can be encoded as base64url websocket subprotocol content', () => {
    const auth = new DefaultAkSkAuth('test-ak-001', 'test-sk-secret-001');
    const payload = auth.generateAuthPayload();

    const encoded = Buffer.from(JSON.stringify(payload), 'utf8')
      .toString('base64')
      .replace(/\+/g, '-')
      .replace(/\//g, '_')
      .replace(/=+$/g, '');

    assert.ok(!encoded.includes('+'));
    assert.ok(!encoded.includes('/'));
    assert.ok(!encoded.includes('='));

    const decoded = JSON.parse(Buffer.from(encoded, 'base64url').toString('utf8'));
    assert.deepStrictEqual(decoded, payload);
  });
});
