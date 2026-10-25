<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { useRouter } from 'vue-router';
import { useI18n } from 'vue-i18n';
import { useLearnerStore } from '../../stores/learner.store';
import { auth, secureUi } from '../../core/auth/auth';
import { apiFailureKey, asApiFailure } from '../../shared/api-failure';
import { diagnosticService } from './diagnostic.service';
import { resolveCurrentDiagnostic, diagnosticSelectionKey } from './current-diagnostic';
import { learningService } from '../attempt/learning.service';
import ExercisePreview from './ExercisePreview.vue';
import { closeMetronome } from '../metronome/metronome-ui';
import type { Exercise } from '../../shared/model/diagnostic.model';

const { t } = useI18n();
const router = useRouter();
const store = useLearnerStore();
const loading = ref(true), pending = ref(false), error = ref(''), search = ref('');
const uncertain = ref(false);
const exercises = ref<Exercise[]>([]), selected = ref<Exercise | null>(null);
const source = ref<{ learner: string; session: string; task: string; version: number; exerciseCode: string } | null>(null);
let seq = 0;
const pendingRequest = ref<{ key: string; code: string; version: string } | null>(null);
const page = ref(0);
const filtered = computed(() => exercises.value.filter(e => e.contentKind === 'LEARNING_REPERTOIRE'
  && (e.title + ' ' + e.code).toLocaleLowerCase().includes(search.value.toLocaleLowerCase())));

const visible = computed(() => filtered.value.slice(page.value * 20, (page.value + 1) * 20));
watch(search, () => { page.value = 0; });
async function load(): Promise<void> {
  const id = ++seq, actor = auth.actor;
  const valid = () => seq === id && (!secureUi || auth.actor === actor);
  loading.value = true; error.value = ''; source.value = null;
  try {
    if (!store.learner || (secureUi && store.learner.id !== actor?.learnerId)) await store.restore();
    const learner = secureUi ? actor?.learnerId : store.learner?.id;
    if (!valid() || !learner) return;
    const current = await resolveCurrentDiagnostic(learner, window.localStorage, valid);
    if (!valid()) return;
    if (!current || current.status !== 'TASK_ASSIGNED') {
      error.value = t('lessonNext.noCompleted'); return;
    }
    const task = await diagnosticService.currentTask(learner, current.id);
    if (!task) { error.value = t('lessonNext.noCompleted'); return; }
    const state = await learningService.state(learner, current.id, task.id);
    if (!valid()) return;
    if (!['COMPLETED_BY_LEARNER', 'COMPLETED_PASSED'].includes(state.taskState) ||
        !Number.isSafeInteger(state.version) || state.version < 0) {
      error.value = t('lessonNext.noCompleted'); return;
    }
    source.value = { learner, session: current.id, task: task.id, version: state.version, exerciseCode: task.exercise.code };
    const catalog = await diagnosticService.listExercises();
    if (valid()) exercises.value = catalog.filter(e => e.contentKind === 'LEARNING_REPERTOIRE');
  } catch (cause) { if (valid()) error.value = t(apiFailureKey(asApiFailure(cause))); }
  finally { if (valid()) loading.value = false; }
}
async function begin(): Promise<void> {
  const s = source.value, e = selected.value, actor = auth.actor, id = seq;
  if (!s || !e || pending.value) return;
  if (pendingRequest.value && (pendingRequest.value.code !== e.code || pendingRequest.value.version !== e.version)) {
    error.value = t('lessonNext.uncertain'); return;
  }
  if (s.exerciseCode === e.code && !pendingRequest.value && !window.confirm(t('lessonNext.confirmRepeat'))) return;
  if (!pendingRequest.value) pendingRequest.value = { key: crypto.randomUUID(), code: e.code, version: e.version };
  const request = pendingRequest.value;
  pending.value = true; error.value = '';
  try {
    await diagnosticService.nextLesson(s.learner, s.session, s.task,
      request.key, s.version, request.code, request.version);
    if (id !== seq || (secureUi && auth.actor !== actor)) return;
    closeMetronome();
    // Always read authoritative current, even if the POST was an old replay.
    const current = await diagnosticService.current(s.learner, 'ACOUSTIC_GUITAR');
    if (id !== seq || (secureUi && auth.actor !== actor) || !current) return;
    window.localStorage.setItem(diagnosticSelectionKey(s.learner), current.id);
    pendingRequest.value = null;
    uncertain.value = false;
    await router.replace('/diagnostics');
  } catch (cause) {
    if (id === seq && (!secureUi || auth.actor === actor)) {
      const failure = asApiFailure(cause);
      error.value = t(apiFailureKey(failure));
      uncertain.value = failure.kind !== 'HTTP' || (failure.status ?? 0) >= 500;
      // Only an indeterminate outcome reuses the requestId; 4xx is a definite refusal.
      if (!uncertain.value) pendingRequest.value = null;
    }
  } finally { if (id === seq && (!secureUi || auth.actor === actor)) pending.value = false; }
}
watch(() => auth.actor, () => {
  ++seq; source.value = null; selected.value = null; exercises.value = []; pendingRequest.value = null; uncertain.value = false; pending.value = false;
  if (auth.actor || !secureUi) void load();
});
onMounted(load);
onBeforeUnmount(() => { ++seq; });
</script>

<template>
  <main class="profile-page next-lesson-page">
    <header class="next-lesson-header"><h1>{{ t('lessonNext.choose') }}</h1></header>
    <p v-if="loading" role="status">{{ t('diagnostic.loading') }}</p>
    <template v-else>
      <p v-if="error" role="alert">{{ error }}</p>
      <button v-if="error && !pending" type="button" @click="load">{{ t('home.retry') }}</button>
      <template v-if="source">
        <p v-if="uncertain" role="status">{{ t('lessonNext.uncertain') }}</p>
        <div class="next-lesson-search"><label for="lesson-search">{{ t('lessonNext.search') }}</label>
          <input id="lesson-search" v-model="search" type="search" autocomplete="off" />
        </div>
        <p v-if="!exercises.length">{{ t('lessonNext.empty') }}</p>
        <p v-else-if="!filtered.length">{{ t('lessonNext.noMatches') }}</p>
        <div v-for="exercise in visible" :key="exercise.code + '@' + exercise.version" class="panel next-lesson-card">
          <h2>{{ exercise.title }}</h2>
          <p>{{ exercise.learningGoal || exercise.description }}</p>
          <p>{{ exercise.difficulty }} · {{ exercise.measures }} · {{ exercise.bpm }} BPM</p>
          <button type="button" class="action-button secondary-button" :disabled="pending || (!!pendingRequest && pendingRequest.code !== exercise.code)" @click="selected = exercise">
            {{ t('lessonNext.preview') }}
          </button>
        </div>
        <nav v-if="filtered.length > 20" class="form-actions" :aria-label="t('lessonNext.pages')">
          <button type="button" :disabled="page === 0 || pending" @click="page--">{{ t('lessonNext.previous') }}</button>
          <span>{{ page + 1 }} / {{ Math.ceil(filtered.length / 20) }}</span>
          <button type="button" :disabled="(page + 1) * 20 >= filtered.length || pending"
            @click="page++">{{ t('lessonNext.more') }}</button>
        </nav>
        <section v-if="selected" class="panel next-lesson-preview">
          <h2>{{ selected.title }}</h2>
          <ExercisePreview :exercise="selected" />
          <button type="button" class="action-button" :disabled="pending" @click="begin">
            {{ pending ? t('diagnostic.working') : t('lessonNext.start') }}
          </button>
        </section>
      </template>
    </template>
    <nav class="next-lesson-links" :aria-label="t('lessonNext.choose')">
      <RouterLink to="/diagnostics">{{ t('diagnostic.back') }}</RouterLink>
      <RouterLink to="/lessons">{{ t('lessonNext.history') }}</RouterLink>
    </nav>
  </main>
</template>

<style scoped>
.next-lesson-page { width: min(100%, 1080px); max-width: 1080px; min-width: 0; margin: 0 auto; padding: clamp(1.25rem, 3vw, 2.5rem); display: flex; flex-direction: column; gap: 1.25rem; }
.next-lesson-header h1 { margin: 0; font-size: clamp(1.65rem, 2.8vw, 2.3rem); line-height: 1.2; letter-spacing: -.025em; }
.next-lesson-search { display: flex; flex-direction: column; align-items: stretch; gap: .5rem; max-width: 32rem; }
.next-lesson-search label { display: block; font-weight: 600; line-height: 1.45; }
.next-lesson-search input { display: block; box-sizing: border-box; width: 100%; min-width: 0; min-height: 2.75rem; padding: .65rem .85rem; border: 1px solid var(--border-color, #cbd5d1); border-radius: .65rem; background: var(--surface, #fff); color: inherit; font: inherit; }
.next-lesson-search input:focus-visible, .next-lesson-page button:focus-visible, .next-lesson-links a:focus-visible { outline: 3px solid var(--accent, #24786e); outline-offset: 2px; }
.next-lesson-card, .next-lesson-preview { box-sizing: border-box; min-width: 0; margin: 0; padding: clamp(1rem, 2.5vw, 1.5rem); border-radius: 1rem; }
.next-lesson-card { display: flex; flex-direction: column; align-items: flex-start; gap: .75rem; }
.next-lesson-card h2, .next-lesson-preview h2 { margin: 0; font-size: 1.2rem; line-height: 1.35; }
.next-lesson-card p { margin: 0; line-height: 1.6; overflow-wrap: anywhere; }
.next-lesson-card button { margin-top: .35rem; min-height: 2.65rem; }
.next-lesson-preview { display: flex; flex-direction: column; gap: 1rem; }
.next-lesson-preview > button { align-self: flex-start; min-height: 2.75rem; }
.next-lesson-links { display: flex; flex-wrap: wrap; align-items: center; gap: .75rem 1.5rem; padding-top: .25rem; }
.next-lesson-links a { display: inline-flex; min-height: 2.5rem; align-items: center; font-weight: 500; text-underline-offset: .2em; }
.next-lesson-page button { font: inherit; cursor: pointer; }
.next-lesson-page button:disabled { cursor: not-allowed; opacity: .6; }
@media (max-width: 600px) { .next-lesson-page { padding: 1rem; gap: 1rem; } .next-lesson-search { max-width: none; } .next-lesson-card button, .next-lesson-preview > button { max-width: 100%; } }
</style>
