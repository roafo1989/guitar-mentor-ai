<script setup lang="ts">
import { computed, defineAsyncComponent, onBeforeUnmount, ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import type { Exercise } from '../../shared/model/diagnostic.model';
import { leaveMetronomeExercise, openExerciseMetronome } from '../metronome/metronome-ui';

const props = defineProps<{ exercise: Exercise; assigned?: boolean }>();
const { t } = useI18n();
const lightMinimal = import.meta.env?.VITE_GUITAR_MENTOR_LIGHT_MINIMAL === 'true';
const ScoreReader = defineAsyncComponent(() => import('../../shared/score/ScoreReader.vue'));
const audioExists = ref(false);
const pdfExists = ref(false);
const checking = ref(true);
const pdfOpen = ref(false);
const studioTab = ref<'score' | 'description'>('score');
function onStudioTabKeydown(event: KeyboardEvent): void {
  const tabs = ['score','description'] as const;
  const index = tabs.indexOf(studioTab.value);
  let next = index;
  if (event.key === 'ArrowRight') next = (index + 1) % tabs.length;
  else if (event.key === 'ArrowLeft') next = (index + tabs.length - 1) % tabs.length;
  else if (event.key === 'Home') next = 0;
  else if (event.key === 'End') next = tabs.length - 1;
  else return;
  event.preventDefault();
  studioTab.value = tabs[next]!;
  if (studioTab.value === 'score') pdfOpen.value = true;
  const buttons = (event.currentTarget as HTMLElement).querySelectorAll<HTMLButtonElement>('[role="tab"]');
  buttons[next]?.focus();
}
const pdfLoading = ref(false);
const pdfReady = ref(false);
const metronomeError = ref(false);
let pdfOpenController: AbortController | null = null;
// A retry must take precedence over the initial PDF HEAD, even if that older request resolves later.
let pdfCheckGeneration = 0;
const exerciseKey = () => props.exercise.code + '@' + props.exercise.version;
const mediaIdentity = computed(() => JSON.stringify([
  exerciseKey(), props.exercise.previewAudioUrl, props.exercise.previewPdfUrl,
  props.exercise.scoreUrl,
]));
const goal = computed(() => props.exercise.learningGoal?.trim() || props.exercise.description?.trim() || '');
const isProbe = computed(() => props.exercise.contentKind === 'DIAGNOSTIC_PROBE');
const criteria = computed(() => Array.isArray(props.exercise.successCriteria)
  ? props.exercise.successCriteria.filter((item): item is string => typeof item === 'string' && item.trim().length > 0)
  : []);

function launchMetronome(event: MouseEvent): void {
  metronomeError.value = !openExerciseMetronome(
    event.currentTarget as HTMLElement, exerciseKey(), props.exercise.bpm, props.exercise.meter, props.exercise.tempoUnit,
  );
}

watch(exerciseKey, (_newKey, previous) => leaveMetronomeExercise(previous));
onBeforeUnmount(() => leaveMetronomeExercise(exerciseKey()));

// Invalidate old HEAD requests on URL/version changes and on unmount.
// Vue cleans up each watcher run before the next one starts.
watch(mediaIdentity, (_identity, _previous, onCleanup) => {
  const controller = new AbortController();
  const initialPdfGeneration = ++pdfCheckGeneration;
  let active = true;
  audioExists.value = false;
  pdfExists.value = false;
  checking.value = true;
  pdfOpen.value = false;
  if (props.assigned && lightMinimal) pdfOpen.value = true;
  studioTab.value = 'score';
  pdfReady.value = false;
  pdfLoading.value = false;
  pdfOpenController?.abort();
  pdfOpenController = null;
  onCleanup(() => { active = false; controller.abort(); pdfOpenController?.abort(); });

  async function check(url: string | null): Promise<boolean> {
    if (!url) return false;
    try {
      const response = await fetch(url, { method: 'HEAD', cache: 'no-cache', signal: controller.signal });
      return response.ok;
    } catch {
      return false;
    }
  }
  void Promise.all([check(props.exercise.previewAudioUrl), check(props.exercise.previewPdfUrl)])
    .then(([audio, pdf]) => {
      if (!active) return;
      audioExists.value = audio;
      if (pdfCheckGeneration === initialPdfGeneration) pdfExists.value = pdf;
      checking.value = false;
    });
}, { immediate: true });

// The first HEAD is advisory. Check again when PDF is actually opened,
// since a file may have been removed between rendering the card and opening it.
// Browsers cannot reliably report iframe HTTP failures; always retain the
// explicit PDF link and MusicXML alternative for those later failures.
async function onPdfToggle(event: Event): Promise<void> {
  const opened = (event.target as HTMLDetailsElement).open;
  pdfOpen.value = opened;
  pdfOpenController?.abort();
  pdfOpenController = null;
  pdfReady.value = false;
  pdfLoading.value = false;
  if (!opened || !props.exercise.previewPdfUrl) return;
  ++pdfCheckGeneration;

  const url = props.exercise.previewPdfUrl;
  const identity = mediaIdentity.value;
  const controller = new AbortController();
  pdfOpenController = controller;
  pdfLoading.value = true;
  try {
    const response = await fetch(url, { method: 'HEAD', cache: 'no-cache', signal: controller.signal });
    if (!controller.signal.aborted && pdfOpen.value && mediaIdentity.value === identity) {
      pdfExists.value = response.ok;
      pdfReady.value = response.ok;
    }
  } catch {
    if (!controller.signal.aborted && pdfOpen.value && mediaIdentity.value === identity) {
      pdfExists.value = false;
    }
  } finally {
    if (pdfOpenController === controller) {
      pdfLoading.value = false;
      pdfOpenController = null;
    }
  }
}
onBeforeUnmount(() => pdfOpenController?.abort());
</script>

<template>
  <section class="exercise-preview" :class="{ 'studio-preview': assigned && lightMinimal }" :aria-label="t('diagnostic.previewHeading')">
    <div v-if="assigned && lightMinimal" class="studio-tabs" role="tablist" :aria-label="t('diagnostic.studio.tabsLabel')" @keydown="onStudioTabKeydown">
      <button type="button" role="tab" :aria-selected="studioTab === 'score'" :tabindex="studioTab === 'score' ? 0 : -1"
        @click="studioTab = 'score'; pdfOpen = true"><svg width="21" height="21" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true"><path d="M9 18V5l12-2v13"/><ellipse cx="6" cy="18" rx="3" ry="2"/><ellipse cx="18" cy="16" rx="3" ry="2"/></svg>{{ t('diagnostic.studio.scoreTab') }}</button>
      <button type="button" role="tab" :aria-selected="studioTab === 'description'" :tabindex="studioTab === 'description' ? 0 : -1"
        @click="studioTab = 'description'"><svg width="21" height="21" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" aria-hidden="true"><path d="M4 6h16M4 12h12M4 18h8"/></svg>{{ t('diagnostic.studio.descriptionTab') }}</button>
    </div>
    <div v-if="!assigned || !lightMinimal" class="exercise-preview-heading">
      <h4>{{ assigned ? t('diagnostic.assignedMediaHeading') : t('diagnostic.previewHeading') }}</h4>
      <span class="hint">{{ assigned ? t('diagnostic.assignedMediaHint') : t('diagnostic.previewBeforeAssign') }}</span>
    </div>
    <section class="exercise-learning" :class="{ 'studio-tab-hidden': assigned && lightMinimal && studioTab !== 'description' }" :aria-label="t('diagnostic.metadata.heading')">
      <h4 v-if="assigned && lightMinimal" class="studio-description-heading">{{ t('diagnostic.metadata.heading') }}</h4>
      <p class="exercise-version">{{ isProbe ? t('diagnostic.probeLabel') : t('diagnostic.repertoireLabel') }} · {{ t('diagnostic.metadata.version') }}: {{ exercise.code }}@{{ exercise.version }}</p>
      <h5>{{ t('diagnostic.metadata.goal') }}</h5>
      <p>{{ goal || t('diagnostic.metadata.unknown') }}</p>
      <h5>{{ t('diagnostic.metadata.instructions') }}</h5>
      <p>{{ exercise.practiceInstructions?.trim() || t('diagnostic.metadata.unknown') }}</p>
      <h5>{{ t('diagnostic.metadata.criteria') }}</h5>
      <ul v-if="criteria.length">
        <li v-for="(criterion, index) in criteria" :key="index">{{ criterion }}</li>
      </ul>
      <p v-else class="hint">{{ t('diagnostic.metadata.unknown') }}</p>
      <p class="hint">{{ t('diagnostic.metadata.criteriaHint') }}</p>
      <dl class="exercise-facts">
        <div v-if="isProbe && exercise.durationTargetSeconds"><dt>{{ t('diagnostic.probeDuration') }}</dt><dd>{{ exercise.durationTargetSeconds }} {{ t('diagnostic.seconds') }}</dd></div>
        <div><dt>{{ t('diagnostic.metadata.duration') }}</dt><dd>{{ exercise.recommendedPracticeMinutes != null ? t('diagnostic.practiceDuration', { minutes: exercise.recommendedPracticeMinutes }) : t('diagnostic.metadata.unknown') }}</dd></div>
        <div><dt>{{ t('diagnostic.metadata.tuning') }}</dt><dd>{{ exercise.tuning?.trim() || t('diagnostic.metadata.unknown') }}</dd></div>
        <div><dt>{{ t('diagnostic.metadata.tempo') }}</dt><dd>{{ exercise.bpm }} BPM · {{ exercise.meter }}</dd></div>
        <div><dt>{{ t('diagnostic.metadata.difficulty') }}</dt><dd>{{ exercise.difficulty || t('diagnostic.metadata.unknown') }}</dd></div>
        <div><dt>{{ t('diagnostic.metadata.author') }}</dt><dd>{{ exercise.author?.trim() || t('diagnostic.metadata.unknown') }}</dd></div>
        <div><dt>{{ t('diagnostic.metadata.license') }}</dt><dd>{{ exercise.license?.trim() || t('diagnostic.metadata.unknown') }}</dd></div>
      </dl>
    </section>
    <div v-if="!assigned || !lightMinimal" class="exercise-preview-audio">
      <strong>{{ t('diagnostic.listenExample') }}</strong>
      <p class="hint">{{ t('diagnostic.synthesizedExample') }}</p>
      <p v-if="checking" class="hint" role="status">{{ t('diagnostic.checkingMedia') }}</p>
      <audio v-else-if="audioExists && exercise.previewAudioUrl"
        :key="mediaIdentity" :src="exercise.previewAudioUrl" controls preload="none" class="audio-player"
        :aria-label="t('diagnostic.listenExample')" @error="audioExists = false" />
      <p v-else class="hint" role="status">{{ t('diagnostic.metadata.audioUnavailable') }}</p>
    </div>
    <ScoreReader v-if="assigned && lightMinimal" :class="{ 'studio-tab-hidden': studioTab !== 'score' }" :key="mediaIdentity"
      :exercise-key="exerciseKey()" :pdf-url="exercise.previewPdfUrl"
      :score-url="exercise.scoreUrl" :title="exercise.title"
      v-model:open="pdfOpen" />
    <details v-if="!assigned || !lightMinimal" :key="mediaIdentity" class="exercise-pdf-details" :open="pdfOpen"
      @toggle="onPdfToggle">
      <summary>{{ t('diagnostic.viewNotes') }}</summary>
      <!-- Explicit PDF verification must not wait for unrelated initial audio HEAD. -->
      <p v-if="pdfLoading || (checking && !pdfOpen)" class="hint" role="status">{{ t('diagnostic.checkingMedia') }}</p>
      <template v-else-if="pdfExists && exercise.previewPdfUrl">
        <div v-if="pdfOpen && pdfReady" class="score-frame-wrapper">
          <iframe :title="t('diagnostic.pdfTitle')" class="score-frame"
            :src="exercise.previewPdfUrl + '#view=FitH&toolbar=1'" @error="pdfExists = false" />
        </div>
        <p class="hint">{{ t('diagnostic.metadata.pdfViewerFallback') }}</p>
        <a :href="exercise.previewPdfUrl" target="_blank" rel="noopener noreferrer">
          {{ t('diagnostic.pdfNewTab') }}
        </a>
      </template>
      <p v-else class="hint" role="status">{{ t('diagnostic.metadata.pdfUnavailable') }}</p>
      <p v-if="exercise.scoreUrl" class="hint">{{ t('diagnostic.musicXmlAlternative') }}
        <a :href="exercise.scoreUrl" download>{{ t('diagnostic.downloadScore') }}</a>
      </p>
    </details>
    <div v-if="!assigned || !lightMinimal" class="metronome-exercise-tool">
      <button type="button" class="recorder-button metronome-exercise-launch"
        :title="t('metronome.openExercise')" @click="launchMetronome">
        {{ t('metronome.openExercise') }} · {{ exercise.bpm }} BPM · {{ exercise.meter }}
      </button>
      <span class="hint">{{ t('metronome.recommended') }}</span>
      <p v-if="metronomeError" role="alert" class="hint">{{ t('metronome.exerciseMeterUnavailable') }}</p>
    </div>
  </section>
</template>

<style scoped>
.exercise-learning { min-width: 0; margin: 1rem 0; overflow-wrap: anywhere; }
details.exercise-learning {border:1px solid #dce5e4;border-radius:10px;padding:8px 14px;background:#f8fbfa}
details.exercise-learning > summary {min-height:44px;display:flex;align-items:center;font-weight:650;cursor:pointer;color:#205f57}
details.exercise-learning > summary:focus-visible {outline:3px solid #28776d;outline-offset:3px}
.exercise-learning h5 { margin: .85rem 0 .3rem; font-size: 1rem; }
.exercise-learning p { margin: .35rem 0; }
.exercise-learning ul { padding-left: 1.5rem; }
.exercise-version { font-weight: 600; }
.exercise-facts { display: grid; grid-template-columns: repeat(auto-fit, minmax(min(100%, 10rem), 1fr)); gap: .65rem 1rem; margin-top: 1rem; }
.exercise-facts div { min-width: 0; }
.exercise-facts dt { font-weight: 600; }
.exercise-facts dd { margin: .2rem 0 0; overflow-wrap: anywhere; }

.studio-preview {min-width:0;display:grid;grid-template-columns:max-content minmax(0,1fr);column-gap:12px;row-gap:10px;align-items:start}
.studio-preview .studio-tab-hidden {display:none!important}
.studio-tabs {display:flex;grid-column:1;grid-row:1;gap:4px;align-items:center;flex-wrap:nowrap;padding:4px;background:#f4f7f5;border-radius:12px;margin:0;min-width:0}
.studio-tabs button {display:inline-flex;align-items:center;justify-content:center;gap:8px;border:0;border-radius:9px;min-height:44px;padding:10px 17px;background:transparent;color:#4b5d5b;font:inherit;font-size:13px;font-weight:600;cursor:pointer}
.studio-tabs button[aria-selected="true"] {background:#fff;color:#205f57;box-shadow:0 1px 4px #16233116}
.studio-tabs button:focus-visible {outline:3px solid #28776d;outline-offset:2px}
.studio-preview :deep(.score-reader) {display:contents;border:0;padding:0}
.studio-preview :deep(.score-toolbar) {grid-column:2;grid-row:1;gap:6px;justify-content:flex-end;padding:0;min-height:52px;align-content:center;flex-wrap:nowrap;min-width:0}
.studio-preview :deep(.score-reader > div:not(.score-toolbar)) {grid-column:1 / -1;min-width:0}
.studio-preview > :not(.studio-tabs):not(.score-reader) {grid-column:1 / -1}
.studio-preview .metronome-exercise-tool {grid-column:1 / -1;margin-top:3px;display:flex;align-items:center;gap:10px;flex-wrap:wrap}
.studio-preview :deep(.score-toolbar button) {border-color:#dce5e4;background:#fff}
.studio-preview :deep(.score-canvas-frame) {height:min(78vh,1000px)}
.studio-preview .exercise-preview-audio {border:1px solid #e1e9e4;border-radius:12px;background:#f8fbfa;padding:18px}
.studio-preview .exercise-learning {border:1px solid #e1e9e4;border-radius:12px;padding:16px}
.studio-description-heading {margin:0 0 14px;color:#205f57;font-size:15px;font-weight:700}
@media(max-width:780px) {
 .studio-preview {grid-template-columns:minmax(0,1fr)}
 .studio-tabs {grid-column:1;grid-row:1;width:max-content;max-width:100%;flex-wrap:wrap}
 .studio-preview :deep(.score-toolbar) {grid-column:1;grid-row:2;justify-content:flex-end;flex-wrap:wrap}
 .studio-preview :deep(.score-reader > div:not(.score-toolbar)) {grid-column:1}
}
@media(max-width:600px) {.studio-tabs button {padding:10px 12px}.studio-preview :deep(.score-canvas-frame) {height:65vh}.studio-preview :deep(.score-toolbar) {justify-content:flex-start}}
</style>
