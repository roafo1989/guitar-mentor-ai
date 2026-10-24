import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import ts from 'typescript';

const asModule = code => 'data:text/javascript,' + encodeURIComponent(code);
const vueUrl = import.meta.resolve('vue');
const audioUrl = asModule([
  'export const metronome = {',
  ' getState: () => globalThis.__exerciseMetronome.getState(),',
  ' stop: () => globalThis.__exerciseMetronome.stop(),',
  ' getConfig: () => globalThis.__exerciseMetronome.getConfig(),',
  ' getPendingConfig: () => null,',
  ' applyConfig: c => globalThis.__exerciseMetronome.applyConfig(c)',
  '};',
].join('\n'));

async function loadRealController() {
  const source = await readFile(new URL('../src/entities/metronome/metronome-ui.ts', import.meta.url), 'utf8');
  const output = ts.transpileModule(source, {
    compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ESNext },
  }).outputText;
  const configSource = await readFile(
    new URL('../src/shared/audio/metronome-config.ts', import.meta.url), 'utf8',
  );
  const configUrl = asModule(ts.transpileModule(configSource, {
    compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ESNext },
  }).outputText);
  const mapped = output.replace(/from (['"])(.*?)\1/g, (_match, _q, specifier) => {
    const mapping = {
      vue: vueUrl,
      '../../shared/audio/metronome': audioUrl,
      '../../shared/audio/metronome-config.ts': configUrl,
    };
    assert.ok(mapping[specifier], 'unexpected controller dependency: ' + specifier);
    return 'from ' + JSON.stringify(mapping[specifier]);
  });
  return import(asModule(mapped));
}

test('exercise launcher applies real meter, grouping, BPM and does not leak a prior pattern', async () => {
  const state = { bpm:80, status:'stopped' };
  let stops = 0;
  const applied = [];
  let config = { bpm:80, numerator:4, denominator:4, tempoUnit:'quarter', groups:[4],
    subdivision:2, masterVolumePercent:30, accentVolumes:{strong:100,medium:65,weak:35},
    subdivisionVolumePercent:20, positions:Array.from({length:4},()=>({accent:'weak',volumePercent:100})) };
  globalThis.__exerciseMetronome = {
    getState: () => ({...state}),
    getConfig: () => structuredClone(config),
    applyConfig(c) { applied.push(structuredClone(c)); config=structuredClone(c);state.bpm=c.bpm;return true; },
    stop() { stops++;state.status='stopped'; },
  };
  try {
    const ui = await loadRealController();
    const a={kind:'piece-a'}, b={kind:'piece-b'};
    assert.equal(ui.openExerciseMetronome(a,'WALTZ@1',76,'3/8'),true);
    assert.equal(config.numerator,3);
    assert.equal(config.denominator,8);
    assert.deepEqual(config.groups,[3]);
    assert.equal(config.tempoUnit,'quarter');
    assert.equal(config.bpm,76);
    assert.equal(config.masterVolumePercent,30);
    assert.equal(config.subdivision,2);
    assert.equal(stops,0);

    state.status='running';
    state.bpm=79;
    ui.minimizeMetronome();
    assert.equal(ui.openExerciseMetronome(a,'WALTZ@1',76,'3/8'),true);
    assert.equal(stops,0,'same exercise keeps playing');
    assert.equal(applied.length,1);

    ui.minimizeMetronome();
    assert.equal(ui.openExerciseMetronome(b,'SIX-EIGHT@1',88,'6/8'),true);
    assert.equal(stops,1,'different piece stops old meter');
    assert.equal(config.numerator,6);
    assert.equal(config.denominator,8);
    assert.deepEqual(config.groups,[3,3]);
    assert.equal(config.tempoUnit,'dotted-quarter');
    assert.equal(config.bpm,88);
    assert.equal(applied.length,2);

    assert.equal(ui.openExerciseMetronome(a,'BROKEN@1',80,'17/8'),false);
    assert.equal(ui.openExerciseMetronome(a,'BROKEN@2',80,'3:8'),false);
    assert.equal(ui.openExerciseMetronome(a,'BROKEN@3',241,'3/8'),false);
    assert.equal(config.numerator,6,'invalid data never silently falls back to 4/4');
    assert.equal(applied.length,2);
    assert.equal(ui.metronomeUi.sourceExercise,'SIX-EIGHT@1');
    ui.closeMetronome();
    assert.equal(stops,2);
    assert.equal(ui.metronomeUi.open,false);
  } finally {
    delete globalThis.__exerciseMetronome;
  }
});
