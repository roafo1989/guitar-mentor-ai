import { createPinia } from 'pinia';
import { createApp } from 'vue';
import App from './App.vue';
import { i18n } from './core/i18n';
import { router } from './router';
import './assets/main.css';
import { installAuthenticatedFetch } from './core/auth/auth';
import { useLearnerStore } from './stores/learner.store';

const app = createApp(App);
app.use(createPinia());
installAuthenticatedFetch(() => useLearnerStore().clearLocalSelection());
app.use(router);
app.use(i18n);
app.mount('#app');
