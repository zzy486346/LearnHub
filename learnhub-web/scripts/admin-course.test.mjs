import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { createRequire } from 'node:module'
import { pathToFileURL } from 'node:url'
import { compileScript, parse } from '@vue/compiler-sfc'
import ts from 'typescript'
import { createRenderer } from 'vue'

const vueUrl = pathToFileURL(createRequire(import.meta.url).resolve('vue')).href
const renderer = createRenderer({
  createComment: () => ({}), createText: text => ({ text }), createElement: () => ({}),
  insert() {}, remove() {}, setText() {}, setElementText() {}, patchProp() {},
  parentNode: () => null, nextSibling: () => null,
})
const result = data => Promise.resolve({ data: { data } })
let serial = 0

async function mount(publish) {
  let status = 'DRAFT'
  const messages = []
  const api = {
    list: () => result([{ id: 7, title: '测试课程', status }]),
    detail: id => result({ id, title: '测试课程', status, chapters: [] }),
    publishCourse: async id => { await publish(id); status = 'PUBLISHED' },
  }
  globalThis.adminHarness = {
    adminCourseApi: api,
    ElMessage: { success: message => messages.push(['success', message]), error: message => messages.push(['error', message]) },
  }
  const source = (await readFile(new URL('../src/views/AdminView.vue', import.meta.url), 'utf8'))
    .replace('</script>', 'defineExpose({ selected, courses, publishing, publishCourse, selectCourse })\n</script>')
  const compiled = compileScript(parse(source).descriptor, { id: 'admin-course-test' }).content
    .replace(/from ['"]vue['"]/g, `from ${JSON.stringify(vueUrl)}`)
    .replace(/import \{ adminCourseApi \} from ['"]@\/api['"]/, 'const { adminCourseApi } = globalThis.adminHarness')
    .replace(/import \{ isSessionExpired \} from ['"]@\/api\/session['"]/, "const isSessionExpired = error => error?.code === 'AUTH_SESSION_EXPIRED'")
    .replace(/import \{ ElMessage \} from ['"]element-plus['"]/, 'const { ElMessage } = globalThis.adminHarness')
  const code = ts.transpileModule(compiled, { compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ESNext } }).outputText
  const module = await import(`data:text/javascript;base64,${Buffer.from(code + `\n// ${++serial}`).toString('base64')}`)
  module.default.render = () => null
  const app = renderer.createApp(module.default)
  const view = app.mount({})
  await new Promise(resolve => setImmediate(resolve))
  await view.selectCourse(view.courses[0])
  return { app, view, messages }
}

test('发布成功后刷新课程状态，已发布课程不再提交', async () => {
  const calls = []
  const { app, view, messages } = await mount(async id => calls.push(id))
  try {
    await view.publishCourse()
    assert.deepEqual(calls, [7])
    assert.equal(view.selected.status, 'PUBLISHED')
    assert.equal(view.courses[0].status, 'PUBLISHED')
    assert.equal(view.publishing, false)
    assert.equal(messages[0][0], 'success')
    await view.publishCourse()
    assert.deepEqual(calls, [7])
  } finally { app.unmount() }
})

test('发布期间显示加载状态，重复点击只发一次请求', async () => {
  let release
  let calls = 0
  const { app, view } = await mount(() => { calls++; return new Promise(resolve => { release = resolve }) })
  try {
    const pending = view.publishCourse()
    assert.equal(view.publishing, true)
    assert.equal(view.selected.status, 'DRAFT')
    await view.publishCourse()
    assert.equal(calls, 1)
    release()
    await pending
    assert.equal(view.publishing, false)
  } finally { app.unmount() }
})

test('发布失败保持草稿，展示真实错误并允许重试', async () => {
  let fail = true
  const { app, view, messages } = await mount(async () => {
    if (fail) throw { response: { data: { message: '课程不存在' } } }
  })
  try {
    await view.publishCourse()
    assert.equal(view.selected.status, 'DRAFT')
    assert.equal(view.courses[0].status, 'DRAFT')
    assert.equal(view.publishing, false)
    assert.deepEqual(messages[0], ['error', '课程不存在'])
    fail = false
    await view.publishCourse()
    assert.equal(view.selected.status, 'PUBLISHED')
  } finally { app.unmount() }
})

test('发布 API 使用对应课程的 POST 接口，按钮有状态与加载绑定', async () => {
  const calls = []
  globalThis.adminHttp = { post: (...args) => { calls.push(args); return result(null) } }
  const source = (await readFile(new URL('../src/api/index.ts', import.meta.url), 'utf8'))
    .replace(/import \{ http, logoutSession \} from ['"]\.\/http['"]/, 'const http = globalThis.adminHttp; const logoutSession = () => http.post("/auth/logout")')
  const code = ts.transpileModule(source, { compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ESNext } }).outputText
  const api = await import(`data:text/javascript;base64,${Buffer.from(code).toString('base64')}`)
  await api.adminCourseApi.publishCourse(7)
  assert.deepEqual(calls, [['/admin/courses/7/publish']])
  const view = await readFile(new URL('../src/views/AdminView.vue', import.meta.url), 'utf8')
  assert.match(view, /v-if="selected.status !== 'PUBLISHED'"[^>]*:loading="publishing"[^>]*@click="publishCourse"/)
})
