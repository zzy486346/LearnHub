import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { createRequire } from 'node:module'
import { pathToFileURL } from 'node:url'
import { compileScript, parse } from '@vue/compiler-sfc'
import ts from 'typescript'
import { createRenderer, reactive, ref, h, nextTick } from 'vue'

const vueUrl = pathToFileURL(createRequire(import.meta.url).resolve('vue')).href
const renderer = createRenderer({
  createComment: () => ({}), createText: text => ({ text }), createElement: () => ({}),
  insert() {}, remove() {}, setText() {}, setElementText() {}, patchProp() {}, parentNode: () => null, nextSibling: () => null,
})
const result = data => ({ data: { data } })
const answer = { requestId: 'r', modelMode: 'FAKE', answerSegments: [{ text: 'Lua 原子执行', evidenceIds: ['E1'], kind: 'FACT' }], citations: [], insufficientEvidence: false, trace: [] }
let serial = 0
async function mount(handler = async () => result(answer), cancel = async () => result(true)) {
  globalThis.window = new EventTarget()
  const auth = reactive({ authenticated: true, user: { id: '7' } })
  const calls = []
  const cancellations = []
  globalThis.assistantHarness = {
    assistantApi: { answer: (...args) => { calls.push(args); return handler(...args) }, cancel: id => { cancellations.push(id); return cancel(id) } },
    useAuthStore: () => auth,
    useRoute: () => ({ fullPath: '/courses/1' }), useRouter: () => ({ push() {} }),
  }
  const source = (await readFile(new URL('../src/components/CourseAssistantPanel.vue', import.meta.url), 'utf8'))
    .replace('</script>', 'defineExpose({ question, submit, answer, loading, errorMessage, cancelCurrent, citationRoute })\n</script>')
  const compiled = compileScript(parse(source).descriptor, { id: 'assistant-test' }).content
    .replace(/from ['"]vue['"]/g, `from ${JSON.stringify(vueUrl)}`)
    .replace(/import \{ useRoute, useRouter \} from ['"]vue-router['"]/, 'const { useRoute, useRouter } = globalThis.assistantHarness')
    .replace(/import \{[^}]*\} from ['"]@\/api\/assistant['"]/, 'const { assistantApi } = globalThis.assistantHarness')
    .replace(/import \{ isSessionExpired \} from ['"]@\/api\/session['"]/, "const isSessionExpired = error => error?.code === 'AUTH_SESSION_EXPIRED'")
    .replace(/import \{ AUTH_SESSION_CLEARED_EVENT, AUTH_SESSION_REPLACED_EVENT \} from ['"]@\/api\/http['"]/, "const AUTH_SESSION_CLEARED_EVENT='learnhub:auth-session-cleared', AUTH_SESSION_REPLACED_EVENT='learnhub:auth-session-replaced'")
    .replace(/import \{ useAuthStore \} from ['"]@\/stores\/auth['"]/, 'const { useAuthStore } = globalThis.assistantHarness')
  const code = ts.transpileModule(compiled, { compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ESNext } }).outputText
  const module = await import(`data:text/javascript;base64,${Buffer.from(code + `\n// ${++serial}`).toString('base64')}`)
  module.default.render = () => null
  const props = reactive({ courseId: '1' })
  const component = ref(null)
  const app = renderer.createApp({ setup: () => () => h(module.default, { courseId: props.courseId, ref: component }) })
  app.mount({})
  await nextTick()
  return { app, view: component.value, props, auth, calls, cancellations }
}

test('问题经登录身份提交，使用UUID且返回来源只生成站内路径', async () => {
  const app = await mount()
  try {
    app.view.question = ' Lua '
    await app.view.submit()
    assert.equal(app.calls.length, 1)
    assert.equal(app.calls[0][0], '1')
    assert.equal(app.calls[0][1], 'Lua')
    assert.match(app.calls[0][2], /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/)
    assert.equal(app.view.answer.modelMode, 'FAKE')
    assert.equal(app.view.citationRoute({ courseId: '1', lessonId: '2' }), '/courses/1/lessons/2')
    assert.equal(app.view.citationRoute({ courseId: 'https://evil.invalid' }), null)
  } finally { app.app.unmount() }
})

test('未登录、空白和超长问题均不发送生成请求', async () => {
  const app = await mount()
  try {
    app.view.question = ' '
    await app.view.submit()
    app.view.question = 'x'.repeat(501)
    await app.view.submit()
    app.auth.authenticated = false
    app.view.question = 'Lua'
    await app.view.submit()
    assert.equal(app.calls.length, 0)
    assert.match(app.view.errorMessage, /登录/)
  } finally { app.app.unmount() }
})

test('重复提交被锁定，取消传播至HTTP和服务端并丢弃迟到结果', async () => {
  let resolve
  const app = await mount(() => new Promise(done => { resolve = done }))
  try {
    app.view.question = 'Lua'
    const pending = app.view.submit()
    await app.view.submit()
    assert.equal(app.calls.length, 1)
    await app.view.cancelCurrent()
    assert.equal(app.calls[0][3].aborted, true)
    assert.equal(app.cancellations.length, 1)
    resolve(result(answer)); await pending
    assert.equal(app.view.answer, null)
    assert.equal(app.view.loading, false)
  } finally { app.app.unmount() }
})

test('换课程立即清旧答案，不等待服务端取消返回', async () => {
  let resolve
  const app = await mount(() => new Promise(done => { resolve = done }), () => new Promise(() => {}))
  try {
    app.view.answer = answer
    app.view.question = 'Lua'
    const pending = app.view.submit()
    app.props.courseId = '2'; await nextTick()
    assert.equal(app.view.answer, null)
    assert.equal(app.view.question, '')
    assert.equal(app.view.loading, false)
    resolve(result(answer)); await pending
    assert.equal(app.view.answer, null)
  } finally { app.app.unmount() }
})

test('同为已登录状态时切换账户仍清除旧回答', async () => {
  const app = await mount()
  try {
    app.view.question = 'Lua'; await app.view.submit()
    assert.ok(app.view.answer)
    window.dispatchEvent(new Event('learnhub:auth-session-replaced'))
    assert.equal(app.view.answer, null)
    app.view.answer = answer
    app.auth.user = { id: '8' }
    assert.equal(app.view.answer, null)
  } finally { app.app.unmount() }
})

test('资料不足与模型禁用分别呈现且不自动重试', async () => {
  const insufficient = await mount(async () => result({ ...answer, insufficientEvidence: true, answerSegments: [] }))
  try {
    insufficient.view.question = '论文'; await insufficient.view.submit()
    assert.equal(insufficient.view.answer.insufficientEvidence, true)
  } finally { insufficient.app.unmount() }
  const failure = await mount(async () => { throw { response: { data: { code: 'AI_DISABLED' } } } })
  try {
    failure.view.question = 'Lua'; await failure.view.submit()
    assert.match(failure.view.errorMessage, /尚未启用/)
    assert.equal(failure.calls.length, 1)
  } finally { failure.app.unmount() }
})
