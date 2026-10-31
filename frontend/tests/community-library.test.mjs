import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import ts from 'typescript';
import * as failure from '../src/shared/api-failure.ts';

const base=new URL('../src/entities/library/',import.meta.url);
const id='11111111-1111-4111-8111-111111111111',asset='22222222-2222-4222-8222-222222222222';
function module(file,deps){const source=fs.readFileSync(new URL(file,base),'utf8');const exports={};new Function('exports','require',ts.transpileModule(source,{compilerOptions:{module:ts.ModuleKind.CommonJS,target:ts.ScriptTarget.ES2022}}).outputText)(exports,name=>{assert.ok(name in deps,'unexpected '+name);return deps[name];});return exports;}
const {pinnedAssetPdfUrl,pinnedPdfUrl}=module('../../shared/score/score-transport.ts',{});
const library=module('library.service.ts',{'../../shared/api-failure':failure});
const service=module('community.service.ts',{'../../shared/api-failure':failure,'./library.service':library});
const {communityService,assetPath,normalizeTags,validPublication,validCapabilities,LibraryFailure}=service;
const file={id:asset,originalName:'score.pdf',format:'PDF',byteSize:12,sha256:'a'.repeat(64),role:'SCORE_ORIGINAL',mediaType:'application/pdf',provenance:'CREATED',rightsBasis:'OWN_WORK',attribution:'Author',downloadUrl:assetPath('publication',id,asset),previewUrl:null};
const publication={id,version:1,sourceRevision:1,revision:1,title:'Test',type:'ETUDE',author:null,state:'PUBLISHED',assets:[file],tags:[]};
const before=globalThis.fetch;
test.afterEach(()=>{globalThis.fetch=before;});
test('typed publication rejects foreign routes, arbitrary bytes, broken DTOs; common DTO needs no owner fields',()=>{
 assert.ok(validPublication(publication));
 for(const url of ['https://evil.example/a','/api/v1/community/publications/'+asset+'/files/'+asset+'/content',file.downloadUrl+'?x=1',file.downloadUrl+'/x'])assert.equal(validPublication({...publication,assets:[{...file,downloadUrl:url}]}),false);
 assert.equal(validPublication({...publication,revision:'1'}),false);
 assert.equal(validPublication({...publication,assets:[{...file,byteSize:-1}]}),false);
 assert.equal(validCapabilities({enabled:true}),false);
});
test('PDF descriptor exact same-origin route; preserves exercise scenarios',()=>{
 for(const kind of ['material','publication']){
  const path=assetPath(kind,id,asset),descriptor=kind==='material'?{kind,materialId:id,assetId:asset}:{kind,publicationId:id,assetId:asset};
  assert.equal(pinnedAssetPdfUrl(path,descriptor,'https://local.test'),'https://local.test'+path);
  for(const bad of [path+'?x',path+'#x',path+'/extra','https://evil.test'+path,'//evil.test'+path,path.replace(id,asset)])assert.equal(pinnedAssetPdfUrl(bad,descriptor,'https://local.test'),null);
 }
 assert.equal(pinnedPdfUrl('/api/v1/exercises/A/versions/1/preview/pdf','A@1','https://local.test'),'https://local.test/api/v1/exercises/A/versions/1/preview/pdf');
});
test('search forwards ALL repeated tags, opaque cursor, abort signal, no browser filtering',async()=>{
 const signal=new AbortController().signal;
 globalThis.fetch=async(url,init)=>{const p=new URL(url,'https://test').searchParams;assert.deepEqual(p.getAll('tag'),['a','b']);assert.equal(p.get('cursor'),'opaque+/=');assert.equal(p.get('hasAudio'),'false');assert.equal(p.get('q'),'test');assert.equal(init.signal,signal);assert.equal(init.cache,'no-store');return Response.json({items:[],nextCursor:'opaque2'});};
 assert.deepEqual(await communityService.search({q:'test',type:'ETUDE',tags:['a','b'],hasAudio:'false'},'opaque+/=',signal),{items:[],nextCursor:'opaque2'});
 assert.deepEqual(normalizeTags(' A, b,a '),['a','b']);assert.throws(()=>normalizeTags('bad/tag'));assert.throws(()=>normalizeTags(Array.from({length:11},(_,n)=>'t'+n).join(',')));
});
test('publish uses material If-Match and stable caller key; withdraw uses publication revision',async()=>{
 const signal=new AbortController().signal;let calls=0;
 globalThis.fetch=async(url,init)=>{calls++;assert.equal(init.credentials,'same-origin');assert.equal(init.method,'POST');if(url.endsWith('/withdraw'))assert.equal(init.headers['If-Match'],'"3"');else {assert.equal(init.headers['If-Match'],'"7"');assert.equal(init.headers['Idempotency-Key'],'stable-key');assert.deepEqual(JSON.parse(init.body),{assets:[],tags:[]});}return Response.json(publication);};
 await communityService.publish({id,revision:7},[],[],'stable-key',signal);await communityService.withdraw(id,{...publication,revision:3},signal);assert.equal(calls,2);
});
test('HTTP statuses retained, Retry-After honored, mutations never automatically repeated',async()=>{
 for(const status of [401,404,409,412,413,415,429,503]){let count=0;globalThis.fetch=async()=>{count++;return Response.json({code:'CATALOG_LAST_SCORE'},{status,headers:{'Retry-After':'2'}});};await assert.rejects(communityService.publish({id,revision:1},[],[],'key',new AbortController().signal),e=>e instanceof LibraryFailure&&e.status===status&&(status!==429||e.retryAt>Date.now()));assert.equal(count,1);}
});
test('media requests use current revision and role metadata, keep delete separate from read retry',async()=>{
 const signal=new AbortController().signal;
 globalThis.fetch=async(url,init)=>{assert.equal(url,'/api/v1/library/materials/'+id+'/files');assert.equal(init.headers['If-Match'],'"8"');assert.equal(init.headers['Idempotency-Key'],'key');assert.equal(JSON.parse(await init.body.get('metadata').text()).role,'ORIGINAL_RECORDING');return Response.json({id,title:'Test',type:'ETUDE',author:null,state:'READY',visibility:'PRIVATE',reviewStatus:'UNREVIEWED',revision:9,createdAt:'date',updatedAt:'date',files:[]});};
 assert.equal((await communityService.addFiles({id,revision:8},'ORIGINAL_RECORDING',[new File(['data'],'a.wav')],'key',signal)).revision,9);
});
