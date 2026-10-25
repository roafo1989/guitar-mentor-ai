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
  'setBpm: (...args)=>globalThis.__testMetronome87.setBpm(...args),',
  'getConfig: (...args)=>globalThis.__testMetronome87.getConfig(...args),',
  'getPendingConfig: (...args)=>globalThis.__testMetronome87.getPendingConfig(...args),',
  'applyConfig: (...args)=>globalThis.__testMetronome87.applyConfig(...args),',
  'setMasterVolume: (...args)=>globalThis.__testMetronome87.setMasterVolume(...args),',
  'start: (...args)=>globalThis.__testMetronome87.start(...args),',
  'stop: (...args)=>globalThis.__testMetronome87.stop(...args)',
  '};',
].join(String.fromCharCode(10)));
const fakeI18nUrl = asModule('export const useI18n = () => ({ t: (key, args) => args ? key + JSON.stringify(args) : key });');
const fakeUiState={presets:[],storageError:false,storageBlocked:false,sourceExercise:null};
globalThis.__testMetronomeUi104=fakeUiState;
const fakeUiUrl = asModule('export const metronomeUi = globalThis.__testMetronomeUi104; export function persistMetronomeConfig(){return true;} export function resetMetronomePreferences(){return true;}');
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

function fakeEngine({ queueWhileRunning=false }={}) {
  const listeners = new Set();
  const state = { status: 'stopped', bpm: 60, beat: null, error: null };
  let starts = 0;
  let config = meterConfig(4,4,60);
  let pendingConfig=null;
  function emit() { for (const callback of listeners) callback({ ...state }); }
  return {
    get starts() { return starts; },
    getState: () => ({ ...state }),
    getConfig: () => copyConfig(config),
    getPendingConfig: () => pendingConfig ? copyConfig(pendingConfig) : null,
    applyConfig(value) {
      if (queueWhileRunning && state.status==='running') pendingConfig=copyConfig(value);
      else { config=copyConfig(value); pendingConfig=null; state.bpm=config.bpm; }
      emit(); return true;
    },
    transitionBar() {
      if (pendingConfig) { config=pendingConfig; pendingConfig=null; state.bpm=config.bpm; }
      emit();
    },
    emitTick() { emit(); },
    setMasterVolume(value) { config.masterVolumePercent=value; emit(); return true; },
    setStatus(status) { state.status = status; emit(); },
    subscribe(callback) {
      listeners.add(callback);
      callback({ ...state });
      return () => listeners.delete(callback);
    },
    setBpm(bpm) {
      assert.ok(Number.isInteger(bpm) && bpm >= 20 && bpm <= 240);
      state.bpm=bpm; config.bpm=bpm; if(pendingConfig)pendingConfig.bpm=bpm; emit();
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
    assert.notEqual(globalThis.document.activeElement, input,
      'opening metronome must not focus the BPM field or raise a mobile keyboard');
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


test('TOOL-02 mounted dialog edits six-beat meter and accent only after explicit Apply', async () => {
  const savedDocument = globalThis.document;
  const engine = fakeEngine();
  const view = createTestRenderer();
  globalThis.__testMetronome87 = engine;
  globalThis.document = { body:view.body, activeElement:null };
  let app;
  try {
    const Dialog = await compileDialog();
    app = view.renderer.createApp({render:()=>h(Dialog)});
    app.mount(view.node('root'));
    await nextTick();
    const quick = view.find('button', n => n.text === '6/8');
    assert.ok(quick, 'quick-meter chooser rendered');
    quick.props.onClick();
    await nextTick();
    const grid = view.find('div', n => n.props.class === 'metronome-count');
    assert.ok(grid);
    assert.equal(grid.children.filter(n => n.tag === 'button').length,6);
    assert.equal(engine.getConfig().numerator,4,'draft is not applied immediately');
    const beat2 = grid.children.filter(n => n.tag === 'button')[1];
    assert.ok(beat2);
    beat2.props.onClick();
    await nextTick();
    const apply = view.find('button', n => n.text === 'metronome.apply');
    assert.ok(apply);
    apply.props.onClick();
    await nextTick();
    assert.equal(engine.getConfig().numerator,6);
    assert.equal(engine.getConfig().positions[1].accent,'mute',
      'weak second beat cycles to mute; unlike fixed 4/4, it is individually editable');
    assert.equal(engine.getState().status,'stopped');
  } finally {
    app?.unmount();
    globalThis.document = savedDocument;
    delete globalThis.__testMetronome87;
  }
});

test('TOOL-02 shows audible 4/4 separately from uncommitted 2/4 and exposes primary master', async () => {
  const savedDocument=globalThis.document;
  const engine=fakeEngine();
  const view=createTestRenderer();
  globalThis.__testMetronome87=engine;
  globalThis.document={body:view.body, activeElement:null};
  let app;
  try {
    const Dialog=await compileDialog();
    engine.setStatus('running');
    app=view.renderer.createApp({render:()=>h(Dialog)});
    app.mount(view.node('root'));
    await nextTick();
    assert.ok(view.find('div', n=>n.props.class==='metronome-master'),
      'master volume shown outside collapsed Details');
    const quick=view.find('button', n=>n.text==='2/4');
    assert.ok(quick);
    quick.props.onClick();
    await nextTick();
    assert.equal(engine.getConfig().numerator,4,'editing does not change the playing meter');
    const live=view.find('div', n=>n.props.class==='metronome-count metronome-count-active');
    const preview=view.find('div', n=>n.props.class==='metronome-count');
    assert.ok(live,'separate playing grid rendered');
    assert.equal(live.children.filter(n=>n.tag==='div').length,4,'playing grid is still 4/4');
    assert.equal(preview.children.filter(n=>n.tag==='button').length,2,'editable grid previews 2/4');
    const reset=view.find('button', n=>n.text==='metronome.resetStorage');
    assert.ok(reset,'normal reset is present even when storage is valid');
    reset.props.onClick();
    await nextTick();
    assert.ok(view.find('button', n=>n.text==='metronome.confirm'),'reset requires confirmation');
  } finally {
    app?.unmount();
    globalThis.document=savedDocument;
    delete globalThis.__testMetronome87;
  }
});

test('TOOL-02 separate beat volume selection does not cycle the selected accent', async () => {
  const savedDocument=globalThis.document;
  const engine=fakeEngine();
  const view=createTestRenderer();
  globalThis.__testMetronome87=engine;
  globalThis.document={body:view.body, activeElement:null};
  let app;
  try {
    const Dialog=await compileDialog();
    app=view.renderer.createApp({render:()=>h(Dialog)});
    app.mount(view.node('root'));
    await nextTick();
    const selector=view.find('select', n=>n.props.id==='metronome-beat-selection');
    assert.ok(selector);
    selector.props.onChange({target:{value:'1'}});
    await nextTick();
    const range=view.find('input', n=>n.props.id==='metronome-position-volume');
    range.props.onInput({target:{value:'30'}});
    await nextTick();
    const apply=view.find('button', n=>n.text==='metronome.apply');
    apply.props.onClick();
    await nextTick();
    assert.equal(engine.getConfig().positions[1].volumePercent,30);
    assert.equal(engine.getConfig().positions[1].accent,'weak',
      'choosing a beat to adjust its volume must not alter the accent');
  } finally {
    app?.unmount();
    globalThis.document=savedDocument;
    delete globalThis.__testMetronome87;
  }
});

test('TOOL-02 pending preset preserves BPM and sound mix through quick meter selection', async () => {
  const previousDocument=globalThis.document;
  const engine=fakeEngine({queueWhileRunning:true});
  const view=createTestRenderer();
  globalThis.__testMetronome87=engine;
  globalThis.document={body:view.body,activeElement:null};
  const preset=meterConfig(7,8,95);
  preset.tempoUnit='eighth';
  preset.accentVolumes={strong:77,medium:41,weak:13};
  preset.subdivisionVolumePercent=62;
  preset.subdivision=3;
  preset.masterVolumePercent=67;
  fakeUiState.presets=[{id:'p95',name:'Seven',config:copyConfig(preset)}];
  let app;
  try {
    engine.applyConfig(meterConfig(4,4,80));
    engine.setStatus('running');
    const Dialog=await compileDialog();
    app=view.renderer.createApp({render:()=>h(Dialog)});
    app.mount(view.node('root'));
    await nextTick();
    const presetList=view.find('select',n=>n.props.id==='metronome-preset-list');
    assert.ok(presetList);
    presetList.props.onChange({target:{value:'p95'}});
    presetList.focus(); // simulate leaving the BPM input before asynchronous scheduler notifications
    await nextTick();
    view.find('button',n=>n.text==='metronome.loadPreset').props.onClick();
    await nextTick();
    engine.emitTick();
    await nextTick();
    assert.equal(engine.getConfig().bpm,80);
    assert.equal(engine.getPendingConfig().bpm,95);
    assert.equal(view.find('input',n=>n.props.id==='metronome-bpm').props.value,'95');
    const comparison=view.find('small',n=>n.props.class==='metronome-tempo-comparison');
    assert.ok(comparison,'separate sounding and queued tempo required');
    const flatten=node=>[node.text,...node.children.map(flatten)].join(' ');
    assert.match(flatten(comparison),/metronome.soundingTempo.*80/);
    assert.match(flatten(comparison),/metronome.queuedTempo.*95/);
    assert.match(flatten(comparison),/eighth/);
    view.find('button',n=>n.text==='6/8').props.onClick();
    await nextTick();
    assert.equal(engine.getPendingConfig().numerator,7,'draft is not committed before Apply');
    view.find('button',n=>n.text==='metronome.apply').props.onClick();
    await nextTick();
    const requested=engine.getPendingConfig();
    assert.equal(requested.numerator,6);
    assert.equal(requested.denominator,8);
    assert.equal(requested.bpm,95,'meter choice must not revert pending BPM');
    assert.equal(requested.tempoUnit,'dotted-quarter','6/8 offers its default beat unit');
    assert.deepEqual(requested.accentVolumes,preset.accentVolumes);
    assert.equal(requested.subdivisionVolumePercent,62);
    assert.equal(requested.subdivision,3);
    assert.equal(requested.masterVolumePercent,67);
    assert.deepEqual(requested.groups,[3,3]);
    assert.equal(engine.getConfig().bpm,80);
    engine.emitTick();
    await nextTick();
    assert.equal(view.find('input',n=>n.props.id==='metronome-bpm').props.value,'95',
      'scheduler ticks cannot replace requested BPM with sounding BPM');
    engine.transitionBar();
    await nextTick();
    assert.equal(engine.getConfig().bpm,95);
    assert.equal(engine.getPendingConfig(),null);
  } finally {
    app?.unmount();
    fakeUiState.presets=[];
    globalThis.document=previousDocument;
    delete globalThis.__testMetronome87;
  }
});

test('TOOL-02 opening during pending change shows requested BPM; +/- steps from it', async () => {
  const previousDocument=globalThis.document;
  const engine=fakeEngine({queueWhileRunning:true});
  const view=createTestRenderer();
  globalThis.__testMetronome87=engine;
  globalThis.document={body:view.body,activeElement:null};
  let app;
  try {
    engine.applyConfig(meterConfig(4,4,80));
    engine.setStatus('running');
    const next=meterConfig(7,8,95);
    next.tempoUnit='eighth';
    engine.applyConfig(next);
    const Dialog=await compileDialog();
    app=view.renderer.createApp({render:()=>h(Dialog)});
    app.mount(view.node('root'));
    await nextTick();
    assert.equal(engine.getState().bpm,80);
    assert.equal(view.find('input',n=>n.props.id==='metronome-bpm').props.value,'95');
    const faster=view.find('button',n=>n.props['aria-label']==='metronome.faster');
    assert.ok(faster);
    faster.props.onClick();
    await nextTick();
    assert.equal(engine.getState().bpm,96);
    assert.equal(engine.getPendingConfig().bpm,96);
  } finally {
    app?.unmount();
    globalThis.document=previousDocument;
    delete globalThis.__testMetronome87;
  }
});

test('TOOL-02 BPM +/- availability uses requested BPM rather than active playback bounds', async () => {
  const savedDocument=globalThis.document;
  const Dialog=await compileDialog();
  // Different active and requested BPM values can coexist until the next bar.
  for (const {activeBpm,pendingBpm,direction,result,disabled} of [
    {activeBpm:240,pendingBpm:95,direction:'faster',result:96,disabled:false},
    {activeBpm:20,pendingBpm:95,direction:'slower',result:94,disabled:false},
    {activeBpm:80,pendingBpm:240,direction:'faster',result:null,disabled:true},
    {activeBpm:80,pendingBpm:20,direction:'slower',result:null,disabled:true},
  ]) {
    const engine=fakeEngine({queueWhileRunning:true});
    const view=createTestRenderer();
    globalThis.__testMetronome87=engine;
    globalThis.document={body:view.body,activeElement:null};
    let app;
    try {
      engine.applyConfig(meterConfig(4,4,activeBpm));
      engine.setStatus('running');
      engine.applyConfig(meterConfig(7,8,pendingBpm));
      app=view.renderer.createApp({render:()=>h(Dialog)});
      app.mount(view.node('root'));
      await nextTick();
      const input=view.find('input',n=>n.props.id==='metronome-bpm');
      assert.equal(input.props.value,String(pendingBpm));
      const control=view.find('button',n=>n.props['aria-label']==='metronome.'+direction);
      assert.ok(control, direction+' control exists');
      assert.equal(Boolean(control.props.disabled),disabled,
        direction+' availability must follow requested '+pendingBpm+' BPM, not active '+activeBpm);
      if (!disabled) {
        control.props.onClick();
        await nextTick();
        assert.equal(engine.getState().bpm,result);
        assert.equal(engine.getPendingConfig().bpm,result);
        assert.equal(input.props.value,String(result));
      }
    } finally {
      app?.unmount();
      globalThis.document=savedDocument;
      delete globalThis.__testMetronome87;
    }
  }
});

test('TOOL-02 Discard clears invalid BPM and restores accepted settings and playback', async () => {
  const savedDocument=globalThis.document;
  const engine=fakeEngine();
  const view=createTestRenderer();
  globalThis.__testMetronome87=engine;
  globalThis.document={body:view.body,activeElement:null};
  let app;
  try {
    engine.applyConfig(meterConfig(4,4,80));
    const Dialog=await compileDialog();
    app=view.renderer.createApp({render:()=>h(Dialog)});
    app.mount(view.node('root'));
    await nextTick();
    const input=view.find('input',n=>n.props.id==='metronome-bpm');
    assert.ok(input);
    input.props.onInput({target:{value:'999'}});
    await nextTick();
    assert.equal(input.props.value,'999');
    assert.ok(view.find('p',n=>n.props.id==='metronome-error'),
      'invalid BPM should be visibly rejected');
    view.find('button',n=>n.text==='6/8').props.onClick();
    await nextTick();
    assert.ok(view.find('button',n=>n.text==='metronome.discard'));
    view.find('button',n=>n.text==='metronome.discard').props.onClick();
    await nextTick();
    assert.equal(input.props.value,'80','discard must restore last accepted BPM input');
    assert.equal(view.find('p',n=>n.props.id==='metronome-error'),undefined,
      'discard must clear stale BPM validation state');
    assert.equal(engine.getConfig().numerator,4,'discard must restore original meter');
    assert.equal(engine.getConfig().bpm,80);
    view.find('button',n=>n.text==='metronome.start').props.onClick();
    await nextTick();
    assert.equal(engine.starts,1,'Start must be usable after discarding invalid BPM');
  } finally {
    app?.unmount();
    globalThis.document=savedDocument;
    delete globalThis.__testMetronome87;
  }
});

test('TOOL-02 invalid BPM cannot be bypassed by changing meter and pressing Apply', async () => {
  const previousDocument=globalThis.document;
  const engine=fakeEngine({queueWhileRunning:true});
  const view=createTestRenderer();
  globalThis.__testMetronome87=engine;
  globalThis.document={body:view.body,activeElement:null};
  let app;
  try {
    engine.applyConfig(meterConfig(4,4,80));
    engine.setStatus('running');
    const Dialog=await compileDialog();
    app=view.renderer.createApp({render:()=>h(Dialog)});
    app.mount(view.node('root'));
    await nextTick();
    const input=view.find('input',n=>n.props.id==='metronome-bpm');
    input.props.onInput({target:{value:'999'}});
    await nextTick();
    view.find('button',n=>n.text==='6/8').props.onClick();
    await nextTick();
    const apply=view.find('button',n=>n.text==='metronome.apply');
    assert.equal(Boolean(apply.props.disabled),false,'meter change offers Apply');
    apply.props.onClick();
    await nextTick();
    assert.equal(engine.getConfig().numerator,4,'invalid BPM cannot alter audible meter');
    assert.equal(engine.getConfig().bpm,80);
    assert.equal(engine.getPendingConfig(),null,'invalid BPM cannot queue any meter change');
    assert.equal(input.props.value,'999','invalid typed value remains visible for correction');
    assert.ok(view.find('p',n=>n.props.id==='metronome-error'));
    assert.equal(Boolean(apply.props.disabled),false,'rejected rhythm edit remains cancellable');
    const discard=view.find('button',n=>n.text==='metronome.discard');
    discard.props.onClick();
    await nextTick();
    assert.equal(input.props.value,'80');
    assert.equal(view.find('p',n=>n.props.id==='metronome-error'),undefined);
  } finally {
    app?.unmount();
    globalThis.document=previousDocument;
    delete globalThis.__testMetronome87;
  }
});

test('TOOL-02 audio ticks never erase blurred invalid BPM without resolving its error', async () => {
  const previousDocument=globalThis.document;
  const engine=fakeEngine({queueWhileRunning:true});
  const view=createTestRenderer();
  globalThis.__testMetronome87=engine;
  globalThis.document={body:view.body,activeElement:null};
  let app;
  try {
    engine.applyConfig(meterConfig(4,4,80));
    engine.setStatus('running');
    const Dialog=await compileDialog();
    app=view.renderer.createApp({render:()=>h(Dialog)});
    app.mount(view.node('root'));
    await nextTick();
    const input=view.find('input',n=>n.props.id==='metronome-bpm');
    input.props.onInput({target:{value:'999'}});
    await nextTick();
    const stop=view.find('button',n=>n.text==='metronome.stop');
    stop.focus(); // editable BPM has lost focus, before the next audio tick
    for(let index=0;index<3;index++)engine.emitTick();
    await nextTick();
    assert.equal(input.props.value,'999','scheduler must not silently replace invalid draft');
    assert.ok(view.find('p',n=>n.props.id==='metronome-error'),
      'invalid BPM message must remain consistent with visible input');
    assert.equal(engine.getState().bpm,80);
    const discard=view.find('button',n=>n.text==='metronome.discard');
    assert.ok(discard);
    assert.equal(Boolean(discard.props.disabled),false,'invalid BPM alone can be discarded');
    discard.props.onClick();
    await nextTick();
    assert.equal(input.props.value,'80');
    assert.equal(view.find('p',n=>n.props.id==='metronome-error'),undefined);
    input.props.onInput({target:{value:'95'}});
    await nextTick();
    assert.equal(engine.getState().bpm,95,'valid correction still takes effect immediately');
    assert.equal(input.props.value,'95');
  } finally {
    app?.unmount();
    globalThis.document=previousDocument;
    delete globalThis.__testMetronome87;
  }
});

test('TOOL-02 explicitly loading a preset replaces invalid BPM and clears stale validation', async () => {
  const previousDocument=globalThis.document;
  const engine=fakeEngine();
  const view=createTestRenderer();
  globalThis.__testMetronome87=engine;
  globalThis.document={body:view.body,activeElement:null};
  fakeUiState.presets=[{id:'valid-95',name:'Valid preset',config:meterConfig(6,8,95)}];
  let app;
  try {
    engine.applyConfig(meterConfig(4,4,80));
    const Dialog=await compileDialog();
    app=view.renderer.createApp({render:()=>h(Dialog)});
    app.mount(view.node('root'));
    await nextTick();
    const input=view.find('input',n=>n.props.id==='metronome-bpm');
    input.props.onInput({target:{value:'999'}});
    await nextTick();
    view.find('select',n=>n.props.id==='metronome-preset-list')
      .props.onChange({target:{value:'valid-95'}});
    await nextTick();
    view.find('button',n=>n.text==='metronome.loadPreset').props.onClick();
    await nextTick();
    assert.equal(input.props.value,'95');
    assert.equal(view.find('p',n=>n.props.id==='metronome-error'),undefined);
    assert.equal(engine.getConfig().numerator,6);
    assert.equal(engine.getConfig().bpm,95);
  } finally {
    app?.unmount();
    fakeUiState.presets=[];
    globalThis.document=previousDocument;
    delete globalThis.__testMetronome87;
  }
});

test('TOOL-02 Save/Replace reject invalid visible BPM or group text without modifying presets', async () => {
  const previousDocument=globalThis.document;
  const Dialog=await compileDialog();
  for (const operation of ['save','replace']) {
    for (const invalidField of ['bpm','groups']) {
      const engine=fakeEngine();
      const view=createTestRenderer();
      globalThis.__testMetronome87=engine;
      globalThis.document={body:view.body,activeElement:null};
      const original=meterConfig(4,4,80);
      fakeUiState.presets=operation==='replace'
        ? [{id:'existing',name:'Existing',config:copyConfig(original)}] : [];
      let app;
      try {
        engine.applyConfig(original);
        app=view.renderer.createApp({render:()=>h(Dialog)});
        app.mount(view.node('root'));
        await nextTick();
        if (invalidField==='bpm') {
          view.find('input',n=>n.props.id==='metronome-bpm')
            .props.onInput({target:{value:'999'}});
        } else {
          view.find('input',n=>n.props.id==='metronome-groups')
            .props.onInput({target:{value:'3+3'}});
        }
        await nextTick();
        if (operation==='save') {
          view.find('input',n=>n.props.id==='metronome-preset-name')
            .props.onInput({target:{value:'New preset'}});
          await nextTick();
          view.find('button',n=>n.text==='metronome.savePreset').props.onClick();
        } else {
          view.find('select',n=>n.props.id==='metronome-preset-list')
            .props.onChange({target:{value:'existing'}});
          await nextTick();
          view.find('button',n=>n.text==='metronome.replacePreset').props.onClick();
          await nextTick();
          view.find('button',n=>n.text==='metronome.confirm').props.onClick();
        }
        await nextTick();
        assert.equal(fakeUiState.presets.length,operation==='save'?0:1,
          operation+' must not create or delete any preset with invalid '+invalidField);
        if(operation==='replace')
          assert.deepEqual(fakeUiState.presets[0].config,original,
            'Replace must never overwrite accepted preset with stale draft settings');
        assert.deepEqual(engine.getConfig(),original,
          'preset validation does not change playing/accepted metronome config');
        if(invalidField==='bpm') {
          assert.equal(view.find('input',n=>n.props.id==='metronome-bpm').props.value,'999');
          assert.ok(view.find('p',n=>n.props.id==='metronome-error'),
            'invalid BPM must remain visible for correction');
        } else {
          assert.equal(view.find('input',n=>n.props.id==='metronome-groups').props.value,'3+3');
          assert.ok(view.find('p',n=>n.props.class==='metronome-error' &&
            n.text==='metronome.invalidGroups'),
            'invalid group error should identify the exact field');
        }
      } finally {
        app?.unmount();
        fakeUiState.presets=[];
        globalThis.document=previousDocument;
        delete globalThis.__testMetronome87;
      }
    }
  }
});

test('TOOL-02 Save/Replace capture valid typed grouping without waiting for a change/blur event', async () => {
  const previousDocument=globalThis.document;
  const Dialog=await compileDialog();
  for(const operation of ['save','replace']) {
    const engine=fakeEngine();
    const view=createTestRenderer();
    globalThis.__testMetronome87=engine;
    globalThis.document={body:view.body,activeElement:null};
    const original=meterConfig(4,4,80);
    fakeUiState.presets=operation==='replace'
      ? [{id:'existing',name:'Existing',config:copyConfig(original)}] : [];
    let app;
    try {
      engine.applyConfig(original);
      app=view.renderer.createApp({render:()=>h(Dialog)});
      app.mount(view.node('root'));
      await nextTick();
      view.find('button',n=>n.text==='7/8').props.onClick();
      await nextTick();
      const groupInput=view.find('input',n=>n.props.id==='metronome-groups');
      groupInput.props.onInput({target:{value:'3+2+2'}});
      // Deliberately do NOT call onChange: the Save/Replace command itself
      // must reconcile the visible grouping, even in an unblurred editor.
      await nextTick();
      if(operation==='save') {
        view.find('input',n=>n.props.id==='metronome-preset-name')
          .props.onInput({target:{value:'Seven'}});
        await nextTick();
        view.find('button',n=>n.text==='metronome.savePreset').props.onClick();
      } else {
        view.find('select',n=>n.props.id==='metronome-preset-list')
          .props.onChange({target:{value:'existing'}});
        await nextTick();
        view.find('button',n=>n.text==='metronome.replacePreset').props.onClick();
        await nextTick();
        view.find('button',n=>n.text==='metronome.confirm').props.onClick();
      }
      await nextTick();
      assert.equal(fakeUiState.presets.length,1);
      const saved=fakeUiState.presets[0].config;
      assert.equal(saved.numerator,7);
      assert.deepEqual(saved.groups,[3,2,2],
        operation+' must reflect the visible new grouping, not stale 2+2+3');
      assert.deepEqual(saved.positions.map(p=>p.accent),
        ['strong','weak','weak','medium','weak','medium','weak'],
        'newly typed group starts must receive suggested accents');
      assert.equal(engine.getConfig().numerator,4,
        'saving a draft is distinct from applying the meter to playback');
    } finally {
      app?.unmount();
      fakeUiState.presets=[];
      globalThis.document=previousDocument;
      delete globalThis.__testMetronome87;
    }
  }
});



test('TOOL-04 B1 provides quick BPM, Tap, 7/8 and editable accent strip without altering sound until Apply', async () => {
  const savedDocument = globalThis.document;
  const engine = fakeEngine();
  const view = createTestRenderer();
  globalThis.__testMetronome87 = engine;
  globalThis.document = { body:view.body, activeElement:null };
  let app;
  try {
    const Dialog = await compileDialog();
    app = view.renderer.createApp({ render:()=>h(Dialog) });
    app.mount(view.node('root'));
    await nextTick();
    await nextTick();
    assert.ok(view.find('input', n=>n.props.id==='metronome-bpm' && n.props.type==='number'),
      'large BPM is a real keyboard-editable number input');
    assert.ok(view.find('button', n=>n.props['aria-label']==='metronome.slower'));
    assert.ok(view.find('button', n=>n.props['aria-label']==='metronome.faster'));
    assert.ok(view.find('button', n=>String(n.text).includes('metronome.tap')),
      'Tap Tempo remains on the primary screen');
    assert.ok(view.find('button', n=>n.text==='metronome.start'),
      'Start is independent from the detailed panel');
    const quick=view.find('button', n=>n.text==='7/8');
    assert.ok(quick,'asymmetric 7/8 is one tap away');
    quick.props.onClick();
    await nextTick();
    const strip=view.find('div',n=>n.props.class==='metronome-count');
    assert.equal(strip.children.filter(n=>n.tag==='button').length,7);
    assert.equal(engine.getConfig().numerator,4,'the quick meter is only a draft before Apply');
    view.find('button',n=>n.text==='metronome.apply').props.onClick();
    await nextTick();
    assert.equal(engine.getConfig().numerator,7);
    assert.deepEqual(engine.getConfig().groups,[2,2,3]);
    assert.equal(engine.getConfig().tempoUnit,'quarter');
    assert.ok(view.find('button',n=>n.props['aria-label']==='metronome.subdivisionCount{"count":3}'),
      'subdivision has an accessible text alternative');
  } finally {
    app?.unmount();
    globalThis.document=savedDocument;
    delete globalThis.__testMetronome87;
  }
});

test('TOOL-04 panels keep deep metronome controls and Escape closes panel before modal', async () => {
  const savedDocument=globalThis.document;
  const engine=fakeEngine();
  const view=createTestRenderer();
  let closed=0;
  globalThis.__testMetronome87=engine;
  globalThis.document={body:view.body,activeElement:null};
  let app;
  try {
    const Dialog=await compileDialog();
    app=view.renderer.createApp({render:()=>h(Dialog,{onClose:()=>closed++})});
    app.mount(view.node('root'));
    await nextTick();
    assert.ok(view.find('div',n=>n.props.class==='m111-sheet'));
    const dialog=view.find('section',n=>n.props.role==='dialog');
    view.find('div',n=>n.props.class==='m111-tools').children.filter(n=>n.tag==='button')[1].props.onClick();
    await nextTick();
    assert.ok(view.find('div',n=>n.props.class==='m111-sheet is-open'),
      'settings panel is open without destroying the metronome');
    assert.ok(view.find('input',n=>n.props.id==='metronome-groups'));
    assert.ok(view.find('input',n=>n.props.id==='metronome-master'));
    assert.ok(view.find('input',n=>n.props.id==='metronome-position-volume'));
    dialog.props.onKeydown({key:'Escape',preventDefault(){},stopPropagation(){}});
    await nextTick();
    assert.equal(closed,0,'Escape in open panel does not close the entire tool');
    assert.equal(view.find('div',n=>n.props.class==='m111-sheet is-open'),undefined);
    view.find('div',n=>n.props.class==='m111-tools').children.filter(n=>n.tag==='button')[0].props.onClick();
    await nextTick();
    assert.ok(view.find('select',n=>n.props.id==='metronome-preset-list'));
    assert.ok(view.find('button',n=>n.text==='metronome.savePreset'));
    dialog.props.onKeydown({key:'Escape',preventDefault(){},stopPropagation(){}});
    await nextTick();
    dialog.props.onKeydown({key:'Escape',preventDefault(){},stopPropagation(){}});
    await nextTick();
    assert.equal(closed,1,'Escape on the base dialog preserves the original close contract');
  } finally {
    app?.unmount();
    globalThis.document=savedDocument;
    delete globalThis.__testMetronome87;
  }
});


test('TOOL-04 CSS preserves the beat strip height and hides inactive Apply/Discard row', async () => {
  const css = await readFile(new URL('../src/assets/main.css', import.meta.url), 'utf8');
  // Regression from real Chrome screenshot (22.10.2026): without these rules
  // the flex child became a horizontal sliver and its click targets were lost.
  assert.match(css, /\.m111\s+\.metronome-count\s*\{\s*flex-shrink:\s*0\s*;\s*min-height:\s*62px\s*;/,
    'each beat row must retain enough vertical space to display accent controls');
  assert.match(css, /\.m111\s+\.metronome-apply\.m111-apply\.m111-apply-quiet\s*\{\s*display:\s*none\s*;/,
    'no-change Apply row must really be hidden despite the base flex rule');
});


test('TOOL-04 portrait mobile uses available viewport height for subdivision controls', async () => {
  const css = await readFile(new URL('../src/assets/main.css', import.meta.url), 'utf8');
  assert.match(css, /@media\s*\(max-width:650px\)\s*\{\s*\.metronome-dialog\.m111\s*\{\s*height:min\(760px,calc\(100dvh - 12px\)\)/,
    '400x832 must not be capped at a 640px tall modal while the rhythm panel clips');
  assert.match(css, /@media\s*\(max-width:650px\) and \(max-height:700px\) and \(min-height:561px\)/,
    '360x640 requires distinct compact spacing instead of hidden beat/subdivision controls');
  assert.match(css, /@media\s*\(max-width:650px\) and \(max-height:560px\)/,
    'very short mobile viewports must keep a scrollable accessibility fallback');
});
