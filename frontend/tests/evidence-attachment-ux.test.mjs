import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { parse, compileTemplate } from 'vue/compiler-sfc';

test('#222 attachment UX: selected state, replace/remove, keyboard and translated errors', async () => {
  const source = await readFile(new URL('../src/entities/diagnostic/DiagnosticEvidencePanel.vue', import.meta.url), 'utf8');
  const { descriptor, errors } = parse(source, { filename: 'DiagnosticEvidencePanel.vue' });
  assert.equal(errors.length, 0);
  const compiled = compileTemplate({ source: descriptor.template.content, filename: 'DiagnosticEvidencePanel.vue', id: 'evidence-attachment' });
  assert.deepEqual(compiled.errors, []);
  for (const value of [
    'evidence-file-selected', 'audio.name', 'score.name', 'size(audio.size)', 'size(score.size)',
    "t('evidence.chooseAudio')", "t('evidence.chooseScore')", "t('evidence.replaceFile')",
    "t('evidence.removeFile')", '@click="removeScore"', '@click="chooseAudio(null)"',
    'technicalError(entry.audio.errorCode)', 'class="evidence-file-input"', 'for="evidence-audio"',
    'for="evidence-score"', 'aria-live="polite"'
  ]) assert.ok(source.includes(value), 'Missing: ' + value);
  assert.ok(source.includes("if (scoreInput.value) scoreInput.value.value = '';"),
    'Removing a score must reset the native input so selecting the same file works again');
  assert.ok(source.includes("if (!file && audioInput.value) audioInput.value.value = '';"),
    'Removing audio must allow selecting the same file again');
  assert.ok(!source.includes('{{ entry.audio.errorCode }}'), 'Never surface raw machine errors to users');
  const css = await readFile(new URL('../src/assets/light-minimal.css', import.meta.url), 'utf8');
  assert.match(css, /\.evidence-file-input:focus-visible \+ \.evidence-file-trigger/);
  // File input stays keyboard accessible; selected-file actions have independent focus.
  assert.match(css, /\.evidence-file-remove:focus-visible/);
  for (const locale of ['ru', 'en']) {
    const json = JSON.parse(await readFile(new URL('../src/i18n/' + locale + '.json', import.meta.url), 'utf8'));
    for (const key of ['chooseAudio','chooseScore','replaceFile','removeFile','attached',
      'durationExceeded','unsupportedFormat','technicalGeneric']) {
      assert.ok(json.evidence[key], locale + ' missing ' + key);
    }
  }
});
