<script setup lang="ts">
import { onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { courseApi } from '@/api'
import type { Course } from '@/types'

const courses = ref<Course[]>([])
const loading = ref(false)
const filters = reactive({ keyword: '', tag: '', current: 1, size: 12 })
const quickTags = ['Java', '架构', '搜索']
let scrollFrame: number | undefined
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
  const target = document.querySelector<HTMLElement>('#featured-courses')
  if (!target) return
  const destination = Math.max(0, target.getBoundingClientRect().top + window.scrollY - 92)
  const start = window.scrollY
  const distance = destination - start

  if (window.matchMedia('(prefers-reduced-motion: reduce)').matches) {
    window.scrollTo({ top: destination })
    return
  }

  if (scrollFrame !== undefined) window.cancelAnimationFrame(scrollFrame)
  const startedAt = window.performance.now()
  const duration = Math.min(1000, Math.max(700, Math.abs(distance) * 0.55))
  const easeInOutCubic = (progress: number) => progress < 0.5
    ? 4 * progress * progress * progress
    : 1 - Math.pow(-2 * progress + 2, 3) / 2

  const animate = (now: number) => {
    const progress = Math.min(1, (now - startedAt) / duration)
    window.scrollTo({ top: start + distance * easeInOutCubic(progress) })
    if (progress < 1) scrollFrame = window.requestAnimationFrame(animate)
    else scrollFrame = undefined
  }
  scrollFrame = window.requestAnimationFrame(animate)
}

onBeforeUnmount(() => {
  if (scrollFrame !== undefined) window.cancelAnimationFrame(scrollFrame)
})
</script>

<template><section class="home-page">
  <div class="hero hero-v2">
    <div class="hero-copy">
      <p class="hero-kicker">快乐学习，轻松进阶</p>
      <h1>从零开始，<br /><span>学会真正能用的技术</span></h1>
      <p class="hero-description">系统课程、项目实战与同行问答，让每一次学习都能解决一个真实问题。</p>
      <div class="hero-actions">
        <el-button type="primary" size="large" round @click="scrollToCourses">立即学习</el-button>
        <RouterLink class="text-action" to="/questions"><span class="play-icon" aria-hidden="true">▶</span> 逛逛问答社区</RouterLink>
      </div>
      <div class="hero-proof" aria-label="平台数据">
        <div><strong>20+</strong><span>体系课程</span></div>
        <div><strong>1,200+</strong><span>学习伙伴</span></div>
        <div><strong>98%</strong><span>课程好评</span></div>
      </div>
    </div>
    <div class="hero-visual" aria-hidden="true">
      <div class="visual-glow"></div>
      <img src="/images/learnhub-hero-v2.png" width="1152" height="768" alt="" />
      <div class="floating-pill pill-course"><span></span> 实战课程持续更新</div>
      <div class="floating-pill pill-growth"><b>+28%</b><small>本周学习进度</small></div>
    </div>
  </div>

  <div class="search-dock" role="search">
    <div class="search-input"><svg viewBox="0 0 24 24" aria-hidden="true"><circle cx="11" cy="11" r="7"/><path d="m16.5 16.5 4 4"/></svg><el-input v-model="filters.keyword" size="large" placeholder="搜索课程、技能或讲师" clearable @keyup.enter="load" /></div>
    <el-button type="primary" size="large" aria-label="搜索课程" @click="load"><span class="search-label-full">搜索课程</span><span class="search-label-short">搜索</span></el-button>
  </div>

  <section class="audience-section" aria-labelledby="audience-title">
    <div class="audience-intro">
      <p class="eyebrow">PERSONALIZED PATH</p>
      <h2 id="audience-title">不同阶段，<br />都有清晰路径</h2>
      <p>根据你的基础与目标，找到适合自己的下一步。</p>
      <span class="audience-line"></span>
    </div>
    <div class="audience-cards">
      <article><span class="role-index">01</span><div class="role-icon role-icon-blue"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 3 3 8l9 5 9-5-9-5Z"/><path d="m7 11 .1 5.3c2.7 2.2 7.1 2.2 9.8 0L17 11"/></svg></div><h3>零基础学习者</h3><p>从核心概念和开发工具入门，逐步完成第一个可运行项目。</p><RouterLink to="/courses">查看入门课 <span>→</span></RouterLink></article>
      <article><span class="role-index">02</span><div class="role-icon role-icon-mint"><svg viewBox="0 0 24 24" aria-hidden="true"><rect x="3" y="4" width="18" height="16" rx="3"/><path d="m8 10 3 2-3 2m5 0h3"/></svg></div><h3>一线开发者</h3><p>围绕高并发、搜索与安全体系，补齐企业级项目能力。</p><RouterLink to="/courses">查看进阶课 <span>→</span></RouterLink></article>
      <article><span class="role-index">03</span><div class="role-icon role-icon-orange"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="M4 19V9m6 10V5m6 14v-7m4 7H2"/><path d="m4 6 5-3 6 5 5-4"/></svg></div><h3>技术管理者</h3><p>建立系统设计视角，用真实案例提升架构决策与协作效率。</p><RouterLink to="/questions">交流实践经验 <span>→</span></RouterLink></article>
    </div>
  </section>

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

  <section class="benefits-section" aria-labelledby="benefits-title">
    <div class="benefits-heading"><p class="eyebrow">WHY LEARNHUB</p><h2 id="benefits-title">不止是看完一门课</h2><p>从知识输入到问题解决，让成长真正发生。</p></div>
    <div class="benefit-grid">
      <article class="benefit-card benefit-purple"><div class="benefit-visual"><span class="visual-ring"></span><svg viewBox="0 0 80 80" aria-hidden="true"><rect x="13" y="18" width="54" height="42" rx="8"/><path d="M24 31h32M24 40h21M24 49h27"/></svg></div><h3>体系化学习</h3><p>课程围绕能力地图组织，告别碎片知识和重复试错。</p></article>
      <article class="benefit-card benefit-green"><div class="benefit-visual"><span class="visual-ring"></span><svg viewBox="0 0 80 80" aria-hidden="true"><path d="M18 57V35l22-14 22 14v22L40 68 18 57Z"/><path d="m30 43 7 7 14-16"/></svg></div><h3>真实项目实践</h3><p>用高并发、认证和搜索等场景，把原理落到工程实现。</p></article>
      <article class="benefit-card benefit-orange"><div class="benefit-visual"><span class="visual-ring"></span><svg viewBox="0 0 80 80" aria-hidden="true"><path d="M18 24h44v30H37L25 64V54h-7V24Z"/><circle cx="30" cy="39" r="2"/><circle cx="40" cy="39" r="2"/><circle cx="50" cy="39" r="2"/></svg></div><h3>同行问答陪伴</h3><p>遇到问题随时交流，用表达、反馈和讨论加深理解。</p></article>
    </div>
  </section>

  <section class="community-cta"><div><p class="eyebrow">LEARN TOGETHER</p><h2>问题不必独自解决</h2><p>把卡住你的问题说清楚，和学习伙伴一起找到答案。</p></div><RouterLink class="cta-link" to="/questions">进入问答社区 <span aria-hidden="true">→</span></RouterLink></section>
</section></template>
