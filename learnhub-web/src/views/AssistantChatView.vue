<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { courseApi } from '@/api'
import { cancelRunThenAbort, chatApi, ChatStreamHttpError, type ChatConversation, type ChatMessage, type ChatStreamEvent } from '@/api/chat'
import type { AssistantCitation } from '@/api/assistant'
import { AUTH_SESSION_CLEARED_EVENT, AUTH_SESSION_REPLACED_EVENT } from '@/api/http'
import { useAuthStore } from '@/stores/auth'
import type { Course } from '@/types'
import PreferenceMemoryDialog from '@/components/PreferenceMemoryDialog.vue'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const conversations = ref<ChatConversation[]>([])
const activeConversation = ref<ChatConversation | null>(null)
const messages = ref<ChatMessage[]>([])
const courses = ref<Course[]>([])
const selectedCourseId = ref('')
const question = ref('')
const loading = ref(false)
const loadingHistory = ref(false)
const sending = ref(false)
const stopping = ref(false)
const sidebarOpen = ref(false)
const preferencesOpen = ref(false)
const draftText = ref('')
const draftSources = ref<AssistantCitation[]>([])
const pendingQuestion = ref('')
const retryQuestion = ref('')
const unknownRequest = ref<{ conversationId: string; message: string; clientRequestId: string } | null>(null)
const errorMessage = ref('')
const streamStatus = ref('选择课程并开始提问')
const modelMode = ref('')
const activeRunId = ref('')
const tools = ref<Array<{ id: string; name: string; status: string }>>([])
const messagePane = ref<HTMLElement | null>(null)
const nearBottom = ref(true)
let streamController: AbortController | null = null
let generation = 0
let lastStreamSeq = -1
let historyLoadEpoch = 0

const remaining = computed(() => 500 - question.value.length)
const canSend = computed(() => Boolean(question.value.trim() && selectedCourseId.value && !sending.value))
const canStop = computed(() => sending.value && !stopping.value)
const activeCourseTitle = computed(() => courses.value.find(item => String(item.id) === selectedCourseId.value)?.title || '所选课程')
const toolLabels: Record<string, string> = {
  search_courses: '课程搜索', get_course_outline: '课程目录',
  search_course_materials: '讲解稿检索', get_my_learning_progress: '本人学习进度',
}
const toolStatusLabels: Record<string, string> = {
  RUNNING: '执行中', COMPLETED: '已完成', FAILED: '执行失败', CANCELLED: '已取消',
}
const sourceKindLabels: Record<string, string> = {
  MATERIAL: '课程资料', OUTLINE: '课程目录', PROGRESS: '本人学习进度', COURSE: '公开课程',
}

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
  return citation.lessonId && /^\d+$/.test(citation.lessonId)
    ? `/courses/${citation.courseId}/lessons/${citation.lessonId}`
    : `/courses/${citation.courseId}`
}

function visibleContent(message: ChatMessage) {
  return message.restricted ? '此回答依赖的课程权限或资料版本已变化，已停止展示。' : message.content
}

function updateNearBottom() {
  const pane = messagePane.value
  if (!pane) return
  nearBottom.value = pane.scrollHeight - pane.scrollTop - pane.clientHeight < 100
}

async function scrollToLatest(force = false) {
  await nextTick()
  const pane = messagePane.value
  if (pane && (force || nearBottom.value)) pane.scrollTop = pane.scrollHeight
}

async function loadCourses() {
  try {
    courses.value = (await courseApi.list({ page: 1, size: 100 })).data.data.records || []
    const queryCourse = typeof route.query.courseId === 'string' ? route.query.courseId : ''
    if (!selectedCourseId.value && queryCourse && courses.value.some(item => String(item.id) === queryCourse)) {
      selectedCourseId.value = queryCourse
    }
    if (!selectedCourseId.value && courses.value.length) selectedCourseId.value = String(courses.value[0]!.id)
  } catch {
    ElMessage.error('课程列表加载失败，请稍后重试')
  }
}

async function loadConversations() {
  if (!auth.authenticated) return
  const loadEpoch = historyLoadEpoch
  loading.value = true
  try {
    const loaded = (await chatApi.conversations()).data.data || []
    if (loadEpoch !== historyLoadEpoch) return
    conversations.value = loaded
    loading.value = false
    const routeId = typeof route.params.conversationId === 'string' ? route.params.conversationId : ''
    if (routeId) await openConversation(routeId, false)
  } catch {
    if (loadEpoch === historyLoadEpoch) errorMessage.value = '历史会话加载失败，请检查网络后重试。'
  } finally {
    if (loadEpoch === historyLoadEpoch) loading.value = false
  }
}

async function openConversation(id: string, navigate = true) {
  if (sending.value) return
  const target = conversations.value.find(item => item.id === id)
  if (!target) {
    errorMessage.value = '该会话不存在或你无权访问。'
    return
  }
  activeConversation.value = target
  const loadEpoch = ++historyLoadEpoch
  selectedCourseId.value = String(target.courseId)
  sidebarOpen.value = false
  loadingHistory.value = true
  errorMessage.value = ''
  try {
    const loaded = (await chatApi.messages(id)).data.data || []
    if (loadEpoch !== historyLoadEpoch || activeConversation.value?.id !== id) return
    messages.value = loaded
    if (navigate && route.params.conversationId !== id) await router.push(`/assistant/${id}`)
    await scrollToLatest(true)
  } catch {
    if (loadEpoch === historyLoadEpoch && activeConversation.value?.id === id) {
      errorMessage.value = '会话消息加载失败，请稍后重试。'
    }
  } finally {
    if (loadEpoch === historyLoadEpoch) loadingHistory.value = false
  }
}

async function loadEarlier() {
  if (!activeConversation.value || !messages.value.length || loadingHistory.value) return
  const conversationId = activeConversation.value.id
  const loadEpoch = historyLoadEpoch
  loadingHistory.value = true
  const previousHeight = messagePane.value?.scrollHeight || 0
  try {
    const earlier = (await chatApi.messages(conversationId, messages.value[0]!.seq, 100)).data.data || []
    if (loadEpoch !== historyLoadEpoch || activeConversation.value?.id !== conversationId) return
    messages.value = [...earlier, ...messages.value]
    await nextTick()
    if (messagePane.value) messagePane.value.scrollTop += messagePane.value.scrollHeight - previousHeight
  } finally {
    if (loadEpoch === historyLoadEpoch) loadingHistory.value = false
  }
}

async function newConversationForCourse(courseId = selectedCourseId.value) {
  if (sending.value) return
  generation++
  historyLoadEpoch++
  streamController?.abort()
  streamController = null
  activeConversation.value = null
  messages.value = []
  unknownRequest.value = null
  clearTransient()
  selectedCourseId.value = courseId
  sidebarOpen.value = false
  await router.push({ path: '/assistant', query: courseId ? { courseId } : {} })
}

async function ensureConversation(message: string, expectedGeneration: number) {
  if (activeConversation.value) return activeConversation.value
  const created = (await chatApi.createConversation({
    courseId: selectedCourseId.value,
    title: message.length > 24 ? `${message.slice(0, 24)}…` : message,
  })).data.data
  if (expectedGeneration !== generation) return null
  conversations.value = [created, ...conversations.value.filter(item => item.id !== created.id)]
  activeConversation.value = created
  historyLoadEpoch++
  await router.replace(`/assistant/${created.id}`)
  return created
}

function applyStreamEvent(event: ChatStreamEvent, currentGeneration: number) {
  if (currentGeneration !== generation || !sending.value) return
  if (activeRunId.value && event.payload.runId !== activeRunId.value) return
  if (Number.isFinite(event.payload.seq) && event.payload.seq <= lastStreamSeq) return
  if (Number.isFinite(event.payload.seq)) lastStreamSeq = event.payload.seq
  if (event.type === 'accepted') {
    activeRunId.value = event.payload.runId
    modelMode.value = String(event.payload.modelMode || '')
    streamStatus.value = '已连接，正在生成回答'
  } else if (event.type === 'status') {
    streamStatus.value = String(event.payload.message || event.payload.status || '正在处理')
  } else if (event.type === 'tool_start') {
    tools.value.push({
      id: String(event.payload.toolCallId || event.payload.eventId),
      name: String(event.payload.toolName || 'tool'), status: 'RUNNING',
    })
    streamStatus.value = `正在执行${toolLabels[String(event.payload.toolName)] || '只读工具'}`
  } else if (event.type === 'tool_end') {
    const tool = tools.value.find(item => item.id === String(event.payload.toolCallId || event.payload.eventId))
    if (tool) tool.status = String(event.payload.status || 'COMPLETED')
  } else if (event.type === 'delta') {
    draftText.value += String(event.payload.text || '')
    streamStatus.value = '正在生成回答'
    void scrollToLatest()
  } else if (event.type === 'sources') {
    draftSources.value = Array.isArray(event.payload.sources) ? event.payload.sources : []
  } else if (event.type === 'error') {
    if (event.payload.clearDraft !== false) {
      draftText.value = ''
      draftSources.value = []
    }
    errorMessage.value = String(event.payload.message || '本次回答生成失败，请手动重试。')
    retryQuestion.value = pendingQuestion.value
    streamStatus.value = '生成失败'
  } else if (event.type === 'cancelled') {
    draftText.value = ''
    draftSources.value = []
    streamStatus.value = '已停止生成'
  } else if (event.type === 'final') {
    streamStatus.value = '回答已完成，正在确认正式历史'
  }
}

async function refreshFormalHistory() {
  if (!activeConversation.value) return
  const conversationId = activeConversation.value.id
  const loadEpoch = historyLoadEpoch
  const [loadedMessages, loadedConversations] = await Promise.all([
    chatApi.messages(conversationId), chatApi.conversations(),
  ])
  if (loadEpoch !== historyLoadEpoch || activeConversation.value?.id !== conversationId) return
  messages.value = loadedMessages.data.data || []
  conversations.value = loadedConversations.data.data || conversations.value
  pendingQuestion.value = ''
  draftText.value = ''
  draftSources.value = []
  tools.value = []
  await scrollToLatest()
}

async function reconcileInterruptedStream(runId: string) {
  if (!runId) return
  try {
    const run = (await chatApi.run(runId)).data.data
    if (run.status === 'COMPLETED') {
      unknownRequest.value = null
      await refreshFormalHistory()
      streamStatus.value = '回答已完成'
    } else if (run.status === 'RUNNING') {
      errorMessage.value = '连接已中断，但回答仍在后台运行。稍后可重新打开本会话查看结果。'
      retryQuestion.value = ''
    } else {
      unknownRequest.value = null
      draftText.value = ''
      draftSources.value = []
      errorMessage.value = run.status === 'CANCELLED' ? '本次生成已停止。' : '本次生成未完成，可以手动重试。'
      retryQuestion.value = pendingQuestion.value
    }
  } catch {
    errorMessage.value = '连接已中断，暂时无法确认运行状态。请稍后重新打开本会话。'
  }
}

async function sendQuestion(override?: string) {
  const message = (override ?? question.value).trim()
  if (!message || message.length > 500 || sending.value || !selectedCourseId.value) return
  if (!auth.authenticated) {
    await router.push({ path: '/login', query: { redirect: route.fullPath } })
    return
  }
  errorMessage.value = ''
  retryQuestion.value = ''
  pendingQuestion.value = message
  question.value = ''
  draftText.value = ''
  draftSources.value = []
  tools.value = []
  modelMode.value = ''
  activeRunId.value = ''
  lastStreamSeq = -1
  sending.value = true
  streamStatus.value = '正在建立安全连接'
  const currentGeneration = ++generation
  const controller = new AbortController()
  streamController = controller
  const reusableRequest = override && unknownRequest.value?.message === message
    && unknownRequest.value.conversationId === activeConversation.value?.id
    ? unknownRequest.value : null
  const clientRequestId = reusableRequest?.clientRequestId || createRequestId()
  if (!reusableRequest) unknownRequest.value = null
  let sawTerminal = false
  let finalStatus = ''
  try {
    const conversation = await ensureConversation(message, currentGeneration)
    if (!conversation || currentGeneration !== generation || controller.signal.aborted) return
    unknownRequest.value = { conversationId: conversation.id, message, clientRequestId }
    await scrollToLatest()
    await chatApi.streamRun(conversation.id, { message, clientRequestId }, (event) => {
      if (sawTerminal) return
      applyStreamEvent(event, currentGeneration)
      if (event.type === 'final' || event.type === 'error' || event.type === 'cancelled') {
        sawTerminal = true
        unknownRequest.value = null
      }
      if (event.type === 'final') finalStatus = String(event.payload.status || '')
    }, controller.signal)
    if (currentGeneration !== generation) return
    if (sawTerminal && (!finalStatus || finalStatus === 'COMPLETED')) await refreshFormalHistory()
    else if (sawTerminal) await reconcileInterruptedStream(activeRunId.value)
    else await reconcileInterruptedStream(activeRunId.value)
  } catch (error) {
    if (currentGeneration !== generation || controller.signal.aborted) return
    if (activeRunId.value && error instanceof Error
      && (error.message === 'SSE_TRUNCATED_EVENT' || error.message === 'SSE_EVENT_TOO_LARGE'
        || error.message === 'CHAT_STREAM_EVENT_INVALID')) {
      try { await chatApi.cancel(activeRunId.value) } catch { /* 解析错误仍按运行查询结果反馈。 */ }
    }
    if (error instanceof ChatStreamHttpError && error.status === 401) {
      try {
        await auth.fetchMe()
        errorMessage.value = '登录状态已更新，本次问题没有自动重放。请手动重试。'
      } catch {
        errorMessage.value = '登录已失效，本次问题没有自动重放。请重新登录后手动发送。'
      }
    } else {
      await reconcileInterruptedStream(activeRunId.value)
      if (!activeRunId.value) errorMessage.value = error instanceof Error ? error.message : '连接学习助理失败，请手动重试。'
    }
    retryQuestion.value ||= message
    draftText.value = ''
    draftSources.value = []
  } finally {
    if (currentGeneration === generation) {
      sending.value = false
      stopping.value = false
      streamController = null
    }
  }
}

async function stopGeneration() {
  if (!canStop.value) return
  stopping.value = true
  const runId = activeRunId.value
  const controller = streamController
  generation++
  if (controller) {
    if (runId) {
      try { await cancelRunThenAbort(runId, controller) } catch { /* 本机流已在 finally 中断开。 */ }
    } else {
      controller.abort()
    }
  }
  sending.value = false
  stopping.value = false
  streamController = null
  draftText.value = ''
  draftSources.value = []
  pendingQuestion.value = ''
  streamStatus.value = '已停止生成'
  if (activeConversation.value) {
    try { await refreshFormalHistory() } catch { /* 取消状态稍后可从历史重新读取。 */ }
  }
}

async function renameConversation(conversation: ChatConversation) {
  try {
    const result = await ElMessageBox.prompt('输入新的会话名称', '重命名会话', {
      inputValue: conversation.title, inputPattern: /\S+/, inputErrorMessage: '名称不能为空',
      confirmButtonText: '保存', cancelButtonText: '取消',
    })
    await chatApi.rename(conversation.id, result.value.trim())
    conversation.title = result.value.trim()
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') ElMessage.error('会话重命名失败，请稍后重试')
  }
}

async function deleteConversation(conversation: ChatConversation) {
  try {
    await ElMessageBox.confirm('删除后将无法在历史列表中恢复该会话。', '删除会话', {
      type: 'warning', confirmButtonText: '确认删除', cancelButtonText: '取消',
    })
    if (activeConversation.value?.id === conversation.id && sending.value) await stopGeneration()
    await chatApi.remove(conversation.id)
    if (unknownRequest.value?.conversationId === conversation.id) unknownRequest.value = null
    conversations.value = conversations.value.filter(item => item.id !== conversation.id)
    if (activeConversation.value?.id === conversation.id) await newConversationForCourse(String(conversation.courseId))
    ElMessage.success('会话已删除')
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') ElMessage.error('会话删除失败，请稍后重试')
  }
}

function clearTransient() {
  draftText.value = ''
  draftSources.value = []
  pendingQuestion.value = ''
  retryQuestion.value = ''
  errorMessage.value = ''
  tools.value = []
  activeRunId.value = ''
  modelMode.value = ''
  streamStatus.value = '选择课程并开始提问'
}

function resetPrivateState() {
  preferencesOpen.value = false
  generation++
  historyLoadEpoch++
  streamController?.abort()
  streamController = null
  conversations.value = []
  activeConversation.value = null
  messages.value = []
  unknownRequest.value = null
  question.value = ''
  sending.value = false
  stopping.value = false
  clearTransient()
}

async function handleSessionReplaced() {
  resetPrivateState()
  if (auth.authenticated && auth.user?.id != null) await Promise.all([loadCourses(), loadConversations()])
}

watch(() => route.params.conversationId, async (id) => {
  if (typeof id === 'string' && id !== activeConversation.value?.id && conversations.value.length) {
    await openConversation(id, false)
  }
})
watch(() => [auth.authenticated, auth.user?.id] as const, async ([authenticated, userId], previous) => {
  if (previous && authenticated === previous[0] && userId === previous[1]) return
  resetPrivateState()
  if (authenticated && userId != null) await Promise.all([loadCourses(), loadConversations()])
}, { flush: 'sync' })
watch(() => [messages.value.length, draftText.value], () => void scrollToLatest())

onMounted(async () => {
  window.addEventListener(AUTH_SESSION_CLEARED_EVENT, resetPrivateState)
  window.addEventListener(AUTH_SESSION_REPLACED_EVENT, handleSessionReplaced)
  await Promise.all([loadCourses(), loadConversations()])
})
onBeforeUnmount(() => {
  window.removeEventListener(AUTH_SESSION_CLEARED_EVENT, resetPrivateState)
  window.removeEventListener(AUTH_SESSION_REPLACED_EVENT, handleSessionReplaced)
  generation++
  streamController?.abort()
})
</script>

<template>
  <section class="assistant-workspace" aria-labelledby="assistant-page-title">
    <header class="workspace-heading">
      <div>
        <p class="eyebrow">LEARNING ASSISTANT</p>
        <h1 id="assistant-page-title">学习助理</h1>
        <p>围绕你有权学习的课程连续追问，回答完成后保存正式历史和来源。</p>
      </div>
      <el-tag v-if="modelMode === 'FAKE'" type="warning" effect="dark">模拟流</el-tag>
      <el-button class="preference-button" :disabled="!auth.authenticated" @click="preferencesOpen = true">学习偏好</el-button>
      <el-button class="sidebar-toggle" @click="sidebarOpen = !sidebarOpen">{{ sidebarOpen ? '收起会话' : '历史会话' }}</el-button>
    </header>

    <PreferenceMemoryDialog v-if="auth.authenticated" v-model="preferencesOpen" />
    <div class="workspace-grid" :class="{ 'sidebar-open': sidebarOpen }">
      <aside class="conversation-sidebar" aria-label="历史会话">
        <el-button class="new-chat-button" type="primary" @click="newConversationForCourse()">新建会话</el-button>
        <div v-if="loading" class="sidebar-state" role="status">正在加载会话…</div>
        <div v-else-if="!conversations.length" class="sidebar-state">还没有历史会话</div>
        <ul v-else class="conversation-list">
          <li v-for="conversation in conversations" :key="conversation.id" :class="{ active: activeConversation?.id === conversation.id }">
            <button type="button" class="conversation-main" @click="openConversation(conversation.id)">
              <strong>{{ conversation.title }}</strong>
              <span>{{ courses.find(item => item.id === conversation.courseId)?.title || `课程 ${conversation.courseId}` }}</span>
            </button>
            <div class="conversation-actions">
              <button type="button" aria-label="重命名会话" @click="renameConversation(conversation)">改名</button>
              <button type="button" aria-label="删除会话" @click="deleteConversation(conversation)">删除</button>
            </div>
          </li>
        </ul>
      </aside>

      <div class="chat-panel">
        <div class="course-context">
          <label for="assistant-course">本会话课程</label>
          <el-select id="assistant-course" v-model="selectedCourseId" :disabled="sending" @change="newConversationForCourse(String($event))">
            <el-option v-for="course in courses" :key="course.id" :label="course.title" :value="String(course.id)" />
          </el-select>
          <span v-if="activeConversation">切换课程会新建会话，当前历史仍归属 {{ activeCourseTitle }}。</span>
        </div>

        <div ref="messagePane" class="message-pane" tabindex="0" aria-label="聊天消息" :aria-busy="loadingHistory" @scroll="updateNearBottom">
          <button v-if="messages.length && messages[0]!.seq > 1" class="load-earlier" type="button" :disabled="loadingHistory" @click="loadEarlier">加载更早消息</button>
          <div v-if="!messages.length && !pendingQuestion" class="empty-chat">
            <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M4 5h16v11H8l-4 4V5Zm4 4h8m-8 3h5" /></svg>
            <h2>从一个具体问题开始</h2>
            <p>例如：热点缓存失效后，怎样避免请求同时打到数据库？</p>
          </div>
          <article v-for="message in messages" :key="message.id" class="message-row" :class="message.role.toLowerCase()">
            <div class="message-bubble" :class="{ restricted: message.restricted }">
              <span class="message-role">{{ message.role === 'USER' ? '你' : '学习助理' }}</span>
              <p>{{ visibleContent(message) }}</p>
              <section v-if="message.role === 'ASSISTANT' && !message.restricted && message.sources?.length" class="message-sources">
                <h3>回答来源</h3>
                <article v-for="source in message.sources" :key="source.evidenceId || `${source.documentId}-${source.chunkId}`">
                  <strong>[{{ source.evidenceId }}] {{ sourceKindLabels[source.kind] || '回答依据' }} · {{ source.title }}</strong><span v-if="source.heading"> · {{ source.heading }}</span>
                  <p>{{ source.excerpt }}</p>
                  <RouterLink v-if="citationRoute(source)" :to="citationRoute(source)!">查看对应{{ source.lessonId ? '课时' : '课程' }}</RouterLink>
                </article>
              </section>
            </div>
          </article>

          <article v-if="pendingQuestion" class="message-row user pending"><div class="message-bubble"><span class="message-role">你</span><p>{{ pendingQuestion }}</p></div></article>
          <article v-if="sending || draftText" class="message-row assistant pending">
            <div class="message-bubble draft">
              <span class="message-role">学习助理 · 暂定内容</span>
              <p v-if="draftText">{{ draftText }}</p><p v-else class="draft-placeholder">正在准备回答…</p>
              <div v-if="tools.length" class="tool-steps" aria-label="工具执行状态">
                <span v-for="tool in tools" :key="tool.id">{{ toolLabels[tool.name] || tool.name }} · {{ toolStatusLabels[tool.status] || '状态待确认' }}</span>
              </div>
              <section v-if="draftSources.length" class="message-sources provisional">
                <h3>待确认来源</h3>
                <article v-for="source in draftSources" :key="source.evidenceId || `${source.documentId}-${source.chunkId}`"><strong>[{{ source.evidenceId }}] {{ sourceKindLabels[source.kind] || '回答依据' }} · {{ source.title }}</strong><p>{{ source.excerpt }}</p></article>
              </section>
            </div>
          </article>
        </div>

        <div class="stream-announcement" role="status" aria-live="polite" aria-atomic="true">{{ streamStatus }}</div>
        <div v-if="errorMessage" class="chat-error" role="alert">
          <span>{{ errorMessage }}</span>
          <el-button v-if="retryQuestion" @click="sendQuestion(retryQuestion)">手动重试</el-button>
        </div>
        <form class="composer" @submit.prevent="sendQuestion()">
          <label for="assistant-question">继续提问</label>
          <el-input id="assistant-question" v-model="question" type="textarea" :rows="3" maxlength="500" resize="none"
            :disabled="sending" placeholder="输入问题；Enter 换行，点击按钮发送" />
          <div class="composer-actions">
            <span :class="{ warning: remaining < 50 }">还可输入 {{ remaining }} 字</span>
            <el-button v-if="sending" :disabled="!canStop" :loading="stopping" @click="stopGeneration">停止生成</el-button>
            <el-button v-else type="primary" native-type="submit" :disabled="!canSend">发送问题</el-button>
          </div>
        </form>
      </div>
    </div>
  </section>
</template>

<style scoped>
.assistant-workspace { min-height: calc(100dvh - 220px); }
.workspace-heading { display:flex; align-items:flex-end; justify-content:space-between; gap:20px; margin:8px 0 22px; }
.workspace-heading h1 { margin:4px 0 8px; font-size:clamp(30px,4vw,44px); }
.workspace-heading p:last-child { margin:0; color:var(--muted); line-height:1.6; }
.workspace-grid { display:grid; grid-template-columns:280px minmax(0,1fr); gap:18px; min-height:680px; }
.conversation-sidebar,.chat-panel { min-width:0; border:1px solid var(--line,#e3e7ee); border-radius:20px; background:#fff; box-shadow:0 8px 28px rgba(33,68,155,.06); }
.conversation-sidebar { padding:16px; }
.new-chat-button { width:100%; min-height:44px; }
.sidebar-state { padding:30px 10px; color:var(--muted); text-align:center; }
.conversation-list { margin:14px 0 0; padding:0; display:grid; gap:8px; list-style:none; }
.conversation-list li { border:1px solid transparent; border-radius:14px; background:#f7f8fb; }
.conversation-list li.active { border-color:rgba(75,94,255,.28); background:#eef0ff; }
.conversation-main { width:100%; min-height:58px; padding:12px; display:grid; gap:5px; border:0; color:var(--ink); background:transparent; text-align:left; cursor:pointer; }
.conversation-main strong,.conversation-main span { overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
.conversation-main span { color:var(--muted); font-size:12px; }
.conversation-actions { padding:0 8px 8px; display:flex; gap:6px; }
.conversation-actions button { min-height:44px; min-width:44px; padding:0 8px; border:0; border-radius:8px; color:var(--muted); background:transparent; cursor:pointer; }
.conversation-actions button:hover { color:var(--primary); background:#fff; }
.chat-panel { display:grid; grid-template-rows:auto minmax(340px,1fr) auto auto auto; overflow:hidden; }
.course-context { padding:15px 18px; display:grid; grid-template-columns:auto minmax(180px,300px) 1fr; align-items:center; gap:12px; border-bottom:1px solid var(--line,#e3e7ee); }
.course-context label { font-weight:700; }.course-context span { color:var(--muted); font-size:13px; }
.message-pane { min-height:0; max-height:58dvh; padding:22px; overflow:auto; scroll-behavior:smooth; background:#fafbfc; }
.empty-chat { min-height:330px; display:grid; place-content:center; justify-items:center; color:var(--muted); text-align:center; }
.empty-chat svg { width:54px; height:54px; fill:none; stroke:#6878ee; stroke-width:1.6; }.empty-chat h2 { margin:16px 0 4px; color:var(--ink); }
.load-earlier { min-height:44px; margin:0 auto 18px; display:block; border:0; color:var(--primary); background:transparent; cursor:pointer; }
.message-row { margin:0 0 18px; display:flex; }.message-row.user { justify-content:flex-end; }
.message-bubble { width:min(78%,760px); padding:16px 18px; border:1px solid #e4e7ee; border-radius:18px 18px 18px 5px; background:#fff; }
.user .message-bubble { color:#fff; border-color:#4b5eff; border-radius:18px 18px 5px 18px; background:#4b5eff; }
.message-bubble.restricted { color:#765614; border-color:#f0d58a; background:#fff9e8; }
.message-role { display:block; margin-bottom:7px; font-size:12px; font-weight:750; opacity:.72; }
.message-bubble>p { margin:0; white-space:pre-wrap; overflow-wrap:anywhere; line-height:1.75; }
.draft { border-style:dashed; }.draft-placeholder { color:var(--muted); }
.tool-steps { margin-top:12px; display:flex; flex-wrap:wrap; gap:8px; }.tool-steps span { padding:5px 9px; border-radius:999px; color:#3947a7; background:#eef0ff; font-size:12px; }
.message-sources { margin-top:16px; padding-top:14px; border-top:1px solid #e5e7ed; }.message-sources h3 { margin:0 0 9px; font-size:14px; }.message-sources article { padding:9px 0; }.message-sources article+article { border-top:1px solid #edf0f4; }.message-sources p { margin:5px 0; color:var(--muted); white-space:pre-wrap; overflow-wrap:anywhere; line-height:1.6; }.message-sources a { min-height:44px; display:inline-flex; align-items:center; color:var(--primary); text-decoration:underline; }
.user .message-sources p { color:inherit; }.provisional { opacity:.8; }
.stream-announcement { min-height:22px; padding:7px 20px 0; color:var(--muted); font-size:13px; }
.chat-error { margin:8px 18px 0; padding:12px 14px; display:flex; align-items:center; justify-content:space-between; gap:12px; border-radius:12px; color:#95253a; background:#fff1f2; }
.composer { padding:14px 18px 18px; }.composer label { display:block; margin-bottom:8px; font-weight:700; }.composer-actions { margin-top:10px; display:flex; justify-content:space-between; align-items:center; gap:12px; color:var(--muted); font-size:13px; }.composer-actions .warning { color:#a34b20; }.composer-actions .el-button { min-width:112px; min-height:44px; }
.sidebar-toggle { display:none; }
.preference-button { min-height:44px; }
@media (max-width:900px) {
  .workspace-heading { align-items:flex-start; flex-wrap:wrap; }.sidebar-toggle { display:inline-flex; }
  .workspace-grid { grid-template-columns:1fr; min-height:0; }.conversation-sidebar { display:none; }.sidebar-open .conversation-sidebar { display:block; }.chat-panel { min-height:680px; }
  .course-context { grid-template-columns:1fr 1.4fr; }.course-context span { grid-column:1 / -1; }
}
@media (max-width:560px) {
  .workspace-heading { margin-top:0; }.workspace-heading>div { flex-basis:100%; }
  .chat-panel { min-height:calc(100dvh - 150px); border-radius:16px; }.course-context { grid-template-columns:1fr; }.course-context span { grid-column:auto; }
  .message-pane { padding:16px 12px; max-height:55dvh; }.message-bubble { width:90%; padding:14px; }.composer { padding:12px; }
  .chat-error { align-items:stretch; flex-direction:column; }.composer-actions { align-items:stretch; }.composer-actions span { align-self:center; }
}
@media (prefers-reduced-motion: reduce) { .message-pane { scroll-behavior:auto; } }
</style>
