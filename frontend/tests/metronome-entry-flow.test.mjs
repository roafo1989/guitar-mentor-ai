import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { meterConfig, validConfig } from '../src/shared/audio/metronome-config.ts';

// Contract test: verify the exercise is passed through to the controller
// and real (not stubbed) meter defaults are valid for the supported rhythms.
test('exercise preview passes both the metronome BPM and meter', async () => {
  const preview = await readFile(new URL('../src/entities/diagnostic/ExercisePreview.vue', import.meta.url),'utf8');
  assert.match(preview,/props\.exercise\.bpm,\s*props\.exercise\.meter/);
  assert.match(preview,/metronomeError\.value = !openExerciseMetronome/);
});

test('controller applies actual meter and refuses invalid input without fallback', async () => {
  const source = await readFile(new URL('../src/entities/metronome/metronome-ui.ts', import.meta.url),'utf8');
  assert.match(source,/meterConfig\(numerator, denominator, bpm\)/);
  assert.match(source,/numerator < 1 \|\| numerator > 16/);
  assert.match(source,/sourceExercise === source/);
  assert.match(source,/metronome\.stop\(\)/);
  assert.doesNotMatch(source,/requested\.tempoUnit = 'quarter'/);
});

test('real metronome configuration supports 3/8, 4/4 and compound 6/8', () => {
  for(const [n,d,bpm,groups,unit] of [
    [3,8,76,[3],'quarter'],
    [4,4,84,[4],'quarter'],
    [6,8,88,[3,3],'dotted-quarter'],
    [7,8,90,[2,2,3],'quarter'],
  ]) {
    const config = meterConfig(n,d,bpm);
    assert.equal(validConfig(config),true);
    assert.equal(config.numerator,n);
    assert.equal(config.denominator,d);
    assert.equal(config.bpm,bpm);
    assert.deepEqual(config.groups,groups);
    assert.equal(config.tempoUnit,unit);
    assert.equal(config.positions[0].accent,'strong');
  }
});
