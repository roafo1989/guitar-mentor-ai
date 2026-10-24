import { defineStore } from 'pinia';
import { ref } from 'vue';
import { learnerService } from '../entities/learner/learner.service';
import { auth } from '../core/auth/auth';
import type {
  CreateLearnerRequest,
  Learner,
  UpdatePreferencesRequest,
} from '../shared/model/learner.model';

const storageKey = 'guitar-mentor.learnerId';

export const useLearnerStore = defineStore('learner', () => {
  const learner = ref<Learner | null>(null);
  const busy = ref(false);
  const error = ref('');

  async function execute(action: () => Promise<Learner>): Promise<boolean> {
    busy.value = true;
    error.value = '';
    try {
      const loaded = await action();
      learner.value = loaded;
      if (auth.actor && loaded.id !== auth.actor.learnerId) throw new Error('RESOURCE_NOT_FOUND');
      window.localStorage.setItem(storageKey, loaded.id);
      return true;
    } catch (cause) {
      error.value = cause instanceof Error ? cause.message : 'UNKNOWN_ERROR';
      return false;
    } finally {
      busy.value = false;
    }
  }

  async function restore(): Promise<void> {
    // In secured mode the authenticated account, never localStorage, chooses the learner.
    const id = auth.actor ? auth.actor.learnerId : window.localStorage.getItem(storageKey);
    if (!id) return;
    const restored = await execute(() => learnerService.get(id));
    if (!restored && error.value.startsWith('HTTP_404')) {
      window.localStorage.removeItem(storageKey);
      learner.value = null;
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
    learner.value = null;
    error.value = '';
    window.localStorage.removeItem(storageKey);
  }

  return { learner, busy, error, restore, create, replacePreferences, clearLocalSelection };
});
