<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { assistantApi, type AssistantCitation, type CourseQaAnswer } from '@/api/assistant'
import { isSessionExpired } from '@/api/session'
import { AUTH_SESSION_CLEARED_EVENT, AUTH_SESSION_REPLACED_EVENT } from '@/api/http'
import { useAuthStore } from '@/stores/auth'

const props = defineProps<{ courseId: string | number }>()
const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const question = ref('')
const loading = ref(false)
const answer = ref<CourseQaAnswer | null>(null)
const errorMessage = ref('')
let activeRequestId = ''
let activeController: AbortController | null = null
let generation = 0

const remaining = computed(() => 500 - question.value.length)

function createRequestId() {
  if (globalThis.crypto.randomUUID) return globalThis.crypto.randomUUID()
  const bytes = globalThis.crypto.getRandomValues(new Uint8Array(16))
  bytes[6] = (bytes[6]! & 0x0f) | 0x40
  bytes[8] = (bytes[8]! & 0x3f) | 0x80
  const hex = Array.from(bytes, byte => byte.toString(16).padStart(2, '0')).join('')
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`
}

function citationRoute(citation: AssistantCitation) {
  if (!citation.courseId || !/^\d+$/.test(citation.courseId)) return null
  if (citation.lessonId && /^\d+$/.test(citation.lessonId)) {
    return `/courses/${citation.courseId}/lessons/${citation.lessonId}`
  }
  return `/courses/${citation.courseId}`
}

function clearResult() {
  answer.value = null
  errorMessage.value = ''
}

async function cancelCurrent() {
  const requestId = activeRequestId
  generation++
  activeRequestId = ''
  activeController?.abort()
  activeController = null
  loading.value = false
  if (requestId) {
    try { await assistantApi.cancel(requestId) } catch { /* 页面离开时取消失败无需打断导航。 */ }
  }
}

function goToLogin() {
  void router.push({ path: '/login', query: { redirect: route.fullPath } })
}

function messageFor(error: unknown) {
  const code = (error as { response?: { data?: { code?: string } } }).response?.data?.code
  if (code === 'AI_DISABLED') return '当前环境尚未启用问答模型，请稍后再试。'
  if (code === 'ASSISTANT_RATE_LIMITED' || code === 'ASSISTANT_BUSY' || code === 'AI_MODEL_RATE_LIMITED') return '问答请求较多，请稍后再试。'
  if (code === 'ASSISTANT_DAILY_LIMIT' || code === 'ASSISTANT_BUDGET_EXCEEDED') return '已达到本次或今日使用额度，请稍后再试。'
  if (code === 'ASSISTANT_EVIDENCE_CHANGED') return '课程权限或资料已变化，请重新提问。'
  if (code === 'ASSISTANT_DEADLINE_EXCEEDED' || code === 'ASSISTANT_CANCELLED' || code === 'AI_MODEL_TIMEOUT') return '本次问答已取消或超时，请重试。'
  if (code === 'ASSISTANT_OUTPUT_INVALID' || code === 'AI_MODEL_TRUNCATED') return '回答未通过完整性或来源校验，请重新提问。'
  return '暂时无法完成课程问答，请稍后重试。'
}

async function submit() {
  const normalized = question.value.trim()
  if (!normalized || normalized.length > 500 || loading.value) return
  if (!auth.authenticated) {
    errorMessage.value = '登录后才能使用课程问答。'
    return
  }
  const currentGeneration = ++generation
  const requestId = createRequestId()
  const controller = new AbortController()
  activeRequestId = requestId
  activeController = controller
  loading.value = true
  clearResult()
  try {
    const response = await assistantApi.answer(props.courseId, normalized, requestId, controller.signal)
    if (currentGeneration !== generation || activeRequestId !== requestId) return
    answer.value = response.data.data
  } catch (error) {
    if (currentGeneration !== generation || controller.signal.aborted) return
    errorMessage.value = isSessionExpired(error) ? '登录已失效，请重新登录。' : messageFor(error)
    void assistantApi.cancel(requestId).catch(() => undefined)
  } finally {
    if (currentGeneration === generation && activeRequestId === requestId) {
      activeRequestId = ''
      activeController = null
      loading.value = false
    }
  }
}

function resetPanel() {
  question.value = ''
  clearResult()
  void cancelCurrent()
}
watch(() => String(props.courseId), resetPanel, { flush: 'sync' })
watch(() => [auth.authenticated, auth.user?.id], resetPanel, { flush: 'sync' })
window.addEventListener(AUTH_SESSION_CLEARED_EVENT, resetPanel)
window.addEventListener(AUTH_SESSION_REPLACED_EVENT, resetPanel)
onBeforeUnmount(() => {
  window.removeEventListener(AUTH_SESSION_CLEARED_EVENT, resetPanel)
  window.removeEventListener(AUTH_SESSION_REPLACED_EVENT, resetPanel)
  void cancelCurrent()
})
</script>

<template>
  <section class="content-card course-assistant-panel" aria-labelledby="course-assistant-title">
    <div class="assistant-heading">
      <div><p class="eyebrow">COURSE ASSISTANT</p><h2 id="course-assistant-title">问课程学习助理</h2></div>
      <el-tag v-if="answer?.modelMode === 'FAKE'" type="warning" effect="dark">模拟回答</el-tag>
    </div>
    <p class="assistant-intro">基于你有权查看的课程讲解稿回答，并附上可核对的课时来源。</p>

    <form class="assistant-form" @submit.prevent="submit">
      <label for="course-assistant-question">你的问题</label>
      <el-input id="course-assistant-question" v-model="question" type="textarea" :rows="3" maxlength="500"
        resize="vertical" placeholder="例如：热点缓存失效后，怎样避免请求同时打到数据库？" :disabled="loading" />
      <div class="assistant-form-actions">
        <span class="assistant-character-count" :class="{ warning: remaining < 50 }">还可输入 {{ remaining }} 字</span>
        <el-button v-if="loading" class="assistant-action" @click="cancelCurrent">取消本次问答</el-button>
        <el-button v-else-if="!auth.authenticated" class="assistant-action" type="primary" @click="goToLogin">登录后提问</el-button>
        <el-button v-else class="assistant-action" type="primary" native-type="submit" :disabled="!question.trim()">开始问答</el-button>
      </div>
    </form>

    <div v-if="loading" class="assistant-state" role="status" aria-live="polite">
      <span class="assistant-pulse" aria-hidden="true"></span><span>正在检索授权资料并生成回答…</span>
    </div>
    <div v-else-if="errorMessage" class="assistant-state assistant-error" role="alert">{{ errorMessage }}</div>
    <div v-else-if="answer?.insufficientEvidence" class="assistant-state assistant-insufficient" role="status">
      <strong>课程资料不足</strong><span>当前授权讲解稿无法可靠回答这个问题，可以换一种问法或询问本课已讲内容。</span>
    </div>
    <div v-else-if="answer" class="assistant-result" aria-live="polite">
      <div class="assistant-answer">
        <div v-for="(segment, index) in answer.answerSegments" :key="`${answer.requestId}-${index}`" class="assistant-segment">
          <el-tag v-if="segment.kind === 'SUGGESTION'" size="small" type="success">学习建议</el-tag>
          <p>{{ segment.text }}</p>
          <span v-if="segment.evidenceIds?.length" class="assistant-evidence-ids">依据 {{ segment.evidenceIds.join('、') }}</span>
        </div>
      </div>
      <div v-if="answer.citations?.length" class="assistant-citations">
        <h3>回答来源</h3>
        <article v-for="citation in answer.citations" :key="citation.evidenceId" class="assistant-citation">
          <div><strong>{{ citation.title }}</strong><span v-if="citation.heading"> · {{ citation.heading }}</span></div>
          <p>{{ citation.excerpt }}</p>
          <router-link v-if="citationRoute(citation)" :to="citationRoute(citation)!">查看对应{{ citation.lessonId ? '课时' : '课程' }}</router-link>
        </article>
      </div>
      <details class="assistant-meta"><summary>查看执行记录</summary>
        <span>{{ answer.retrievalMode }}</span>
        <span v-if="answer.trace?.length">步骤：{{ answer.trace.join(' → ') }}</span>
        <span v-if="answer.usage?.usageComplete">Token：{{ answer.usage.actualTotalTokens }}</span>
      </details>
    </div>
  </section>
</template>

<style scoped>
.course-assistant-panel { margin: 24px 0; }
.assistant-heading, .assistant-form-actions { display: flex; align-items: center; justify-content: space-between; gap: 12px; flex-wrap: wrap; }
.assistant-heading h2 { margin: 4px 0 12px; }
.assistant-intro, .assistant-character-count, .assistant-evidence-ids, .assistant-meta { color: var(--text-secondary, #566477); }
.assistant-form label { display: block; font-weight: 600; margin-bottom: 10px; }
.assistant-form-actions { margin: 12px 0; }
.assistant-action { min-height: 44px; }
.assistant-state { padding: 16px; background: var(--surface-soft, #f1f5f9); border-radius: 12px; line-height: 1.7; }
.assistant-error { color: #9f2536; background: #fff1f2; }
.assistant-insufficient { display: grid; gap: 6px; }
.assistant-segment p, .assistant-citation p { white-space: pre-wrap; overflow-wrap: anywhere; line-height: 1.8; }
.assistant-citation { border-top: 1px solid var(--border-color, #dce3eb); padding: 14px 0; }
.assistant-citation a { display: inline-flex; min-height: 44px; align-items: center; text-decoration: underline; }
.assistant-meta summary { cursor: pointer; min-height: 44px; line-height: 44px; }
.assistant-meta span { display: inline-block; margin-right: 16px; }
@media (max-width: 600px) { .assistant-form-actions { align-items: stretch; } .assistant-action { flex: 1 0 auto; } }
</style>
