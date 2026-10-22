import test from 'node:test';
import assert from 'node:assert/strict';
import { learningService, LearningHttpError } from '../src/entities/attempt/learning.service.ts';

const learner = 'learner-1', session = 'session-2', task = 'task-3';
const root = '/api/v1/learners/learner-1/diagnostic-sessions/session-2/tasks/task-3';

test('learning reads use the correct scoped endpoints and cursor', async t => {
  const original = globalThis.fetch;
  const calls = [];
  globalThis.fetch = async (url) => {
    calls.push(url);
    return new Response(JSON.stringify({ items: [], nextCursor: null }), { status: 200 });
  };
  t.after(() => { globalThis.fetch = original; });
  await learningService.state(learner, session, task);
  await learningService.assessments(learner, session, task, 'attempt-4', 'next/page');
  await learningService.decisions(learner, session, task, 'next/page');
  await learningService.evidence(learner, session, task, 'next/page');
  assert.deepEqual(calls, [
    root + '/learning',
    root + '/attempts/attempt-4/assessments?cursor=next%2Fpage',
    root + '/decisions?cursor=next%2Fpage',
    root + '/learning/evidence?cursor=next%2Fpage',
  ]);
});

test('decision request preserves requestId, expectedVersion and null assessment', async t => {
  const original = globalThis.fetch;
  let captured;
  globalThis.fetch = async (url, init) => {
    captured = { url, init };
    return new Response(JSON.stringify({ taskState: 'COMPLETED_BY_LEARNER' }), { status: 201 });
  };
  t.after(() => { globalThis.fetch = original; });
  const command = {
    requestId: 'fixed-request-id', attemptId: 'attempt-4', action: 'COMPLETE',
    expectedVersion: 7, baseAssessmentId: null, reason: 'Continue later',
  };
  await learningService.decide(learner, session, task, command);
  assert.equal(captured.url, root + '/decisions');
  assert.equal(captured.init.method, 'POST');
  assert.equal(captured.init.headers['Content-Type'], 'application/json');
  assert.deepEqual(JSON.parse(captured.init.body), command);
});

test('structured 409 conflict is returned without leaking server message', async t => {
  const original = globalThis.fetch;
  globalThis.fetch = async () => new Response(
    JSON.stringify({ code: 'ASSESSMENT_CHANGED', message: 'Sensitive message' }),
    { status: 409 },
  );
  t.after(() => { globalThis.fetch = original; });
  await assert.rejects(
    learningService.state(learner, session, task),
    e => e instanceof LearningHttpError && e.status === 409 &&
      e.code === 'ASSESSMENT_CHANGED' && !e.message.includes('Sensitive'),
  );
});

test('network errors are normalized for localized UI', async t => {
  const original = globalThis.fetch;
  globalThis.fetch = async () => { throw new TypeError('network failure'); };
  t.after(() => { globalThis.fetch = original; });
  await assert.rejects(
    learningService.state(learner, session, task),
    e => e instanceof LearningHttpError && e.status === 0 && e.code === 'NETWORK_UNAVAILABLE',
  );
});
