<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import { auth } from './auth';
import { isValidLogin, normalizeLogin } from './credential-policy';
import { useUnsavedChanges } from '../../shared/unsaved-changes';
const { t } = useI18n();
const currentLogin = ref<string | null>(null);
const draft = ref('');
const password = ref('');
const loading = ref(true);
const loaded = ref(false);
const busy = ref(false);
const editing = ref(false);
const saved = ref(false);
const error = ref('');
const accountId = auth.actor?.accountId;
let active = true;
let generation = 0;
const current = () => active && auth.actor?.accountId === accountId;
const dirty = computed(() => editing.value && (draft.value !== currentLogin.value || password.value !== ''));
useUnsavedChanges(dirty, () => t('profileUx.discardPrompt'));
watch(() => auth.actor?.accountId, next => {
  if (next === accountId) return;
  ++generation; password.value = ''; draft.value = ''; currentLogin.value = null;
  editing.value = false; loaded.value = false; loading.value = false; busy.value = false; saved.value = false; error.value = '';
});
onBeforeUnmount(() => { active = false; ++generation; password.value = ''; draft.value = ''; });
async function load(): Promise<void> {
  const seq = ++generation; loading.value = true; loaded.value = false; error.value = '';
  try {
    const response = await fetch('/api/v1/local-auth/username', { cache: 'no-store' });
    if (!response.ok) throw new Error('load');
    const result = await response.json();
    if (!result || (result.login !== null && typeof result.login !== 'string')) throw new Error('invalid');
    if (!current() || seq !== generation) return;
    currentLogin.value = result.login; draft.value = result.login ?? ''; loaded.value = true;
  } catch { if (current() && seq === generation) error.value = 'accountLogin.loadError'; }
  finally { if (current() && seq === generation) loading.value = false; }
}
function edit(): void { draft.value = currentLogin.value ?? ''; password.value = ''; saved.value = false; error.value = ''; editing.value = true; }
function cancel(): void { password.value = ''; draft.value = currentLogin.value ?? ''; editing.value = false; error.value = ''; }
async function save(): Promise<void> {
  if (!current() || !loaded.value || !currentLogin.value || busy.value) return;
  error.value = ''; saved.value = false;
  if (!isValidLogin(draft.value) || !password.value) { error.value = 'accountLogin.invalid'; return; }
  const seq = ++generation; busy.value = true;
  try {
    const response = await fetch('/api/v1/local-auth/username', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ newLogin: normalizeLogin(draft.value), currentPassword: password.value }) });
    if (!current() || seq !== generation) return;
    if (!response.ok) {
      error.value = response.status === 409 ? 'accountLogin.unavailable' : response.status === 429 ? 'accountLogin.rateLimited' : 'accountLogin.saveError';
      return;
    }
    const result = await response.json();
    if (!current() || seq !== generation) return;
    if (!result || typeof result.login !== 'string' || !isValidLogin(result.login)) throw new Error('invalid');
    currentLogin.value = result.login; draft.value = result.login; password.value = ''; editing.value = false; saved.value = true;
  } catch { if (current() && seq === generation) error.value = 'accountLogin.saveError'; }
  finally { if (current() && seq === generation) busy.value = false; }
}
onMounted(() => { void load(); });
</script>
<template>
  <section class="panel username-settings" aria-labelledby="account-login-title">
    <div class="page-heading"><div><h2 id="account-login-title">{{ t('accountLogin.title') }}</h2><p v-if="loaded && currentLogin" class="account-login-value">{{ currentLogin }}</p></div><button v-if="loaded && currentLogin && !editing" type="button" class="action-button secondary-button" @click="edit">{{ t('profileUx.editShort') }}</button></div>
    <p v-if="loading" role="status">{{ t('accountLogin.loading') }}</p>
    <p v-else-if="loaded && !currentLogin" class="hint">{{ t('accountLogin.noPassword') }}</p>
    <p v-else-if="loaded && !editing" class="hint">{{ t('accountLogin.purpose') }}</p>
    <form v-if="editing && loaded" @submit.prevent="save">
      <fieldset class="plain-fieldset" :disabled="busy">
        <label class="field"><span>{{ t('accountLogin.newLogin') }}</span><input id="account-new-login" v-model="draft" autocomplete="username" required minlength="3" maxlength="128" aria-describedby="account-login-help" /></label>
        <p id="account-login-help" class="hint username-help">{{ t('accountLogin.rules') }}</p>
        <label class="field"><span>{{ t('accountLogin.password') }}</span><input id="account-login-password" v-model="password" type="password" autocomplete="section-username-change current-password" maxlength="256" required /></label>
        <div class="form-actions"><button type="submit" :disabled="busy" :aria-busy="busy">{{ busy ? t('form.saving') : t('onboarding.saveShort') }}</button><button type="button" class="text-button" @click="cancel">{{ t('profileUx.cancel') }}</button></div>
      </fieldset>
    </form>
    <p v-if="error" role="alert" class="error">{{ t(error) }}</p>
    <button v-if="!loaded && !loading" type="button" class="text-button" @click="load">{{ t('home.retry') }}</button>
    <p v-if="saved" class="saved-confirmation" role="status">✓ {{ t('accountLogin.saved') }}</p>
  </section>
</template>
