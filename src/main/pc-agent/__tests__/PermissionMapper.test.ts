/**
 * PermissionMapper Unit Tests
 *
 * Tests the mapPermissionResponse() function for protocol-to-SDK
 * permission response translation.
 */

import { describe, test, expect } from 'bun:test';
import { mapPermissionResponse } from '../PermissionMapper';

describe('PermissionMapper.mapPermissionResponse', () => {
    // -----------------------------------------------------------------------
    // Valid mappings
    // -----------------------------------------------------------------------

    describe('valid protocol responses', () => {
        test('allow -> once', () => {
            expect(mapPermissionResponse('allow')).toBe('once');
        });

        test('always -> always', () => {
            expect(mapPermissionResponse('always')).toBe('always');
        });

        test('deny -> reject', () => {
            expect(mapPermissionResponse('deny')).toBe('reject');
        });
    });

    // -----------------------------------------------------------------------
    // Invalid inputs
    // -----------------------------------------------------------------------

    describe('invalid responses should throw', () => {
        test('empty string throws', () => {
            expect(() => mapPermissionResponse('')).toThrow(/Unknown permission response/);
        });

        test('unknown value throws', () => {
            expect(() => mapPermissionResponse('maybe')).toThrow(/Unknown permission response/);
        });

        test('case sensitivity: "Allow" throws', () => {
            expect(() => mapPermissionResponse('Allow')).toThrow(/Unknown permission response/);
        });

        test('case sensitivity: "DENY" throws', () => {
            expect(() => mapPermissionResponse('DENY')).toThrow(/Unknown permission response/);
        });
    });
});
