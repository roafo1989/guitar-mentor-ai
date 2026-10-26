<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { apiFailureKey, asApiFailure, type ApiFailure } from '../../shared/api-failure';
import { auth, secureUi } from '../../core/auth/auth';
import { useUnsavedChanges } from '../../shared/unsaved-changes';
import { resolveHomeSummary, type HomeSummary } from '../../core/home/home-summary';
import { homeNextTarget } from '../../core/home/home-next-action';
import { useI18n } from 'vue-i18n';
import { useLearnerStore } from '../../stores/learner.store';
import { onboardingService } from './onboarding.service';
import type {
  ExperienceLevel, GoalCode, KnownPiece, LearningBackground, SkillTag,
} from '../../shared/model/onboarding.model';

const { t } = useI18n();
const learnerStore = useLearnerStore();
const experienceOptions: ExperienceLevel[] = ['NONE', 'BEGINNER', 'INTERMEDIATE', 'ADVANCED'];
const skillOptions: SkillTag[] = [
  'CHORDS', 'STRUMMING', 'ARPEGGIOS', 'FINGERSTYLE_MELODY',
];
const goalOptions: GoalCode[] = [
  'FINGERSTYLE', 'RHYTHM', 'INDEPENDENT_BASS',
  'DIFFERENT_STYLES', 'COMPOSITIONS', 'THEORY',
];

const step = ref<1 | 2 | 3>(1);
const loading = ref(true);
const saving = ref(false);
const error = ref<ApiFailure | null>(null);
const localValidation = ref(false);
const failedRead = ref(false);
const edited = ref(false);
const loadedFor = ref<string | null>(null);
const readResolved = ref(false);
const pendingBackground = ref<LearningBackground | null>(null);
let saveGeneration = 0;
const needsReadResolution = computed(() => pendingBackground.value !== null);
const maySave = computed(() => !saved.value && !!learnerStore.learner && !saving.value && !loading.value && !failedRead.value && readResolved.value && loadedFor.value === learnerStore.learner.id);
function resetForm(): void {
  step.value = 1;
  experienceLevel.value = 'BEGINNER';
  yearsPlaying.value = 0;
  skills.value = [];
  knownPieces.value = [];
  goals.value = ['FINGERSTYLE'];
  practiceMinutes.value = 40;
  sessionsPerWeek.value = 3;
  notes.value = '';
  edited.value = false;
  readResolved.value = false;
  loadedFor.value = null;
  pendingBackground.value = null;
  saving.value = false;
  ++saveGeneration;
  failedRead.value = false;
  error.value = null;
  localValidation.value = false;
  saved.value = false; savedConfirmation.value = false;
  summary.value = null; nextError.value = null; ++nextGeneration;
}
const errorMessage = computed(() => localValidation.value ? t('profileUx.invalidAnswers') : error.value ? t(apiFailureKey(error.value)) : '');
let loadGeneration = 0;
let active = true;
onBeforeUnmount(() => { active = false; ++loadGeneration; });
watch(() => (secureUi ? auth.actor?.accountId ?? '' : '') + ':' + (learnerStore.learner?.id ?? ''), (next, previous) => {
  if (next === previous) return;
  ++loadGeneration;
  resetForm();
  if (learnerStore.learner && active) void loadBackground();
});
const saved = ref(false);
const savedConfirmation = ref(false);
const experienceLevel = ref<ExperienceLevel>('BEGINNER');
const yearsPlaying = ref(0);
const skills = ref<SkillTag[]>([]);
const knownPieces = ref<KnownPiece[]>([]);
const goals = ref<GoalCode[]>(['FINGERSTYLE']);
const practiceMinutes = ref(40);
const sessionsPerWeek = ref(3);
const notes = ref('');

function apply(view: LearningBackground): void {
  experienceLevel.value = view.experienceLevel;
  yearsPlaying.value = view.yearsPlaying;
  skills.value = [...view.skills];
  knownPieces.value = view.knownPieces.map(piece => ({ ...piece }));
  goals.value = [...view.goals];
  practiceMinutes.value = view.practiceMinutes;
  sessionsPerWeek.value = view.sessionsPerWeek;
  notes.value = view.notes ?? '';
}

async function loadBackground(): Promise<void> {
  const seq = ++loadGeneration;
  const actor = auth.actor;
  loading.value = true;
  failedRead.value = false;
  error.value = null;
  try {
    if (!learnerStore.learner) await learnerStore.restore();
    if (learnerStore.error) throw learnerStore.error;
    const id = learnerStore.learner?.id;
    if (!id) return;
    const background = await onboardingService.get(id, 'ACOUSTIC_GUITAR');
    if (active && seq === loadGeneration && learnerStore.learner?.id === id && (!secureUi || auth.actor?.accountId === actor?.accountId)) {
      // A GET never overwrites a locally edited form. Even a successful 204
      // resolves the load gate, so a new background can be saved explicitly.
      loadedFor.value = id;
      if (background && edited.value) {
        // Preserve the draft, but do not allow a partial default-filled PUT.
        // The learner must explicitly load the existing server version.
        pendingBackground.value = background;
        readResolved.value = false;
      } else {
        if (background) apply(background);
        pendingBackground.value = null;
        readResolved.value = true;
      }
    }
  } catch (cause) {
    if (active && seq === loadGeneration && !(cause instanceof Error && cause.name === 'AbortError'))
      { error.value = asApiFailure(cause); failedRead.value = true; }
  } finally {
    if (active && seq === loadGeneration) loading.value = false;
  }
}
function acceptServerBackground(): void {
  const server = pendingBackground.value;
  if (!server || loadedFor.value !== learnerStore.learner?.id) return;
  apply(server);
  edited.value = false;
  pendingBackground.value = null;
  readResolved.value = true;
}
onMounted(() => { void loadBackground(); });

useUnsavedChanges(edited, () => t('profileUx.discardPrompt'));
const summary = ref<HomeSummary | null>(null);
const nextError = ref<ApiFailure | null>(null);
const resolvingNext = ref(false);
const heading = ref<HTMLElement | null>(null);
const addPieceButton = ref<HTMLButtonElement | null>(null);
let nextGeneration = 0;
const nextTarget = computed(() => homeNextTarget(summary.value?.step));
const nextLabel = computed(() => summary.value?.step === 'result' ? t('lessonNext.choose') : t('home.next.' + summary.value?.step));
async function resolveNext(): Promise<void> {
  const id = learnerStore.learner?.id; if (!id) return;
  const seq = ++nextGeneration; const actorId = auth.actor?.accountId;
  const current = () => active && seq === nextGeneration && learnerStore.learner?.id === id && (!secureUi || auth.actor?.accountId === actorId);
  resolvingNext.value = true; nextError.value = null; summary.value = null;
  try {
    const result = await resolveHomeSummary(id, window.localStorage, current);
    if (current()) summary.value = result;
  } catch (cause) { if (current()) nextError.value = asApiFailure(cause); }
  finally { if (current()) resolvingNext.value = false; }
}
function addPiece(): void {
  if (knownPieces.value.length >= 10) return;
  knownPieces.value.push({ title: '', artist: '' }); edited.value = true; savedConfirmation.value = false;
  void nextTick(() => Array.from(document.querySelectorAll<HTMLInputElement>('[data-piece-title]')).at(-1)?.focus());
}
function removePiece(index: number): void {
  knownPieces.value.splice(index, 1); edited.value = true; savedConfirmation.value = false;
  void nextTick(() => addPieceButton.value?.focus?.());
}
function validStep(): boolean {
  if (step.value === 1) return Number.isInteger(yearsPlaying.value) && yearsPlaying.value >= 0 && yearsPlaying.value <= 60
    && knownPieces.value.length <= 10 && knownPieces.value.every(piece => piece.title.trim().length > 0 && piece.title.length <= 120 && piece.artist.length <= 120);
  if (step.value === 3) return Number.isInteger(practiceMinutes.value) && practiceMinutes.value >= 10 && practiceMinutes.value <= 120
    && Number.isInteger(sessionsPerWeek.value) && sessionsPerWeek.value >= 1 && sessionsPerWeek.value <= 7 && notes.value.length <= 1000;
  return true;
}
function nextStep(): void {
  if (saving.value) return;
  localValidation.value = !validStep();
  if (!localValidation.value && step.value < 3) { step.value = (step.value + 1) as 2 | 3; focusHeading(); }
}
function backStep(): void { if (saving.value) return; if (step.value > 1) step.value = (step.value - 1) as 1 | 2; localValidation.value = false; focusHeading(); }
function focusHeading(): void { void nextTick(() => heading.value?.focus?.()); }
function editAgain(): void { ++nextGeneration; resolvingNext.value = false; saved.value = false; step.value = 1; summary.value = null; focusHeading(); }

async function save(complete = true): Promise<void> {
  if (!maySave.value || !learnerStore.learner) return;
  saved.value = false; savedConfirmation.value = false;
  failedRead.value = false;
  error.value = null;
  localValidation.value = false;
  const pieces = knownPieces.value.map(piece => ({ title: piece.title.trim(), artist: piece.artist.trim() }));
  // Every save writes the complete API object, even when initiated on an earlier step.
  if (!Number.isInteger(yearsPlaying.value) || yearsPlaying.value < 0 || yearsPlaying.value > 60
    || !Number.isInteger(practiceMinutes.value) || practiceMinutes.value < 10 || practiceMinutes.value > 120
    || !Number.isInteger(sessionsPerWeek.value) || sessionsPerWeek.value < 1 || sessionsPerWeek.value > 7
    || notes.value.length > 1000 || pieces.length > 10
    || pieces.some(piece => !piece.title || piece.title.length > 120 || piece.artist.length > 120)) {
    localValidation.value = true;
    return;
  }
  saving.value = true;
  const learnerId = learnerStore.learner.id;
  const currentSave = ++saveGeneration;
  const actorId = auth.actor?.accountId;
  try {
    const background: LearningBackground = {
      experienceLevel: experienceLevel.value,
      yearsPlaying: yearsPlaying.value,
      skills: [...skills.value],
      knownPieces: pieces,
      goals: [...goals.value],
      practiceMinutes: practiceMinutes.value,
      sessionsPerWeek: sessionsPerWeek.value,
      notes: notes.value,
    };
    const result = await onboardingService.save(
      learnerId, 'ACOUSTIC_GUITAR', background,
    );
    if (!active || currentSave !== saveGeneration || learnerStore.learner?.id !== learnerId || (secureUi && auth.actor?.accountId !== actorId)) return;
    if (result) apply(result);
    edited.value = false;
    savedConfirmation.value = true;
    if (complete) {
      saved.value = true;
      focusHeading();
      void resolveNext();
    }
  } catch (cause) {
    if (active && currentSave === saveGeneration && learnerStore.learner?.id === learnerId && (!secureUi || auth.actor?.accountId === actorId))
      error.value = asApiFailure(cause);
  } finally {
    if (currentSave === saveGeneration) saving.value = false;
  }
}
</script>

<template>
  <section class="profile-page learner-experience onboarding-page">
    <div class="intro"><span class="eyebrow">{{ t('profileUx.myMusic') }}</span><h1>{{ t('onboarding.title') }}</h1><p>{{ t('onboarding.subtitle') }}</p></div>
    <div v-if="loading" class="panel" role="status">{{ t('onboarding.loading') }}</div>
    <div v-else-if="error && !learnerStore.learner" class="panel" role="alert"><p>{{ errorMessage }}</p><button type="button" class="action-button" @click="loadBackground">{{ t('home.retry') }}</button></div>
    <div v-else-if="!learnerStore.learner" class="panel"><p>{{ t('onboarding.needProfile') }}</p><RouterLink to="/profile">{{ t('app.profile') }}</RouterLink></div>
    <section v-else-if="saved" class="panel completion-panel">
      <span class="completion-mark" aria-hidden="true">✓</span>
      <h2 ref="heading" tabindex="-1">{{ t('profileUx.completeTitle') }}</h2>
      <p role="status">{{ t('onboarding.saved') }}</p>
      <p>{{ t('profileUx.completeDescription') }}</p>
      <p v-if="resolvingNext" role="status">{{ t('profileUx.resolvingNext') }}</p>
      <div v-else-if="nextError" role="alert"><p>{{ t(apiFailureKey(nextError)) }}</p><button type="button" class="action-button" @click="resolveNext">{{ t('home.retry') }}</button></div>
      <RouterLink v-else-if="summary" :to="nextTarget" class="action-button primary-link">{{ nextLabel }} →</RouterLink>
      <div class="form-actions"><button type="button" class="text-button" @click="editAgain">{{ t('profileUx.editAnswers') }}</button><RouterLink to="/profile">{{ t('onboarding.profileBack') }}</RouterLink></div>
    </section>
    <form v-else class="panel" @input="edited = true; savedConfirmation = false" @change="edited = true; savedConfirmation = false" @submit.prevent="step < 3 ? nextStep() : save()">
      <ol class="wizard-steps" :aria-label="t('profileUx.stepsLabel')"><li v-for="(label, index) in ['experience', 'goals', 'rhythm']" :key="label" :aria-current="step === index + 1 ? 'step' : undefined" :class="{ current: step === index + 1, done: step > index + 1 }"><span>{{ index + 1 }}</span>{{ t('profileUx.steps.' + label) }}</li></ol>
      <p class="step-indicator">{{ t('onboarding.step', { step, total: 3 }) }}</p>
      <fieldset class="plain-fieldset" :disabled="saving">
        <template v-if="step === 1">
          <h2 ref="heading" tabindex="-1">{{ t('onboarding.experienceTitle') }}</h2>
          <div class="form-grid"><label class="field"><span>{{ t('onboarding.experienceLevel') }}</span><select v-model="experienceLevel"><option v-for="level in experienceOptions" :key="level" :value="level">{{ t('onboarding.levels.' + level) }}</option></select></label><label class="field"><span>{{ t('onboarding.yearsPlaying') }}</span><input v-model.number="yearsPlaying" type="number" min="0" max="60" required /></label></div>
          <div class="section-heading"><h3>{{ t('onboarding.skillsTitle') }}</h3><p class="hint">{{ t('onboarding.skillsHint') }}</p></div>
          <div class="choice-grid"><label v-for="code in skillOptions" :key="code" class="choice-checkbox"><input v-model="skills" type="checkbox" :value="code" /><span>{{ t('onboarding.skills.' + code) }}</span></label></div>
          <div class="section-heading"><h3>{{ t('onboarding.piecesTitle') }}</h3><p class="hint">{{ t('onboarding.piecesHint') }}</p></div>
          <div v-for="(piece, index) in knownPieces" :key="index" class="known-piece-row"><label class="field"><span>{{ t('profileUx.pieceTitle', { number: index + 1 }) }}</span><input v-model="piece.title" data-piece-title required maxlength="120" /></label><label class="field"><span>{{ t('profileUx.pieceArtist') }}</span><input v-model="piece.artist" maxlength="120" /></label><button type="button" class="text-button" :aria-label="t('profileUx.removePiece', { number: index + 1 })" @click="removePiece(index)">{{ t('profileUx.remove') }}</button></div>
          <button ref="addPieceButton" type="button" class="action-button secondary-button" :disabled="knownPieces.length >= 10" @click="addPiece">+ {{ t('profileUx.addPiece') }}</button>
        </template>
        <template v-else-if="step === 2">
          <h2 ref="heading" tabindex="-1">{{ t('onboarding.goalsTitle') }}</h2><p class="hint">{{ t('profileUx.goalsHint') }}</p>
          <div class="choice-grid"><label v-for="code in goalOptions" :key="code" class="choice-checkbox"><input v-model="goals" type="checkbox" :value="code" /><span>{{ t('onboarding.goals.' + code) }}</span></label></div>
          <div class="section-heading"><h3>{{ t('onboarding.notesTitle') }}</h3></div><label class="field"><span>{{ t('onboarding.notesHint') }}</span><textarea v-model="notes" rows="4" maxlength="1000" /></label>
        </template>
        <template v-else>
          <h2 ref="heading" tabindex="-1">{{ t('onboarding.practiceTitle') }}</h2>
          <p class="hint">{{ t('profileUx.rhythmHint') }}</p>
          <div class="form-grid"><label class="field"><span>{{ t('onboarding.practiceMinutes') }}</span><input v-model.number="practiceMinutes" type="number" min="10" max="120" required /></label><label class="field"><span>{{ t('onboarding.sessionsPerWeek') }}</span><input v-model.number="sessionsPerWeek" type="number" min="1" max="7" required /></label></div>
        </template>
        <div class="form-actions wizard-actions">
          <button v-if="step > 1" type="button" class="text-button" @click="backStep">← {{ t('onboarding.back') }}</button>
          <button v-if="step < 3" type="button" class="action-button" :disabled="!maySave" :aria-busy="saving" @click="save(false)">{{ saving ? t('form.saving') : t('onboarding.saveChanges') }}</button>
          <button v-if="step < 3" type="submit" class="action-button secondary-button wizard-next">{{ t('onboarding.next') }} →</button>
          <button v-else type="submit" :disabled="!maySave" :aria-busy="saving">{{ saving ? t('form.saving') : t('onboarding.save') }}</button>
        </div>
        <p v-if="savedConfirmation" class="saved-confirmation" role="status">✓ {{ t('form.saved') }}</p>
      </fieldset>
      <p v-if="error || localValidation" class="error" role="alert">{{ errorMessage }}</p>
      <div v-if="needsReadResolution" class="notice" role="alert"><p>{{ t('onboarding.draftConflict') }}</p><button type="button" class="action-button secondary-button" @click="acceptServerBackground">{{ t('onboarding.loadServerBackground') }}</button></div>
      <button v-if="failedRead && error && !saving" type="button" class="text-button" @click="loadBackground">{{ t('home.retry') }}</button>
    </form>
    <RouterLink v-if="!saved" to="/profile" class="quiet-link page-back">← {{ t('onboarding.profileBack') }}</RouterLink>
  </section>
</template>
