import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import ts from 'typescript';
import { ApiFailure, isArrayOf, isObject, isString, isUuid } from '../src/shared/api-failure.ts';
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

function serviceHarness() {
  const code = ts.transpileModule(service, {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 },
  }).outputText;
  const exports = {};
  new Function('require', 'exports', code)(
    id => {
      assert.equal(id, '../../shared/api-failure');
      return { ApiFailure, isArrayOf, isObject, isString, isUuid };
    }, exports,
  );
  return exports;
}
const uuid = '11111111-1111-4111-8111-111111111111';
const fileId = '22222222-2222-4222-8222-222222222222';
const downloadUrl = '/api/v1/library/materials/' + uuid + '/files/' + fileId + '/content';
const material = {
  id: uuid, title: 'Yesterday', type: 'COMPOSITION', author: 'The Beatles',
  state: 'READY', visibility: 'PRIVATE', reviewStatus: 'UNREVIEWED',
  revision: 1, createdAt: '2026-10-25T00:00:00Z', updatedAt: '2026-10-25T00:00:00Z',
  files: [{ id: fileId, originalName: 'Yesterday.pdf', format: 'PDF',
    byteSize: 5, sha256: 'a'.repeat(64), validationStatus: 'PDF_HEADER_CHECKED', downloadUrl, role:'SCORE_ORIGINAL', mediaType:'application/pdf', processingState:'READY',previewAvailable:false,previewUrl:null,previewReasonCode:null,durationMillis:null,channels:null,sampleRate:null,width:null,height:null }],
};
async function mockFetch(fn, work) {
  const before = globalThis.fetch;
  globalThis.fetch = fn;
  try { return await work(); }
  finally { globalThis.fetch = before; }
}

test('#163 API actually validates responses and builds owner-only paginated GET', async () => {
  const { libraryService } = serviceHarness();
  let path;
  await mockFetch(async (url, options) => {
    path = String(url);
    assert.equal(options.credentials, 'same-origin');
    assert.equal(options.cache, 'no-store');
    return Response.json({ items: [material], nextCursor: 'signed-cursor' });
  }, async () => {
    const result = await libraryService.page('page-2');
    assert.equal(result.items[0].title, 'Yesterday');
    assert.equal(result.nextCursor, 'signed-cursor');
  });
  assert.ok(path.includes('cursor=page-2'));
  assert.ok(path.includes('limit=20'));

  await mockFetch(async () => Response.json({ items: [{ ...material, state: 'DELETING' }], nextCursor: null }),
    async () => assert.rejects(libraryService.page(), failure => failure instanceof ApiFailure && failure.kind === 'INVALID_RESPONSE'));
});

test('#163 real multipart request uses JSON metadata and reuses caller idempotency key', async () => {
  const { libraryService } = serviceHarness();
  let seen = 0;
  const pdf = new File(['%PDF-'], 'sample.pdf', { type: 'application/pdf' });
  await mockFetch(async (url, options) => {
    seen++;
    assert.equal(url, '/api/v1/library/materials');
    assert.equal(options.method, 'POST');
    assert.equal(options.headers['Idempotency-Key'], uuid);
    assert.ok(options.body instanceof FormData);
    assert.equal((await options.body.get('metadata').text()).includes('"title":"Yesterday"'), true);
    assert.equal(options.body.getAll('files').length, 1);
    assert.equal(options.body.get('files').name, 'sample.pdf');
    return Response.json(material, { status: seen === 1 ? 201 : 200 });
  }, async () => {
    for (let i = 0; i < 2; i++) {
      const item = await libraryService.upload({
        title: 'Yesterday', type: 'COMPOSITION', author: 'The Beatles',
      }, [pdf], uuid);
      assert.equal(item.id, uuid);
    }
  });
  assert.equal(seen, 2);
});

test('#163 delete sends If-Match and checks HTTP202 rather than faking success', async () => {
  const { libraryService } = serviceHarness();
  await mockFetch(async (url, opts) => {
    assert.equal(url, '/api/v1/library/materials/' + uuid);
    assert.equal(opts.method, 'DELETE');
    assert.equal(opts.headers['If-Match'], '"7"');
    return new Response(JSON.stringify({ id: uuid, state: 'DELETING' }), { status: 202 });
  }, () => libraryService.remove(uuid, 7));

  await mockFetch(async () => new Response(null, { status: 500 }),
    async () => assert.rejects(libraryService.remove(uuid, 7), e => e instanceof ApiFailure && e.status === 500));
});

test('#163 file validation rejects invalid count/extensions/size', () => {
  const { validateUpload } = serviceHarness();
  const entry = (name, size) => ({ name, size });
  assert.equal(validateUpload([]), 'empty');
  assert.equal(validateUpload(Array(6).fill(entry('good.gpx', 3))), 'count');
  assert.equal(validateUpload([entry('bad.exe', 2)]), 'format');
  assert.equal(validateUpload([entry('empty.pdf', 0)]), 'size');
  assert.equal(validateUpload([entry('a.pdf', 14*1024*1024), entry('b.gp5', 7*1024*1024)]), 'size');
  assert.equal(validateUpload([entry('test.gp3', 2)]), null);
});

test('#163 download never starts saving bytes if account changes while streaming', async () => {
  const { libraryService } = serviceHarness();
  const oldCreate = URL.createObjectURL;
  URL.createObjectURL = () => { throw Error('download must not be started'); };
  try {
    await mockFetch(async () => new Response('%PDF-', { status: 200 }), async () => {
      await libraryService.download(uuid, material.files[0], () => false);
    });
  } finally { URL.createObjectURL = oldCreate; }
  await assert.rejects(libraryService.download(uuid, { ...material.files[0], downloadUrl: '/evil' }),
    failure => failure instanceof ApiFailure && failure.kind === 'INVALID_RESPONSE');
});
