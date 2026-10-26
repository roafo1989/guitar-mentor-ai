import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import ts from 'typescript';
import { parse, compileScript } from 'vue/compiler-sfc';
import { createRenderer, h, nextTick, reactive } from 'vue';
import { createI18n } from 'vue-i18n';

// Node has no DOM Document; Vue v-model checks instanceof Document on updates.
globalThis.Document ??= class Document {};
globalThis.ShadowRoot ??= class ShadowRoot {};
const moduleUrl = code => 'data:text/javascript,' + encodeURIComponent(code);
let component;
async function getComponent() {
  if (component) return component;
  const source = await readFile(new URL('../src/entities/onboarding/OnboardingView.vue', import.meta.url), 'utf8');
  const { descriptor } = parse(source, { filename: 'OnboardingView.vue' });
  const script = compileScript(descriptor, { id: 'onboarding-127', inlineTemplate: true });
  let js = ts.transpileModule(script.content, { compilerOptions: {
    target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ESNext,
  } }).outputText;
  const store = moduleUrl('export const useLearnerStore = () => globalThis.__learner127;');
  const service = moduleUrl('export const onboardingService = {get: async (...args) => globalThis.__background127.get(...args), save: async (...args) => globalThis.__background127.save(...args)};');
  const failure = moduleUrl('export const apiFailureKey = () => "apiErrors.network"; export const asApiFailure = value => value;');
  const sources = {
    vue: import.meta.resolve('vue'),
    'vue-i18n': import.meta.resolve('vue-i18n'),
    '../../core/auth/auth': moduleUrl('export const auth = {actor:null}; export const secureUi=false;'),
    '../../shared/unsaved-changes': moduleUrl('export const useUnsavedChanges=()=>{};'),
    '../../core/home/home-summary': moduleUrl('export const resolveHomeSummary=async(...args)=>globalThis.__summary229 ? globalThis.__summary229(...args) : ({step:"diagnostics"});'),
    '../../core/home/home-next-action': moduleUrl('export const homeNextTarget=()=>"/diagnostics";'),
    '../../stores/learner.store': store,
    './onboarding.service': service,
    '../../shared/api-failure': failure,
  };
  js = js.replace(/from (['"])(.*?)\1/g, (_all, _q, path) => {
    assert.ok(sources[path], 'unknown import: ' + path);
    return 'from ' + JSON.stringify(sources[path]);
  });
  component = (await import(moduleUrl(js))).default;
  return component;
}
function renderer() {
  const node = (tag, text = '') => ({
    tag, tagName: String(tag).toUpperCase(), text, children: [], parent: null, props: {},
    value: '', checked: false, multiple: false, selectedIndex: -1,
    addEventListener() {}, removeEventListener() {}, dispatchEvent() {},
    getRootNode() { return { activeElement: null }; },
    get options() { return this.children.filter(child => child.tag === 'option'); },
  });
  const remove = n => {
    const children = n.parent?.children;
    if (children) { const index = children.indexOf(n); if (index >= 0) children.splice(index, 1); }
    n.parent = null;
  };
  return createRenderer({
    createElement: tag => node(tag), createText: text => node('#text', text),
    createComment: text => node('#comment', text),
    setText: (n, text) => { n.text = text; },
    setElementText: (n, text) => { n.text = text; n.children = []; },
    patchProp: (n, key, _prev, next) => { n.props[key] = next; },
    insert: (n, parent, anchor = null) => {
      if (n.parent) remove(n);
      n.parent = parent;
      const index = parent.children.indexOf(anchor);
      if (index < 0) parent.children.push(n);
      else parent.children.splice(index, 0, n);
    },
    remove, parentNode: n => n.parent,
    nextSibling: n => n.parent?.children[n.parent.children.indexOf(n) + 1] ?? null,
  });
}
const nodeText = node => [node.text ?? '', ...(node.children ?? []).map(nodeText)].join(' ');
const flatten = node => [node, ...(node.children ?? []).flatMap(flatten)];
async function settle() {
  for (let i = 0; i < 15; i++) { await Promise.resolve(); await nextTick(); }
}
const background = notes => ({
  experienceLevel: 'BEGINNER', yearsPlaying: 1, skills: [],
  knownPieces: [], goals: ['FINGERSTYLE'], practiceMinutes: 40,
  sessionsPerWeek: 3, notes,
});

test('CORE-E failed background GET prevents PUT; recovery GET enables form without write retry', async () => {
  const oldWindow = globalThis.window;
  const learner = reactive({ learner: { id: 'learner-one' }, error: null, restore: async () => {} });
  const calls = [];
  let fail = true;
  globalThis.__learner127 = learner;
  globalThis.__background127 = {
    get: async id => { calls.push(['GET', id]); if (fail) throw new Error('offline'); return background('safe'); },
    save: async id => { calls.push(['PUT', id]); return background('saved'); },
  };
  globalThis.window = { localStorage: { getItem: () => 'learner-one' } };
  let app;
  try {
    const View = await getComponent();
    const root = { tag: 'root', children: [], parent: null };
    app = renderer().createApp({ render: () => h(View) });
    const i18n = createI18n({ legacy: false, locale: 'en', messages: {
      en: { apiErrors: { network: 'Offline' }, home: { retry: 'Retry' },
        onboarding: { save: 'Save', step: 'Step {step}' }, form: { saving: 'Saving' } },
      ru: { apiErrors: { network: 'Нет связи' }, home: { retry: 'Повторить' },
        onboarding: { save: 'Сохранить', step: 'Шаг {step}' }, form: { saving: 'Сохранение' } },
    } });
    app.use(i18n);
    app.mount(root);
    await settle();
    let nodes = flatten(root);
    assert.ok(nodes.some(n => n.text === 'Offline'), 'the failed GET is visible');
    i18n.global.locale.value = 'ru';
    await nextTick();
    nodes = flatten(root);
    assert.ok(nodes.some(n => n.text === 'Нет связи'), 'already displayed error translates immediately');
    assert.equal(calls.filter(([method]) => method === 'PUT').length, 0);
    const nextStep = nodes.find(n => n.tag === 'button' && nodeText(n).includes('onboarding.next'));
    assert.ok(nextStep, 'first step navigation exists');
    nodes.find(n => n.tag === 'form').props.onSubmit({preventDefault(){}});
    await settle();
    flatten(root).find(n => n.tag === 'form').props.onSubmit({preventDefault(){}});
    await settle();
    nodes = flatten(root);
    const saveButton = nodes.find(n => n.tag === 'button' && nodeText(n).includes('Сохранить'));
    assert.ok(saveButton?.props.disabled, 'unsafe PUT is disabled when GET failed');
    // Invoke the actual form submit handler: guard must also live in save(), not only in the template.
    const form = nodes.find(n => n.tag === 'form');
    assert.ok(form?.props.onSubmit);
    form.props.onSubmit({ preventDefault() {} });
    await settle();
    assert.equal(calls.filter(([method]) => method === 'PUT').length, 0);
    fail = false;
    const retry = nodes.find(n => n.tag === 'button' && nodeText(n).includes('Повторить'));
    assert.ok(retry, 'read-only retry exists');
    retry.props.onClick();
    await settle();
    assert.deepEqual(calls.map(([m]) => m), ['GET', 'GET']);
    nodes = flatten(root);
    const recoveredSave = nodes.find(n => n.tag === 'button' && nodeText(n).includes('Сохранить'));
    assert.equal(recoveredSave?.props.disabled, false, 'successful read unlocks explicit saving');
    // Changing the learner should invalidate old state and fetch for the new context.
    learner.learner = { id: 'learner-two' };
    await settle();
    assert.equal(calls.at(-1)[1], 'learner-two');
  } finally {
    app?.unmount();
    globalThis.window = oldWindow;
    delete globalThis.__learner127;
    delete globalThis.__background127;
  }
});

test('P1: edited draft after failed GET cannot overwrite existing background on Retry', async () => {
  const oldWindow = globalThis.window;
  const learner = reactive({ learner: { id: 'learner-one' }, error: null, restore: async () => {} });
  const calls = [];
  let fail = true;
  globalThis.__learner127 = learner;
  globalThis.__background127 = {
    get: async () => { calls.push('GET'); if (fail) throw new Error('offline'); return background('preserved server notes'); },
    save: async () => { calls.push('PUT'); return background('lost'); },
  };
  globalThis.window = { localStorage: { getItem: () => 'learner-one' } };
  let app;
  try {
    const View = await getComponent();
    const root = { tag: 'root', children: [], parent: null };
    app = renderer().createApp({ render: () => h(View) });
    app.use(createI18n({ legacy: false, locale: 'en', messages: { en: {
      home: { retry: 'Retry' }, onboarding: { next: 'Next', save: 'Save', loadServerBackground: 'Load server' },
    } } }));
    app.mount(root);
    await settle();
    let nodes = flatten(root);
    const form = nodes.find(n => n.tag === 'form');
    assert.ok(form?.props.onInput, 'edit tracking on form');
    form.props.onInput({ target: {} });
    fail = false;
    const retry = nodes.find(n => n.tag === 'button' && nodeText(n).includes('Retry'));
    retry.props.onClick();
    await settle();
    nodes = flatten(root);
    const next = nodes.find(n => n.tag === 'button' && nodeText(n).includes('Next'));
    nodes.find(n => n.tag === 'form').props.onSubmit({preventDefault(){}});
    await settle();
    flatten(root).find(n => n.tag === 'form').props.onSubmit({preventDefault(){}});
    await settle();
    nodes = flatten(root);
    const save = nodes.find(n => n.tag === 'button' && nodeText(n).includes('Save'));
    assert.equal(save?.props.disabled, true, 'saved background and local draft must be reconciled');
    nodes.find(n => n.tag === 'form').props.onSubmit({ preventDefault() {} });
    await settle();
    assert.deepEqual(calls, ['GET', 'GET'], 'no silent overwrite');
    const accept = nodes.find(n => n.tag === 'button' && nodeText(n).includes('Load server'));
    assert.ok(accept?.props.onClick, 'user gets an explicit conflict-resolution action');
    accept.props.onClick();
    await settle();
    nodes = flatten(root);
    const unlocked = nodes.find(n => n.tag === 'button' && nodeText(n).includes('Save'));
    assert.equal(unlocked?.props.disabled, false, 'explicit loading unlocks PUT. DOM=' + JSON.stringify(nodes.filter(n => n.tag === 'button').map(n => ({text:n.text,disabled:n.props.disabled}))) + ' conflict=' + nodes.map(n=>String(n.text||'')).filter(x=>x.includes('Load server')).join('|'));
  } finally {
    app?.unmount(); globalThis.window = oldWindow;
    delete globalThis.__learner127; delete globalThis.__background127;
  }
});

test('P1: late PUT for learner A cannot replace background or saved badge for learner B', async () => {
  const oldWindow = globalThis.window;
  const learner = reactive({ learner: { id: 'learner-A' }, error: null, restore: async () => {} });
  const calls = [];
  let resolveSave;
  const pending = new Promise(resolve => { resolveSave = resolve; });
  globalThis.__learner127 = learner;
  globalThis.__background127 = {
    get: async id => { calls.push(['GET', id]); return background('notes ' + id); },
    save: async id => { calls.push(['PUT', id]); return pending; },
  };
  globalThis.window = { localStorage: { getItem: () => 'learner-A' } };
  let app;
  try {
    const View = await getComponent();
    const root = { tag: 'root', children: [], parent: null };
    app = renderer().createApp({ render: () => h(View) });
    app.use(createI18n({ legacy: false, locale: 'en', messages: { en: {
      onboarding: { next: 'Next', save: 'Save', saved: 'Saved' },
    } } }));
    app.mount(root);
    await settle();
    let nodes = flatten(root);
    nodes.find(n => n.tag === 'form').props.onSubmit({preventDefault(){}});
    await settle();
    flatten(root).find(n => n.tag === 'form').props.onSubmit({preventDefault(){}});
    await settle();
    nodes = flatten(root);
    assert.equal(nodes.find(n => n.tag === 'button' && nodeText(n).includes('Save')).props.disabled, false);
    nodes.find(n => n.tag === 'form').props.onSubmit({ preventDefault() {} });
    await settle();
    assert.ok(calls.some(([method,id]) => method === 'PUT' && id === 'learner-A'));
    learner.learner = { id: 'learner-B' };
    await settle();
    resolveSave(background('OLD A RESULT'));
    await settle();
    nodes = flatten(root);
    const text = nodes.map(n => String(n.text ?? '')).join(' ');
    assert.ok(!text.includes('Saved'), 'old PUT cannot announce success for new learner');
    assert.ok(calls.some(([method,id]) => method === 'GET' && id === 'learner-B'));
    assert.equal(nodes.some(n => String(n.value ?? '') === 'OLD A RESULT'), false);
  } finally {
    app?.unmount(); globalThis.window = oldWindow;
    delete globalThis.__learner127; delete globalThis.__background127;
  }
});

async function mount229(service, summary) {
  const oldWindow = globalThis.window;
  const learner = reactive({ learner: { id: 'A' }, error: null, restore: async () => {} });
  globalThis.__learner127 = learner;
  globalThis.__background127 = service;
  globalThis.__summary229 = summary;
  globalThis.window = { localStorage: { getItem: () => 'A' } };
  const root = { tag: 'root', children: [], parent: null };
  const View = await getComponent();
  const app = renderer().createApp({render: () => h(View)});
  const en = JSON.parse(await readFile(new URL('../src/i18n/en.json', import.meta.url), 'utf8'));
  app.use(createI18n({legacy:false,locale:'en',messages:{en},missingWarn:false,fallbackWarn:false}));
  app.mount(root); await settle();
  return { learner, root, nodes:()=>flatten(root),
    submit:async()=>{ flatten(root).find(n=>n.tag==='form').props.onSubmit({preventDefault(){}}); await settle(); },
    button: text=>flatten(root).find(n=>n.tag==='button'&&(nodeText(n)+' '+(n.props?.['aria-label']??'')).includes(text)),
    cleanup:()=>{app.unmount();globalThis.window=oldWindow;delete globalThis.__learner127;delete globalThis.__background127;delete globalThis.__summary229;},
  };
}
const deferred229=()=>{let resolve,reject;const promise=new Promise((a,b)=>{resolve=a;reject=b;});return {promise,resolve,reject};};

test('#229 three steps preserve known piece fields including literal |; one save on double submit; next-action retry is read only',async()=>{
  const pending=deferred229();let puts=0,nextReads=0,payload;
  const initial={...background('draft'),knownPieces:[{title:'A | B',artist:'C | D'}]};
  const h=await mount229({get:async()=>initial,save:async(_id,_code,data)=>{puts++;payload=data;return pending.promise;}},async()=>{nextReads++;if(nextReads===1)throw Error('offline');return {step:'result'};});
  try {
    await h.submit();assert.ok(h.nodes().some(n=>n.text==='Learning goals') || h.nodes().some(n=>nodeText(n).includes('goalsTitle')));
    h.button('Back').props.onClick();await settle();
    assert.ok(h.nodes().some(n=>n.value==='A | B'));
    assert.ok(h.nodes().some(n=>n.value==='C | D'));
    await h.submit();await h.submit();
    const form=h.nodes().find(n=>n.tag==='form');form.props.onSubmit({preventDefault(){}});form.props.onSubmit({preventDefault(){}});await settle();
    assert.equal(puts,1);assert.deepEqual(payload.knownPieces,initial.knownPieces);
    pending.resolve(initial);await settle();
    assert.equal(nextReads,1);assert.ok(h.button('Try again'));
    h.button('Try again').props.onClick();await settle();assert.equal(nextReads,2);assert.equal(puts,1);
    assert.ok(h.nodes().some(n=>nodeText(n).includes('Choose') || nodeText(n).includes('next lesson')),'completion offers real resolved action');
  }finally{h.cleanup();}
});

test('#229 failed save keeps draft; explicit retry persists and completion cannot submit again',async()=>{
  let puts=0;
  const initial={...background('keep this'),knownPieces:[{title:'Yesterday',artist:'Beatles'}]};
  const h=await mount229({get:async()=>initial,save:async()=>{if(++puts===1)throw Error('offline');return initial;}});
  try {
    await h.submit();await h.submit();await h.submit();assert.equal(puts,1);assert.ok(h.button('Save'));
    h.button('Back').props.onClick();await settle();assert.ok(h.nodes().some(n=>n.value==='keep this'));
    await h.submit();await h.submit();assert.equal(puts,2);
    assert.equal(h.nodes().some(n=>n.tag==='form'),false);assert.ok(h.nodes().some(n=>n.props?.role==='status'));
  }finally{h.cleanup();}
});

test('#229 late background read cannot apply to another learner',async()=>{
  const pending=deferred229();
  const h=await mount229({get:async id=>id==='A'?pending.promise:background('B only'),save:async()=>{throw Error('must not write');}});
  try {
    h.learner.learner={id:'B'};await settle();pending.resolve(background('secret A'));await settle();
    await h.submit();assert.ok(h.nodes().some(n=>n.value==='B only'));assert.equal(h.nodes().some(n=>n.value==='secret A'),false);
  }finally{h.cleanup();}
});

test('#229 numeric limits and known-piece maximum reject unsafe wizard progression',async()=>{
  for(const years of [-1,61,0.5]){
    const h=await mount229({get:async()=>({...background(''),yearsPlaying:years}),save:async()=>{throw Error('must not write');}});
    try{await h.submit();assert.ok(h.nodes().some(n=>nodeText(n).includes('Check your answers')));assert.equal(h.button('Back'),undefined);}finally{h.cleanup();}
  }
  const h=await mount229({get:async()=>({...background(''),knownPieces:Array.from({length:10},()=>({title:'valid',artist:''}))}),save:async()=>{throw Error('must not write');}});
  try {
    assert.equal(h.button('Add piece').props.disabled,true);
    h.button('Remove').props.onClick();await settle();assert.equal(h.button('Add piece').props.disabled,false);
    await h.submit();await h.submit();
    const minutes=h.nodes().find(n=>n.tag==='input'&&n.props.min==='10');
    minutes.props['onUpdate:modelValue'](9);await h.submit();
    assert.ok(h.nodes().some(n=>nodeText(n).includes('Check your answers')));
  }finally{h.cleanup();}
});

test('#229 acceptance: explicit Save changes on steps 1 and 2 persists without leaving the step or resolving next action',async()=>{
 const pending=deferred229();let puts=0,resolutions=0,payload;
 const initial={...background('unchanged rhythm'),knownPieces:[{title:'Yesterday',artist:'Beatles'}]};
 const h=await mount229({get:async()=>initial,save:async(_id,_code,data)=>{puts++;payload=data;return puts===1?pending.promise:data;}},async()=>{resolutions++;return {step:'diagnostics'};});
 try {
  const years=h.nodes().find(n=>n.tag==='input'&&n.props?.max==='60');
  years.props['onUpdate:modelValue'](4);h.nodes().find(n=>n.tag==='form').props.onInput();
  const save=h.button('Save changes');assert.ok(save);save.props.onClick();save.props.onClick();await settle();assert.equal(puts,1,'double click is guarded');
  pending.resolve(payload);await settle();
  assert.ok(h.nodes().some(n=>n.tag==='input'&&n.props?.max==='60'),'still on experience step');
  assert.ok(h.nodes().some(n=>n.props?.class==='saved-confirmation'&&n.props.role==='status'));
  assert.equal(resolutions,0);assert.equal(payload.yearsPlaying,4);assert.equal(payload.practiceMinutes,40);assert.deepEqual(payload.knownPieces,initial.knownPieces);
  h.button('Remove').props.onClick();await settle();assert.equal(h.nodes().some(n=>n.props?.class==='saved-confirmation'),false,'removing a piece invalidates confirmation');
  await h.submit();assert.ok(h.nodes().some(n=>n.tag==='textarea'),'now on goals step');
  const notes=h.nodes().find(n=>n.tag==='textarea');notes.props['onUpdate:modelValue']('updated goals');h.nodes().find(n=>n.tag==='form').props.onInput();await settle();
  assert.equal(h.nodes().some(n=>n.props?.class==='saved-confirmation'),false,'editing removes old confirmation');
  h.button('Save changes').props.onClick();await settle();
  assert.equal(puts,2);assert.equal(payload.notes,'updated goals');assert.ok(h.nodes().some(n=>n.tag==='textarea'));assert.equal(resolutions,0);
  await h.submit();await h.submit();assert.equal(puts,3);assert.equal(resolutions,1,'final save resolves next action');
 }finally{h.cleanup();}
});

test('#229 acceptance: early save preserves failed-write draft and requires full-object validation',async()=>{
 let puts=0;let fail=true;
 const h=await mount229({get:async()=>background('existing'),save:async(_id,_code,data)=>{puts++;if(fail)throw Error('offline');return data;}});
 try{
  const years=h.nodes().find(n=>n.tag==='input'&&n.props?.max==='60');years.props['onUpdate:modelValue'](7);h.nodes().find(n=>n.tag==='form').props.onInput();
  h.button('Save changes').props.onClick();await settle();assert.equal(puts,1);assert.ok(h.nodes().some(n=>n.value===7));assert.equal(h.nodes().some(n=>n.props?.class==='saved-confirmation'),false);
  fail=false;h.button('Save changes').props.onClick();await settle();assert.equal(puts,2);assert.ok(h.nodes().some(n=>n.props?.class==='saved-confirmation'));
 }finally{h.cleanup();}
 const invalid=await mount229({get:async()=>({...background('existing'),sessionsPerWeek:8}),save:async()=>{throw Error('invalid hidden field must not be sent');}});
 try{invalid.button('Save changes').props.onClick();await settle();assert.ok(invalid.nodes().some(n=>nodeText(n).includes('Check your answers')));}finally{invalid.cleanup();}
});

test('#229 acceptance: failed GET blocks early Save changes until successful read and explicit conflict resolution',async()=>{
 let fail=true,puts=0;
 const h=await mount229({get:async()=>{if(fail)throw Error('offline');return background('server');},save:async()=>{puts++;return background('saved');}});
 try{
  assert.equal(h.button('Save changes').props.disabled,true);
  h.button('Save changes').props.onClick();await settle();assert.equal(puts,0);
  h.nodes().find(n=>n.tag==='form').props.onInput();fail=false;h.button('Try again').props.onClick();await settle();
  assert.equal(h.button('Save changes').props.disabled,true);h.button('Save changes').props.onClick();await settle();assert.equal(puts,0);
  h.button('Load saved background').props.onClick();await settle();assert.equal(h.button('Save changes').props.disabled,false);
 }finally{h.cleanup();}
});
