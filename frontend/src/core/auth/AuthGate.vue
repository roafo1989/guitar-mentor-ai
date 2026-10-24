<script setup lang="ts">
import { onMounted } from 'vue';
import { auth, login, refreshActor } from './auth';
onMounted(() => { void refreshActor(); });
</script>
<template>
  <slot v-if="auth.ready && auth.actor" />
  <main v-else-if="auth.ready" class="main-content" aria-live="polite">
    <h1>{{ auth.expired ? 'Сессия завершена / Session expired' : 'Вход / Sign in' }}</h1>
    <p>Доступ предоставляется только приглашённым пользователям / Invited accounts only.</p>
    <button type="button" @click="login">Войти / Sign in</button>
  </main>
  <main v-else class="main-content" aria-live="polite">Loading…</main>
</template>
