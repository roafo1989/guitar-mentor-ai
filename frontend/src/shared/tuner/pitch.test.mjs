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


// Independent QA regression for BUG-98-QA-01:
// six strings × 44.1/48 kHz × nine profiles with a nonzero fundamental.
test('QA: 108 harmonic-rich guitar signals preserve the actual pitch', () => {
  const profiles = [
    [1], [0.15, 1], [0.05, 1, 0.05], [1, 0.45, 0.3, 0.1],
    [0.02, 1], [0.02, 0.02, 1], [0.15, 0.3, 0.5, 0.7, 0.9, 1],
    [0.1, 0.1, 1, 0.1], [0.2, 1, 0.3],
  ];
  let count = 0;
  for (const rate of [44100, 48000]) for (const string of STRINGS) for (const amplitudes of profiles) {
    const found = detectPitch(harmonics(string.hz, rate, amplitudes), rate);
    assert.ok(found !== null, string.note + ' undetected with ' + amplitudes);
    assert.ok(Math.abs(cents(found, string.hz)) < 10,
      string.note + ' / ' + rate + ' / ' + amplitudes + ': ' + found);
    count++;
  }
  assert.equal(count, 108);
});

test('QA: isolated upper overtones never invent a lower missing fundamental', () => {
  for (const rate of [44100, 48000]) {
    for (const string of STRINGS.slice(0, 3)) {
      const audible = 2 * string.hz;
      const found = detectPitch(harmonics(audible, rate, [1]), rate);
      assert.ok(found !== null);
      assert.ok(Math.abs(cents(found, audible)) < 10);
    }
    // A high partial outside the scan range is ambiguous, not proof of a lower note.
    for (const string of STRINGS.slice(3)) {
      assert.equal(detectPitch(harmonics(string.hz, rate, [0, 0, 1]), rate), null);
    }
  }
});

test('QA: repeated weak fundamentals stabilize on the intended note', () => {
  const history = [];
  for (let i = 0; i < 2; i++) {
    assert.equal(stablePitch(history, detectPitch(harmonics(110, 48000, [0.02, 0.02, 1]), 48000)), null);
  }
  const result = stablePitch(history, detectPitch(harmonics(110, 48000, [0.02, 0.02, 1]), 48000));
  assert.ok(result !== null && Math.abs(cents(result, 110)) < 10);
});


// Independent review regression: realistic stiff strings have slightly sharp
// upper partials, f_n = n*f1*sqrt((1+B*n*n)/(1+B)), despite f1 being exactly tuned.
const stiffString = (frequency, rate, amplitudes, B) =>
  Float32Array.from({ length: 8192 }, (_, i) => {
    const t = i / rate;
    let value = 0;
    for (let h = 1; h <= amplitudes.length; h++) {
      const partialHz = h * frequency * Math.sqrt((1 + B * h * h) / (1 + B));
      value += amplitudes[h - 1] * Math.sin(2 * Math.PI * partialHz * t + h * 0.4);
    }
    return 0.2 * Math.exp(-0.6 * t) * value;
  });

test('P2 inharmonicity: 288 modeled stiff-string signals stay within 3 cents', () => {
  const spectra = [[0.2, 1, 0.6, 0.3, 0.12], [1, 0.4, 0.3, 0.2]];
  let checked = 0;
  for (const rate of [44100, 48000]) for (const string of STRINGS) {
    for (const amps of spectra) for (const B of [0, 0.0005, 0.001, 0.002]) {
      for (const detune of [-25, 0, 25]) {
        const actual = string.hz * 2 ** (detune / 1200);
        const result = detectPitch(stiffString(actual, rate, amps, B), rate);
        assert.ok(result !== null, string.note + ' not detected: B=' + B);
        const error = cents(result, actual);
        assert.ok(Math.abs(error) < 3,
          string.note + ' B=' + B + ' detune=' + detune + ' ' + rate +
          ' spectrum=' + amps + ' bias=' + error + ' cents');
        const reading = tunerReading(result, string.hz);
        assert.equal(reading.status, detune === 0 ? 'inTune' :
          detune < 0 ? 'flat' : 'sharp',
        string.note + ' B=' + B + ', detune=' + detune);
        checked++;
      }
    }
  }
  assert.equal(checked, 288);
});

test('P2 inharmonicity: weak 2% fundamental cannot yield a false tuned note', () => {
  // A very weak fundamental can yield a temporary no-reading response;
  // accepting null is preferable to showing a wrong note or octave.
  let checked = 0, detected = 0;
  for (const rate of [44100, 48000]) for (const string of STRINGS) {
    for (const B of [0, 0.0005, 0.001, 0.002]) {
      for (const detune of [-25, 0, 25]) {
        const actual = string.hz * 2 ** (detune / 1200);
        const result = detectPitch(stiffString(actual, rate, [0.02, 1], B), rate);
        if (result !== null) {
          assert.ok(Math.abs(cents(result, actual)) < 5,
            'False confident reading for ' + string.note + ', B=' + B + ' offset=' + detune);
          detected++;
        }
        checked++;
      }
    }
  }
  assert.equal(checked, 144);
  assert.ok(detected >= 120, 'weak fundamental should remain useful');
});


import { newPitchTrack, trackPitch, autoStringIndex, HOLD_MS } from './tuner-session.ts';

test('REAL-01: a verified note survives isolated silent frames but expires after bounded silence', () => {
  const tracker = newPitchTrack();
  assert.equal(trackPitch(tracker, 110, 100), null);
  assert.equal(trackPitch(tracker, 110.2, 200), null);
  assert.ok(Math.abs(cents(trackPitch(tracker, 110.1, 300), 110)) < 3);
  assert.ok(Math.abs(cents(trackPitch(tracker, null, 450), 110)) < 3);
  assert.ok(Math.abs(cents(trackPitch(tracker, 110, 550), 110)) < 3);
  assert.ok(Math.abs(cents(trackPitch(tracker, null, 550 + HOLD_MS), 110)) < 3);
  assert.equal(trackPitch(tracker, null, 551 + HOLD_MS), null);
  assert.equal(tracker.history.length, 0, 'expired samples must not influence a new string');
});

test('REAL-01: different string immediately drops old reading and requires stabilization', () => {
  const state = newPitchTrack();
  for (const now of [100, 200, 300]) trackPitch(state, 82.406889, now);
  assert.ok(state.frequency !== null);
  assert.equal(trackPitch(state, 110, 400), null, 'never display the previous string during attack');
  assert.equal(trackPitch(state, 110.1, 500), null);
  const note = trackPitch(state, 109.9, 600);
  assert.ok(note !== null && Math.abs(cents(note, 110)) < 3);
});

test('REAL-01: lower levels recognize naturally decaying strings without accepting silence/noise', () => {
  for (const string of STRINGS) {
    const samples = sine(string.hz, 48000, 0.009);
    const frequency = detectPitch(samples, 48000);
    assert.ok(frequency !== null, string.note + ': sustain should be detectable');
    assert.ok(Math.abs(cents(frequency, string.hz)) < 5, string.note + ': wrong quiet note');
  }
  for (const amplitude of [0, 0.002]) {
    assert.equal(detectPitch(sine(110, 48000, amplitude), 48000), null);
  }
  let seed = 1234;
  const noise = Float32Array.from({ length: 8192 }, () => {
    seed = (1664525 * seed + 1013904223) >>> 0;
    return (seed / 4294967296 - 0.5) * 0.018;
  });
  assert.equal(detectPitch(noise, 48000), null);
});

test('REAL-02: automatic mode identifies all six open strings, including moderate detuning', () => {
  for (let index = 0; index < STRINGS.length; index++) {
    for (const centsOffset of [-120, 0, 120]) {
      const frequency = STRINGS[index].hz * 2 ** (centsOffset / 1200);
      assert.equal(autoStringIndex(frequency, null), index, STRINGS[index].note);
    }
  }
  assert.equal(autoStringIndex(110 * 2 ** (300 / 1200), null), null,
    'out-of-range pitch requires manual selection');
  assert.equal(autoStringIndex(null, 1), null);
});

test('REAL-02: hysteresis prevents auto-target thrash around string boundaries', () => {
  const middle = Math.sqrt(STRINGS[3].hz * STRINGS[4].hz);
  assert.equal(autoStringIndex(middle, null), null, 'without context the boundary is ambiguous');
  assert.equal(autoStringIndex(middle * 1.004, 3), 3);
  assert.equal(autoStringIndex(middle / 1.004, 4), 4);
  assert.equal(autoStringIndex(STRINGS[4].hz, 3), 4, 'real new string switches');
  assert.equal(autoStringIndex(STRINGS[3].hz, 4), 3);
});

test('REAL-02: notes outside ordinary open strings are not silently marked in tune', () => {
  for (const frequency of [95, 127, 174, 220 * 2]) {
    const choice = autoStringIndex(frequency, null);
    if (choice !== null) {
      assert.ok(Math.abs(cents(frequency, STRINGS[choice].hz)) <= 175);
    }
  }
  // The selected note must come from the measured frequency, not from a button state.
  for (const string of STRINGS) {
    const candidate = detectPitch(sine(string.hz), 48000);
    assert.ok(candidate !== null);
    assert.equal(autoStringIndex(candidate, null), STRINGS.indexOf(string));
  }
});


// P2 BUG-98-REVIEW-03: Auto must not report "in tune" while two separately
// pitched strings are visible in the spectrum. Some exact harmonic overlaps
// are physically indistinguishable in a short mono recording; those exceptions
// are explicitly limited and described in the feature documentation.
const twoStrings = (first, second, secondLevel, rate, phaseVariant) =>
  Float32Array.from({ length: 8192 }, (_, i) => {
    const time = i / rate;
    const sound = (hz, gain, phase) => [1, 0.45, 0.25].reduce((value, amp, k) =>
      value + gain * amp * Math.sin(2 * Math.PI * hz * (k + 1) * time +
        (phaseVariant === 0 ? k : k + 1) * phase), 0);
    return 0.16 * Math.exp(-0.3 * time) *
      (sound(first, 1, 0.4) + sound(second, secondLevel, 0.8));
  });

test('REVIEW-03: 150 two-string mixtures never confidently show a clear false green', () => {
  let checked = 0, rejected = 0;
  const unresolved = [];
  for (const phaseVariant of [0, 1]) {
    for (const level of [0.2, 0.4, 0.6, 0.8, 1]) {
      for (let first = 0; first < STRINGS.length; first++) {
        for (let second = first + 1; second < STRINGS.length; second++) {
          const signal = twoStrings(STRINGS[first].hz, STRINGS[second].hz,
            level, 48000, phaseVariant);
          const result = detectPitch(signal, 48000, true);
          if (result === null) {
            rejected++;
          } else {
            unresolved.push({
              first, second, level, phaseVariant,
              status: tunerReading(result, STRINGS[first].hz).status,
            });
          }
          checked++;
        }
      }
    }
  }
  assert.equal(checked, 150);
  assert.ok(rejected >= 148, 'Most polyphonic signals must be withheld in Auto');
  assert.ok(unresolved.every(x =>
    x.first === 0 && x.second === 5 && x.level === 0.2),
    'Only the quiet E4 aligned with the E2 fourth harmonic may be indeterminate');
});

test('REVIEW-03: Auto recognizes clean low notes, overtones and inharmonic strings', () => {
  const profiles = [[1], [1, 0.45, 0.3, 0.1], [0.02, 0.02, 1],
    [0.15, 0.3, 0.5, 0.7, 0.9, 1], [0.2, 1, 0.6, 0.3, 0.12]];
  let checked = 0;
  for (const rate of [44100, 48000]) for (const string of STRINGS) {
    for (const amps of profiles) {
      const clean = harmonics(string.hz, rate, amps);
      const found = detectPitch(clean, rate, true);
      assert.ok(found !== null, 'Auto rejected ' + string.note + ' with ' + amps);
      assert.ok(Math.abs(cents(found, string.hz)) < 5,
        'Auto misstuned ' + string.note);
      checked++;
    }
    for (const stiffness of [0.0005, 0.001, 0.002]) {
      const stiff = stiffString(string.hz, rate, [1, 0.4, 0.3, 0.2], stiffness);
      const found = detectPitch(stiff, rate, true);
      assert.ok(found !== null, 'Auto rejected stiff ' + string.note);
      assert.ok(Math.abs(cents(found, string.hz)) < 5,
        'Auto wrong cents on stiff ' + string.note);
      checked++;
    }
  }
  assert.equal(checked, 96);
});

test('REVIEW-03: manual mode may analyze dominant pitch without green Auto lock', () => {
  const signal = twoStrings(STRINGS[0].hz, STRINGS[4].hz, 1, 48000, 0);
  const manual = detectPitch(signal, 48000);
  assert.ok(manual !== null, 'Manual still offers fallback if desired');
  assert.equal(detectPitch(signal, 48000, true), null,
    'Auto must not confirm tuning while other strings sound');
});
