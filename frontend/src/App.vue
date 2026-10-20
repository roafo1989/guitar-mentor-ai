<script setup lang="ts">
import { nextTick, ref, watch } from 'vue';
import { useRoute } from 'vue-router';
import { useI18n } from 'vue-i18n';
import TunerDialog from './shared/tuner/TunerDialog.vue';
import { useLearnerStore } from './stores/learner.store';

const { locale, t } = useI18n();
// All routed pages restore the selected learner on mount; this header reacts to Pinia changes.
const learnerStore = useLearnerStore();
const route = useRoute();
const tunerOpen = ref(false);
const tunerButton = ref<HTMLButtonElement | null>(null);
function openTuner(): void { tunerOpen.value = true; }
async function closeTuner(): Promise<void> {
  tunerOpen.value = false;
  await nextTick();
  tunerButton.value?.focus();
}
watch(() => route.fullPath, () => { if (tunerOpen.value) void closeTuner(); });
</script>

<template>
  <div class="app-shell">
    <header class="site-header">
      <RouterLink to="/" class="brand" :aria-label="t('app.home') + ' · Guitar Mentor AI'">
        <img class="brand-icon" src="/favicon.png" alt="" aria-hidden="true" width="40" height="40" />
        <div>
          <strong>Guitar Mentor AI</strong>
          <small>{{ t('app.subtitle') }}</small>
        </div>
      </RouterLink>
      <div class="header-actions">
        <button ref="tunerButton" class="header-tool-button" type="button"
          :title="t('tuner.title')" :aria-label="t('tuner.open')"
          aria-haspopup="dialog" :aria-expanded="tunerOpen" @click="openTuner">
          <svg viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor"
            stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
            <path d="M8 3v5a4 4 0 0 0 8 0V3M8 3 5 6m11-3 3 3M12 12v9m-3 0h6"/>
          </svg>
        </button>
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
        <label class="language-picker">
          <span class="sr-only">{{ t('app.language') }}</span>
          <select v-model="locale" aria-label="Language">
            <option value="ru">RU</option>
            <option value="en">EN</option>
          </select>
        </label>
      </div>
    </header>
    <main class="main-content"><RouterView /></main>
    <TunerDialog :open="tunerOpen" @close="closeTuner" />
    <footer>Guitar Mentor AI · MVP</footer>
  </div>
</template>
