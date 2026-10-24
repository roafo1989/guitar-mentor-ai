import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { parse, compileTemplate } from '@vue/compiler-sfc';

async function src(path) {
  return readFile(new URL('../src/' + path,import.meta.url),'utf8');
}
test('diagnostic UI separates preview-only short probes and optional repertoire',async()=>{
  const ui=await src('entities/diagnostic/DiagnosticView.vue');
  const parsed=parse(ui,{filename:'DiagnosticView.vue'});
  assert.deepEqual(parsed.errors,[]);
  const template=compileTemplate({source:parsed.descriptor.template.content,
    filename:'DiagnosticView.vue',id:'probe-145'});
  assert.deepEqual(template.errors,[]);
  assert.match(ui,/contentKind === 'DIAGNOSTIC_PROBE'/);
  assert.match(ui,/contentKind !== 'DIAGNOSTIC_PROBE'/);
  assert.match(ui,/v-for="exercise in probes"/);
  assert.match(ui,/v-for="exercise in repertoire"/);
  assert.match(ui,/noProbes/);
});
test('preview displays short duration and carries explicit tempo unit',async()=>{
  const ui=await src('entities/diagnostic/ExercisePreview.vue');
  assert.match(ui,/durationTargetSeconds/);
  assert.match(ui,/props\.exercise\.tempoUnit/);
  assert.match(ui,/metronomeError/);
});
test('localization and backwards-compatible API contract',async()=>{
  for (const lang of ['ru','en']) {
    const l=JSON.parse(await src('i18n/'+lang+'.json'));
    for(const key of ['probesHeading','repertoireHeading','noProbes','probeLabel',
      'probeDuration','chooseProbe','repertoireHint','seconds'])
      assert.equal(typeof l.diagnostic[key],'string',lang+'.'+key);
  }
  const service=await src('entities/diagnostic/diagnostic.service.ts');
  assert.match(service,/value\.contentKind == null/);
  assert.match(service,/value\.tempoUnit == null/);
});
test('metronome retains explicit dotted-quarter versus eighth tempo unit',async()=>{
  const ui=await src('entities/metronome/metronome-ui.ts');
  assert.match(ui,/if \(tempoUnit\) requested\.tempoUnit = tempoUnit/);
});
