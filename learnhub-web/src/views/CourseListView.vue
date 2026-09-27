<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { courseApi } from '@/api'
import type { Course } from '@/types'

const courses = ref<Course[]>([])
const loading = ref(false)
const filters = reactive({ keyword: '', tag: '', current: 1, size: 12 })
const fallback: Course[] = [
  { id: 1, title: 'Java 高并发与系统设计', subtitle: '从线程模型到分布式一致性', teacherName: '林老师', tags: ['Java', '架构'], likeCount: 2380 },
  { id: 2, title: 'Spring Boot 企业级实战', subtitle: '完成一套可上线的业务系统', teacherName: '陈老师', tags: ['Spring', '后端'], likeCount: 1926 },
  { id: 3, title: 'Elasticsearch 搜索实践', subtitle: '相关性、联想与业务排序', teacherName: '周老师', tags: ['搜索', 'ES'], likeCount: 986 },
]
async function load() {
  loading.value = true
  try {
    if (filters.keyword || filters.tag) {
      courses.value = (await courseApi.search({ keyword: filters.keyword, tags: filters.tag || undefined, limit: filters.size })).data.data
    } else {
      courses.value = (await courseApi.list({ page: filters.current, size: filters.size })).data.data.records
    }
  }
  catch { courses.value = fallback }
  finally { loading.value = false }
}
onMounted(load)
</script>

<template><section>
  <div class="hero"><div><p class="eyebrow">职业教育 · 系统成长</p><h1>找到你的下一门好课</h1><p>聚焦实战能力，用课程、问答与社群陪你持续进阶。</p></div><div class="search"><el-input v-model="filters.keyword" size="large" placeholder="搜索课程、技能或讲师" clearable @keyup.enter="load" /><el-button type="primary" size="large" @click="load">搜索</el-button></div></div>
  <div class="section-heading"><div><h2>精选课程</h2><p>为职业进阶设计的系统学习路径</p></div><el-select v-model="filters.tag" clearable placeholder="全部标签" @change="load"><el-option label="Java" value="Java" /><el-option label="架构" value="架构" /><el-option label="搜索" value="搜索" /></el-select></div>
  <div v-loading="loading" class="course-grid"><RouterLink v-for="course in courses" :key="course.id" class="course-card" :to="`/courses/${course.id}`"><div class="course-cover" :style="course.coverUrl ? { backgroundImage: `url(${course.coverUrl})` } : {}"><span>{{ course.tags?.[0] || '精品课程' }}</span></div><div class="course-body"><h3>{{ course.title }}</h3><p>{{ course.subtitle || course.description }}</p><div><span>{{ course.teacherName || course.instructor || '问课讲师' }}</span><span>♡ {{ course.likeCount || 0 }}</span></div></div></RouterLink></div>
  <el-empty v-if="!loading && !courses.length" description="暂时没有匹配的课程" />
</section></template>
