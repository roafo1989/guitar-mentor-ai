import test from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import ts from 'typescript';
import {parse,compileScript,compileTemplate} from '@vue/compiler-sfc';
const transport = await readFile(new URL('../src/shared/score/score-transport.ts',import.meta.url),'utf8');
const compiled=ts.transpileModule(transport,{compilerOptions:{module:ts.ModuleKind.CommonJS,target:ts.ScriptTarget.ES2022}}).outputText;
const exported={};new Function('exports',compiled)(exported);
const {pinnedPdfUrl,safeScoreLink,fetchBoundedPdf,ScoreFetchError,MAX_PDF_BYTES}=exported;
const origin='https://guitar-mentor.ru';

test('#150 only version-pinned same-origin PDF URLs are accepted',()=>{
 const good='/api/v1/exercises/GM-EX01/versions/1.0/preview/pdf';
 assert.equal(pinnedPdfUrl(good,'GM-EX01@1.0',origin),origin+good);
 for(const invalid of [
  '//evil.tld/x','https://guitar-mentor.ru'+good,good+'?download=1',
  good+'#page=3','/api/v1/exercises/GM-EX01/versions/2.0/preview/pdf',
  '/api/v1/exercises/GM-EX01/versions/1.0/preview/audio',
  '/api/v1/exercises/../versions/1.0/preview/pdf',
 ]) assert.equal(pinnedPdfUrl(invalid,'GM-EX01@1.0',origin),null,invalid);
 assert.equal(pinnedPdfUrl(good,'GM-EX01@2.0',origin),null);
 assert.equal(pinnedPdfUrl(null,'GM-EX01@1.0',origin),null);
 assert.equal(safeScoreLink('//attacker/file',origin),null);
 assert.equal(safeScoreLink('/api/v1/exercises/GM-EX01/versions/1.0/score',origin),origin+'/api/v1/exercises/GM-EX01/versions/1.0/score');
});
const encoder=new TextEncoder();
function response(chunks,headers={'Content-Type':'application/pdf'},status=200) {
 let index=0, cancelCount=0;
 return {get cancelCount(){return cancelCount},status,ok:status>=200&&status<300,
   headers:new Headers(headers),
   body:{getReader(){return {
     async read(){return index>=chunks.length?{done:true}:{done:false,value:chunks[index++]};},
     async cancel(){cancelCount++}
   }}}};
}
test('#150 reads bounded PDF bytes and never trusts only Content-Length',async()=>{
 const save=globalThis.fetch;
 try{
  const file=response([encoder.encode('%PDF-1.7'),encoder.encode(' test')]);
  globalThis.fetch=async (_url,init)=>{assert.equal(init.method,'GET');assert.equal(init.credentials,'same-origin');return file};
  const result=await fetchBoundedPdf(origin+'/pdf',new AbortController().signal);
  assert.equal(new TextDecoder().decode(result),'%PDF-1.7 test');
  assert.equal(file.cancelCount,1);
  globalThis.fetch=async()=>response([encoder.encode('%PDF-')],{'Content-Type':'text/html'});
  await assert.rejects(fetchBoundedPdf(origin+'/pdf',new AbortController().signal),e=>e.reasonCode==='type');
  globalThis.fetch=async()=>response([encoder.encode('NOT_PDF')]);
  await assert.rejects(fetchBoundedPdf(origin+'/pdf',new AbortController().signal),e=>e.reasonCode==='signature');
  globalThis.fetch=async()=>response([encoder.encode('%PDF-')],{'Content-Type':'application/pdf','Content-Length':String(MAX_PDF_BYTES+1)});
  await assert.rejects(fetchBoundedPdf(origin+'/pdf',new AbortController().signal),e=>e.reasonCode==='limit');
  globalThis.fetch=async()=>response([encoder.encode('%PDF-'),new Uint8Array(MAX_PDF_BYTES)]);
  await assert.rejects(fetchBoundedPdf(origin+'/pdf',new AbortController().signal),e=>e.reasonCode==='limit');
  globalThis.fetch=async()=>response([],{},404);
  await assert.rejects(fetchBoundedPdf(origin+'/pdf',new AbortController().signal),e=>e.reasonCode==='missing');
 }finally{globalThis.fetch=save}
});
test('#150 sandbox reader and lesson compile and remain behind opt-in feature flag',async()=>{
 const source=await readFile(new URL('../src/shared/score/ScoreReader.vue',import.meta.url),'utf8');
 const preview=await readFile(new URL('../src/entities/diagnostic/ExercisePreview.vue',import.meta.url),'utf8');
 const diagnostic=await readFile(new URL('../src/entities/diagnostic/DiagnosticView.vue',import.meta.url),'utf8');
 const {descriptor,errors}=parse(source,{filename:'ScoreReader.vue'});
 assert.deepEqual(errors,[]);
 assert.ok(compileScript(descriptor,{id:'score-reader'}).content);
 assert.deepEqual(compileTemplate({source:descriptor.template.content,filename:'ScoreReader.vue',id:'score-reader'}).errors,[]);
 for(const expected of ["sandbox=\"allow-scripts\"","connect-src 'none'","worker-src blob:","nonce","MessageChannel","MAX_CANVAS_PIXELS","isEvalSupported:false","pdfjs-dist/build/pdf.mjs?raw","pdfjs-dist/build/pdf.worker.mjs?raw"]) {
  assert.ok(source.includes(expected)||transport.includes(expected),expected);
 }
 assert.doesNotMatch(source,/allow-same-origin|iframe[^\n]*:src="pdfUrl"/);
 assert.match(preview,/assigned && lightMinimal/);
 assert.match(preview,/defineAsyncComponent\(\(\) => import\('\.\.\/\.\.\/shared\/score\/ScoreReader\.vue'\)\)/);
 assert.match(diagnostic,/grid-template-columns:minmax\(0,1fr\) 320px/);
 assert.match(diagnostic,/@media\(max-width:1099px\)/);
 assert.match(diagnostic,/TASK_ASSIGNED/);
});
test('#150 toolbar and errors are bilingual',async()=>{
 for(const language of ['ru','en']){
  const messages=JSON.parse(await readFile(new URL('../src/i18n/'+language+'.json',import.meta.url),'utf8')).score;
  for(const key of ['heading','open','close','previous','next','zoomIn','zoomOut','fitWidth','focus','exitFocus','openOriginal','musicXml','retry','unavailable']) assert.ok(messages[key],language+': '+key);
  for(const key of ['missing','network','auth','type','limit','signature','timeout','password','corrupt','render']) assert.ok(messages.errors[key],language+': '+key);
 }
});


test('#150 assigned session must not become a silent empty workspace when task GET is null or fails',async()=>{
 const source=await readFile(new URL('../src/entities/diagnostic/DiagnosticView.vue',import.meta.url),'utf8');
 assert.match(source,/const taskFailure = ref/);
 assert.match(source,/if \(!requestedTask\) taskFailure\.value = 'missing'/);
 assert.match(source,/taskFailure\.value = isHttp\(cause, 404\) \? 'missing' : 'network'/);
 assert.match(source,/session\?\.status === 'TASK_ASSIGNED' && !task/);
 assert.match(source,/role="alert"/);
 assert.match(source,/@click="retryAssignedTask"/);
 assert.match(source,/seq === taskGeneration/);
 for(const lang of ['ru','en']) {
  const json=JSON.parse(await readFile(new URL('../src/i18n/'+lang+'.json',import.meta.url),'utf8'));
  for(const label of ['assignedTaskLoading','assignedTaskMissing','assignedTaskUnavailable'])
   assert.ok(json.diagnostic[label],lang+': '+label);
 }
});


test('#150 owner regression: centered lesson and Start never silently drops saved session',async()=>{
 const source=await readFile(new URL('../src/entities/diagnostic/DiagnosticView.vue',import.meta.url),'utf8');
 assert.match(source,/margin-inline:auto/);
 assert.match(source,/width:min\(100% - 48px,1560px\)/);
 assert.match(source,/lesson-submission :deep\(input\[type="file"\]\)/);
 assert.match(source,/await restoreSession\(\);\n    if \(!session\.value/);
 assert.doesNotMatch(source,/failedRestoreRead\.value \|\| window\.localStorage\.getItem\(storageKey\(id\)\)\) return/);
 for (const lang of ['ru','en']){
   const dictionary=JSON.parse(await readFile(new URL('../src/i18n/'+lang+'.json',import.meta.url),'utf8'));
   assert.ok(dictionary.diagnostic.startRecoveryHint);
 }
});


test('#150 accepted layout contract: actual assignment banner, full-width score and single recording panel',async()=>{
 const diagnostic=await readFile(new URL('../src/entities/diagnostic/DiagnosticView.vue',import.meta.url),'utf8');
 const preview=await readFile(new URL('../src/entities/diagnostic/ExercisePreview.vue',import.meta.url),'utf8');
 const submission=await readFile(new URL('../src/entities/attempt/SubmissionPanel.vue',import.meta.url),'utf8');
 const microphone=await readFile(new URL('../src/entities/attempt/MicrophoneRecorder.vue',import.meta.url),'utf8');
 assert.match(diagnostic,/studio-titlebar/);
 assert.match(diagnostic,/studio-assignment-description/);
 assert.match(diagnostic,/studio-columns/);
 assert.match(diagnostic,/studio-score/);
 assert.match(diagnostic,/studio-sidebar/);
 assert.match(diagnostic,/grid-template-columns:minmax\(0,1fr\) minmax\(340px,500px\)/);
 assert.match(diagnostic,/profile-page\.diagnostic-workspace--light/);
 assert.match(diagnostic,/max-width:none;width:min\(calc\(100% - 64px\),1560px\)/);
 assert.match(diagnostic,/session\?\.status === 'TASK_ASSIGNED' && task && !lightMinimal/);
 assert.match(diagnostic,/session\?\.status === 'TASK_ASSIGNED' && task && lightMinimal/);
 assert.equal((diagnostic.match(/<SubmissionPanel/g)||[]).length,2,'one submission component per exclusive branch');
 assert.match(preview,/role="tablist"/);
 assert.match(preview,/studioTab === 'score'/);
 assert.doesNotMatch(preview,/studioTab === 'audio'/);
 assert.match(preview,/studioTab === 'description'/);
 assert.match(preview,/onStudioTabKeydown/);
 assert.match(preview,/if \(props\.assigned && lightMinimal\) pdfOpen\.value = true/);
 assert.match(submission,/class="recording-primary"/);
 assert.match(submission,/class="recording-file-option"/);
 assert.match(microphone,/compact\?: boolean/);
 assert.match(microphone,/class="compact-recording-info"/);
 for(const lang of ['ru','en']){
  const json=JSON.parse(await readFile(new URL('../src/i18n/'+lang+'.json',import.meta.url),'utf8'));
  for(const key of ['currentTitle','subtitle','assigned','scoreTab','descriptionTab'])
   assert.ok(json.diagnostic.studio[key],lang+':'+key);
  assert.ok(json.attempt.microphoneInstructions);
 }
});


test('#148/#149 regression: unified local launcher retains accepted Light Minimal UI by default',async()=>{
 const launcher=await readFile(new URL('../../scripts/local-auth-unified.ps1',import.meta.url),'utf8');
 assert.match(launcher,/if \(\[string\]::IsNullOrWhiteSpace\(\$env:VITE_GUITAR_MENTOR_LIGHT_MINIMAL\)\)/);
 assert.match(launcher,/\$env:VITE_GUITAR_MENTOR_LIGHT_MINIMAL = 'true'/);
 assert.match(launcher,/\$env:VITE_GUITAR_MENTOR_AUTH_REQUIRED = 'true'/);
});


test('#150 microphone recording is available independently of upload capability', async () => {
  const submission = await readFile(new URL('../src/entities/attempt/SubmissionPanel.vue', import.meta.url), 'utf8');
  const recorder = await readFile(new URL('../src/entities/attempt/MicrophoneRecorder.vue', import.meta.url), 'utf8');
  const uses = [...submission.matchAll(/<MicrophoneRecorder\b[^>]*:[d]isabled="([^"]+)"/g)];
  assert.equal(uses.length, 2, 'both feature-flag branches mount the same recording contract');
  for (const match of uses) {
    assert.doesNotMatch(match[1], /capabilities\??\.enabled/, 'local recording must not be gated on server upload');
    assert.match(match[1], /learningTerminal/);
    assert.match(match[1], /uploading/);
  }
  assert.match(submission, /!capabilities\.value\?\.enabled/, 'server submission must remain upload-gated');
  assert.match(recorder, /navigator\.mediaDevices\.getUserMedia/);
  assert.match(recorder, /emit\('recorded', new File/);
  for (const lang of ['ru','en']) {
    const dict = JSON.parse(await readFile(new URL('../src/i18n/'+lang+'.json', import.meta.url), 'utf8'));
    assert.ok(dict.attempt.localRecordingAvailable);
  }
});


test('#150 secure unified local environment explicitly enables authenticated uploads, preserving override', async () => {
  const launcher = await readFile(new URL('../../scripts/local-auth-unified.ps1', import.meta.url), 'utf8');
  assert.match(launcher, /GUITAR_MENTOR_SECURITY_ENABLED = 'true'/);
  assert.match(launcher, /SPRING_PROFILES_ACTIVE = 'secure,local-auth,local-unified'/);
  assert.match(launcher, /GUITAR_MENTOR_BIND_ADDRESS = '127.0.0.1'/);
  assert.match(launcher, /IsNullOrWhiteSpace\(\$env:GUITAR_MENTOR_ENABLE_UPLOADS\)/);
  assert.match(launcher, /\$env:GUITAR_MENTOR_ENABLE_UPLOADS = 'true'/);
  const submission = await readFile(new URL('../src/entities/attempt/SubmissionPanel.vue', import.meta.url), 'utf8');
  assert.match(submission, /id="attempt-comment"[^>]*v-model="comment"/);
  assert.match(submission, /:disabled="uploading"\s*:placeholder="t\('attempt.commentPlaceholder'\)"/);
  assert.match(submission, /!capabilities\.value\?\.enabled/);
  for (const lang of ['ru','en']) {
    const copy = JSON.parse(await readFile(new URL('../src/i18n/'+lang+'.json', import.meta.url),'utf8')).attempt;
    assert.doesNotMatch(copy.disabledHelp, /не имеет авторизации|does not have auth/i);
    assert.match(copy.disabledHelp, /отключен|disabled/i);
  }
});

test('#150 design acceptance: assigned preview has real audio, capture waveform and compact score controls', async () => {
 const diagnostic=await readFile(new URL('../src/entities/diagnostic/DiagnosticView.vue',import.meta.url),'utf8');
 const microphone=await readFile(new URL('../src/entities/attempt/MicrophoneRecorder.vue',import.meta.url),'utf8');
 const submission=await readFile(new URL('../src/entities/attempt/SubmissionPanel.vue',import.meta.url),'utf8');
 const reader=await readFile(new URL('../src/shared/score/ScoreReader.vue',import.meta.url),'utf8');
 assert.match(diagnostic,/class="studio-audio-example"/);
 assert.match(diagnostic,/:src="task\.exercise\.previewAudioUrl"/);
 assert.match(diagnostic,/preload="none" controls/);
 assert.match(microphone,/class="studio-live-wave"/);
 assert.match(microphone,/meterLevel/);
 assert.match(microphone,/v-if="supported \|\| !compact" class="microphone-controls"/);
 assert.match(submission,/studio-result-card/);
 assert.match(reader,/v-if="!fitWidth"/);
 assert.match(reader,/justify-content:flex-end/);
});

test('#150 compact capture and upload are placed in one real form action row', async () => {
 const submission=await readFile(new URL('../src/entities/attempt/SubmissionPanel.vue',import.meta.url),'utf8');
 const microphone=await readFile(new URL('../src/entities/attempt/MicrophoneRecorder.vue',import.meta.url),'utf8');
 assert.match(submission, /<template #actions>/);
 assert.match(submission, /class="recorder-button studio-send-button"/);
 assert.match(submission, /<div v-if="!lightMinimal" class="form-actions">/);
 assert.match(microphone, /<slot name="actions"\s*\/>/);
 assert.match(submission, /:disabled="learningTerminal \|\| !file \|\| !capabilities\?\.enabled \|\| uploading \|\| recordingActive"/);
});

test('#150 final visual revisions: example audio is only in hero, status separate, recording playback is inline',async()=>{
 const diagnostic=await readFile(new URL('../src/entities/diagnostic/DiagnosticView.vue',import.meta.url),'utf8');
 const preview=await readFile(new URL('../src/entities/diagnostic/ExercisePreview.vue',import.meta.url),'utf8');
 const submission=await readFile(new URL('../src/entities/attempt/SubmissionPanel.vue',import.meta.url),'utf8');
 assert.match(diagnostic,/class="studio-assignment-actions"/);
 assert.match(diagnostic,/class="studio-assignment-status"/);
 assert.match(diagnostic,/class="studio-audio-example"/);
 assert.match(preview,/const tabs = \['score','description'\]/);
 assert.doesNotMatch(preview,/role="tab"[^\n]+studioTab === 'audio'/);
 assert.match(preview,/v-if="!assigned \|\| !lightMinimal" class="exercise-preview-audio"/);
 assert.match(submission,/t\('attempt.recordingPanelTitle'\)/);
 assert.match(submission,/class="studio-play-button"/);
 assert.match(submission,/class="studio-discard-button"/);
 for(const lang of ['ru','en']){
  const j=JSON.parse(await readFile(new URL('../src/i18n/'+lang+'.json',import.meta.url),'utf8'));
  assert.ok(j.attempt.recordingPanelTitle);
 }
});

test('#150 approved microphone row has mic icon, compact controls and valid disabled playback', async () => {
 const s=await readFile(new URL('../src/entities/attempt/SubmissionPanel.vue',import.meta.url),'utf8');
 const mic=await readFile(new URL('../src/entities/attempt/MicrophoneRecorder.vue',import.meta.url),'utf8');
 assert.match(s,/class="studio-mic-icon"/);
 assert.match(s,/class="studio-play-button"/);
 assert.match(s,/:disabled="!file \|\| !recordedPreview \|\| recordingActive \|\| uploading"/);
 assert.match(s,/@ended="playbackActive = false"/);
 assert.match(s,/@click="togglePlayback"/);
 assert.match(s,/:disabled="learningTerminal \|\| !file \|\| !capabilities\?\.enabled \|\| uploading \|\| recordingActive"/);
 assert.match(mic,/studio-record-dot/);
 assert.match(mic,/studio-stop-square/);
 for (const lang of ['ru','en']){
  const j=JSON.parse(await readFile(new URL('../src/i18n/'+lang+'.json',import.meta.url),'utf8'));
  assert.ok(j.attempt.listenShort && j.attempt.submitShort && j.attempt.pausePlayback);
 }
});

test('#150 selected input tabs and two result cards preserve one shared upload owner',async()=>{
 const view=await readFile(new URL('../src/entities/attempt/SubmissionPanel.vue',import.meta.url),'utf8');
 const reader=await readFile(new URL('../src/entities/diagnostic/ExercisePreview.vue',import.meta.url),'utf8');
 assert.match(view,/class="recording-mode-tabs" role="tablist"/);
 assert.match(view,/role="tabpanel"/);
 assert.match(view,/changeInputMode/);
 assert.match(view,/inputModeKeys/);
 assert.match(view,/!canSwitchInput/);
 assert.match(view,/sourceKind.value = 'record'/);
 assert.match(view,/sourceKind.value = 'file'/);
 assert.match(view,/class="studio-file-picker"/);
 assert.match(view,/class="studio-selected-file"/);
 assert.match(view,/class="studio-discard-button"/);
 assert.match(view,/studio-record-card/);
 assert.match(view,/studio-result-card/);
 assert.equal((view.match(/<LearningResultsPanel /g)||[]).length,1);
 assert.match(reader,/studioTab === 'score'/);
 assert.match(reader,/studioTab === 'description'/);
 assert.match(reader,/viewBox="0 0 24 24"/);
 for(const lang of ['ru','en']) {
   const j=JSON.parse(await readFile(new URL('../src/i18n/'+lang+'.json',import.meta.url),'utf8'));
   for(const key of ['recordTab','fileTab','chooseFile','removeAttachment'])
    assert.ok(j.attempt[key]);
 }
});

test('#150 desktop score toolbar shares the same grid row with notes/description tabs', async () => {
 const preview=await readFile(new URL('../src/entities/diagnostic/ExercisePreview.vue',import.meta.url),'utf8');
 assert.match(preview,/\.studio-preview \{[^}]*display:grid;grid-template-columns:max-content minmax\(0,1fr\)/);
 assert.match(preview,/\.studio-tabs \{[^}]*grid-column:1;grid-row:1/);
 assert.match(preview,/\.studio-preview :deep\(\.score-toolbar\) \{[^}]*grid-column:2;grid-row:1/);
 assert.match(preview,/\.studio-preview :deep\(\.score-reader > div:not\(\.score-toolbar\)\) \{[^}]*grid-column:1 \/ -1/);
 assert.match(preview,/@media\(max-width:780px\)/);
});

test('#150 description tab renders goal and instructions without a nested disclosure', async () => {
 const preview=await readFile(new URL('../src/entities/diagnostic/ExercisePreview.vue',import.meta.url),'utf8');
 assert.match(preview,/<section class="exercise-learning"/);
 assert.match(preview,/class="studio-description-heading"/);
 assert.doesNotMatch(preview,/<summary v-if="assigned && lightMinimal">/);
 assert.doesNotMatch(preview,/:is="assigned && lightMinimal \? 'details' : 'section'"/);
 assert.match(preview,/diagnostic\.metadata\.goal/);
 assert.match(preview,/diagnostic\.metadata\.instructions/);
});

test('#150 artwork: approved acoustic image replaces note tile with safe fallback', async () => {
 const { stat, readFile: readBytes } = await import('node:fs/promises');
 const artwork = new URL('../public/images/exercise-default-acoustic.jpg', import.meta.url);
 const bytes = await readBytes(artwork);
 assert.ok(bytes.length > 1500);
 assert.equal(bytes[0], 0xff);
 assert.equal(bytes[1], 0xd8);
 assert.equal(bytes.at(-2), 0xff);
 assert.equal(bytes.at(-1), 0xd9);
 const view=await readFile(new URL('../src/entities/diagnostic/DiagnosticView.vue',import.meta.url),'utf8');
 const model=await readFile(new URL('../src/shared/model/diagnostic.model.ts',import.meta.url),'utf8');
 assert.match(view,/fallbackArtwork = '\/images\/exercise-default-acoustic\.jpg'/);
 assert.match(view,/class="studio-assignment-cover"/);
 assert.match(view,/:src="artworkSrc"/);
 assert.match(view,/@error="onArtworkError"/);
 assert.match(view,/object-fit:cover/);
 assert.match(model,/coverUrl\?: string \| null/);
});

test('#150 exercise metronome lives between recording and result without duplicate under notation', async () => {
 const preview=await readFile(new URL('../src/entities/diagnostic/ExercisePreview.vue',import.meta.url),'utf8');
 const submission=await readFile(new URL('../src/entities/attempt/SubmissionPanel.vue',import.meta.url),'utf8');
 const diagnostic=await readFile(new URL('../src/entities/diagnostic/DiagnosticView.vue',import.meta.url),'utf8');
 assert.match(preview,/v-if="!assigned \|\| !lightMinimal" class="metronome-exercise-tool"/);
 assert.match(submission,/class="studio-metronome-card"/);
 assert.ok(submission.indexOf('class="studio-metronome-card"') > submission.indexOf('studio-record-card'));
 assert.ok(submission.indexOf('class="studio-metronome-card"') < submission.indexOf('studio-result-card'));
 assert.match(submission,/openExerciseMetronome\(/);
 assert.match(submission,/exercise\.bpm, exercise\.meter, exercise\.tempoUnit/);
 assert.match(diagnostic,/:exercise="task\.exercise" @draft-state="onSubmissionDraft"/);
});
