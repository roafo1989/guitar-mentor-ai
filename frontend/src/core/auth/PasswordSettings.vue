<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, ref } from 'vue';
import { useI18n } from 'vue-i18n';
import { takeAccountLinkToken } from './account-link';
import { isValidLogin, isValidPassword, normalizeLogin } from './credential-policy';
import { auth, refreshActor } from './auth';
const emit = defineEmits<{ 'credentials-changed': [] }>();
const { locale } = useI18n();
const label = (ru: string, en: string) => locale.value === 'ru' ? ru : en;
const loaded = ref(false);
const methodsKnown = ref(false);
let active = true;
let generation = 0;
const accountId = auth.actor?.accountId;
const isCurrent = () => active && auth.actor?.accountId === accountId;
onBeforeUnmount(() => { active = false; ++generation; currentPassword.value = ''; newPassword.value = ''; confirm.value = ''; googlePassword.value = ''; identifier.value = ''; token.value = ''; });
const passwordEnabled = ref(false);
const googleEnabled = ref(false);
const googleAvailable = ref(false);
const identifier = ref('');
const googlePassword = ref('');
const googleLinkOpen = ref(false);
const googleIdentifierInput = ref<HTMLInputElement | null>(null);
const googleLinkButton = ref<HTMLButtonElement | null>(null);
const email = ref('');
const login = ref('');
const currentPassword = ref('');
const newPassword = ref('');
const confirm = ref('');
const token = ref('');
const tokenFromLink = ref(false);
const busy = ref(false);
const show = ref(false);
const error = ref('');
const notice = ref('');
const verificationPending = ref(false);
onMounted(async () => {
  const value = takeAccountLinkToken();
  if (value) { token.value = value; tokenFromLink.value = true; }
  void loadMethods();
});
async function loadMethods(): Promise<void> {
  const seq = ++generation;
  loaded.value = false; methodsKnown.value = false; error.value = '';
  try {
    const response = await fetch('/api/v1/local-auth/methods');
    if (!response.ok) throw new Error('METHODS_UNAVAILABLE');
    const methods = await response.json();
    if (typeof methods.passwordEnabled !== 'boolean' || typeof methods.googleEnabled !== 'boolean' || typeof methods.googleAvailable !== 'boolean') throw new Error('INVALID_METHODS');
    if (!isCurrent() || seq !== generation) return;
    passwordEnabled.value = methods.passwordEnabled;
    googleEnabled.value = methods.googleEnabled;
    googleAvailable.value = methods.googleAvailable;
    methodsKnown.value = true;
    verificationPending.value = Boolean(token.value);
  } catch { if (isCurrent() && seq === generation) error.value = label('Не удалось получить способы входа.', 'Could not load sign-in methods.'); }
  finally { if (isCurrent() && seq === generation) loaded.value = true; }
}
async function submit(path: string, body: object): Promise<boolean> {
  if (busy.value || !methodsKnown.value || !isCurrent()) return false;
  busy.value = true; error.value = ''; notice.value = '';
  try {
    const response = await fetch('/api/v1/local-auth/' + path, {
      method: 'POST', credentials: 'same-origin',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    });
    if (!isCurrent()) return false;
    if (!response.ok) {
      error.value = response.status === 429
        ? label('Слишком много попыток. Попробуйте позже.', 'Too many attempts. Please try later.')
        : label('Действие не выполнено. Проверьте данные.', 'Could not complete the request. Check the details.');
      return false;
    }
    return true;
  } catch {
    if (!isCurrent()) return false;
    error.value = label('Ошибка сети. Попробуйте снова.', 'Network error. Try again.');
    return false;
  } finally { busy.value = false; }
}
async function change(): Promise<void> {
  if (!isValidPassword(newPassword.value)) { error.value = label('Пароль должен содержать 6–128 символов.', 'Password must be 6–128 characters.'); return; }
  if (newPassword.value !== confirm.value) {
    error.value = label('Пароли не совпадают.', 'Passwords do not match.'); return;
  }
  if (await submit('change',{currentPassword:currentPassword.value,newPassword:newPassword.value})) {
    currentPassword.value = ''; newPassword.value = ''; confirm.value = '';
    notice.value = label('Пароль изменён.', 'Password changed.');
    await refreshActor();
  }
}
async function linkPassword(): Promise<void> {
  if (!isValidLogin(login.value) || !isValidPassword(newPassword.value)) { error.value = label('Проверьте логин и пароль (6–128 символов).', 'Check username and password (6–128 characters).'); return; }
  if (newPassword.value !== confirm.value) {
    error.value = label('Пароли не совпадают.', 'Passwords do not match.'); return;
  }
  if (await submit('link/password/start',{email:email.value,login:normalizeLogin(login.value),password:newPassword.value})) {
    newPassword.value = ''; confirm.value = '';
    verificationPending.value = true;
    notice.value = label('Отправлено письмо. Подтвердите новый способ входа.', 'Check your email to confirm the new sign-in method.');
  }
}
async function verifyLink(): Promise<void> {
  if (await submit('link/password/verify',{token:token.value})) {
    passwordEnabled.value = true; verificationPending.value = false;
    emit('credentials-changed');
    token.value = ''; notice.value = label('Пароль добавлен.', 'Password sign-in enabled.');
  }
}
function openGoogleLink(): void {
  identifier.value = ''; googlePassword.value = ''; error.value = ''; notice.value = ''; googleLinkOpen.value = true;
  void nextTick(() => googleIdentifierInput.value?.focus?.());
}
function cancelGoogleLink(): void {
  identifier.value = ''; googlePassword.value = ''; googleLinkOpen.value = false;
  void nextTick(() => googleLinkButton.value?.focus?.());
}
async function linkGoogle(): Promise<void> {
  if (!googleLinkOpen.value || !identifier.value.trim() || !googlePassword.value) return;
  if (await submit('link/google/start',{identifier:identifier.value,password:googlePassword.value})) {
    googlePassword.value = '';
    window.location.assign('/oauth2/authorization/guitar-mentor');
  }
}
</script>
<template>
  <p v-if="!loaded" class="panel" role="status">{{ label('Загрузка…', 'Loading…') }}</p>
  <section v-else class="panel password-settings" aria-labelledby="password-settings-title">
    <h2 id="password-settings-title">{{ label('Способы входа', 'Sign-in methods') }}</h2>
    <p v-if="methodsKnown" class="hint">
      {{ label('Пароль:', 'Password:') }} {{ passwordEnabled ? label('подключён', 'enabled') : label('не подключён', 'not enabled') }} ·
      Google: {{ googleEnabled ? label('подключён', 'enabled') : label('не подключён', 'not enabled') }}
    </p>
    <form v-if="methodsKnown && passwordEnabled" class="auth-form" @submit.prevent="change">
      <h3>{{ label('Изменить пароль', 'Change password') }}</h3>
      <label for="current-password">{{ label('Текущий пароль', 'Current password') }}</label>
      <input id="current-password" name="password-change-current" v-model="currentPassword" :type="show ? 'text' : 'password'" autocomplete="section-password-change current-password" required />
      <label for="new-account-password">{{ label('Новый пароль (6–128 символов)', 'New password (6–128 characters)') }}</label>
      <input id="new-account-password" v-model="newPassword" :type="show ? 'text' : 'password'" autocomplete="section-password-change new-password" minlength="6" maxlength="256" required />
      <label for="confirm-account-password">{{ label('Повторите пароль', 'Confirm password') }}</label>
      <input id="confirm-account-password" v-model="confirm" :type="show ? 'text' : 'password'" autocomplete="section-password-change new-password" required />
      <label class="auth-checkbox"><input v-model="show" type="checkbox" />{{ label('Показать пароли', 'Show passwords') }}</label>
      <p class="hint">{{ label('Все старые сессии завершатся.', 'All older sessions will expire.') }}</p>
      <button type="submit" class="auth-primary" :disabled="busy" :aria-busy="busy">{{ label('Сменить пароль', 'Change password') }}</button>
    </form>
    <form v-else-if="methodsKnown" class="auth-form" @submit.prevent="linkPassword">
      <h3>{{ label('Добавить пароль', 'Add password') }}</h3>
      <p class="hint">{{ label('Для безопасности войдите через Google заново, затем добавьте пароль в течение 5 минут.', 'For security, sign in with Google again, then add a password within 5 minutes.') }}</p>
      <label for="link-email">Email</label>
      <input id="link-email" v-model.trim="email" type="email" autocomplete="email" required />
      <label for="link-username">{{ label('Логин', 'Username') }}</label>
      <input id="link-username" v-model.trim="login" autocomplete="username" minlength="3" maxlength="128" required />
      <p class="hint">{{ label('Логин: 3–64 символа, латиница или кириллица, цифры, . _ -; начните с буквы или цифры.', 'Username: 3–64 Latin or Cyrillic letters, digits, . _ -; start with a letter or digit.') }}</p>
      <label for="link-password">{{ label('Пароль (6–128 символов)', 'Password (6–128 characters)') }}</label>
      <input id="link-password" v-model="newPassword" :type="show ? 'text' : 'password'" autocomplete="new-password" minlength="6" maxlength="256" required />
      <label for="link-confirm">{{ label('Подтверждение пароля', 'Confirm password') }}</label>
      <input id="link-confirm" v-model="confirm" :type="show ? 'text' : 'password'" autocomplete="new-password" required />
      <label class="auth-checkbox"><input v-model="show" type="checkbox" />{{ label('Показать пароль', 'Show password') }}</label>
      <button type="submit" class="auth-primary" :disabled="busy">{{ label('Подтвердить по почте', 'Confirm by email') }}</button>
    </form>
    <form v-if="methodsKnown && !passwordEnabled && verificationPending" class="auth-form" @submit.prevent="verifyLink">
      <label v-if="!tokenFromLink" for="link-token">{{ label('Код из письма', 'Email verification code') }}</label>
      <input v-if="!tokenFromLink" id="link-token" v-model.trim="token" type="password" autocomplete="off" maxlength="64" required />
      <button type="submit" class="auth-primary" :disabled="busy">{{ label('Подключить пароль', 'Enable password') }}</button>
    </form>
    <section v-if="methodsKnown && passwordEnabled && !googleEnabled && googleAvailable" class="google-provider" aria-labelledby="google-provider-title">
      <h3 id="google-provider-title">{{ label('Вход через Google', 'Sign in with Google') }}</h3>
      <p class="hint">{{ label('Добавь Google как ещё один способ входа в этот же профиль. Вход по паролю сохранится.', 'Add Google as another way to sign in to this profile. Password sign-in will remain available.') }}</p>
      <button ref="googleLinkButton" v-if="!googleLinkOpen" type="button" class="action-button secondary-button" :disabled="busy" aria-expanded="false" aria-controls="google-link-form" @click="openGoogleLink">{{ label('Добавить вход через Google', 'Add Google sign-in') }}</button>
      <form v-else id="google-link-form" class="auth-form" @submit.prevent="linkGoogle">
        <p class="hint" id="google-link-help">{{ label('Сначала подтверди свою учётную запись Guitar Mentor AI. Затем откроется страница Google для выбора аккаунта. Пароль от почты здесь вводить не нужно.', 'First confirm your Guitar Mentor AI account. Then Google will open so you can choose an account. Do not enter your email password here.') }}</p>
        <label for="google-link-identifier">{{ label('Email или логин в Guitar Mentor AI', 'Guitar Mentor AI email or username') }}</label>
        <input ref="googleIdentifierInput" id="google-link-identifier" name="google-link-username" v-model.trim="identifier" autocomplete="section-google-link username" aria-describedby="google-link-help" required />
        <label for="google-link-password">{{ label('Пароль Guitar Mentor AI', 'Guitar Mentor AI password') }}</label>
        <input id="google-link-password" name="google-link-password" v-model="googlePassword" type="password" autocomplete="section-google-link current-password" aria-describedby="google-link-help" required />
        <button type="submit" class="auth-primary" :disabled="busy" :aria-busy="busy">{{ label('Продолжить в Google', 'Continue to Google') }}</button>
        <button type="button" class="text-button" :disabled="busy" @click="cancelGoogleLink">{{ label('Отмена', 'Cancel') }}</button>
      </form>
    </section>
    <p v-if="notice" class="success" role="status">{{ notice }}</p>
    <p v-if="error" class="auth-error" role="alert">{{ error }}</p>
    <button v-if="!methodsKnown" type="button" class="action-button secondary-button" @click="loadMethods">{{ label('Повторить', 'Retry') }}</button>
  </section>
</template>
