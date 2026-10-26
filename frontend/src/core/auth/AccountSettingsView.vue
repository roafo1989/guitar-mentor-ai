<script setup lang="ts">
import { ref } from 'vue';
import UsernameSettings from './UsernameSettings.vue';
import { useI18n } from 'vue-i18n';
import { auth, secureUi } from './auth';
import PasswordSettings from './PasswordSettings.vue';
const { t } = useI18n();
const credentialsVersion = ref(0);
</script>
<template>
  <section class="profile-page learner-experience account-page">
    <div class="intro">
      <RouterLink to="/profile" class="continue-link">← {{ t('onboarding.profileBack') }}</RouterLink>
      <h1>{{ t('profileUx.account') }}</h1>
      <p>{{ t('profileUx.accountDescription') }}</p>
    </div>
    <template v-if="secureUi && auth.actor">
      <UsernameSettings :key="auth.actor.accountId + ':' + credentialsVersion" />
      <PasswordSettings :key="auth.actor.accountId" @credentials-changed="credentialsVersion++" />
    </template>
    <p v-else class="panel">{{ t('profileUx.accountUnavailable') }}</p>
  </section>
</template>
