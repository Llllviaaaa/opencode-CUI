import { describe, expect, test } from 'bun:test';
import { mapPermissionResponse } from '../PermissionMapper';

describe('PermissionMapper.mapPermissionResponse', () => {
  describe('valid protocol responses', () => {
    test('once -> once', () => {
      expect(mapPermissionResponse('once')).toBe('once');
    });

    test('always -> always', () => {
      expect(mapPermissionResponse('always')).toBe('always');
    });

    test('reject -> reject', () => {
      expect(mapPermissionResponse('reject')).toBe('reject');
    });
  });

  describe('invalid responses should throw', () => {
    test('empty string throws', () => {
      expect(() => mapPermissionResponse('')).toThrow(/Unknown permission response/);
    });

    test('legacy allow throws', () => {
      expect(() => mapPermissionResponse('allow')).toThrow(/Unknown permission response/);
    });

    test('legacy deny throws', () => {
      expect(() => mapPermissionResponse('deny')).toThrow(/Unknown permission response/);
    });

    test('unknown value throws', () => {
      expect(() => mapPermissionResponse('maybe')).toThrow(/Unknown permission response/);
    });
  });
});
