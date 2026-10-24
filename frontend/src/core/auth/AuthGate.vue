<script setup lang="ts">
import { onMounted, ref } from 'vue';
import { useI18n } from 'vue-i18n';
import { auth, login, refreshActor, secureUi } from './auth';
const localPrototype = !secureUi;
const { t } = useI18n();
const code = ref('');
const error = ref('');
const busy = ref(false);
async function redeem(): Promise<void> {
  if (!/^[a-f0-9]{64}$/.test(code.value)) {
    error.value = t('auth.invalidInvitation');
    return;
  }
  busy.value = true;
  error.value = '';
  try {
    // The authenticated but not yet admitted Google OIDC session can only invoke activation.
    const response = await fetch('/api/v1/invitations/activate', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ token: code.value }),
    });
    if (!response.ok) { error.value = t('auth.invalidInvitation'); return; }
    code.value = '';
    await refreshActor();
  } catch { error.value = t('auth.invalidInvitation'); }
  finally { busy.value = false; }
}
onMounted(() => { if (!localPrototype) void refreshActor(); });
</script>
<template>
  <slot v-if="localPrototype || (auth.ready && auth.actor)" />
  <main v-else-if="auth.ready" class="main-content" aria-live="polite">
    <h1>{{ auth.expired ? t('auth.expired') : t('auth.welcome') }}</h1>
    <p>{{ t('auth.invited') }}</p>
    <button type="button" @click="login">{{ t('auth.signIn') }}</button>
    <form @submit.prevent="redeem" style="margin-top:1rem">
      <label for="invitation-code">{{ t('auth.invitationCode') }}</label>
      <input id="invitation-code" v-model.trim="code" :placeholder="t('auth.invitationCode')"
        autocomplete="off" spellcheck="false" maxlength="64" />
      <button type="submit" :disabled="busy || code.length !== 64">{{ t('auth.activate') }}</button>
      <p v-if="error" role="alert">{{ error }}</p>
    </form>
  </main>
  <main v-else class="main-content" aria-live="polite">{{ t('auth.loading') }}</main>
</template>
