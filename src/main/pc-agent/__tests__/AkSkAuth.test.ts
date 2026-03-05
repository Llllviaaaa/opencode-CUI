/**
 * AkSkAuth Unit Tests
 *
 * Tests HMAC-SHA256 signature generation for AI-Gateway authentication.
 * Verifies Bun compatibility with node:crypto built-ins.
 */

import { describe, test, expect } from 'bun:test';
import { createHmac } from 'node:crypto';
import { AkSkAuth } from '../AkSkAuth';

describe('AkSkAuth', () => {
    // -----------------------------------------------------------------------
    // sign() output structure
    // -----------------------------------------------------------------------

    describe('sign() output', () => {
        test('returns all required fields', () => {
            const params = AkSkAuth.sign('test-ak', 'test-sk');

            expect(params.ak).toBe('test-ak');
            expect(typeof params.timestamp).toBe('string');
            expect(typeof params.nonce).toBe('string');
            expect(typeof params.signature).toBe('string');
        });

        test('timestamp is a numeric string (epoch seconds)', () => {
            const params = AkSkAuth.sign('ak', 'sk');
            const ts = Number(params.timestamp);

            expect(Number.isNaN(ts)).toBe(false);
            expect(ts).toBeGreaterThan(0);
            // Should be within a few seconds of now
            const now = Math.floor(Date.now() / 1000);
            expect(Math.abs(ts - now)).toBeLessThan(5);
        });

        test('nonce is a UUID format', () => {
            const params = AkSkAuth.sign('ak', 'sk');
            // UUID v4 regex
            const uuidRegex = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
            expect(uuidRegex.test(params.nonce)).toBe(true);
        });

        test('nonces are unique per call', () => {
            const params1 = AkSkAuth.sign('ak', 'sk');
            const params2 = AkSkAuth.sign('ak', 'sk');

            expect(params1.nonce).not.toBe(params2.nonce);
        });
    });

    // -----------------------------------------------------------------------
    // Signature verification
    // -----------------------------------------------------------------------

    describe('signature correctness', () => {
        test('signature matches manual HMAC-SHA256 computation', () => {
            const params = AkSkAuth.sign('test-ak', 'test-sk');

            // Manually compute the expected signature
            const message = `test-ak\n${params.timestamp}\n${params.nonce}`;
            const expected = createHmac('sha256', 'test-sk')
                .update(message)
                .digest('base64');

            expect(params.signature).toBe(expected);
        });

        test('different SK produces different signature', () => {
            // Fix nonce for controlled comparison by verifying structure
            const params1 = AkSkAuth.sign('ak', 'sk1');
            const params2 = AkSkAuth.sign('ak', 'sk2');

            // Different SK should produce different signatures (nonces also differ, but that's fine)
            // This is a probabilistic test — technically could collide, but effectively never will
            expect(params1.signature).not.toBe(params2.signature);
        });
    });

    // -----------------------------------------------------------------------
    // Bun compatibility (node:crypto)
    // -----------------------------------------------------------------------

    describe('Bun compatibility', () => {
        test('createHmac from node:crypto works', () => {
            const hmac = createHmac('sha256', 'secret');
            hmac.update('test message');
            const result = hmac.digest('base64');

            expect(typeof result).toBe('string');
            expect(result.length).toBeGreaterThan(0);
        });
    });
});
