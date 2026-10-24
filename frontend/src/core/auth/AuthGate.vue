<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, ref } from 'vue';
import { useI18n } from 'vue-i18n';
import { auth, clearActor, login, refreshActor, secureUi } from './auth';
import { captureEmailActionLink, takeEmailActionLink } from './auth-link';
import { captureOAuthBridgeTicket, takeOAuthBridgeTicket } from './oauth-bridge-ticket';
const { t, locale } = useI18n();
const localPrototype = !secureUi;
type Mode = 'login' | 'register' | 'check-mail' | 'verify' | 'forgot' | 'reset' | 'expired-link';
const mode = ref<Mode>('login');
const email = ref('');
const username = ref('');
const identifier = ref('');
const password = ref('');
const confirmPassword = ref('');
const showPassword = ref(false);
const showConfirm = ref(false);
const manualCode = ref(false);
const mailPurpose = ref<'register' | 'reset'>('register');
const heading = ref<HTMLElement | null>(null);
const requestEpoch = ref(0);
let mounted = false;
const oauthPending = ref(false);
const busy = ref(false);
const error = ref('');
const success = ref('');
const verificationToken = ref('');
const tokenFromLink = ref(false);
const emailActionInProgress = ref(false);
const identifierInput = ref<HTMLInputElement | null>(null);
const tokenPattern = /^[a-f0-9]{64}$/;
function clearTokenLikeIdentifier(): void {
  // Browser password managers sometimes populate a one-time code into the username field.
  if (tokenPattern.test(identifier.value)) identifier.value = '';
}

const label = (ru: string, en: string) => locale.value === 'ru' ? ru : en;
function setMode(next: Mode) {
  if (busy.value) return;
  requestEpoch.value++;
  mode.value = next; error.value = ''; success.value = '';
  emailActionInProgress.value = next === 'verify' || next === 'reset' || next === 'expired-link';
  password.value = ''; confirmPassword.value = ''; showPassword.value = false; showConfirm.value = false; manualCode.value = false;
  if (next !== 'verify' && next !== 'reset') {
    verificationToken.value = ''; tokenFromLink.value = false;
  }
  void nextTick(() => heading.value?.focus());
}
async function request(path: string, body: object): Promise<boolean> {
  if (busy.value) return false;
  busy.value = true; error.value = ''; success.value = '';
  const epoch = ++requestEpoch.value;
  try {
    const response = await fetch('/api/v1/local-auth/' + path, {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body), credentials: 'same-origin',
    });
    if (!response.ok) {
      if (!mounted || epoch !== requestEpoch.value) return false;
      if (response.status === 400 && (path === 'verify' || path === 'reset')) {
        mailPurpose.value = path === 'reset' ? 'reset' : 'register';
        mode.value = 'expired-link';
        verificationToken.value = ''; tokenFromLink.value = false;
        emailActionInProgress.value = true;
        void nextTick(() => heading.value?.focus());
        return false;
      }
      error.value = response.status === 429
        ? label('Слишком много попыток. Попробуйте позже.', 'Too many attempts. Try later.')
        : label('Не удалось выполнить действие. Проверьте данные или ссылку.', 'Request failed. Check the details or link.');
      return false;
    }
    return mounted && epoch === requestEpoch.value;
  } catch {
    if (!mounted || epoch !== requestEpoch.value) return false;
    error.value = label('Ошибка сети. Повторите попытку.', 'Network error. Please retry.');
    return false;
  } finally { if (mounted && epoch === requestEpoch.value) busy.value = false; }
}
async function localLogin() {
  if (busy.value || oauthPending.value) return;
  clearTokenLikeIdentifier();
  if (!identifier.value || !password.value) {
    error.value = label('Введите email или логин.', 'Enter your email or username.');
    return;
  }
  const epoch = requestEpoch.value + 1;
  if (await request('login', { identifier: identifier.value, password: password.value })) {
    password.value = '';
    await refreshActor();
    if (!mounted || epoch !== requestEpoch.value) return;
    if (!auth.actor) error.value = label('Не удалось завершить вход. Попробуйте ещё раз.', 'Could not complete sign-in. Please retry.');
  }
}
async function register() {
  if (busy.value || oauthPending.value) return;
  if (!/^[a-z0-9][a-z0-9._-]{2,63}$/.test(username.value.toLowerCase())) { error.value = label('Проверьте логин: 3–64 латинских символа, цифры, точка, _ или -.', 'Check username: 3–64 Latin letters, digits, dot, _ or -.'); return; }
  if (password.value.length < 12 || password.value.length > 128) { error.value = label('Пароль должен содержать 12–128 символов.', 'Password must be 12–128 characters.'); return; }
  if (password.value !== confirmPassword.value) {
    error.value = label('Пароли не совпадают.', 'Passwords do not match.'); return;
  }
  if (await request('register', {
    email: email.value, login: username.value,
    password: password.value, confirmPassword: confirmPassword.value,
  })) {
    password.value = ''; confirmPassword.value = '';
    mailPurpose.value = 'register';
    setMode('check-mail');
  }
}
async function verify() {
  if (busy.value || !tokenPattern.test(verificationToken.value)) {
    if (!busy.value) error.value = label('Код подтверждения недействителен.', 'Invalid verification code.');
    return;
  }
  if (await request('verify', { token: verificationToken.value })) {
    verificationToken.value = '';
    tokenFromLink.value = false;
    setMode('login');
    success.value = label('Почта подтверждена. Теперь можно войти.', 'Email verified. You can sign in.');
  }
}
async function forgot() {
  if (busy.value) return;
  if (await request('request-reset', { email: email.value })) {
    mailPurpose.value = 'reset';
    setMode('check-mail');
  }
}
async function reset() {
  if (busy.value) return;
  if (!tokenPattern.test(verificationToken.value)) { error.value = label('Код восстановления недействителен.', 'Invalid reset code.'); return; }
  if (password.value.length < 12 || password.value.length > 128) { error.value = label('Пароль должен содержать 12–128 символов.', 'Password must be 12–128 characters.'); return; }
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
  // On initial load main.ts already scrubbed the token before router install.
  // For in-app hash navigation capture immediately, then consume in component state.
  captureEmailActionLink();
  const link = takeEmailActionLink();
  if (!link) return;
  // Navigation to an email action invalidates any response from the previous form.
  requestEpoch.value++; busy.value = false;
  emailActionInProgress.value = true;
  if (!link.token || !tokenPattern.test(link.token)) {
    mailPurpose.value = link.action === 'reset' ? 'reset' : 'register';
    mode.value = 'expired-link';
    verificationToken.value = ''; tokenFromLink.value = false;
    password.value = ''; confirmPassword.value = '';
    error.value = ''; success.value = '';
    void nextTick(() => heading.value?.focus());
    return;
  }
  verificationToken.value = link.token;
  mailPurpose.value = link.action === 'reset' ? 'reset' : 'register';
  tokenFromLink.value = true;
  identifier.value = '';
  password.value = '';
  confirmPassword.value = '';
  error.value = '';
  success.value = '';
  mode.value = link.action;
  void nextTick(() => heading.value?.focus());
}
function handleGoogle(): void {
  if (busy.value || oauthPending.value || !mounted) return;
  oauthPending.value = true;
  password.value = ''; confirmPassword.value = '';
  login();
}
function consumeGoogleError(): void {
  const url = new URL(window.location.href);
  const marker = url.searchParams.get('auth');
  if (!marker || !['denied', 'failed', 'google_failed', 'bridge_start_failed'].includes(marker)) return;
  url.searchParams.delete('auth');
  window.history.replaceState(window.history.state, '', url.pathname + url.search + url.hash);
  error.value = label('Не удалось войти через Google. Попробуйте снова или войдите по email.', 'Could not sign in with Google. Try again or sign in with email.');
}
function onPageShow(event: PageTransitionEvent): void {
  // A restored page never retains a disabled OAuth button or a secret.
  if (event.persisted) { oauthPending.value = false; password.value = ''; confirmPassword.value = ''; }
}

onMounted(() => {
  mounted = true;
  consumeAuthFragment();
  consumeGoogleError();
  window.addEventListener('pageshow', onPageShow);
  window.addEventListener('hashchange', consumeAuthFragment);
  if (!localPrototype) {
    void (async () => {
      captureOAuthBridgeTicket();
      const ticket = takeOAuthBridgeTicket();
      if (ticket) {
        try {
          if (!tokenPattern.test(ticket)) throw new Error('INVALID_OAUTH_TICKET');
          const response = await fetch('/api/v1/oauth-bridge/consume', {
            method: 'POST', headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ ticket }), credentials: 'same-origin',
          });
          if (!response.ok) throw new Error('OAUTH_BRIDGE_FAILED');
        } catch {
          error.value = label('Не удалось завершить вход через Google. Попробуйте снова.',
            'Could not finish Google sign-in. Please try again.');
        }
      }
      await refreshActor();
    })();
  }
});
onBeforeUnmount(() => {
  mounted = false; requestEpoch.value++;
  password.value = ''; confirmPassword.value = ''; verificationToken.value = '';
  window.removeEventListener('hashchange', consumeAuthFragment);
  window.removeEventListener('pageshow', onPageShow);
});
</script>
<template>
  <slot v-if="localPrototype || (auth.ready && auth.actor && !emailActionInProgress)" />
  <main v-else-if="auth.ready || emailActionInProgress" class="main-content auth-page">
    <section class="auth-panel auth03-panel">
      <div class="auth03-brand">
        <img src="/brand/guitar-mentor-logo-128.png" width="56" height="56" alt="" aria-hidden="true" />
        <strong>Guitar Mentor AI</strong>
      </div>
      <h1 ref="heading" tabindex="-1">{{ mode === 'login' ? label('С возвращением!', 'Welcome back!') :
        mode === 'register' ? label('Создать аккаунт', 'Create an account') :
        mode === 'check-mail' ? label('Проверьте почту', 'Check your email') :
        mode === 'forgot' ? label('Восстановить пароль', 'Reset your password') :
        mode === 'reset' ? label('Новый пароль', 'Choose a new password') :
        mode === 'expired-link' ? label('Ссылка недействительна или истекла', 'This link is invalid or expired') :
        label('Подтвердите почту', 'Verify your email') }}</h1>
      <p class="auth03-intro">{{ mode === 'login' ? label('Войдите в Guitar Mentor AI', 'Sign in to Guitar Mentor AI') :
        mode === 'check-mail' ? (mailPurpose === 'register' ? label('Если регистрация доступна для этих данных, вы получите письмо с подтверждением. Проверьте входящие и спам.', 'If registration can proceed with these details, you will receive a verification email. Check your inbox and spam folder.') : label('Если такой аккаунт существует, вы получите письмо с инструкциями.', 'If an account exists, you will receive reset instructions.')) :
        mode === 'forgot' ? label('Введите email вашего аккаунта', 'Enter your account email') :
        mode === 'register' ? label('Начните заниматься с Guitar Mentor AI', 'Start learning with Guitar Mentor AI') : '' }}</p>
      <p v-if="auth.expired && mode === 'login'" class="auth03-note" role="status">{{ label('Сессия завершена. Войдите снова.', 'Your session has ended. Sign in again.') }}</p>
      <form v-if="mode === 'login'" class="auth-form auth03-form" @submit.prevent="localLogin">
        <label for="local-identifier">{{ label('Email или логин', 'Email or username') }}</label>
        <input id="local-identifier" ref="identifierInput" v-model.trim="identifier" autocomplete="username" required maxlength="320" @input="clearTokenLikeIdentifier" />
        <label for="local-password">{{ label('Пароль', 'Password') }}</label>
        <div class="auth03-password"><input id="local-password" v-model="password" :type="showPassword ? 'text' : 'password'" autocomplete="current-password" required /><button type="button" :aria-pressed="showPassword" :aria-label="showPassword ? label('Скрыть пароль', 'Hide password') : label('Показать пароль', 'Show password')" @click="showPassword = !showPassword">{{ showPassword ? '◉' : '◎' }}</button></div>
        <button class="auth-primary" type="submit" :disabled="busy || oauthPending" :aria-busy="busy">{{ busy ? t('auth.loading') : label('Войти', 'Sign in') }}</button>
        <button class="auth-link" type="button" :disabled="busy" @click="setMode('forgot')">{{ label('Забыли пароль?', 'Forgot password?') }}</button>
      </form>
      <form v-else-if="mode === 'register'" class="auth-form auth03-form" @submit.prevent="register">
        <label for="registration-email">Email</label><input id="registration-email" v-model.trim="email" type="email" autocomplete="email" maxlength="320" required />
        <label for="registration-login">{{ label('Логин', 'Username') }}</label>
        <input id="registration-login" v-model.trim="username" autocomplete="username" minlength="3" maxlength="64" pattern="[A-Za-z0-9][A-Za-z0-9._-]{2,63}" aria-describedby="auth03-login-help" required />
        <small id="auth03-login-help">{{ label('3–64: латинские буквы, цифры, точка, _ и -; первый символ — буква или цифра', '3–64: Latin letters, digits, dot, _ and -; first character must be a letter or digit') }}</small>
        <label for="registration-password">{{ label('Пароль', 'Password') }}</label>
        <div class="auth03-password"><input id="registration-password" v-model="password" :type="showPassword ? 'text' : 'password'" autocomplete="new-password" minlength="12" maxlength="128" aria-describedby="auth03-password-help" required /><button type="button" :aria-pressed="showPassword" :aria-label="showPassword ? label('Скрыть пароль', 'Hide password') : label('Показать пароль', 'Show password')" @click="showPassword = !showPassword">{{ showPassword ? '◉' : '◎' }}</button></div>
        <small id="auth03-password-help">{{ label('12–128 символов', '12–128 characters') }}</small>
        <label for="registration-confirm">{{ label('Повторите пароль', 'Confirm password') }}</label>
        <div class="auth03-password"><input id="registration-confirm" v-model="confirmPassword" :type="showConfirm ? 'text' : 'password'" autocomplete="new-password" minlength="12" maxlength="128" required /><button type="button" :aria-pressed="showConfirm" :aria-label="showConfirm ? label('Скрыть подтверждение', 'Hide confirmation') : label('Показать подтверждение', 'Show confirmation')" @click="showConfirm = !showConfirm">{{ showConfirm ? '◉' : '◎' }}</button></div>
        <button class="auth-primary" type="submit" :disabled="busy || oauthPending" :aria-busy="busy">{{ busy ? t('auth.loading') : label('Зарегистрироваться', 'Create account') }}</button>
      </form>
      <div v-if="mode === 'login' || mode === 'register'" class="auth03-google-block">
        <div class="auth03-divider"><span>{{ label('или', 'or') }}</span></div>
        <button class="auth03-google" type="button" :disabled="busy || oauthPending" :aria-busy="oauthPending" @click="handleGoogle"><img src="/vendor/google-signin/g-logo.png" width="20" height="20" alt="" aria-hidden="true"/><span>{{ label('Продолжить с Google', 'Continue with Google') }}</span></button>
        <button class="auth-link auth03-switch" type="button" :disabled="busy || oauthPending" @click="setMode(mode === 'login' ? 'register' : 'login')">{{ mode === 'login' ? label('Нет аккаунта? Зарегистрироваться', 'New here? Create an account') : label('Уже есть аккаунт? Войти', 'Already have an account? Sign in') }}</button>
      </div>
      <section v-else-if="mode === 'check-mail' || mode === 'expired-link'" class="auth03-actions">
        <button v-if="mode === 'expired-link' && mailPurpose === 'reset'" class="auth-primary" type="button" @click="setMode('forgot')">{{ label('Запросить новую ссылку', 'Request another link') }}</button>
        <button class="auth-link" type="button" @click="setMode('login')">{{ label('Вернуться ко входу', 'Back to sign in') }}</button>
        <button v-if="mode === 'check-mail' && mailPurpose === 'register'" class="auth-link" type="button" @click="setMode('register')">{{ label('Изменить данные регистрации', 'Change registration details') }}</button>
      </section>
      <form v-else-if="mode === 'verify'" class="auth-form auth03-form" @submit.prevent="verify">
        <button v-if="!tokenFromLink && !manualCode" type="button" class="auth-link" @click="manualCode = true">{{ label('Ввести код из письма', 'Enter code from email') }}</button>
        <template v-if="!tokenFromLink && manualCode"><label for="verify-token">{{ label('Код подтверждения из письма', 'Verification code from email') }}</label><input id="verify-token" v-model.trim="verificationToken" autocomplete="one-time-code" minlength="64" maxlength="64" pattern="[a-f0-9]{64}" required /></template>
        <button v-if="tokenFromLink || manualCode" class="auth-primary" type="submit" :disabled="busy" :aria-busy="busy">{{ label('Подтвердить email', 'Verify email') }}</button>
        <button class="auth-link" type="button" :disabled="busy" @click="setMode('login')">{{ label('Вернуться ко входу', 'Back to sign in') }}</button>
      </form>
      <form v-else-if="mode === 'forgot'" class="auth-form auth03-form" @submit.prevent="forgot">
        <label for="forgot-email">Email</label><input id="forgot-email" v-model.trim="email" type="email" autocomplete="email" maxlength="320" required />
        <button class="auth-primary" type="submit" :disabled="busy" :aria-busy="busy">{{ label('Отправить ссылку', 'Send reset link') }}</button>
        <button class="auth-link" type="button" :disabled="busy" @click="setMode('login')">{{ label('Вернуться ко входу', 'Back to sign in') }}</button>
      </form>
      <form v-else-if="mode === 'reset'" class="auth-form auth03-form" @submit.prevent="reset">
        <button v-if="!tokenFromLink && !manualCode" type="button" class="auth-link" @click="manualCode = true">{{ label('Ввести код из письма', 'Enter code from email') }}</button>
        <template v-if="!tokenFromLink && manualCode"><label for="reset-token">{{ label('Код восстановления', 'Reset code') }}</label><input id="reset-token" v-model.trim="verificationToken" autocomplete="one-time-code" minlength="64" maxlength="64" pattern="[a-f0-9]{64}" required /></template>
        <template v-if="tokenFromLink || manualCode">
          <label for="reset-password">{{ label('Новый пароль', 'New password') }}</label>
          <div class="auth03-password"><input id="reset-password" v-model="password" :type="showPassword ? 'text' : 'password'" autocomplete="new-password" minlength="12" maxlength="128" required /><button type="button" :aria-pressed="showPassword" :aria-label="showPassword ? label('Скрыть пароль', 'Hide password') : label('Показать пароль', 'Show password')" @click="showPassword = !showPassword">{{ showPassword ? '◉' : '◎' }}</button></div>
          <small>{{ label('12–128 символов', '12–128 characters') }}</small>
          <label for="reset-confirm">{{ label('Подтверждение пароля', 'Confirm password') }}</label>
          <div class="auth03-password"><input id="reset-confirm" v-model="confirmPassword" :type="showConfirm ? 'text' : 'password'" autocomplete="new-password" minlength="12" maxlength="128" required /><button type="button" :aria-pressed="showConfirm" :aria-label="showConfirm ? label('Скрыть подтверждение', 'Hide confirmation') : label('Показать подтверждение', 'Show confirmation')" @click="showConfirm = !showConfirm">{{ showConfirm ? '◉' : '◎' }}</button></div>
          <button class="auth-primary" type="submit" :disabled="busy" :aria-busy="busy">{{ label('Сохранить пароль', 'Save password') }}</button>
        </template>
      </form>
      <p v-if="success" class="success" role="status">{{ success }}</p>
      <p v-if="error" class="auth-error" role="alert">{{ error }}</p>
    </section>
  </main>
  <main v-else class="main-content" role="status">{{ label('Проверяем вход…', 'Checking your sign-in…') }}</main>
</template>