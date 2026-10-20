import test from 'node:test';
import assert from 'node:assert/strict';
import { createRouter, createMemoryHistory } from 'vue-router';
import {
  intakeNavigationAction, permitIntakeNavigation, shouldWarnBeforeUnload,
} from '../src/entities/diagnostic/intake-navigation.ts';

test('intake can be skipped with an empty form', () => {
  assert.equal(intakeNavigationAction(false, false, false), 'ALLOW');
});
test('active microphone recording prevents leaving intake', () => {
  assert.equal(intakeNavigationAction(false, true, false), 'BLOCK');
});
test('ongoing upload prevents continue and avoids lost failed upload message', () => {
  assert.equal(intakeNavigationAction(false, true, true), 'BLOCK');
});
test('unsaved recorded audio or partially completed form requires confirmation', () => {
  assert.equal(intakeNavigationAction(false, false, true), 'CONFIRM_DISCARD');
});
test('a second continue while a previous continue request is pending is blocked', () => {
  assert.equal(intakeNavigationAction(true, false, false), 'BLOCK');
});

/** Runs the same policy called by DiagnosticView.onBeforeRouteLeave in a real Vue Router. */
async function guardedRouter(intake, confirmation) {
  const view = { render: () => null };
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/diagnostics', component: view },
      { path: '/onboarding', component: view },
      { path: '/profile', component: view },
    ],
  });
  router.beforeEach((_to, from) => {
    if (from.path !== '/diagnostics' || !intake.collecting) return true;
    return permitIntakeNavigation(intake.parentBusy, intake.busy, intake.dirty, confirmation);
  });
  await router.push('/diagnostics');
  await router.isReady();
  return router;
}

test('Vue Router blocks Back/header navigation during an active recording', async () => {
  const state = { collecting: true, parentBusy: false, busy: true, dirty: false };
  let confirmations = 0;
  const router = await guardedRouter(state, () => { confirmations++; return true; });
  await router.push('/onboarding'); // same target as page Back
  assert.equal(router.currentRoute.value.path, '/diagnostics');
  await router.push('/profile'); // header navigation is protected too
  assert.equal(router.currentRoute.value.path, '/diagnostics');
  assert.equal(confirmations, 0); // Never allow discarding an active recording.
  state.busy = false; // User stopped/cancelled recording safely.
  await router.push('/onboarding');
  assert.equal(router.currentRoute.value.path, '/onboarding');
});

test('Vue Router keeps draft after user declines Back confirmation', async () => {
  const state = { collecting: true, parentBusy: false, busy: false, dirty: true };
  let confirmations = 0;
  const router = await guardedRouter(state, () => { confirmations++; return false; });
  await router.push('/onboarding');
  assert.equal(router.currentRoute.value.path, '/diagnostics');
  assert.equal(state.dirty, true);
  assert.equal(confirmations, 1);
});

test('Vue Router permits leaving only after user confirms discarding draft', async () => {
  const state = { collecting: true, parentBusy: false, busy: false, dirty: true };
  const router = await guardedRouter(state, () => true);
  await router.push('/profile');
  assert.equal(router.currentRoute.value.path, '/profile');
});

test('upload blocks router navigation until upload completes or is cancelled', async () => {
  const state = { collecting: true, parentBusy: false, busy: true, dirty: true };
  const router = await guardedRouter(state, () => false);
  await router.push('/profile');
  assert.equal(router.currentRoute.value.path, '/diagnostics');
  state.busy = false; // Abort has settled; files remain for idempotent retry.
  await router.push('/profile');
  assert.equal(router.currentRoute.value.path, '/diagnostics'); // Unsaved draft confirmation declined.
  state.dirty = false; // A completed upload cleared the draft.
  await router.push('/profile');
  assert.equal(router.currentRoute.value.path, '/profile');
});

test('browser reload/tab close prompts only while collecting with a draft or active upload', () => {
  assert.equal(shouldWarnBeforeUnload(true, false, false), false);
  assert.equal(shouldWarnBeforeUnload(true, true, false), true);
  assert.equal(shouldWarnBeforeUnload(true, false, true), true);
  assert.equal(shouldWarnBeforeUnload(false, true, true), false);
});
