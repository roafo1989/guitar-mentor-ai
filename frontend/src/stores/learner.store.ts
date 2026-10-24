import { defineStore } from 'pinia';
import { ref } from 'vue';
import { learnerService } from '../entities/learner/learner.service';
import { ApiFailure, asApiFailure, isHttp } from '../shared/api-failure';
import type { CreateLearnerRequest, Learner, UpdatePreferencesRequest } from '../shared/model/learner.model';

const storageKey = 'guitar-mentor.learnerId';

export const useLearnerStore = defineStore('learner', () => {
  const learner = ref<Learner | null>(null);
  const busy = ref(false);
  // Keep structured failure; views translate it at render time (including locale switches).
  const error = ref<ApiFailure | null>(null);
  let generation = 0;

  async function execute(action: () => Promise<Learner>): Promise<boolean> {
    const current = ++generation;
    busy.value = true;
    error.value = null;
    try {
      const loaded = await action();
      if (current !== generation) return false;
      learner.value = loaded;
      window.localStorage.setItem(storageKey, loaded.id);
      return true;
    } catch (cause) {
      if (current === generation && !(cause instanceof Error && cause.name === 'AbortError'))
        error.value = asApiFailure(cause);
      return false;
    } finally {
      if (current === generation) busy.value = false;
    }
  }

  async function restore(): Promise<void> {
    const id = window.localStorage.getItem(storageKey);
    if (!id) { error.value = null; return; }
    const current = ++generation;
    busy.value = true;
    error.value = null;
    try {
      const loaded = await learnerService.get(id);
      if (current !== generation || window.localStorage.getItem(storageKey) !== id) return;
      learner.value = loaded;
    } catch (cause) {
      if (current !== generation || window.localStorage.getItem(storageKey) !== id) return;
      if (cause instanceof Error && cause.name === 'AbortError') return;
      if (isHttp(cause, 404)) {
        // Only a failed learner GET may forget this learner. No other key is touched.
        window.localStorage.removeItem(storageKey);
        learner.value = null;
      } else {
        error.value = asApiFailure(cause);
      }
    } finally {
      if (current === generation) busy.value = false;
    }
  }

  async function create(data: CreateLearnerRequest): Promise<boolean> {
    return execute(() => learnerService.create(data));
  }

  async function replacePreferences(data: UpdatePreferencesRequest): Promise<boolean> {
    if (!learner.value) return false;
    return execute(() => learnerService.replacePreferences(learner.value!.id, data));
  }

  function clearLocalSelection(): void {
    ++generation;
    busy.value = false;
    learner.value = null;
    error.value = null;
    window.localStorage.removeItem(storageKey);
  }

  return { learner, busy, error, restore, create, replacePreferences, clearLocalSelection };
});
