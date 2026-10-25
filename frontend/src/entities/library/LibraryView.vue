<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import { auth } from '../../core/auth/auth';
import { asApiFailure, apiFailureKey } from '../../shared/api-failure';
import { libraryService, validateUpload, type LibraryMaterial, type LibraryMetadata, type MaterialType } from './library.service';

const { t } = useI18n();
const entries = ref<LibraryMaterial[]>([]);
const selected = ref<LibraryMaterial | null>(null);
const cursor = ref<string | null>(null);
const history = ref<(string | null)[]>([]);
const currentCursor = ref<string | null>(null);
const loading = ref(false);
const busy = ref(false);
const error = ref('');
const notice = ref('');
const title = ref('');
const author = ref('');
const type = ref<MaterialType>('COMPOSITION');
const files = ref<File[]>([]);
const fileInput = ref<HTMLInputElement | null>(null);
const editTitle = ref('');
const editAuthor = ref('');
const editType = ref<MaterialType>('COMPOSITION');
const editing = ref(false);
const removing = ref(false);
let active = true;
let generation = 0;
let pendingKey: string | null = null;
let lastUploadFingerprint = '';

function clearPrivate(): void {
  ++generation;
  entries.value = [];
  selected.value = null;
  cursor.value = null;
  history.value = [];
  currentCursor.value = null;
  error.value = '';
  notice.value = '';
  files.value = [];
  title.value = '';
  author.value = '';
  pendingKey = null;
  lastUploadFingerprint = '';
  loading.value = false;
  busy.value = false;
  editing.value = false;
  removing.value = false;
}
watch(() => auth.actor?.accountId, (a, b) => {
  if (a === b) return;
  clearPrivate();
  if (a) void load(null, []);
});
onBeforeUnmount(() => { active = false; clearPrivate(); });
onMounted(() => { if (auth.actor) void load(null, []); });

function message(cause: unknown): string {
  const failure = asApiFailure(cause);
  if (failure.status === 503) return t('library.disabled');
  if (failure.status === 413) return t('library.size');
  if (failure.status === 415) return t('library.format');
  if (failure.status === 428 || failure.status === 412) return t('library.conflict');
  return t(apiFailureKey(failure));
}
async function load(nextCursor: string | null, previous: (string | null)[]): Promise<void> {
  const ticket = ++generation;
  const owner = auth.actor?.accountId;
  if (!owner) return;
  loading.value = true;
  error.value = '';
  try {
    const page = await libraryService.page(nextCursor || undefined);
    if (!active || ticket !== generation || auth.actor?.accountId !== owner) return;
    entries.value = page.items;
    cursor.value = page.nextCursor;
    history.value = previous;
    currentCursor.value = nextCursor;
    if (selected.value) selected.value = page.items.find(item => item.id === selected.value?.id) ?? null;
  } catch (cause) {
    if (active && ticket === generation) error.value = message(cause);
  } finally {
    if (active && ticket === generation) loading.value = false;
  }
}
function pickFiles(event: Event): void {
  const input = event.target as HTMLInputElement;
  files.value = Array.from(input.files ?? []);
  pendingKey = null; lastUploadFingerprint = '';
  error.value = ''; notice.value = '';
}
function metadata(): LibraryMetadata {
  return { title: title.value.trim(), type: type.value, author: author.value.trim() || null };
}
async function upload(): Promise<void> {
  if (busy.value) return;
  error.value = ''; notice.value = '';
  if (!title.value.trim() || title.value.trim().length > 150 || author.value.trim().length > 150) {
    error.value = t('library.metadata'); return;
  }
  const invalid = validateUpload(files.value);
  if (invalid) { error.value = t('library.' + (invalid === 'empty' ? 'emptyFiles' : invalid)); return; }
  const fingerprint = JSON.stringify(metadata()) + ':' + files.value.map(file => file.name + ':' + file.size + ':' + file.lastModified).join('|');
  if (pendingKey === null || fingerprint !== lastUploadFingerprint) {
    pendingKey = crypto.randomUUID(); lastUploadFingerprint = fingerprint;
  }
  const owner = auth.actor?.accountId;
  const ticket = generation;
  if (!owner) return;
  busy.value = true;
  try {
    const saved = await libraryService.upload(metadata(), files.value, pendingKey);
    if (!active || ticket !== generation || auth.actor?.accountId !== owner) return;
    notice.value = t('library.uploaded');
    title.value = ''; author.value = ''; type.value = 'COMPOSITION';
    files.value = []; if (fileInput.value) fileInput.value.value = '';
    pendingKey = null; lastUploadFingerprint = '';
    await load(null, []);
    // Even if listing is transiently unavailable, show the successfully persisted card.
    if (active && auth.actor?.accountId === owner) selected.value = saved;
  } catch (cause) {
    if (active && ticket === generation) error.value = message(cause);
  } finally { if (active && ticket === generation) busy.value = false; }
}
async function openCard(id: string): Promise<void> {
  const ticket = generation;
  const owner = auth.actor?.accountId;
  if (!owner) return;
  error.value = '';
  try {
    const card = await libraryService.detail(id);
    if (active && ticket === generation && auth.actor?.accountId === owner) {
      selected.value = card;
      editing.value = false; removing.value = false;
      editTitle.value = card.title; editType.value = card.type; editAuthor.value = card.author || '';
    }
  } catch (cause) { if (active && ticket === generation) error.value = message(cause); }
}
async function saveCard(): Promise<void> {
  if (!selected.value || busy.value) return;
  if (!editTitle.value.trim() || editTitle.value.trim().length > 150 || editAuthor.value.trim().length > 150) {
    error.value = t('library.metadata'); return;
  }
  const owner = auth.actor?.accountId, ticket = generation, card = selected.value;
  busy.value = true; error.value = ''; notice.value = '';
  try {
    const updated = await libraryService.edit(card.id, card.revision, {
      title: editTitle.value.trim(), type: editType.value, author: editAuthor.value.trim() || null,
    });
    if (!active || ticket !== generation || auth.actor?.accountId !== owner) return;
    selected.value = updated; editing.value = false;
    entries.value = entries.value.map(item => item.id === updated.id ? updated : item);
    notice.value = t('library.saved');
  } catch (cause) { if (active && ticket === generation) error.value = message(cause); }
  finally { if (active && ticket === generation) busy.value = false; }
}
async function removeCard(): Promise<void> {
  if (!selected.value || busy.value) return;
  const owner = auth.actor?.accountId, ticket = generation, card = selected.value;
  busy.value = true; error.value = ''; notice.value = '';
  try {
    await libraryService.remove(card.id, card.revision);
    if (!active || ticket !== generation || auth.actor?.accountId !== owner) return;
    selected.value = null; removing.value = false;
    notice.value = t('library.deleted');
    await load(null, []);
  } catch (cause) { if (active && ticket === generation) error.value = message(cause); }
  finally { if (active && ticket === generation) busy.value = false; }
}
async function download(file: LibraryMaterial['files'][number]): Promise<void> {
  if (!selected.value || busy.value) return;
  const ticket = generation, owner = auth.actor?.accountId;
  busy.value = true; error.value = '';
  try { await libraryService.download(selected.value.id, file); }
  catch (cause) { if (active && ticket === generation && auth.actor?.accountId === owner) error.value = message(cause); }
  finally { if (active && ticket === generation) busy.value = false; }
}
</script>

<template>
  <section class="library-page" aria-labelledby="library-title">
    <header class="library-intro">
      <p class="library-eyebrow">{{ t('library.eyebrow') }}</p>
      <h1 id="library-title">{{ t('library.title') }}</h1>
      <p>{{ t('library.subtitle') }}</p>
    </header>
    <p v-if="error" class="library-error" role="alert">{{ error }}</p>
    <div v-if="busy" class="library-progress" role="status" aria-live="polite">
      <progress aria-label="Uploading or updating material" />
      <span>{{ t('library.working') }}</span>
    </div>
    <p v-if="notice" class="library-success" role="status">{{ notice }}</p>
    <div class="library-layout">
      <section class="library-panel" aria-labelledby="library-upload-heading">
        <h2 id="library-upload-heading">{{ t('library.add') }}</h2>
        <p class="library-muted">{{ t('library.privateNote') }}</p>
        <form @submit.prevent="upload">
          <label class="library-field">{{ t('library.name') }}
            <input v-model="title" type="text" required maxlength="150" :disabled="busy" />
          </label>
          <label class="library-field">{{ t('library.type') }}
            <select v-model="type" :disabled="busy">
              <option value="COMPOSITION">{{ t('library.types.COMPOSITION') }}</option>
              <option value="ETUDE">{{ t('library.types.ETUDE') }}</option>
              <option value="EXERCISE">{{ t('library.types.EXERCISE') }}</option>
              <option value="OTHER">{{ t('library.types.OTHER') }}</option>
            </select>
          </label>
          <label class="library-field">{{ t('library.author') }}
            <input v-model="author" type="text" maxlength="150" :disabled="busy" />
          </label>
          <label class="library-field">{{ t('library.files') }}
            <input ref="fileInput" type="file" multiple accept=".pdf,.gtp,.gp,.gp3,.gp4,.gp5,.gpx"
              :disabled="busy" @change="pickFiles" />
          </label>
          <p class="library-muted">{{ t('library.limits') }}</p>
          <ul v-if="files.length" class="library-files">
            <li v-for="(file,index) in files" :key="index">{{ file.name }} · {{ (file.size / 1024 / 1024).toFixed(2) }} MB</li>
          </ul>
          <button type="submit" :disabled="busy || !title.trim() || files.length === 0">
            {{ busy ? t('library.working') : t('library.upload') }}
          </button>
        </form>
      </section>
      <section class="library-panel" aria-labelledby="library-list-heading">
        <div class="library-heading">
          <h2 id="library-list-heading">{{ t('library.myMaterials') }}</h2>
          <button type="button" class="library-secondary" :disabled="loading || busy" @click="load(null,[])">{{ t('library.refresh') }}</button>
        </div>
        <p v-if="loading" role="status">{{ t('library.loading') }}</p>
        <p v-else-if="entries.length === 0" class="library-muted">{{ t('library.empty') }}</p>
        <ul v-else class="library-list">
          <li v-for="item in entries" :key="item.id">
            <button type="button" class="library-item" :disabled="busy" @click="openCard(item.id)">
              <strong>{{ item.title }}</strong>
              <small>{{ t('library.types.' + item.type) }} · {{ item.files.length }} {{ t('library.fileCount') }}</small>
            </button>
          </li>
        </ul>
        <div class="library-pagination">
          <button type="button" class="library-secondary" :disabled="loading || busy || history.length === 0"
            @click="load(history[history.length-1] ?? null,history.slice(0,-1))">{{ t('library.previous') }}</button>
          <button type="button" class="library-secondary" :disabled="loading || busy || !cursor"
            @click="load(cursor,[...history,currentCursor])">{{ t('library.next') }}</button>
        </div>
      </section>
    </div>

    <section v-if="selected" class="library-panel library-detail" aria-labelledby="library-detail-heading">
      <div class="library-heading">
        <h2 id="library-detail-heading">{{ t('library.details') }}</h2>
        <button type="button" class="library-secondary" @click="selected=null;editing=false;removing=false">{{ t('library.close') }}</button>
      </div>
      <template v-if="!editing">
        <h3>{{ selected.title }}</h3>
        <p>{{ t('library.types.' + selected.type) }} · {{ selected.author || t('library.unknownAuthor') }}</p>
        <p class="library-muted">{{ t('library.privateNote') }}</p>
        <ul class="library-files">
          <li v-for="file in selected.files" :key="file.id" class="library-download">
            <span>{{ file.originalName }} · {{ file.format }} · {{ (file.byteSize / 1024 / 1024).toFixed(2) }} MB</span>
            <button type="button" class="library-secondary" :disabled="busy" @click="download(file)">{{ t('library.download') }}</button>
          </li>
        </ul>
        <div class="library-actions">
          <button type="button" class="library-secondary" :disabled="busy" @click="editing=true">{{ t('library.edit') }}</button>
          <button type="button" class="library-danger" :disabled="busy" @click="removing=true">{{ t('library.delete') }}</button>
        </div>
      </template>
      <form v-else class="library-edit" @submit.prevent="saveCard">
        <label class="library-field">{{ t('library.name') }}<input v-model="editTitle" maxlength="150" required :disabled="busy" /></label>
        <label class="library-field">{{ t('library.type') }}<select v-model="editType" :disabled="busy">
          <option v-for="value in (['COMPOSITION','ETUDE','EXERCISE','OTHER'] as const)" :key="value" :value="value">{{ t('library.types.' + value) }}</option>
        </select></label>
        <label class="library-field">{{ t('library.author') }}<input v-model="editAuthor" maxlength="150" :disabled="busy" /></label>
        <div class="library-actions">
          <button type="submit" :disabled="busy">{{ t('library.save') }}</button>
          <button type="button" class="library-secondary" :disabled="busy" @click="editing=false">{{ t('library.cancel') }}</button>
        </div>
      </form>
      <div v-if="removing" class="library-confirm" role="group" :aria-label="t('library.delete')">
        <p>{{ t('library.confirmDelete') }}</p>
        <div class="library-actions">
          <button type="button" class="library-danger" :disabled="busy" @click="removeCard">{{ t('library.confirm') }}</button>
          <button type="button" class="library-secondary" :disabled="busy" @click="removing=false">{{ t('library.cancel') }}</button>
        </div>
      </div>
    </section>
  </section>
</template>

<style scoped>
.library-page{max-width:1140px;margin:0 auto;padding:42px 24px 80px;color:var(--text-primary,#263445)}
.library-intro{margin-bottom:28px}.library-eyebrow{font-size:.75rem;letter-spacing:.12em;text-transform:uppercase;color:#397d71;font-weight:700}
.library-intro h1{font-size:clamp(1.8rem,4vw,2.6rem);margin:8px 0}.library-intro p,.library-muted{color:var(--text-secondary,#657487);line-height:1.6}
.library-layout{display:grid;grid-template-columns:minmax(280px,.9fr) minmax(320px,1.1fr);gap:20px;align-items:start}
.library-panel{background:var(--surface,#fff);border:1px solid #dce5e5;border-radius:18px;padding:24px;min-width:0}
.library-panel h2{font-size:1.2rem;margin:0 0 14px}.library-field{display:flex;flex-direction:column;gap:7px;margin:14px 0;font-weight:600;font-size:.92rem}
.library-field input,.library-field select{box-sizing:border-box;width:100%;min-height:42px;border:1px solid #b8c9ca;border-radius:8px;padding:9px 11px;background:var(--surface,#fff);color:inherit;font:inherit;max-width:100%}
.library-panel button{cursor:pointer;font:inherit;border-radius:9px;padding:10px 15px;border:1px solid #2d746b;background:#28776d;color:#fff}
.library-panel button:disabled{opacity:.5;cursor:not-allowed}
.library-panel button:focus-visible,.library-field :focus-visible{outline:3px solid #95cebf;outline-offset:2px}
.library-panel .library-secondary{border-color:#b7cbca;color:#23544e;background:transparent}
.library-panel .library-danger{border-color:#a4343b;background:#a4343b;color:#fff}
.library-heading,.library-actions,.library-pagination{display:flex;align-items:center;justify-content:space-between;gap:12px;flex-wrap:wrap}
.library-list,.library-files{padding:0;list-style:none}.library-list li{border-bottom:1px solid #e3ebeb}
.library-panel button.library-item{background:none;color:inherit;border:0;border-radius:6px;text-align:left;width:100%;padding:14px 6px;display:flex;flex-direction:column;gap:5px;overflow-wrap:anywhere}
.library-item:hover{background:#eaf5f1!important}.library-item small{color:#647787}
.library-pagination{justify-content:flex-end;margin-top:18px}.library-detail{margin-top:20px}
.library-download{display:flex;align-items:center;justify-content:space-between;gap:12px;flex-wrap:wrap;border-bottom:1px solid #e3ebeb;padding:10px 0;overflow-wrap:anywhere}
.library-progress{display:flex;align-items:center;gap:12px;margin:10px 0}.library-progress progress{width:140px;max-width:40vw;height:7px}
.library-error,.library-success{padding:12px 15px;border-radius:9px;margin:12px 0;background:#fff1f0;color:#7a2424}
.library-success{background:#eef8f2;color:#1d684e}.library-confirm{margin-top:20px;border:1px solid #e5c4c4;border-radius:10px;padding:14px}
@media(max-width:760px){.library-layout{grid-template-columns:1fr}.library-page{padding:24px 14px 55px}.library-panel{padding:18px}}
</style>
