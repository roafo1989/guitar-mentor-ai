<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue';
import { useI18n } from 'vue-i18n';
import { useLearnerStore } from '../../stores/learner.store';
import type { MusicPreference, PreferenceRating } from '../../shared/model/learner.model';

const { t } = useI18n();
const store = useLearnerStore();
const name = ref('');
const success = ref('');
const styleCodes = [
  'BLUES', 'BLUES_ROCK', 'ROCK', 'BLUEGRASS', 'JAZZ', 'SWING',
  'CLASSICAL', 'MANOUCHE', 'FUNK',
] as const;
const ratingValues: PreferenceRating[] = ['LIKE', 'NEUTRAL', 'EXCLUDE'];
const ratings = reactive<Record<string, PreferenceRating>>({});

function resetRatings(): void {
  styleCodes.forEach(code => { ratings[code] = 'NEUTRAL'; });
}

function applyProfile(): void {
  resetRatings();
  const instrument = store.learner?.instruments.find(
    item => item.instrumentCode === 'ACOUSTIC_GUITAR',
  );
  instrument?.preferences.forEach(pref => {
    if (styleCodes.some(code => code === pref.styleCode)) {
      ratings[pref.styleCode] = pref.rating;
    }
  });
  name.value = store.learner?.displayName ?? '';
}

const hasProfile = computed(() => store.learner !== null);
const message = computed(() =>
  store.error === 'NETWORK_UNAVAILABLE' ? t('form.networkError') : store.error,
);

onMounted(async () => {
  resetRatings();
  await store.restore();
  applyProfile();
});

function newProfile(): void {
  store.clearLocalSelection();
  name.value = '';
  success.value = '';
  resetRatings();
}

async function save(): Promise<void> {
  success.value = '';
  const preferences: MusicPreference[] = styleCodes.map(styleCode => ({
    styleCode, rating: ratings[styleCode],
  }));
  const saved = hasProfile.value
    ? await store.replacePreferences({ instrumentCode: 'ACOUSTIC_GUITAR', preferences })
    : await store.create({
        displayName: name.value.trim(),
        instrumentCode: 'ACOUSTIC_GUITAR',
        techniqueCode: 'FINGERSTYLE',
        preferences,
      });
  if (saved) {
    applyProfile();
    success.value = t('form.saved');
  }
}
</script>

<template>
  <section class="profile-page">
    <div class="intro">
      <span class="eyebrow">DEV-03 · MVP</span>
      <h1>{{ t('form.title') }}</h1>
      <p>{{ t('form.description') }}</p>
    </div>

    <div class="notice" role="note">{{ t('form.notice') }}</div>

    <form class="panel" @submit.prevent="save">
      <div v-if="hasProfile" class="saved-status" role="status">
        ✓ {{ t('form.loaded') }} · {{ store.learner?.id }}
      </div>

      <div class="form-grid">
        <label class="field">
          <span>{{ t('form.name') }}</span>
          <input
            v-model="name"
            type="text"
            :placeholder="t('form.example')"
            :readonly="hasProfile"
            required
            maxlength="100"
          />
          <small v-if="hasProfile">{{ t('form.nameReadonly') }}</small>
        </label>
        <label class="field">
          <span>{{ t('form.instrument') }}</span>
          <select aria-label="Instrument" disabled>
            <option>{{ t('instruments.ACOUSTIC_GUITAR') }}</option>
          </select>
        </label>
        <label class="field">
          <span>{{ t('form.technique') }}</span>
          <select aria-label="Technique" disabled>
            <option>{{ t('instruments.FINGERSTYLE') }}</option>
          </select>
          <small>{{ t('form.supported') }}</small>
        </label>
      </div>

      <div class="section-heading">
        <h2>{{ t('form.styles') }}</h2>
      </div>
      <div class="style-grid">
        <label v-for="code in styleCodes" :key="code" class="style-choice">
          <span>{{ t('styles.' + code) }}</span>
          <select v-model="ratings[code]" :aria-label="t('styles.' + code)">
            <option v-for="rating in ratingValues" :key="rating" :value="rating">
              {{ t('ratings.' + rating) }}
            </option>
          </select>
        </label>
      </div>

      <p v-if="message" class="error" role="alert">{{ t('form.error') }} {{ message }}</p>
      <p v-if="success" class="success" role="status">{{ success }}</p>

      <div class="form-actions">
        <button type="submit" :disabled="store.busy || !name.trim()">
          {{ store.busy ? t('form.saving') : t(hasProfile ? 'form.update' : 'form.save') }}
        </button>
        <button v-if="hasProfile" type="button" class="text-button" @click="newProfile">
          {{ t('form.new') }}
        </button>
      </div>
    </form>
  </section>
</template>
