import test from 'node:test';
import assert from 'node:assert/strict';
import { intakeNavigationAction } from '../src/entities/diagnostic/intake-navigation.ts';

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
