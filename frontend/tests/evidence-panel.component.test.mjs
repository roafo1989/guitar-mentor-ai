import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { parse, compileScript, compileTemplate } from 'vue/compiler-sfc';
import ts from 'typescript';
import { createRenderer, h, nextTick } from 'vue';
import { createI18n } from 'vue-i18n';

// Mount the real production script setup; provide a minimal headless render function.
const moduleUrl = (code) => 'data:text/javascript,' + encodeURIComponent(code);
const blank = moduleUrl("export default {render(){return null;}};");
const service = moduleUrl('export const evidenceService = { capabilities: (...a) => globalThis.__evidenceServiceMock.capabilities(...a), list: (...a) => globalThis.__evidenceServiceMock.list(...a), upload: (...a) => globalThis.__evidenceServiceMock.upload(...a) };');
let Panel;
async function getPanel() {
  if (Panel) return Panel;
  const source = await readFile(new URL('../src/entities/diagnostic/DiagnosticEvidencePanel.vue', import.meta.url), 'utf8');
  const { descriptor, errors } = parse(source, { filename: 'DiagnosticEvidencePanel.vue' });
  assert.equal(errors.length, 0);
  const script = compileScript(descriptor, { id: 'evidence-panel-test', inlineTemplate: false });
  const js = ts.transpileModule(script.content, {
    compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ESNext }
  }).outputText;
  const mapped = js.replace(/from (['"])(.*?)\1/g, (_full, _quote, name) => {
    const value = name === 'vue' ? import.meta.resolve('vue') :
      name === 'vue-i18n' ? import.meta.resolve('vue-i18n') :
      name.endsWith('MicrophoneRecorder.vue') ? blank :
      name.endsWith('evidence.service') ? service : null;
    if (!value) throw Error('Unknown panel runtime dependency ' + name);
    return 'from ' + JSON.stringify(value);
  });
  Panel = (await import(moduleUrl(mapped))).default;
  Panel.render = () => h('section');
  return Panel;
}
function headlessRenderer() {
  const node = (tag, text = '') => ({ tag, text, children: [], parent: null });
  const remove = (n) => {
    const items = n.parent?.children;
    if (items) { const i = items.indexOf(n); if (i >= 0) items.splice(i, 1); }
    n.parent = null;
  };
  return createRenderer({
    createElement: node, createText: (text) => node('#text', text),
    createComment: (text) => node('#comment', text),
    setText: (n, text) => { n.text = text; },
    setElementText: (n, text) => { n.text = text; n.children = []; },
    patchProp() {},
    insert: (n, parent) => { if (n.parent) remove(n); n.parent = parent; parent.children.push(n); },
    remove, parentNode: (n) => n.parent, nextSibling: () => null,
  });
}
const capabilities = {
  enabled: true, maxAudioBytes: 20971520, maxScoreBytes: 10485760, maxItems: 10,
  audioFormats: ['wav', 'm4a'], scoreFormats: ['pdf']
};
async function mountWith(mock) {
  globalThis.__evidenceServiceMock = mock;
  const view = await getPanel();
  const app = headlessRenderer().createApp(view, {
    learnerId: 'learner', sessionId: 'session', allowUpload: true
  });
  app.use(createI18n({ legacy: false, locale: 'en', messages: { en: {} } }));
  app.mount({ tag: 'root', children: [], parent: null });
  const instance = app._instance;
  for (let i = 0; i < 8 && instance.setupState.loading; i++) {
    await Promise.resolve(); await nextTick();
  }
  return { app, state: instance.setupState };
}
function audio(data, filename = 'a.wav') {
  return new File([new Uint8Array(data)], filename, { type: 'audio/wav' });
}

test('editing draft after canceled uncertain upload rotates requestId; unchanged retry keeps original', async () => {
  let signal;
  const mock = {
    capabilities: async () => capabilities,
    list: async () => [],
    upload: (_learner, _session, request) => {
      signal = request.signal;
      return new Promise((_resolve, reject) => {
        request.signal.addEventListener('abort', () => reject(Error('ABORTED')), { once: true });
      });
    }
  };
  const { app, state } = await mountWith(mock);
  try {
    state.title = 'Yesterday';
    state.audio = audio([1, 2, 3]);
    const first = state.requestId;
    const pending = state.submit();
    await Promise.resolve();
    assert.equal(state.uploading, true);
    state.cancelUpload();
    await pending;
    assert.equal(signal.aborted, true);
    assert.equal(state.uploading, false);
    assert.equal(state.requestId, first, 'aborted request keeps its idempotent retry key');
    assert.ok(state.audio, 'cancel does not destroy recorded audio');

    state.audio = audio([4, 5, 6], 'b.wav');
    assert.notEqual(state.requestId, first, 'new recording MUST have a new request ID');
    const second = state.requestId;
    state.title = 'Changed title';
    assert.notEqual(state.requestId, second, 'changed metadata also creates a new request ID');
  } finally {
    app.unmount();
    delete globalThis.__evidenceServiceMock;
  }
});

test('resolved POST releases busy before subsequent history GET finishes', async () => {
  let calls = 0;
  let refreshStarted = false;
  const mock = {
    capabilities: async () => capabilities,
    list: () => {
      calls++;
      if (calls === 1) return Promise.resolve([]);
      refreshStarted = true;
      return new Promise(() => {}); // Simulate a permanently stalled history GET.
    },
    upload: async () => ({ id: 'saved', title: 'Yesterday' }),
  };
  const { app, state } = await mountWith(mock);
  try {
    state.title = 'Yesterday';
    state.audio = audio([1, 2, 3]);
    await state.submit();
    assert.equal(state.uploading, false, 'POST completion releases navigation immediately');
    assert.equal(refreshStarted, true, 'background history GET started');
    assert.equal(state.audio, null, 'only confirmed POST clears the file');
  } finally {
    app.unmount();
    delete globalThis.__evidenceServiceMock;
  }
});
 
test('native details archive compiles and shows the saved count when uploads are disabled', async () => {
  const file = new URL('../src/entities/diagnostic/DiagnosticEvidencePanel.vue', import.meta.url);
  const source = await readFile(file, 'utf8');
  const { descriptor, errors } = parse(source, { filename: 'DiagnosticEvidencePanel.vue' });
  assert.equal(errors.length, 0);
  const template = descriptor.template.content;
  const compiled = compileTemplate({ source: template, filename: 'DiagnosticEvidencePanel.vue', id: 'evidence-qa' });
  assert.equal(compiled.errors.length, 0, 'real Vue template must compile');
  assert.match(template, /<details[^>]*:open="props.allowUpload"/);
  assert.match(template, /<summary[^>]*class="evidence-summary"/);
  assert.ok(template.includes("{{ entries.length }}"), 'summary must show saved count');
  assert.match(template, /<form v-if="props.allowUpload && capabilities/);
  assert.match(template, /<article v-for="entry in entries"/);
  assert.match(template, /<h3 v-if="props.allowUpload"/);
});
