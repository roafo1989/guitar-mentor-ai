import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { parse, compileScript } from '@vue/compiler-sfc';
import ts from 'typescript';
import { createRenderer, h, nextTick } from 'vue';

const asModule = code => 'data:text/javascript,' + encodeURIComponent(code);
const vueUrl = import.meta.resolve('vue');
const i18nUrl = asModule('export const useI18n = () => ({ t: (key) => key });');

function headlessRenderer() {
  function node(tag, text = '') { return { tag, text, children: [], parent: null, props: {} }; }
  const remove = item => {
    if (!item.parent) return;
    const siblings = item.parent.children;
    const index = siblings.indexOf(item);
    if (index >= 0) siblings.splice(index, 1);
    item.parent = null;
  };
  const renderer = createRenderer({
    createElement: tag => node(tag),
    createText: value => node('#text', value),
    createComment: value => node('#comment', value),
    setText: (item, value) => { item.text = value; },
    setElementText: (item, value) => { item.text = value; item.children = []; },
    patchProp: (item, key, _old, value) => { item.props[key] = value; },
    insert: (item, parent, anchor = null) => {
      if (item.parent) remove(item);
      item.parent = parent;
      const index = parent.children.indexOf(anchor);
      if (index < 0) parent.children.push(item);
      else parent.children.splice(index, 0, item);
    },
    remove,
    parentNode: item => item.parent,
    nextSibling: item => item.parent?.children[item.parent.children.indexOf(item) + 1] ?? null,
  });
  const root = node('root');
  const find = (tag, phrase) => {
    const all = [];
    function walk(item) { for (const child of item.children) { all.push(child); walk(child); } }
    walk(root);
    return all.find(item => item.tag === tag && (!phrase || item.text.includes(phrase)));
  };
  return { renderer, root, find };
}

async function compileRealRecorder(source, busyLocale) {
  const { descriptor, errors } = parse(source, { filename: 'MicrophoneRecorder.vue' });
  assert.equal(errors.length, 0);
  const component = compileScript(descriptor, { id: 'record-while-metronome', inlineTemplate: true });
  const js = ts.transpileModule(component.content, {
    compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ESNext },
  }).outputText;
  // Load the real microphone lease module; metronome playback must remain
  // unaffected by recorder acquisition, even when tuner code is present.
  const sessionSource = await readFile(new URL('../src/shared/audio/audio-session.ts', import.meta.url), 'utf8');
  const sessionJs = ts.transpileModule(sessionSource, {
    compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ESNext },
  }).outputText;
  const sessionUrl = asModule(sessionJs);
  const dictionary = busyLocale ? JSON.parse(await readFile(new URL('../src/i18n/' + busyLocale + '.json', import.meta.url), 'utf8')) : null;
  const translatedI18n = dictionary ? asModule('const messages = ' + JSON.stringify(dictionary) + '; export const useI18n = () => ({ t: key => key.split(".").reduce((v,k)=>v?.[k], messages) ?? key });') : i18nUrl;
  const mapped = js.replace(/from (['"])(.*?)\1/g, (_matched, _q, name) => {
    const path = { vue: vueUrl, 'vue-i18n': translatedI18n,
      '../../shared/audio/audio-session': sessionUrl }[name];
    if (!path) throw Error('Unexpected microphone runtime dependency: ' + name);
    return 'from ' + JSON.stringify(path);
  });
  return { Recorder: (await import(asModule(mapped))).default, session: await import(sessionUrl) };
}

test('actual recorder can start while metronome is playing; playback is not stopped', async () => {
  const oldNavigator = Object.getOwnPropertyDescriptor(globalThis, 'navigator');
  const oldRecorder = globalThis.MediaRecorder;
  const oldAudioContext = globalThis.AudioContext;
  const events = [];
  const tracks = [{ stop() {} }];
  let createdRecorder;
  class FakeMediaRecorder {
    static isTypeSupported() { return true; }
    constructor(_stream, options) {
      this.mimeType = options.mimeType;
      this.state = 'inactive';
      createdRecorder = this;
    }
    start() { this.state = 'recording'; }
    stop() { this.state = 'inactive'; this.onstop?.(); }
  }
  Object.defineProperty(globalThis, 'navigator', {
    configurable: true,
    value: { mediaDevices: { getUserMedia: async () => ({ getTracks: () => tracks }) } },
  });
  globalThis.MediaRecorder = FakeMediaRecorder;
  globalThis.AudioContext = undefined; // No UI signal meter needed for this test.
  let app;
  try {
    const source = await readFile(new URL('../src/entities/attempt/MicrophoneRecorder.vue', import.meta.url), 'utf8');
    assert.doesNotMatch(source, /stopMetronomeForAudioCapture\s*\(/,
      'capturing microphone audio must not stop the backing click');
    const { Recorder } = await compileRealRecorder(source);
    const view = headlessRenderer();
    app = view.renderer.createApp({
      render: () => h(Recorder, {
        disabled: false, maxBytes: 1_000_000,
        onActive: value => events.push(value),
      }),
    });
    app.mount(view.root);
    const button = view.find('button', 'attempt.startRecording');
    assert.ok(button, 'start recording button is rendered');
    let metronomePlaying = true; // independent Web Audio player already running
    button.props.onClick();
    await Promise.resolve();
    await nextTick();
    assert.equal(createdRecorder?.state, 'recording');
    assert.ok(events.includes(true), 'recording became active');
    assert.equal(metronomePlaying, true, 'the metronome keeps playing during microphone capture');
    const stop = view.find('button', 'attempt.stopRecording');
    assert.ok(stop, 'stop recording remains available');
    stop.props.onClick();
    await nextTick();
    assert.equal(createdRecorder?.state, 'inactive');
    assert.equal(metronomePlaying, true, 'recording end does not stop the metronome');
  } finally {
    app?.unmount();
    if (oldNavigator) Object.defineProperty(globalThis, 'navigator', oldNavigator);
    else delete globalThis.navigator;
    globalThis.MediaRecorder = oldRecorder;
    globalThis.AudioContext = oldAudioContext;
  }
});

for (const locale of ['ru', 'en']) test('mounted recorder lease refusal is localized and preserves active recording: ' + locale, async () => {
  const previousNavigator = Object.getOwnPropertyDescriptor(globalThis, 'navigator');
  const previousRecorder = globalThis.MediaRecorder;
  let captures = 0, stopped = 0, app;
  Object.defineProperty(globalThis, 'navigator', { configurable: true, value: {
    mediaDevices: { getUserMedia: async () => { captures++; throw Error('must not acquire hardware'); } },
  }});
  globalThis.MediaRecorder = class { static isTypeSupported() { return true; } };
  const { Recorder, session } = await compileRealRecorder(await readFile(new URL('../src/entities/attempt/MicrophoneRecorder.vue', import.meta.url), 'utf8'), locale);
  const owner = Symbol('existing-recording');
  assert.equal(session.acquireAudio('recorder', owner, () => { stopped++; }), true);
  try {
    const dictionary = JSON.parse(await readFile(new URL('../src/i18n/' + locale + '.json', import.meta.url), 'utf8'));
    const view = headlessRenderer();
    app = view.renderer.createApp({ render: () => h(Recorder, { disabled: false, maxBytes: 1000 }) });
    app.mount(view.root);
    view.find('button', dictionary.attempt.startRecording).props.onClick();
    await nextTick();
    const alert = view.find('p', dictionary.attempt.microphoneBusy);
    assert.ok(alert); assert.equal(alert.props.role, 'alert');
    assert.equal(captures, 0); assert.equal(stopped, 0); assert.equal(session.audioOwner(), 'recorder');
    app.unmount(); app = null;
    assert.equal(session.audioOwner(), 'recorder', 'unmounting refused recorder cannot release another lease');
  } finally {
    app?.unmount(); session.releaseAudio(owner);
    if (previousNavigator) Object.defineProperty(globalThis, 'navigator', previousNavigator);
    else delete globalThis.navigator;
    globalThis.MediaRecorder = previousRecorder;
  }
});
