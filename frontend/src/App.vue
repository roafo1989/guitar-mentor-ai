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
const tunerIconPath = 'M9 21v-6.5c-1-1-1.5-2.1-1.5-3.7V5.5A4.5 4.5 0 0 1 12 1.5a4.5 4.5 0 0 1 4.5 4v5.3c0 1.6-.5 2.7-1.5 3.7V21M7.5 6h-3m3 3h-3m3 3h-3m12-6h3m-3 3h3m-3 3h3M13.4 6.5a1.4 1.4 0 1 1-2.8 0 1.4 1.4 0 0 1 2.8 0ZM10 21h4';
const metronomeIconPath = 'M8.5 6.5 5 20h14L15.5 6.5M4 22h16M12 18 17 3m-1.2 3.8 3.4-1.1 1.1 3.4-3.4 1.1Z';
const localPrototype = !secureUi;
const lightMinimal = import.meta.env?.VITE_GUITAR_MENTOR_LIGHT_MINIMAL === 'true';
const menuOpen = ref(false);
const accountMenuOpen = ref(false);
const accountMenuButton = ref<HTMLButtonElement | null>(null);
function closeAccountMenu(restoreFocus = false): void {
  if (!accountMenuOpen.value) return;
  accountMenuOpen.value = false;
  if (restoreFocus) void nextTick(() => accountMenuButton.value?.focus());
}

const menuButton = ref<HTMLButtonElement | null>(null);
const menuId = 'gm-header-menu';
function closeMenu(restoreFocus = false): void {
  if (!menuOpen.value) return;
  menuOpen.value = false;
  if (restoreFocus) void nextTick(() => menuButton.value?.focus());
}
function onHeaderKeydown(event: KeyboardEvent): void {
  if (event.key !== 'Escape') return;
  if (accountMenuOpen.value) { event.preventDefault(); closeAccountMenu(true); }
  else if (menuOpen.value) { event.preventDefault(); closeMenu(true); }
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
  closeAccountMenu();
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
  closeAccountMenu();
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
          <RouterLink to="/profile" @click="closeMenu()">{{ t('app.profile') }}</RouterLink>
          <RouterLink to="/onboarding" @click="closeMenu()">{{ t('app.onboarding') }}</RouterLink>
          <RouterLink to="/diagnostics" @click="closeMenu()">{{ locale === 'ru' ? 'Занятие' : 'Practice' }}</RouterLink>
        </nav>
        <RouterLink
          v-if="!lightMinimal && learnerStore.learner && (auth.actor || localPrototype)"
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
          <svg viewBox="0 0 24 24" width="24" height="24" fill="none" stroke="currentColor"
            stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
            <path :d="tunerIconPath"/>
            
            
            
            
            
          </svg>
          <span v-if="lightMinimal" class="header-tool-label">{{ t('tuner.title') }}</span>
        </button>
        <button v-if="auth.actor || localPrototype" ref="headerMetronomeButton" type="button" class="header-tool-button" :class="{ 'is-playing': metronomeStatus === 'running' }"
          :title="metronomeStatus === 'running' ? t('metronome.playingInBackground') : t('metronome.open')"
          :aria-label="metronomeStatus === 'running' ? t('metronome.playingInBackground') : t('metronome.open')"
          aria-haspopup="dialog" :aria-expanded="metronomeUi.open" @click="openHeaderMetronome">
          <svg viewBox="0 0 24 24" width="24" height="24" fill="none"
            stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"
            aria-hidden="true">
            <path :d="metronomeIconPath"/>
            
            
          </svg>
          <span v-if="lightMinimal" class="header-tool-label">{{ locale === 'ru' ? 'Метроном' : 'Metronome' }}</span>
          <span v-if="metronomeStatus === 'running'" class="metronome-running-dot" aria-hidden="true"></span>
        </button>
        <div v-if="lightMinimal && (auth.actor || localPrototype)" class="header-account">
          <button ref="accountMenuButton" type="button" class="header-account-trigger"
            :aria-label="locale === 'ru' ? 'Меню пользователя' : 'Account menu'"
            :aria-expanded="accountMenuOpen" aria-controls="gm-account-menu"
            aria-haspopup="true" @click="accountMenuOpen = !accountMenuOpen">
            <span class="header-avatar" aria-hidden="true">{{ (learnerStore.learner?.displayName || auth.actor?.accountId || '?').slice(0,1).toUpperCase() }}</span>
            <span class="header-account-name">{{ learnerStore.learner?.displayName || (locale === 'ru' ? 'Пользователь' : 'Account') }}</span>
            <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true"><path d="m6 9 6 6 6-6"/></svg>
          </button>
          <div v-if="accountMenuOpen" id="gm-account-menu" class="header-account-popover">
            <RouterLink to="/profile" @click="closeAccountMenu()">{{ t('app.profile') }}</RouterLink>
            <label class="language-picker header-account-locale">
              <span>{{ t('app.language') }}</span>
              <select v-model="locale" :aria-label="t('app.language')">
                <option value="ru">RU</option>
                <option value="en">EN</option>
              </select>
            </label>
            <button v-if="auth.actor" type="button" class="header-signout-button" :disabled="logoutPending" :aria-busy="logoutPending" @click="signOut">{{ logoutPending ? t('auth.loading') : t('auth.signOut') }}</button>
            <span v-if="logoutError" class="header-action-error" role="alert">{{ locale === 'ru' ? 'Не удалось выйти. Повторите попытку.' : 'Sign out failed. Please retry.' }}</span>
          </div>
        </div>
        <button v-if="!lightMinimal && auth.actor" type="button" class="header-signout-button" :disabled="logoutPending" :aria-busy="logoutPending" @click="signOut">{{ logoutPending ? t('auth.loading') : t('auth.signOut') }}</button>
        <span v-if="!lightMinimal && logoutError" class="header-action-error" role="alert">{{ locale === 'ru' ? 'Не удалось выйти. Повторите попытку.' : 'Sign out failed. Please retry.' }}</span>
        <label v-if="!lightMinimal || !(auth.actor || localPrototype)" class="language-picker">
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
