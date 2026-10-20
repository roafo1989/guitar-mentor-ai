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

import { tunerReading } from './tuner-reading.ts';
import { isolateDialogFocus } from './modal-focus.ts';

test('stable A2 is displayed even with E2 selected; only matching string is in tune', () => {
  const wrong = tunerReading(110, STRINGS[0].hz);
  assert.equal(wrong.note, 'A2');
  assert.equal(wrong.status, 'wrongString');
  assert.ok(wrong.cents > 400);
  assert.equal(wrong.meterValue, 50);
  const matched = tunerReading(110, STRINGS[1].hz);
  assert.equal(matched.status, 'inTune');
  assert.equal(matched.meterValue, 0);
});

test('tuning tolerances and meter use bounded accessibility values', () => {
  const target = 110;
  for (const offset of [-1200, -200, -50, -6, -5, 0, 5, 6, 50, 200, 1200]) {
    const frequency = target * 2 ** (offset / 1200);
    const reading = tunerReading(frequency, target);
    assert.ok(reading, 'valid frequency at ' + offset);
    assert.ok(reading.meterValue >= -50 && reading.meterValue <= 50);
    const expected = Math.abs(offset) > 200 ? 'wrongString' :
      Math.abs(offset) <= 5 ? 'inTune' : offset < 0 ? 'flat' : 'sharp';
    // Floating-point values at exact boundaries may need a small tolerance.
    if (![-200, -5, 5, 200].includes(offset)) assert.equal(reading.status, expected);
    if (Math.abs(offset) >= 50) assert.equal(reading.meterValue, Math.sign(offset) * 50);
  }
  assert.equal(tunerReading(null, target), null);
  assert.equal(tunerReading(Number.NaN, target), null);
});

test('modal isolation preserves previous inert state and guards programmatic focus', () => {
  let handler;
  let addCount = 0;
  let removeCount = 0;
  let focused = 0;
  const outside = { id: 'background' };
  const dialog = {
    contains: node => node === dialog || node === inside,
    focus: () => { focused++; },
  };
  const inside = { id: 'button' };
  const doc = {
    addEventListener: (type, fn, capture) => {
      assert.equal(type, 'focusin'); assert.equal(capture, true);
      handler = fn; addCount++;
    },
    removeEventListener: (type, fn, capture) => {
      assert.equal(type, 'focusin'); assert.equal(fn, handler);
      assert.equal(capture, true); removeCount++;
    },
  };
  const root = { inert: false };
  const release = isolateDialogFocus(root, dialog, doc);
  assert.equal(root.inert, true);
  assert.equal(focused, 1);
  handler({ target: inside });
  assert.equal(focused, 1);
  handler({ target: outside });
  assert.equal(focused, 2);
  release();
  release();
  assert.equal(root.inert, false);
  assert.equal(addCount, 1);
  assert.equal(removeCount, 1);

  const previouslyInert = { inert: true };
  isolateDialogFocus(previouslyInert, dialog, doc)();
  assert.equal(previouslyInert.inert, true);
});

const harmonics = (frequency, rate, amplitudes, detuneCents = 0, withNoise = false) => {
  const tunedFrequency = frequency * 2 ** (detuneCents / 1200);
  return Float32Array.from({ length: 8192 }, (_, i) => {
    const time = i / rate;
    const signal = amplitudes.reduce((v, amp, harmonic) =>
      v + amp * Math.sin(2 * Math.PI * tunedFrequency * (harmonic + 1) * time + harmonic * 0.45), 0);
    return 0.3 * (signal * Math.exp(-0.6 * time) +
      (withNoise ? 0.008 * Math.sin(2 * Math.PI * 9977 * time) : 0));
  });
};

test('strong second harmonics do not cause octave aliases on six strings', () => {
  for (const rate of [44100, 48000]) for (const string of STRINGS) {
    for (const amplitudes of [[0.15, 1], [0.05, 1, 0.05], [1, 0.45, 0.3, 0.1]]) {
      const result = detectPitch(harmonics(string.hz, rate, amplitudes, 0, true), rate);
      assert.ok(result, 'No pitch for ' + string.note + ' at ' + rate + ' with ' + amplitudes);
      assert.ok(Math.abs(cents(result, string.hz)) < 5,
        'Octave/partial error for ' + string.note + ' at ' + rate + ': ' + result);
    }
  }
});

test('detuned multi-harmonic guitar strings retain accurate fundamentals', () => {
  for (const string of STRINGS) for (const offset of [-35, 35]) {
    const expected = string.hz * 2 ** (offset / 1200);
    const result = detectPitch(harmonics(string.hz, 48000, [0.15, 1, 0.3], offset, true), 48000);
    assert.ok(result, 'No pitch for ' + string.note + ' offset ' + offset);
    assert.ok(Math.abs(cents(result, expected)) < 5,
      'Detuning error for ' + string.note + ' offset ' + offset + ': ' + result);
  }
});

test('a pure second harmonic is not mistaken for a missing lower fundamental', () => {
  for (const string of STRINGS.slice(0, 3)) {
    const result = detectPitch(sine(string.hz * 2), 48000);
    assert.ok(result);
    assert.ok(Math.abs(cents(result, string.hz * 2)) < 5);
  }
});
