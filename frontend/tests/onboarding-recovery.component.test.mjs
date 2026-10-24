import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import ts from 'typescript';
import { parse, compileScript } from 'vue/compiler-sfc';
import { createRenderer, h, nextTick, reactive } from 'vue';
import { createI18n } from 'vue-i18n';

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
    const nextStep = nodes.find(n => n.tag === 'button' && String(n.text).includes('onboarding.next'));
    assert.ok(nextStep?.props.onClick, 'first step navigation exists');
    nextStep.props.onClick();
    await settle();
    nodes = flatten(root);
    const saveButton = nodes.find(n => n.tag === 'button' && String(n.text).includes('Сохранить'));
    assert.ok(saveButton?.props.disabled, 'unsafe PUT is disabled when GET failed');
    // Invoke the actual form submit handler: guard must also live in save(), not only in the template.
    const form = nodes.find(n => n.tag === 'form');
    assert.ok(form?.props.onSubmit);
    form.props.onSubmit({ preventDefault() {} });
    await settle();
    assert.equal(calls.filter(([method]) => method === 'PUT').length, 0);
    fail = false;
    const retry = nodes.find(n => n.tag === 'button' && String(n.text).includes('Повторить'));
    assert.ok(retry, 'read-only retry exists');
    retry.props.onClick();
    await settle();
    assert.deepEqual(calls.map(([m]) => m), ['GET', 'GET']);
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
