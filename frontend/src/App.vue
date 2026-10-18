<script setup lang="ts">
import { useI18n } from 'vue-i18n';

const { locale, t } = useI18n();
</script>

<template>
  <div class="app-shell">
    <header class="site-header">
      <div class="brand">
        <span aria-hidden="true" class="brand-icon">♫</span>
        <div>
          <strong>Guitar Mentor AI</strong>
          <small>{{ t('app.subtitle') }}</small>
        </div>
      </div>
      <div class="header-actions">
        <nav aria-label="Navigation">
          <RouterLink to="/profile">{{ t('app.profile') }}</RouterLink>
          <RouterLink to="/onboarding">{{ t('app.onboarding') }}</RouterLink>
          <RouterLink to="/diagnostics">{{ t('app.diagnostics') }}</RouterLink>
        </nav>
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
