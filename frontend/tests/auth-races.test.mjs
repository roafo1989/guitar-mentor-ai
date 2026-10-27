import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import ts from 'typescript';
const source=await readFile(new URL('../src/core/auth/auth.ts',import.meta.url),'utf8');
const compiled=ts.transpileModule(source.replaceAll('import.meta.env','({DEV:false})'),{compilerOptions:{target:ts.ScriptTarget.ES2022,module:ts.ModuleKind.ESNext}}).outputText.replace(/from ['"]vue['"]/g,'from '+JSON.stringify(import.meta.resolve('vue')));
const deferred=()=>{let resolve,reject;const promise=new Promise((a,b)=>{resolve=a;reject=b;});return {promise,resolve,reject};};
const response=(body,status=200)=>({ok:status<400,status,json:async()=>body,clone(){return response(body,status);}});
const actor=id=>({accountId:id,learnerId:'learner-'+id,role:'LEARNER'});
const token=value=>response({headerName:'X-XSRF-TOKEN',token:value});
async function setup(native){const oldWindow=globalThis.window,oldFetch=globalThis.fetch;let resets=0;
 globalThis.window={location:{origin:'https://qa.example.test'},fetch:native};
 const mod=await import('data:text/javascript,'+encodeURIComponent(compiled)+'#'+Math.random());
 mod.installAuthenticatedFetch(()=>{resets++;});globalThis.fetch=window.fetch;
 return {mod,get resets(){return resets;},cleanup(){globalThis.window=oldWindow;globalThis.fetch=oldFetch;}};}
const tick=()=>new Promise(r=>setImmediate(r));
test('old CSRF cancels A write and cannot replace B cache (URL without learnerId)',async()=>{
 const old=deferred(),writes=[];let tokens=0;const h=await setup(async(path,options)=>{
 if(path==='/api/v1/csrf')return ++tokens===1?old.promise:token('B-token');
 writes.push([options.body,options.headers.get('X-XSRF-TOKEN')]);return response({});});
 try {h.mod.auth.actor=actor('A');const pending=fetch('/api/v1/library/materials',{method:'POST',body:'A-draft'}).catch(e=>e);
 h.mod.clearActor();h.mod.auth.actor=actor('B');await fetch('/api/v1/library/materials',{method:'POST',body:'B-draft'});
 old.resolve(token('A-token'));assert.equal((await pending).name,'AbortError');
 await fetch('/api/v1/library/materials',{method:'POST',body:'B-next'});
 assert.deepEqual(writes,[['B-draft','B-token'],['B-next','B-token']]);assert.equal(tokens,2);
 }finally{h.cleanup();}});
for(const outcome of ['success','http401','http403','network','json'])test(`stale /me ${outcome} cannot alter B or flags or personal state`,async()=>{
 const me=deferred(),h=await setup(()=>me.promise);
 try {h.mod.auth.actor=actor('A');const pending=h.mod.refreshActor();h.mod.clearActor();h.mod.auth.actor=actor('B');h.mod.auth.ready=false;h.mod.auth.expired=true;
 if(outcome==='network')me.reject(Error('offline'));
 else if(outcome==='json')me.resolve({...response(null),json:async()=>{throw Error('bad json');}});
 else me.resolve(response(actor('A'),outcome==='http401'?401:outcome==='http403'?403:200));
 await pending;assert.equal(h.mod.auth.actor.accountId,'B');assert.equal(h.mod.auth.ready,false);assert.equal(h.mod.auth.expired,true);assert.equal(h.resets,0);
 }finally{h.cleanup();}});
test('late initial /me cannot resurrect an actor after clear',async()=>{
 const me=deferred(),h=await setup(()=>me.promise);try {const pending=h.mod.refreshActor();h.mod.clearActor();me.resolve(response(actor('A')));await pending;
 assert.equal(h.mod.auth.actor,null);assert.equal(h.mod.auth.ready,true);}finally{h.cleanup();}});
for(const status of [200,401,403])test(`newest refresh wins reversed responses (old status ${status})`,async()=>{
 const old=deferred(),fresh=deferred();let count=0;const h=await setup(()=>++count===1?old.promise:fresh.promise);
 try {h.mod.auth.actor=actor('A');const a=h.mod.refreshActor(),b=h.mod.refreshActor();fresh.resolve(response(actor('A')));await b;
 old.resolve(response(actor('old'),status));await a;assert.equal(h.mod.auth.actor.accountId,'A');assert.equal(h.mod.auth.ready,true);assert.equal(h.mod.auth.expired,false);assert.equal(h.resets,0);
 }finally{h.cleanup();}});
test('same-account refresh preserves queued write and CSRF',async()=>{
 const csrf=deferred(),writes=[];const h=await setup(async(path,options)=>{
 if(path==='/api/v1/me')return response(actor('A'));if(path==='/api/v1/csrf')return csrf.promise;writes.push(options);return response({});});
 try {h.mod.auth.actor=actor('A');const pending=fetch('/api/v1/library/materials',{method:'POST'});await h.mod.refreshActor();csrf.resolve(token('A'));await pending;
 assert.equal(writes.length,1);assert.equal(h.resets,0);}finally{h.cleanup();}});
for(const stage of ['csrf','response'])test(`stale logout at ${stage} cannot clear B or call callback`,async()=>{
 const waiting=deferred();let logouts=0,callback=0;const h=await setup(async(path)=>{
 if(path==='/api/v1/csrf')return stage==='csrf'?waiting.promise:token('A');if(path==='/logout'){logouts++;return waiting.promise;}return response(actor('B'));});
 try {h.mod.auth.actor=actor('A');const pending=h.mod.logout(()=>callback++).catch(e=>e);await tick();h.mod.clearActor();h.mod.auth.actor=actor('B');
 waiting.resolve(stage==='csrf'?token('A'):response({}));await pending;assert.equal(h.mod.auth.actor.accountId,'B');assert.equal(callback,0);assert.equal(logouts,stage==='csrf'?0:1);
 }finally{h.cleanup();}});
test('logout immediately invalidates pending write and identity refresh',async()=>{
 const old=deferred(),me=deferred();let tokens=0,writes=0;const h=await setup(async(path)=>{
 if(path==='/api/v1/csrf')return ++tokens===1?old.promise:token('logout');if(path==='/api/v1/me')return me.promise;if(path==='/logout')return response({});writes++;return response({});});
 try {h.mod.auth.actor=actor('A');const write=fetch('/api/v1/library/materials',{method:'POST'}).catch(e=>e),refresh=h.mod.refreshActor();await h.mod.logout(()=>{});
 old.resolve(token('A'));me.resolve(response(actor('A')));await refresh;assert.equal((await write).name,'AbortError');assert.equal(writes,0);assert.equal(h.mod.auth.actor,null);
 }finally{h.cleanup();}});
for(const status of [401,403])test(`current /me ${status} clears actor and personal state`,async()=>{
 const h=await setup(()=>Promise.resolve(response({code:'ACCOUNT_NOT_ADMITTED'},status)));
 try {h.mod.auth.actor=actor('A');await h.mod.refreshActor();assert.equal(h.mod.auth.actor,null);assert.equal(h.mod.auth.ready,true);assert.equal(h.mod.auth.expired,true);assert.equal(h.resets,1);
 }finally{h.cleanup();}});
for(const path of ['register','login','verify','reset'])test(`public local-auth ${path} still sends with CSRF`,async()=>{
 const writes=[];const h=await setup(async(url,options)=>url==='/api/v1/csrf'?token('public'):(writes.push(options),response({})));
 try {await fetch('/api/v1/local-auth/'+path,{method:'POST',body:'{}'});assert.equal(writes.length,1);assert.equal(writes[0].headers.get('X-XSRF-TOKEN'),'public');}finally{h.cleanup();}});

for(const failure of ['network','json'])test('stale CSRF ' + failure + ' is cancellation, never a B error',async()=>{
 const old=deferred();const h=await setup(()=>old.promise);
 try {h.mod.auth.actor=actor('A');const pending=fetch('/api/v1/library/materials',{method:'POST'}).catch(e=>e);
 h.mod.clearActor();h.mod.auth.actor=actor('B');
 if(failure==='network')old.reject(Error('offline'));else old.resolve({...token('A'),json:async()=>{throw Error('broken json');}});
 assert.equal((await pending).name,'AbortError');assert.equal(h.mod.auth.actor.accountId,'B');assert.equal(h.resets,0);
 }finally{h.cleanup();}});
test('same-account object refresh cannot hide real expiry from a pending private request',async()=>{
 const pending=deferred();const h=await setup(path=>path==='/api/v1/me'?Promise.resolve(response(actor('A'))):pending.promise);
 try {h.mod.auth.actor=actor('A');const request=fetch('/api/v1/private');await h.mod.refreshActor();pending.resolve(response({},401));await request;
 assert.equal(h.mod.auth.actor,null);assert.equal(h.mod.auth.expired,true);assert.equal(h.resets,1);
 }finally{h.cleanup();}});
