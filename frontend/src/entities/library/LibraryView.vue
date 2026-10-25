<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import { auth } from '../../core/auth/auth';
import { asApiFailure, apiFailureKey } from '../../shared/api-failure';
import { libraryService, validateUpload, type LibraryMaterial, type LibraryMetadata, type MaterialType } from './library.service';

const { t, locale } = useI18n();
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
const uploadOpen = ref(false);
const editOpen = ref(false);
const actionFor = ref<string | null>(null);
const actionBusyId = ref<string | null>(null);
let active = true;
let generation = 0;
let detailGeneration = 0;
let pendingKey: string | null = null;
let lastUploadFingerprint = '';

function clearPrivate(): void {
  ++generation;
  ++detailGeneration;
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
  type.value = 'COMPOSITION';
  editTitle.value = '';
  editAuthor.value = '';
  editType.value = 'COMPOSITION';
  if (fileInput.value) fileInput.value.value = '';
  pendingKey = null;
  lastUploadFingerprint = '';
  loading.value = false;
  busy.value = false;
  editing.value = false;
  removing.value = false;
  uploadOpen.value = false;
  editOpen.value = false;
  actionFor.value = null;
  actionBusyId.value = null;
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
  ++detailGeneration;
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
  // upload() retains its key for identical retry payloads.
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
    uploadOpen.value = false;
    await load(null, []);
    // Even if listing is transiently unavailable, show the successfully persisted card.
    if (active && auth.actor?.accountId === owner) selected.value = saved;
  } catch (cause) {
    if (active && ticket === generation) error.value = message(cause);
  } finally { if (active && auth.actor?.accountId === owner) busy.value = false; }
}
async function openCard(id: string): Promise<void> {
  const ticket = generation;
  const requestId = ++detailGeneration;
  const owner = auth.actor?.accountId;
  if (!owner) return;
  error.value = '';
  try {
    const card = await libraryService.detail(id);
    if (active && ticket === generation && requestId === detailGeneration && auth.actor?.accountId === owner) {
      selected.value = card;
      editing.value = false; removing.value = false;
      editTitle.value = card.title; editType.value = card.type; editAuthor.value = card.author || '';
    }
  } catch (cause) { if (active && ticket === generation && requestId === detailGeneration) error.value = message(cause); }
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
    selected.value = updated; editing.value = false; editOpen.value = false;
    entries.value = entries.value.map(item => item.id === updated.id ? updated : item);
    notice.value = t('library.saved');
  } catch (cause) { if (active && ticket === generation) error.value = message(cause); }
  finally { if (active && auth.actor?.accountId === owner) busy.value = false; }
}
async function removeCard(): Promise<void> {
  if (!selected.value || busy.value) return;
  const owner = auth.actor?.accountId, ticket = generation, card = selected.value;
  busy.value = true; error.value = ''; notice.value = '';
  try {
    await libraryService.remove(card.id, card.revision);
    if (!active || ticket !== generation || auth.actor?.accountId !== owner) return;
    selected.value = null; removing.value = false; actionFor.value = null;
    notice.value = t('library.deleted');
    await load(null, []);
  } catch (cause) { if (active && ticket === generation) error.value = message(cause); }
  finally { if (active && auth.actor?.accountId === owner) busy.value = false; }
}
async function download(file: LibraryMaterial['files'][number]): Promise<void> {
  if (!selected.value || busy.value) return;
  const ticket = generation, owner = auth.actor?.accountId;
  busy.value = true; error.value = '';
  try { await libraryService.download(selected.value.id, file,
    () => active && ticket === generation && auth.actor?.accountId === owner); }
  catch (cause) { if (active && ticket === generation && auth.actor?.accountId === owner) error.value = message(cause); }
  finally { if (active && auth.actor?.accountId === owner) busy.value = false; }
}

function openUpload(): void {
  if (busy.value) return;
  actionFor.value = null;
  error.value = '';
  notice.value = '';
  uploadOpen.value = true;
}
function closeUpload(): void {
  if (busy.value) return;
  uploadOpen.value = false;
}
function toggleActions(id: string): void {
  if (busy.value) return;
  actionFor.value = actionFor.value === id ? null : id;
}
async function rowAction(item: LibraryMaterial, action: 'edit' | 'delete' | 'download', file?: LibraryMaterial['files'][number]): Promise<void> {
  if (busy.value) return;
  actionFor.value = null;
  actionBusyId.value = item.id;
  try {
    await openCard(item.id);
    if (selected.value?.id !== item.id) return;
    if (action === 'edit') {
      editOpen.value = true;
      removing.value = false;
    } else if (action === 'delete') {
      removing.value = true;
      editOpen.value = false;
    } else if (file) {
      const currentFile = selected.value.files.find(f => f.id === file.id);
      if (currentFile) await download(currentFile);
    }
  } finally {
    actionBusyId.value = null;
  }
}
function closeDialog(): void {
  if (busy.value) return;
  uploadOpen.value = false;
  editOpen.value = false;
  removing.value = false;
}
function onPageKeydown(event: KeyboardEvent): void {
  if (event.key !== 'Escape' || busy.value) return;
  if (uploadOpen.value || editOpen.value || removing.value) closeDialog();
  else actionFor.value = null;
}
</script>

<template>
  <section class="library-page" aria-labelledby="library-title" @keydown="onPageKeydown">
    <div class="library-toolbar">
      <div class="library-intro">
        <p class="library-eyebrow">{{ t('library.eyebrow') }}</p>
        <h1 id="library-title">{{ t('library.title') }}</h1>
        <p>{{ t('library.subtitle') }}</p>
      </div>
      <button type="button" class="library-primary add-action" :disabled="busy" @click="openUpload">
        <span aria-hidden="true">＋</span> {{ t('library.add') }}
      </button>
    </div>
    <p v-if="error" class="library-error" role="alert">{{ error }}</p>
    <p v-if="notice" class="library-success" role="status">{{ notice }}</p>

    <section class="library-panel" aria-labelledby="library-list-heading">
      <div class="library-table-heading">
        <div>
          <h2 id="library-list-heading">{{ t('library.myMaterials') }}</h2>
          <p class="library-muted">{{ t('library.privateNote') }}</p>
        </div>
        <button type="button" class="library-quiet" :disabled="loading || busy" @click="load(null,[])">{{ t('library.refresh') }}</button>
      </div>
      <p v-if="loading" class="library-muted" role="status">{{ t('library.loading') }}</p>
      <p v-else-if="entries.length === 0" class="library-empty">{{ t('library.empty') }}</p>
      <div v-else class="library-table-scroll">
        <table class="library-table">
          <thead>
            <tr>
              <th scope="col">{{ t('library.name') }}</th>
              <th scope="col">{{ t('library.type') }}</th>
              <th scope="col">{{ t('library.author') }}</th>
              <th scope="col">{{ t('library.files') }}</th>
              <th scope="col">{{ t('library.updated') }}</th>
              <th scope="col" class="action-column">{{ t('library.actions') }}</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="item in entries" :key="item.id">
              <td class="name-cell"><strong>{{ item.title }}</strong></td>
              <td>{{ t('library.types.' + item.type) }}</td>
              <td>{{ item.author || '—' }}</td>
              <td>{{ item.files.length }}</td>
              <td class="date-cell">{{ new Date(item.updatedAt).toLocaleDateString(locale === 'ru' ? 'ru-RU' : 'en-US') }}</td>
              <td class="action-cell">
                <div class="row-actions">
                  <button type="button" class="library-more" :aria-label="t('library.actionsFor', { title: item.title })"
                    :aria-expanded="actionFor === item.id" aria-haspopup="menu"
                    :disabled="busy || actionBusyId === item.id" @click="toggleActions(item.id)">
                    <span aria-hidden="true">⋯</span>
                  </button>
                  <div v-if="actionFor === item.id" class="library-dropdown" role="menu">
                    <button v-for="file in item.files" :key="file.id" type="button" role="menuitem"
                      @click="rowAction(item,'download',file)">
                      <span aria-hidden="true">↓</span> {{ t('library.download') }}<small v-if="item.files.length > 1"> · {{ file.originalName }}</small>
                    </button>
                    <button type="button" role="menuitem" @click="rowAction(item,'edit')">
                      <span aria-hidden="true">✎</span> {{ t('library.edit') }}
                    </button>
                    <button type="button" role="menuitem" class="danger-item" @click="rowAction(item,'delete')">
                      <span aria-hidden="true">×</span> {{ t('library.delete') }}
                    </button>
                  </div>
                </div>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <div class="library-pagination">
        <button type="button" class="library-quiet" :disabled="loading || busy || history.length === 0"
          @click="load(history[history.length-1] ?? null,history.slice(0,-1))">{{ t('library.previous') }}</button>
        <button type="button" class="library-quiet" :disabled="loading || busy || !cursor"
          @click="load(cursor,[...history,currentCursor])">{{ t('library.next') }}</button>
      </div>
    </section>

    <div v-if="uploadOpen" class="library-overlay" @click.self="closeUpload">
      <section class="library-dialog" role="dialog" aria-modal="true" aria-labelledby="upload-heading">
        <div class="dialog-header">
          <h2 id="upload-heading">{{ t('library.add') }}</h2>
          <button type="button" class="library-close" :aria-label="t('library.close')" :disabled="busy" @click="closeUpload">×</button>
        </div>
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
          <div class="library-field">
            <span>{{ t('library.files') }}</span>
            <label class="library-file-picker">
              <span class="library-file-icon" aria-hidden="true">↑</span>
              <span>{{ t('library.pickFiles') }}</span>
              <input ref="fileInput" type="file" multiple accept=".pdf,.gtp,.gp,.gp3,.gp4,.gp5,.gpx"
                :disabled="busy" :aria-label="t('library.pickFiles')" @change="pickFiles" />
            </label>
            <small class="library-muted">{{ t('library.limits') }}</small>
            <ul v-if="files.length" class="library-selected-files">
              <li v-for="(file,index) in files" :key="index">{{ file.name }} · {{ (file.size / 1024 / 1024).toFixed(2) }} MB</li>
            </ul>
          </div>
          <div v-if="busy" class="library-progress" role="status"><progress />{{ t('library.working') }}</div>
          <div class="dialog-footer">
            <button type="button" class="library-quiet" :disabled="busy" @click="closeUpload">{{ t('library.cancel') }}</button>
            <button type="submit" class="library-primary" :disabled="busy || !title.trim() || files.length === 0">
              {{ busy ? t('library.working') : t('library.upload') }}
            </button>
          </div>
        </form>
      </section>
    </div>

    <div v-if="editOpen && selected" class="library-overlay" @click.self="closeDialog">
      <section class="library-dialog" role="dialog" aria-modal="true" aria-labelledby="edit-heading">
        <div class="dialog-header">
          <h2 id="edit-heading">{{ t('library.edit') }}</h2>
          <button type="button" class="library-close" :disabled="busy" :aria-label="t('library.close')" @click="closeDialog">×</button>
        </div>
        <form @submit.prevent="saveCard">
          <label class="library-field">{{ t('library.name') }}<input v-model="editTitle" maxlength="150" required :disabled="busy" /></label>
          <label class="library-field">{{ t('library.type') }}<select v-model="editType" :disabled="busy">
            <option v-for="value in (['COMPOSITION','ETUDE','EXERCISE','OTHER'] as const)" :key="value" :value="value">{{ t('library.types.' + value) }}</option>
          </select></label>
          <label class="library-field">{{ t('library.author') }}<input v-model="editAuthor" maxlength="150" :disabled="busy" /></label>
          <div class="dialog-footer">
            <button type="button" class="library-quiet" :disabled="busy" @click="closeDialog">{{ t('library.cancel') }}</button>
            <button type="submit" class="library-primary" :disabled="busy">{{ t('library.save') }}</button>
          </div>
        </form>
      </section>
    </div>

    <div v-if="removing && selected" class="library-overlay" @click.self="closeDialog">
      <section class="library-dialog confirm-dialog" role="alertdialog" aria-modal="true" aria-labelledby="delete-heading" aria-describedby="delete-description">
        <div class="dialog-header">
          <h2 id="delete-heading">{{ t('library.delete') }}</h2>
          <button type="button" class="library-close" :disabled="busy" :aria-label="t('library.close')" @click="closeDialog">×</button>
        </div>
        <p><strong>{{ selected.title }}</strong></p>
        <p id="delete-description" class="library-muted">{{ t('library.confirmDelete') }}</p>
        <div class="dialog-footer">
          <button type="button" class="library-quiet" :disabled="busy" @click="closeDialog">{{ t('library.cancel') }}</button>
          <button type="button" class="library-danger" :disabled="busy" @click="removeCard">{{ t('library.confirm') }}</button>
        </div>
      </section>
    </div>
  </section>
</template>
<style scoped>
.library-page{max-width:1160px;margin:0 auto;padding:48px 24px 80px;color:var(--text-primary,#243345)}
.library-toolbar,.library-table-heading,.dialog-header,.dialog-footer,.library-pagination{display:flex;align-items:center;justify-content:space-between;gap:18px}
.library-toolbar{margin-bottom:28px;align-items:end}
.library-intro h1{font-size:clamp(2rem,3.5vw,2.8rem);margin:8px 0 8px;letter-spacing:-.035em}
.library-intro p{margin:0}.library-eyebrow{font-size:.74rem;letter-spacing:.14em;color:#347c70;font-weight:700}
.library-muted{font-size:.88rem;color:var(--text-secondary,#657487);line-height:1.6}
.library-primary,.library-quiet,.library-more,.library-close,.library-danger{font:inherit;cursor:pointer;border-radius:9px;padding:10px 17px;border:1px solid #b9d0ca;background:#fff;color:#285a54}
.library-primary{background:#28776d;color:#fff;border-color:#28776d;font-weight:600}
.library-danger{background:#a53741;border-color:#a53741;color:white}
.add-action{white-space:nowrap}.add-action span{font-size:1.35rem;margin-right:4px;vertical-align:-1px}
button:disabled{opacity:.5;cursor:not-allowed}
button:focus-visible,input:focus-visible,select:focus-visible{outline:3px solid #91cbbc;outline-offset:2px}
.library-panel{background:var(--surface,#fff);border:1px solid #dbe8e4;border-radius:18px;padding:24px;min-width:0}
.library-table-heading h2{font-size:1.18rem;margin:0 0 4px}.library-table-heading p{margin:0}
.library-table-scroll{overflow-x:auto;margin-top:22px}
.library-table{width:100%;border-collapse:collapse;text-align:left;font-size:.9rem;min-width:700px}
.library-table th{font-weight:600;color:#657487;font-size:.8rem;padding:14px 12px;border-bottom:1px solid #dce6e3;white-space:nowrap}
.library-table td{padding:17px 12px;border-bottom:1px solid #e9eeee;vertical-align:middle}
.library-table tbody tr:hover{background:#f6faf8}
.library-table tbody tr:last-child td{border-bottom:0}
.library-table td.name-cell{max-width:340px;overflow-wrap:anywhere}
.library-table .name-cell strong{font-weight:600;color:#223d3a}.date-cell{white-space:nowrap;color:#71807f}
.action-column{text-align:right}.action-cell{text-align:right;width:64px}
.row-actions{position:relative;display:inline-block}
.library-more{padding:4px 11px;font-size:1.4rem;line-height:1.1;min-width:42px;border:1px solid transparent}
.library-more:hover,.library-more[aria-expanded=true]{background:#edf6f2;border-color:#c7dcd3}
.library-dropdown{position:absolute;z-index:15;right:0;top:calc(100% + 4px);min-width:180px;max-width:280px;border:1px solid #d8e6e1;background:#fff;border-radius:12px;box-shadow:0 10px 30px #18382b24;padding:5px;text-align:left}
.library-dropdown button{background:none;border:0;width:100%;display:block;text-align:left;font:inherit;padding:11px 12px;border-radius:7px;cursor:pointer;color:#2e4945;overflow-wrap:anywhere}
.library-dropdown button:hover{background:#eef6f2}.library-dropdown button span{margin-right:10px}
.library-dropdown .danger-item{color:#a53741}.library-dropdown .danger-item:hover{background:#fff0ef}
.library-dropdown small{font-size:.73rem;line-height:1.2;display:block;margin-left:23px}
.library-pagination{justify-content:flex-end;border-top:1px solid #e9eeee;margin-top:14px;padding-top:16px}
.library-empty{padding:40px 14px;text-align:center;color:#647787}
.library-overlay{position:fixed;inset:0;z-index:1001;background:#142c2c8c;display:flex;justify-content:center;align-items:center;padding:20px}
.library-dialog{background:#fff;width:min(100%,490px);max-height:calc(100dvh - 40px);overflow-y:auto;padding:28px;border-radius:17px;box-shadow:0 20px 70px #0003}
.dialog-header{margin-bottom:14px}.dialog-header h2{margin:0;font-size:1.32rem}
.library-close{font-size:1.4rem;border:0;background:transparent;padding:5px 10px;color:#607576}
.library-field{display:flex;flex-direction:column;gap:7px;margin:14px 0;font-size:.88rem;font-weight:600}
.library-field input:not([type=file]),.library-field select{font:inherit;min-height:44px;box-sizing:border-box;padding:10px;border:1px solid #b8c9ca;border-radius:9px;background:white;color:inherit;width:100%}
.library-file-picker{position:relative;border:1px dashed #91b8ad;border-radius:10px;min-height:64px;display:flex;align-items:center;justify-content:center;gap:10px;color:#2b6b60;background:#f3faf7;cursor:pointer}
.library-file-picker:hover{background:#e8f4ef}.library-file-picker:focus-within{outline:3px solid #91cbbc;outline-offset:2px}
.library-file-picker input{position:absolute;inset:0;width:100%;height:100%;opacity:0;cursor:pointer}
.library-file-picker input:disabled{cursor:not-allowed}.library-file-icon{font-size:1.5rem}
.library-selected-files{font-weight:400;font-size:.8rem;padding-left:18px;overflow-wrap:anywhere}
.dialog-footer{justify-content:flex-end;margin-top:24px;flex-wrap:wrap}
.library-progress{display:flex;gap:12px;align-items:center}.library-progress progress{max-width:120px}
.library-error,.library-success{border-radius:9px;padding:12px 15px;margin-bottom:18px;background:#fff1f0;color:#85343b}
.library-success{background:#eef8f2;color:#1d684e}
@media(max-width:760px){.library-page{padding:26px 14px 48px}.library-toolbar{align-items:start;flex-wrap:wrap}.library-panel{padding:15px}.library-table-heading{align-items:start}.library-dialog{padding:20px}}
</style>
