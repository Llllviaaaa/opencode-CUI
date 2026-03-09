import { describe, expect, test } from 'bun:test';

import {
  getErrorDetails,
  getErrorDetailsForLog,
  getErrorMessage,
  safeStringify,
} from '../../dist/utils/error.js';
import { safeExecute } from '../../dist/types/index.js';

describe('error utils', () => {
  test('extracts details from Error instances', () => {
    const error = Object.assign(new Error('boom'), { code: 'ECONNREFUSED' });

    expect(getErrorMessage(error)).toBe('boom');
    expect(getErrorDetails(error)).toMatchObject({
      message: 'boom',
      name: 'Error',
      code: 'ECONNREFUSED',
    });
    expect(getErrorDetailsForLog(error)).toMatchObject({
      errorDetail: 'boom',
      errorName: 'Error',
      sourceErrorCode: 'ECONNREFUSED',
    });
  });

  test('extracts details from strings and plain objects', () => {
    expect(getErrorMessage('plain failure')).toBe('plain failure');
    expect(getErrorDetailsForLog('plain failure')).toMatchObject({
      errorDetail: 'plain failure',
      rawType: 'string',
    });

    expect(getErrorDetails({ message: 'boom', code: 'E1' })).toMatchObject({
      message: 'boom',
      code: 'E1',
    });
    expect(getErrorDetails({ error: { message: 'nested boom', code: 'E2' } })).toMatchObject({
      message: 'nested boom',
      code: 'E2',
    });
  });

  test('handles circular values without throwing', () => {
    const circular = {};
    circular.self = circular;

    expect(() => safeStringify(circular)).not.toThrow();
    expect(getErrorMessage(circular)).toContain('[Circular]');
  });

  test('safeExecute uses unified error message mapping by default', async () => {
    const result = await safeExecute(
      Promise.reject({
        error: {
          message: 'nested failure',
          code: 'E_NESTED',
        },
      }),
    );

    expect(result).toEqual({
      success: false,
      error: 'nested failure',
    });
  });
});
