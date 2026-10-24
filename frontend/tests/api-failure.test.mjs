import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import ts from 'typescript';
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

test('real onboarding service: 204 means no background, 200 null/malformed objects are INVALID_RESPONSE', async () => {
  // Load the actual TypeScript service, rewriting only its extensionless transport import.
  const source = await readFile(new URL('../src/entities/onboarding/onboarding.service.ts', import.meta.url), 'utf8');
  const compiled = ts.transpileModule(source, {
    compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ESNext },
  }).outputText.replace(
    "'../../shared/api-failure'",
    JSON.stringify(new URL('../src/shared/api-failure.ts', import.meta.url).href),
  );
  const { onboardingService } = await import('data:text/javascript,' + encodeURIComponent(compiled));
  const id = '123e4567-e89b-12d3-a456-426614174000';
  const instrument = 'ACOUSTIC_GUITAR';
  const background = {
    experienceLevel: 'BEGINNER', yearsPlaying: 1, skills: [], knownPieces: [],
    goals: ['FINGERSTYLE'], practiceMinutes: 40, sessionsPerWeek: 3, notes: '',
  };
  let count = 0;
  await withFetch(async () => { ++count; return new Response(null, { status: 204 }); }, async () => {
    assert.equal(await onboardingService.get(id, instrument), null);
    assert.equal(await onboardingService.save(id, instrument, background), null);
  });
  assert.equal(count, 2, 'GET and PUT 204 each make only one request');
  for (const body of ['null', '{}', '[]', '""']) {
    let attempts = 0;
    await withFetch(async () => { ++attempts; return new Response(body, { status: 200 }); }, async () => {
      await assert.rejects(onboardingService.get(id, instrument),
        error => error instanceof ApiFailure && error.kind === 'INVALID_RESPONSE');
      await assert.rejects(onboardingService.save(id, instrument, background),
        error => error instanceof ApiFailure && error.kind === 'INVALID_RESPONSE');
    });
    assert.equal(attempts, 2, 'no implicit retry for malformed JSON ' + body);
  }
  await withFetch(async () => new Response(JSON.stringify(background), { status: 200 }), async () => {
    assert.deepEqual(await onboardingService.get(id, instrument), background);
  });
});

test('real diagnostic currentTask: 204 is absent, 200 null is invalid, valid 200 is accepted', async () => {
  const source = await readFile(new URL('../src/entities/diagnostic/diagnostic.service.ts', import.meta.url), 'utf8');
  const compiled = ts.transpileModule(source, {
    compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ESNext },
  }).outputText.replace(
    "'../../shared/api-failure'",
    JSON.stringify(new URL('../src/shared/api-failure.ts', import.meta.url).href),
  );
  const { diagnosticService } = await import('data:text/javascript,' + encodeURIComponent(compiled));
  const learnerId = '123e4567-e89b-12d3-a456-426614174000';
  const sessionId = '123e4567-e89b-12d3-a456-426614174001';
  const task = {
    id: '123e4567-e89b-12d3-a456-426614174002',
    sessionId,
    assignedAt: '2026-10-24T09:00:00Z',
    exercise: {
      code: 'BLUES-01', version: '1', title: 'Blues', styleCode: 'BLUES',
      difficulty: 'BEGINNER', meter: '4/4', author: 'Test',
      license: 'CC0', reviewStatus: 'APPROVED', description: 'Study',
      scoreUrl: '/score', eventsUrl: '/events', bpm: 80, measures: 4,
      skillCodes: [], previewAudioUrl: null, previewPdfUrl: null,
    },
  };
  let count = 0;
  await withFetch(async (url, options) => {
    ++count;
    assert.equal(options?.method, undefined, 'currentTask must only GET');
    assert.ok(String(url).endsWith('/tasks'));
    return new Response(null, { status: 204 });
  }, async () => assert.equal(await diagnosticService.currentTask(learnerId, sessionId), null));
  assert.equal(count, 1);
  for (const body of ['null', '{}', '[]', '""']) {
    let attempts = 0;
    await withFetch(async () => { ++attempts; return new Response(body, { status: 200 }); }, async () => {
      await assert.rejects(diagnosticService.currentTask(learnerId, sessionId),
        error => error instanceof ApiFailure && error.kind === 'INVALID_RESPONSE');
    });
    assert.equal(attempts, 1, 'malformed GET is never automatically repeated');
  }
  await withFetch(async () => new Response(JSON.stringify(task), { status: 200 }), async () => {
    assert.deepEqual(await diagnosticService.currentTask(learnerId, sessionId), task);
  });
});
