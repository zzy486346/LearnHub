<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { courseApi } from '@/api'
import type { Course } from '@/types'

const route = useRoute()
const course = ref<Course | null>(null)
const loading = ref(true)
const fallback: Course = { id: Number(route.params.id), title: 'Java 高并发与系统设计', subtitle: '建立完整的高并发知识体系', description: '通过秒杀、异步消息和最终一致性等真实业务场景，掌握高并发系统设计方法。', teacherName: '林老师', tags: ['Java', 'Redis', 'RabbitMQ'], likeCount: 2380, favoriteCount: 816, chapters: [{ id: 1, title: '第一章：高并发基础', lessons: [{ id: 1, title: '认识吞吐量与延迟', durationSeconds: 1260, freePreview: true }, { id: 2, title: '缓存与消息队列', durationSeconds: 1680 }] }] }
onMounted(async () => { try { course.value = (await courseApi.detail(String(route.params.id))).data.data } catch { course.value = fallback } finally { loading.value = false } })
async function toggle(action: 'like' | 'favorite') {
  if (!course.value) return
  try {
    if (action === 'like') { await (course.value.liked ? courseApi.unlike(course.value.id) : courseApi.like(course.value.id)); course.value.liked = !course.value.liked }
    else { await (course.value.favorited ? courseApi.unfavorite(course.value.id) : courseApi.favorite(course.value.id)); course.value.favorited = !course.value.favorited }
    ElMessage.success('操作成功')
  } catch { ElMessage.error('请先登录或稍后再试') }
}
</script>

<template><section v-loading="loading"><template v-if="course"><div class="detail-hero"><div><div class="tag-row"><el-tag v-for="tag in course.tags" :key="tag">{{ tag }}</el-tag></div><h1>{{ course.title }}</h1><p>{{ course.subtitle || course.description }}</p><p class="muted">讲师 · {{ course.teacherName || course.instructor || '问课讲师' }}</p><div class="button-row"><el-button type="primary" size="large">开始学习</el-button><el-button size="large" @click="toggle('like')">{{ course.liked ? '已点赞' : '点赞' }} {{ course.likeCount || 0 }}</el-button><el-button size="large" @click="toggle('favorite')">{{ course.favorited ? '已收藏' : '收藏' }}</el-button></div></div><div class="detail-art">LEARN<br />WITHOUT<br />LIMITS</div></div>
<div class="detail-grid"><article class="content-card"><h2>课程介绍</h2><p>{{ course.description }}</p><h2>课程目录</h2><el-collapse><el-collapse-item v-for="chapter in course.chapters" :key="chapter.id" :title="chapter.title"><div v-for="lesson in chapter.lessons" :key="lesson.id" class="lesson"><span>▶ {{ lesson.title }} <el-tag v-if="lesson.freePreview" size="small">试看</el-tag></span><span>{{ Math.ceil((lesson.durationSeconds || 0) / 60) }} 分钟</span></div></el-collapse-item></el-collapse></article><aside class="content-card"><h3>学习收获</h3><p>系统化知识框架</p><p>真实项目实践</p><p>问答社区支持</p></aside></div></template></section></template>
