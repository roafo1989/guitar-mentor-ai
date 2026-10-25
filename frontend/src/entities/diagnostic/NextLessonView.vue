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
const exercises = ref<Exercise[]>([]), selected = ref<Exercise | null>(null);
const source = ref<{ learner: string; session: string; task: string; version: number } | null>(null);
let seq = 0;
let pendingRequest: { key: string; code: string; version: string } | null = null;
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
    if (state.taskState === 'ACTIVE') { error.value = t('lessonNext.noCompleted'); return; }
    source.value = { learner, session: current.id, task: task.id, version: state.version };
    const catalog = await diagnosticService.listExercises();
    if (valid()) exercises.value = catalog.filter(e => e.contentKind === 'LEARNING_REPERTOIRE');
  } catch (cause) { if (valid()) error.value = t(apiFailureKey(asApiFailure(cause))); }
  finally { if (valid()) loading.value = false; }
}
async function begin(): Promise<void> {
  const s = source.value, e = selected.value, actor = auth.actor, id = seq;
  if (!s || !e || pending.value) return;
  if (!pendingRequest || pendingRequest.code !== e.code || pendingRequest.version !== e.version)
    pendingRequest = { key: crypto.randomUUID(), code: e.code, version: e.version };
  const request = pendingRequest;
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
    pendingRequest = null;
    await router.replace('/diagnostics');
  } catch (cause) {
    if (id === seq && (!secureUi || auth.actor === actor))
      error.value = t(apiFailureKey(asApiFailure(cause)));
    // Keep the same UUID for a network/5xx retry; a lost response may have committed.
  } finally { if (id === seq && (!secureUi || auth.actor === actor)) pending.value = false; }
}
watch(() => auth.actor, () => {
  ++seq; source.value = null; selected.value = null; exercises.value = []; pendingRequest = null; pending.value = false;
  if (auth.actor || !secureUi) void load();
});
onMounted(load);
onBeforeUnmount(() => { ++seq; });
</script>

<template>
  <main class="profile-page">
    <h1>{{ t('lessonNext.choose') }}</h1>
    <p v-if="loading" role="status">{{ t('diagnostic.loading') }}</p>
    <template v-else>
      <p v-if="error" role="alert">{{ error }}</p>
      <button v-if="error && !pending" type="button" @click="load">{{ t('home.retry') }}</button>
      <template v-if="source">
        <label for="lesson-search">{{ t('lessonNext.search') }}</label>
        <input id="lesson-search" v-model="search" type="search" />
        <p v-if="!exercises.length">{{ t('lessonNext.empty') }}</p>
        <p v-else-if="!filtered.length">{{ t('lessonNext.noMatches') }}</p>
        <div v-for="exercise in visible" :key="exercise.code + '@' + exercise.version" class="panel">
          <h2>{{ exercise.title }}</h2>
          <p>{{ exercise.learningGoal || exercise.description }}</p>
          <p>{{ exercise.difficulty }} · {{ exercise.measures }} · {{ exercise.bpm }} BPM</p>
          <button type="button" :disabled="pending" @click="selected = exercise">
            {{ t('lessonNext.preview') }}
          </button>
        </div>
        <nav v-if="filtered.length > 20" class="form-actions" :aria-label="t('lessonNext.pages')">
          <button type="button" :disabled="page === 0 || pending" @click="page--">{{ t('lessonNext.previous') }}</button>
          <span>{{ page + 1 }} / {{ Math.ceil(filtered.length / 20) }}</span>
          <button type="button" :disabled="(page + 1) * 20 >= filtered.length || pending"
            @click="page++">{{ t('lessonNext.more') }}</button>
        </nav>
        <section v-if="selected" class="panel">
          <h2>{{ selected.title }}</h2>
          <ExercisePreview :exercise="selected" />
          <button type="button" class="action-button" :disabled="pending" @click="begin">
            {{ pending ? t('diagnostic.working') : t('lessonNext.start') }}
          </button>
        </section>
      </template>
    </template>
    <RouterLink to="/diagnostics">{{ t('diagnostic.back') }}</RouterLink>
    <RouterLink to="/lessons">{{ t('lessonNext.history') }}</RouterLink>
  </main>
</template>
