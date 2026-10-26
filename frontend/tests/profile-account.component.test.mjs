import test from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import ts from 'typescript';
import {parse,compileScript} from 'vue/compiler-sfc';
import {createRenderer,h,nextTick,reactive,ref} from 'vue';
import {createI18n} from 'vue-i18n';
globalThis.Document ??= class Document {};
globalThis.ShadowRoot ??= class ShadowRoot {};
const url=code=>'data:text/javascript,'+encodeURIComponent(code);
const cache=new Map();
async function component(path){
 if(cache.has(path))return cache.get(path);
 const source=await readFile(new URL('../src/'+path,import.meta.url),'utf8');
 const {descriptor}=parse(source,{filename:path});
 const script=compileScript(descriptor,{id:'qa229',inlineTemplate:true});
 let js=ts.transpileModule(script.content,{compilerOptions:{target:ts.ScriptTarget.ES2022,module:ts.ModuleKind.ESNext}}).outputText;
 const sources={
  vue:import.meta.resolve('vue'),'vue-i18n':import.meta.resolve('vue-i18n'),
  '../../stores/learner.store':url('export const useLearnerStore=()=>globalThis.__store229;'),
  '../../shared/api-failure':url('export const apiFailureKey=()=>"apiErrors.network";export const asApiFailure=x=>x;'),
  '../../core/auth/auth':url('export const secureUi=true;export const auth=globalThis.__auth229;'),
  './account-link':url('export const takeAccountLinkToken=()=>null;'),
  './auth':url('export const auth=globalThis.__auth229;export const refreshActor=async()=>{};'),
  '../../shared/unsaved-changes':url('export const useUnsavedChanges=(dirty)=>{globalThis.__dirty229=dirty};'),
  '../onboarding/onboarding.service':url('export const onboardingService={get:(...args)=>globalThis.__background229(...args)};'),
  '../diagnostic/diagnostic.service':url('export const diagnosticService={skillMap:(...args)=>globalThis.__skills229(...args)};'),
  './ProfileLearningSummary.vue':url('export default {render:()=>null};'),
 };
 js=js.replace(/from (['"])(.*?)\1/g,(_a,_q,path)=>{assert.ok(sources[path],'unknown '+path);return 'from '+JSON.stringify(sources[path]);});
 const view=(await import(url(js))).default;cache.set(path,view);return view;
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
const flatten = node => [node, ...(node.children ?? []).flatMap(flatten)];
async function settle() {
  for (let i = 0; i < 15; i++) { await Promise.resolve(); await nextTick(); }
}

const en=JSON.parse(await readFile(new URL('../src/i18n/en.json',import.meta.url),'utf8'));
const guitar={instrumentCode:'ACOUSTIC_GUITAR',techniqueCode:'FINGERSTYLE',preferences:[{styleCode:'ROCK',rating:'LIKE'}]};
const profile=(id='A',displayName='Alice')=>({id,displayName,instruments:[guitar]});
const actor=reactive({actor:{accountId:'account-A',learnerId:'A'}});
globalThis.__auth229=actor;
async function mount(path,store,props={}){
 const oldWindow=globalThis.window;const confirms=[];
 globalThis.window={location:{href:'https://example.test/settings/account'},history:{replaceState(){}},confirm:message=>{confirms.push(message);return false;}};
 globalThis.__store229=store;actor.actor={accountId:'account-A',learnerId:'A'};
 const root={tag:'root',children:[],parent:null};
 const View=await component(path);
 const app=renderer().createApp({render:()=>h(View,props)});
 app.use(createI18n({legacy:false,locale:'en',messages:{en},missingWarn:false,fallbackWarn:false}));app.mount(root);await settle();
 return {root,confirms,nodes:()=>flatten(root),button:text=>flatten(root).find(n=>n.tag==='button'&&String(n.text).includes(text)),cleanup:()=>{app.unmount();globalThis.window=oldWindow;}};
}
const deferred=()=>{let resolve;const promise=new Promise(r=>resolve=r);return {resolve,promise};};

test('#229 profile overview edits explicitly; failed save keeps draft; success updates shared header source',async()=>{
 let writes=0;
 const store=reactive({learner:profile(),busy:false,error:null,restore:async()=>{},replacePreferences:async data=>{writes++;if(writes===1){store.error=Error('offline');return false;}store.error=null;store.learner={...profile('A','Updated Alice'),instruments:[{...guitar,preferences:data.preferences}]};return true;}});
 const h=await mount('entities/learner/LearnerProfileView.vue',store);
 try{
  assert.equal(h.nodes().some(n=>n.tag==='form'),false);
  h.button('Edit').props.onClick();await settle();
  const radio=h.nodes().find(n=>n.tag==='input'&&n.props?.name==='BLUES'&&n.props?.value==='LIKE');
  radio.props['onUpdate:modelValue']('LIKE');h.nodes().find(n=>n.tag==='form').props.onInput();await settle();
  const submit=()=>h.nodes().find(n=>n.tag==='form').props.onSubmit({preventDefault(){}});
  submit();await settle();assert.equal(writes,1);assert.ok(globalThis.__dirty229.value);assert.ok(h.nodes().some(n=>n.tag==='form'));
  h.button('Cancel').props.onClick();await settle();assert.equal(h.confirms.length,1);assert.ok(h.nodes().some(n=>n.tag==='form'));
  submit();await settle();assert.equal(writes,2);assert.equal(globalThis.__dirty229.value,false);assert.equal(store.learner.displayName,'Updated Alice');
  assert.ok(h.nodes().some(n=>n.props?.class==='saved-confirmation'&&n.props?.role==='status'));
 }finally{h.cleanup();}
});

test('#229 failed profile GET blocks preference PUT; recovery explicitly resolves read/draft conflict',async()=>{
 let reads=0,writes=0;
 const store=reactive({learner:profile(),busy:false,error:null,restore:async()=>{store.error=++reads===1?Error('offline'):null;},replacePreferences:async()=>{writes++;return true;}});
 const h=await mount('entities/learner/LearnerProfileView.vue',store);
 try{
  h.button('Edit').props.onClick();await settle();let form=h.nodes().find(n=>n.tag==='form');form.props.onInput();form.props.onSubmit({preventDefault(){}});await settle();assert.equal(writes,0);
  h.button('Try again').props.onClick();await settle();
  form=h.nodes().find(n=>n.tag==='form');form.props.onSubmit({preventDefault(){}});await settle();assert.equal(writes,0);
  h.button('Load saved profile').props.onClick();await settle();assert.equal(globalThis.__dirty229.value,false);
  h.nodes().find(n=>n.tag==='form').props.onSubmit({preventDefault(){}});await settle();assert.equal(writes,1);
 }finally{h.cleanup();}
});

test('#229 profile late save after account switch does not restore previous draft or show success',async()=>{
 const pending=deferred();
 const store=reactive({learner:profile(),busy:false,error:null,restore:async()=>{},replacePreferences:async()=>pending.promise});
 const h=await mount('entities/learner/LearnerProfileView.vue',store);
 try{
  h.button('Edit').props.onClick();await settle();h.nodes().find(n=>n.tag==='form').props.onInput();h.nodes().find(n=>n.tag==='form').props.onSubmit({preventDefault(){}});
  actor.actor={accountId:'account-B',learnerId:'B'};store.learner=profile('B','Bob');await settle();pending.resolve(true);await settle();
  assert.equal(globalThis.__dirty229.value,false);assert.equal(h.nodes().some(n=>n.props?.class==='saved-confirmation'),false);
  assert.ok(h.nodes().some(n=>n.text==='Bob'));assert.equal(h.nodes().some(n=>n.text==='Alice'),false);
 }finally{h.cleanup();}
});

test('#229 account methods failure shows no guessed providers and retries read only',async()=>{
 const native=globalThis.fetch;let calls=0;
 globalThis.fetch=async()=>{calls++;return calls===1?new Response('{}',{status:503}):Response.json({passwordEnabled:false,googleEnabled:true,googleAvailable:true});};
 const h=await mount('core/auth/PasswordSettings.vue');
 try{
  assert.equal(h.nodes().some(n=>n.tag==='form'),false);assert.ok(h.button('Retry'));
  h.button('Retry').props.onClick();await settle();assert.equal(calls,2);assert.ok(h.nodes().some(n=>n.props?.id==='link-email'));assert.equal(h.nodes().some(n=>n.props?.id==='current-password'),false);
 }finally{h.cleanup();globalThis.fetch=native;}
});

test('#229 account mismatched password does not POST, errors preserve input, success clears secrets',async()=>{
 const native=globalThis.fetch;let posts=0;let fail=true;
 globalThis.fetch=async(_input,init)=>{if(init?.method==='POST'){posts++;return new Response('{}',{status:fail?400:200});}return Response.json({passwordEnabled:true,googleEnabled:false,googleAvailable:true});};
 const h=await mount('core/auth/PasswordSettings.vue');
 const set=(id,value)=>h.nodes().find(n=>n.props?.id===id).props['onUpdate:modelValue'](value);
 const submit=()=>h.nodes().find(n=>n.tag==='form').props.onSubmit({preventDefault(){}});
 try{
  set('current-password','dummy-current');set('new-account-password','dummy-new-123');set('confirm-account-password','different-123');submit();await settle();assert.equal(posts,0);
  set('confirm-account-password','dummy-new-123');submit();await settle();assert.equal(posts,1);assert.ok(h.nodes().some(n=>n.value==='dummy-new-123'));
  fail=false;submit();await settle();assert.equal(posts,2);assert.equal(h.nodes().some(n=>n.value==='dummy-new-123'),false);assert.ok(h.nodes().some(n=>n.props?.role==='status'&&String(n.text).includes('changed')));
 }finally{h.cleanup();globalThis.fetch=native;}
});

test('#229 skill self-report is separate from observed/measured sources; no invented confirmation or percentages',async()=>{
 globalThis.__background229=async()=>({experienceLevel:'BEGINNER',yearsPlaying:1,practiceMinutes:40,sessionsPerWeek:3,goals:['FINGERSTYLE'],skills:['CHORDS']});
 globalThis.__skills229=async()=>[
  {skillCode:'a',title:'Self report only',selfReported:true,assessedLevel:0,evidenceStatus:'NOT_ASSESSED'},
  {skillCode:'b',title:'Measured rhythm',assessedLevel:2,evidenceStatus:'MEASURED'},
  {skillCode:'c',title:'Observed bass',assessedLevel:1,evidenceStatus:'OBSERVED'},
 ];
 const h=await mount('entities/learner/ProfileLearningSummary.vue',null,{learnerId:'A'});
 try{
  const text=h.nodes().map(n=>n.text).join(' ');
  assert.ok(text.includes('Your self-assessment'));assert.ok(text.includes('Chords'));assert.ok(text.includes('Measured rhythm'));assert.ok(text.includes('Source: performance measurement'));assert.ok(text.includes('Source: performance observation'));
  assert.equal(text.includes('Self report only'),false);assert.equal(text.includes('%'),false);
 }finally{h.cleanup();delete globalThis.__background229;delete globalThis.__skills229;}
});

test('#229 a late skill-map response is discarded after switching learner',async()=>{
 const old=deferred();
 globalThis.__background229=async()=>({experienceLevel:'BEGINNER',yearsPlaying:1,practiceMinutes:40,sessionsPerWeek:3,goals:[],skills:[]});
 globalThis.__skills229=async id=>id==='A'?old.promise:[];
 const props=reactive({learnerId:'A'});
 const h=await mount('entities/learner/ProfileLearningSummary.vue',null,props);
 try{
  props.learnerId='B';await settle();old.resolve([{skillCode:'a',title:'Private A observation',evidenceStatus:'MEASURED'}]);await settle();
  assert.equal(h.nodes().some(n=>String(n.text).includes('Private A observation')),false);
  assert.ok(h.nodes().some(n=>String(n.text).includes('No playing observations')));
 }finally{h.cleanup();delete globalThis.__background229;delete globalThis.__skills229;}
});

test('#229 acceptance: Google linking is explicit, uses its own password and explains the app account',async()=>{
 const native=globalThis.fetch;const posts=[];
 globalThis.fetch=async(input,init)=>{if(init?.method==='POST'){posts.push({input,body:JSON.parse(init.body)});return new Response('{}',{status:400});}return Response.json({passwordEnabled:true,googleEnabled:false,googleAvailable:true});};
 const h=await mount('core/auth/PasswordSettings.vue');
 const set=(id,value)=>h.nodes().find(n=>n.props?.id===id).props['onUpdate:modelValue'](value);
 try{
  assert.equal(h.nodes().some(n=>n.props?.id==='google-link-identifier'),false,'no Google credential form before explicit action');
  set('current-password','password-for-change-only');
  h.button('Add Google sign-in').props.onClick();await settle();
  assert.ok(h.nodes().some(n=>String(n.text).includes('First confirm your Guitar Mentor AI account')));
  assert.ok(h.nodes().some(n=>String(n.text).includes('Do not enter your email password')));
  assert.equal(h.nodes().find(n=>n.props?.id==='google-link-password').value,'','change-password value is not copied');
  set('google-link-identifier','student@example.test');set('google-link-password','google-link-app-password');await settle();
  assert.equal(h.nodes().find(n=>n.props?.id==='current-password').value,'password-for-change-only','Google confirmation does not change the other form');
  h.nodes().find(n=>n.tag==='form'&&n.props?.id==='google-link-form').props.onSubmit({preventDefault(){}});await settle();
  assert.equal(posts.length,1);assert.ok(posts[0].input.endsWith('/link/google/start'));
  assert.deepEqual(posts[0].body,{identifier:'student@example.test',password:'google-link-app-password'});
  assert.equal(h.nodes().find(n=>n.props?.id==='google-link-password').value,'google-link-app-password','failed request keeps retry input');
  h.button('Cancel').props.onClick();await settle();assert.equal(h.nodes().some(n=>n.props?.id==='google-link-password'),false);
  h.button('Add Google sign-in').props.onClick();await settle();assert.equal(h.nodes().find(n=>n.props?.id==='google-link-password').value,'','cancel clears secret before reopening');
 }finally{h.cleanup();globalThis.fetch=native;}
});

test('#229 acceptance: successful Google confirmation clears secret and redirects only to the configured OAuth path',async()=>{
 const native=globalThis.fetch;let path;
 globalThis.fetch=async(_input,init)=>init?.method==='POST'?new Response('{}'):Response.json({passwordEnabled:true,googleEnabled:false,googleAvailable:true});
 const h=await mount('core/auth/PasswordSettings.vue');globalThis.window.location.assign=value=>{path=value;};
 try{
  h.button('Add Google sign-in').props.onClick();await settle();
  h.nodes().find(n=>n.props?.id==='google-link-identifier').props['onUpdate:modelValue']('student');
  h.nodes().find(n=>n.props?.id==='google-link-password').props['onUpdate:modelValue']('app-password');
  h.nodes().find(n=>n.props?.id==='google-link-form').props.onSubmit({preventDefault(){}});await settle();
  assert.equal(path,'/oauth2/authorization/guitar-mentor');assert.equal(h.nodes().find(n=>n.props?.id==='google-link-password').value,'');
 }finally{h.cleanup();globalThis.fetch=native;}
});
