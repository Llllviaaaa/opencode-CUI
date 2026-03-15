import { describe, test } from 'node:test';
import assert from 'node:assert/strict';

import {
  getErrorDetails,
  getErrorDetailsForLog,
  getErrorMessage,
  safeStringify,
} from '../../src/utils/error.ts';
import { safeExecute } from '../../src/types/index.ts';

describe('error utils', () => {
  test('extracts details from Error instances', () => {
    const error = Object.assign(new Error('boom'), { code: 'ECONNREFUSED' });

    assert.strictEqual(getErrorMessage(error), 'boom');
    const details = getErrorDetails(error);
    assert.strictEqual(details.message, 'boom');
    assert.strictEqual(details.name, 'Error');
    assert.strictEqual(details.code, 'ECONNREFUSED');

    const logDetails = getErrorDetailsForLog(error);
    assert.strictEqual(logDetails.errorDetail, 'boom');
    assert.strictEqual(logDetails.errorName, 'Error');
    assert.strictEqual(logDetails.sourceErrorCode, 'ECONNREFUSED');
  });

  test('extracts details from strings and plain objects', () => {
    assert.strictEqual(getErrorMessage('plain failure'), 'plain failure');
    const logDetails = getErrorDetailsForLog('plain failure');
    assert.strictEqual(logDetails.errorDetail, 'plain failure');
    assert.strictEqual(logDetails.rawType, 'string');

    const d1 = getErrorDetails({ message: 'boom', code: 'E1' });
    assert.strictEqual(d1.message, 'boom');
    assert.strictEqual(d1.code, 'E1');

    const d2 = getErrorDetails({ error: { message: 'nested boom', code: 'E2' } });
    assert.strictEqual(d2.message, 'nested boom');
    assert.strictEqual(d2.code, 'E2');
  });

  test('handles circular values without throwing', () => {
    const circular = {};
    circular.self = circular;

    assert.doesNotThrow(() => safeStringify(circular));
    assert.ok(getErrorMessage(circular).includes('[Circular]'));
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

    assert.deepStrictEqual(result, {
      success: false,
      error: 'nested failure',
    });
  });
});
