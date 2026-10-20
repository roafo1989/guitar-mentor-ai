<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import type { Exercise } from '../../shared/model/diagnostic.model';
import { leaveMetronomeExercise, openExerciseMetronome } from '../metronome/metronome-ui';

const props = defineProps<{ exercise: Exercise; assigned?: boolean }>();
const { t } = useI18n();
const audioExists = ref(false);
const pdfExists = ref(false);
const checking = ref(true);
const exerciseKey = () => props.exercise.code + '@' + props.exercise.version;

function launchMetronome(event: MouseEvent): void {
  openExerciseMetronome(event.currentTarget as HTMLElement, exerciseKey(), props.exercise.bpm);
}
watch(exerciseKey, (_newKey, previous) => leaveMetronomeExercise(previous));
onBeforeUnmount(() => leaveMetronomeExercise(exerciseKey()));

onMounted(async () => {
  const check = async (url: string | null) => {
    if (!url) return false;
    try {
      const response = await fetch(url, { method: 'HEAD', cache: 'no-cache' });
      return response.ok;
    } catch {
      return false;
    }
  };
  [audioExists.value, pdfExists.value] = await Promise.all([
    check(props.exercise.previewAudioUrl), check(props.exercise.previewPdfUrl),
  ]);
  checking.value = false;
});
</script>

<template>
  <section class="exercise-preview" :aria-label="t('diagnostic.previewHeading')">
    <div class="exercise-preview-heading">
      <h4>{{ assigned ? t('diagnostic.assignedMediaHeading') : t('diagnostic.previewHeading') }}</h4>
      <span class="hint">{{ assigned ? t('diagnostic.assignedMediaHint') : t('diagnostic.previewBeforeAssign') }}</span>
    </div>
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
      <p v-if="checking" class="hint">{{ t('diagnostic.checkingMedia') }}</p>
      <audio v-else-if="audioExists && exercise.previewAudioUrl"
        :src="exercise.previewAudioUrl" controls preload="none" class="audio-player"
        :aria-label="t('diagnostic.listenExample')" />
      <p v-else class="hint">{{ t('diagnostic.mediaNotInstalled') }}</p>
    </div>
    <details class="exercise-pdf-details">
      <summary>{{ t('diagnostic.viewNotes') }}</summary>
      <p v-if="checking" class="hint">{{ t('diagnostic.checkingMedia') }}</p>
      <template v-else-if="pdfExists && exercise.previewPdfUrl">
        <div class="score-frame-wrapper">
          <iframe :title="t('diagnostic.pdfTitle')" class="score-frame"
            :src="exercise.previewPdfUrl + '#view=FitH&toolbar=1'" />
        </div>
        <a :href="exercise.previewPdfUrl" target="_blank" rel="noopener noreferrer">
          {{ t('diagnostic.pdfNewTab') }}
        </a>
      </template>
      <p v-else class="hint">{{ t('diagnostic.mediaNotInstalled') }}</p>
      <p class="hint">{{ t('diagnostic.musicXmlAlternative') }}
        <a :href="exercise.scoreUrl" download>{{ t('diagnostic.downloadScore') }}</a>
      </p>
    </details>
  </section>
</template>
