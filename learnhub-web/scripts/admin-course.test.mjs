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

async function mount(publish = async () => {}, overrides = {}, confirm = async () => {}) {
  let status = 'DRAFT'
  const messages = []
  const api = {
    list: (page = 1) => result({ records: [{ id: 7, title: '测试课程', status }], total: 1, current: page, size: 10 }),
    detail: id => result({ id, title: '测试课程', status, chapters: [] }),
    publishCourse: async id => { await publish(id); status = 'PUBLISHED' },
    categories: () => result([{ id: 1, name: '后端开发', sortOrder: 1, status: 'ENABLED' }]),
    tags: () => result([]),
    courseTags: () => result([]),
    ...overrides,
  }
  globalThis.adminHarness = {
    adminCourseApi: api,
    couponApi: {},
    ElMessage: { success: message => messages.push(['success', message]), error: message => messages.push(['error', message]), warning: message => messages.push(['warning', message]) },
    ElMessageBox: { confirm },
  }
  const source = (await readFile(new URL('../src/views/AdminView.vue', import.meta.url), 'utf8'))
    .replace('</script>', 'defineExpose({ selected, courses, publishing, publishCourse, selectCourse, loadCourses, coursePage, courseTotal, pageSize, changeCoursePage, openLessonDialog, lessonForm, createLesson, deleteLesson, deletingLessonId, submitting, createCourse, courseForm })\n</script>')
  const compiled = compileScript(parse(source).descriptor, { id: 'admin-course-test' }).content
    .replace(/from ['"]vue['"]/g, `from ${JSON.stringify(vueUrl)}`)
    .replace(/import \{ adminCourseApi, couponApi \} from ['"]@\/api['"]/, 'const { adminCourseApi, couponApi } = globalThis.adminHarness')
    .replace(/import \{ isSessionExpired \} from ['"]@\/api\/session['"]/, "const isSessionExpired = error => error?.code === 'AUTH_SESSION_EXPIRED'")
    .replace(/import \{ ElMessage, ElMessageBox \} from ['"]element-plus['"]/, 'const { ElMessage, ElMessageBox } = globalThis.adminHarness')
  const code = ts.transpileModule(compiled, { compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ESNext } }).outputText
  const module = await import(`data:text/javascript;base64,${Buffer.from(code + `\n// ${++serial}`).toString('base64')}`)
  module.default.render = () => null
  const app = renderer.createApp(module.default)
  const view = app.mount({})
  await new Promise(resolve => setImmediate(resolve))
  if (view.courses[0]) await view.selectCourse(view.courses[0])
  return { app, view, messages, api }
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

function catalogApi(items, calls) {
  return {
    list: page => {
      calls.push(page)
      return result({ records: items.slice((page - 1) * 10, page * 10), total: items.length, current: page, size: 10 })
    },
    detail: id => result({ id, title: `课程${id}`, chapters: [] }),
  }
}

test('课程列表每页10条、末页不足10条，翻页只请求对应页并清除旧选中', async () => {
  const items = Array.from({ length: 21 }, (_, i) => ({ id: i + 1, title: `课程${i + 1}` }))
  const calls = []
  const { app, view } = await mount(undefined, catalogApi(items, calls))
  try {
    assert.equal(view.courses.length, 10)
    assert.equal(view.courseTotal, 21)
    assert.equal(view.pageSize, 10)
    await view.changeCoursePage(2)
    assert.equal(view.coursePage, 2)
    assert.equal(view.courses[0].id, 11)
    assert.equal(view.selected, undefined)
    await view.changeCoursePage(3)
    assert.equal(view.courses.length, 1)
    assert.deepEqual(calls, [1, 2, 3])
  } finally { app.unmount() }
})

test('列表请求失败保留当前页，空末页回退到有效页面', async () => {
  const items = Array.from({ length: 21 }, (_, i) => ({ id: i + 1 }))
  const calls = []
  const api = catalogApi(items, calls)
  const { app, view, messages } = await mount(undefined, api)
  try {
    const original = api.list
    globalThis.adminHarness.adminCourseApi.list = () => Promise.reject(new Error('offline'))
    await view.changeCoursePage(2)
    assert.equal(view.coursePage, 1)
    assert.equal(view.courses.length, 10)
    assert.equal(messages.at(-1)[0], 'error')
    globalThis.adminHarness.adminCourseApi.list = original
    await view.changeCoursePage(3)
    items.pop()
    await view.loadCourses(undefined, 3)
    assert.equal(view.coursePage, 2)
    assert.equal(view.courseTotal, 20)
    assert.equal(view.courses.length, 10)
  } finally { app.unmount() }
})

test('第二页创建课程后回到第一页并选中新课程', async () => {
  const items = Array.from({ length: 21 }, (_, i) => ({ id: i + 1 }))
  const calls = []
  const { app, view } = await mount(undefined, {
    ...catalogApi(items, calls),
    createCourse: () => { items.unshift({ id: 99 }); return result(99) },
  })
  try {
    await view.changeCoursePage(2)
    Object.assign(view.courseForm, { categoryId: 1, title: '新课', instructor: '讲师' })
    await view.createCourse()
    assert.equal(view.coursePage, 1)
    assert.equal(view.selected.id, 99)
    assert.equal(view.courseTotal, 22)
    assert.equal(view.courses.length, 10)
  } finally { app.unmount() }
})

test('第二页创建后新课程详情失败，不保留上一页旧课程编辑器', async () => {
  const items = Array.from({ length: 21 }, (_, i) => ({ id: i + 1 }))
  const api = catalogApi(items, [])
  const { app, view, messages } = await mount(undefined, {
    ...api,
    detail: id => id === 99 ? Promise.reject(new Error('offline')) : api.detail(id),
    createCourse: () => { items.unshift({ id: 99 }); return result(99) },
  })
  try {
    await view.changeCoursePage(2)
    await view.selectCourse(view.courses[0])
    assert.equal(view.selected.id, 11)
    Object.assign(view.courseForm, { categoryId: 1, title: '新课', instructor: '讲师' })
    await view.createCourse()
    assert.equal(view.coursePage, 1)
    assert.equal(view.courses[0].id, 99)
    assert.equal(view.selected, undefined)
    assert.deepEqual(messages.at(-1), ['error', '课程详情加载失败'])
  } finally { app.unmount() }
})

function lessonApi(calls) {
  let lessons = [{ id: 71, title: '原课时', sortOrder: 4, durationSeconds: 60 }]
  return {
    detail: id => result({ id, title: '课程', chapters: [{ id: 70, title: '章节', lessons: lessons.map(item => ({ ...item })) }] }),
    createLesson: (chapterId, body) => { calls.push(['create', chapterId, body]); lessons.push({ id: 72, ...body }); return result(72) },
    deleteLesson: id => { calls.push(['delete', id]); lessons = lessons.filter(item => item.id !== id); return result(null) },
  }
}

test('添加课时使用章节ID、trim标题、排序取现有最大值后一位并刷新目录', async () => {
  const calls = []
  const { app, view } = await mount(undefined, lessonApi(calls))
  try {
    view.openLessonDialog(view.selected.chapters[0])
    assert.equal(view.lessonForm.sortOrder, 5)
    view.lessonForm.title = '  新课时  '
    await view.createLesson()
    assert.deepEqual(calls, [['create', 70, { title: '新课时', sortOrder: 5, durationSeconds: 60, freePreview: false }]])
    assert.equal(view.selected.chapters[0].lessons.length, 2)
  } finally { app.unmount() }
})

test('添加课时防止重复提交，空标题不调用接口', async () => {
  const calls = []
  let release
  const api = lessonApi(calls)
  const original = api.createLesson
  api.createLesson = (...args) => new Promise(resolve => { release = () => resolve(original(...args)) })
  const { app, view, messages } = await mount(undefined, api)
  try {
    view.openLessonDialog(view.selected.chapters[0])
    view.lessonForm.title = '  '
    await view.createLesson()
    assert.equal(messages.at(-1)[0], 'warning')
    assert.equal(release, undefined)
    view.lessonForm.title = '新课时'
    const first = view.createLesson()
    await view.createLesson()
    assert.equal(view.submitting, true)
    release()
    await first
    assert.equal(calls.length, 1)
  } finally { app.unmount() }
})

test('取消或关闭删除确认不调用接口，确认后删除并刷新目录', async () => {
  for (const action of ['cancel', 'close', 'confirm']) {
    const calls = []
    const { app, view } = await mount(undefined, lessonApi(calls), async message => {
      assert.match(message, /原课时/)
      if (action !== 'confirm') throw action
    })
    try {
      await view.deleteLesson(view.selected.chapters[0].lessons[0])
      assert.equal(calls.length, action === 'confirm' ? 1 : 0)
      assert.equal(view.selected.chapters[0].lessons.length, action === 'confirm' ? 0 : 1)
      assert.equal(view.deletingLessonId, undefined)
    } finally { app.unmount() }
  }
})

test('删除期间防重复提交，失败保留课时且反馈服务端错误', async () => {
  let rejectDelete
  let calls = 0
  const { app, view, messages } = await mount(undefined, {
    ...lessonApi([]),
    deleteLesson: () => { calls++; return new Promise((resolve, reject) => { rejectDelete = reject }) },
  })
  try {
    const lesson = view.selected.chapters[0].lessons[0]
    const pending = view.deleteLesson(lesson)
    await new Promise(resolve => setImmediate(resolve))
    await view.deleteLesson(lesson)
    assert.equal(calls, 1)
    rejectDelete({ response: { data: { message: '课时不存在' } } })
    await pending
    assert.equal(view.selected.chapters[0].lessons.length, 1)
    assert.deepEqual(messages.at(-1), ['error', '课时不存在'])
  } finally { app.unmount() }
})

test('分页和删除API使用服务端对应请求，分页组件绑定10条和总量', async () => {
  const calls = []
  globalThis.adminHttp = {
    get: (...args) => { calls.push(['get', ...args]); return result(null) },
    delete: (...args) => { calls.push(['delete', ...args]); return result(null) },
  }
  const source = (await readFile(new URL('../src/api/index.ts', import.meta.url), 'utf8'))
    .replace(/import \{ http, logoutSession \} from ['"]\.\/http['"]/, 'const http = globalThis.adminHttp; const logoutSession = () => {}')
  const code = ts.transpileModule(source, { compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ESNext } }).outputText
  const api = await import(`data:text/javascript;base64,${Buffer.from(code + `\n// ${++serial}`).toString('base64')}`)
  await api.adminCourseApi.list(2)
  await api.adminCourseApi.deleteLesson(71)
  assert.deepEqual(calls, [['get', '/admin/courses', { params: { page: 2 } }], ['delete', '/admin/lessons/71']])
  const view = await readFile(new URL('../src/views/AdminView.vue', import.meta.url), 'utf8')
  assert.match(view, /:total="courseTotal" :page-size="pageSize" :current-page="coursePage"/)
  assert.match(view, /@click="deleteLesson\(lesson\)"/)
})

test('新课程强制创建草稿，后台提供编辑、下架、封面、分类与标签契约', async () => {
  const calls = []
  globalThis.adminHttp = {
    get: (...args) => { calls.push(['get', ...args]); return result([]) },
    post: (...args) => { calls.push(['post', ...args]); return result(null) },
    put: (...args) => { calls.push(['put', ...args]); return result(null) },
    delete: (...args) => { calls.push(['delete', ...args]); return result(null) },
  }
  const source = (await readFile(new URL('../src/api/index.ts', import.meta.url), 'utf8'))
    .replace(/import \{ http, logoutSession \} from ['"]\.\/http['"]/, 'const http = globalThis.adminHttp; const logoutSession = () => {}')
  const code = ts.transpileModule(source, { compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ESNext } }).outputText
  const api = await import(`data:text/javascript;base64,${Buffer.from(code + `\n// ${++serial}`).toString('base64')}`)
  await api.adminCourseApi.updateCourse(7, { title: '新标题' })
  await api.adminCourseApi.offlineCourse(7)
  await api.adminCourseApi.updateChapter(8, { title: '章', sortOrder: 1 })
  await api.adminCourseApi.updateLesson(9, { title: '课', durationSeconds: 60, freePreview: false, sortOrder: 1 })
  await api.adminCourseApi.replaceCourseTags(7, ['9007199254740993', '9007199254740995'])
  await api.adminCourseApi.updateCategory('9007199254740993', { name: '后端', sortOrder: 1, status: 'ENABLED' })
  await api.adminCourseApi.updateTag('9007199254740995', { name: '大模型' })
  await api.adminCourseApi.categories()
  await api.adminCourseApi.tags()
  assert.deepEqual(calls, [
    ['put', '/admin/courses/7', { title: '新标题' }],
    ['post', '/admin/courses/7/offline'],
    ['put', '/admin/chapters/8', { title: '章', sortOrder: 1 }],
    ['put', '/admin/lessons/9', { title: '课', durationSeconds: 60, freePreview: false, sortOrder: 1 }],
    ['put', '/admin/courses/7/tags', { tagIds: ['9007199254740993', '9007199254740995'] }],
    ['put', '/admin/course-categories/9007199254740993', { name: '后端', sortOrder: 1, status: 'ENABLED' }],
    ['put', '/admin/course-tags/9007199254740995', { name: '大模型' }],
    ['get', '/admin/course-categories'],
    ['get', '/admin/course-tags'],
  ])
  const view = await readFile(new URL('../src/views/AdminView.vue', import.meta.url), 'utf8')
  assert.doesNotMatch(view, /立即发布/)
  assert.match(view, /status: 'DRAFT'/)
  assert.match(view, /至少包含章节、课时和可用视频后才能发布/)
})

test('创建课程时分类雪花ID保持字符串精度', async () => {
  const categoryId = '9007199254740993'
  const calls = []
  const { app, view } = await mount(undefined, {
    categories: () => result([{ id: categoryId, name: '后端开发', sortOrder: 1, status: 'ENABLED' }]),
    createCourse: body => { calls.push(body); return result(99) },
  })
  try {
    Object.assign(view.courseForm, { categoryId, title: '雪花课程', instructor: '讲师' })
    await view.createCourse()
    assert.equal(calls[0].categoryId, categoryId)
    assert.equal(typeof calls[0].categoryId, 'string')
  } finally { app.unmount() }
})
