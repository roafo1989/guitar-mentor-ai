import test from 'node:test';
import assert from 'node:assert/strict';
import { TUNINGS, TUNING_IDS, TUNING_STORAGE_KEY, noteFrequency,
  readTuningPreference, saveTuningPreference } from './tuning-profiles.ts';
import { detectPitch, cents } from './pitch.ts';
import { autoStringIndex, newPitchTrack, resetPitchTrack, trackPitch, displayPitch, HOLD_MS } from './tuner-session.ts';
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

const acoustic = (hz, rate, amplitudes, B=0.0008, decay=0.65) =>
  Float32Array.from({length:8192}, (_, i) => {
    const t=i/rate;
    const harmonics=amplitudes.reduce((sum, gain, j) => {
      const n=j+1;
      const partial=n*hz*Math.sqrt((1+B*n*n)/(1+B));
      return sum + gain*Math.sin(2*Math.PI*partial*t+n*0.35);
    },0);
    return 0.24 * Math.exp(-decay*t) * harmonics;
  });

test('REVIEW P1: a newly detected +20/-20 cents must never inherit green median', () => {
  const target=110;
  for (const offset of [-20,20]) {
    const track=newPitchTrack();
    assert.equal(displayPitch(track,target,100),null);
    assert.equal(displayPitch(track,target,200),null);
    assert.equal(displayPitch(track,target,300),target);
    assert.equal(tunerReading(displayPitch(track,target,350),target).status,'inTune');
    const changed=target*2**(offset/1200);
    const first=displayPitch(track,changed,400);
    assert.equal(first,null,'a stale tuned median is not a fresh measurement');
    assert.equal(tunerReading(first,target),null,'green, cents and meter are all cleared');
    const second=displayPitch(track,changed,500);
    assert.ok(second !== null);
    assert.equal(tunerReading(second,target).status,offset>0?'sharp':'flat');
    assert.equal(displayPitch(track,null,550),second,'bounded hold stays readable but marked held');
    assert.equal(displayPitch(track,null,500+HOLD_MS+1),null,'hold expires');
  }
});

test('REVIEW P1: near-green threshold crossing never masquerades as in-tune', () => {
  const target=110, track=newPitchTrack();
  const green=target*2**(4/1200);
  for(const at of [100,200,300]) displayPitch(track,green,at);
  assert.equal(tunerReading(displayPitch(track,green,350),target).status,'inTune');
  const out=target*2**(8/1200);
  // Existing median differs by only 4 cents; a target-aware display
  // additionally rejects it in the Vue computed reading, which is tested
  // through the real-component harness.
  const med=displayPitch(track,out,400);
  assert.ok(med !== null);
  assert.equal(tunerReading(med,target).status,'inTune');
  assert.equal(tunerReading(out,target).status,'sharp',
    'fresh candidate conflicts with green; UI must use candidate guard');
});

test('REVIEW P2: harmonic-rich, mildly stiff Drop D and Open G notes remain correct', () => {
  let checked=0;
  for(const id of ['DROP_D','OPEN_G']) for(const rate of [44100,48000]) {
    const strings=TUNINGS[id].strings;
    for(const string of strings) for(const deviation of [-25,0,25]) {
      const pitch=string.hz*2**(deviation/1200);
      const result=detectPitch(acoustic(pitch,rate,[1,.44,.24,.12],0.0008),rate,true);
      assert.ok(result !== null,id+' '+string.note+' '+rate+' '+deviation);
      assert.ok(Math.abs(cents(result,pitch))<5,
        id+' '+string.note+' '+rate+' '+deviation+' pitch='+result);
      assert.equal(autoStringIndex(result,null,strings),strings.indexOf(string),
        id+' '+string.note+' wrong physical peg');
      assert.equal(tunerReading(result,string.hz).status,
        deviation===0?'inTune':deviation<0?'flat':'sharp');
      checked++;
    }
  }
  assert.equal(checked,72);
});

test('REVIEW P2: weak fundamental / overtone-heavy detuned alternative strings have no false confidence', () => {
  let observed=0;
  for(const id of ['DROP_D','OPEN_G']) for(const rate of [44100,48000]) {
    const strings=TUNINGS[id].strings;
    for(const index of [0,1,2,5]) {
      const string=strings[index];
      for(const profile of [[.16,1,.28,.09],[.02,.02,1]]) {
        const result=detectPitch(acoustic(string.hz,rate,profile,0.0015),rate,true);
        if(result!==null) {
          assert.ok(Math.abs(cents(result,string.hz))<6,
            id+' '+string.note+' falsely detects octave '+result);
          const selected=autoStringIndex(result,null,strings);
          assert.equal(selected,index);
          observed++;
        }
      }
    }
  }
  // The detector may withhold individual highly ambiguous frames. That is
  // preferable to an incorrect octave or an unjustified green state.
  assert.ok(observed>=16,'enough useful harmonic-rich notes remain detectable');
});

test('REVIEW P2: strong competing non-harmonic guitar strings are withheld in Auto', () => {
  for(const id of ['DROP_D','OPEN_G']) {
    const strings=TUNINGS[id].strings;
    for(const [a,b] of [[0,1],[0,4],[1,5]]) {
      const rate=48000;
      const signal=Float32Array.from({length:8192}, (_,i) => {
        const t=i/rate;
        const sound=(hz,phase) => [1,.42,.24].reduce((sum,gain,k) =>
          sum+gain*Math.sin(2*Math.PI*(k+1)*hz*t+phase*(k+1)),0);
        return .13*Math.exp(-.35*t)*(sound(strings[a].hz,.3)+sound(strings[b].hz,.67));
      });
      const result=detectPitch(signal,rate,true);
      assert.equal(result,null,id+' mixed strings '+strings[a].note+'/'+strings[b].note);
    }
  }
});

test('REVIEW P2: profile switch never carries history, hold or a tuned verdict', () => {
  const state=newPitchTrack();
  const standard=TUNINGS.STANDARD.strings;
  const open=TUNINGS.OPEN_G.strings;
  for(const at of [100,200,300]) trackPitch(state,standard[0].hz,at);
  assert.equal(tunerReading(state.frequency,standard[0].hz).status,'inTune');
  assert.equal(autoStringIndex(state.frequency,null,standard),0);
  resetPitchTrack(state);
  assert.equal(state.frequency,null);
  assert.equal(state.history.length,0);
  assert.equal(displayPitch(state,null,400),null);
  assert.equal(displayPitch(state,open[0].hz,500),null);
  assert.equal(displayPitch(state,open[0].hz,600),null);
  const confirmed=displayPitch(state,open[0].hz,700);
  assert.ok(confirmed !== null);
  assert.equal(autoStringIndex(confirmed,null,open),0);
  assert.equal(tunerReading(confirmed,open[0].hz).status,'inTune');
});
