import { createPinia } from 'pinia';
import { createApp } from 'vue';
import App from './App.vue';
import { i18n } from './core/i18n';
import { router } from './router';
import './assets/design-tokens.css';
import './assets/main.css';
import './assets/light-minimal.css';
import './assets/learner-experience.css';
import { installAuthenticatedFetch } from './core/auth/auth';
import { captureEmailActionLink } from './core/auth/auth-link';
import { useLearnerStore } from './stores/learner.store';

// Remove sensitive email token from URL before router installation can snapshot it.
captureEmailActionLink();
const app = createApp(App);
app.use(createPinia());
installAuthenticatedFetch(() => useLearnerStore().clearLocalSelection());
app.use(router);
app.use(i18n);
app.mount('#app');
