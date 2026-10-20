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
      get currentTime() { return clock.now / 1000; },
      resume() {
        if (options.resume) return options.resume(this);
        this.state = 'running';
        return Promise.resolve();
      },
      close() { this.state = 'closed'; this.closed++; return Promise.resolve(); },
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
        return { gain: { setValueAtTime: () => {}, exponentialRampToValueAtTime: () => {} },
          connect: () => {}, disconnect: () => {} };
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
