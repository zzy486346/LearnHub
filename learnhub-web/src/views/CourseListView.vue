<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { courseApi } from '@/api'
import { isSessionExpired } from '@/api/session'
import type { Course, CourseSearchResult } from '@/types'

interface CourseCard {
  id: number
  title: string
  subtitle?: string
  description?: string
  teacherName?: string
  instructor?: string
  coverUrl?: string
  tags?: string[]
  likeCount?: number
}
type Suggestion = { value: string }

const courses = ref<CourseCard[]>([])
const loading = ref(false)
const loadError = ref('')
const total = ref(0)
const filters = reactive({ keyword: '', tags: [] as string[], current: 1, size: 3 })
const activeQuery = reactive({ keyword: '', tags: [] as string[] })
const carouselRegion = ref<HTMLElement | null>(null)
const slideVersion = ref(0)
const slideDirection = ref('next')
const hovered = ref(false)
const focused = ref(false)
const regionVisible = ref(true)
const paginationTotal = computed(() => activeQuery.keyword || activeQuery.tags.length ? Math.min(total.value, 9999) : total.value)
const pageCount = computed(() => Math.ceil(paginationTotal.value / filters.size))
let rotationTimer: ReturnType<typeof setTimeout> | undefined
let visibilityObserver: IntersectionObserver | undefined
let mounted = false
const quickTags = ['Java', '架构', '搜索']
let scrollFrame: number | undefined
let loadGeneration = 0
let suggestionGeneration = 0

function toCourseCard(course: Course | CourseSearchResult): CourseCard {
  return {
    id: course.id,
    title: course.title,
    description: course.description,
    instructor: course.instructor,
    coverUrl: course.coverUrl,
    tags: course.tags,
    likeCount: course.likeCount,
    ...('subtitle' in course ? { subtitle: course.subtitle, teacherName: course.teacherName } : {}),
  }
}

function errorMessage(error: unknown, fallback: string) {
  return (error as { response?: { data?: { message?: string } } }).response?.data?.message || fallback
}

async function load() {
  const generation = ++loadGeneration
  clearTimeout(rotationTimer)
  loading.value = true
  loadError.value = ''
  try {
    const keyword = activeQuery.keyword
    if (keyword || activeQuery.tags.length) {
      const result = (await courseApi.searchPage({
        keyword,
        tags: activeQuery.tags.length ? activeQuery.tags.join(',') : undefined,
        page: filters.current,
        size: filters.size,
      })).data.data
      if (generation !== loadGeneration) return
      courses.value = result.records.map(toCourseCard)
      total.value = result.total
    } else {
      const result = (await courseApi.list({ page: filters.current, size: filters.size })).data.data
      if (generation !== loadGeneration) return
      courses.value = result.records.map(toCourseCard)
      total.value = result.total
    }
    slideVersion.value++
  }
  catch (error) {
    if (generation !== loadGeneration) return
    if (isSessionExpired(error)) return
    courses.value = []
    total.value = 0
    loadError.value = errorMessage(error, '课程加载失败，请检查网络连接后重试')
  }
  finally {
    if (generation === loadGeneration) {
      loading.value = false
      scheduleRotation()
    }
  }
}

function scheduleRotation() {
  clearTimeout(rotationTimer)
  if (!mounted || loading.value || loadError.value || hovered.value
    || focused.value || !regionVisible.value || document.hidden || pageCount.value <= 1) return
  rotationTimer = setTimeout(() => { void changePage(filters.current % pageCount.value + 1) }, 3000)
}

function changePage(page: number, direction = 'next') {
  if (loading.value || pageCount.value <= 1) return
  slideDirection.value = direction
  filters.current = Math.min(pageCount.value, Math.max(1, page))
  return load()
}

function previousPage() {
  return changePage(filters.current === 1 ? pageCount.value : filters.current - 1, 'previous')
}

function nextPage() {
  return changePage(filters.current % pageCount.value + 1)
}

function setHovered(value: boolean) { hovered.value = value; scheduleRotation() }
function setFocused(value: boolean) { focused.value = value; scheduleRotation() }
function handleFocusIn(event: FocusEvent) {
  setFocused((event.target as HTMLElement).matches(':focus-visible'))
}
function handleFocusOut(event: FocusEvent) {
  if (!(event.currentTarget as HTMLElement).contains(event.relatedTarget as Node | null)) setFocused(false)
}

watch(carouselRegion, (element, previous) => {
  if (previous) visibilityObserver?.unobserve(previous)
  if (element) visibilityObserver?.observe(element)
})

onMounted(() => {
  mounted = true
  document.addEventListener('visibilitychange', scheduleRotation)
  if (typeof IntersectionObserver !== 'undefined') {
    regionVisible.value = false
    visibilityObserver = new IntersectionObserver(([entry]) => {
      regionVisible.value = Boolean(entry?.isIntersecting && entry.intersectionRatio >= 0.15)
      scheduleRotation()
    }, { threshold: 0.15 })
    if (carouselRegion.value) visibilityObserver.observe(carouselRegion.value)
  }
  void load()
})

function submitSearch() {
  activeQuery.keyword = filters.keyword.trim()
  activeQuery.tags = [...filters.tags]
  filters.current = 1
  return load()
}

function selectTag(tag: string) {
  const index = filters.tags.indexOf(tag)
  if (index >= 0) filters.tags.splice(index, 1)
  else filters.tags.push(tag)
  return submitSearch()
}

async function fetchSuggestions(prefix: string, callback: (items: Suggestion[]) => void) {
  const query = prefix.trim()
  const generation = ++suggestionGeneration
  if (!query) {
    callback([])
    return
  }
  try {
    const suggestions = (await courseApi.suggestions({ prefix: query, limit: 8 })).data.data
    callback(generation === suggestionGeneration ? suggestions.map(value => ({ value })) : [])
  } catch {
    if (generation === suggestionGeneration) callback([])
  }
}

function selectSuggestion(item: Suggestion) {
  filters.keyword = item.value
  return submitSearch()
}

function scrollToCourses() {
  const target = document.querySelector<HTMLElement>('#featured-courses')
  if (!target) return
  const destination = Math.max(0, target.getBoundingClientRect().top + window.scrollY - 92)
  const start = window.scrollY
  const distance = destination - start

  if (scrollFrame !== undefined) window.cancelAnimationFrame(scrollFrame)
  const startedAt = window.performance.now()
  const reducedMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches
  const duration = reducedMotion ? 420 : Math.min(1000, Math.max(700, Math.abs(distance) * 0.55))
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
  mounted = false
  loadGeneration++
  clearTimeout(rotationTimer)
  document.removeEventListener('visibilitychange', scheduleRotation)
  visibilityObserver?.disconnect()
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
    <div class="search-input"><svg viewBox="0 0 24 24" aria-hidden="true"><circle cx="11" cy="11" r="7"/><path d="m16.5 16.5 4 4"/></svg><el-autocomplete v-model="filters.keyword" :fetch-suggestions="fetchSuggestions" :trigger-on-focus="false" size="large" placeholder="搜索课程、技能或讲师" clearable @keyup.enter="submitSearch" @clear="submitSearch" @select="selectSuggestion" /></div>
    <el-button type="primary" size="large" aria-label="搜索课程" @click="submitSearch"><span class="search-label-full">搜索课程</span><span class="search-label-short">搜索</span></el-button>
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
    <div class="filter-chips" aria-label="课程标签筛选"><button v-for="tag in quickTags" :key="tag" type="button" :class="{ active: filters.tags.includes(tag) }" :aria-pressed="filters.tags.includes(tag)" @click="selectTag(tag)">{{ tag }}</button></div>
  </div>
  <div v-if="loadError" class="content-card state-panel course-search-state" role="alert"><h3>暂时无法加载课程</h3><p>{{ loadError }}</p><el-button @click="load">重新加载</el-button></div>
  <p v-else-if="activeQuery.keyword || activeQuery.tags.length" class="search-result-summary" aria-live="polite">找到 {{ total }} 门匹配课程</p>
  <section v-if="!loadError" ref="carouselRegion" class="course-carousel" :class="{ 'is-auto-playing': !hovered && !focused }" aria-label="精选课程轮播" aria-roledescription="轮播" @mouseenter="setHovered(true)" @mouseleave="setHovered(false)" @focusin="handleFocusIn" @focusout="handleFocusOut">
  <div v-loading="loading && !courses.length" class="course-carousel-window" :aria-busy="loading">
  <Transition :name="slideDirection === 'previous' ? 'course-previous' : 'course-next'">
  <div :key="slideVersion" class="course-grid"><RouterLink v-for="(course, index) in courses" :key="course.id" class="course-card" :to="`/courses/${course.id}`">
    <div class="course-cover" :class="`cover-${index % 3}`" :style="course.coverUrl ? { backgroundImage: `linear-gradient(180deg, transparent 30%, rgba(7, 43, 33, .75)), url(${course.coverUrl})` } : {}">
      <span>{{ course.tags?.[0] || '精品课程' }}</span><b>0{{ index + 1 }}</b>
      <div class="cover-mark" aria-hidden="true"><span></span><span></span><span></span></div>
    </div>
    <div class="course-body"><div class="course-meta"><span>{{ course.tags?.slice(0, 2).join(' · ') || '职业技能' }}</span><span>{{ course.likeCount || 0 }} 人喜欢</span></div><h3>{{ course.title }}</h3><p>{{ course.subtitle || course.description }}</p><div class="course-footer"><span class="teacher-avatar">{{ (course.teacherName || course.instructor || '问').slice(0, 1) }}</span><span>{{ course.teacherName || course.instructor || '问课讲师' }}</span><span class="course-arrow" aria-hidden="true">→</span></div></div>
  </RouterLink></div>
  </Transition></div>
  <div v-if="pageCount > 1" class="course-carousel-controls">
    <el-button :disabled="loading" aria-label="上一组课程" @click="previousPage">上一组</el-button>
    <span :aria-live="hovered || focused ? 'polite' : 'off'">第 {{ filters.current }} / {{ pageCount }} 页 · 每页 3 门</span>
    <el-button :disabled="loading" aria-label="下一组课程" @click="nextPage">下一组</el-button>
  </div>
  <el-pagination v-if="paginationTotal > filters.size" :disabled="loading" :current-page="filters.current" :page-size="filters.size" :total="paginationTotal" layout="prev, pager, next" class="course-pagination" @current-change="changePage" />
  </section>
  <el-empty v-if="!loading && !loadError && !courses.length" description="暂时没有匹配的课程" />

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
