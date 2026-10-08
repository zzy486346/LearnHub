<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { questionApi } from '@/api'
import { isSessionExpired } from '@/api/session'
import type { Answer, Question } from '@/types'
import VoteButton from '@/components/VoteButton.vue'

const route = useRoute()
const router = useRouter()
const questionId = computed(() => String(route.params.id))
const question = ref<Question>()
const answers = ref<Answer[]>([])
const loading = ref(false)
const submitting = ref(false)
const loadError = ref('')
const content = ref('')
const page = ref(1)
const total = ref(0)
const pageSize = 20

function formatDate(value: string) {
  return value ? new Date(value).toLocaleString('zh-CN', { dateStyle: 'medium', timeStyle: 'short' }) : ''
}

async function load() {
  loading.value = true
  loadError.value = ''
  try {
    const [questionResult, answerResult] = await Promise.all([
      questionApi.detail(questionId.value),
      questionApi.answers(questionId.value, { page: page.value, size: pageSize }),
    ])
    question.value = questionResult.data.data
    answers.value = answerResult.data.data.records
    total.value = answerResult.data.data.total
  } catch (error) {
    if (isSessionExpired(error)) return
    loadError.value = (error as { response?: { data?: { message?: string } } }).response?.data?.message || '问题加载失败，请稍后重试'
  } finally {
    loading.value = false
  }
}

async function submitAnswer() {
  if (!content.value.trim()) return ElMessage.warning('请先填写回答内容')
  if (!localStorage.getItem('learnhub_access_token')) {
    await router.push({ path: '/login', query: { redirect: route.fullPath } })
    return
  }
  submitting.value = true
  try {
    await questionApi.answer(questionId.value, { content: content.value.trim() })
    content.value = ''
    page.value = 1
    await load()
    ElMessage.success('回答已发布')
  } catch (error) {
    if (isSessionExpired(error)) return
    const response = (error as { response?: { data?: { message?: string } } }).response
    ElMessage.error(response?.data?.message || '回答发布失败，请稍后重试')
  } finally {
    submitting.value = false
  }
}

onMounted(load)
</script>

<template>
  <section class="question-detail-page" v-loading="loading">
    <button class="back-link" type="button" @click="router.push('/questions')">
      <svg viewBox="0 0 24 24" aria-hidden="true"><path d="m15 18-6-6 6-6" /></svg>返回问答社区
    </button>
    <div v-if="loadError" class="content-card state-panel" role="alert">
      <h2>暂时无法打开这个问题</h2><p>{{ loadError }}</p><el-button @click="load">重新加载</el-button>
    </div>
    <template v-else-if="question">
      <article class="question-detail-card">
        <p class="eyebrow">COMMUNITY QUESTION</p>
        <h1>{{ question.title }}</h1>
        <p class="question-detail-content">{{ question.content }}</p>
        <div class="question-meta"><span>{{ question.nickname }}</span><span>{{ formatDate(question.createdAt) }}</span><span>{{ question.answerCount }} 个回答</span></div>
        <VoteButton :key="question.id" target-type="QUESTION" :target-id="question.id" />
      </article>

      <div class="answer-layout">
        <main>
          <div class="answer-heading"><h2>{{ total }} 个回答</h2><span>按采纳状态和时间排序</span></div>
          <el-empty v-if="!loading && !answers.length" description="还没有回答，分享你的经验吧" />
          <article v-for="item in answers" :key="item.id" class="answer-card">
            <div class="answer-author"><span class="answer-avatar">{{ item.nickname.slice(0, 1) }}</span><div><strong>{{ item.nickname }}</strong><small>{{ formatDate(item.createdAt) }}</small></div><el-tag v-if="item.accepted" type="success">已采纳</el-tag></div>
            <p>{{ item.content }}</p>
            <VoteButton target-type="ANSWER" :target-id="item.id" />
          </article>
          <el-pagination v-if="total > pageSize" v-model:current-page="page" :page-size="pageSize" :total="total" layout="prev, pager, next" @current-change="load" />
        </main>
        <aside class="answer-form content-card">
          <h2>写下你的回答</h2><p>给出可复现的步骤、依据或示例，会让回答更有帮助。</p>
          <label for="answer-content">回答内容</label>
          <el-input id="answer-content" v-model="content" type="textarea" :rows="8" maxlength="10000" show-word-limit placeholder="分享你的思路和实践经验" />
          <el-button type="primary" :loading="submitting" :disabled="!content.trim()" @click="submitAnswer">发布回答</el-button>
        </aside>
      </div>
    </template>
  </section>
</template>
