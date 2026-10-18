<script setup lang="ts">
import { useI18n } from 'vue-i18n';
import { useLearnerStore } from './stores/learner.store';

const { locale, t } = useI18n();
// All routed pages restore the selected learner on mount; this header reacts to Pinia changes.
const learnerStore = useLearnerStore();
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
    <footer>Guitar Mentor AI · MVP</footer>
  </div>
</template>
