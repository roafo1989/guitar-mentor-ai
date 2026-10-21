import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import ts from 'typescript';

const asModule = code => 'data:text/javascript,' + encodeURIComponent(code);
const vueUrl = import.meta.resolve('vue');
const audioUrl = asModule([
  'export const metronome = {',
  '  getState: () => globalThis.__exerciseMetronome.getState(),',
  '  stop: () => globalThis.__exerciseMetronome.stop(),',
  '  getConfig: () => globalThis.__exerciseMetronome.getConfig(),',
  '  getPendingConfig: () => null,',
  '  applyConfig: c => globalThis.__exerciseMetronome.applyConfig(c),',
  '  setBpm: bpm => globalThis.__exerciseMetronome.setBpm(bpm)',
  '};',
].join('\n'));

async function loadRealController() {
  const source = await readFile(new URL('../src/entities/metronome/metronome-ui.ts', import.meta.url), 'utf8');
  const output = ts.transpileModule(source, {
    compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ESNext },
  }).outputText;
  const mapped = output.replace(/from (['"])(.*?)\1/g, (_match, _q, specifier) => {
    const mapping = {
      vue: vueUrl,
      '../../shared/audio/metronome': audioUrl,
      '../../shared/audio/metronome-config.ts': asModule('export const copyConfig=c=>structuredClone(c); export const defaultMetronomeConfig=()=>({bpm:80}); export const readMetronomeStore=()=>({store:null,error:false}); export const saveMetronomeStore=()=>true;'),
    };
    assert.ok(mapping[specifier], 'unexpected controller dependency: ' + specifier);
    return 'from ' + JSON.stringify(mapping[specifier]);
  });
  return import(asModule(mapped));
}

test('real exercise/controller entries keep existing playback/BPM and do not reset scheduling', async () => {
  const state = { bpm: 80, status: 'stopped' };
  let stops = 0;
  const appliedBpms = [];
  globalThis.__exerciseMetronome = {
    getState: () => ({ ...state }),
    getConfig: () => ({ bpm:state.bpm }),
    applyConfig: () => true,
    stop() { stops++; state.status = 'stopped'; },
    setBpm(bpm) {
      appliedBpms.push(bpm);
      if (!Number.isInteger(bpm) || bpm < 20 || bpm > 240) return false;
      state.bpm = bpm;
      return true;
    },
  };
  try {
    const ui = await loadRealController();
    const header = { kind: 'header' };
    const exercise = { kind: 'exercise-button' };
    const exerciseB = { kind: 'exercise-b' };

    ui.openExerciseMetronome(exercise, 'GM-EB01@0.8B', 84);
    assert.equal(ui.metronomeUi.open, true);
    assert.equal(ui.metronomeUi.sourceExercise, 'GM-EB01@0.8B');
    assert.equal(state.bpm, 84, 'stopped engine adopts recommended exercise tempo');
    assert.deepEqual(appliedBpms, [84]);
    assert.equal(stops, 0, 'opening never invokes an unnecessary stop');

    state.status = 'running';
    state.bpm = 90; // student adjusts live tempo from the controls
    ui.minimizeMetronome();
    assert.equal(ui.metronomeUi.open, false);
    assert.equal(state.status, 'running');
    ui.openExerciseMetronome(exercise, 'GM-EB01@0.8B', 84);
    assert.equal(ui.metronomeUi.open, true);
    assert.equal(ui.metronomeUi.returnFocus.kind, exercise.kind, 'focus target remains the exercise opener');
    assert.equal(state.bpm, 90, 'reopen from task does not replace the chosen tempo');
    assert.equal(state.status, 'running', 'ongoing click must continue');
    assert.equal(stops, 0, 'reopening does not stop the audio graph');
    assert.deepEqual(appliedBpms, [84], 'reopening does not even reset the beat scheduler via setBpm');

    ui.minimizeMetronome();
    ui.openMetronome(header);
    assert.equal(ui.metronomeUi.open, true);
    assert.equal(state.status, 'running');
    assert.equal(state.bpm, 90);
    assert.equal(stops, 0, 'opening the header control is not an implicit Stop');

    ui.minimizeMetronome();
    ui.openExerciseMetronome(exerciseB, 'GM-EB02@1', 72);
    assert.equal(state.bpm, 90, 'other exercise entry also preserves running tempo');
    assert.equal(stops, 0);
    assert.deepEqual(appliedBpms, [84]);
    assert.equal(ui.metronomeUi.sourceExercise, 'GM-EB02@1');

    state.status = 'starting';
    ui.openExerciseMetronome(exercise, 'GM-EB01@0.8B', 84);
    assert.equal(state.status, 'starting', 'do not cancel an in-flight asynchronous Start');
    assert.equal(stops, 0);
    assert.deepEqual(appliedBpms, [84]);

    ui.closeMetronome();
    assert.equal(stops, 1, 'explicit close still stops audio');
    assert.equal(ui.metronomeUi.open, false);
    ui.openExerciseMetronome(exercise, 'GM-EB01@0.8B', 84);
    assert.equal(state.bpm, 84, 'recommendation applies on a newly stopped engine');
    assert.deepEqual(appliedBpms, [84, 84]);
    ui.closeMetronome();
  } finally {
    delete globalThis.__exerciseMetronome;
  }
});
