<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from "vue";
import { useI18n } from "vue-i18n";
import { auth } from "../../core/auth/auth";
import type { LibraryFile, LibraryMaterial } from "./library.service";
import { ConversionFailure, conversionService, type ConversionJob } from "./conversion.service";
import { libraryError } from "./library-errors";

const props = defineProps<{ material: LibraryMaterial }>();
const emit = defineEmits<{ updated: [material: LibraryMaterial] }>();
const { t, te } = useI18n();
const job = ref<ConversionJob | null>(null);
const selectedTrack = ref<number | null>(null);
const busy = ref(false);
const error = ref("");
const notice = ref("");
let active = true;
let generation = 0;
let timer: ReturnType<typeof setTimeout> | null = null;
let controller: AbortController | null = null;
let pendingKey = "";
let pendingFingerprint = "";
const supported = new Set(["GP3", "GP4", "GP5"]);
const sources = computed(() => props.material.files.filter(file =>
  file.role === "SCORE_ORIGINAL" && file.processingState === "READY" && supported.has(file.format)));
const terminal = computed(() => !!job.value && ["SUCCEEDED","UNSUPPORTED","FAILED","TIMED_OUT","CANCELLED"].includes(job.value.state));
const canCancel = computed(() => !!job.value && ["QUEUED","RUNNING","AWAITING_TRACK_SELECTION"].includes(job.value.state));
const canRetry = computed(() => !!job.value && ["FAILED","TIMED_OUT"].includes(job.value.state) && job.value.retryable);
const storageKey = () => "gm.conversion." + (auth.actor?.accountId ?? "anonymous") + "." + props.material.id;
function readStored(): string {
  try { return sessionStorage.getItem(storageKey()) ?? ""; } catch { return ""; }
}
function storeJob(id: string) {
  try { sessionStorage.setItem(storageKey(), id); } catch { /* The current view still works without session storage. */ }
}
function clearTimer() {
  if (timer !== null) clearTimeout(timer);
  timer = null;
}
function abortRequest() {
  controller?.abort();
  controller = null;
}
function reset() {
  generation++;
  clearTimer();
  abortRequest();
  job.value = null;
  selectedTrack.value = null;
  busy.value = false;
  error.value = "";
  notice.value = "";
  pendingKey = "";
  pendingFingerprint = "";
}
function requestController(): AbortController {
  abortRequest();
  controller = new AbortController();
  return controller;
}
function errorMessage(cause: unknown): string {
  if (cause instanceof ConversionFailure) {
    if (cause.status === 503) return t("conversion.unavailable");
    if (cause.status === 412 || cause.status === 428) return t("conversion.stale");
    if (cause.status === 409) return t("conversion.conflict");
    if (cause.status === 404) return t("conversion.notFound");
    if (cause.status === 413 || cause.status === 415) return t("conversion.unsupported");
    if (cause.status === null) return t("conversion.network");
    if (cause.code && te("conversion.errors." + cause.code)) return t("conversion.errors." + cause.code);
  }
  return libraryError(cause, t);
}
function isCurrent(ticket: number, owner: string | undefined): boolean {
  return active && ticket === generation && auth.actor?.accountId === owner;
}
function schedulePoll(ticket: number, owner: string | undefined) {
  clearTimer();
  if (!job.value || !["QUEUED","RUNNING","CANCEL_REQUESTED"].includes(job.value.state)) return;
  timer = setTimeout(() => { void poll(ticket, owner); }, 1800);
}
async function applyJob(next: ConversionJob, ticket: number, owner: string | undefined) {
  if (!isCurrent(ticket, owner)) return;
  if (next.materialId !== props.material.id) {
    error.value = t("conversion.invalidResponse");
    return;
  }
  job.value = next;
  if (next.state === "SUCCEEDED") {
    clearTimer();
    try {
      const fresh = await conversionService.refreshMaterial(props.material.id, controller?.signal);
      if (!isCurrent(ticket, owner)) return;
      const pdf = fresh.files.find(f => f.id === next.pdfAssetId && f.role === "DERIVED_SCORE_PDF");
      const audio = fresh.files.find(f => f.id === next.audioAssetId && f.role === "SYNTHESIZED_PREVIEW");
      if (!pdf || !audio) {
        error.value = t("conversion.invalidResult");
        return;
      }
      emit("updated", fresh);
      notice.value = t("conversion.completed");
    } catch (cause) {
      if (isCurrent(ticket, owner) && !(cause instanceof Error && cause.name === "AbortError"))
        error.value = t("conversion.refreshFailed");
    }
  } else {
    schedulePoll(ticket, owner);
  }
}
async function poll(ticket = generation, owner = auth.actor?.accountId) {
  const current = job.value;
  if (!current || !isCurrent(ticket, owner)) return;
  const request = requestController();
  try {
    const next = await conversionService.get(props.material.id, current.id, request.signal);
    if (!isCurrent(ticket, owner)) return;
    await applyJob(next, ticket, owner);
  } catch (cause) {
    if (isCurrent(ticket, owner) && !request.signal.aborted) {
      error.value = errorMessage(cause);
      // Do not hammer an endpoint explicitly disabled by the server.
      if (!(cause instanceof ConversionFailure && cause.status === 503))
        timer = setTimeout(() => { void poll(ticket, owner); }, 4000);
    }
  }
}
async function restore() {
  const owner = auth.actor?.accountId;
  if (!owner) return;
  const id = readStored();
  if (!id) return;
  const ticket = generation;
  const request = requestController();
  busy.value = true;
  try {
    const existing = await conversionService.get(props.material.id, id, request.signal);
    if (isCurrent(ticket, owner)) await applyJob(existing, ticket, owner);
  } catch (cause) {
    if (isCurrent(ticket, owner) && !request.signal.aborted) {
      if (cause instanceof ConversionFailure && cause.status === 404) {
        try { sessionStorage.removeItem(storageKey()); } catch { /* Ignore unavailable storage. */ }
      } else error.value = errorMessage(cause);
    }
  } finally {
    if (isCurrent(ticket, owner)) busy.value = false;
  }
}
watch(() => [props.material.id, auth.actor?.accountId], () => {
  reset();
  if (auth.actor) void restore();
}, { immediate: true, flush: "sync" });
onBeforeUnmount(() => { active = false; reset(); });

async function start(source: LibraryFile) {
  if (busy.value || !sources.value.some(file => file.id === source.id)) return;
  const owner = auth.actor?.accountId;
  if (!owner) return;
  const ticket = generation;
  const fingerprint = props.material.id + ":" + props.material.revision + ":" + source.id;
  if (!pendingKey || pendingFingerprint !== fingerprint) {
    pendingKey = crypto.randomUUID();
    pendingFingerprint = fingerprint;
  }
  busy.value = true;
  error.value = "";
  notice.value = "";
  const request = requestController();
  try {
    const created = await conversionService.create(props.material, source.id, pendingKey, request.signal);
    if (!isCurrent(ticket, owner)) return;
    storeJob(created.id);
    pendingKey = "";
    pendingFingerprint = "";
    selectedTrack.value = null;
    await applyJob(created, ticket, owner);
  } catch (cause) {
    if (isCurrent(ticket, owner) && !request.signal.aborted) error.value = errorMessage(cause);
  } finally {
    if (isCurrent(ticket, owner)) busy.value = false;
  }
}
async function mutate(action: () => Promise<ConversionJob>) {
  const current = job.value, owner = auth.actor?.accountId;
  if (!current || busy.value || !owner) return;
  const ticket = generation;
  busy.value = true;
  error.value = "";
  const request = requestController();
  try {
    const next = await action();
    if (isCurrent(ticket, owner)) await applyJob(next, ticket, owner);
  } catch (cause) {
    if (isCurrent(ticket, owner) && !request.signal.aborted) error.value = errorMessage(cause);
  } finally {
    if (isCurrent(ticket, owner)) busy.value = false;
  }
}
function chooseTrack() {
  if (selectedTrack.value === null) {
    error.value = t("conversion.chooseTrack");
    return;
  }
  void mutate(() => conversionService.selectTrack(job.value!, selectedTrack.value!, controller?.signal));
}
function cancel() {
  void mutate(() => conversionService.cancel(job.value!, controller?.signal));
}
function retry() {
  void mutate(() => conversionService.retry(job.value!, controller?.signal));
}
function stateLabel(state: string): string {
  return te("conversion.states." + state) ? t("conversion.states." + state) : t("conversion.states.UNKNOWN");
}
function stageLabel(stage: string | null): string {
  if (!stage) return "";
  return te("conversion.stages." + stage) ? t("conversion.stages." + stage) : t("conversion.stages.UNKNOWN");
}
</script>

<template>
  <section v-if="sources.length || job" class="conversion-panel" :aria-label="t('conversion.title')">
    <h3>{{ t("conversion.title") }}</h3>
    <p class="conversion-help">{{ t("conversion.scope") }}</p>
    <div v-if="sources.length && (!job || terminal)" class="conversion-actions">
      <button v-for="source in sources" :key="source.id" type="button"
        :disabled="busy" @click="start(source)">
        {{ t("conversion.create") }} · {{ source.originalName }}
      </button>
    </div>
    <article v-if="job" class="conversion-job" aria-live="polite">
      <div class="conversion-heading">
        <strong>{{ stateLabel(job.state) }}</strong>
        <span v-if="job.attemptCount">{{ t("conversion.attempt", { count: job.attemptCount }) }}</span>
      </div>
      <p v-if="job.stage" class="conversion-stage">{{ stageLabel(job.stage) }}</p>
      <div v-if="['QUEUED','RUNNING','CANCEL_REQUESTED'].includes(job.state)" class="conversion-progress">
        <progress :value="job.progress" max="100" :aria-label="t('conversion.progress')" />
        <span>{{ job.progress }}%</span>
      </div>
      <p v-if="job.state === 'AWAITING_TRACK_SELECTION'">{{ t("conversion.selectTrack") }}</p>
      <fieldset v-if="job.state === 'AWAITING_TRACK_SELECTION'" :disabled="busy">
        <legend>{{ t("conversion.guitarTracks") }}</legend>
        <label v-for="track in job.tracks.filter(item => item.guitar)" :key="track.trackIndex">
          <input v-model="selectedTrack" type="radio" :value="track.trackIndex" />
          {{ track.name || t("conversion.trackNumber", { number: track.trackIndex + 1 }) }}
          <small v-if="track.instrument">{{ track.instrument }}</small>
        </label>
        <button type="button" :disabled="busy || selectedTrack === null" @click="chooseTrack">
          {{ t("conversion.confirmTrack") }}
        </button>
      </fieldset>
      <p v-if="job.errorCode" class="conversion-error" role="alert">
        {{ te("conversion.errors." + job.errorCode) ? t("conversion.errors." + job.errorCode) : t("conversion.failed") }}
      </p>
      <p v-if="error" class="conversion-error" role="alert">{{ error }}</p>
      <p v-if="notice" class="conversion-notice" role="status">{{ notice }}</p>
      <div v-if="job.state === 'SUCCEEDED'" class="conversion-result">
        <strong>{{ t("conversion.ready") }}</strong>
        <p>{{ t("conversion.resultHint") }}</p>
        <p>{{ t("conversion.downloadHint") }}</p>
      </div>
      <p v-if="job.state === 'UNSUPPORTED'" class="conversion-error">{{ t("conversion.unsupported") }}</p>
      <div class="conversion-actions">
        <button v-if="canCancel" type="button" :disabled="busy" @click="cancel">{{ t("conversion.cancel") }}</button>
        <button v-if="canRetry" type="button" :disabled="busy" @click="retry">{{ t("conversion.retry") }}</button>
        <button type="button" :disabled="busy" @click="poll()">{{ t("conversion.refresh") }}</button>
      </div>
    </article>
    <p v-if="error && !job" class="conversion-error" role="alert">{{ error }}</p>
    <p v-if="busy" class="conversion-working" role="status"><progress /> {{ t("conversion.working") }}</p>
  </section>
</template>

<style scoped>
.conversion-panel{border:1px solid #dbe8e4;border-radius:12px;padding:18px;margin:18px 0;background:#fbfdfc;min-width:0}
h3{margin:0 0 8px;font-size:1.05rem}.conversion-help,.conversion-stage{color:#657487;font-size:.92rem}
.conversion-actions{display:flex;flex-wrap:wrap;gap:8px;margin-top:12px}
button{min-height:42px;border:1px solid #adc8be;border-radius:8px;padding:8px 14px;background:white;color:#285a54;font:inherit;cursor:pointer}
button:disabled{opacity:.55;cursor:not-allowed}
button:focus-visible,input:focus-visible{outline:3px solid #28776d;outline-offset:2px}
.conversion-job{border-top:1px solid #dbe8e4;margin-top:14px;padding-top:14px}
.conversion-heading,.conversion-progress{display:flex;justify-content:space-between;gap:12px;align-items:center}
.conversion-progress{margin:10px 0}.conversion-progress progress{flex:1;min-width:0}
fieldset{border:1px solid #dbe8e4;border-radius:8px;padding:12px;display:grid;gap:10px}
fieldset label{display:flex;align-items:center;gap:8px;min-height:36px}
fieldset small{color:#657487;margin-left:auto}
.conversion-error{color:#a23b3b}.conversion-notice{color:#28776d}.conversion-result{border-top:1px solid #dbe8e4;margin-top:14px;padding-top:12px}
.conversion-result button{margin:6px 8px 0 0}.conversion-working{display:flex;align-items:center;gap:8px}
</style>
