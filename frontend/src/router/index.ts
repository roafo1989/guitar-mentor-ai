import { createRouter, createWebHistory } from 'vue-router';
import LearnerProfileView from '../entities/learner/LearnerProfileView.vue';

export const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', redirect: '/profile' },
    { path: '/profile', name: 'learner-profile', component: LearnerProfileView },
    { path: '/:pathMatch(.*)*', redirect: '/profile' },
  ],
});
