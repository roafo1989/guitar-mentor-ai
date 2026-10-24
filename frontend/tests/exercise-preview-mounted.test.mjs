import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { parse, compileScript } from '@vue/compiler-sfc';
import ts from 'typescript';
import { createRenderer, h, nextTick, ref } from 'vue';

const asModule = code => 'data:text/javascript,' + encodeURIComponent(code);
const vueUrl = import.meta.resolve('vue');
const i18nUrl = asModule("export const useI18n = () => ({t: key => key});");
const metronomeUrl = asModule("export const leaveMetronomeExercise = () => {}; export const openExerciseMetronome = () => {};");
const filename = new URL('../src/entities/diagnostic/ExercisePreview.vue', import.meta.url);

async function component() {
  const { descriptor, errors } = parse(await readFile(filename, 'utf8'), { filename: 'ExercisePreview.vue' });
  assert.deepEqual(errors, []);
  const script = compileScript(descriptor, { id: 'issue85-mounted', inlineTemplate: true });
  const js = ts.transpileModule(script.content, {
    compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ESNext },
  }).outputText.replace(/from (['"])(.*?)\1/g, (_match, _q, name) => {
    const mapped = { vue: vueUrl, 'vue-i18n': i18nUrl, '../metronome/metronome-ui': metronomeUrl }[name];
    if (!mapped) throw new Error('Unexpected dependency: ' + name);
    return 'from ' + JSON.stringify(mapped);
  });
  return (await import(asModule(js))).default;
}

function view() {
  const node = (tag, text = '') => ({ tag, text, props: {}, children: [], parent: null });
  const remove = child => {
    if (!child.parent) return;
    const list = child.parent.children;
    const index = list.indexOf(child);
    if (index !== -1) list.splice(index, 1);
    child.parent = null;
  };
  const renderer = createRenderer({
    createElement: tag => node(tag),
    createText: text => node('#text', text),
    createComment: text => node('#comment', text),
    setText: (n, text) => { n.text = text; },
    setElementText: (n, text) => { n.text = text; n.children = []; },
    patchProp: (n, key, _old, value) => { n.props[key] = value; },
    insert: (n, parent, anchor = null) => {
      if (n.parent) remove(n);
      n.parent = parent;
      const at = parent.children.indexOf(anchor);
      if (at === -1) parent.children.push(n); else parent.children.splice(at, 0, n);
    },
    remove,
    parentNode: n => n.parent,
    nextSibling: n => n.parent?.children[n.parent.children.indexOf(n) + 1] ?? null,
  });
  const root = node('root');
  const all = () => {
    const found = [];
    function collect(n) { found.push(n); n.children.forEach(collect); }
    collect(root);
    return found;
  };
  const find = (tag, predicate = () => true) => all().find(n => n.tag === tag && predicate(n));
  const text = () => all().map(n => n.text).join(' ');
  return { renderer, root, find, text };
}

function exercise(version, suffix = version) {
  return {
    code: 'GM-EB01', version, title: 'Blues', description: 'Fallback goal',
    learningGoal: 'Learn steady bass', practiceInstructions: 'Practice slowly',
    successCriteria: ['Even pulse'], recommendedPracticeMinutes: 30,
    tuning: 'EADGBE', bpm: 84, meter: '4/4', difficulty: 'BEGINNER',
    author: 'Composer', license: 'Test', scoreUrl: '/score/' + suffix,
    previewAudioUrl: '/audio/' + suffix, previewPdfUrl: '/pdf/' + suffix,
  };
}
function deferred() {
  let resolve;
  const promise = new Promise(r => { resolve = r; });
  return { promise, resolve };
}
async function flush() { await Promise.resolve(); await Promise.resolve(); await nextTick(); }

test('mounted preview discards late HEAD responses after version changes and unmount', async () => {
  const saved = globalThis.fetch;
  const requests = [];
  globalThis.fetch = (url, options) => {
    const item = { url, signal: options.signal, ...deferred() };
    requests.push(item);
    return item.promise;
  };
  const screen = view();
  const current = ref(exercise('v1'));
  let app;
  try {
    const Preview = await component();
    app = screen.renderer.createApp({ render: () => h(Preview, { exercise: current.value }) });
    app.mount(screen.root);
    assert.equal(requests.length, 2);
    current.value = exercise('v2');
    await nextTick();
    assert.equal(requests.length, 4);
    assert.equal(requests[0].signal.aborted, true);
    assert.equal(requests[1].signal.aborted, true);
    requests[0].resolve({ ok: true });
    requests[1].resolve({ ok: true });
    await flush();
    assert.equal(screen.find('audio'), undefined, 'old available audio cannot appear in new version');
    requests[2].resolve({ ok: true });
    requests[3].resolve({ ok: false });
    await flush();
    assert.equal(screen.find('audio')?.props.src, '/audio/v2');
    assert.equal(screen.find('iframe'), undefined, 'PDF remains collapsed and unavailable');
    app.unmount();
    app = null;
  } finally {
    app?.unmount();
    globalThis.fetch = saved;
  }
});

test('mounted preview invalidates media-only URL edits, rechecks PDF on open and handles fallback', async () => {
  const saved = globalThis.fetch;
  const requests = [];
  globalThis.fetch = (url, options) => {
    const item = { url, signal: options.signal, ...deferred() };
    requests.push(item);
    return item.promise;
  };
  const screen = view();
  const current = ref(exercise('v1'));
  let app;
  try {
    const Preview = await component();
    app = screen.renderer.createApp({ render: () => h(Preview, { exercise: current.value, assigned: true }) });
    app.mount(screen.root);
    requests[0].resolve({ ok: true });
    requests[1].resolve({ ok: true });
    await flush();
    assert.equal(screen.find('audio')?.props.src, '/audio/v1');

    current.value = exercise('v1', 'replacement');
    await nextTick();
    assert.equal(requests.length, 4, 'same version but new URLs trigger fresh HEAD');
    assert.equal(screen.find('audio'), undefined, 'old audio removed immediately');
    requests[2].resolve({ ok: false });
    requests[3].resolve({ ok: true });
    await flush();
    assert.equal(screen.find('audio'), undefined);
    assert.ok(screen.text().includes('diagnostic.metadata.audioUnavailable'));

    const details = screen.find('details');
    assert.ok(details);
    details.props.onToggle({ target: { open: true } });
    assert.equal(requests.length, 5, 'opening PDF performs a second HEAD');
    assert.equal(screen.find('iframe'), undefined, 'iframe not rendered until fresh check succeeds');
    requests[4].resolve({ ok: false });
    await flush();
    assert.equal(screen.find('iframe'), undefined);
    assert.ok(screen.text().includes('diagnostic.metadata.pdfUnavailable'));
    assert.ok(screen.find('a', n => n.props.href === '/score/replacement'), 'source score fallback remains');
  } finally {
    app?.unmount();
    globalThis.fetch = saved;
  }
});

test('PDF HEAD after opening is ignored when preview is replaced', async () => {
  const saved = globalThis.fetch;
  const requests = [];
  globalThis.fetch = (url, options) => {
    const item = { url, signal: options.signal, ...deferred() };
    requests.push(item);
    return item.promise;
  };
  const screen = view();
  const current = ref(exercise('v1'));
  let app;
  try {
    const Preview = await component();
    app = screen.renderer.createApp({ render: () => h(Preview, { exercise: current.value }) });
    app.mount(screen.root);
    requests[0].resolve({ ok: true });
    requests[1].resolve({ ok: true });
    await flush();
    screen.find('details').props.onToggle({ target: { open: true } });
    assert.equal(requests.length, 3);
    current.value = exercise('v2');
    await nextTick();
    assert.equal(requests[2].signal.aborted, true);
    requests[2].resolve({ ok: true });
    await flush();
    assert.equal(screen.find('iframe'), undefined);
    assert.equal(screen.find('details')?.props.open, false);
  } finally {
    app?.unmount();
    globalThis.fetch = saved;
  }
});

test('PDF recovers after initial HEAD failed and reopened after connectivity returns', async () => {
  const saved = globalThis.fetch;
  const requests = [];
  globalThis.fetch = (url, options) => {
    const item = { url, signal: options.signal, ...deferred() };
    requests.push(item);
    return item.promise;
  };
  const screen = view();
  let app;
  try {
    const Preview = await component();
    app = screen.renderer.createApp({ render: () => h(Preview, { exercise: exercise('v1') }) });
    app.mount(screen.root);
    requests[0].resolve({ ok: true });
    requests[1].resolve({ ok: false }); // transient initial PDF failure
    await flush();
    assert.ok(screen.text().includes('diagnostic.metadata.pdfUnavailable'));
    const details = screen.find('details');
    details.props.onToggle({ target: { open: true } });
    assert.equal(requests.length, 3, 'retry runs even though pdfExists is false');
    requests[2].resolve({ ok: false });
    await flush();
    details.props.onToggle({ target: { open: false } });
    details.props.onToggle({ target: { open: true } });
    assert.equal(requests.length, 4, 'reopen attempts recovery again');
    requests[3].resolve({ ok: true });
    await flush();
    assert.equal(screen.find('iframe')?.props.src, '/pdf/v1#view=FitH&toolbar=1');
    assert.ok(screen.find('a', n => n.props.href === '/score/v1'));
  } finally {
    app?.unmount();
    globalThis.fetch = saved;
  }
});

test('opening PDF during initial HEAD allows retry and stale initial failure cannot hide it', async () => {
  const saved = globalThis.fetch;
  const requests = [];
  globalThis.fetch = (url, options) => {
    const item = { url, signal: options.signal, ...deferred() };
    requests.push(item);
    return item.promise;
  };
  const screen = view();
  let app;
  try {
    const Preview = await component();
    app = screen.renderer.createApp({ render: () => h(Preview, { exercise: exercise('v1') }) });
    app.mount(screen.root);
    const details = screen.find('details');
    details.props.onToggle({ target: { open: true } });
    assert.equal(requests.length, 3, 'opening before initial HEAD completion checks the PDF');
    requests[2].resolve({ ok: true });
    await flush();
    requests[0].resolve({ ok: true });
    requests[1].resolve({ ok: false }); // obsolete initial PDF result
    await flush();
    assert.equal(screen.find('iframe')?.props.src, '/pdf/v1#view=FitH&toolbar=1',
      'manual retry succeeds despite older initial failure');
    assert.equal(screen.text().includes('diagnostic.metadata.pdfUnavailable'), false);
  } finally {
    app?.unmount();
    globalThis.fetch = saved;
  }
});

test('successful explicit PDF HEAD renders iframe while both initial HEAD checks remain pending', async () => {
  const saved = globalThis.fetch;
  const requests = [];
  globalThis.fetch = (url, options) => {
    const item = { url, signal: options.signal, ...deferred() };
    requests.push(item);
    return item.promise;
  };
  const screen = view();
  let app;
  try {
    const Preview = await component();
    app = screen.renderer.createApp({ render: () => h(Preview, { exercise: exercise('v1') }) });
    app.mount(screen.root);
    assert.equal(requests.length, 2, 'initial audio and PDF checks started');
    const details = screen.find('details');
    details.props.onToggle({ target: { open: true } });
    assert.equal(requests.length, 3, 'opening makes independent PDF request');
    requests[2].resolve({ ok: true });
    await flush();
    assert.equal(requests[0].signal.aborted, false, 'initial audio check remains pending');
    assert.equal(requests[1].signal.aborted, false, 'initial PDF check remains pending');
    assert.equal(screen.find('iframe')?.props.src, '/pdf/v1#view=FitH&toolbar=1',
      'PDF is visible without settling either initial HEAD request');
    assert.ok(screen.find('a', n => n.props.href === '/score/v1'), 'source score is still available');
    assert.equal(screen.text().includes('diagnostic.metadata.pdfUnavailable'), false);

    // Late initial failure does not clear a successful explicitly opened PDF.
    requests[0].resolve({ ok: false });
    requests[1].resolve({ ok: false });
    await flush();
    assert.equal(screen.find('iframe')?.props.src, '/pdf/v1#view=FitH&toolbar=1');
  } finally {
    app?.unmount();
    globalThis.fetch = saved;
  }
});
