import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { createRequire } from 'node:module'
import { pathToFileURL } from 'node:url'
import { compileScript, parse } from '@vue/compiler-sfc'
import ts from 'typescript'
import { createRenderer, reactive } from 'vue'

const vueUrl = pathToFileURL(createRequire(import.meta.url).resolve('vue')).href
const renderer = createRenderer({
  createComment: () => ({}), createText: text => ({ text }), createElement: () => ({}),
  insert() {}, remove() {}, setText() {}, setElementText() {}, patchProp() {},
  parentNode: () => null, nextSibling: () => null,
})
const result = data => Promise.resolve({ data: { data } })
let serial = 0

async function mount(saveProgress, overrides = {}) {
  const route = reactive({ params: { courseId: '7', lessonId: '11' }, fullPath: '/courses/7/lessons/11' })
  const messages = []
  const guards = {}
  globalThis.window = new EventTarget()
  globalThis.localStorage = { getItem: () => 'access-token' }
  globalThis.fetch = async () => ({ ok: true })
  globalThis.learningHarness = {
    courseApi: { detail: overrides.detail || (() => result({
      id: 7,
      title: '测试课程',
      chapters: [{ id: 1, title: '第一章', lessons: [
        { id: 11, title: '当前课时', accessible: true, durationSeconds: 60 },
        { id: 12, title: '下一课时', accessible: true, durationSeconds: 60 },
      ] }],
    })) },
    learningApi: { progress: overrides.progress || (() => result([])), saveProgress },
    useAuthStore: () => reactive({ authenticated: true }),
    useRoute: () => route,
    useRouter: () => ({ push: async () => {} }),
    onBeforeRouteLeave: guard => { guards.leave = guard },
    onBeforeRouteUpdate: guard => { guards.update = guard },
    ElMessage: Object.fromEntries(['success', 'info', 'error', 'warning'].map(level => [level, message => messages.push([level, message])])),
    ElMessageBox: { confirm: async () => {} },
  }
  const source = (await readFile(new URL('../src/views/LessonView.vue', import.meta.url), 'utf8'))
    .replace('import.meta.env.VITE_API_BASE_URL', 'undefined')
    .replace('</script>', 'defineExpose({ loadLesson, handleEnded, retryProgressSave, queueProgressSave, saveWithKeepalive, saveState, pendingProgress, progress, videoRef, course, loading })\n</script>')
  const compiled = compileScript(parse(source).descriptor, { id: 'learning-flow-test' }).content
    .replace(/from ['"]vue['"]/g, `from ${JSON.stringify(vueUrl)}`)
    .replace(/import \{ onBeforeRouteLeave, onBeforeRouteUpdate, useRoute, useRouter \} from ['"]vue-router['"]/, 'const { onBeforeRouteLeave, onBeforeRouteUpdate, useRoute, useRouter } = globalThis.learningHarness')
    .replace(/import \{ ElMessage, ElMessageBox \} from ['"]element-plus['"]/, 'const { ElMessage, ElMessageBox } = globalThis.learningHarness')
    .replace(/import \{ courseApi, learningApi \} from ['"]@\/api['"]/, 'const { courseApi, learningApi } = globalThis.learningHarness')
    .replace(/import \{ isSessionExpired \} from ['"]@\/api\/session['"]/, "const isSessionExpired = error => error?.code === 'AUTH_SESSION_EXPIRED'")
    .replace(/import \{ useAuthStore \} from ['"]@\/stores\/auth['"]/, 'const { useAuthStore } = globalThis.learningHarness')
  const code = ts.transpileModule(compiled, { compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ESNext } }).outputText
  const module = await import(`data:text/javascript;base64,${Buffer.from(code + `\n// ${++serial}`).toString('base64')}`)
  module.default.render = () => null
  const app = renderer.createApp(module.default)
  const view = app.mount({})
  await new Promise(resolve => setImmediate(resolve))
  await new Promise(resolve => setImmediate(resolve))
  view.videoRef = { currentTime: 42, duration: 60 }
  return { app, view, messages, guards, route }
}

async function mountCourseDetail(detail) {
  const route = reactive({ params: { id: '1' }, fullPath: '/courses/1' })
  globalThis.courseDetailHarness = {
    courseApi: {
      detail,
      likeStatus: () => result({ liked: false, count: 0 }),
      favoriteStatus: () => result(false),
    },
    orderApi: {},
    couponApi: {},
    useAuthStore: () => reactive({ authenticated: false }),
    useRoute: () => route,
    useRouter: () => ({ push: async () => {} }),
    ElMessage: Object.fromEntries(['success', 'info', 'error', 'warning'].map(level => [level, () => {}])),
  }
  const source = (await readFile(new URL('../src/views/CourseDetailView.vue', import.meta.url), 'utf8'))
    .replace('</script>', 'defineExpose({ loadCourse, course, loading, errorMessage })\n</script>')
  const compiled = compileScript(parse(source).descriptor, { id: 'course-route-race-test' }).content
    .replace(/from ['"]vue['"]/g, `from ${JSON.stringify(vueUrl)}`)
    .replace(/import \{ useRoute, useRouter \} from ['"]vue-router['"]/, 'const { useRoute, useRouter } = globalThis.courseDetailHarness')
    .replace(/import \{ ElMessage \} from ['"]element-plus['"]/, 'const { ElMessage } = globalThis.courseDetailHarness')
    .replace(/import \{ couponApi, courseApi, orderApi \} from ['"]@\/api['"]/, 'const { couponApi, courseApi, orderApi } = globalThis.courseDetailHarness')
    .replace(/import \{ isSessionExpired \} from ['"]@\/api\/session['"]/, "const isSessionExpired = error => error?.code === 'AUTH_SESSION_EXPIRED'")
    .replace(/import \{ useAuthStore \} from ['"]@\/stores\/auth['"]/, 'const { useAuthStore } = globalThis.courseDetailHarness')
  const code = ts.transpileModule(compiled, { compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ESNext } }).outputText
  const module = await import(`data:text/javascript;base64,${Buffer.from(code + `\n// ${++serial}`).toString('base64')}`)
  module.default.render = () => null
  const app = renderer.createApp(module.default)
  const view = app.mount({})
  await new Promise(resolve => setImmediate(resolve))
  return { app, view, route }
}

test('完成状态保存失败时不提示完成，保留进度并允许原值重试', async () => {
  let failing = true
  const payloads = []
  const { app, view, messages } = await mount(async (courseId, body) => {
    payloads.push([courseId, body])
    if (failing) throw new Error('offline')
    return result({ lessonId: body.lessonId, positionSeconds: body.positionSeconds, completed: body.completed })
  })
  try {
    await view.handleEnded()
    assert.equal(view.saveState, 'error')
    assert.equal(view.pendingProgress.positionSeconds, 42)
    assert.equal(view.pendingProgress.completed, true)
    assert.deepEqual(messages, [['error', '课时完成状态保存失败，请重试']])

    await view.queueProgressSave(undefined, true)
    assert.equal(payloads[1][1].completed, true)

    view.videoRef.currentTime = 3
    failing = false
    await view.retryProgressSave()
    assert.equal(payloads[2][1].positionSeconds, 42)
    assert.equal(payloads[2][1].completed, true)
    assert.equal(view.pendingProgress, null)
    assert.equal(view.saveState, 'saved')
    assert.deepEqual(messages[1], ['success', '学习进度已重新同步'])
  } finally { app.unmount() }
})

test('存在未同步进度时关闭页面会发起保活保存并触发浏览器离开提醒', async () => {
  const requests = []
  const { app, view } = await mount(() => Promise.reject(new Error('offline')))
  try {
    await view.handleEnded()
    globalThis.fetch = async (url, init) => { requests.push([url, init]); return { ok: true } }
    let prevented = false
    const event = { preventDefault: () => { prevented = true }, returnValue: undefined }
    view.saveWithKeepalive(event)
    assert.equal(prevented, true)
    assert.equal(event.returnValue, '')
    assert.equal(JSON.parse(requests[0][1].body).completed, true)
    assert.equal(JSON.parse(requests[0][1].body).positionSeconds, 42)
  } finally { app.unmount() }
})

test('快速切换课程时旧课程响应不能覆盖当前课程', async () => {
  const pending = new Map()
  const detail = id => new Promise(resolve => pending.set(String(id), resolve))
  const { app, view, route } = await mountCourseDetail(detail)
  try {
    route.params.id = '2'
    await new Promise(resolve => setImmediate(resolve))
    pending.get('2')({ data: { data: { id: 2, title: '新课程', chapters: [] } } })
    await new Promise(resolve => setImmediate(resolve))
    pending.get('1')({ data: { data: { id: 1, title: '旧课程', chapters: [] } } })
    await new Promise(resolve => setImmediate(resolve))
    assert.equal(view.course.id, 2)
    assert.equal(view.loading, false)
  } finally { app.unmount() }
})

test('快速切换课时时旧详情和旧进度响应不能覆盖当前课时', async () => {
  const details = new Map()
  const progresses = new Map()
  const course = (id, lessonId) => ({
    id, title: `课程${id}`, chapters: [{ id, title: '章节', lessons: [
      { id: lessonId, title: `课时${lessonId}`, accessible: true, durationSeconds: 60 },
    ] }],
  })
  const { app, view, route } = await mount(
    () => result({}),
    {
      detail: id => new Promise(resolve => details.set(String(id), resolve)),
      progress: id => new Promise(resolve => progresses.set(String(id), resolve)),
    },
  )
  try {
    route.params.courseId = '8'
    route.params.lessonId = '21'
    await new Promise(resolve => setImmediate(resolve))
    details.get('8')({ data: { data: course(8, 21) } })
    await new Promise(resolve => setImmediate(resolve))
    progresses.get('8')({ data: { data: [{ lessonId: 21, positionSeconds: 30, completed: false }] } })
    await new Promise(resolve => setImmediate(resolve))
    details.get('7')({ data: { data: course(7, 11) } })
    await new Promise(resolve => setImmediate(resolve))
    assert.equal(view.course.id, 8)
    assert.equal(view.progress[0].lessonId, 21)
    assert.equal(view.loading, false)
  } finally { app.unmount() }
})
