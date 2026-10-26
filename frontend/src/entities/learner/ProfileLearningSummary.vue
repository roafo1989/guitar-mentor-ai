<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import { onboardingService } from '../onboarding/onboarding.service';
import { diagnosticService } from '../diagnostic/diagnostic.service';
import { apiFailureKey, asApiFailure, type ApiFailure } from '../../shared/api-failure';
import type { LearningBackground } from '../../shared/model/onboarding.model';
import type { SkillSnapshot } from '../../shared/model/diagnostic.model';
const props = defineProps<{ learnerId: string }>();
const { t } = useI18n();
const background = ref<LearningBackground | null>(null);
const signals = ref<SkillSnapshot[]>([]);
const error = ref<ApiFailure | null>(null);
const loading = ref(true);
const observed = computed(() => signals.value.filter(item => ['OBSERVED', 'MEASURED'].includes(item.evidenceStatus)));
let generation = 0;
let active = true;
onBeforeUnmount(() => { active = false; ++generation; });
async function load(): Promise<void> {
  const seq = ++generation; const id = props.learnerId;
  loading.value = true; error.value = null; background.value = null; signals.value = [];
  const results = await Promise.allSettled([onboardingService.get(id, 'ACOUSTIC_GUITAR'), diagnosticService.skillMap(id, 'ACOUSTIC_GUITAR')]);
  if (!active || seq !== generation || props.learnerId !== id) return;
  if (results[0].status === 'fulfilled') background.value = results[0].value;
  else error.value = asApiFailure(results[0].reason);
  if (results[1].status === 'fulfilled') signals.value = results[1].value;
  else error.value = asApiFailure(results[1].reason);
  loading.value = false;
}
watch(() => props.learnerId, () => { void load(); }, { immediate: true });
</script>
<template>
  <section class="panel learning-summary">
    <div class="page-heading"><h2>{{ t('profileUx.goals') }}</h2><RouterLink to="/onboarding" class="continue-link">{{ t(background ? 'profileUx.editAnswers' : 'profileUx.fillAnswers') }} →</RouterLink></div>
    <p v-if="loading" role="status">{{ t('onboarding.loading') }}</p>
    <template v-else>
      <div v-if="error" role="alert"><p>{{ t(apiFailureKey(error)) }}</p><button type="button" class="text-button" @click="load">{{ t('home.retry') }}</button></div>
      <p v-if="background?.goals.length">{{ background.goals.map(code => t('onboarding.goals.' + code)).join(' · ') }}</p>
      <p v-else class="hint">{{ t('profileUx.noGoals') }}</p>
      <h2>{{ t('profileUx.skills') }}</h2>
      <h3>{{ t('profileUx.selfReport') }}</h3>
      <div v-if="background?.skills.length" class="skill-chips"><span v-for="code in background.skills" :key="code">{{ t('onboarding.skills.' + code) }}</span></div>
      <p v-else class="hint">{{ t('profileUx.noSelfReport') }}</p>
      <h3>{{ t('profileUx.evidence') }}</h3>
      <ul v-if="observed.length" class="skill-evidence"><li v-for="item in observed" :key="item.skillCode"><strong>{{ item.title }}</strong> · {{ t('profileUx.sources.' + item.evidenceStatus) }}</li></ul>
      <p v-else class="hint">{{ t(error ? 'profileUx.evidenceUnavailable' : 'profileUx.noEvidence') }}</p>
    </template>
  </section>
</template>
