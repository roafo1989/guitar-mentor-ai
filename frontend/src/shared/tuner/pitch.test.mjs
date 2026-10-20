import test from 'node:test';
import assert from 'node:assert/strict';
import { STRINGS, detectPitch, cents, noteName, stablePitch } from './pitch.ts';
import { acquireAudio, releaseAudio, audioOwner } from '../audio/audio-session.ts';
const sine = (frequency, rate = 48000, amplitude = 0.3) =>
  Float32Array.from({ length: 8192 }, (_, i) => amplitude * Math.sin(2 * Math.PI * frequency * i / rate));
test('six standard guitar strings at 44.1 and 48 kHz', () => {
  for (const rate of [44100, 48000]) for (const string of STRINGS) {
    const detected = detectPitch(sine(string.hz, rate), rate);
    assert.ok(detected, string.note);
    assert.ok(Math.abs(cents(detected, string.hz)) < 3, string.note);
  }
});
test('silence, weak sine and uncorrelated noise are rejected', () => {
  assert.equal(detectPitch(new Float32Array(8192), 48000), null);
  assert.equal(detectPitch(sine(110, 48000, 0.002), 48000), null);
  let seed = 1234;
  const noise = Float32Array.from({ length: 8192 }, () => {
    seed = (1664525 * seed + 1013904223) >>> 0;
    return (seed / 4294967296 - 0.5) * 0.6;
  });
  assert.equal(detectPitch(noise, 48000), null);
});
test('correct note, octave, and sign of cents', () => {
  assert.equal(noteName(440), 'A4');
  assert.equal(noteName(82.406889), 'E2');
  assert.ok(cents(430, 440) < 0);
  assert.ok(cents(450, 440) > 0);
});
test('requires 3 stable readings and discards stale pitch', () => {
  const history = [];
  assert.equal(stablePitch(history, 110), null);
  assert.equal(stablePitch(history, 110.1), null);
  assert.ok(stablePitch(history, 109.9) !== null);
  assert.equal(stablePitch(history, 125), null);
  assert.equal(stablePitch(history, null), null);
});
test('recording has priority and stale releases do not free it', () => {
  const tuner = Symbol(), recorder = Symbol(), other = Symbol();
  assert.equal(acquireAudio('tuner', tuner, () => releaseAudio(tuner)), true);
  assert.equal(acquireAudio('recorder', recorder, () => {}), true);
  assert.equal(audioOwner(), 'recorder');
  assert.equal(acquireAudio('tuner', other, () => {}), false);
  assert.equal(acquireAudio('recorder', other, () => {}), false);
  releaseAudio(tuner);
  assert.equal(audioOwner(), 'recorder');
  releaseAudio(recorder);
  assert.equal(audioOwner(), null);
});
