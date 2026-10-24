import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { parse, compileScript, compileTemplate } from '@vue/compiler-sfc';

const file = new URL('../src/entities/diagnostic/ExercisePreview.vue', import.meta.url);
const source = await readFile(file, 'utf8');
const { descriptor, errors } = parse(source, { filename: 'ExercisePreview.vue' });
const languages = await Promise.all(['ru', 'en'].map(lang =>
  readFile(new URL('../src/i18n/' + lang + '.json', import.meta.url), 'utf8').then(JSON.parse)));

test('metadata preview compiles and every new label is localized RU/EN', () => {
  assert.deepEqual(errors, []);
  assert.ok(compileScript(descriptor, { id: 'issue-85', inlineTemplate: false }).content);
  const template = compileTemplate({ source: descriptor.template.content, filename: 'ExercisePreview.vue', id: 'issue-85' });
  assert.deepEqual(template.errors, []);
  const expected = ['heading','version','goal','instructions','criteria','criteriaHint','unknown',
    'duration','tuning','tempo','difficulty','author','license','audioUnavailable','pdfUnavailable','pdfViewerFallback'];
  for (const dictionary of languages) {
    for (const key of expected) assert.equal(typeof dictionary.diagnostic.metadata[key], 'string', key);
  }
});

test('nullable learning fields are displayed as text, not as execution or completion state', () => {
  assert.match(source, /learningGoal\?\.trim\(\) \|\| props\.exercise\.description\?\.trim\(\)/);
  assert.match(source, /Array\.isArray\(props\.exercise\.successCriteria\)/);
  assert.match(source, /<li v-for=/);
  assert.doesNotMatch(source, /v-html|type="checkbox"/);
});

test('version and URL changes invalidate requests and stop stale media', () => {
  assert.match(source, /watch\(mediaIdentity,/);
  assert.match(source, /controller\.abort\(\)/);
  assert.match(source, /if \(!active\) return/);
  assert.match(source, /audioExists\.value = false/);
  assert.match(source, /pdfExists\.value = false/);
  assert.match(source, /pdfOpen\.value = false/);
  assert.match(source, /@error="audioExists = false"/);
  assert.match(source, /@error="pdfExists = false"/);
  assert.match(source, /:key="mediaIdentity"/);
  assert.match(source, /v-if="pdfOpen && pdfReady"/);
  assert.match(source, /leaveMetronomeExercise/);
});
