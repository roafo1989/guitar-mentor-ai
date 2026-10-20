import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { parse, compileScript } from 'vue/compiler-sfc';
import ts from 'typescript';
import { createRenderer, h, nextTick } from 'vue';
import { createI18n } from 'vue-i18n';

// Mount the real production script setup; provide a minimal headless render function.
const moduleUrl = (code) => 'data:text/javascript,' + encodeURIComponent(code);
const blank = moduleUrl("export default {render(){return null;}};");
const service = moduleUrl('export const evidenceService = { capabilities: (...a) => globalThis.__evidenceServiceMock.capabilities(...a), list: (...a) => globalThis.__evidenceServiceMock.list(...a), upload: (...a) => globalThis.__evidenceServiceMock.upload(...a) };');
let Panel, FullPanel;
async function getPanel(withTemplate = false) {
  if (withTemplate && FullPanel) return FullPanel;
  if (!withTemplate && Panel) return Panel;
  const source = await readFile(new URL('../src/entities/diagnostic/DiagnosticEvidencePanel.vue', import.meta.url), 'utf8');
  const { descriptor, errors } = parse(source, { filename: 'DiagnosticEvidencePanel.vue' });
  assert.equal(errors.length, 0);
  const script = compileScript(descriptor, {
    id: withTemplate ? 'evidence-archive-test' : 'evidence-panel-test',
    inlineTemplate: withTemplate,
  });
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
  const component = (await import(moduleUrl(mapped))).default;
  if (withTemplate) {
    FullPanel = component;
    return FullPanel;
  }
  Panel = component;
  Panel.render = () => h('section');
  return Panel;
}
function headlessRenderer() {
  const node = (tag, text = '') => ({
    tag, text, children: [], parent: null, props: {},
    addEventListener() {}, removeEventListener() {},
  });
  const remove = (n) => {
    if (!n) return; // Vue fragment teardown may hand the headless host a null anchor.
    const items = n.parent?.children;
    if (items) { const i = items.indexOf(n); if (i >= 0) items.splice(i, 1); }
    n.parent = null;
  };
  return createRenderer({
    createElement: node, createText: (text) => node('#text', text),
    createComment: (text) => node('#comment', text),
    setText: (n, text) => { n.text = text; },
    setElementText: (n, text) => { n.text = text; n.children = []; },
    patchProp: (n, key, _old, value) => { n.props[key] = value; },
    insert: (n, parent) => { if (n.parent) remove(n); n.parent = parent; parent.children.push(n); },
    remove, parentNode: (n) => n.parent, nextSibling: () => null,
  });
}
const capabilities = {
  enabled: true, maxAudioBytes: 20971520, maxScoreBytes: 10485760, maxItems: 10,
  audioFormats: ['wav', 'm4a'], scoreFormats: ['pdf']
};
async function mountWith(mock, { withTemplate = false, allowUpload = true } = {}) {
  globalThis.__evidenceServiceMock = mock;
  const view = await getPanel(withTemplate);
  const app = headlessRenderer().createApp(view, {
    learnerId: 'learner', sessionId: 'session', allowUpload
  });
  app.use(createI18n({ legacy: false, locale: 'en', messages: { en: {} } }));
  const root = { tag: 'root', children: [], parent: null, props: {} };
  app.mount(root);
  const instance = app._instance;
  for (let i = 0; i < 8 && instance.setupState.loading; i++) {
    await Promise.resolve(); await nextTick();
  }
  return { app, state: instance.setupState, root };
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

function allNodes(node) {
  return [node, ...(node.children ?? []).flatMap(allNodes)];
}
function visibleText(node) {
  return [node.text ?? '', ...(node.children ?? []).map(visibleText)].join(' ');
}
function evidenceEntry(id, title) {
  return {
    id, title, composer: null, comment: null, submittedAt: '2026-10-20T10:00:00Z',
    audio: {
      format: 'm4a', bytes: 314572, status: 'PROCESSED', durationSeconds: 12,
      errorCode: null, mediaState: 'AVAILABLE',
    },
    score: null, source: 'STUDENT_UPLOADED', skillEvidence: 'NOT_ASSESSED',
  };
}

test('readonly evidence archive mounts CLOSED with an accessible summary, a count and both saved recordings', async () => {
  const mock = {
    capabilities: async () => capabilities,
    list: async () => [evidenceEntry('a', 'Mic recording'), evidenceEntry('b', 'Samsung M4A')],
    upload: async () => { throw Error('Read-only history cannot upload'); },
  };
  const { app, root } = await mountWith(mock, { withTemplate: true, allowUpload: false });
  try {
    await nextTick();
    const nodes = allNodes(root);
    const archive = nodes.find(n => n.tag === 'details');
    assert.ok(archive, 'native details is mounted');
    assert.equal(archive.props.open, false, 'archive must be closed after entering task selection');
    const summary = nodes.find(n => n.tag === 'summary');
    assert.ok(summary, 'summary is a keyboard-accessible native toggle');
    assert.match(visibleText(summary), /\\(2\\)/, 'collapsed header shows number of saved items');
    assert.equal(nodes.filter(n => n.tag === 'article').length, 2, 'both evidence records remain in the archive');
    assert.equal(nodes.filter(n => n.tag === 'form').length, 0, 'upload form is not offered in readonly mode');
    assert.ok(nodes.some(n => n.tag === 'h4' && visibleText(n).includes('Samsung M4A')));
  } finally {
    app.unmount();
    delete globalThis.__evidenceServiceMock;
  }
});

test('evidence section remains OPEN for intake, with upload form accessible', async () => {
  const mock = {
    capabilities: async () => capabilities,
    list: async () => [],
    upload: async () => { throw Error('not reached'); },
  };
  const { app, root } = await mountWith(mock, { withTemplate: true, allowUpload: true });
  try {
    await nextTick();
    const nodes = allNodes(root);
    const archive = nodes.find(n => n.tag === 'details');
    assert.ok(archive);
    assert.equal(archive.props.open, true, 'intake form must be open');
    const summary = nodes.find(n => n.tag === 'summary');
    assert.equal(summary.props.style.display, 'none', 'summary toggle hidden during intake');
    assert.ok(nodes.some(n => n.tag === 'form'), 'upload form is shown while collecting evidence');
  } finally {
    app.unmount();
    delete globalThis.__evidenceServiceMock;
  }
});
