<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { courseApi } from '@/api'
import { useAuthStore } from '@/stores/auth'
import type { Course, Lesson } from '@/types'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const course = ref<Course | null>(null)
const loading = ref(false)
const errorMessage = ref('')
const lessons = computed(() => course.value?.chapters?.flatMap((chapter) => chapter.lessons || []) ?? [])
const firstLesson = computed(() => lessons.value[0] ?? null)

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
  if (course.value) router.push(`/courses/${course.value.id}/lessons/${lesson.id}`)
}

function startLearning() {
  if (firstLesson.value) openLesson(firstLesson.value)
}
async function toggle(action: 'like' | 'favorite') {
  if (!course.value) return
  try {
    if (action === 'like') {
      const response = await (course.value.liked ? courseApi.unlike(course.value.id) : courseApi.like(course.value.id))
      course.value.liked = response.data.data.liked
      course.value.likeCount = response.data.data.count
    } else {
      const response = await (course.value.favorited ? courseApi.unfavorite(course.value.id) : courseApi.favorite(course.value.id))
      course.value.favorited = response.data.data
    }
    ElMessage.success('操作成功')
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
      <div class="detail-hero"><div><div class="tag-row"><el-tag v-for="tag in course.tags" :key="tag">{{ tag }}</el-tag></div><h1>{{ course.title }}</h1><p>{{ course.subtitle || course.description }}</p><p class="muted">讲师 · {{ course.teacherName || course.instructor || '问课讲师' }}</p><div class="button-row"><el-button type="primary" size="large" :disabled="!firstLesson" @click="startLearning">{{ firstLesson ? '开始学习' : '暂无课时' }}</el-button><el-button size="large" @click="toggle('like')">{{ course.liked ? '已点赞' : '点赞' }} {{ course.likeCount || 0 }}</el-button><el-button size="large" @click="toggle('favorite')">{{ course.favorited ? '已收藏' : '收藏' }}</el-button></div></div><div class="detail-art">LEARN<br />WITHOUT<br />LIMITS</div></div>
      <div class="detail-grid"><article class="content-card course-content-card"><h2>课程介绍</h2><p class="course-description">{{ course.description || '讲师正在完善课程介绍。' }}</p><div class="catalog-heading"><div><h2>课程目录</h2><p>{{ course.chapters?.length || 0 }} 个章节 · {{ lessons.length }} 个课时</p></div></div><el-collapse v-if="course.chapters?.length"><el-collapse-item v-for="chapter in course.chapters" :key="chapter.id" :title="chapter.title"><button v-for="lesson in chapter.lessons" :key="lesson.id" class="lesson" type="button" @click="openLesson(lesson)"><span class="lesson-main"><span class="lesson-play" aria-hidden="true"><svg viewBox="0 0 24 24"><path d="m8 5 11 7-11 7V5Z" /></svg></span><span>{{ lesson.title }}</span><el-tag v-if="lesson.freePreview" size="small">试看</el-tag></span><span class="lesson-duration">{{ Math.ceil((lesson.durationSeconds || 0) / 60) }} 分钟</span></button></el-collapse-item></el-collapse><el-empty v-else description="课程目录正在准备中" :image-size="92" /></article><aside class="content-card learning-benefits"><p class="eyebrow">LEARNING OUTCOMES</p><h3>学习收获</h3><ul><li>系统化知识框架</li><li>真实项目实践</li><li>问答社区支持</li></ul></aside></div>
    </template>
  </section>
</template>
