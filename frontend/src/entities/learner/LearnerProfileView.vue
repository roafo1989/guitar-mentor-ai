<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import { apiFailureKey } from '../../shared/api-failure';
import { useLearnerStore } from '../../stores/learner.store';
import { auth, secureUi } from '../../core/auth/auth';
import { useUnsavedChanges } from '../../shared/unsaved-changes';
import ProfileLearningSummary from './ProfileLearningSummary.vue';
import type { MusicPreference, PreferenceRating } from '../../shared/model/learner.model';

const { t } = useI18n();
const store = useLearnerStore();
const name = ref('');
const success = ref(false);
const editing = ref(false);
const dirty = ref(false);
const loading = ref(true);
const readResolved = ref(false);
const conflict = ref(false);
const styleCodes = ['BLUES', 'BLUES_ROCK', 'ROCK', 'BLUEGRASS', 'JAZZ', 'SWING', 'CLASSICAL', 'MANOUCHE', 'FUNK'] as const;
const ratingValues: PreferenceRating[] = ['LIKE', 'NEUTRAL', 'EXCLUDE'];
const ratings = reactive<Record<string, PreferenceRating>>({});
function applyProfile(): void {
  styleCodes.forEach(code => { ratings[code] = 'NEUTRAL'; });
  store.learner?.instruments.find(item => item.instrumentCode === 'ACOUSTIC_GUITAR')?.preferences.forEach(pref => {
    if (styleCodes.some(code => code === pref.styleCode)) ratings[pref.styleCode] = pref.rating;
  });
  name.value = store.learner?.displayName ?? '';
}
const hasProfile = computed(() => store.learner !== null);
const hasGuitar = computed(() => store.learner?.instruments.some(item => item.instrumentCode === 'ACOUSTIC_GUITAR') ?? false);
const message = computed(() => store.error ? t(apiFailureKey(store.error)) : '');
const maySave = computed(() => !loading.value && readResolved.value && !conflict.value && !store.busy && !!name.value.trim() && (!secureUi || hasProfile.value));
let active = true;
let generation = 0;
onBeforeUnmount(() => { active = false; ++generation; });
useUnsavedChanges(dirty, () => t('profileUx.discardPrompt'));
async function loadProfile(): Promise<void> {
  const seq = ++generation;
  loading.value = true;
  readResolved.value = false;
  await store.restore();
  if (!active || seq !== generation) return;
  loading.value = false;
  if (store.error) return;
  if (dirty.value) conflict.value = true;
  else { applyProfile(); readResolved.value = true; editing.value = !hasGuitar.value; }
}
function acceptServer(): void {
  applyProfile(); dirty.value = false; conflict.value = false; readResolved.value = true;
}
function cancelEdit(): void {
  if (dirty.value && !window.confirm(t('profileUx.discardPrompt'))) return;
  acceptServer(); editing.value = false;
}
watch(() => secureUi ? auth.actor?.accountId : store.learner?.id, (next, previous) => {
  if (next === previous) return;
  ++generation; dirty.value = false; success.value = false; editing.value = false;
  conflict.value = false; name.value = ''; styleCodes.forEach(code => { ratings[code] = 'NEUTRAL'; });
  void loadProfile();
});
onMounted(() => { applyProfile(); void loadProfile(); });
async function save(): Promise<void> {
  if (!maySave.value) return;
  const seq = ++generation;
  success.value = false;
  const preferences: MusicPreference[] = styleCodes.map(styleCode => ({ styleCode, rating: ratings[styleCode] }));
  const saved = hasProfile.value
    ? await store.replacePreferences({ instrumentCode: 'ACOUSTIC_GUITAR', preferences })
    : await store.create({ displayName: name.value.trim(), instrumentCode: 'ACOUSTIC_GUITAR', techniqueCode: 'FINGERSTYLE', preferences });
  if (!active || seq !== generation) return;
  if (saved) { applyProfile(); dirty.value = false; success.value = true; editing.value = false; }
}
</script>
<template>
  <section class="profile-page learner-experience">
    <div class="intro page-heading">
      <div><span class="eyebrow">{{ t('profileUx.myMusic') }}</span><h1>{{ t('profileUx.title') }}</h1><p>{{ t('profileUx.description') }}</p></div>
      <RouterLink to="/settings/account" class="quiet-link">{{ t('profileUx.account') }} ↗</RouterLink>
    </div>
    <p v-if="loading" class="panel" role="status">{{ t('onboarding.loading') }}</p>
    <template v-else>
      <p v-if="message" class="notice error" role="alert">{{ message }}</p>
      <button v-if="store.error && !store.busy" type="button" class="action-button secondary-button" @click="loadProfile">{{ t('home.retry') }}</button>
      <section v-if="!editing && hasProfile" class="panel profile-overview">
        <div class="page-heading"><div><h2>{{ store.learner?.displayName }}</h2><p>{{ t('instruments.ACOUSTIC_GUITAR') }} · {{ t('instruments.FINGERSTYLE') }}</p></div>
          <button type="button" class="action-button secondary-button" @click="editing = true; success = false">{{ t('profileUx.edit') }}</button></div>
        <h3>{{ t('form.styles') }}</h3>
        <dl class="preference-summary"><div v-for="rating in ratingValues" :key="rating"><dt>{{ t('ratings.' + rating) }}</dt><dd>{{ styleCodes.filter(code => ratings[code] === rating).map(code => t('styles.' + code)).join(', ') || t('profileUx.none') }}</dd></div></dl>
        <p v-if="success" class="saved-confirmation" role="status">✓ {{ t('form.saved') }}</p>
        <RouterLink to="/" class="continue-link">{{ t('profileUx.backLearning') }} →</RouterLink>
      </section>
      <form v-else class="panel" @input="dirty = true; success = false" @change="dirty = true; success = false" @submit.prevent="save">
        <fieldset :disabled="store.busy" class="plain-fieldset">
          <h2>{{ t(hasGuitar ? 'profileUx.editPreferences' : 'profileUx.setup') }}</h2>
          <label class="field"><span>{{ t('form.name') }}</span><input v-model="name" :readonly="hasProfile" required maxlength="100" autocomplete="given-name" /><small v-if="hasProfile">{{ t('form.nameReadonly') }}</small></label>
          <p>{{ t('instruments.ACOUSTIC_GUITAR') }} · {{ t('instruments.FINGERSTYLE') }}</p>
          <div class="section-heading"><h2>{{ t('form.styles') }}</h2><p class="hint">{{ t('profileUx.preferencesHint') }}</p></div>
          <div class="preference-rows"><fieldset v-for="code in styleCodes" :key="code" class="preference-row"><legend>{{ t('styles.' + code) }}</legend><div class="rating-options"><label v-for="rating in ratingValues" :key="rating" class="rating-option"><input v-model="ratings[code]" type="radio" :name="code" :value="rating" /><span>{{ t('ratings.' + rating) }}</span></label></div></fieldset></div>
          <div v-if="conflict" class="notice" role="alert"><p>{{ t('profileUx.profileConflict') }}</p><button type="button" class="action-button secondary-button" @click="acceptServer">{{ t('profileUx.loadServer') }}</button></div>
          <div class="form-actions"><button type="submit" :disabled="!maySave">{{ store.busy ? t('form.saving') : t('form.update') }}</button><button v-if="hasGuitar" type="button" class="text-button" @click="cancelEdit">{{ t('profileUx.cancel') }}</button></div>
        </fieldset>
      </form>
      <ProfileLearningSummary v-if="hasGuitar" :learner-id="store.learner!.id" />
    </template>
  </section>
</template>
