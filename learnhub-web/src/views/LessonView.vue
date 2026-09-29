<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { onBeforeRouteLeave, useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { courseApi, learningApi } from '@/api'
import { useAuthStore } from '@/stores/auth'
import type { Course, LearningProgress } from '@/types'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const course = ref<Course | null>(null)
const progress = ref<LearningProgress[]>([])
const videoRef = ref<HTMLVideoElement | null>(null)
const loading = ref(false)
const errorMessage = ref('')
const saveState = ref<'idle' | 'saving' | 'saved' | 'error'>('idle')
const resumePosition = ref(0)
let lastReportAt = 0
let lastSavedPosition = -1
let saveQueue: Promise<unknown> = Promise.resolve()

const courseId = computed(() => Number(route.params.courseId))
const lessonId = computed(() => Number(route.params.lessonId))
const lessonEntries = computed(() => course.value?.chapters?.flatMap((chapter) =>
  (chapter.lessons || []).map((lesson) => ({ lesson, chapterTitle: chapter.title })),
) ?? [])
const currentIndex = computed(() => lessonEntries.value.findIndex((item) => item.lesson.id === lessonId.value))
const currentEntry = computed(() => lessonEntries.value[currentIndex.value] ?? null)
const currentAccessible = computed(() => currentEntry.value?.lesson.accessible === true)
const previousEntry = computed(() => currentIndex.value > 0 ? lessonEntries.value[currentIndex.value - 1] : null)
const nextEntry = computed(() => currentIndex.value >= 0 && currentIndex.value < lessonEntries.value.length - 1
  ? lessonEntries.value[currentIndex.value + 1]
  : null)
const completedLessonIds = computed(() => new Set(progress.value.filter((item) => item.completed).map((item) => item.lessonId)))

async function loadLesson() {
  loading.value = true
  errorMessage.value = ''
  course.value = null
  progress.value = []
  resumePosition.value = 0
  lastSavedPosition = -1
  saveState.value = 'idle'
  try {
    course.value = (await courseApi.detail(courseId.value)).data.data
    if (!currentEntry.value) {
      errorMessage.value = '该课时不存在，或已经从课程中移除。'
      return
    }
    if (auth.authenticated && currentAccessible.value) {
      try {
        progress.value = (await learningApi.progress(courseId.value)).data.data || []
        const saved = progress.value.find((item) => item.lessonId === lessonId.value)
        resumePosition.value = saved?.positionSeconds || 0
        lastSavedPosition = resumePosition.value
        saveState.value = 'saved'
      } catch {
        ElMessage.warning('学习进度暂时无法读取，本次仍可继续观看')
      }
    }
    await nextTick()
  } catch {
    errorMessage.value = '课时加载失败，请稍后重试。'
  } finally {
    loading.value = false
  }
}

function restorePlaybackPosition() {
  const video = videoRef.value
  if (!video || !resumePosition.value || !Number.isFinite(video.duration)) return
  video.currentTime = Math.min(resumePosition.value, Math.max(0, video.duration - 1))
}

function currentPosition() {
  return Math.max(0, Math.floor(videoRef.value?.currentTime || resumePosition.value || 0))
}

function queueProgressSave(completed?: boolean, force = false) {
  if (!auth.authenticated || !currentEntry.value || !currentAccessible.value) return Promise.resolve()
  const positionSeconds = currentPosition()
  const finalCompleted = completed ?? completedLessonIds.value.has(currentEntry.value.lesson.id)
  if (!force && !finalCompleted && Math.abs(positionSeconds - lastSavedPosition) < 5) return Promise.resolve()
  saveState.value = 'saving'
  const savedLessonId = currentEntry.value.lesson.id
  const savedCourseId = courseId.value
  saveQueue = saveQueue
    .catch(() => undefined)
    .then(() => learningApi.saveProgress(savedCourseId, { lessonId: savedLessonId, positionSeconds, completed: finalCompleted }))
    .then((response) => {
      lastSavedPosition = positionSeconds
      saveState.value = 'saved'
      const saved = response.data.data
      const index = progress.value.findIndex((item) => item.lessonId === saved.lessonId)
      if (index >= 0) progress.value[index] = saved
      else progress.value.push(saved)
    })
    .catch(() => { saveState.value = 'error' })
  return saveQueue
}

function handleTimeUpdate() {
  const now = Date.now()
  if (now - lastReportAt < 10_000) return
  lastReportAt = now
  void queueProgressSave()
}

function handleEnded() {
  void queueProgressSave(true, true).then(() => {
    if (nextEntry.value) ElMessage.success('本课时已完成，可以继续下一课')
  })
}

async function goToLesson(targetLessonId: number) {
  const target = lessonEntries.value.find((item) => item.lesson.id === targetLessonId)
  if (!target) return
  if (!auth.authenticated) {
    await router.push({ path: '/login', query: { redirect: `/courses/${courseId.value}/lessons/${targetLessonId}` } })
    return
  }
  if (!target.lesson.accessible) {
    ElMessage.info('购买课程后即可观看该课时')
    await router.push(`/courses/${courseId.value}`)
    return
  }
  await queueProgressSave(undefined, true)
  await router.push(`/courses/${courseId.value}/lessons/${targetLessonId}`)
}

function goToLogin() {
  void router.push({ path: '/login', query: { redirect: route.fullPath } })
}

function goToPurchase() {
  void router.push(`/courses/${courseId.value}`)
}

function saveWithKeepalive() {
  if (!auth.authenticated || !currentEntry.value || !currentAccessible.value) return
  const token = localStorage.getItem('learnhub_access_token')
  const baseUrl = import.meta.env.VITE_API_BASE_URL || '/api'
  void fetch(`${baseUrl}/learning/progress/${courseId.value}`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}) },
    body: JSON.stringify({ lessonId: currentEntry.value.lesson.id, positionSeconds: currentPosition(), completed: completedLessonIds.value.has(currentEntry.value.lesson.id) }),
    keepalive: true,
  })
}

window.addEventListener('beforeunload', saveWithKeepalive)
watch([() => route.params.courseId, () => route.params.lessonId], loadLesson, { immediate: true })
onBeforeRouteLeave(async () => { await queueProgressSave(undefined, true); return true })
onBeforeUnmount(() => window.removeEventListener('beforeunload', saveWithKeepalive))
</script>

<template>
  <section v-loading="loading" class="lesson-page">
    <div v-if="errorMessage && !loading" class="page-state-card" role="alert">
      <div class="state-icon" aria-hidden="true"><svg viewBox="0 0 24 24"><path d="M12 9v4m0 4h.01M10.3 3.7 2.6 17a2 2 0 0 0 1.7 3h15.4a2 2 0 0 0 1.7-3L13.7 3.7a2 2 0 0 0-3.4 0Z" /></svg></div>
      <h1>无法打开课时</h1><p>{{ errorMessage }}</p>
      <div class="button-row"><el-button type="primary" @click="loadLesson">重新加载</el-button><el-button @click="router.push(`/courses/${courseId}`)">返回课程</el-button></div>
    </div>

    <template v-else-if="course && currentEntry">
      <nav class="lesson-breadcrumb" aria-label="面包屑">
        <RouterLink to="/courses">课程</RouterLink><span>/</span>
        <RouterLink :to="`/courses/${course.id}`">{{ course.title }}</RouterLink><span>/</span>
        <strong>{{ currentEntry.lesson.title }}</strong>
      </nav>

      <div class="lesson-shell">
        <main class="lesson-stage">
          <div class="video-frame">
            <video v-if="currentAccessible && currentEntry.lesson.mediaUrl" ref="videoRef" :key="currentEntry.lesson.id" :src="currentEntry.lesson.mediaUrl" :poster="course.coverUrl" controls preload="metadata" @loadedmetadata="restorePlaybackPosition" @timeupdate="handleTimeUpdate" @pause="queueProgressSave(undefined, true)" @ended="handleEnded">您的浏览器不支持视频播放。</video>
            <div v-else-if="!auth.authenticated" class="video-empty lesson-access-gate">
              <span aria-hidden="true"><svg viewBox="0 0 24 24"><path d="M12 15v2m-5-7V8a5 5 0 0 1 10 0v2m-9 0h8a2 2 0 0 1 2 2v7H6v-7a2 2 0 0 1 2-2Z" /></svg></span>
              <h2>登录后才能观看课程</h2><p>登录后可观看试看课时，并可购买解锁完整课程。</p>
              <el-button type="primary" size="large" @click="goToLogin">立即登录</el-button>
            </div>
            <div v-else-if="!currentAccessible" class="video-empty lesson-access-gate">
              <span aria-hidden="true"><svg viewBox="0 0 24 24"><path d="M12 15v2m-5-7V8a5 5 0 0 1 10 0v2m-9 0h8a2 2 0 0 1 2 2v7H6v-7a2 2 0 0 1 2-2Z" /></svg></span>
              <h2>该课时需要购买后观看</h2><p>返回课程确认订单，零元支付后即可解锁全部课时。</p>
              <el-button type="primary" size="large" @click="goToPurchase">购买课程</el-button>
            </div>
            <div v-else class="video-empty">
              <span aria-hidden="true"><svg viewBox="0 0 24 24"><path d="m15 10 4.6-2.7a1 1 0 0 1 1.4.9v7.6a1 1 0 0 1-1.4.9L15 14M4 6h9a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2Z" /></svg></span>
              <h2>视频资源暂未配置</h2><p>课时目录已就绪，讲师上传视频后即可在这里学习。</p>
            </div>
          </div>

          <div class="lesson-heading">
            <div><p class="eyebrow">{{ currentEntry.chapterTitle }}</p><h1>{{ currentEntry.lesson.title }}</h1></div>
            <span v-if="auth.authenticated && currentAccessible" class="save-state" :class="`is-${saveState}`" aria-live="polite">{{ saveState === 'saving' ? '正在保存' : saveState === 'error' ? '保存失败' : '进度已同步' }}</span>
          </div>

          <div class="lesson-navigation">
            <el-button :disabled="!previousEntry" @click="previousEntry && goToLesson(previousEntry.lesson.id)">上一课</el-button>
            <el-button type="primary" :disabled="!nextEntry" @click="nextEntry && goToLesson(nextEntry.lesson.id)">下一课</el-button>
          </div>
        </main>

        <aside class="lesson-sidebar" aria-label="课程目录">
          <div class="lesson-sidebar-header"><p>课程目录</p><strong>{{ currentIndex + 1 }} / {{ lessonEntries.length }}</strong></div>
          <div v-for="chapter in course.chapters" :key="chapter.id" class="lesson-sidebar-chapter">
            <h2>{{ chapter.title }}</h2>
            <button v-for="lesson in chapter.lessons" :key="lesson.id" type="button" :class="{ active: lesson.id === currentEntry.lesson.id, completed: completedLessonIds.has(lesson.id), locked: !lesson.accessible }" :aria-current="lesson.id === currentEntry.lesson.id ? 'page' : undefined" @click="lesson.id !== currentEntry.lesson.id && goToLesson(lesson.id)">
              <span class="catalog-status" aria-hidden="true"><svg viewBox="0 0 24 24"><path v-if="completedLessonIds.has(lesson.id)" d="m5 12 4 4L19 6" /><path v-else-if="lesson.accessible" d="m9 7 7 5-7 5V7Z" /><path v-else d="M8 11V8a4 4 0 0 1 8 0v3m-8 0h8v8H8v-8Z" /></svg></span>
              <span><strong>{{ lesson.title }}</strong><small>{{ lesson.freePreview ? '试看 · ' : !lesson.accessible ? '购买后解锁 · ' : '' }}{{ Math.ceil((lesson.durationSeconds || 0) / 60) }} 分钟</small></span>
            </button>
          </div>
        </aside>
      </div>
    </template>
  </section>
</template>
