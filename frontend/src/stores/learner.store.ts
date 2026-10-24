import { defineStore } from 'pinia';
import { ref } from 'vue';
import { learnerService } from '../entities/learner/learner.service';
import { auth, secureUi } from '../core/auth/auth';
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
      if (secureUi && (!auth.actor || loaded.id !== auth.actor.learnerId)) {
        throw new Error('RESOURCE_NOT_FOUND');
      }
      learner.value = loaded;
      if (!secureUi) window.localStorage.setItem(storageKey, loaded.id);
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
    if (secureUi) learner.value = null;
    const id = secureUi ? auth.actor?.learnerId : window.localStorage.getItem(storageKey);
    if (!id) return;
    const restored = await execute(() => learnerService.get(id));
    if (!restored && error.value.startsWith('HTTP_404')) {
      window.localStorage.removeItem(storageKey);
      learner.value = null;
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
    learner.value = null;
    error.value = '';
    window.localStorage.removeItem(storageKey);
    if (secureUi) {
      // Per-learner draft/session pointers are private browser context, not server identity.
      for (let i = window.localStorage.length - 1; i >= 0; i--) {
        const key = window.localStorage.key(i);
        if (key?.startsWith('guitar-mentor.diagnostic.')) {
          window.localStorage.removeItem(key);
        }
      }
    }
  }

  return { learner, busy, error, restore, create, replacePreferences, clearLocalSelection };
});
