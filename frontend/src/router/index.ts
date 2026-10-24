import { createRouter, createWebHistory } from 'vue-router';
import { captureEmailActionLink } from '../core/auth/auth-link';
import LearnerProfileView from '../entities/learner/LearnerProfileView.vue';
import OnboardingView from '../entities/onboarding/OnboardingView.vue';
import DiagnosticView from '../entities/diagnostic/DiagnosticView.vue';
import HomeView from '../core/home/HomeView.vue';

// This must run BEFORE createWebHistory snapshots location and its hash.
// Capturing only in main.ts is too late: ESM evaluates router imports first.
captureEmailActionLink();

export const router = createRouter({
  history: createWebHistory(),
  scrollBehavior: (_to, _from, savedPosition) => savedPosition ?? { top: 0 },
  routes: [
    { path: '/', name: 'home', component: HomeView },
    { path: '/profile', name: 'learner-profile', component: LearnerProfileView },
    { path: '/onboarding', name: 'onboarding', component: OnboardingView },
    { path: '/diagnostics', name: 'diagnostics', component: DiagnosticView },
    { path: '/:pathMatch(.*)*', redirect: '/' },
  ],
});
