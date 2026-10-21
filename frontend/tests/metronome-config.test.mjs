import test from 'node:test';
import assert from 'node:assert/strict';
import {
  meterConfig, validConfig, copyConfig, regroup, parseGroups, positionSeconds, cellGain,
  audible, TapTempo, STORE_KEY, MAX_STORE_BYTES, validStore,
  saveMetronomeStore, readMetronomeStore,
} from '../src/shared/audio/metronome-config.ts';

test('every supported meter uses denominator positions and explicit BPM unit', () => {
  const examples = [
    [4,4,'quarter',60,4],
    [3,4,'quarter',60,3],
    [6,8,'dotted-quarter',60,2],
    [7,8,'eighth',140,3],
    [16,16,'quarter',60,4],
  ];
  for (const [n,d,unit,bpm,seconds] of examples) {
    const c = meterConfig(n,d,bpm);
    c.tempoUnit = unit;
    assert.equal(validConfig(c), true);
    assert.equal(c.positions.length, n);
    assert.ok(Math.abs(n * positionSeconds(c) - seconds) < 1e-9);
  }
  const six = meterConfig(6,8,60);
  assert.deepEqual(six.groups,[3,3]);
  assert.deepEqual(six.positions.map(p=>p.accent),['strong','weak','weak','medium','weak','weak']);
  const seven = meterConfig(7,8);
  assert.deepEqual(seven.groups,[2,2,3]);
  assert.deepEqual(regroup(seven,[3,2,2]).groups,[3,2,2]);
  assert.equal(regroup(seven,[2,2]),null);
  assert.deepEqual(parseGroups(' 3 + 2 + 2 ',7),[3,2,2]);
  for (const s of ['0+7','2+2','Infinity','2+NaN','2+2+3.0','2+2+-3']) assert.equal(parseGroups(s,7),null);
});

test('validation blocks external malformed data and prevents aliasing', () => {
  const initial = meterConfig();
  const copy = copyConfig(initial);
  copy.positions[0].accent='mute';
  copy.groups[0]=2;
  assert.equal(initial.positions[0].accent,'strong');
  assert.deepEqual(initial.groups,[4]);
  for (const mutate of [
    c=>{c.numerator=0;}, c=>{c.denominator=3;}, c=>{c.bpm=Infinity;},
    c=>{c.groups=[3];}, c=>{c.groups=[2,0,2];}, c=>{c.positions.pop();},
    c=>{c.positions[0].accent='loud';}, c=>{c.positions[1].volumePercent=101;},
    c=>{c.subdivision=5;}, c=>{c.tempoUnit='hidden';}, c=>{c.accentVolumes.medium=-1;},
    c=>{c.extra='unexpected';},
  ]) {
    const candidate = copyConfig(initial);
    mutate(candidate);
    assert.equal(validConfig(candidate),false);
  }
  assert.throws(()=>meterConfig(17,4));
  assert.throws(()=>meterConfig(4,3));
});

test('position and subdivision gain are independent, mute consumes time', () => {
  const c=meterConfig(4,4);
  assert.equal(cellGain(c,0),1);
  assert.equal(cellGain(c,1),.35);
  c.positions[1].volumePercent=20;
  assert.equal(cellGain(c,1),.07);
  c.subdivision=4;
  assert.equal(cellGain(c,1,true),.05);
  c.positions[1].accent='mute';
  assert.equal(cellGain(c,1),0);
  assert.equal(cellGain(c,1,true),0);
  c.positions.forEach(p=>p.accent='mute');
  assert.equal(audible(c),false);
  c.positions[0].accent='strong';
  c.masterVolumePercent=0;
  assert.equal(audible(c),false);
});

test('Tap Tempo uses median of up to eight intervals, resets after pause and rejects implausible values', () => {
  const tap=new TapTempo();
  assert.equal(tap.tap(0).bpm,null);
  tap.tap(500); tap.tap(1000);
  assert.equal(tap.tap(1500).bpm,120);
  assert.equal(tap.tap(2000).bpm,120);
  tap.tap(2800); tap.tap(3300);
  assert.equal(tap.tap(3800).bpm,120, 'a stray slow tap does not bias the median');
  assert.equal(tap.tap(9000).count,1);
  tap.tap(9050); tap.tap(9100);
  const invalid=tap.tap(9150);
  assert.equal(invalid.bpm,null);
  assert.equal(invalid.outOfRange,true);
  tap.reset(); assert.equal(tap.tap(0).count,1);
});

test('local storage validates envelope, bounded UTF-8 and quota errors without deleting data', () => {
  const old=Object.getOwnPropertyDescriptor(globalThis,'localStorage');
  const map=new Map();
  Object.defineProperty(globalThis,'localStorage',{configurable:true,value:{
    getItem:k=>map.get(k)??null,
    setItem:(k,v)=>map.set(k,v),
  }});
  try {
    const config=meterConfig();
    const store={schemaVersion:1,currentConfig:config,presets:[
      {id:'a',name:'7/8 groups',config:meterConfig(7,8)},
    ]};
    assert.equal(validStore(store),true);
    assert.equal(saveMetronomeStore(store),true);
    assert.equal(readMetronomeStore().store.presets.length,1);
    map.set(STORE_KEY,'{broken');
    assert.deepEqual(readMetronomeStore(),{store:null,error:true});
    assert.equal(map.get(STORE_KEY),'{broken','invalid data is preserved for recovery');
    map.set(STORE_KEY,'🎸'.repeat(MAX_STORE_BYTES/2));
    assert.equal(readMetronomeStore().error,true,'oversize UTF-8 is rejected before parsing');
    map.set(STORE_KEY,JSON.stringify({...store,schemaVersion:2}));
    assert.equal(readMetronomeStore().error,true);
    const prev=map.get(STORE_KEY);
    Object.defineProperty(globalThis,'localStorage',{configurable:true,value:{
      getItem:k=>map.get(k)??null,
      setItem:()=>{throw new Error('QuotaExceededError');},
    }});
    assert.equal(saveMetronomeStore(store),false);
    assert.equal(map.get(STORE_KEY),prev);
  } finally {
    if(old)Object.defineProperty(globalThis,'localStorage',old); else delete globalThis.localStorage;
  }
});
