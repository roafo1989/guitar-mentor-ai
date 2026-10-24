<script setup lang="ts">
import { onMounted } from 'vue';
import { useI18n } from 'vue-i18n';
import { auth, login, refreshActor } from './auth';
const localPrototype = import.meta.env.DEV && import.meta.env.VITE_GUITAR_MENTOR_AUTH_REQUIRED !== 'true';
const { t } = useI18n();
onMounted(() => { if (!localPrototype) void refreshActor(); });
</script>
<template>
  <slot v-if="localPrototype || (auth.ready && auth.actor)" />
  <main v-else-if="auth.ready" class="main-content" aria-live="polite">
    <h1>{{ auth.expired ? t('auth.expired') : t('auth.welcome') }}</h1>
    <p>{{ t('auth.invited') }}</p>
    <button type="button" @click="login">{{ t('auth.signIn') }}</button>
  </main>
  <main v-else class="main-content" aria-live="polite">{{ t('auth.loading') }}</main>
</template>
