import test from 'node:test';
import assert from 'node:assert/strict';
import { ApiFailure, apiRequest, apiFailureKey, isHttp, isUuid } from '../src/shared/api-failure.ts';

async function withFetch(implementation, action) {
  const previous = globalThis.fetch;
  globalThis.fetch = implementation;
  try { return await action(); }
  finally { globalThis.fetch = previous; }
}
const valid = (value) => !!value && typeof value === 'object' && typeof value.id === 'string';

test('HTML/empty 404 and unknown server codes are safe typed failures', async () => {
  for (const body of ['<html>internal stack secret</html>', '', '{"code":"EVIL_CODE","message":"secret"}']) {
    await withFetch(async () => new Response(body, { status: 404 }), async () => {
      await assert.rejects(apiRequest('/read', undefined, valid), error =>
        error instanceof ApiFailure && isHttp(error, 404) && error.code === null &&
        !error.message.includes('secret') && !error.message.includes('stack'));
    });
  }
});
test('known machine codes are allowlisted and never include raw messages', async () => {
  await withFetch(async () => new Response(
    '{"code":"RESOURCE_NOT_FOUND","message":"do not expose me"}', { status: 404 }),
  async () => assert.rejects(apiRequest('/read', undefined, valid),
    error => error instanceof ApiFailure && error.code === 'RESOURCE_NOT_FOUND' &&
      error.message === 'HTTP_404'));
});
test('network, invalid JSON, invalid DTO and 204 are distinct', async () => {
  await withFetch(async () => { throw new Error('private connection details'); },
    async () => assert.rejects(apiRequest('/read', undefined, valid),
      error => error instanceof ApiFailure && error.kind === 'NETWORK' &&
        error.message === 'NETWORK'));
  for (const response of [
    new Response('<html>oops</html>', { status: 200 }),
    new Response('{"other":"unexpected"}', { status: 200 }),
  ]) {
    await withFetch(async () => response,
      async () => assert.rejects(apiRequest('/read', undefined, valid),
        error => error instanceof ApiFailure && error.kind === 'INVALID_RESPONSE'));
  }
  await withFetch(async () => new Response(null, { status: 204 }), async () => {
    assert.equal(await apiRequest('/read', undefined, valid, true), null);
  });
});
test('no auto retry for GET or POST; failure leaves explicit caller in control', async () => {
  for (const method of ['GET', 'POST']) {
    let attempts = 0;
    await withFetch(async () => { ++attempts; throw new Error('network'); }, async () => {
      await assert.rejects(apiRequest('/endpoint', { method }, valid));
      assert.equal(attempts, 1);
    });
  }
});
test('AbortError passes through unchanged instead of showing network error', async () => {
  const aborted = new Error('aborted');
  aborted.name = 'AbortError';
  await withFetch(async () => { throw aborted; }, async () => {
    await assert.rejects(apiRequest('/read', undefined, valid), cause => cause === aborted);
  });
});
test('typed key classification never incorporates exception text', () => {
  assert.equal(apiFailureKey(new ApiFailure('HTTP', 409)), 'apiErrors.conflict');
  assert.equal(apiFailureKey(new ApiFailure('HTTP', 413)), 'apiErrors.tooLarge');
  assert.equal(apiFailureKey(new ApiFailure('INVALID_RESPONSE')), 'apiErrors.invalid');
  assert.equal(isHttp(new Error('HTTP_404'), 404), false);
  assert.equal(isUuid('bad-id'), false);
  assert.equal(isUuid('123e4567-e89b-12d3-a456-426614174000'), true);
});
