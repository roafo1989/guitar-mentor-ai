import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import ts from 'typescript';

const file = (path) => readFileSync(new URL(path, import.meta.url), 'utf8');
const source = file('../src/entities/diagnostic/diagnostic.service.ts');
const compiled = ts.transpileModule(source, {
  compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 },
}).outputText;
const validUuid = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa';
const exercise = {
  code:'GM-EB01',version:'0.8B',title:'Blues',styleCode:'BLUES',difficulty:'BEGINNER',
  bpm:84,measures:12,meter:'4/4',author:'GM',license:'CC BY 4.0',
  reviewStatus:'APPROVED',description:'study',scoreUrl:'/score',eventsUrl:'/events',
  skillCodes:[],previewAudioUrl:null,previewPdfUrl:null,contentKind:'LEARNING_REPERTOIRE'
};
function harness(responses) {
  const calls = [];
  const api = {
    apiRequest: async (url,init,valid) => {
      calls.push({url,init});
      const answer = responses.shift();
      if (!valid(answer)) throw new Error('INVALID_RESPONSE');
      return answer;
    },
    isArrayOf: (x, valid) => Array.isArray(x) && x.every(valid),
    isNumber: x => typeof x === 'number' && Number.isFinite(x),
    isObject: x => x !== null && typeof x === 'object' && !Array.isArray(x),
    isString: x => typeof x === 'string',
    isStringArray: x => Array.isArray(x) && x.every(y => typeof y === 'string'),
    isUuid: x => typeof x === 'string' && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(x),
  };
  const exports = {};
  new Function('require','exports',compiled)(name => {
    assert.equal(name,'../../shared/api-failure');
    return api;
  },exports);
  return {calls,service:exports.diagnosticService};
}

test('#216: next lesson sends stable key/version to correct scoped POST', async () => {
  const result = {
    transitionId:validUuid,sourceSessionId:validUuid,sourceTaskId:validUuid,
    session: { id: validUuid, instrumentCode:'ACOUSTIC_GUITAR', status:'TASK_ASSIGNED',
      createdAt:'2026-10-25T11:00:00Z',updatedAt:'2026-10-25T11:00:00Z',availableActions:['VIEW_TASK'] },
    task: {id:validUuid,sessionId:validUuid,assignedAt:'2026-10-25T11:00:00Z',exercise}
  };
  const h = harness([result]);
  await h.service.nextLesson(validUuid,validUuid,validUuid,validUuid,2,'GM-EB01','0.8B');
  const call = h.calls[0];
  assert.match(call.url,/\/diagnostic-sessions\/.*\/tasks\/.*\/next-lesson$/);
  assert.equal(call.init.method,'POST');
  assert.deepEqual(JSON.parse(call.init.body),{
    requestId:validUuid,expectedVersion:2,exerciseCode:'GM-EB01',exerciseVersion:'0.8B',
  });
});

test('#216: next lesson rejects malformed success payload', async () => {
  const h=harness([{transitionId:validUuid,task:{}}]);
  await assert.rejects(h.service.nextLesson(validUuid,validUuid,validUuid,validUuid,2,'GM-EB01','0.8B'),/INVALID_RESPONSE/);
});

test('#216: history includes opaque cursor and refuses malformed rows', async () => {
  const page={items:[{sessionId:validUuid,taskId:validUuid,exerciseCode:'GM-EB01',
    exerciseVersion:'0.8B',title:'Blues',assignedAt:'2026-10-25T11:00:00Z',
    taskState:'COMPLETED_BY_LEARNER',isCurrent:false}],nextCursor:'cursor+/='};
  const h=harness([page]);
  assert.deepEqual(await h.service.history(validUuid,'old+/='),page);
  assert.match(h.calls[0].url,/cursor=old%2B%2F%3D/);
  assert.equal(h.calls[0].init.cache,'no-store');
  const bad=harness([{items:[{...page.items[0],isCurrent:'yes'}],nextCursor:null}]);
  await assert.rejects(bad.service.history(validUuid),/INVALID_RESPONSE/);
});

test('#216: UI exposes history, manual picker and both locales', () => {
  const router=file('../src/router/index.ts');
  for (const route of ["'/lessons'","'/lessons/new'","'/lessons/:sessionId'"])
    assert.ok(router.includes(route),'missing route '+route);
  const view=file('../src/entities/diagnostic/NextLessonView.vue');
  assert.match(view,/COMPLETED_BY_LEARNER/);
  assert.match(view,/COMPLETED_PASSED/);
  assert.match(view,/pendingRequest\.value/);
  assert.match(view,/crypto\.randomUUID\(\)/);
  assert.match(view,/closeMetronome/);
  const history=file('../src/entities/diagnostic/LessonHistoryView.vue');
  assert.match(history,/learningService\.decisions/);
  assert.match(history,/learningService\.evidence/);
  for(const locale of ['ru','en']) {
    const messages=JSON.parse(file('../src/i18n/'+locale+'.json'));
    for(const key of ['choose','history','search','preview','start','readOnly','confirmRepeat','uncertain'])
      assert.ok(messages.lessonNext[key],'missing '+locale+'.lessonNext.'+key);
  }
});
