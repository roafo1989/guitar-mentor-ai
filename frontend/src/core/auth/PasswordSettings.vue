<script setup lang="ts">
import { onMounted, ref } from 'vue';
import { useI18n } from 'vue-i18n';
import { refreshActor } from './auth';
const { locale } = useI18n();
const label = (ru: string, en: string) => locale.value === 'ru' ? ru : en;
const enabled = ref(false);
const loaded = ref(false);
const oldPassword = ref('');
const newPassword = ref('');
const confirm = ref('');
const busy = ref(false);
const show = ref(false);
const error = ref('');
onMounted(async () => {
  try {
    const response = await fetch('/api/v1/local-auth/methods');
    if (response.ok) {
      enabled.value = Boolean((await response.json() as { passwordEnabled: boolean }).passwordEnabled);
    }
  } finally { loaded.value = true; }
});
async function change(): Promise<void> {
  if (busy.value) return;
  error.value = '';
  if (newPassword.value !== confirm.value) {
    error.value = label('Пароли не совпадают.', 'Passwords do not match.'); return;
  }
  busy.value = true;
  try {
    const response = await fetch('/api/v1/local-auth/change', {
      method: 'POST', credentials: 'same-origin',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ currentPassword: oldPassword.value, newPassword: newPassword.value }),
    });
    if (!response.ok) {
      error.value = label('Пароль не изменён. Проверьте текущий пароль.', 'Password not changed. Check your current password.');
      return;
    }
    oldPassword.value = ''; newPassword.value = ''; confirm.value = '';
    await refreshActor();
  } catch {
    error.value = label('Ошибка сети. Повторите попытку.', 'Network error. Please retry.');
  } finally { busy.value = false; }
}
</script>
<template>
  <section v-if="loaded && enabled" class="panel password-settings" aria-labelledby="password-settings-title">
    <h2 id="password-settings-title">{{ label('Изменить пароль', 'Change password') }}</h2>
    <p class="hint">{{ label('После изменения все сессии завершатся, потребуется повторный вход.', 'Changing your password signs out all sessions. Sign in again afterward.') }}</p>
    <form class="auth-form" @submit.prevent="change">
      <label for="current-password">{{ label('Текущий пароль', 'Current password') }}</label>
      <input id="current-password" v-model="oldPassword" :type="show ? 'text' : 'password'" autocomplete="current-password" required />
      <label for="new-account-password">{{ label('Новый пароль (минимум 12 символов)', 'New password (12 characters minimum)') }}</label>
      <input id="new-account-password" v-model="newPassword" :type="show ? 'text' : 'password'" autocomplete="new-password" minlength="12" maxlength="128" required />
      <label for="confirm-account-password">{{ label('Повторите новый пароль', 'Confirm new password') }}</label>
      <input id="confirm-account-password" v-model="confirm" :type="show ? 'text' : 'password'" autocomplete="new-password" required />
      <label class="auth-checkbox"><input v-model="show" type="checkbox" />{{ label('Показать пароли', 'Show passwords') }}</label>
      <button type="submit" class="auth-primary" :disabled="busy" :aria-busy="busy">{{ label('Сменить пароль', 'Change password') }}</button>
      <p v-if="error" class="auth-error" role="alert">{{ error }}</p>
    </form>
  </section>
</template>
