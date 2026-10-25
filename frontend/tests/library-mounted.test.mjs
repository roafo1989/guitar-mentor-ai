import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { parse, compileScript } from 'vue/compiler-sfc';
import ts from 'typescript';
import { createRenderer, reactive, nextTick } from 'vue';
import { createI18n } from 'vue-i18n';

// Mount the actual #163 Vue SFC with a headless host renderer and deterministic backend.
// This catches stale busy flags and async ordering; unlike string checks, it runs setup()/UI events.
const asModule = code => 'data:text/javascript,' + encodeURIComponent(code);
const vueUrl = import.meta.resolve('vue');
const authModule = asModule('export const auth = globalThis.__libraryAuth;');
const serviceModule = asModule('export const libraryService = globalThis.__libraryBackend; export const validateUpload = () => null;');
const apiModule = asModule("export const asApiFailure = e => e; export const apiFailureKey = () => 'apiErrors.http';");
let View;
async function realView() {
  if (View) return View;
  const source = await readFile(new URL('../src/entities/library/LibraryView.vue', import.meta.url), 'utf8');
  const { descriptor, errors } = parse(source, { filename: 'LibraryView.vue' });
  assert.equal(errors.length, 0);
  const compiled = compileScript(descriptor, { id: 'private-library', inlineTemplate: true });
  const js = ts.transpileModule(compiled.content,
    { compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2022 } }).outputText;
  const mapped = js.replace(/from (['"])(.*?)\1/g, (_whole, _quote, name) => {
    const sources = {
      vue: vueUrl,
      'vue-i18n': import.meta.resolve('vue-i18n'),
      '../../core/auth/auth': authModule,
      '../../shared/api-failure': apiModule,
      './library.service': serviceModule,
    };
    assert.ok(sources[name], 'unexpected runtime import ' + name);
    return 'from ' + JSON.stringify(sources[name]);
  });
  View = (await import(asModule(mapped))).default;
  return View;
}
function renderer() {
  function node(tag, text = '') { return { tag, text, props: {}, children: [], parent: null }; }
  const remove = n => {
    if (!n.parent) return;
    const siblings = n.parent.children;
    const index = siblings.indexOf(n);
    if (index !== -1) siblings.splice(index, 1);
    n.parent = null;
  };
  return createRenderer({
    createElement: tag => node(tag),
    createText: value => node('#text', value),
    createComment: value => node('#comment', value),
    setText(n, value) { n.text = value; },
    setElementText(n, value) { n.text = value; n.children = []; },
    patchProp(n, key, _old, value) { n.props[key] = value; },
    insert(n, parent, anchor = null) {
      if (n.parent) remove(n);
      n.parent = parent;
      const index = parent.children.indexOf(anchor);
      if (index === -1) parent.children.push(n);
      else parent.children.splice(index, 0, n);
    },
    remove,
    parentNode: n => n.parent,
    nextSibling: n => n.parent?.children[n.parent.children.indexOf(n)+1] ?? null,
  });
}
const flatten = n => [n, ...(n.children ?? []).flatMap(flatten)];
async function settle() {
  for (let i = 0; i < 15; i++) { await Promise.resolve(); await nextTick(); }
}
function card(id, name) {
  return {
    id, title: name, type: 'COMPOSITION', author: null, state: 'READY',
    visibility: 'PRIVATE', reviewStatus: 'UNREVIEWED', revision: 1,
    createdAt: '2026-10-25T00:00:00Z', updatedAt: '2026-10-25T00:00:00Z',
    files: [],
  };
}
const first='11111111-1111-4111-8111-111111111111';
const second='22222222-2222-4222-8222-222222222222';

test('real mounted library: upload/delete must unlock UI; late detail must not override current selection', async () => {
  const actor = reactive({ actor: { accountId: 'account-A' } });
  globalThis.__libraryAuth = actor;
  const a = card(first, 'Alpha');
  const b = card(second, 'Bravo');
  const pages = [];
  let resolveA, resolveB;
  globalThis.__libraryBackend = {
    async page() { pages.push(true); return { items: pages.length === 1 ? [a,b] : [a,b], nextCursor: null }; },
    async detail(id) { return new Promise(resolve => { if(id===first) resolveA=resolve; else resolveB=resolve; }); },
    async upload() { return card('33333333-3333-4333-8333-333333333333', 'New material'); },
    async remove() {},
    async edit() { return a; },
    async download() {},
  };
  let app;
  try {
    const Component=await realView();
    const root={tag:'root',children:[],parent:null};
    app=renderer().createApp(Component);
    const ru=JSON.parse(await readFile(new URL('../src/i18n/ru.json',import.meta.url),'utf8'));
    app.use(createI18n({legacy:false,locale:'ru',messages:{ru}}));
    app.mount(root);
    await settle();
    const nodes=()=>flatten(root);
    assert.ok(nodes().some(n=>n.text==='Alpha'));
    const choose = name => nodes().find(n=>n.tag==='button' && n.props.class==='library-item' &&
      n.children.some(c=>c.text===name));
    choose('Alpha').props.onClick();
    choose('Bravo').props.onClick();
    await settle();
    resolveB(b);
    await settle();
    resolveA(a);
    await settle();
    assert.ok(nodes().some(n=>n.tag==='h3' && n.text==='Bravo'),
      'a slower Alpha GET must not replace the newer Bravo selection');

    const forms=nodes().filter(n=>n.tag==='form');
    const titleInput=nodes().find(n=>n.tag==='input' && n.props.maxlength===150 && n.props.type==='text');
    assert.ok(titleInput,'upload title input exists');
    titleInput.props['onUpdate:modelValue']('New material');
    const fileInput=nodes().find(n=>n.tag==='input' && n.props.type==='file');
    fileInput.props.onChange({target:{ files:[{name:'test.pdf',size:5,lastModified:1}] }});
    await settle();
    assert.ok(forms[0].props.onSubmit);
    forms[0].props.onSubmit({preventDefault() {}});
    await settle();
    const uploadButton=nodes().find(n=>n.tag==='button' && n.props.type==='submit' &&
      n.text==='Загрузить');
    assert.ok(uploadButton, 'after successful upload UI returns to normal enabled state');
    assert.equal(uploadButton.props.disabled, true, 'form is cleared after success, not stuck busy');
    assert.ok(nodes().some(n=>n.text==='Материал успешно сохранён.'));

    const removeButton=nodes().find(n=>n.tag==='button' && n.text==='Удалить');
    assert.ok(removeButton);
    removeButton.props.onClick();
    await settle();
    const confirm=nodes().find(n=>n.tag==='button' && n.text==='Да, удалить');
    assert.ok(confirm);
    confirm.props.onClick();
    await settle();
    assert.ok(!nodes().some(n=>n.tag==='progress'), 'successful DELETE must not leave busy spinner');

    actor.actor={accountId:'account-B'};
    await settle();
    assert.ok(!nodes().some(n=>n.tag==='h3' && n.text==='Bravo'),
      'after account switch previous detail is removed');
  } finally {
    app?.unmount();
    delete globalThis.__libraryAuth;
    delete globalThis.__libraryBackend;
  }
});
