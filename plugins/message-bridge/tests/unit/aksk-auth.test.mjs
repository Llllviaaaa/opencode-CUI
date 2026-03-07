import { createHmac } from 'crypto';
import { describe, expect, test } from 'bun:test';

import { DefaultAkSkAuth } from '../../dist/connection/AkSkAuth.js';

describe('DefaultAkSkAuth', () => {
  test('generates gateway-compatible query params', () => {
    const ak = 'test-ak-001';
    const sk = 'test-sk-secret-001';
    const auth = new DefaultAkSkAuth(ak, sk);

    const query = auth.generateQueryParams();
    const ts = query.get('ts');
    const nonce = query.get('nonce');
    const sign = query.get('sign');

    expect(query.get('ak')).toBe(ak);
    expect(ts).toBeDefined();
    expect(nonce).toBeDefined();
    expect(sign).toBeDefined();

    // Gateway expects Unix timestamp in seconds.
    expect(ts).toMatch(/^\d{10}$/);

    const expectedSign = createHmac('sha256', sk)
      .update(`${ak}\n${ts}\n${nonce}`)
      .digest('base64');

    expect(sign).toBe(expectedSign);
  });
});
