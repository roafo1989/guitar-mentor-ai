import test from 'node:test';
import assert from 'node:assert/strict';
import { MAX_BPM, MIN_BPM, parseMetronomeBpm, stepMetronomeBpm } from '../src/entities/metronome/metronome-input.ts';

test('BPM input accepts integer boundaries and whitespace', () => {
  assert.equal(MIN_BPM, 20);
  assert.equal(MAX_BPM, 240);
  for (const input of ['20', '80', '84', '240', ' 120 ', '080']) {
    assert.equal(parseMetronomeBpm(input), Number(input), input);
  }
});

test('invalid BPM input never changes to NaN, fractions, or out of range', () => {
  for (const input of ['', ' ', '19', '241', '999', '20.5', 'NaN', '1e2', 'Infinity', '-20', '20BPM', '1,20']) {
    assert.equal(parseMetronomeBpm(input), null, input);
  }
});

test('BPM step changes one unit and clamps to inclusive limits', () => {
  assert.equal(stepMetronomeBpm(80, 1), 81);
  assert.equal(stepMetronomeBpm(80, -1), 79);
  assert.equal(stepMetronomeBpm(20, -1), 20);
  assert.equal(stepMetronomeBpm(240, 1), 240);
});
