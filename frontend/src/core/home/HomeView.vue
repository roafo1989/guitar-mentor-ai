<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { apiFailureKey, asApiFailure, type ApiFailure } from '../../shared/api-failure';
import { resolveHomeSummary, type HomeSummary } from './home-summary';
import { useI18n } from 'vue-i18n';
import { useLearnerStore } from '../../stores/learner.store';
import { auth, secureUi } from '../auth/auth';

const { t } = useI18n();
const lightMinimal = import.meta.env?.VITE_GUITAR_MENTOR_LIGHT_MINIMAL === 'true';
const learnerStore = useLearnerStore();
const loading = ref(true);
const summary = ref<HomeSummary | null>(null);
const recoveryError = ref<ApiFailure | null>(null);
const primaryTarget = computed(() =>
  summary.value?.step === 'profile' ? '/profile'
    : summary.value?.step === 'onboarding' ? '/onboarding' : '/diagnostics');
const primaryLabel = computed(() => {
  if (recoveryError.value) return t('home.retry');
  if (!summary.value) return t('home.start');
  return t('home.next.' + summary.value.step);
});
let generation = 0;
let active = true;
const restoreFailed = computed(() => recoveryError.value !== null);
const hasInstrument = computed(() => learnerStore.learner?.instruments?.some(
  instrument => instrument.instrumentCode === 'ACOUSTIC_GUITAR',
) ?? false);
const hasAssignedWork = computed(() => summary.value?.step === 'lesson' || summary.value?.step === 'result');
const homeHeading = computed(() => hasAssignedWork.value ? t('home.light.currentTitle') : t('home.title'));
const homeDescription = computed(() => {
  if (!summary.value) return t('home.description');
  return t('home.light.descriptions.' + summary.value.step);
});
const visibleSections = computed(() => sections.filter(section =>
  section.key === 'profile' || (section.key === 'onboarding' ? hasInstrument.value : hasInstrument.value && summary.value?.step !== 'profile'),
));

const recoveryMessage = computed(() => recoveryError.value ? t(apiFailureKey(recoveryError.value)) : '');
const sections = [
  { key: 'profile', to: '/profile', number: '01', icon: 'M12 12a4 4 0 1 0 0-8 4 4 0 0 0 0 8ZM4 21v-2a6 6 0 0 1 6-6h4a6 6 0 0 1 6 6v2' },
  { key: 'onboarding', to: '/onboarding', number: '02', icon: 'M8 4H5a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2V6a2 2 0 0 0-2-2h-3M8 2h8v5H8ZM7 12h10M7 17h7' },
  { key: 'diagnostics', to: '/diagnostics', number: '03', icon: 'M4 14v-4M8 19V5M12 22V2M16 17V7M20 14v-4' },
] as const;

async function restoreProfile(): Promise<void> {
  const seq = ++generation;
  const actorId = auth.actor?.accountId ?? null;
  loading.value = true;
  recoveryError.value = null;
  try {
    const previous = learnerStore.learner?.id;
    if (!learnerStore.learner) await learnerStore.restore();
    if (!active || seq !== generation || (secureUi && auth.actor?.accountId !== actorId)) return;
    if (learnerStore.error) throw learnerStore.error;
    // No new session or assignment is created by the home screen.
    const id = learnerStore.learner?.id ?? null;
    if (previous && id !== previous) summary.value = null;
    // Public signup creates an Account and a bare Learner before instrument setup.
    // Never request instrument background for a learner without that instrument.
    const hasGuitar = learnerStore.learner?.instruments.some(
      instrument => instrument.instrumentCode === 'ACOUSTIC_GUITAR',
    ) ?? false;
    const next = await resolveHomeSummary(hasGuitar ? id : null, window.localStorage);
    if (!active || seq !== generation || learnerStore.learner?.id !== id || (secureUi && auth.actor?.accountId !== actorId)) return;
    summary.value = next;
  } catch (cause) {
    if (active && seq === generation && (!secureUi || auth.actor?.accountId === actorId) && !(cause instanceof Error && cause.name === 'AbortError')) recoveryError.value = asApiFailure(cause);
  } finally {
    if (active && seq === generation && (!secureUi || auth.actor?.accountId === actorId)) loading.value = false;
  }
}
function refreshForLearner(): void {
  summary.value = null;
  void restoreProfile();
}
watch(() => learnerStore.learner?.id, (now, old) => {
  if (now !== old) refreshForLearner();
});
watch(() => auth.actor?.accountId, (now, old) => {
  if (!secureUi || now === old) return;
  ++generation; summary.value = null; recoveryError.value = null; loading.value = true;
  if (now) void restoreProfile();
});
onBeforeUnmount(() => { active = false; ++generation; });

onMounted(restoreProfile);
</script>

<template>
  <section class="home-page" :data-home-ui="lightMinimal ? 'light-minimal' : undefined" aria-labelledby="home-title">
    <div class="home-hero">
      <div class="home-welcome">
        <p class="eyebrow">
          {{ learnerStore.learner
            ? t('home.welcomeBack', { name: learnerStore.learner.displayName })
            : t('home.welcome') }}
        </p>
        <h1 id="home-title">{{ lightMinimal ? homeHeading : t('home.title') }}</h1>
        <p class="home-description">{{ lightMinimal ? homeDescription : t('home.description') }}</p>
        <div class="home-start">
          <p v-if="loading" class="home-loading" role="status">{{ t('home.loading') }}</p>
          <RouterLink v-else-if="!restoreFailed" :to="primaryTarget" class="home-primary">
            {{ primaryLabel }} <span aria-hidden="true">→</span>
          </RouterLink>
          <p v-if="!loading && !restoreFailed && summary?.title" class="home-current-task">
            <span class="home-current-caption" v-if="lightMinimal">{{ t('home.light.assigned') }}: </span>{{ summary.title }} · {{ summary.exerciseCode }} · v{{ summary.exerciseVersion }}
          </p>
        </div>
      </div>
      <aside v-if="!lightMinimal || (!loading && !restoreFailed && hasInstrument)" class="home-instrument" :aria-label="t('home.focus')">
        <img class="home-instrument-mark" src="/favicon.png" alt="" aria-hidden="true" width="72" height="72" />
        <div>
          <p class="home-instrument-label">{{ t('instruments.ACOUSTIC_GUITAR') }}</p>
          <p class="home-instrument-style">{{ t('instruments.FINGERSTYLE') }}</p>
          <p class="home-instrument-note">{{ t('home.pace') }}</p>
        </div>
      </aside>
    </div>

    <div v-if="!loading && restoreFailed" class="notice home-recovery" role="alert">
      <span>{{ recoveryMessage }}</span>
      <button type="button" class="text-button" @click="restoreProfile">{{ t('home.retry') }}</button>
    </div>

    <section v-if="!lightMinimal || (!loading && !restoreFailed)" class="home-sections" aria-labelledby="home-sections-title">
      <h2 id="home-sections-title">{{ t('home.sectionsTitle') }}</h2>
      <div class="home-cards">
        <RouterLink v-for="section in (lightMinimal ? visibleSections : sections)" :key="section.key" :to="section.to" class="home-card">
          <div class="home-card-top" aria-hidden="true">
            <svg viewBox="0 0 24 24" width="24" height="24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round">
              <path :d="section.icon" />
            </svg>
            <span>{{ section.number }}</span>
          </div>
          <h3>{{ t('app.' + section.key) }}</h3>
          <p>{{ t('home.sections.' + section.key) }}</p>
          <span class="home-card-link">{{ t('home.open') }} <span aria-hidden="true">→</span></span>
        </RouterLink>
      </div>
    </section>
  </section>
</template>

<style scoped>
.home-page { max-width: 1130px; margin: 0 auto; padding: 64px 24px 72px; }
.home-hero { display: grid; grid-template-columns: minmax(0, 1.6fr) minmax(0, 1fr); gap: 48px; align-items: center; margin-bottom: 52px; }
.home-welcome .eyebrow { margin: 0 0 18px; overflow-wrap: anywhere; }
h1 { max-width: 590px; font-size: clamp(36px, 4.5vw, 54px); line-height: 1.12; letter-spacing: -1.6px; margin: 0 0 20px; text-wrap: balance; }
.home-description { max-width: 540px; color: #5c6b7e; line-height: 1.75; margin: 0; }
.home-start { margin-top: 28px; min-height: 48px; }
.home-current-task { margin: 14px 0 0; color: #395f58; font-size: 14px; overflow-wrap: anywhere; }
button.home-primary { border: 0; font: inherit; cursor: pointer; }
.home-loading { color: #5c6b7e; margin: 0; padding: 13px 0; }
.home-primary { display: inline-flex; align-items: center; justify-content: space-between; gap: 24px; border-radius: 9px; padding: 14px 22px; background: #28776d; color: white; text-decoration: none; font-weight: 600; }
.home-primary:hover { background: #205f57; }
.home-instrument { min-width: 0; display: flex; flex-direction: column; gap: 28px; align-items: flex-start; border: 1px solid #d5e6e0; border-radius: 24px; padding: 32px; background: linear-gradient(135deg, #e8f2ed, #f3f7f5); }
.home-instrument-mark { display: block; width: 72px; height: 72px; object-fit: contain; }
.home-instrument-label { font-weight: 700; font-size: 18px; margin: 0 0 8px; }
.home-instrument-style { color: #3d695f; margin: 0; font-size: 14px; }
.home-instrument-note { border-top: 1px solid #d5e6e0; padding-top: 18px; margin: 22px 0 0; color: #5c6b7e; font-size: 14px; line-height: 1.6; }
.home-recovery { display: flex; align-items: center; justify-content: space-between; flex-wrap: wrap; gap: 12px; }
.home-cards { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 18px; }
.home-card { display: flex; flex-direction: column; padding: 26px; background: white; border: 1px solid #e0e6ed; border-radius: 14px; text-decoration: none; box-shadow: 0 5px 25px rgba(25,42,64,.03); }
.home-card:hover { border-color: #7daba1; box-shadow: 0 5px 25px rgba(25,42,64,.08); }
.home-card-top { display: flex; align-items: center; justify-content: space-between; color: #3d786e; margin-bottom: 22px; }
.home-card-top span { color: #768498; font-size: 12px; letter-spacing: 1px; }
.home-card h3 { margin: 0 0 10px; font-size: 18px; }
.home-card p { margin: 0 0 24px; color: #5c6b7e; font-size: 14px; line-height: 1.65; }
.home-card-link { margin-top: auto; display: flex; justify-content: space-between; color: #28776d; font-size: 14px; font-weight: 600; }
.home-primary:focus-visible, .home-card:focus-visible { outline: 3px solid #28776d; outline-offset: 4px; }
@media (max-width: 800px) {
  .home-hero { gap: 24px; }
  .home-instrument { padding: 24px; }
  .home-cards { gap: 12px; }
  .home-card { padding: 20px; }
}
@media (max-width: 680px) {
  .home-page { padding: 36px 16px 48px; }
  .home-hero { grid-template-columns: 1fr; margin-bottom: 36px; }
  .home-instrument { flex-direction: row; align-items: center; gap: 24px; }
  .home-instrument-mark { flex-shrink: 0; width: 56px; height: 56px; }
  .home-instrument-label { font-size: 16px; }
  .home-instrument-note { margin-top: 14px; padding-top: 12px; }
  .home-cards { grid-template-columns: 1fr; }
}
/* FT-016-02: opt-in Home presentation; legacy layout and business actions are unchanged. */
.home-page[data-home-ui="light-minimal"] {max-width:1320px;padding:clamp(32px,5vw,72px) 24px 80px}
.home-page[data-home-ui="light-minimal"] .home-hero {
  grid-template-columns:minmax(0,1.2fr) minmax(280px,.8fr);gap:clamp(24px,5vw,80px);
  align-items:center;margin-bottom:56px;
}
.home-page[data-home-ui="light-minimal"] h1 {
  max-width:680px;font-size:clamp(38px,4.6vw,62px);line-height:1.1;
  letter-spacing:-.045em;color:var(--gm-text);font-weight:750;
}
.home-page[data-home-ui="light-minimal"] .home-description {
  max-width:600px;line-height:1.7;color:var(--gm-muted);font-size:clamp(16px,1.2vw,18px);
}
.home-page[data-home-ui="light-minimal"] .home-welcome .eyebrow {color:var(--gm-primary)}
.home-page[data-home-ui="light-minimal"] .home-primary {
  background:var(--gm-primary);border-radius:10px;min-height:52px;padding:14px 23px;
}
.home-page[data-home-ui="light-minimal"] .home-primary:hover {background:var(--gm-primary-hover)}
.home-page[data-home-ui="light-minimal"] .home-instrument {
  background:linear-gradient(135deg,#eaf4ef,#f5f8f6);
  border:1px solid var(--gm-border);border-radius:24px;padding:36px;box-shadow:none;
}
.home-page[data-home-ui="light-minimal"] .home-cards {gap:20px}
.home-page[data-home-ui="light-minimal"] .home-card {
  border:1px solid var(--gm-border);border-radius:16px;box-shadow:var(--gm-shadow);
  padding:28px;min-width:0;transition:border-color .15s,box-shadow .15s;
}
.home-page[data-home-ui="light-minimal"] .home-card:hover {border-color:#89b6a8;box-shadow:0 10px 30px #16233112}
.home-page[data-home-ui="light-minimal"] .home-card h3 {color:var(--gm-text)}
.home-page[data-home-ui="light-minimal"] .home-card p {color:var(--gm-muted)}
.home-page[data-home-ui="light-minimal"] .home-card-link {color:var(--gm-primary)}
.home-page[data-home-ui="light-minimal"] .home-current-caption {font-weight:650}
.home-page[data-home-ui="light-minimal"] .home-loading {min-height:52px;color:var(--gm-muted)}
.home-page[data-home-ui="light-minimal"] .home-recovery {margin:0 0 32px}
.home-page[data-home-ui="light-minimal"] :is(a,button):focus-visible {outline:3px solid var(--gm-focus);outline-offset:4px}
@media(max-width:900px) {
  .home-page[data-home-ui="light-minimal"] .home-hero {grid-template-columns:1fr;gap:24px}
  .home-page[data-home-ui="light-minimal"] .home-instrument {max-width:600px;flex-direction:row;align-items:center}
  .home-page[data-home-ui="light-minimal"] .home-cards {grid-template-columns:repeat(2,minmax(0,1fr))}
}
@media(max-width:680px) {
  .home-page[data-home-ui="light-minimal"] {padding:32px 16px 56px}
  .home-page[data-home-ui="light-minimal"] .home-hero {margin-bottom:40px}
  .home-page[data-home-ui="light-minimal"] .home-cards {grid-template-columns:1fr}
  .home-page[data-home-ui="light-minimal"] .home-card {padding:20px}
  .home-page[data-home-ui="light-minimal"] .home-instrument {padding:20px;gap:16px}
}

</style>
