import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { test } from 'node:test';

const read = file => readFileSync(new URL('../' + file, import.meta.url), 'utf8');
const service = read('src/entities/library/library.service.ts');
const view = read('src/entities/library/LibraryView.vue');
const router = read('src/router/index.ts');
const header = read('src/App.vue');
const home = read('src/core/home/HomeView.vue');
const ru = JSON.parse(read('src/i18n/ru.json')).library;
const en = JSON.parse(read('src/i18n/en.json')).library;

test('#163 private library is routed and reachable from both menus', () => {
  assert.match(router, /path: '\/library'/);
  assert.match(header, /to="\/library"/);
  assert.match(home, /to: '\/library'/);
});
test('#163 private CRUD uses server-owned URLs and CSRF-aware native fetch', () => {
  assert.match(service, /\/api\/v1\/library\/materials/);
  assert.match(service, /method: 'POST'/);
  assert.match(service, /'Idempotency-Key': key/);
  assert.match(service, /method: 'PATCH'/);
  assert.match(service, /method: 'DELETE'/);
  assert.match(service, /'If-Match'/);
  assert.match(service, /credentials: 'same-origin'/);
  assert.match(service, /file\.downloadUrl !== expected/);
  assert.match(service, /URL\.revokeObjectURL/);
  assert.doesNotMatch(service, /window\.open\(file\.downloadUrl/);
});
test('#163 forms retain pending key for retry and clear private data on account switch', () => {
  assert.match(view, /lastUploadFingerprint/);
  assert.match(view, /pendingKey = crypto\.randomUUID\(\)/);
  assert.match(view, /watch\(\(\) => auth\.actor\?\.accountId/);
  assert.match(view, /onBeforeUnmount/);
  assert.match(view, /clearPrivate\(\)/);
  assert.match(view, /history\.value = previous/);
  assert.match(view, /currentCursor\.value = nextCursor/);
});
test('#163 no public publishing or server-selected owner', () => {
  assert.doesNotMatch(view + service, /POST.*\/publications|\/api\/v1\/catalog\/materials/);
  assert.doesNotMatch(service, /ownerAccountId|owner_account_id/);
  assert.match(view, /selected\.value = null; removing\.value = false/);
  assert.match(view, /role="alert"/);
});
test('#163 translations cover all visible states', () => {
  for (const locale of [ru,en]) {
    for (const key of ['nav','title','subtitle','privateNote','add','types','upload','empty',
      'count','emptyFiles','size','format','saved','deleted','disabled','conflict','confirmDelete',
      'myMaterials','download','loading','previous','next']) {
      assert.ok(locale[key], 'missing '+key);
    }
    for (const key of ['COMPOSITION','ETUDE','EXERCISE','OTHER']) assert.ok(locale.types[key]);
  }
});

test('#163 complete account switch clears private edit values and native file selection', () => {
  assert.match(view, /editTitle\.value = '';/);
  assert.match(view, /editAuthor\.value = '';/);
  assert.match(view, /editType\.value = 'COMPOSITION';/);
  assert.match(view, /fileInput\.value\) fileInput\.value\.value = '';/);
  assert.match(view, /auth\.actor\?\.accountId === owner\)/);
});
test('#163 stale download is not written after logout or identity switch', () => {
  assert.match(service, /stillAuthorized: \(\) => boolean/);
  assert.match(service, /if \(!stillAuthorized\(\)\) return;/);
  assert.match(view, /\(\) => active && ticket === generation && auth\.actor\?\.accountId === owner/);
});
