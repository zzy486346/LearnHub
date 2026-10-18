import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { createRequire } from 'node:module'
import { pathToFileURL } from 'node:url'
import { compileScript, parse } from '@vue/compiler-sfc'
import ts from 'typescript'
import { createRenderer, h, nextTick, reactive, ref } from 'vue'

const vueUrl = pathToFileURL(createRequire(import.meta.url).resolve('vue')).href
const renderer = createRenderer({
  createComment: () => ({}), createText: text => ({ text }), createElement: () => ({}),
  insert() {}, remove() {}, setText() {}, setElementText() {}, patchProp() {}, parentNode: () => null, nextSibling: () => null,
})
const result = data => ({ data: { data } })
const conversation = { id: 'c1', ownerUserId: 7, courseId: 101, title: '会话一', nextSeq: 1 }
let serial = 0

async function flush() {
  await nextTick()
  await new Promise(resolve => setImmediate(resolve))
  await nextTick()
}

async function mount(overrides = {}) {
  globalThis.window = new EventTarget()
  const auth = reactive({ authenticated: true, user: { id: 7 }, fetchMe: async () => {} })
  const route = reactive({ params: { conversationId: overrides.routeConversationId ?? 'c1' }, query: {}, fullPath: '/assistant/c1' })
  const calls = { create: [], streams: [], cancels: [], messages: [], conversations: 0 }
  const chatApi = {
    conversations: async () => { calls.conversations++; return result(overrides.conversations ?? [conversation]) },
    messages: async id => { calls.messages.push(id); return result(overrides.messages ?? []) },
    createConversation: async body => { calls.create.push(body); return overrides.createConversation?.(body) ?? result(conversation) },
    streamRun: async (id, body, onEvent, signal) => {
      calls.streams.push({ id, body, onEvent, signal })
      return overrides.streamRun?.(id, body, onEvent, signal)
    },
    cancel: async id => { calls.cancels.push(id); return result({ id, conversationId: 'c1', status: 'CANCELLED' }) },
    run: async id => result({ id, conversationId: 'c1', status: 'RUNNING' }),
    rename: async () => result(null), remove: async () => result(null),
  }
  const harness = {
    chatApi,
    cancelRunThenAbort: async (id, controller) => { try { await chatApi.cancel(id) } finally { controller.abort() } },
    ChatStreamHttpError: class ChatStreamHttpError extends Error {},
    courseApi: { list: async () => result({ records: [{ id: 101, title: 'Redis 缓存实战' }] }) },
    useAuthStore: () => auth,
    useRoute: () => route,
    useRouter: () => ({ push: async () => {}, replace: async () => {} }),
    ElMessage: { error() {}, success() {} },
    ElMessageBox: { prompt: async () => { throw 'cancel' }, confirm: async () => { throw 'cancel' } },
  }
  globalThis.chatWorkspaceHarness = harness
  const source = (await readFile(new URL('../src/views/AssistantChatView.vue', import.meta.url), 'utf8'))
    .replace('</script>', `defineExpose({
      question, sendQuestion, stopGeneration, activeConversation, sending, draftText,
      retryQuestion, unknownRequest, messages, conversations, activeRunId
    })\n</script>`)
  const compiled = compileScript(parse(source).descriptor, { id: 'chat-workspace-test' }).content
    .replace(/from ['"]vue['"]/g, `from ${JSON.stringify(vueUrl)}`)
    .replace(/import \{ useRoute, useRouter \} from ['"]vue-router['"]/, 'const { useRoute, useRouter } = globalThis.chatWorkspaceHarness')
    .replace(/import \{ ElMessage, ElMessageBox \} from ['"]element-plus['"]/, 'const { ElMessage, ElMessageBox } = globalThis.chatWorkspaceHarness')
    .replace(/import \{ courseApi \} from ['"]@\/api['"]/, 'const { courseApi } = globalThis.chatWorkspaceHarness')
    .replace(/import \{ cancelRunThenAbort,[^}]*\} from ['"]@\/api\/chat['"]/,
      'const { cancelRunThenAbort, chatApi, ChatStreamHttpError } = globalThis.chatWorkspaceHarness')
    .replace(/import \{ AUTH_SESSION_CLEARED_EVENT, AUTH_SESSION_REPLACED_EVENT \} from ['"]@\/api\/http['"]/,
      "const AUTH_SESSION_CLEARED_EVENT='learnhub:auth-session-cleared', AUTH_SESSION_REPLACED_EVENT='learnhub:auth-session-replaced'")
    .replace(/import \{ useAuthStore \} from ['"]@\/stores\/auth['"]/, 'const { useAuthStore } = globalThis.chatWorkspaceHarness')
  const code = ts.transpileModule(compiled, {
    compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ESNext },
  }).outputText
  const module = await import(`data:text/javascript;base64,${Buffer.from(`${code}\n// ${++serial}`).toString('base64')}`)
  module.default.render = () => null
  const component = ref(null)
  const app = renderer.createApp({ setup: () => () => h(module.default, { ref: component }) })
  app.mount({})
  await flush()
  return { app, view: component.value, auth, route, calls }
}

test('账户切换同步中止旧流并丢弃迟到增量', async () => {
  let release
  const app = await mount({ streamRun: () => new Promise(resolve => { release = resolve }) })
  try {
    app.view.question = '旧账户问题'
    const pending = app.view.sendQuestion()
    await flush()
    const stream = app.calls.streams[0]
    stream.onEvent({ type: 'accepted', payload: { runId: 'r-old', eventId: 'e1', seq: 1 } })
    stream.onEvent({ type: 'delta', payload: { runId: 'r-old', eventId: 'e2', seq: 2, text: '旧内容' } })
    assert.equal(app.view.draftText, '旧内容')
    app.auth.user = { id: 8 }
    assert.equal(stream.signal.aborted, true)
    assert.equal(app.view.draftText, '')
    stream.onEvent({ type: 'delta', payload: { runId: 'r-old', eventId: 'e3', seq: 3, text: '迟到内容' } })
    assert.equal(app.view.draftText, '')
    release(); await pending
  } finally { app.app.unmount() }
})

test('创建会话尚未返回时停止，不启动生成且迟到会话不复活', async () => {
  let releaseCreate
  const app = await mount({
    routeConversationId: '', conversations: [],
    createConversation: () => new Promise(resolve => { releaseCreate = resolve }),
  })
  try {
    app.view.question = 'accepted 前停止'
    const pending = app.view.sendQuestion()
    await flush()
    assert.equal(app.view.sending, true)
    await app.view.stopGeneration()
    releaseCreate(result(conversation))
    await pending
    assert.equal(app.calls.streams.length, 0)
    assert.equal(app.view.activeConversation, null)
    assert.equal(app.view.sending, false)
  } finally { app.app.unmount() }
})

test('结果未知后的手动重试复用原clientRequestId', async () => {
  let attempt = 0
  const app = await mount({
    streamRun: async (id, body, onEvent) => {
      attempt++
      if (attempt === 1) throw new Error('network disconnected before accepted')
      onEvent({ type: 'accepted', payload: { runId: 'r1', eventId: 'e1', seq: 1 } })
      onEvent({ type: 'final', payload: { runId: 'r1', eventId: 'e2', seq: 2, status: 'COMPLETED' } })
    },
  })
  try {
    app.view.question = '复用请求标识'
    await app.view.sendQuestion()
    assert.equal(app.calls.streams.length, 1)
    assert.equal(app.view.retryQuestion, '复用请求标识')
    await app.view.sendQuestion(app.view.retryQuestion)
    assert.equal(app.calls.streams.length, 2)
    assert.equal(app.calls.streams[1].body.clientRequestId, app.calls.streams[0].body.clientRequestId)
  } finally { app.app.unmount() }
})
