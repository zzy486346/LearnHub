import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', redirect: '/courses' },
    { path: '/login', component: () => import('@/views/LoginView.vue'), meta: { guest: true } },
    { path: '/register', component: () => import('@/views/RegisterView.vue'), meta: { guest: true } },
    { path: '/courses', component: () => import('@/views/CourseListView.vue') },
    { path: '/courses/:courseId/lessons/:lessonId', component: () => import('@/views/LessonView.vue') },
    { path: '/courses/:id', component: () => import('@/views/CourseDetailView.vue') },
    { path: '/questions', component: () => import('@/views/QuestionView.vue') },
    { path: '/questions/:id', component: () => import('@/views/QuestionDetailView.vue') },
    { path: '/coupons', component: () => import('@/views/CouponView.vue') },
    { path: '/profile', component: () => import('@/views/ProfileView.vue'), meta: { requiresAuth: true } },
    { path: '/admin', component: () => import('@/views/AdminView.vue'), meta: { requiresAuth: true, requiresAdmin: true } },
    { path: '/:pathMatch(.*)*', redirect: '/courses' },
  ],
  scrollBehavior: () => ({ top: 0 }),
})

router.beforeEach(async (to) => {
  if (to.meta.requiresAuth && !localStorage.getItem('learnhub_access_token')) {
    return { path: '/login', query: { redirect: to.fullPath } }
  }
  if (to.meta.requiresAdmin) {
    const auth = useAuthStore()
    if (!auth.user) {
      try { await auth.fetchMe() } catch { return { path: '/login', query: { redirect: to.fullPath } } }
    }
    if (!auth.isAdmin) return { path: '/courses' }
  }
})

export default router
