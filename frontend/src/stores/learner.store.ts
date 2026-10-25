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
  let sessionActor = auth.actor;
  let inFlight: Promise<void> | null = null;
  let inFlightId: string | null = null;
  function syncActor(): void {
    if (!secureUi || sessionActor === auth.actor) return;
    sessionActor = auth.actor;
    ++generation;
    inFlight = null;
    inFlightId = null;
    learner.value = null;
    busy.value = false;
    error.value = null;
  }

  async function execute(action: () => Promise<Learner>): Promise<boolean> {
    const current = ++generation;
    inFlight = null;
    inFlightId = null;
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

  function restore(): Promise<void> {
    syncActor();
    const actor = auth.actor;
    const id = secureUi ? actor?.learnerId : window.localStorage.getItem(storageKey);
    if (!id) { ++generation; inFlight = null; inFlightId = null; busy.value = false; error.value = null; if (secureUi) learner.value = null; return Promise.resolve(); }
    if (inFlight && inFlightId === id) return inFlight;
    if (learner.value && learner.value.id !== id) learner.value = null;
    const current = ++generation;
    busy.value = true;
    error.value = null;
    inFlightId = id;
    const valid = () => current === generation && (!secureUi || auth.actor === actor) &&
      (secureUi ? auth.actor?.learnerId === id : window.localStorage.getItem(storageKey) === id);
    const request = (async () => {
      try {
        const loaded = await learnerService.get(id);
        if (!valid()) return;
        if (secureUi && loaded.id !== id) throw new Error('RESOURCE_NOT_FOUND');
        learner.value = loaded;
      } catch (cause) {
        if (!valid() || (cause instanceof Error && cause.name === 'AbortError')) return;
        if (isHttp(cause, 404)) {
          if (!secureUi) window.localStorage.removeItem(storageKey);
          else error.value = asApiFailure(cause);
          learner.value = null;
        } else error.value = asApiFailure(cause);
      } finally {
        if (valid()) { busy.value = false; inFlight = null; inFlightId = null; }
      }
    })();
    inFlight = request;
    return request;
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
    sessionActor = auth.actor;
    inFlight = null;
    inFlightId = null;
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
