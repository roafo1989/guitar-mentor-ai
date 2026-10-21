import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { parse, compileScript } from '@vue/compiler-sfc';
import ts from 'typescript';
import { createRenderer, h, nextTick, reactive, markRaw } from 'vue';

const asModule = code => 'data:text/javascript,' + encodeURIComponent(code);
const vueUrl = import.meta.resolve('vue');
const i18nUrl = asModule("import {ref} from '"+vueUrl+"'; export const useI18n=()=>({locale:ref('ru'),t:key=>key});");
const routerUrl = asModule("export const useRoute=()=>globalThis.__shellRoute;");
const learnerUrl = asModule("export const useLearnerStore=()=>({learner:{displayName:'Sample'}});");
const audioUrl = asModule("export const metronome=globalThis.__shellAudio;");
const uiUrl = asModule([
  'export const metronomeUi=globalThis.__shellUi;',
  'export function openMetronome(trigger){metronomeUi.returnFocus=trigger;metronomeUi.open=true;}',
  'export function closeMetronome(){globalThis.__shellStops++;metronomeUi.open=false;}',
  'export function minimizeMetronome(){metronomeUi.open=false;}',
  'export function stopMetronomeForAudioCapture(){globalThis.__shellStops++;metronomeUi.open=false;}',
].join('\n'));
const dialogUrl = asModule([
  "import {h,Teleport} from '"+vueUrl+"';",
  "export default { emits:['close','minimize'], setup(_,{emit}) {",
  " return ()=>h(Teleport,{to:'body'},h('section',{role:'dialog'},[",
  " h('button',{id:'shell-close',onClick:()=>emit('close')},'Close'),",
  " h('button',{id:'shell-minimize',onClick:()=>emit('minimize')},'Minimize')",
  " ]));",
  "} };",
].join('\n'));

const tunerUrl = asModule([
  "import {h,Teleport} from '"+vueUrl+"';",
  "export default { props:['open'], emits:['close'], setup(props,{emit}) {",
  " return ()=>props.open?h(Teleport,{to:'body'},h('section',{role:'dialog',id:'shell-tuner'},[",
  " h('button',{id:'shell-tuner-close',onClick:()=>emit('close')},'Close tuner')",
  " ])):null;",
  "} };",
].join('\n'));


async function compileRealTuner(instance) {
  // Compile the real SFC, not the previous simplified tuner stub. The
  // helper and pitch/audio modules are imported as real Node .ts modules.
  const source = await readFile(new URL('../src/shared/tuner/TunerDialog.vue', import.meta.url), 'utf8');
  const { descriptor, errors } = parse(source, { filename: 'TunerDialog.vue' });
  assert.equal(errors.length, 0);
  const compiled = compileScript(descriptor, { id: 'actual-tuner-dialog', inlineTemplate: true });
  const js = ts.transpileModule(compiled.content, {
    compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2022 },
  }).outputText;
  const imports = {
    vue: vueUrl,
    'vue-i18n': i18nUrl,
    '../audio/audio-session': new URL('../src/shared/audio/audio-session.ts', import.meta.url).href,
    './pitch': new URL('../src/shared/tuner/pitch.ts', import.meta.url).href,
    './tuner-session': new URL('../src/shared/tuner/tuner-session.ts', import.meta.url).href,
    './modal-focus': new URL('../src/shared/tuner/modal-focus.ts', import.meta.url).href,
    './tuner-reading': new URL('../src/shared/tuner/tuner-reading.ts', import.meta.url).href,
    './tuner.css': asModule(''),
  };
  const mapped = js.replace(/from (['"])(.*?)\1/g, (_full, _q, name) => {
    if (!imports[name]) throw Error('Unexpected real tuner dependency: ' + name);
    return 'from ' + JSON.stringify(imports[name]);
  }).replace("import './tuner.css';",
    'import ' + JSON.stringify(imports['./tuner.css']) + ';');
  return asModule(mapped + '\n// actual tuner ' + instance);
}

let appInstance = 0;
async function compileRealApp(useRealTuner = false) {
  // Each mounted app must use the current test's independent reactive stubs.
  const instance = ++appInstance;
  const source = await readFile(new URL('../src/App.vue', import.meta.url), 'utf8');
  const { descriptor, errors } = parse(source, { filename: 'App.vue' });
  assert.equal(errors.length, 0);
  const compiled = compileScript(descriptor, { id: 'app-metronome-shell', inlineTemplate: true });
  const js = ts.transpileModule(compiled.content, {
    compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2022 },
  }).outputText;
  const sources = {
    vue: vueUrl, 'vue-router': routerUrl, 'vue-i18n': i18nUrl,
    './entities/metronome/MetronomeDialog.vue': dialogUrl,
    './shared/tuner/TunerDialog.vue': useRealTuner ? await compileRealTuner(instance) : tunerUrl,
    './entities/metronome/metronome-ui': uiUrl + '#app-' + instance,
    './shared/audio/metronome': audioUrl + '#app-' + instance,
    './stores/learner.store': learnerUrl,
  };
  const mapped = js.replace(/from (['"])(.*?)\1/g, (_full, _q, name) => {
    if (!sources[name]) throw Error('Unexpected App.vue runtime import: ' + name);
    return 'from ' + JSON.stringify(sources[name]);
  });
  return (await import(asModule(mapped + '\n// component instance ' + instance))).default;
}

function createHeadlessDocument() {
  const connectedRoots = new Set();
  const host = (tag, text = '') => markRaw({
    tag, text, props: {}, children: [], parent: null,
    get isConnected() {
      let current = this;
      while (current?.parent) current = current.parent;
      return connectedRoots.has(current);
    },
    focus() {
      // Native browsers refuse to focus descendants of inert shells.
      for (let parent = this; parent; parent = parent.parent) {
        if (parent.props?.inert) return;
      }
      document.activeElement = this;
    },
    addEventListener() {},
    removeEventListener() {},
    get options() { return this.children.filter(c => c.tag === 'option'); },
    get selectedIndex() { return this.options.findIndex(o => o.selected); },
    set selectedIndex(_value) {},
    get value() { return this._value ?? ''; },
    set value(value) { this._value = value; },
    setAttribute(key, value) { this.props[key] = value; },
    removeAttribute(key) { delete this.props[key]; },
  });
  const root = host('root');
  const body = host('body');
  connectedRoots.add(root); connectedRoots.add(body);
  function remove(node) {
    if (!node.parent) return;
    const siblings = node.parent.children;
    const idx = siblings.indexOf(node);
    if (idx !== -1) siblings.splice(idx, 1);
    node.parent = null;
  }
  const renderer = createRenderer({
    createElement: tag => host(tag),
    createText: text => host('#text', text),
    createComment: text => host('#comment', text),
    setText: (node, text) => { node.text = text; },
    setElementText: (node, text) => { node.text = text; node.children = []; },
    patchProp: (node, key, _prev, value) => { node.props[key] = value; },
    insert: (node, parent, anchor = null) => {
      if (node.parent) remove(node);
      node.parent = parent;
      const idx = parent.children.indexOf(anchor);
      if (idx < 0) parent.children.push(node);
      else parent.children.splice(idx, 0, node);
    },
    remove,
    parentNode: node => node.parent,
    nextSibling: node => node.parent?.children[node.parent.children.indexOf(node) + 1] ?? null,
    querySelector: query => query === 'body' ? body : null,
  });
  function find(where, predicate) {
    const all = [];
    function visit(parent) {
      for (const node of parent.children) { all.push(node); visit(node); }
    }
    visit(where);
    return all.find(predicate);
  }
  return { root, body, renderer, find };
}

test('real App.vue isolates all background and restores focus after close/minimize', async () => {
  const prior = globalThis.document;
  const dom = createHeadlessDocument();
  globalThis.document = { activeElement: null, body: dom.body };
  const ui = reactive({ open: false, sourceExercise: null, returnFocus: null });
  globalThis.__shellUi = ui;
  globalThis.__shellRoute = reactive({ fullPath: '/diagnostics' });
  globalThis.__shellStops = 0;
  globalThis.__shellAudio = {
    getState: () => ({ status: 'stopped' }),
    subscribe(fn) { fn({ status: 'stopped' }); return () => {}; },
  };
  let app;
  try {
    const App = await compileRealApp();
    app = dom.renderer.createApp(App);
    app.component('RouterLink', {
      props: ['to'], render() { return h('a', {href:this.to}, this.$slots.default?.()); },
    });
    app.component('RouterView', { render() { return h('div', {id:'route'}, 'Route'); } });
    app.mount(dom.root);
    await nextTick();
    const shell = dom.find(dom.root, n => n.tag === 'div' && n.props.class === 'app-shell');
    const opener = dom.find(dom.root, n => n.tag === 'button' &&
      n.props['aria-label'] === 'metronome.open');
    assert.ok(shell && opener);
    assert.equal(shell.props.inert, false, 'all page elements are usable before opening');
    opener.focus();
    opener.props.onClick({currentTarget:opener});
    await nextTick();
    assert.equal(shell.props.inert, true, 'entire page, including header and links, is inert');
    const modal = dom.find(dom.body, n => n.tag === 'section' && n.props.role === 'dialog');
    const close = dom.find(dom.body, n => n.props.id === 'shell-close');
    assert.ok(modal && close, 'dialog is teleported outside inert shell');
    assert.equal(modal.isConnected, true);
    close.focus();
    close.props.onClick();
    await nextTick();
    await nextTick();
    assert.equal(shell.props.inert, false, 'closing restores background interactivity');
    assert.equal(globalThis.document.activeElement, opener, 'focus returns to opener');
    assert.equal(globalThis.__shellStops, 1);

    opener.props.onClick({currentTarget:opener});
    await nextTick();
    const minimize = dom.find(dom.body, n => n.props.id === 'shell-minimize');
    assert.ok(minimize);
    minimize.focus();
    minimize.props.onClick();
    await nextTick(); await nextTick();
    assert.equal(shell.props.inert, false, 'minimizing also unlocks background');
    assert.equal(globalThis.document.activeElement, opener);
    assert.equal(globalThis.__shellStops, 1, 'minimizing must not silence playback');
  } finally {
    app?.unmount();
    globalThis.document = prior;
    for(const name of ['__shellUi','__shellRoute','__shellStops','__shellAudio']) delete globalThis[name];
  }
});


test('tuner launch stops minimized metronome without losing header access', async () => {
  const previousDocument = globalThis.document;
  const dom = createHeadlessDocument();
  globalThis.document = { activeElement: null, body: dom.body };
  const ui = reactive({ open: false, sourceExercise: null, returnFocus: null });
  globalThis.__shellUi = ui;
  globalThis.__shellRoute = reactive({ fullPath: '/diagnostics' });
  globalThis.__shellStops = 0;
  globalThis.__shellAudio = {
    getState: () => ({ status: 'running' }),
    subscribe(fn) { fn({ status: 'running' }); return () => {}; },
  };
  let app;
  try {
    const App = await compileRealApp();
    app = dom.renderer.createApp(App);
    app.component('RouterLink', {
      props: ['to'], render() { return h('a', {href:this.to}, this.$slots.default?.()); },
    });
    app.component('RouterView', { render() { return h('div', {id:'route'}, 'Route'); } });
    app.mount(dom.root);
    await nextTick();
    const tunerButton = dom.find(dom.root, n => n.tag === 'button' &&
      n.props['aria-label'] === 'tuner.open');
    const metroButton = dom.find(dom.root, n => n.tag === 'button' &&
      n.props['aria-label'] === 'metronome.playingInBackground');
    assert.ok(tunerButton && metroButton, 'both tools present in header');
    tunerButton.props.onClick();
    await nextTick();
    assert.equal(globalThis.__shellStops, 1, 'tuner entry stops metronome clicks');
    const activeTuner = dom.find(dom.body, n => n.props.id === 'shell-tuner');
    assert.ok(activeTuner, 'tuner modal remains available after metronome stop');
    const shell = dom.find(dom.root, n => n.props.class === 'app-shell');
    assert.equal(shell.props.inert, true);
    dom.find(dom.body, n => n.props.id === 'shell-tuner-close').props.onClick();
    await nextTick(); await nextTick();
    assert.equal(shell.props.inert, false);
    assert.equal(globalThis.document.activeElement, tunerButton);
  } finally {
    app?.unmount();
    globalThis.document = previousDocument;
    for (const name of ['__shellUi','__shellRoute','__shellStops','__shellAudio']) delete globalThis[name];
  }
});


test('P1 REVIEW-04: real tuner opens, closes, restores shell/focus, and reopens', async () => {
  const previousDocument = globalThis.document;
  const dom = createHeadlessDocument();
  const listeners = new Set();
  globalThis.document = {
    activeElement: null, body: dom.body,
    querySelector: selector => selector === '.app-shell'
      ? dom.find(dom.root, n => n.props.class === 'app-shell') : null,
    addEventListener(type, listener) {
      if (type === 'focusin') listeners.add(listener);
    },
    removeEventListener(type, listener) {
      if (type === 'focusin') listeners.delete(listener);
    },
  };
  const ui = reactive({ open: false, sourceExercise: null, returnFocus: null });
  globalThis.__shellUi = ui;
  globalThis.__shellRoute = reactive({ fullPath: '/diagnostics' });
  globalThis.__shellStops = 0;
  globalThis.__shellAudio = {
    getState: () => ({ status: 'stopped' }),
    subscribe(fn) { fn({ status: 'stopped' }); return () => {}; },
  };
  let app;
  try {
    const App = await compileRealApp(true);
    app = dom.renderer.createApp(App);
    app.component('RouterLink', {
      props: ['to'], render() { return h('a', { href: this.to }, this.$slots.default?.()); },
    });
    app.component('RouterView', { render() { return h('div', { id: 'route' }, 'Route'); } });
    app.mount(dom.root);
    await nextTick();

    const shell = dom.find(dom.root, n => n.props.class === 'app-shell');
    const tunerButton = dom.find(dom.root, n => n.tag === 'button' &&
      n.props['aria-label'] === 'tuner.open');
    const metroButton = dom.find(dom.root, n => n.tag === 'button' &&
      n.props['aria-label'] === 'metronome.open');
    assert.ok(shell && tunerButton && metroButton, 'both audio tools remain in header');

    async function open() {
      tunerButton.focus();
      tunerButton.props.onClick();
      await nextTick(); await nextTick(); await nextTick();
      const modal = dom.find(dom.body, n => n.tag === 'section' &&
        n.props['aria-label'] === 'tuner.title');
      assert.ok(modal, 'actual teleported tuner dialog mounted');
      assert.equal(shell.props.inert, true, 'page is inert while tuner is open');
      assert.equal(globalThis.document.activeElement, modal, 'focus moved inside tuner');
      assert.equal(listeners.size, 1, 'single focus isolation guard');
      return modal;
    }

    async function assertClosed() {
      await nextTick(); await nextTick(); await nextTick();
      assert.equal(shell.props.inert, false, 'page unlocked after real tuner close');
      assert.equal(listeners.size, 0, 'focus isolation listener cleaned');
      assert.equal(globalThis.document.activeElement, tunerButton,
        'focus restored to the usable header opener');
      const modal = dom.find(dom.body, n => n.tag === 'section' &&
        n.props['aria-label'] === 'tuner.title');
      assert.equal(modal, undefined, 'modal completely removed');
    }

    let modal = await open();
    const closeButton = dom.find(dom.body, n => n.tag === 'button' &&
      n.props['aria-label'] === 'tuner.close');
    assert.ok(closeButton);
    closeButton.props.onClick();
    await assertClosed();

    modal = await open();
    let prevented = false;
    modal.props.onKeydown({ key: 'Escape', preventDefault() { prevented = true; } });
    assert.equal(prevented, true);
    await assertClosed();

    // Route changes also close the dialog, and a subsequent open is allowed.
    await open();
    globalThis.__shellRoute.fullPath = '/profile';
    await assertClosed();
    assert.ok(metroButton.props.onClick, 'metronome button remains interactive');
  } finally {
    app?.unmount();
    globalThis.document = previousDocument;
    for (const name of ['__shellUi', '__shellRoute', '__shellStops', '__shellAudio']) {
      delete globalThis[name];
    }
  }
});
