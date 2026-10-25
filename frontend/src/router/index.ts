import { createRouter, createWebHistory } from 'vue-router';
import { captureEmailActionLink } from '../core/auth/auth-link';
import { captureOAuthBridgeTicket } from '../core/auth/oauth-bridge-ticket';
import LearnerProfileView from '../entities/learner/LearnerProfileView.vue';
import OnboardingView from '../entities/onboarding/OnboardingView.vue';
import DiagnosticView from '../entities/diagnostic/DiagnosticView.vue';
import HomeView from '../core/home/HomeView.vue';
import LibraryView from '../entities/library/LibraryView.vue';

// Scrub one-time email and OAuth tickets before Vue Router snapshots browser history.
captureOAuthBridgeTicket();
captureEmailActionLink();

export const router = createRouter({
  history: createWebHistory(),
  scrollBehavior: (_to, _from, savedPosition) => savedPosition ?? { top: 0 },
  routes: [
    { path: '/', name: 'home', component: HomeView },
    { path: '/profile', name: 'learner-profile', component: LearnerProfileView },
    { path: '/onboarding', name: 'onboarding', component: OnboardingView },
    { path: '/diagnostics', name: 'diagnostics', component: DiagnosticView },
    { path: '/lessons/new', name: 'lesson-next', component: () => import('../entities/diagnostic/NextLessonView.vue') },
    { path: '/lessons', name: 'lesson-history', component: () => import('../entities/diagnostic/LessonHistoryView.vue') },
    { path: '/lessons/:sessionId', name: 'lesson-detail', component: () => import('../entities/diagnostic/LessonHistoryView.vue') },
    { path: '/library', name: 'library', component: LibraryView },
    { path: '/music-archive', name: 'music-archive', component: () => import('../entities/music-archive/MusicArchiveView.vue') },
    { path: '/:pathMatch(.*)*', redirect: '/' },
  ],
});
