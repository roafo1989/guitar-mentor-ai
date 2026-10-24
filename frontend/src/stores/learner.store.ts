import { defineStore } from 'pinia';
import { ref } from 'vue';
import { learnerService } from '../entities/learner/learner.service';
import { auth, secureUi } from '../core/auth/auth';
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
      if (secureUi && (!auth.actor || loaded.id !== auth.actor.learnerId)) throw new Error('RESOURCE_NOT_FOUND');
      learner.value = loaded;
      if (!secureUi) window.localStorage.setItem(storageKey, loaded.id);
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
    if (secureUi) learner.value = null;
    const id = secureUi ? auth.actor?.learnerId : window.localStorage.getItem(storageKey);
    if (!id) { error.value = null; return; }
    const current = ++generation;
    busy.value = true;
    error.value = null;
    try {
      const loaded = await learnerService.get(id);
      if (current !== generation || (secureUi ? auth.actor?.learnerId !== id : window.localStorage.getItem(storageKey) !== id)) return;
      if (secureUi && loaded.id !== id) throw new Error('RESOURCE_NOT_FOUND');
      learner.value = loaded;
    } catch (cause) {
      if (current !== generation || (secureUi ? auth.actor?.learnerId !== id : window.localStorage.getItem(storageKey) !== id)) return;
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
    if (secureUi) return false;
    return execute(() => learnerService.create(data));
  }

  async function replacePreferences(data: UpdatePreferencesRequest): Promise<boolean> {
    if (!learner.value || (secureUi && learner.value.id !== auth.actor?.learnerId)) return false;
    return execute(() => learnerService.replacePreferences(learner.value!.id, data));
  }

  function clearLocalSelection(): void {
    ++generation;
    busy.value = false;
    learner.value = null;
    error.value = null;
    window.localStorage.removeItem(storageKey);
    if (secureUi) {
      for (let i = window.localStorage.length - 1; i >= 0; i--) {
        const key = window.localStorage.key(i);
        if (key?.startsWith('guitar-mentor.diagnostic.')) window.localStorage.removeItem(key);
      }
    }
  }

  return { learner, busy, error, restore, create, replacePreferences, clearLocalSelection };
});
