<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, ref } from 'vue';
import { useI18n } from 'vue-i18n';
import { auth, clearActor, login, refreshActor, secureUi } from './auth';
const { t, locale } = useI18n();
const localPrototype = !secureUi;
type Mode = 'login' | 'register' | 'verify' | 'forgot' | 'reset' | 'google';
const mode = ref<Mode>('login');
const email = ref('');
const username = ref('');
const identifier = ref('');
const password = ref('');
const confirmPassword = ref('');
const showPassword = ref(false);
const busy = ref(false);
const error = ref('');
const success = ref('');
const verificationToken = ref('');
const tokenFromLink = ref(false);
const identifierInput = ref<HTMLInputElement | null>(null);
const tokenPattern = /^[a-f0-9]{64}$/;
function clearTokenLikeIdentifier(): void {
  // Browser password managers sometimes populate a one-time code into the username field.
  if (tokenPattern.test(identifier.value)) identifier.value = '';
}

const label = (ru: string, en: string) => locale.value === 'ru' ? ru : en;
function setMode(next: Mode) {
  mode.value = next; error.value = ''; success.value = '';
  password.value = ''; confirmPassword.value = '';
  if (next !== 'verify' && next !== 'reset') {
    verificationToken.value = ''; tokenFromLink.value = false;
  }
}
async function request(path: string, body: object): Promise<boolean> {
  if (busy.value) return false;
  busy.value = true; error.value = ''; success.value = '';
  try {
    const response = await fetch('/api/v1/local-auth/' + path, {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body), credentials: 'same-origin',
    });
    if (!response.ok) {
      error.value = response.status === 429
        ? label('Слишком много попыток. Попробуйте позже.', 'Too many attempts. Try later.')
        : label('Не удалось выполнить действие. Проверьте данные или ссылку.', 'Request failed. Check the details or link.');
      return false;
    }
    return true;
  } catch {
    error.value = label('Ошибка сети. Повторите попытку.', 'Network error. Please retry.');
    return false;
  } finally { busy.value = false; }
}
async function localLogin() {
  clearTokenLikeIdentifier();
  if (!identifier.value) {
    error.value = label('Введите email или логин.', 'Enter your email or username.');
    return;
  }
  if (await request('login', { identifier: identifier.value, password: password.value })) {
    password.value = '';
    await refreshActor();
  }
}
async function register() {
  if (password.value !== confirmPassword.value) {
    error.value = label('Пароли не совпадают.', 'Passwords do not match.'); return;
  }
  if (await request('register', {
    email: email.value, login: username.value,
    password: password.value, confirmPassword: confirmPassword.value,
  })) {
    password.value = ''; confirmPassword.value = '';
    setMode('verify');
    success.value = label('Если данные доступны для регистрации, письмо с подтверждением отправлено.', 'If registration can proceed, a verification email has been sent.');
  }
}
async function verify() {
  if (await request('verify', { token: verificationToken.value })) {
    verificationToken.value = '';
    tokenFromLink.value = false;
    setMode('login');
    success.value = label('Почта подтверждена. Теперь можно войти.', 'Email verified. You can sign in.');
  }
}
async function forgot() {
  if (await request('request-reset', { email: email.value })) {
    setMode('login');
    success.value = label('Если адрес зарегистрирован, письмо отправлено.', 'If this email is registered, a message has been sent.');
  }
}
async function reset() {
  if (password.value !== confirmPassword.value) {
    error.value = label('Пароли не совпадают.', 'Passwords do not match.'); return;
  }
  if (await request('reset', { token: verificationToken.value, password: password.value })) {
    verificationToken.value = ''; tokenFromLink.value = false;
    identifier.value = ''; email.value = '';
    clearActor();
    setMode('login');
    await nextTick();
    if (identifierInput.value) identifierInput.value.value = '';
    success.value = label('Пароль изменён. Войдите снова.', 'Password changed. Please sign in.');
  }
}
function consumeAuthFragment(): void {
  const url = new URL(window.location.href);
  const fragment = new URLSearchParams(url.hash.slice(1));
  const action = fragment.get('authAction');
  const token = fragment.get('token');
  if (action !== 'verify' && action !== 'reset') return;
  // Always remove authentication secrets from the visible URL, including malformed tokens.
  // Hash-only navigation in an already-running Vue app doesn't remount AuthGate.
  fragment.delete('token');
  fragment.delete('authAction');
  window.history.replaceState(window.history.state, '', url.pathname + url.search
    + (fragment.toString() ? '#' + fragment.toString() : ''));
  if (!token || !tokenPattern.test(token)) {
    verificationToken.value = '';
    tokenFromLink.value = false;
    error.value = label('Ссылка недействительна. Запросите новую.', 'Invalid link. Request another.');
    setMode(action);
    error.value = label('Ссылка недействительна. Запросите новую.', 'Invalid link. Request another.');
    return;
  }
  verificationToken.value = token;
  tokenFromLink.value = true;
  identifier.value = '';
  password.value = '';
  confirmPassword.value = '';
  error.value = '';
  success.value = '';
  mode.value = action;
}
onMounted(() => {
  consumeAuthFragment();
  window.addEventListener('hashchange', consumeAuthFragment);
  if (!localPrototype) void refreshActor();
});
onBeforeUnmount(() => window.removeEventListener('hashchange', consumeAuthFragment));
</script>
<template>
  <slot v-if="localPrototype || (auth.ready && auth.actor && !(mode === 'reset' && tokenFromLink))" />
  <main v-else-if="auth.ready || (mode === 'reset' && tokenFromLink)" class="main-content auth-page" aria-live="polite">
    <section class="auth-panel">
      <h1>{{ auth.expired ? t('auth.expired') : t('auth.welcome') }}</h1>
      <p>{{ label('Создайте аккаунт или войдите в Guitar Mentor AI.', 'Create an account or sign in to Guitar Mentor AI.') }}</p>
      <div class="auth-mode-switch">
        <button type="button" class="auth-secondary" @click="setMode('login')">{{ label('Вход', 'Sign in') }}</button>
        <button type="button" class="auth-secondary" @click="setMode('register')">{{ label('Регистрация', 'Register') }}</button>
        <button type="button" class="auth-secondary" @click="setMode('google')">Google</button>
      </div>
      <form v-if="mode === 'login'" class="auth-form" @submit.prevent="localLogin">
        <label for="local-identifier">{{ label('Email или логин', 'Email or username') }}</label>
        <input id="local-identifier" ref="identifierInput" v-model.trim="identifier" autocomplete="username" required maxlength="320" @input="clearTokenLikeIdentifier" />
        <label for="local-password">{{ label('Пароль', 'Password') }}</label>
        <input id="local-password" v-model="password" :type="showPassword ? 'text' : 'password'" autocomplete="current-password" required />
        <label class="auth-checkbox"><input v-model="showPassword" type="checkbox" />{{ label('Показать пароль', 'Show password') }}</label>
        <button type="submit" class="auth-primary" :disabled="busy" :aria-busy="busy">{{ label('Войти', 'Sign in') }}</button>
        <button type="button" class="auth-link" @click="setMode('forgot')">{{ label('Забыли пароль?', 'Forgot password?') }}</button>
      </form>
      <form v-else-if="mode === 'register'" class="auth-form" @submit.prevent="register">
        <label for="registration-email">Email</label>
        <input id="registration-email" v-model.trim="email" type="email" autocomplete="email" maxlength="320" required />
        <label for="registration-login">{{ label('Логин', 'Username') }}</label>
        <input id="registration-login" v-model.trim="username" autocomplete="username" maxlength="64" minlength="3" required />
        <label for="registration-password">{{ label('Пароль (12–128 символов)', 'Password (12–128 characters)') }}</label>
        <input id="registration-password" v-model="password" :type="showPassword ? 'text' : 'password'" autocomplete="new-password" minlength="12" maxlength="128" required />
        <label for="registration-confirm">{{ label('Повторите пароль', 'Confirm password') }}</label>
        <input id="registration-confirm" v-model="confirmPassword" :type="showPassword ? 'text' : 'password'" autocomplete="new-password" required />
        <label class="auth-checkbox"><input v-model="showPassword" type="checkbox" />{{ label('Показать пароль', 'Show password') }}</label>
        <button class="auth-primary" type="submit" :disabled="busy" :aria-busy="busy">{{ label('Зарегистрироваться', 'Register') }}</button>
      </form>
      <form v-else-if="mode === 'verify'" class="auth-form" @submit.prevent="verify">
        <label v-if="!tokenFromLink" for="verify-token">{{ label('Код подтверждения из письма', 'Verification code from email') }}</label>
        <input v-if="!tokenFromLink" id="verify-token" v-model.trim="verificationToken" autocomplete="one-time-code" maxlength="64" required />
        <button class="auth-primary" type="submit" :disabled="busy">{{ label('Подтвердить', 'Verify') }}</button>
      </form>
      <form v-else-if="mode === 'forgot'" class="auth-form" @submit.prevent="forgot">
        <label for="forgot-email">Email</label>
        <input id="forgot-email" v-model.trim="email" type="email" autocomplete="email" required />
        <button class="auth-primary" type="submit" :disabled="busy">{{ label('Отправить письмо', 'Send email') }}</button>
      </form>
      <form v-else-if="mode === 'reset'" class="auth-form" @submit.prevent="reset">
        <label v-if="!tokenFromLink" for="reset-token">{{ label('Код восстановления', 'Reset code') }}</label>
        <input v-if="!tokenFromLink" id="reset-token" v-model.trim="verificationToken" autocomplete="one-time-code" maxlength="64" required />
        <label for="reset-password">{{ label('Новый пароль', 'New password') }}</label>
        <input id="reset-password" v-model="password" type="password" autocomplete="new-password" minlength="12" required />
        <label for="reset-confirm">{{ label('Подтверждение пароля', 'Confirm password') }}</label>
        <input id="reset-confirm" v-model="confirmPassword" type="password" autocomplete="new-password" required />
        <button class="auth-primary" type="submit" :disabled="busy">{{ label('Сменить пароль', 'Reset password') }}</button>
      </form>
      <section v-else class="auth-form">
        <button type="button" class="auth-primary" @click="login">{{ t('auth.signIn') }}</button>

      </section>
      <p v-if="success" class="success" role="status">{{ success }}</p>
      <p v-if="error" class="auth-error" role="alert">{{ error }}</p>
    </section>
  </main>
  <main v-else class="main-content" aria-live="polite">{{ t('auth.loading') }}</main>
</template>
