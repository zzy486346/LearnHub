<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { courseApi, orderApi } from '@/api'
import { useAuthStore } from '@/stores/auth'
import type { Course, CourseOrder, Lesson } from '@/types'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const course = ref<Course | null>(null)
const loading = ref(false)
const errorMessage = ref('')
const likeAnnouncement = ref('')
const creatingOrder = ref(false)
const paying = ref(false)
const orderDialogVisible = ref(false)
const activeOrder = ref<CourseOrder | null>(null)
const lessons = computed(() => course.value?.chapters?.flatMap((chapter) => chapter.lessons || []) ?? [])
const firstAccessibleLesson = computed(() => lessons.value.find((lesson) => lesson.accessible) ?? null)
const hasFullAccess = computed(() => course.value?.accessLevel === 'FULL')

async function loadCourse() {
  loading.value = true
  errorMessage.value = ''
  course.value = null
  try {
    course.value = (await courseApi.detail(String(route.params.id))).data.data
    if (auth.authenticated && course.value) {
      const [like, favorite] = await Promise.allSettled([
        courseApi.likeStatus(course.value.id),
        courseApi.favoriteStatus(course.value.id),
      ])
      if (like.status === 'fulfilled') {
        course.value.liked = like.value.data.data.liked
        course.value.likeCount = like.value.data.data.count
      }
      if (favorite.status === 'fulfilled') course.value.favorited = favorite.value.data.data
    }
  } catch {
    errorMessage.value = '课程加载失败，请检查网络连接后重试。'
  } finally {
    loading.value = false
  }
}

function openLesson(lesson: Lesson) {
  if (!auth.authenticated) {
    goToLogin()
    return
  }
  if (!lesson.accessible) {
    void openPurchase()
    return
  }
  if (course.value) void router.push(`/courses/${course.value.id}/lessons/${lesson.id}`)
}

function startLearning() {
  if (!auth.authenticated) {
    goToLogin()
    return
  }
  if (firstAccessibleLesson.value) {
    openLesson(firstAccessibleLesson.value)
    return
  }
  if (!hasFullAccess.value) void openPurchase()
}

function goToLogin() {
  void router.push({ path: '/login', query: { redirect: route.fullPath } })
}

async function openPurchase() {
  if (!course.value) return
  if (!auth.authenticated) {
    goToLogin()
    return
  }
  if (hasFullAccess.value) {
    ElMessage.info('你已购买该课程')
    return
  }
  creatingOrder.value = true
  try {
    activeOrder.value = (await orderApi.createCourseOrder(course.value.id)).data.data
    if (activeOrder.value.status === 'PAID') {
      await loadCourse()
      ElMessage.success('课程已解锁')
      return
    }
    orderDialogVisible.value = true
  } catch {
    ElMessage.error('订单创建失败，请稍后重试')
  } finally {
    creatingOrder.value = false
  }
}

async function payOrder() {
  if (!activeOrder.value) return
  paying.value = true
  try {
    activeOrder.value = (await orderApi.pay(activeOrder.value.id)).data.data
    orderDialogVisible.value = false
    ElMessage.success('支付成功，全部课程已解锁')
    await loadCourse()
  } catch {
    ElMessage.error('支付失败，请稍后重试')
  } finally {
    paying.value = false
  }
}
async function toggle(action: 'like' | 'favorite') {
  if (!course.value) return
  try {
    if (action === 'like') {
      const response = await (course.value.liked ? courseApi.unlike(course.value.id) : courseApi.like(course.value.id))
      course.value.liked = response.data.data.liked
      course.value.likeCount = response.data.data.count
      likeAnnouncement.value = `${course.value.liked ? '已喜欢' : '已取消喜欢'}，当前 ${course.value.likeCount} 人喜欢`
    } else {
      const response = await (course.value.favorited ? courseApi.unfavorite(course.value.id) : courseApi.favorite(course.value.id))
      course.value.favorited = response.data.data
    }
    ElMessage.success(action === 'like' ? likeAnnouncement.value : '收藏状态已更新')
  } catch { ElMessage.error('请先登录或稍后再试') }
}

watch(() => route.params.id, loadCourse, { immediate: true })
</script>

<template>
  <section v-loading="loading" class="course-detail-page">
    <div v-if="errorMessage && !loading" class="page-state-card" role="alert">
      <div class="state-icon" aria-hidden="true"><svg viewBox="0 0 24 24"><path d="M12 9v4m0 4h.01M10.3 3.7 2.6 17a2 2 0 0 0 1.7 3h15.4a2 2 0 0 0 1.7-3L13.7 3.7a2 2 0 0 0-3.4 0Z" /></svg></div>
      <h1>暂时无法打开课程</h1><p>{{ errorMessage }}</p>
      <el-button type="primary" @click="loadCourse">重新加载</el-button>
    </div>
    <template v-else-if="course">
      <div class="detail-hero">
        <div>
          <div class="tag-row"><el-tag v-for="tag in course.tags" :key="tag">{{ tag }}</el-tag></div>
          <h1>{{ course.title }}</h1><p>{{ course.subtitle || course.description }}</p>
          <p class="muted">讲师 · {{ course.teacherName || course.instructor || '问课讲师' }}</p>
          <div class="button-row">
            <el-button type="primary" size="large" :disabled="!lessons.length" @click="startLearning">{{ !lessons.length ? '暂无课时' : course.accessLevel === 'LOGIN_REQUIRED' ? '登录后学习' : firstAccessibleLesson ? '开始学习' : '购买后学习' }}</el-button>
            <el-button v-if="!hasFullAccess" class="purchase-button" size="large" :loading="creatingOrder" @click="openPurchase">{{ auth.authenticated ? `购买课程 ¥${Number(course.price || 0).toFixed(2)}` : '登录后购买' }}</el-button>
            <el-tag v-else class="purchased-tag" size="large" effect="dark" type="success">已购买 · 全部课时可学</el-tag>
            <el-button size="large" :aria-pressed="course.liked" @click="toggle('like')">{{ course.liked ? '已喜欢' : '喜欢' }} {{ course.likeCount || 0 }}</el-button>
            <el-button size="large" @click="toggle('favorite')">{{ course.favorited ? '已收藏' : '收藏' }}</el-button>
          </div>
          <span class="sr-only" role="status" aria-atomic="true">{{ likeAnnouncement }}</span>
        </div>
        <div class="detail-art">LEARN<br />WITHOUT<br />LIMITS</div>
      </div>
      <div class="detail-grid">
        <article class="content-card course-content-card">
          <h2>课程介绍</h2><p class="course-description">{{ course.description || '讲师正在完善课程介绍。' }}</p>
          <div class="catalog-heading"><div><h2>课程目录</h2><p>{{ course.chapters?.length || 0 }} 个章节 · {{ lessons.length }} 个课时</p></div></div>
          <el-collapse v-if="course.chapters?.length">
            <el-collapse-item v-for="chapter in course.chapters" :key="chapter.id" :title="chapter.title">
              <button v-for="lesson in chapter.lessons" :key="lesson.id" class="lesson" :class="{ locked: !lesson.accessible }" type="button" @click="openLesson(lesson)">
                <span class="lesson-main"><span class="lesson-play" aria-hidden="true"><svg viewBox="0 0 24 24"><path :d="lesson.accessible ? 'm8 5 11 7-11 7V5Z' : 'M7 11V8a5 5 0 0 1 10 0v3m-9 0h8a2 2 0 0 1 2 2v6H6v-6a2 2 0 0 1 2-2Z'" /></svg></span><span>{{ lesson.title }}</span><el-tag v-if="lesson.freePreview" size="small">试看</el-tag><el-tag v-else-if="!lesson.accessible" size="small" type="info">购买后解锁</el-tag></span>
                <span class="lesson-duration">{{ Math.ceil((lesson.durationSeconds || 0) / 60) }} 分钟</span>
              </button>
            </el-collapse-item>
          </el-collapse>
          <el-empty v-else description="课程目录正在准备中" :image-size="92" />
        </article>
        <aside class="content-card learning-benefits"><p class="eyebrow">LEARNING OUTCOMES</p><h3>学习收获</h3><ul><li>系统化知识框架</li><li>真实项目实践</li><li>问答社区支持</li></ul></aside>
      </div>

      <el-dialog v-model="orderDialogVisible" title="确认课程订单" width="min(92vw, 520px)" :close-on-click-modal="false" :close-on-press-escape="!paying" :show-close="!paying">
        <div v-if="activeOrder" class="order-summary">
          <div><span>课程</span><strong>{{ activeOrder.courseTitle }}</strong></div>
          <div><span>订单号</span><strong>{{ activeOrder.orderNo }}</strong></div>
          <div><span>课程原价</span><strong>¥{{ Number(activeOrder.originalAmount || 0).toFixed(2) }}</strong></div>
          <div class="order-payable"><span>本次支付</span><strong>¥0.00</strong></div>
          <p>当前为零元支付，确认后将立即解锁全部课时。</p>
        </div>
        <template #footer><el-button :disabled="paying" @click="orderDialogVisible = false">取消</el-button><el-button type="primary" :loading="paying" @click="payOrder">确认支付 ¥0.00</el-button></template>
      </el-dialog>
    </template>
  </section>
</template>
