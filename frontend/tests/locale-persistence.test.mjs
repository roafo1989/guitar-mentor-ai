import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import ts from 'typescript';
import { nextTick } from 'vue';
import {
  LOCALE_STORAGE_KEY, normalizeLocale, readStoredLocale, saveLocale,
} from '../src/core/locale-storage.ts';

function memoryStorage(initial = {}) {
  const values = new Map(Object.entries(initial));
  const writes = [];
  return {
    values, writes,
    getItem(key) { return values.has(key) ? values.get(key) : null; },
    setItem(key, value) { writes.push([key, value]); values.set(key, value); },
  };
}

function withWindow(value, callback) {
  const existing = Object.getOwnPropertyDescriptor(globalThis, 'window');
  Object.defineProperty(globalThis, 'window', { configurable: true, value, writable: true });
  return Promise.resolve().then(callback).finally(() => {
    if (existing) Object.defineProperty(globalThis, 'window', existing);
    else delete globalThis.window;
  });
}

test('locale values are allow-listed with RU default; no parsing or evaluation', () => {
  for (const value of ['ru', null, undefined, '', 'EN', 'fr', '{"locale":"en"}', '<script>alert(1)</script>', 1]) {
    assert.equal(normalizeLocale(value), 'ru');
  }
  assert.equal(normalizeLocale('en'), 'en');
  const storage = memoryStorage({ [LOCALE_STORAGE_KEY]: 'EN', 'learner': 'keep-me' });
  assert.equal(readStoredLocale(storage), 'ru');
  saveLocale('fr', storage);
  assert.equal(storage.values.get(LOCALE_STORAGE_KEY), 'EN', 'unsupported values must not be saved');
  assert.equal(storage.values.get('learner'), 'keep-me', 'unrelated state must not be cleared');
});

test('read/write use exactly one browser key and preserve unrelated storage', () => {
  const storage = memoryStorage({ 'learner': 'saved' });
  assert.equal(readStoredLocale(storage), 'ru');
  saveLocale('en', storage);
  assert.equal(readStoredLocale(storage), 'en');
  saveLocale('ru', storage);
  assert.equal(readStoredLocale(storage), 'ru');
  assert.deepEqual(storage.writes, [[LOCALE_STORAGE_KEY, 'en'], [LOCALE_STORAGE_KEY, 'ru']]);
  assert.equal(storage.values.get('learner'), 'saved');
});

test('unavailable getItem/setItem and null storage are nonfatal', () => {
  const readFailure = { getItem() { throw new Error('SecurityError'); }, setItem() {} };
  const writeFailure = { getItem() { return 'en'; }, setItem() { throw new Error('QuotaExceededError'); } };
  assert.equal(readStoredLocale(readFailure), 'ru');
  assert.equal(readStoredLocale(writeFailure), 'en');
  assert.doesNotThrow(() => saveLocale('ru', writeFailure));
  assert.equal(readStoredLocale(null), 'ru');
  assert.doesNotThrow(() => saveLocale('en', null));
});

test('SSR, missing window and a throwing localStorage getter default safely', async () => {
  await withWindow(undefined, async () => {
    assert.equal(readStoredLocale(), 'ru');
    assert.doesNotThrow(() => saveLocale('en'));
  });
  const original = Object.getOwnPropertyDescriptor(globalThis, 'window');
  Object.defineProperty(globalThis, 'window', {
    configurable: true, get() { throw new Error('window getter denied'); },
  });
  try {
    assert.equal(readStoredLocale(), 'ru');
    assert.doesNotThrow(() => saveLocale('en'));
  } finally {
    if (original) Object.defineProperty(globalThis, 'window', original);
    else delete globalThis.window;
  }
  await withWindow(Object.defineProperty({}, 'localStorage', {
    get() { throw new Error('localStorage getter denied'); },
  }), async () => {
    assert.equal(readStoredLocale(), 'ru');
    assert.doesNotThrow(() => saveLocale('en'));
  });
});

const codeUrl = source => 'data:text/javascript,' + encodeURIComponent(source);
let bootNumber = 0;

async function bootRealI18n() {
  // Compile the actual production i18n module, not a duplicated implementation.
  // Replace only its module specifiers so Node can load .ts and JSON without Vite.
  const file = await readFile(new URL('../src/core/i18n.ts', import.meta.url), 'utf8');
  const js = ts.transpileModule(file, {
    compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2022 },
  }).outputText;
  const messages = codeUrl('export default {app:{profile:"Profile"}};');
  const imports = {
    vue: import.meta.resolve('vue'),
    'vue-i18n': import.meta.resolve('vue-i18n'),
    '../i18n/en.json': messages,
    '../i18n/ru.json': messages,
    './locale-storage': new URL('../src/core/locale-storage.ts', import.meta.url).href,
    './locale-storage.ts': new URL('../src/core/locale-storage.ts', import.meta.url).href,
  };
  const mapped = js.replace(/from (['"])(.*?)\1/g, (_match, _quote, name) => {
    if (!imports[name]) throw new Error('Unexpected i18n module import: ' + name);
    return 'from ' + JSON.stringify(imports[name]);
  });
  return (await import(codeUrl(mapped + '\n// fresh start ' + (++bootNumber)))).i18n;
}

test('actual createI18n starts with stored EN, saves transitions and restores after simulated F5', async () => {
  const storage = memoryStorage({ 'learner': 'preserve' });
  await withWindow({ localStorage: storage }, async () => {
    const first = await bootRealI18n();
    assert.equal(first.global.locale.value, 'ru', 'empty key uses RU');
    assert.equal(storage.writes.length, 0, 'initialization does not rewrite preferences');
    first.global.locale.value = 'en'; // same global ref used by header v-model
    await nextTick();
    assert.deepEqual(storage.writes, [[LOCALE_STORAGE_KEY, 'en']]);
    first.global.locale.value = 'en';
    await nextTick();
    assert.equal(storage.writes.length, 1, 'no duplicate save on unchanged selection');

    const afterF5 = await bootRealI18n();
    assert.equal(afterF5.global.locale.value, 'en', 'restoration happens at createI18n');
    assert.equal(storage.writes.length, 1, 'reloading does not generate a write');
    afterF5.global.locale.value = 'ru';
    await nextTick();
    const afterSecondF5 = await bootRealI18n();
    assert.equal(afterSecondF5.global.locale.value, 'ru');
    assert.equal(storage.values.get('learner'), 'preserve');
  });
});

test('actual i18n continues changing in memory if storage becomes blocked', async () => {
  const storage = {
    getItem() { throw new Error('get blocked'); },
    setItem() { throw new Error('set blocked'); },
  };
  await withWindow({ localStorage: storage }, async () => {
    const instance = await bootRealI18n();
    assert.equal(instance.global.locale.value, 'ru');
    instance.global.locale.value = 'en';
    await nextTick();
    assert.equal(instance.global.locale.value, 'en');
    const reloaded = await bootRealI18n();
    assert.equal(reloaded.global.locale.value, 'ru');
  });
});

test('header retains the shared global locale binding', async () => {
  const app = await readFile(new URL('../src/App.vue', import.meta.url), 'utf8');
  assert.match(app, /const\s*\{\s*locale\s*,\s*t\s*\}\s*=\s*useI18n\(\)/);
  assert.match(app, /<select\s+v-model="locale"\s+aria-label="Language">/);
});
