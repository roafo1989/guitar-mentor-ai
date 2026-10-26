<script setup lang="ts">
import { useI18n } from 'vue-i18n';
import { auth, secureUi } from './auth';
import PasswordSettings from './PasswordSettings.vue';
const { t } = useI18n();
</script>
<template>
  <section class="profile-page learner-experience account-page">
    <div class="intro">
      <RouterLink to="/profile" class="continue-link">← {{ t('onboarding.profileBack') }}</RouterLink>
      <h1>{{ t('profileUx.account') }}</h1>
      <p>{{ t('profileUx.accountDescription') }}</p>
    </div>
    <PasswordSettings v-if="secureUi && auth.actor" :key="auth.actor.accountId" />
    <p v-else class="panel">{{ t('profileUx.accountUnavailable') }}</p>
  </section>
</template>
