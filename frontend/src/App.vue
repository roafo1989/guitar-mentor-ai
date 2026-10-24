<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { useRoute } from 'vue-router';
import { useI18n } from 'vue-i18n';
import MetronomeDialog from './entities/metronome/MetronomeDialog.vue';
import AuthGate from './core/auth/AuthGate.vue';
import { auth, logout, secureUi } from './core/auth/auth';
import TunerDialog from './shared/tuner/TunerDialog.vue';
import { closeMetronome, metronomeUi, minimizeMetronome, openMetronome, stopMetronomeForAudioCapture } from './entities/metronome/metronome-ui';
import { metronome, type MetronomeStatus } from './shared/audio/metronome';
import { useLearnerStore } from './stores/learner.store';

const { locale, t } = useI18n();
const localPrototype = !secureUi;
const lightMinimal = import.meta.env?.VITE_GUITAR_MENTOR_LIGHT_MINIMAL === 'true';
const menuOpen = ref(false);
const menuButton = ref<HTMLButtonElement | null>(null);
const menuId = 'gm-header-menu';
function closeMenu(restoreFocus = false): void {
  if (!menuOpen.value) return;
  menuOpen.value = false;
  if (restoreFocus) void nextTick(() => menuButton.value?.focus());
}
function onHeaderKeydown(event: KeyboardEvent): void {
  if (event.key === 'Escape' && menuOpen.value) { event.preventDefault(); closeMenu(true); }
}

// All routed pages restore the selected learner on mount; this header reacts to Pinia changes.
const learnerStore = useLearnerStore();
const route = useRoute();
const headerMetronomeButton = ref<HTMLButtonElement | null>(null);
const tunerButton = ref<HTMLButtonElement | null>(null);
const tunerOpen = ref(false);
const logoutPending = ref(false);
const logoutError = ref(false);
async function signOut(): Promise<void> {
  if (logoutPending.value) return;
  logoutPending.value = true;
  logoutError.value = false;
  try {
    await logout(() => learnerStore.clearLocalSelection());
  } catch {
    logoutError.value = true;
  } finally {
    logoutPending.value = false;
  }
}
function openTuner(): void {
  // Stop even a minimized metronome to keep clicks out of pitch detection.
  stopMetronomeForAudioCapture();
  tunerOpen.value = true;
}
async function closeTuner(): Promise<void> {
  tunerOpen.value = false;
  await nextTick();
  tunerButton.value?.focus();
}
const metronomeStatus = ref<MetronomeStatus>(metronome.getState().status);
let unsubscribeMetronome: (() => void) | null = null;
onMounted(() => {
  unsubscribeMetronome = metronome.subscribe(state => { metronomeStatus.value = state.status; });
});

function openHeaderMetronome(event: MouseEvent): void {
  openMetronome(event.currentTarget as HTMLElement);
}

watch(() => auth.actor?.accountId, (current, previous) => {
  if (!secureUi || current === previous) return;
  // Mic/tuner/metronome state may outlive routed components otherwise.
  closeMetronome();
  tunerOpen.value = false;
});

watch(() => route.fullPath, () => {
  closeMenu();
  // Route changes never carry audio tools into a different form or exercise.
  metronomeUi.sourceExercise = null;
  closeMetronome();
  if (tunerOpen.value) void closeTuner();
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
  <div class="app-shell" :data-ui="lightMinimal ? 'light-minimal' : undefined" :inert="metronomeUi.open || tunerOpen">
    <header class="site-header" @keydown="onHeaderKeydown">
      <RouterLink to="/" class="brand" :aria-label="t('app.home') + ' · Guitar Mentor AI'">
        <img class="brand-icon" src="/favicon.png" alt="" aria-hidden="true" width="40" height="40" />
        <div>
          <strong>Guitar Mentor AI</strong>
          <small>{{ t('app.subtitle') }}</small>
        </div>
      </RouterLink>
      <div class="header-actions">
        <button v-if="lightMinimal && (auth.actor || localPrototype)" ref="menuButton" class="header-menu-button" type="button" :aria-expanded="menuOpen" :aria-controls="menuId" :aria-label="locale === 'ru' ? 'Меню навигации' : 'Navigation menu'" @click="menuOpen = !menuOpen">
          <span aria-hidden="true">☰</span>
        </button>
        <nav v-if="auth.actor || localPrototype" :id="menuId" class="header-navigation" :class="{ 'menu-open': menuOpen }" :aria-label="locale === 'ru' ? 'Основная навигация' : 'Main navigation'">
          <RouterLink to="/" @click="closeMenu()">{{ t('app.home') }}</RouterLink>
          <RouterLink to="/profile" @click="closeMenu()">{{ t('app.profile') }}</RouterLink>
          <RouterLink to="/onboarding" @click="closeMenu()">{{ t('app.onboarding') }}</RouterLink>
          <RouterLink to="/diagnostics" @click="closeMenu()">{{ locale === 'ru' ? 'Занятие' : 'Practice' }}</RouterLink>
        </nav>
        <RouterLink
          v-if="learnerStore.learner && (auth.actor || localPrototype)"
          to="/profile"
          class="header-learner"
          :title="t('app.currentLearner') + ': ' + learnerStore.learner.displayName"
        >
          <span>{{ t('app.currentLearner') }}:</span>
          <strong class="header-learner-name">{{ learnerStore.learner.displayName }}</strong>
        </RouterLink>
        <button v-if="auth.actor || localPrototype" ref="tunerButton" class="header-tool-button" type="button"
          :title="t('tuner.title')" :aria-label="t('tuner.open')"
          aria-haspopup="dialog" :aria-expanded="tunerOpen" @click="openTuner">
          <svg viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor"
            stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
            <path d="M8 3v5a4 4 0 0 0 8 0V3M8 3 5 6m11-3 3 3M12 12v9m-3 0h6"/>
          </svg>
        </button>
        <button v-if="auth.actor || localPrototype" ref="headerMetronomeButton" type="button" class="header-tool-button" :class="{ 'is-playing': metronomeStatus === 'running' }"
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
        <button v-if="auth.actor" type="button" class="header-signout-button" :disabled="logoutPending" :aria-busy="logoutPending" @click="signOut">{{ logoutPending ? t('auth.loading') : t('auth.signOut') }}</button>
        <span v-if="logoutError" class="header-action-error" role="alert">{{ locale === 'ru' ? 'Не удалось выйти. Повторите попытку.' : 'Sign out failed. Please retry.' }}</span>
        <label class="language-picker">
          <span class="sr-only">{{ t('app.language') }}</span>
          <select v-model="locale" aria-label="Language">
            <option value="ru">RU</option>
            <option value="en">EN</option>
          </select>
        </label>
      </div>
    </header>
    <MetronomeDialog v-if="(auth.actor || localPrototype) && metronomeUi.open" @close="closeMetronome" @minimize="minimizeMetronome" />
    <TunerDialog :open="(auth.actor || localPrototype) && tunerOpen" @close="closeTuner" />
    <AuthGate><main class="main-content"><RouterView /></main></AuthGate>
    <footer>Guitar Mentor AI · MVP</footer>
  </div>
</template>
