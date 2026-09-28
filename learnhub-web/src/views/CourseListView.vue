<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { courseApi } from '@/api'
import type { Course } from '@/types'

const courses = ref<Course[]>([])
const loading = ref(false)
const filters = reactive({ keyword: '', tag: '', current: 1, size: 12 })
const quickTags = ['Java', '架构', '搜索']
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

function selectTag(tag: string) {
  filters.tag = filters.tag === tag ? '' : tag
  load()
}

function scrollToCourses() {
  document.querySelector('#featured-courses')?.scrollIntoView({ behavior: 'smooth' })
}
</script>

<template><section class="home-page">
  <div class="hero">
    <div class="hero-copy">
      <p class="eyebrow"><span class="status-dot"></span> 职业教育 · 系统成长</p>
      <h1>在真实项目中，<br /><span>长出解决问题的能力</span></h1>
      <p class="hero-description">从系统课程到同行问答，把每一个知识点转化为可复用的实践经验。</p>
      <div class="hero-actions">
        <el-button type="primary" size="large" round @click="scrollToCourses">开始探索</el-button>
        <RouterLink class="text-action" to="/questions">去问答社区 <span aria-hidden="true">→</span></RouterLink>
      </div>
      <div class="hero-proof" aria-label="平台数据">
        <div><strong>20+</strong><span>实战课程</span></div>
        <div><strong>1.2k</strong><span>学习伙伴</span></div>
        <div><strong>98%</strong><span>学习好评</span></div>
      </div>
    </div>
    <div class="hero-panel" aria-label="学习路径预览">
      <div class="hero-panel-head"><span>本周学习路径</span><small>进阶路线</small></div>
      <div class="path-item is-active"><span class="path-number">01</span><div><strong>掌握核心原理</strong><small>构建扎实知识体系</small></div><span class="path-state">进行中</span></div>
      <div class="path-item"><span class="path-number">02</span><div><strong>完成项目实践</strong><small>从需求到可运行系统</small></div></div>
      <div class="path-item"><span class="path-number">03</span><div><strong>参与社区共创</strong><small>用分享验证理解</small></div></div>
      <div class="panel-note"><span class="note-icon"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 3v18M3 12h18"/></svg></span><div><strong>保持连续学习</strong><small>今天再前进一点点</small></div></div>
    </div>
  </div>

  <div class="search-dock" role="search">
    <div class="search-input"><svg viewBox="0 0 24 24" aria-hidden="true"><circle cx="11" cy="11" r="7"/><path d="m16.5 16.5 4 4"/></svg><el-input v-model="filters.keyword" size="large" placeholder="搜索课程、技能或讲师" clearable @keyup.enter="load" /></div>
    <el-button type="primary" size="large" @click="load">搜索课程</el-button>
  </div>

  <div id="featured-courses" class="section-heading">
    <div><p class="eyebrow">CURATED LEARNING</p><h2>精选课程</h2><p>按能力成长设计，让每一次学习都更接近真实工作。</p></div>
    <div class="filter-chips" aria-label="课程标签筛选"><button v-for="tag in quickTags" :key="tag" type="button" :class="{ active: filters.tag === tag }" @click="selectTag(tag)">{{ tag }}</button></div>
  </div>
  <div v-loading="loading" class="course-grid"><RouterLink v-for="(course, index) in courses" :key="course.id" class="course-card" :to="`/courses/${course.id}`">
    <div class="course-cover" :class="`cover-${index % 3}`" :style="course.coverUrl ? { backgroundImage: `linear-gradient(180deg, transparent 30%, rgba(7, 43, 33, .75)), url(${course.coverUrl})` } : {}">
      <span>{{ course.tags?.[0] || '精品课程' }}</span><b>0{{ index + 1 }}</b>
      <div class="cover-mark" aria-hidden="true"><span></span><span></span><span></span></div>
    </div>
    <div class="course-body"><div class="course-meta"><span>{{ course.tags?.slice(0, 2).join(' · ') || '职业技能' }}</span><span>{{ course.likeCount || 0 }} 人喜欢</span></div><h3>{{ course.title }}</h3><p>{{ course.subtitle || course.description }}</p><div class="course-footer"><span class="teacher-avatar">{{ (course.teacherName || course.instructor || '问').slice(0, 1) }}</span><span>{{ course.teacherName || course.instructor || '问课讲师' }}</span><span class="course-arrow" aria-hidden="true">→</span></div></div>
  </RouterLink></div>
  <el-empty v-if="!loading && !courses.length" description="暂时没有匹配的课程" />

  <section class="community-cta"><div><p class="eyebrow">LEARN TOGETHER</p><h2>问题不必独自解决</h2><p>把卡住你的问题说清楚，和学习伙伴一起找到答案。</p></div><RouterLink class="cta-link" to="/questions">进入问答社区 <span aria-hidden="true">→</span></RouterLink></section>
</section></template>
