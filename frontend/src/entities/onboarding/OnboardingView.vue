<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { apiFailureKey, asApiFailure, type ApiFailure } from '../../shared/api-failure';
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

const step = ref<1 | 2>(1);
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
const maySave = computed(() => !!learnerStore.learner && !saving.value && !loading.value && !failedRead.value && readResolved.value && loadedFor.value === learnerStore.learner.id);
function resetForm(): void {
  step.value = 1;
  experienceLevel.value = 'BEGINNER';
  yearsPlaying.value = 0;
  skills.value = [];
  knownPiecesText.value = '';
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
  saved.value = false;
}
const errorMessage = computed(() => localValidation.value ? t('onboarding.invalidPieces') : error.value ? t(apiFailureKey(error.value)) : '');
let loadGeneration = 0;
let active = true;
onBeforeUnmount(() => { active = false; ++loadGeneration; });
watch(() => learnerStore.learner?.id ?? null, (next, previous) => {
  if (next === previous) return;
  ++loadGeneration;
  resetForm();
  if (next && active) void loadBackground();
});
const saved = ref(false);
const experienceLevel = ref<ExperienceLevel>('BEGINNER');
const yearsPlaying = ref(0);
const skills = ref<SkillTag[]>([]);
const knownPiecesText = ref('');
const goals = ref<GoalCode[]>(['FINGERSTYLE']);
const practiceMinutes = ref(40);
const sessionsPerWeek = ref(3);
const notes = ref('');

function apply(view: LearningBackground): void {
  experienceLevel.value = view.experienceLevel;
  yearsPlaying.value = view.yearsPlaying;
  skills.value = [...view.skills];
  knownPiecesText.value = view.knownPieces
    .map(piece => piece.title + ' | ' + piece.artist).join('\n');
  goals.value = [...view.goals];
  practiceMinutes.value = view.practiceMinutes;
  sessionsPerWeek.value = view.sessionsPerWeek;
  notes.value = view.notes;
}

async function loadBackground(): Promise<void> {
  const seq = ++loadGeneration;
  const learnerAtStart = learnerStore.learner?.id ?? window.localStorage.getItem('guitar-mentor.learnerId');
  loading.value = true;
  failedRead.value = false;
  error.value = null;
  try {
    if (!learnerStore.learner) await learnerStore.restore();
    if (learnerStore.error) throw learnerStore.error;
    const id = learnerStore.learner?.id;
    if (!id) return;
    const background = await onboardingService.get(id, 'ACOUSTIC_GUITAR');
    if (active && seq === loadGeneration && learnerStore.learner?.id === id && learnerAtStart === id) {
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

function parseKnownPieces(): KnownPiece[] {
  return knownPiecesText.value.split(/\r?\n/).map(line => line.trim())
    .filter(Boolean).map(line => {
      const separator = line.indexOf('|');
      const title = separator < 0 ? line : line.slice(0, separator).trim();
      const artist = separator < 0 ? '' : line.slice(separator + 1).trim();
      return { title, artist };
    });
}

async function save(): Promise<void> {
  if (!maySave.value || !learnerStore.learner) return;
  saved.value = false;
  failedRead.value = false;
  error.value = null;
  localValidation.value = false;
  const pieces = parseKnownPieces();
  if (pieces.length > 10 || pieces.some(piece => !piece.title || piece.title.length > 120
    || piece.artist.length > 120)) {
    localValidation.value = true;
    return;
  }
  saving.value = true;
  const learnerId = learnerStore.learner.id;
  const currentSave = ++saveGeneration;
  try {
    const background: LearningBackground = {
      experienceLevel: experienceLevel.value,
      yearsPlaying: yearsPlaying.value,
      skills: skills.value,
      knownPieces: pieces,
      goals: goals.value,
      practiceMinutes: practiceMinutes.value,
      sessionsPerWeek: sessionsPerWeek.value,
      notes: notes.value,
    };
    const result = await onboardingService.save(
      learnerId, 'ACOUSTIC_GUITAR', background,
    );
    if (!active || currentSave !== saveGeneration || learnerStore.learner?.id !== learnerId) return;
    if (result) apply(result);
    edited.value = false;
    saved.value = true;
  } catch (cause) {
    if (active && currentSave === saveGeneration && learnerStore.learner?.id === learnerId)
      error.value = asApiFailure(cause);
  } finally {
    if (currentSave === saveGeneration) saving.value = false;
  }
}
</script>

<template>
  <section class="profile-page">
    <div class="intro">
      <span class="eyebrow">DEV-04 · ONBOARDING</span>
      <h1>{{ t('onboarding.title') }}</h1>
      <p>{{ t('onboarding.subtitle') }}</p>
    </div>
    <div class="notice" role="note">{{ t('form.notice') }}</div>

    <div v-if="loading" class="panel">{{ t('onboarding.loading') }}</div>
    <div v-else-if="error && !learnerStore.learner" class="panel" role="alert">
      <p>{{ errorMessage }}</p>
      <button type="button" class="action-button" @click="loadBackground">{{ t('home.retry') }}</button>
    </div>
    <div v-else-if="!learnerStore.learner" class="panel">
      <p>{{ t('onboarding.needProfile') }}</p>
      <RouterLink to="/profile">{{ t('app.profile') }}</RouterLink>
    </div>
    <form v-else class="panel" @input="edited = true" @change="edited = true" @submit.prevent="save">
      <div class="step-indicator">{{ t('onboarding.step', { step, total: 2 }) }}</div>

      <template v-if="step === 1">
        <h2>{{ t('onboarding.experienceTitle') }}</h2>
        <div class="form-grid">
          <label class="field">
            <span>{{ t('onboarding.experienceLevel') }}</span>
            <select v-model="experienceLevel">
              <option v-for="level in experienceOptions" :key="level" :value="level">
                {{ t('onboarding.levels.' + level) }}
              </option>
            </select>
          </label>
          <label class="field">
            <span>{{ t('onboarding.yearsPlaying') }}</span>
            <input v-model.number="yearsPlaying" type="number" min="0" max="60" required />
          </label>
        </div>

        <div class="section-heading"><h2>{{ t('onboarding.skillsTitle') }}</h2></div>
        <p class="hint">{{ t('onboarding.skillsHint') }}</p>
        <div class="choice-grid">
          <label v-for="code in skillOptions" :key="code" class="choice-checkbox">
            <input v-model="skills" type="checkbox" :value="code" />
            <span>{{ t('onboarding.skills.' + code) }}</span>
          </label>
        </div>

        <div class="section-heading"><h2>{{ t('onboarding.piecesTitle') }}</h2></div>
        <label class="field">
          <span>{{ t('onboarding.piecesHint') }}</span>
          <textarea v-model="knownPiecesText" rows="5" maxlength="2500"
            :placeholder="t('onboarding.piecesExample')" />
        </label>
        <div class="form-actions">
          <button type="button" class="action-button" @click="step = 2">
            {{ t('onboarding.next') }} →
          </button>
        </div>
      </template>

      <template v-else>
        <h2>{{ t('onboarding.goalsTitle') }}</h2>
        <div class="choice-grid">
          <label v-for="code in goalOptions" :key="code" class="choice-checkbox">
            <input v-model="goals" type="checkbox" :value="code" />
            <span>{{ t('onboarding.goals.' + code) }}</span>
          </label>
        </div>

        <div class="section-heading"><h2>{{ t('onboarding.practiceTitle') }}</h2></div>
        <div class="form-grid">
          <label class="field">
            <span>{{ t('onboarding.practiceMinutes') }}</span>
            <input v-model.number="practiceMinutes" type="number" min="10" max="120" required />
          </label>
          <label class="field">
            <span>{{ t('onboarding.sessionsPerWeek') }}</span>
            <input v-model.number="sessionsPerWeek" type="number" min="1" max="7" required />
          </label>
        </div>

        <div class="section-heading"><h2>{{ t('onboarding.notesTitle') }}</h2></div>
        <label class="field">
          <span>{{ t('onboarding.notesHint') }}</span>
          <textarea v-model="notes" rows="4" maxlength="1000" />
        </label>

        <div class="form-actions">
          <button type="button" class="action-button secondary-button" @click="step = 1">
            ← {{ t('onboarding.back') }}
          </button>
          <button type="submit" :disabled="!maySave">
            {{ saving ? t('form.saving') : t('onboarding.save') }}
          </button>
        </div>
      </template>
      <p v-if="error || localValidation" class="error" role="alert">{{ errorMessage }}</p>
      <div v-if="needsReadResolution" class="notice" role="alert">
        <p>{{ t("onboarding.draftConflict") }}</p>
        <button type="button" class="action-button secondary-button" @click="acceptServerBackground">{{ t("onboarding.loadServerBackground") }}</button>
      </div>
      <button v-if="failedRead && error && !saving" type="button" class="text-button" @click="loadBackground">{{ t('home.retry') }}</button>
      <p v-if="saved" class="success" role="status">{{ t('onboarding.saved') }}</p>
      <div class="form-actions"><RouterLink to="/profile">{{ t('onboarding.profileBack') }}</RouterLink><RouterLink to="/diagnostics">{{ t('diagnostic.nextFromOnboarding') }} →</RouterLink></div>
    </form>
  </section>
</template>
