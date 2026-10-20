<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { useRoute } from 'vue-router';
import { useI18n } from 'vue-i18n';
import MetronomeDialog from './entities/metronome/MetronomeDialog.vue';
import { closeMetronome, metronomeUi, minimizeMetronome, openMetronome } from './entities/metronome/metronome-ui';
import { metronome, type MetronomeStatus } from './shared/audio/metronome';
import { useLearnerStore } from './stores/learner.store';

const { locale, t } = useI18n();
// All routed pages restore the selected learner on mount; this header reacts to Pinia changes.
const learnerStore = useLearnerStore();
const route = useRoute();
const headerMetronomeButton = ref<HTMLButtonElement | null>(null);
const metronomeStatus = ref<MetronomeStatus>(metronome.getState().status);
let unsubscribeMetronome: (() => void) | null = null;
onMounted(() => {
  unsubscribeMetronome = metronome.subscribe(state => { metronomeStatus.value = state.status; });
});

function openHeaderMetronome(event: MouseEvent): void {
  openMetronome(event.currentTarget as HTMLElement);
}

watch(() => route.fullPath, () => {
  // Route changes never carry playback into a different form or exercise.
  metronomeUi.sourceExercise = null;
  closeMetronome();
});

watch(() => metronomeUi.open, (open, old) => {
  if (open || !old) return;
  const previous = metronomeUi.returnFocus;
  void nextTick(() => {
    if (previous?.isConnected) previous.focus();
    else headerMetronomeButton.value?.focus();
    metronomeUi.returnFocus = null;
  });
});
onBeforeUnmount(() => { unsubscribeMetronome?.(); closeMetronome(); });
</script>

<template>
  <div class="app-shell" :inert="metronomeUi.open">
    <header class="site-header">
      <RouterLink to="/" class="brand" :aria-label="t('app.home') + ' · Guitar Mentor AI'">
        <img class="brand-icon" src="/favicon.png" alt="" aria-hidden="true" width="40" height="40" />
        <div>
          <strong>Guitar Mentor AI</strong>
          <small>{{ t('app.subtitle') }}</small>
        </div>
      </RouterLink>
      <div class="header-actions">
        <nav aria-label="Navigation">
          <RouterLink to="/profile">{{ t('app.profile') }}</RouterLink>
          <RouterLink to="/onboarding">{{ t('app.onboarding') }}</RouterLink>
          <RouterLink to="/diagnostics">{{ t('app.diagnostics') }}</RouterLink>
        </nav>
        <RouterLink
          v-if="learnerStore.learner"
          to="/profile"
          class="header-learner"
          :title="t('app.currentLearner') + ': ' + learnerStore.learner.displayName"
        >
          <span>{{ t('app.currentLearner') }}:</span>
          <strong class="header-learner-name">{{ learnerStore.learner.displayName }}</strong>
        </RouterLink>
        <button ref="headerMetronomeButton" type="button" class="header-tool-button" :class="{ 'is-playing': metronomeStatus === 'running' }"
          :title="metronomeStatus === 'running' ? t('metronome.playingInBackground') : t('metronome.open')"
          :aria-label="metronomeStatus === 'running' ? t('metronome.playingInBackground') : t('metronome.open')"
          aria-haspopup="dialog" :aria-expanded="metronomeUi.open" @click="openHeaderMetronome">
          <svg viewBox="0 0 24 24" width="22" height="22" fill="none"
            stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"
            aria-hidden="true">
            <path d="M8 20h9M9 18 12 4l5 14M13 5l5-2M9 18h8M12 4l-1-2"/>
          </svg>
          <span v-if="metronomeStatus === 'running'" class="metronome-running-dot" aria-hidden="true"></span>
        </button>
        <label class="language-picker">
          <span class="sr-only">{{ t('app.language') }}</span>
          <select v-model="locale" aria-label="Language">
            <option value="ru">RU</option>
            <option value="en">EN</option>
          </select>
        </label>
      </div>
    </header>
    <MetronomeDialog v-if="metronomeUi.open" @close="closeMetronome" @minimize="minimizeMetronome" />
    <main class="main-content"><RouterView /></main>
    <footer>Guitar Mentor AI · MVP</footer>
  </div>
</template>
