// Historical reproduction; expanded regressions run in npm test (tests/auth-races.test.mjs).
// Run from repository root: node --experimental-strip-types --test docs/testing/regression-2026-10-27/auth-races.repro.mjs
// Assertions express the required safe behavior; failures reproduce known defects.
import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import ts from '../../../frontend/node_modules/typescript/lib/typescript.js';
const source = await readFile(new URL('../../../frontend/src/core/auth/auth.ts', import.meta.url), 'utf8');
const vue = new URL('../../../frontend/node_modules/vue/dist/vue.runtime.esm-bundler.js', import.meta.url).href;
const compiled = ts.transpileModule(source.replaceAll('import.meta.env', '({DEV:false})'), {
  compilerOptions: {target:ts.ScriptTarget.ES2022,module:ts.ModuleKind.ESNext},
}).outputText.replace(/from ['"]vue['"]/g, 'from ' + JSON.stringify(vue));
const deferred = () => {let resolve; const promise = new Promise(r=>{resolve=r;}); return {promise,resolve};};
const response = body => ({ok:true,status:200,json:async()=>body,clone(){return response(body);}});
const actor = id => ({accountId:id,learnerId:'learner-'+id,role:'LEARNER'});
async function setup(native) {
  const oldWindow=globalThis.window,oldFetch=globalThis.fetch;
  globalThis.window={location:{origin:'https://qa.example.test'},fetch:native};
  const mod=await import('data:text/javascript,'+encodeURIComponent(compiled)+'#'+Math.random());
  mod.installAuthenticatedFetch(()=>{});globalThis.fetch=window.fetch;
  return {mod,cleanup(){globalThis.window=oldWindow;globalThis.fetch=oldFetch;}};
}

test('required: changing account while CSRF is pending cancels the previous account write',async()=>{
  const token=deferred(),writes=[];
  const h=await setup(async(path,options)=>{
    if(path==='/api/v1/csrf')return token.promise;
    writes.push({path,body:options.body,actor:h.mod.auth.actor?.accountId});return response({});
  });
  try {
    h.mod.auth.actor=actor('A');
    const pending=window.fetch('/api/v1/library/materials',{method:'POST',body:'private-draft-from-A'})
      .catch(error=>error);
    h.mod.clearActor();h.mod.auth.actor=actor('B');
    token.resolve(response({headerName:'X-XSRF-TOKEN',token:'synthetic-B-token'}));
    await pending;
    console.log('OBSERVED_CSRF_RACE',JSON.stringify(writes));
    assert.equal(writes.length,0,'an old-account write must never reach nativeFetch after the actor changes');
  } finally {h.cleanup();}
});

test('required: a late /me response cannot replace the current account after logout and login',async()=>{
  const me=deferred();
  const h=await setup(async()=>me.promise);
  try {
    h.mod.auth.actor=actor('A');
    const pending=h.mod.refreshActor();
    h.mod.clearActor();h.mod.auth.actor=actor('B');
    me.resolve(response(actor('A')));await pending;
    console.log('OBSERVED_REFRESH_RACE',h.mod.auth.actor?.accountId);
    assert.equal(h.mod.auth.actor?.accountId,'B','a stale identity response must not resurrect account A');
  } finally {h.cleanup();}
});
