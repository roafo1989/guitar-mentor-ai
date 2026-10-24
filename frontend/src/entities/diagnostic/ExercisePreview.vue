<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import type { Exercise } from '../../shared/model/diagnostic.model';
import { leaveMetronomeExercise, openExerciseMetronome } from '../metronome/metronome-ui';

const props = defineProps<{ exercise: Exercise; assigned?: boolean }>();
const { t } = useI18n();
const audioExists = ref(false);
const pdfExists = ref(false);
const checking = ref(true);
const pdfOpen = ref(false);
const pdfLoading = ref(false);
const pdfReady = ref(false);
let pdfOpenController: AbortController | null = null;
// A retry must take precedence over the initial PDF HEAD, even if that older request resolves later.
let pdfCheckGeneration = 0;
const exerciseKey = () => props.exercise.code + '@' + props.exercise.version;
const mediaIdentity = computed(() => JSON.stringify([
  exerciseKey(), props.exercise.previewAudioUrl, props.exercise.previewPdfUrl,
  props.exercise.scoreUrl,
]));
const goal = computed(() => props.exercise.learningGoal?.trim() || props.exercise.description?.trim() || '');
const criteria = computed(() => Array.isArray(props.exercise.successCriteria)
  ? props.exercise.successCriteria.filter((item): item is string => typeof item === 'string' && item.trim().length > 0)
  : []);

function launchMetronome(event: MouseEvent): void {
  openExerciseMetronome(event.currentTarget as HTMLElement, exerciseKey(), props.exercise.bpm);
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
  <section class="exercise-preview" :aria-label="t('diagnostic.previewHeading')">
    <div class="exercise-preview-heading">
      <h4>{{ assigned ? t('diagnostic.assignedMediaHeading') : t('diagnostic.previewHeading') }}</h4>
      <span class="hint">{{ assigned ? t('diagnostic.assignedMediaHint') : t('diagnostic.previewBeforeAssign') }}</span>
    </div>
    <section class="exercise-learning" :aria-label="t('diagnostic.metadata.heading')">
      <p class="exercise-version">{{ t('diagnostic.metadata.version') }}: {{ exercise.code }}@{{ exercise.version }}</p>
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
        <div><dt>{{ t('diagnostic.metadata.duration') }}</dt><dd>{{ exercise.recommendedPracticeMinutes != null ? t('diagnostic.practiceDuration', { minutes: exercise.recommendedPracticeMinutes }) : t('diagnostic.metadata.unknown') }}</dd></div>
        <div><dt>{{ t('diagnostic.metadata.tuning') }}</dt><dd>{{ exercise.tuning?.trim() || t('diagnostic.metadata.unknown') }}</dd></div>
        <div><dt>{{ t('diagnostic.metadata.tempo') }}</dt><dd>{{ exercise.bpm }} BPM · {{ exercise.meter }}</dd></div>
        <div><dt>{{ t('diagnostic.metadata.difficulty') }}</dt><dd>{{ exercise.difficulty || t('diagnostic.metadata.unknown') }}</dd></div>
        <div><dt>{{ t('diagnostic.metadata.author') }}</dt><dd>{{ exercise.author?.trim() || t('diagnostic.metadata.unknown') }}</dd></div>
        <div><dt>{{ t('diagnostic.metadata.license') }}</dt><dd>{{ exercise.license?.trim() || t('diagnostic.metadata.unknown') }}</dd></div>
      </dl>
    </section>
    <div class="metronome-exercise-tool">
      <button type="button" class="recorder-button metronome-exercise-launch"
        :title="t('metronome.openExercise')" @click="launchMetronome">
        {{ t('metronome.openExercise') }} · {{ exercise.bpm }} BPM
      </button>
      <span class="hint">{{ t('metronome.recommended') }}</span>
    </div>
    <div class="exercise-preview-audio">
      <strong>{{ t('diagnostic.listenExample') }}</strong>
      <p class="hint">{{ t('diagnostic.synthesizedExample') }}</p>
      <p v-if="checking" class="hint" role="status">{{ t('diagnostic.checkingMedia') }}</p>
      <audio v-else-if="audioExists && exercise.previewAudioUrl"
        :key="mediaIdentity" :src="exercise.previewAudioUrl" controls preload="none" class="audio-player"
        :aria-label="t('diagnostic.listenExample')" @error="audioExists = false" />
      <p v-else class="hint" role="status">{{ t('diagnostic.metadata.audioUnavailable') }}</p>
    </div>
    <details :key="mediaIdentity" class="exercise-pdf-details" :open="pdfOpen"
      @toggle="onPdfToggle">
      <summary>{{ t('diagnostic.viewNotes') }}</summary>
      <p v-if="checking" class="hint" role="status">{{ t('diagnostic.checkingMedia') }}</p>
      <p v-else-if="pdfLoading" class="hint" role="status">{{ t('diagnostic.checkingMedia') }}</p>
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
  </section>
</template>

<style scoped>
.exercise-learning { min-width: 0; margin: 1rem 0; overflow-wrap: anywhere; }
.exercise-learning h5 { margin: .85rem 0 .3rem; font-size: 1rem; }
.exercise-learning p { margin: .35rem 0; }
.exercise-learning ul { padding-left: 1.5rem; }
.exercise-version { font-weight: 600; }
.exercise-facts { display: grid; grid-template-columns: repeat(auto-fit, minmax(min(100%, 10rem), 1fr)); gap: .65rem 1rem; margin-top: 1rem; }
.exercise-facts div { min-width: 0; }
.exercise-facts dt { font-weight: 600; }
.exercise-facts dd { margin: .2rem 0 0; overflow-wrap: anywhere; }
</style>
