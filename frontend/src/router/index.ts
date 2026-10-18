import { createRouter, createWebHistory } from 'vue-router';
import LearnerProfileView from '../entities/learner/LearnerProfileView.vue';
import OnboardingView from '../entities/onboarding/OnboardingView.vue';
import DiagnosticView from '../entities/diagnostic/DiagnosticView.vue';
import HomeView from '../core/home/HomeView.vue';

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
