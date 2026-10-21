import test from 'node:test';
import assert from 'node:assert/strict';
import { MetronomeEngine, metronome } from '../src/shared/audio/metronome.ts';

class FakeClock {
  now = 0;
  id = 0;
  tasks = new Map();
  original = {};

  install() {
    for (const key of ['setInterval', 'clearInterval', 'setTimeout', 'clearTimeout']) {
      this.original[key] = globalThis[key];
    }
    globalThis.setInterval = (fn, delay) => this.add(fn, delay, true);
    globalThis.setTimeout = (fn, delay) => this.add(fn, delay, false);
    globalThis.clearInterval = globalThis.clearTimeout = id => { this.tasks.delete(id); };
  }

  add(fn, delay, repeating) {
    const id = ++this.id;
    this.tasks.set(id, { fn, at: this.now + delay, delay, repeating });
    return id;
  }

  advance(ms) {
    const end = this.now + ms;
    for (let steps = 0; steps < 5000; steps++) {
      const next = [...this.tasks.entries()]
        .filter(([, task]) => task.at <= end)
        .sort((a, b) => a[1].at - b[1].at)[0];
      if (!next) break;
      const [id, task] = next;
      this.now = task.at;
      if (task.repeating) task.at += task.delay;
      else this.tasks.delete(id);
      task.fn();
    }
    this.now = end;
  }

  restore() {
    for (const [key, fn] of Object.entries(this.original)) globalThis[key] = fn;
  }
}

function audioFactory(clock, contexts, options = {}) {
  return () => {
    if (options.failConstruct) throw new Error('no audio hardware');
    const context = {
      state: 'suspended',
      destination: {},
      closed: 0,
      oscillators: [],
      gains: [],
      listeners: new Map(),
      addEventListener(name, fn) { this.listeners.set(name, fn); },
      removeEventListener(name, fn) { if (this.listeners.get(name) === fn) this.listeners.delete(name); },
      get currentTime() { return clock.now / 1000; },
      resume() {
        if (options.resume) return options.resume(this);
        this.state = 'running';
        return Promise.resolve();
      },
      close() {
        this.closed++;
        if (options.close) return options.close(this);
        this.state = 'closed';
        return Promise.resolve();
      },
      createOscillator() {
        if (options.failOscillator) throw new Error('audio device error');
        const osc = {
          type: '', starts: [], stops: [], onended: null, disconnected: false,
          frequency: { setValueAtTime: (freq, at) => { osc.freq = freq; osc.at = at; } },
          connect: () => {}, disconnect() { this.disconnected = true; },
          start(at) { this.starts.push(at); }, stop(at) { this.stops.push(at); },
        };
        this.oscillators.push(osc);
        return osc;
      },
      createGain() {
        const gain = {
          value:1, events:[],
          setValueAtTime(value, at) { this.events.push({ type:'set', value, at }); this.value=value; },
          exponentialRampToValueAtTime(value, at) { this.events.push({ type:'exponential', value, at }); this.value=value; },
          linearRampToValueAtTime(value, at) { this.events.push({ type:'linear', value, at }); this.value=value; },
          cancelScheduledValues(at) { this.events=this.events.filter(event => event.at < at); },
        };
        const node = { gain, connect: () => {}, disconnect: () => {} };
        this.gains.push(node);
        return node;
      },
    };
    contexts.push(context);
    return context;
  };
}

function withFakeBrowser(clock) {
  const previous = { document: globalThis.document, window: globalThis.window };
  const documentListeners = new Map();
  const windowListeners = new Map();
  globalThis.document = {
    visibilityState: 'visible',
    addEventListener: (name, callback) => documentListeners.set(name, callback),
    removeEventListener: (name, callback) => {
      if (documentListeners.get(name) === callback) documentListeners.delete(name);
    },
  };
  globalThis.window = {
    addEventListener: (name, callback) => windowListeners.set(name, callback),
    removeEventListener: (name, callback) => {
      if (windowListeners.get(name) === callback) windowListeners.delete(name);
    },
  };
  return {
    documentListeners, windowListeners,
    restore() { globalThis.document = previous.document; globalThis.window = previous.window; clock.restore(); },
  };
}

function setup(options = {}) {
  const clock = new FakeClock();
  clock.install();
  const browser = withFakeBrowser(clock);
  const contexts = [];
  const engine = new MetronomeEngine({ createAudioContext: audioFactory(clock, contexts, options) });
  return { clock, contexts, engine, ...browser };
}

test('no autoplay, validated integer BPM, defensive state and subscription', async () => {
  const env = setup();
  try {
    assert.equal(metronome.getState().status, 'ready');
    assert.equal(env.contexts.length, 0);
    const received = [];
    const unsubscribe = env.engine.subscribe(state => received.push(state));
    assert.equal(received[0].bpm, 80);
    assert.equal(env.engine.setBpm(19), false);
    assert.equal(env.engine.setBpm(240.5), false);
    assert.equal(env.engine.setBpm(Number.NaN), false);
    assert.equal(env.engine.setBpm(20), true);
    assert.equal(env.engine.getState().bpm, 20);
    received.at(-1).bpm = 100;
    assert.equal(env.engine.getState().bpm, 20);
    unsubscribe();
    env.engine.dispose();
  } finally { env.restore(); }
});

test('beats 1-2-3-4 and accent are scheduled at 20, 84 and 240 BPM', async () => {
  for (const bpm of [20, 84, 240]) {
    const env = setup();
    try {
      env.engine.setBpm(bpm);
      const beats = [];
      env.engine.subscribe(s => { if (s.beat !== null && s.status === 'running') beats.push(s.beat); });
      assert.equal(await env.engine.start(), true);
      env.clock.advance(4.2 * 60000 / bpm);
      const oscillators = env.contexts[0].oscillators;
      assert.ok(oscillators.length >= 4, 'at least four clicks at ' + bpm + ' BPM');
      assert.deepEqual(oscillators.slice(0, 4).map(o => o.freq), [1150, 760, 760, 760]);
      for (let i = 1; i < 4; i++) {
        assert.ok(Math.abs(oscillators[i].at - oscillators[i - 1].at - 60 / bpm) < 1e-8);
      }
      assert.deepEqual(beats.slice(0, 4), [1, 2, 3, 4]);
      env.engine.stop();
      assert.equal(env.contexts[0].closed, 1);
      assert.equal(env.clock.tasks.size, 0);
    } finally { env.restore(); }
  }
});

test('repeated start creates one context; live BPM reset cancels scheduled clicks', async () => {
  const env = setup();
  try {
    const [first, second] = await Promise.all([env.engine.start(), env.engine.start()]);
    assert.equal(first, true); assert.equal(second, true);
    assert.equal(env.contexts.length, 1);
    assert.equal(env.engine.getState().status, 'running');
    const previous = env.contexts[0].oscillators;
    assert.ok(previous.length >= 1);
    assert.equal(env.engine.setBpm(240), true);
    assert.equal(env.engine.getState().bpm, 240);
    assert.ok(previous[0].disconnected, 'old click disconnected');
    assert.ok(previous[0].stops.length >= 2, 'old click canceled immediately');
    env.clock.advance(800);
    const created = env.contexts[0].oscillators;
    assert.ok(created.length >= 4);
    assert.equal(created[1].freq, 1150, 'first click at new tempo has accent');
    assert.equal(env.contexts.length, 1);
    env.engine.stop();
    assert.equal(env.engine.getState().beat, null);
    assert.equal(env.clock.tasks.size, 0);
  } finally { env.restore(); }
});

test('stop, visibility hidden, pagehide and dispose release audio and timers', async () => {
  const env = setup();
  try {
    await env.engine.start();
    assert.equal(env.documentListeners.size, 1);
    assert.equal(env.windowListeners.size, 1);
    globalThis.document.visibilityState = 'hidden';
    env.documentListeners.get('visibilitychange')();
    assert.equal(env.engine.getState().status, 'stopped');
    assert.equal(env.clock.tasks.size, 0);
    assert.equal(env.documentListeners.size, 0);
    assert.equal(env.windowListeners.size, 0);
    assert.equal(env.contexts[0].closed, 1);
    globalThis.document.visibilityState = 'visible';
    await env.engine.start();
    env.windowListeners.get('pagehide')();
    assert.equal(env.engine.getState().status, 'stopped');
    assert.equal(env.contexts[1].closed, 1);
    await env.engine.start();
    env.engine.dispose();
    assert.equal(env.contexts[2].closed, 1);
    assert.equal(env.clock.tasks.size, 0);
  } finally { env.restore(); }
});

test('Web Audio missing / failed output goes unavailable without throwing; can retry', async () => {
  const env = setup({ failConstruct: true });
  try {
    assert.equal(await env.engine.start(), false);
    assert.deepEqual(env.engine.getState(), { status: 'unavailable', bpm: 80, beat: null, error: 'web-audio-unavailable' });
    assert.equal(env.clock.tasks.size, 0);
    env.engine.stop();
    assert.equal(env.engine.getState().status, 'stopped');
  } finally { env.restore(); }
  const fail = setup({ failOscillator: true });
  try {
    assert.equal(await fail.engine.start(), false);
    assert.equal(fail.engine.getState().status, 'unavailable');
    assert.equal(fail.contexts[0].closed, 1);
    assert.equal(fail.clock.tasks.size, 0);
  } finally { fail.restore(); }
});

test('stop during async resume does not restart later or block a new start', async () => {
  let release;
  const deferred = new Promise(resolve => { release = resolve; });
  let counter = 0;
  const env = setup({ resume(ctx) {
    if (++counter === 1) return deferred.then(() => { ctx.state = 'running'; });
    ctx.state = 'running'; return Promise.resolve();
  } });
  try {
    const pending = env.engine.start();
    env.engine.stop();
    assert.equal(env.engine.getState().status, 'stopped');
    assert.equal(await env.engine.start(), true);
    release();
    assert.equal(await pending, false);
    assert.equal(env.contexts.length, 2);
    assert.equal(env.engine.getState().status, 'running');
    env.engine.stop();
    assert.equal(env.clock.tasks.size, 0);
  } finally { env.restore(); }
});

test('delayed scheduler skips stale beats instead of producing a catch-up burst', async () => {
  const env = setup();
  try {
    assert.equal(await env.engine.start(), true);
    env.clock.now += 10_000; // emulate a severely throttled background scheduler
    const interval = [...env.clock.tasks.values()].find(t => t.repeating);
    interval.fn();
    assert.ok(env.contexts[0].oscillators.length <= 2);
    env.engine.stop();
    assert.equal(env.clock.tasks.size, 0);
  } finally { env.restore(); }
});


test('pagehide while resume is pending cancels startup before any sound', async () => {
  let release;
  const deferred = new Promise(resolve => { release = resolve; });
  const env = setup({ resume(ctx) { return deferred.then(() => { ctx.state = 'running'; }); } });
  try {
    const pending = env.engine.start();
    assert.equal(env.windowListeners.has('pagehide'), true);
    env.windowListeners.get('pagehide')();
    release();
    assert.equal(await pending, false);
    assert.equal(env.engine.getState().status, 'stopped');
    assert.equal(env.contexts[0].closed, 1);
    assert.equal(env.contexts[0].oscillators.length, 0);
    assert.equal(env.clock.tasks.size, 0);
  } finally { env.restore(); }
});


test('suspended/interrupted output becomes unavailable; no automatic restart', async () => {
  const env = setup();
  try {
    assert.equal(await env.engine.start(), true);
    const context = env.contexts[0];
    context.state = 'suspended';
    context.listeners.get('statechange')();
    assert.equal(env.engine.getState().status, 'unavailable');
    assert.equal(env.engine.getState().beat, null);
    assert.equal(env.engine.getState().error, 'web-audio-unavailable');
    assert.equal(context.closed, 1);
    assert.equal(env.clock.tasks.size, 0);
    env.clock.advance(1000);
    assert.equal(env.contexts.length, 1, 'interruption cannot restart audio');
    assert.equal(await env.engine.start(), true, 'explicit restart can retry');
    const second = env.contexts[1];
    second.state = 'interrupted';
    const interval = [...env.clock.tasks.values()].find(t => t.repeating);
    interval.fn(); // fallback if browser did not dispatch statechange
    assert.equal(env.engine.getState().status, 'unavailable');
    assert.equal(second.closed, 1);
    assert.equal(env.clock.tasks.size, 0);
  } finally { env.restore(); }
});

test('rapid stop/start waits for previous close and never stacks contexts', async () => {
  const waiters = [];
  const env = setup({ close(ctx) {
    return new Promise(resolve => waiters.push(() => { ctx.state = 'closed'; resolve(); }));
  } });
  try {
    assert.equal(await env.engine.start(), true);
    env.engine.stop();
    assert.equal(waiters.length, 1);
    const next = env.engine.start();
    const duplicate = env.engine.start();
    assert.equal(env.contexts.length, 1, 'cannot allocate while close pending');
    waiters.shift()();
    assert.equal(await next, true);
    assert.equal(await duplicate, true);
    assert.equal(env.contexts.length, 2);
    assert.equal(env.contexts[0].state, 'closed');
    env.engine.stop();
    waiters.shift()();
    await Promise.resolve();
    assert.equal(env.clock.tasks.size, 0);
  } finally { env.restore(); }
});

test('stop during pending close cancels queued start without creating a context', async () => {
  let release;
  const env = setup({ close(ctx) {
    return new Promise(resolve => { release = () => { ctx.state = 'closed'; resolve(); }; });
  } });
  try {
    await env.engine.start();
    env.engine.stop();
    const pending = env.engine.start();
    env.engine.stop();
    release();
    assert.equal(await pending, false);
    assert.equal(env.contexts.length, 1);
    assert.equal(env.engine.getState().status, 'stopped');
    assert.equal(env.clock.tasks.size, 0);
  } finally { env.restore(); }
});

test('malformed listener cannot alter next subscriber state', async () => {
  const env = setup();
  try {
    const observed = [];
    env.engine.subscribe(state => { state.bpm = 201; state.beat = 4; });
    env.engine.subscribe(state => observed.push(state));
    assert.equal(env.engine.setBpm(84), true);
    assert.equal(observed.at(-1).bpm, 84);
    assert.equal(observed.at(-1).beat, null);
    assert.equal(env.engine.getState().bpm, 84);
  } finally { env.restore(); }
});


test('TOOL-02 schedules 100 bars per meter on the audio clock, including 16/16 subdivision 4', async () => {
  const { meterConfig, positionSeconds } = await import('../src/shared/audio/metronome-config.ts');
  const cases = [[4,4,'quarter',60,1], [3,4,'quarter',60,1],
    [6,8,'dotted-quarter',60,1], [7,8,'eighth',140,1],
    [16,16,'quarter',80,4]];
  for (const [n,d,unit,bpm,sub] of cases) {
    const env=setup();
    try {
      const config=meterConfig(n,d,bpm);
      config.tempoUnit=unit;
      config.subdivision=sub;
      assert.equal(env.engine.applyConfig(config),true);
      assert.equal(await env.engine.start(),true);
      const barMillis=n*positionSeconds(config)*1000;
      // Chunks avoid the fake timer simulator's 5000-step per-call guard.
      for (let bar=0;bar<100;bar++) env.clock.advance(barMillis);
      const clicks=env.contexts[0].oscillators;
      const expected=100*n*sub;
      assert.ok(clicks.length>=expected, n+'/'+d+' scheduled '+clicks.length+'/'+expected);
      const interval=positionSeconds(config)/sub;
      for(let i=0;i<expected;i++) {
        assert.ok(Math.abs(clicks[i].at-(0.03+i*interval))<=1e-6,
          n+'/'+d+' unexpected scheduled click '+i+' at '+clicks[i].at);
      }
      env.engine.stop();
      assert.equal(env.clock.tasks.size,0,'no timers survive Stop');
    } finally { env.restore(); }
  }
});

test('TOOL-02 queued meter/preset swaps once at bar line; live BPM updates queued tempo too', async () => {
  const { meterConfig } = await import('../src/shared/audio/metronome-config.ts');
  const env=setup();
  try {
    assert.equal(await env.engine.start(),true);
    env.clock.advance(1100); // after 4/4's first quarter, before first bar ends
    const preset=meterConfig(7,8,95);
    preset.tempoUnit='eighth';
    preset.groups=[3,2,2];
    assert.equal(env.engine.applyConfig(preset),true);
    assert.equal(env.engine.getConfig().numerator,4,'current bar is unchanged');
    assert.equal(env.engine.getPendingConfig().numerator,7);
    assert.equal(env.engine.getPendingConfig().bpm,95,'composite preset retains its BPM');
    env.clock.advance(2200); // cross default 4/4 at 80 BPM: 3.03s bar line
    assert.equal(env.engine.getConfig().numerator,7);
    assert.equal(env.engine.getConfig().bpm,95);
    const next=meterConfig(6,8,82);
    assert.equal(env.engine.applyConfig(next),true);
    assert.equal(env.engine.setBpm(90),true);
    assert.equal(env.engine.getState().bpm,90);
    assert.equal(env.engine.getPendingConfig().bpm,90,'new tap BPM cannot be reverted by old pending');
    env.clock.advance(7500);
    assert.equal(env.engine.getConfig().numerator,6);
    assert.equal(env.engine.getConfig().bpm,90);
    env.engine.stop();
    assert.equal(env.clock.tasks.size,0);
  } finally { env.restore(); }
});

test('TOOL-02 all-muted beat cells still count; per-position gain and master zero remain safe', async () => {
  const { meterConfig } = await import('../src/shared/audio/metronome-config.ts');
  const env=setup();
  try {
    const c=meterConfig(4,4,120);
    c.subdivision=4;
    c.positions[1].accent='mute';
    c.positions[2].volumePercent=0;
    assert.equal(env.engine.applyConfig(c),true);
    const heard=[];
    env.engine.subscribe(s=>{ if(s.status==='running' && s.beat!==null) heard.push(s.beat); });
    assert.equal(await env.engine.start(),true);
    env.clock.advance(2100);
    assert.deepEqual(heard.slice(0,4),[1,2,3,4]);
    const at=env.contexts[0].oscillators.map(o=>o.at);
    assert.ok(!at.some(t=>t>=.53 && t<1.03), 'mute removes entire subdivided second beat');
    assert.ok(!at.some(t=>t>=1.03 && t<1.53), 'volume zero removes third beat');
    assert.equal(env.engine.setMasterVolume(0),true);
    assert.equal(env.engine.getConfig().masterVolumePercent,0);
    env.engine.stop();
  } finally { env.restore(); }
});

test('TOOL-02 lookahead: last request wins at the imminent bar, not the next one', async () => {
  const { meterConfig } = await import('../src/shared/audio/metronome-config.ts');
  const env=setup();
  try {
    assert.equal(await env.engine.start(),true);
    env.clock.advance(2940); // next bar begins at 3.03s (4/4, 80 quarter BPM)
    const p1=meterConfig(3,4,70);
    assert.equal(env.engine.applyConfig(p1),true);
    env.clock.advance(35); // the scheduler has planned the next bar inside its 100ms lookahead
    assert.equal(env.engine.getConfig().numerator,4,'audio has not reached the boundary');
    const p2=meterConfig(7,8,120);
    p2.tempoUnit='eighth';
    assert.equal(env.engine.applyConfig(p2),true);
    assert.equal(env.engine.getPendingConfig().numerator,7);
    assert.equal(env.engine.getConfig().numerator,4,'future clicks are not treated as already audible');
    env.clock.advance(70);
    assert.equal(env.engine.getConfig().numerator,7,'latest config reaches the imminent bar');
    assert.equal(env.engine.getConfig().bpm,120);
    const activeBoundaryClicks=env.contexts[0].oscillators.filter(o =>
      !o.disconnected && o.at >=3.03-1e-6 && o.at<3.04);
    assert.equal(activeBoundaryClicks.length,1,'no stale or double first click');
    env.engine.stop();
    assert.equal(env.clock.tasks.size,0);
  } finally { env.restore(); }
});

test('TOOL-02 master override cancels upcoming stale gain automation, then ramps smoothly', async () => {
  const { meterConfig } = await import('../src/shared/audio/metronome-config.ts');
  const env=setup();
  try {
    assert.equal(await env.engine.start(),true);
    env.clock.advance(2940);
    const preset=meterConfig(6,8,90);
    preset.masterVolumePercent=10;
    assert.equal(env.engine.applyConfig(preset),true);
    env.clock.advance(35);
    const gain=env.contexts[0].gains[0].gain;
    assert.ok(gain.events.some(e=>e.at>=3.03 && e.value===0.1),'old preset gain was queued');
    assert.equal(env.engine.setMasterVolume(85),true);
    assert.equal(env.engine.getPendingConfig().masterVolumePercent,85);
    assert.ok(!gain.events.some(e=>e.at>=3.03 && e.value===0.1),'stale future automation removed');
    assert.ok(gain.events.some(e=>e.type==='linear' && e.value===0.85 && e.at>=2.975));
    env.clock.advance(80);
    assert.equal(env.engine.getConfig().masterVolumePercent,85);
    env.engine.stop();
  } finally { env.restore(); }
});
