import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { parse, compileScript } from 'vue/compiler-sfc';
import ts from 'typescript';
import { createRenderer, reactive, h, nextTick } from 'vue';

// Execute real LibraryView <script setup> lifecycle and methods with mock backend,
// independent of DOM-specific v-model event directives.
const asModule=code=>'data:text/javascript,'+encodeURIComponent(code);
const vueUrl=import.meta.resolve('vue');
let component;
async function realSetupComponent() {
  if (component) return component;
  const source=await readFile(new URL('../src/entities/library/LibraryView.vue',import.meta.url),'utf8');
  const {descriptor,errors}=parse(source,{filename:'LibraryView.vue'});
  assert.equal(errors.length,0);
  const script=compileScript(descriptor,{id:'library-unit',inlineTemplate:false});
  const js=ts.transpileModule(script.content,{
    compilerOptions:{module:ts.ModuleKind.ESNext,target:ts.ScriptTarget.ES2022},
  }).outputText;
  const mapped=js.replace(/from (['"])(.*?)\1/g,(_full,_q,id)=>{
    const dependencies={
      vue:vueUrl,
      'vue-i18n':asModule("export const useI18n=()=>({t:key=>key});"),
      '../../core/auth/auth':asModule("export const auth=globalThis.__libraryTestAuth;"),
      '../../shared/api-failure':asModule("export const asApiFailure=e=>e; export const apiFailureKey=()=> 'apiErrors.http';"),
      './library.service':asModule("export const libraryService=globalThis.__libraryTestApi; export const validateUpload=()=>null;"),
    };
    assert.ok(dependencies[id],'unexpected dependency '+id);
    return 'from '+JSON.stringify(dependencies[id]);
  });
  component=(await import(asModule(mapped))).default;
  return component;
}
function minimalRenderer() {
  function node(tag){return {tag,parent:null,children:[],text:'',props:{}};}
  function remove(n){
    if(!n.parent)return;
    const siblings=n.parent.children;
    const pos=siblings.indexOf(n);
    if(pos>=0)siblings.splice(pos,1);
    n.parent=null;
  }
  return createRenderer({
    createElement:node,createText:value=>({...node('#text'),text:value}),
    createComment:value=>({...node('#comment'),text:value}),
    setText(n,value){n.text=value;},setElementText(n,value){n.text=value;n.children=[];},
    patchProp(n,k,_prev,v){n.props[k]=v;},
    insert(n,p,anchor=null){if(n.parent)remove(n);n.parent=p;const i=p.children.indexOf(anchor);if(i<0)p.children.push(n);else p.children.splice(i,0,n);},
    remove,parentNode:n=>n.parent,nextSibling:n=>n.parent?.children[n.parent.children.indexOf(n)+1]??null,
  });
}
function card(id,title){
  return {id,title,type:'COMPOSITION',author:null,state:'READY',
    visibility:'PRIVATE',reviewStatus:'UNREVIEWED',revision:1,
    createdAt:'2026-10-25T00:00:00Z',updatedAt:'2026-10-25T00:00:00Z',files:[]};
}
const idA='11111111-1111-4111-8111-111111111111';
const idB='22222222-2222-4222-8222-222222222222';
async function flush(){for(let i=0;i<10;i++){await Promise.resolve();await nextTick();}}

test('#163 mounted setup: latest detail wins; upload/delete release busy; account switch wipes drafts',async()=>{
  globalThis.__libraryTestAuth=reactive({actor:{accountId:'A'}});
  const a=card(idA,'Alpha'),b=card(idB,'Bravo'),c=card('33333333-3333-4333-8333-333333333333','New');
  let resolveA,resolveB;
  let uploads=0,deletions=0,lists=0;
  globalThis.__libraryTestApi={
    async page(){lists++;return {items:[a,b],nextCursor:null};},
    detail(id){return new Promise(resolve=>{if(id===idA)resolveA=resolve;else resolveB=resolve;});},
    async upload(){uploads++;return c;},
    async remove(){deletions++;},
    async edit(){return a;},async download(){},
  };
  const Base=await realSetupComponent();
  let binding,app;
  try{
    const Wrapper={setup(props,context){binding=Base.setup(props,context);return()=>h('section');}};
    app=minimalRenderer().createApp(Wrapper);
    app.mount({tag:'root',children:[],parent:null});
    await flush();
    assert.equal(binding.entries.value.length,2);
    const pA=binding.openCard(idA);
    const pB=binding.openCard(idB);
    resolveB(b);await pB;
    resolveA(a);await pA;
    assert.equal(binding.selected.value.title,'Bravo','older detail response cannot overwrite newer selection');
    // The primary workflow is file first: suggest title from the original filename.
    binding.pickFiles({target:{files:[{name:'Ветер перемен.live.gpx',size:5,lastModified:1}]}});
    assert.equal(binding.title.value,'Ветер перемен.live');
    binding.pickFiles({target:{files:[{name:'Yesterday.pdf',size:5,lastModified:2}]}});
    assert.equal(binding.title.value,'Yesterday','automatic suggestion follows selection until manually edited');
    binding.title.value='My own title';
    binding.titleEditedByUser.value=true;
    binding.pickFiles({target:{files:[{name:'Other.gp5',size:5,lastModified:3}]}});
    assert.equal(binding.title.value,'My own title','file changes must preserve manual title');
    binding.title.value='New material';
    binding.files.value=[{name:'sample.pdf',size:5,lastModified:1}];
    await binding.upload();
    assert.equal(uploads,1);
    assert.equal(binding.busy.value,false,'upload must release busy after list reload');
    assert.equal(binding.selected.value.id,c.id);
    assert.equal(binding.title.value,'');
    binding.removing.value=true;
    await binding.removeCard();
    assert.equal(deletions,1);
    assert.equal(binding.busy.value,false,'delete must release busy after list reload');
    assert.equal(binding.selected.value,null);
    binding.editTitle.value='private old-account title';
    binding.editAuthor.value='private old-account author';
    globalThis.__libraryTestAuth.actor={accountId:'B'};
    await flush();
    assert.equal(binding.editTitle.value,'');
    assert.equal(binding.editAuthor.value,'');
    assert.equal(binding.entries.value.length,2,'fresh request after actor switch is allowed');
    assert.ok(lists>=4);
  } finally {
    app?.unmount();
    delete globalThis.__libraryTestAuth;
    delete globalThis.__libraryTestApi;
  }
});
