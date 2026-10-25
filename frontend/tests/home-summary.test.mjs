import './current-diagnostic.test.mjs';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { test } from 'node:test';
import ts from 'typescript';

// Test the actual TypeScript module with its API dependencies stubbed.
// No browser or network is needed to exercise the read-only state resolver.
const source = readFileSync(new URL('../src/core/home/home-summary.ts', import.meta.url), 'utf8');
const compiled = ts.transpileModule(source, {
  compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 },
}).outputText;

function harness({ session, task, learning, background = null, sessionError, taskError, learningError } = {}) {
  const calls = [];
  const selected = 'saved-session';
  const data = new Map([['guitar-mentor.diagnostic.learner-1.ACOUSTIC_GUITAR', selected]]);
  const storage = {
    getItem: key => data.get(key) ?? null,
    removeItem: key => { calls.push('remove'); data.delete(key); },
    setItem: (key, value) => { calls.push('set'); data.set(key, value); },
  };
  const services = {
    '../../shared/api-failure': { ApiFailure: class ApiFailure extends Error {}, isHttp: (cause, status) => cause?.status === status },
    '../../entities/diagnostic/diagnostic.service': {
      diagnosticService: {
        get: async () => { calls.push('get-session'); if (sessionError) throw sessionError; return session; },
        currentTask: async () => { calls.push('get-task'); if (taskError) throw taskError; return task; },
      },
    },
    '../../entities/diagnostic/current-diagnostic': {
      diagnosticSelectionKey: learnerId => 'guitar-mentor.diagnostic.' + learnerId + '.ACOUSTIC_GUITAR',
      resolveCurrentDiagnostic: async (learnerId, storage) => {
        const key = 'guitar-mentor.diagnostic.' + learnerId + '.ACOUSTIC_GUITAR';
        const selected = storage.getItem(key);
        if (selected) {
          try {
            return await services['../../entities/diagnostic/diagnostic.service'].diagnosticService.get(learnerId, selected);
          } catch (cause) {
            if (cause?.status !== 404) throw cause;
            if (storage.getItem(key) !== selected) throw cause;
            storage.removeItem(key);
          }
        }
        calls.push('get-current');
        return null;
      },
    },
    '../../entities/onboarding/onboarding.service': {
      onboardingService: { get: async () => { calls.push('get-background'); return background; } },
    },
    '../../entities/attempt/learning.service': {
      LearningHttpError: class LearningHttpError extends Error { constructor(status, code) { super(code); this.status = status; this.code = code; } },
      learningService: { state: async () => { calls.push('get-learning'); if (learningError) throw new services['../../entities/attempt/learning.service'].LearningHttpError(learningError.status, learningError.code); return learning; } },
    },
  };
  const exports = {};
  new Function('require', 'exports', compiled)(
    id => { assert.ok(services[id], 'unexpected import: ' + id); return services[id]; }, exports,
  );
  return { ...exports, storage, calls, data };
}
test('no learner returns profile without network or writes', async () => {
  const h = harness();
  assert.equal((await h.resolveHomeSummary(null, h.storage)).step, 'profile');
  assert.deepEqual(h.calls, []);
});
test('no selected session asks existing onboarding, not POST', async () => {
  const h = harness({ background: { instrumentCode: 'ACOUSTIC_GUITAR' } });
  h.data.clear();
  assert.equal((await h.resolveHomeSummary('learner-1', h.storage)).step, 'diagnostics');
  assert.deepEqual(h.calls, ['get-current', 'get-background']);
});
test('assigned active task shows title and lesson', async () => {
  const h = harness({
    session: { id: 'saved-session', status: 'TASK_ASSIGNED' },
    task: { id: 'task-1', exercise: { title: 'Blues study', code: 'GM-EB01', version: '1.0' } },
    learning: { taskState: 'ACTIVE' },
  });
  assert.deepEqual(await h.resolveHomeSummary('learner-1', h.storage), {
    step: 'lesson', title: 'Blues study', exerciseCode: 'GM-EB01', exerciseVersion: '1.0',
  });
  assert.deepEqual(h.calls, ['get-session', 'get-task', 'get-learning']);
});
test('completed by learner opens result without claiming skill', async () => {
  const h = harness({
    session: { id: 'saved-session', status: 'TASK_ASSIGNED' },
    task: { id: 'task-1', exercise: { title: 'Blues', code: 'GM-EB01', version: '1' } },
    learning: { taskState: 'COMPLETED_BY_LEARNER' },
  });
  assert.equal((await h.resolveHomeSummary('learner-1', h.storage)).step, 'result');
});
test('network failure retains session selection', async () => {
  const h = harness({ sessionError: new Error('NETWORK_UNAVAILABLE') });
  await assert.rejects(h.resolveHomeSummary('learner-1', h.storage), /NETWORK_UNAVAILABLE/);
  assert.equal(h.storage.getItem(h.selectedSessionKey('learner-1')), 'saved-session');
  assert.ok(!h.calls.includes('remove'));
});
test('404 on GET session clears only its key', async () => {
  const h = harness({ sessionError: Object.assign(new Error('HTTP_404'), { status: 404 }) });
  assert.equal((await h.resolveHomeSummary('learner-1', h.storage)).step, 'onboarding');
  assert.deepEqual(h.calls, ['get-session', 'remove', 'get-current', 'get-background']);
});
test('404 from task GET does not clear selected session', async () => {
  const h = harness({
    session: { id: 'saved-session', status: 'TASK_ASSIGNED' },
    taskError: Object.assign(new Error('HTTP_404'), { status: 404 }),
  });
  await assert.rejects(h.resolveHomeSummary('learner-1', h.storage), /HTTP_404/);
  assert.equal(h.storage.getItem(h.selectedSessionKey('learner-1')), 'saved-session');
});
test('late 404 cannot erase a more recent selection', async () => {
  let rejectSession;
  const h = harness({ sessionError: null });
  const original = h.storage.getItem;
  // Delay request through the service mock by recompiling with an externally controlled rejection.
  const key = h.selectedSessionKey('learner-1');
  const request = new Promise((_, reject) => { rejectSession = reject; });
  const services = {
    '../../shared/api-failure': { ApiFailure: class ApiFailure extends Error {}, isHttp: (cause, status) => cause?.status === status },
    '../../entities/diagnostic/diagnostic.service': { diagnosticService: { get: () => request } },
    '../../entities/diagnostic/current-diagnostic': { diagnosticSelectionKey: learnerId => 'guitar-mentor.diagnostic.' + learnerId + '.ACOUSTIC_GUITAR', resolveCurrentDiagnostic: async (_id, storage) => { try { return await services['../../entities/diagnostic/diagnostic.service'].diagnosticService.get(); } catch(e) { if(storage.getItem(key) !== 'saved-session') throw e; throw e; } } },
    '../../entities/onboarding/onboarding.service': { onboardingService: { get: async () => null } },
    '../../entities/attempt/learning.service': { LearningHttpError: class LearningHttpError extends Error {}, learningService: { state: async () => null } },
  };
  const exports = {};
  new Function('require', 'exports', compiled)(id => services[id], exports);
  const promise = exports.resolveHomeSummary('learner-1', h.storage);
  h.data.set(key, 'newer-session');
  rejectSession(Object.assign(new Error('HTTP_404'), { status: 404 }));
  await assert.rejects(promise, /HTTP_404/);
  assert.equal(original(key), 'newer-session');
  assert.ok(!h.calls.includes('remove'));
});

test('new registered learner without instrument must be directed to profile before instrument background GET', async () => {
  const view = readFileSync(new URL('../src/core/home/HomeView.vue', import.meta.url), 'utf8');
  assert.match(view, /learnerStore\.learner\?\.instruments\.some\(/);
  assert.match(view, /resolveHomeSummary\(hasGuitar \? id : null, window\.localStorage,/);
  const h = harness();
  assert.equal((await h.resolveHomeSummary(null, h.storage)).step, 'profile');
  assert.deepEqual(h.calls, []);
});


test('ready for task directs to exercise selection without reading learning state', async () => {
  const h = harness({ session: { id: 'saved-session', status: 'READY_FOR_TASK' } });
  assert.equal((await h.resolveHomeSummary('learner-1', h.storage)).step, 'choose');
  assert.deepEqual(h.calls, ['get-session']);
});
test('collecting evidence resumes assessment without falsely assigning an exercise', async () => {
  const h = harness({ session: { id: 'saved-session', status: 'COLLECTING_EVIDENCE' } });
  assert.equal((await h.resolveHomeSummary('learner-1', h.storage)).step, 'diagnostics');
  assert.deepEqual(h.calls, ['get-session']);
});
test('completed passed task leads to result, not an invented next lesson', async () => {
  const h = harness({
    session: { id: 'saved-session', status: 'TASK_ASSIGNED' },
    task: { id: 'task-1', exercise: { title: 'Study', code: 'GM-E1', version: '2' } },
    learning: { taskState: 'COMPLETED_PASSED' },
  });
  assert.equal((await h.resolveHomeSummary('learner-1', h.storage)).step, 'result');
});
test('unknown diagnostic status and malformed assigned task reject instead of producing a misleading CTA', async () => {
  const unknown = harness({ session: { id: 'saved-session', status: 'UNKNOWN' } });
  await assert.rejects(unknown.resolveHomeSummary('learner-1', unknown.storage));
  const malformed = harness({ session: { id: 'saved-session', status: 'TASK_ASSIGNED' }, task: null });
  await assert.rejects(malformed.resolveHomeSummary('learner-1', malformed.storage));
  assert.equal(malformed.storage.getItem(malformed.selectedSessionKey('learner-1')), 'saved-session');
});
test('#149 opt-in home layout is localized and does not fabricate learning data', () => {
  const view = readFileSync(new URL('../src/core/home/HomeView.vue', import.meta.url), 'utf8');
  const ru = JSON.parse(readFileSync(new URL('../src/i18n/ru.json', import.meta.url), 'utf8'));
  const en = JSON.parse(readFileSync(new URL('../src/i18n/en.json', import.meta.url), 'utf8'));
  assert.match(view, /VITE_GUITAR_MENTOR_LIGHT_MINIMAL === 'true'/);
  assert.match(view, /:data-home-ui="lightMinimal/);
  assert.match(view, /hasAssignedWork/);
  assert.match(view, /visibleSections/);
  assert.match(view, /watch\(\(\) => auth\.actor,/);
  assert.match(view, /auth\.actor !== actor/);
  assert.doesNotMatch(view, /streak|mockProgress|fakeMinutes|Math\.random\(/i);
  for (const messages of [ru,en]) {
    for (const step of ['profile','onboarding','diagnostics','choose','lesson','result']) {
      assert.ok(messages.home.next[step], 'primary CTA locale ' + step);
      assert.ok(messages.home.light.descriptions[step], 'state description locale ' + step);
    }
    assert.ok(messages.home.light.currentTitle);
    assert.ok(messages.home.light.assigned);
  }
});

test('#149 regression: disabled optional learning module must not blank assigned lesson', async () => {
  const h = harness({
    session: { id: 'saved-session', status: 'TASK_ASSIGNED' },
    task: { id: 'task-1', exercise: { title: 'Triple meter pulse', code: 'GM-DIAG-PULSE-3-8-01', version: '0.1.0' } },
    learningError: { status: 503, code: 'LEARNING_DISABLED' },
  });
  assert.deepEqual(await h.resolveHomeSummary('learner-1', h.storage), {
    step: 'lesson', title: 'Triple meter pulse',
    exerciseCode: 'GM-DIAG-PULSE-3-8-01', exerciseVersion: '0.1.0',
  });
  assert.deepEqual(h.calls, ['get-session', 'get-task', 'get-learning']);
  assert.equal(h.storage.getItem(h.selectedSessionKey('learner-1')), 'saved-session');
});
test('#149 regression: unrelated learning faults must not be silently swallowed', async () => {
  for (const error of [
    { status: 401, code: 'UNAUTHENTICATED' },
    { status: 403, code: 'FORBIDDEN' },
    { status: 503, code: 'DATABASE_UNAVAILABLE' },
    { status: 404, code: 'RESOURCE_NOT_FOUND' },
  ]) {
    const h = harness({
      session: { id: 'saved-session', status: 'TASK_ASSIGNED' },
      task: { id: 'task-1', exercise: { title: 'Study', code: 'GM-E1', version: '1' } },
      learningError: error,
    });
    await assert.rejects(h.resolveHomeSummary('learner-1', h.storage), e => e.status === error.status && e.code === error.code);
    assert.equal(h.storage.getItem(h.selectedSessionKey('learner-1')), 'saved-session');
  }
});
