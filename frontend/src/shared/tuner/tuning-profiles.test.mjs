import test from 'node:test';
import assert from 'node:assert/strict';
import { TUNINGS, TUNING_IDS, TUNING_STORAGE_KEY, noteFrequency,
  readTuningPreference, saveTuningPreference } from './tuning-profiles.ts';
import { detectPitch, cents } from './pitch.ts';
import { autoStringIndex } from './tuner-session.ts';
import { tunerReading } from './tuner-reading.ts';

const expected = {
  STANDARD: ['E2','A2','D3','G3','B3','E4'],
  DROP_D: ['D2','A2','D3','G3','B3','E4'],
  OPEN_G: ['D2','G2','D3','G3','B3','D4'],
};
const sine = (hz, rate = 48000) =>
  Float32Array.from({length:8192}, (_, i) => .3 * Math.sin(2*Math.PI*hz*i/rate));
test('TOOL-03 all 18 physical target notes, octaves and A4 reference', () => {
  assert.equal(noteFrequency('A4'), 440);
  assert.ok(Math.abs(noteFrequency('D2') - 73.41619) < .001);
  assert.ok(Math.abs(noteFrequency('G2') - 97.99886) < .001);
  for (const id of TUNING_IDS) {
    const targets = TUNINGS[id].strings;
    assert.deepEqual(targets.map(s => s.note), expected[id]);
    assert.deepEqual(targets.map(s => s.number), [6,5,4,3,2,1]);
    for (const s of targets) assert.ok(Math.abs(cents(s.hz,noteFrequency(s.note))) < .001);
  }
});
test('TOOL-03 dropped D2 and Open G lower / upper notes are real DSP targets', () => {
  for (const id of TUNING_IDS) for (const rate of [44100, 48000]) {
    const strings = TUNINGS[id].strings;
    for (let i=0; i<strings.length; i++) {
      const s = strings[i];
      const measured = detectPitch(sine(s.hz,rate),rate,true);
      assert.ok(measured !== null, id + ' ' + s.note);
      assert.ok(Math.abs(cents(measured,s.hz)) < 5, id + ' ' + s.note);
      assert.equal(autoStringIndex(measured,null,strings),i, id + ' ' + s.note);
      assert.equal(tunerReading(measured,s.hz).status,'inTune');
    }
  }
});
test('TOOL-03 D2 below target still detectable down to conservative margin', () => {
  const d2=noteFrequency('D2');
  for (const detune of [-210,-150,0,120]) {
    const actual=d2*2**(detune/1200);
    const measured=detectPitch(sine(actual),48000);
    assert.ok(measured !== null, 'D2 ' + detune + 'c');
    assert.ok(Math.abs(cents(measured,actual))<6);
    const index=autoStringIndex(measured,null,TUNINGS.DROP_D.strings);
    assert.equal(index, Math.abs(detune)>175 ? null : 0);
    assert.notEqual(tunerReading(measured,d2).status,detune===0?'flat':'inTune');
  }
});
test('TOOL-03 Open G same-letter notes never alias across octaves', () => {
  const strings=TUNINGS.OPEN_G.strings;
  for (const index of [0,2,5,1,3]) {
    const note=strings[index];
    assert.equal(autoStringIndex(note.hz,null,strings), index, note.note);
  }
  assert.equal(autoStringIndex(null, 1, strings),null);
});
test('TOOL-03 safe local tuning preference schema, unknown values, blocked storage', () => {
  const old=globalThis.localStorage;
  const values=new Map();
  try {
    globalThis.localStorage={
      getItem: key=>values.get(key)??null,
      setItem: (key,val)=>values.set(key,val),
    };
    assert.equal(readTuningPreference(),'STANDARD');
    saveTuningPreference('OPEN_G');
    assert.equal(readTuningPreference(),'OPEN_G');
    assert.deepEqual(JSON.parse(values.get(TUNING_STORAGE_KEY)),{schemaVersion:1,tuningId:'OPEN_G'});
    for(const data of ['{invalid','{"schemaVersion":1,"tuningId":"EVIL"}','{"schemaVersion":2,"tuningId":"DROP_D"}']) {
      values.set(TUNING_STORAGE_KEY,data);
      assert.equal(readTuningPreference(),'STANDARD');
    }
    globalThis.localStorage={getItem:()=>{throw Error('blocked')},setItem:()=>{throw Error('blocked')}};
    assert.equal(readTuningPreference(),'STANDARD');
    assert.doesNotThrow(()=>saveTuningPreference('DROP_D'));
  } finally {
    if (old === undefined) delete globalThis.localStorage;
    else globalThis.localStorage=old;
  }
});
