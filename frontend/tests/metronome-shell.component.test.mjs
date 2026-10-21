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
    './tuning-profiles': new URL('../src/shared/tuner/tuning-profiles.ts', import.meta.url).href,
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
    // TOOL-03: the real component exposes 3 presets, six physical peg
    // buttons, preserves a manually chosen string when changing tuning,
    // and returns to Auto without taking microphone permission.
    const profileSelect = dom.find(dom.body, n => n.tag === 'select');
    assert.ok(profileSelect, 'guitar tuning can be selected with keyboard');
    assert.equal(profileSelect.children.filter(n => n.tag === 'option').length, 3);
    const findPegs = () => {
      const headstock = dom.find(dom.body, n => n.tag === 'div' && n.props.class === 'tuner-headstock');
      assert.ok(headstock, 'headstock vector display');
      return headstock.children.filter(n => n.tag === 'button');
    };
    let pegs = findPegs();
    assert.equal(pegs.length, 6, 'six accessible physical strings');
    assert.deepEqual(pegs.map(p => p.children.find(c => c.tag === 'small')?.text),
      ['6','5','4','3','2','1']);
    pegs[0].props.onClick();
    await nextTick();
    pegs = findPegs();
    assert.equal(pegs[0].props['aria-pressed'], true);
    profileSelect.props.onChange({ target: { value: 'OPEN_G' } });
    await nextTick();
    pegs = findPegs();
    assert.equal(pegs[0].props['aria-pressed'], true, 'manual physical 6th string survives change');
    assert.equal(pegs[0].children.find(c => c.tag === 'span' && c.props.class === 'tuner-peg-note')?.text, 'D2');
    assert.equal(pegs[1].children.find(c => c.tag === 'span' && c.props.class === 'tuner-peg-note')?.text, 'G2');
    assert.equal(pegs[5].children.find(c => c.tag === 'span' && c.props.class === 'tuner-peg-note')?.text, 'D4');
    const auto = dom.find(dom.body, n => n.tag === 'button' &&
      typeof n.props.class === 'string' && n.props.class.includes('tuner-auto-button'));
    assert.ok(auto);
    auto.props.onClick();
    await nextTick();
    assert.equal(findPegs().some(p => p.props['aria-pressed'] === true), false,
      'Auto clears manual target and stale visual success');
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

    // UX feedback MT-98-04: accidental clicks outside the tuner must NOT
    // stop tuning or release microphone; closing is explicit (X / Escape).
    const backdropModal = await open();
    const overlay = dom.find(dom.body, n => n.props.class === 'tuner-overlay');
    assert.ok(overlay, 'real tuner backdrop remains rendered');
    assert.equal(overlay.props.onMousedown, undefined, 'backdrop has no dismiss on mousedown');
    assert.equal(overlay.props.onClick, undefined, 'backdrop has no dismiss on click');
    assert.equal(overlay.props.onPointerdown, undefined, 'backdrop has no pointer dismiss');
    assert.equal(shell.props.inert, true, 'page remains inert while tuner is visible');
    assert.equal(dom.find(dom.body, n => n.tag === 'section' &&
      n.props['aria-label'] === 'tuner.title'), backdropModal, 'tuner remains open');
    backdropModal.props.onKeydown({ key: 'Escape', preventDefault() {} });
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


test('TOOL-03 review P2: real tuner swaps profiles during live microphone session without stale green or duplicate stream', async () => {
  const originals = Object.fromEntries(['document','window','CustomEvent','AudioContext',
    'requestAnimationFrame','cancelAnimationFrame'].map(key=>[key,
      Object.getOwnPropertyDescriptor(globalThis,key)]));
  const savedNavigator=Object.getOwnPropertyDescriptor(globalThis,'navigator');
  const dom=createHeadlessDocument();
  const listeners=new Set();
  let permissions=0,stops=0,contexts=0,frameId=0,pending=null,currentHz=110;
  const track={onended:null,stop(){stops++;}};
  const stream={getTracks:()=>[track],getAudioTracks:()=>[track]};
  try {
    globalThis.document={
      activeElement:null,body:dom.body,
      addEventListener(type,listener){if(type==='focusin')listeners.add(listener);},
      removeEventListener(type,listener){if(type==='focusin')listeners.delete(listener);},
    };
    globalThis.window={dispatchEvent(){return true;}};
    globalThis.CustomEvent=class {constructor(type,options){this.type=type;this.detail=options?.detail;}};
    Object.defineProperty(globalThis,'navigator',{configurable:true,value:{
      mediaDevices:{async getUserMedia(){permissions++;return stream;}},
    }});
    globalThis.AudioContext=class {
      constructor(){contexts++;this.sampleRate=48000;this.state='running';}
      createMediaStreamSource(){return {connect(){},disconnect(){}};}
      createAnalyser(){return {
        fftSize:8192,smoothingTimeConstant:0,disconnect(){},
        getFloatTimeDomainData(buffer){
          for(let i=0;i<buffer.length;i++) buffer[i]=.3*Math.sin(2*Math.PI*currentHz*i/48000);
        },
      };}
      close(){return Promise.resolve();}
    };
    globalThis.requestAnimationFrame=fn=>{pending={id:++frameId,fn};return frameId;};
    globalThis.cancelAnimationFrame=id=>{if(pending?.id===id)pending=null;};
    globalThis.__shellUi=reactive({open:false,sourceExercise:null,returnFocus:null});
    globalThis.__shellRoute=reactive({fullPath:'/diagnostics'});
    globalThis.__shellStops=0;
    globalThis.__shellAudio={
      getState:()=>({status:'stopped'}),
      subscribe:()=>()=>{},
    };
    const App=await compileRealApp(true);
    const app=dom.renderer.createApp(App);
    app.component('RouterLink',{
      props:['to'],render(){return h('a',{href:this.to},this.$slots.default?.());},
    });
    app.component('RouterView',{render(){return h('div',{id:'route'},'Route');}});
    try {
      app.mount(dom.root);
      await nextTick();
      const openButton=dom.find(dom.root,n=>n.tag==='button'&&n.props['aria-label']==='tuner.open');
      assert.ok(openButton);
      openButton.props.onClick();
      await nextTick(); await nextTick();
      const findStatus=()=>dom.find(dom.body,n=>n.tag==='p'&&n.props.role==='status');
      const findNote=()=>dom.find(dom.body,n=>n.tag==='div' &&
        typeof n.props.class==='string' && n.props.class.includes('tuner-note'));
      const findNeedle=()=>dom.find(dom.body,n=>n.tag==='span' &&
        typeof n.props.class==='string' && n.props.class.includes('tuner-needle'));
      const findSelect=()=>dom.find(dom.body,n=>n.tag==='select');
      const findPegs=()=>{
        const headstock=dom.find(dom.body,n=>n.tag==='div'&&n.props.class==='tuner-headstock');
        return headstock?.children.filter(n=>n.tag==='button')??[];
      };
      // Approved mockup regression: no button is outside the scalable
      // headstock coordinate system, physical labels still map 6..1.
      const headstock=dom.find(dom.body,n=>n.tag==='div'&&n.props.class==='tuner-headstock');
      const svg=dom.find(headstock,n=>n.tag==='svg');
      assert.equal(svg?.props.viewBox,'0 0 440 340');
      assert.equal(findPegs().length,6);
      const nodes=findPegs();
      for(const node of nodes) {
        const x=parseFloat(node.props.style.left),y=parseFloat(node.props.style.top);
        assert.ok(x>8 && x<92 && y>10 && y<90,
          'a peg cannot be clipped by the frame: '+x+'% / '+y+'%');
      }
      assert.deepEqual(nodes.map(n=>n.children.find(c=>c.tag==='strong')?.text),
        ['E','A','D','G','B','E']);
      assert.deepEqual(nodes.map(n=>n.children.find(c=>c.tag==='span' &&
        c.props.class==='tuner-peg-note')?.text),['E2','A2','D3','G3','B3','E4']);
      const micBar=dom.find(dom.body,n=>n.tag==='div'&&n.props.class?.includes('tuner-microphone'));
      assert.ok(micBar,'new microphone status bar is present');

      const start=dom.find(dom.body,n=>n.tag==='button'&&
        typeof n.props.class==='string'&&n.props.class.includes('tuner-action'));
      assert.ok(start);
      start.props.onClick();
      await Promise.resolve();await nextTick();await nextTick();
      assert.equal(permissions,1);
      assert.equal(contexts,1);
      async function pulse(at) {
        assert.ok(pending,'WebAudio requestAnimationFrame must remain scheduled');
        const callback=pending.fn;
        pending=null;
        callback(at);
        await nextTick();
      }
      let time=0;
      for(let k=0;k<6;k++){time+=100;await pulse(time);}
      assert.equal(findStatus()?.text,'tuner.inTune');
      assert.equal(findPegs()[1].props['aria-pressed'],true,'Auto recognizes standard A2');
      // Direct rendered-UI regression: +4c still counts as tuned, but
      // crossing to +8c on the following fresh sample must not display
      // the stale green median at +4c (even though both differ <5c).
      currentHz=110*2**(4/1200);
      for(let k=0;k<2;k++){time+=100;await pulse(time);}
      assert.notEqual(findStatus()?.text,'tuner.inTune',
        'pitch change should invalidate earlier steady confirmation');
      currentHz=110*2**(8/1200);
      time+=100;await pulse(time);
      assert.equal(findStatus()?.text,'tuner.stabilizing',
        'fresh +8c candidate invalidates a previously green +4c median');
      // Both directions must remain readable *throughout* a peg adjustment,
      // not only when the sound becomes steady again.
      for(const direction of [1,-1]) {
        currentHz=110;
        for(let k=0;k<6;k++) {time+=100;await pulse(time);}
        assert.equal(findStatus()?.text,'tuner.inTune','steady note stabilizes before ramp');
        for(let step=1;step<=10;step++) {
          currentHz=110*2**(direction*step*6/1200);
          time+=100;
          await pulse(time);
          assert.ok(findNote()?.text && findNote().text !== '—',
            'live note stays visible even when its displayed pitch class changes');
          assert.ok(findNeedle(),'needle does not disappear during ramp');
          assert.equal(findPegs()[1].props['aria-pressed'],true,
            'Auto stays on physical A2 while the note changes');
          assert.equal(findStatus()?.text,direction>0?'tuner.sharp':'tuner.flat',
            'moving pitch has an honest, non-green direction');
        }
      }
      // A single candidate crossing into the target must not flash green
      // while the confirmed three-frame median is still out of tune.
      currentHz=110*2**(20/1200);
      for(let k=0;k<3;k++) {time+=100;await pulse(time);}
      assert.equal(findStatus()?.text,'tuner.sharp');
      // No phase of the downward crossing may flash green. The old logic
      // went green at -4c because its last candidate and median both
      // happened to be inside ±5c during the same moving window.
      for(const offset of [14,8,2,-4,-10]) {
        currentHz=110*2**(offset/1200);
        time+=100;await pulse(time);
        assert.ok(findNeedle(),'needle must remain visible at '+offset+'c');
        assert.equal(findPegs()[1].props['aria-pressed'],true);
        assert.notEqual(findStatus()?.text,'tuner.inTune',
          'no transient green while passing through '+offset+'c');
        if(Math.abs(offset)<=5) {
          const meter=dom.find(dom.body,n=>n.tag==='div' && n.props.role==='meter');
          assert.ok(meter,'meter remains accessible through target crossing');
          assert.notEqual(findStatus()?.text,'tuner.inTune');
        }
      }
      currentHz=110;
      for(let k=0;k<3;k++) {
        time+=100;await pulse(time);
        assert.notEqual(findStatus()?.text,'tuner.inTune',
          'stability gate requires more than three settled frames');
        assert.ok(findNeedle(),'needle remains visible during confirmation');
      }
      time+=100;await pulse(time);
      assert.equal(findStatus()?.text,'tuner.inTune',
        'four stable fresh samples confirm target at rest');
      const select=findSelect();
      select.props.onChange({target:{value:'DROP_D'}});
      await nextTick();
      assert.notEqual(findStatus()?.text,'tuner.inTune','changing profile clears green immediately');
      assert.equal(findPegs().filter(p=>p.props['aria-pressed']).length,0);
      assert.equal(permissions,1,'switching profile must not reacquire microphone');
      assert.equal(contexts,1,'switching profile must reuse AudioContext');

      currentHz=73.41619;
      for(let k=0;k<6;k++) {time+=100;await pulse(time);}
      assert.equal(findStatus()?.text,'tuner.inTune','drop D becomes valid after fresh stable frames');
      assert.equal(findPegs()[0].props['aria-pressed'],true);
      const sixth=findPegs()[0];
      sixth.props.onClick();
      await nextTick();
      select.props.onChange({target:{value:'OPEN_G'}});
      await nextTick();
      assert.equal(findPegs()[0].props['aria-pressed'],true,'manual mode keeps physical sixth string');
      assert.equal(findPegs()[0].children.find(c=>c.tag==='span' && c.props.class==='tuner-peg-note')?.text,'D2');
      assert.notEqual(findStatus()?.text,'tuner.inTune','manual tune change also clears green');
      assert.equal(permissions,1);
      assert.equal(contexts,1);

      const close=dom.find(dom.body,n=>n.tag==='button'&&n.props['aria-label']==='tuner.close');
      close.props.onClick();
      await nextTick();await nextTick();
      assert.equal(stops,1,'mic track is released on close');
      assert.equal(listeners.size,0);
      openButton.props.onClick();
      await nextTick();await nextTick();
      assert.equal(permissions,1,'reopening must not autoplay microphone');
      assert.ok(findSelect(),'reopened tuner still exposes saved profile choice');
    } finally {
      app.unmount();
    }
  } finally {
    for(const [key,desc] of Object.entries(originals)) {
      if(desc) Object.defineProperty(globalThis,key,desc);
      else delete globalThis[key];
    }
    if(savedNavigator) Object.defineProperty(globalThis,'navigator',savedNavigator);
    else delete globalThis.navigator;
    for(const key of ['__shellUi','__shellRoute','__shellStops','__shellAudio']) delete globalThis[key];
  }
});
