import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { parse, compileScript } from '@vue/compiler-sfc';
import ts from 'typescript';
import { createRenderer, h, nextTick } from 'vue';
import { meterConfig, copyConfig } from '../src/shared/audio/metronome-config.ts';

// Integration regression: mount the actual MetronomeDialog.vue compiled from source.
// A simulated user enters BPM and clicks Start; pure parser tests missed the old
// <input type="number"> v-model coercion that caused .trim() to throw.
const asModule = code => 'data:text/javascript,' + encodeURIComponent(code);
const vueUrl = import.meta.resolve('vue');
const fakeAudioUrl = asModule([
  'export const metronome = {',
  'getState: (...args)=>globalThis.__testMetronome87.getState(...args),',
  'subscribe: (...args)=>globalThis.__testMetronome87.subscribe(...args),',
  'setBpm: (...args)=>globalThis.__testMetronome87.setBpm(...args),
  'getConfig: (...args)=>globalThis.__testMetronome87.getConfig(...args),
  'getPendingConfig: (...args)=>globalThis.__testMetronome87.getPendingConfig(...args),
  'applyConfig: (...args)=>globalThis.__testMetronome87.applyConfig(...args),
  'setMasterVolume: (...args)=>globalThis.__testMetronome87.setMasterVolume(...args),',
  'start: (...args)=>globalThis.__testMetronome87.start(...args),',
  'stop: (...args)=>globalThis.__testMetronome87.stop(...args)',
  '};',
].join(String.fromCharCode(10)));
const fakeI18nUrl = asModule('export const useI18n = () => ({ t: (key) => key });');
const fakeUiUrl = asModule('export const metronomeUi = {presets:[],storageError:false,storageBlocked:false}; export function persistMetronomeConfig(){return true;} export function resetMetronomePreferences(){return true;}');
let Component;

async function compileDialog() {
  if (Component) return Component;
  const filename = new URL('../src/entities/metronome/MetronomeDialog.vue', import.meta.url);
  const { descriptor, errors } = parse(await readFile(filename, 'utf8'), { filename: 'MetronomeDialog.vue' });
  assert.equal(errors.length, 0);
  const script = compileScript(descriptor, { id: 'metronome-87', inlineTemplate: true });
  const js = ts.transpileModule(script.content, {
    compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ESNext },
  }).outputText;
  const inputUrl = new URL('../src/entities/metronome/metronome-input.ts', import.meta.url).href;
  const mapped = js.replace(/from (['"])(.*?)\1/g, (full, _quote, name) => {
    const sources = {
      'vue': vueUrl,
      'vue-i18n': fakeI18nUrl,
      '../../shared/audio/metronome': fakeAudioUrl,
      './metronome-input': inputUrl,
      '../../shared/audio/metronome-config.ts': new URL('../src/shared/audio/metronome-config.ts', import.meta.url).href,
      './metronome-ui': fakeUiUrl,
    };
    if (!sources[name]) throw Error('Unmocked MetronomeDialog runtime dependency ' + name);
    return 'from ' + JSON.stringify(sources[name]);
  });
  Component = (await import(asModule(mapped))).default;
  return Component;
}

function createTestRenderer() {
  function node(tag, text = '') {
    return {
      tag, text, children: [], parent: null, props: {},
      focus() { globalThis.document.activeElement = this; },
      getClientRects() { return [1]; },
      querySelectorAll() {
        const matches = [];
        function walk(parent) {
          for (const child of parent.children) {
            if (['input', 'button'].includes(child.tag) && !child.props.disabled) matches.push(child);
            walk(child);
          }
        }
        walk(this);
        return matches;
      },
    };
  }
  const remove = item => {
    const siblings = item.parent?.children;
    if (!siblings) return;
    const index = siblings.indexOf(item);
    if (index >= 0) siblings.splice(index, 1);
    item.parent = null;
  };
  const body = node('body');
  body.style = { overflow: '' };
  const renderer = createRenderer({
    createElement: tag => node(tag),
    createText: value => node('#text', value),
    createComment: value => node('#comment', value),
    setText: (item, value) => { item.text = value; },
    setElementText: (item, value) => { item.text = value; item.children = []; },
    patchProp: (item, key, _previous, value) => { item.props[key] = value; },
    insert: (item, parent, anchor = null) => {
      if (item.parent) remove(item);
      item.parent = parent;
      const position = parent.children.indexOf(anchor);
      if (position < 0) parent.children.push(item);
      else parent.children.splice(position, 0, item);
    },
    remove,
    parentNode: item => item.parent,
    nextSibling: item => item.parent?.children[item.parent.children.indexOf(item) + 1] ?? null,
    querySelector: selector => selector === 'body' ? body : null,
  });
  function find(tag, predicate = () => true) {
    const children = [];
    function walk(parent) {
      for (const child of parent.children) { children.push(child); walk(child); }
    }
    walk(body);
    return children.find(item => item.tag === tag && predicate(item));
  }
  return { node, renderer, body, find };
}

function fakeEngine() {
  const listeners = new Set();
  const state = { status: 'stopped', bpm: 60, beat: null, error: null };
  let starts = 0;
  let config = meterConfig(4,4,60);
  function emit() { for (const callback of listeners) callback({ ...state }); }
  return {
    get starts() { return starts; },
    getState: () => ({ ...state }),
    getConfig: () => copyConfig(config),
    getPendingConfig: () => null,
    applyConfig(value) { config = copyConfig(value); state.bpm=config.bpm; emit(); return true; },
    setMasterVolume(value) { config.masterVolumePercent=value; emit(); return true; },
    setStatus(status) { state.status = status; emit(); },
    subscribe(callback) {
      listeners.add(callback);
      callback({ ...state });
      return () => listeners.delete(callback);
    },
    setBpm(bpm) {
      assert.ok(Number.isInteger(bpm) && bpm >= 20 && bpm <= 240);
      state.bpm = bpm; config.bpm=bpm; emit();
      return true;
    },
    start() { starts++; state.status = 'running'; emit(); return Promise.resolve(true); },
    stop() { state.status = 'stopped'; state.beat = null; emit(); },
  };
}

test('real dialog applies typed BPM and starts; invalid input preserves BPM and blocks Start', async () => {
  const savedDocument = globalThis.document;
  const engine = fakeEngine();
  const view = createTestRenderer();
  globalThis.__testMetronome87 = engine;
  globalThis.document = { body: view.body, activeElement: null };
  let app;
  try {
    const Dialog = await compileDialog();
    let minimized = false;
    app = view.renderer.createApp({ render: () => h(Dialog, { onMinimize: () => {
      minimized = true;
      app.unmount(); // Vue unmount after collapsing the modal.
      app = undefined;
    } }) });
    app.mount(view.node('root'));
    await nextTick();
    await nextTick();
    const input = view.find('input', n => n.props.id === 'metronome-bpm');
    assert.ok(input, 'real BPM field rendered');
    // This is the actual DOM input event, not a direct parser invocation.
    input.props.onInput({ target: { value: '90' } });
    await nextTick();
    assert.equal(engine.getState().bpm, 90, 'typing updates the engine BPM immediately');

    const start = view.find('button', n => n.children.some(c => c.text === 'metronome.start') || n.text === 'metronome.start');
    assert.ok(start, 'Start button rendered');
    start.focus();
    start.props.onClick();
    await nextTick();
    assert.equal(globalThis.document.activeElement, start, 'Start/Stop preserves DOM focus');
    assert.equal(engine.starts, 1, 'Start click reaches Web Audio engine');
    assert.equal(engine.getState().status, 'running');

    const stop = view.find('button', n => n.text === 'metronome.stop');
    assert.ok(stop);
    assert.equal(stop, start, 'Start/Stop must be the same DOM button');
    stop.props.onClick();
    await nextTick();

    input.props.onInput({ target: { value: '241' } });
    await nextTick();
    assert.equal(engine.getState().bpm, 90, 'out-of-range input does not overwrite last valid tempo');
    const retry = view.find('button', n => n.text === 'metronome.start');
    retry.props.onClick();
    await nextTick();
    assert.equal(engine.starts, 1, 'invalid input cannot trigger playback');
    // The student can minimize controls and still hear the metronome during a take.
    input.props.onInput({ target: { value: '92' } });
    await nextTick();
    retry.props.onClick();
    await nextTick();
    assert.equal(engine.getState().status, 'running');
    const backdrop = view.find('div', n => n.props.class === 'metronome-backdrop');
    assert.ok(backdrop);
    assert.equal(backdrop.props.onClick, undefined, 'backdrop is not a close button');
    assert.equal(engine.getState().status, 'running', 'unintentional backdrop interaction cannot stop playback');
    const minimize = view.find('button', n => n.text === 'metronome.minimize');
    assert.ok(minimize, 'minimize command offered when playing');
    minimize.props.onClick();
    await nextTick();
    assert.equal(minimized, true);
    assert.equal(engine.getState().status, 'running',
      'minimizing and destroying the dialog must not stop metronome audio');
  } finally {
    app?.unmount();
    globalThis.document = savedDocument;
    delete globalThis.__testMetronome87;
  }
});

test('focused Start remains enabled, focusable and stable while audio is starting', async () => {
  const savedDocument = globalThis.document;
  const engine = fakeEngine();
  const view = createTestRenderer();
  globalThis.__testMetronome87 = engine;
  globalThis.document = { body: view.body, activeElement: null };
  let app;
  try {
    const Dialog = await compileDialog();
    app = view.renderer.createApp({ render: () => h(Dialog) });
    app.mount(view.node('root'));
    await nextTick();
    const button = view.find('button', n => n.text === 'metronome.start' || n.children.some(c => c.text === 'metronome.start'));
    assert.ok(button);
    button.focus();
    engine.setStatus('starting');
    await nextTick();
    assert.equal(button.props.disabled, undefined, 'do not natively disable focused button');
    assert.equal(button.props['aria-disabled'], true, 'announce pending state to assistive technology');
    button.props.onClick();
    await nextTick();
    assert.equal(engine.starts, 0, 'pending start is not invoked a second time');
    assert.equal(globalThis.document.activeElement, button, 'focus retained while waiting');
    engine.setStatus('running');
    await nextTick();
    assert.equal(view.find('button', n => n.text === 'metronome.stop'), button);
    assert.equal(globalThis.document.activeElement, button);
    button.props.onClick();
    await nextTick();
    assert.equal(engine.getState().status, 'stopped');
    assert.equal(globalThis.document.activeElement, button, 'Stop keeps focus');
  } finally {
    app?.unmount();
    globalThis.document = savedDocument;
    delete globalThis.__testMetronome87;
  }
});
