import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {runInNewContext} from 'node:vm';
import {MetronomeEngine} from '../src/shared/audio/metronome.ts';
import {meterConfig} from '../src/shared/audio/metronome-config.ts';

function processorHarness(sampleRate=48000) {
  const messages=[];
  let Processor;
  const environment={
    sampleRate,currentFrame:0,
    AudioWorkletProcessor:class {constructor(){this.port={postMessage:m=>messages.push(m),onmessage:null};}},
    registerProcessor(name,klass){assert.equal(name,'guitar-mentor-metronome');Processor=klass;},
  };
  runInNewContext(readFileSync(new URL('../src/shared/audio/metronome-processor.js',import.meta.url),'utf8'),environment);
  const instance=new Processor();
  const command=(type,seq,payload={})=>instance.port.onmessage({data:{
    v:1,type,seq,sessionId:12,...payload,
  }});
  const render=blocks=>{
    let peak=0;
    for(let i=0;i<blocks;i++){
      const samples=[new Float32Array(128)];
      instance.process([], [samples]);
      peak=Math.max(peak,...samples[0].map(Math.abs));
      environment.currentFrame+=128;
    }
    return peak;
  };
  return {messages,instance,command,render,environment};
}
test('render thread produces deterministic beats without JS timers at multiple meters',()=>{
  for(const [n,d,bpm,unit,sub] of [
    [4,4,20,'quarter',1],[4,4,84,'quarter',1],[6,8,120,'dotted-quarter',1],
    [7,8,240,'eighth',2],[16,16,80,'quarter',4],
  ]) {
    const h=processorHarness();
    const config=meterConfig(n,d,bpm);config.tempoUnit=unit;config.subdivision=sub;
    h.command('INIT',1,{config});h.command('START',2);
    assert.equal(h.messages[0].type,'READY');
    assert.equal(h.messages[1].type,'STARTED');
    const step=60/bpm*(4/d)/(unit==='quarter'?1:unit==='eighth'?.5:1.5);
    const samplesPerBeat=Math.round(step*48000);
    h.render(Math.ceil((samplesPerBeat*n+2000)/128));
    const beats=h.messages.filter(m=>m.type==='BEAT');
    assert.ok(beats.length>=n, n+'/'+d+' rendered '+beats.length);
    assert.deepEqual(beats.slice(0,n).map(m=>m.beat),Array.from({length:n},(_,i)=>i+1));
  }
});
test('processor preserves pending and applies latest config at the next bar',()=>{
  const h=processorHarness();
  h.command('INIT',1,{config:meterConfig(4,4,120)});h.command('START',2);
  h.render(250); // ~0.67 seconds: within first bar
  h.command('CONFIG',3,{config:meterConfig(3,4,95),revision:1});
  h.command('CONFIG',4,{config:meterConfig(7,8,95),revision:2});
  h.render(200);
  assert.equal(h.messages.filter(m=>m.type==='APPLIED').length,0);
  h.render(500);
  const applied=h.messages.filter(m=>m.type==='APPLIED');
  assert.equal(applied.length,1);
  assert.equal(applied[0].activeConfig.numerator,7);
  assert.equal(applied[0].revision,2);
});
test('BPM changes immediately, updates pending and does not consume it on reset',()=>{
  const h=processorHarness();
  h.command('INIT',1,{config:meterConfig(4,4,80)});h.command('START',2);
  h.render(100);
  h.command('CONFIG',3,{config:meterConfig(7,8,90),revision:1});
  h.command('BPM',4,{value:120});
  assert.equal(h.instance.config.bpm,120);
  assert.equal(h.instance.pending.bpm,120);
  h.render(20); // first reset beat
  assert.equal(h.instance.config.numerator,4);
  assert.equal(h.instance.pending.numerator,7);
});
test('stale session, invalid settings and sequence replay cannot alter audio',()=>{
  const h=processorHarness();
  h.command('INIT',1,{config:meterConfig()});h.command('START',2);
  h.instance.port.onmessage({data:{v:1,sessionId:11,seq:50,type:'STOP'}});
  h.instance.port.onmessage({data:{v:1,sessionId:12,seq:2,type:'STOP'}});
  h.command('MASTER',3,{value:Infinity});
  h.command('CONFIG',4,{config:{bpm:9999},revision:1});
  assert.equal(h.instance.running,true);
  assert.equal(h.instance.master,.5);
  assert.equal(h.instance.pending,null);
  assert.ok(h.render(40)>0);
  h.command('STOP',5);
  assert.equal(h.render(40),0);
});
function hostSetup(options={}) {
  const original={AudioWorkletNode:globalThis.AudioWorkletNode,document:globalThis.document,window:globalThis.window};
  const contexts=[];const nodes=[];
  const doc=new Map();const win=new Map();
  globalThis.document={visibilityState:'visible',addEventListener:(key,cb)=>doc.set(key,cb),
    removeEventListener:(key,cb)=>{if(doc.get(key)===cb)doc.delete(key);}};
  globalThis.window={addEventListener:(key,cb)=>win.set(key,cb),
    removeEventListener:(key,cb)=>{if(win.get(key)===cb)win.delete(key);}};
  globalThis.AudioWorkletNode=class {
    constructor(ctx){this.ctx=ctx;this.connected=false;this.port={
      onmessage:null,closed:false,close(){this.closed=true;},
      postMessage:m=>{
        this.commands.push(m);
        const response=m.type==='INIT'?'READY':m.type==='START'?'STARTED':null;
        if(response&&!options.noAck)queueMicrotask(()=>this.port.onmessage?.({data:{
          v:1,type:response,sessionId:m.sessionId,seq:m.seq,
        }}));
      },
    };this.commands=[];nodes.push(this);
    }
    connect(){this.connected=true;}
    disconnect(){this.connected=false;}
  };
  const engine=new MetronomeEngine({createAudioContext:()=>{
    const ctx={state:'suspended',destination:{},listeners:new Map(),closed:0,
      audioWorklet:{addModule:async url=>{assert.ok(url.includes('metronome-processor'));if(options.moduleFail)throw Error('404');}},
      addEventListener(k,cb){this.listeners.set(k,cb);},
      removeEventListener(k,cb){if(this.listeners.get(k)===cb)this.listeners.delete(k);},
      resume(){if(options.resume)return options.resume(this);this.state='running';return Promise.resolve();},
      close(){this.closed++;this.state='closed';return Promise.resolve();}};
    contexts.push(ctx);return ctx;
  }});
  return {engine,contexts,nodes,doc,win,restore:()=>{
    globalThis.AudioWorkletNode=original.AudioWorkletNode;
    globalThis.document=original.document;globalThis.window=original.window;
  }};
}
test('host keeps playing on hidden tab, requests snapshot on return and closes on pagehide',async()=>{
  const h=hostSetup();
  try{
    assert.equal(await h.engine.start(),true);
    globalThis.document.visibilityState='hidden';
    h.doc.get('visibilitychange')();
    assert.equal(h.engine.getState().status,'running');
    globalThis.document.visibilityState='visible';
    h.doc.get('visibilitychange')();
    assert.equal(h.nodes[0].commands.at(-1).type,'SNAPSHOT');
    h.win.get('pagehide')();
    assert.equal(h.engine.getState().status,'stopped');
    assert.equal(h.nodes[0].connected,false);
    assert.equal(h.nodes[0].port.closed,true);
    assert.equal(h.contexts[0].closed,1);
  }finally{h.restore();}
});
test('host rapid Stop and async resume cannot resurrect canceled session',async()=>{
  let release;
  const h=hostSetup({resume:ctx=>new Promise(resolve=>{release=()=>{ctx.state='running';resolve();};})});
  try{
    const start=h.engine.start();
    h.engine.stop();
    release();
    assert.equal(await start,false);
    assert.equal(h.engine.getState().status,'stopped');
    assert.equal(h.nodes.length,0);
  }finally{h.restore();}
});
test('host explicit error on module failure, without legacy scheduling',async()=>{
  const h=hostSetup({moduleFail:true});
  try{
    assert.equal(await h.engine.start(),false);
    assert.equal(h.engine.getState().status,'unavailable');
    assert.equal(h.contexts[0].closed,1);
  }finally{h.restore();}
});
test('host retains active/pending configuration and immediate BPM/master',async()=>{
  const h=hostSetup();
  try{
    assert.equal(await h.engine.start(),true);
    const c=meterConfig(7,8,95);
    assert.equal(h.engine.applyConfig(c),true);
    assert.equal(h.engine.getConfig().numerator,4);
    assert.equal(h.engine.getPendingConfig().numerator,7);
    h.engine.setBpm(110);h.engine.setMasterVolume(80);
    assert.equal(h.engine.getPendingConfig().bpm,110);
    assert.equal(h.engine.getConfig().masterVolumePercent,80);
    assert.deepEqual(h.nodes[0].commands.slice(-3).map(m=>m.type),['CONFIG','BPM','MASTER']);
    h.engine.stop();
    assert.equal(h.engine.getConfig().numerator,7);
  }finally{h.restore();}
});
