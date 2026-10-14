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

async function mount(api, { reducedMotion = false } = {}) {
  const motion = Object.assign(new EventTarget(), { matches: reducedMotion })
  globalThis.window = { matchMedia: () => motion }
  globalThis.document = Object.assign(new EventTarget(), { hidden: false })
  globalThis.searchHarness = { courseApi: api }
  const source = (await readFile(new URL('../src/views/CourseListView.vue', import.meta.url), 'utf8'))
    .replace('</script>', 'defineExpose({ courses, filters, categories, availableTags, showTagFilters, total, loadError, loading, load, submitSearch, selectCategory, selectTag, toggleTagFilters, fetchSuggestions, nextPage, previousPage, setHovered, setFocused, handleFocusIn })\n</script>')
  const compiled = compileScript(parse(source).descriptor, { id: 'course-search-test' }).content
    .replace(/from ['"]vue['"]/g, `from ${JSON.stringify(vueUrl)}`)
    .replace(/import \{ courseApi \} from ['"]@\/api['"]/, 'const { courseApi } = globalThis.searchHarness')
    .replace(/import \{ isSessionExpired \} from ['"]@\/api\/session['"]/, "const isSessionExpired = error => error?.code === 'AUTH_SESSION_EXPIRED'")
  const code = ts.transpileModule(compiled, { compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ESNext } }).outputText
  const module = await import(`data:text/javascript;base64,${Buffer.from(code + `\n// ${++serial}`).toString('base64')}`)
  module.default.render = () => null
  const app = renderer.createApp(module.default)
  const view = app.mount({})
  await new Promise(resolve => setImmediate(resolve))
  return { app, view }
}

async function courseTemplate() {
  const source = await readFile(new URL('../src/views/CourseListView.vue', import.meta.url), 'utf8')
  const parsed = parse(source)
  assert.deepEqual(parsed.errors, [])
  assert.ok(parsed.descriptor.template?.ast)
  return parsed.descriptor.template.ast
}

function findElements(node, predicate, found = []) {
  if (node?.type === 1 && predicate(node)) found.push(node)
  for (const child of node?.children || []) findElements(child, predicate, found)
  return found
}

function hasAttribute(node, name) {
  return node.props.some(prop => prop.type === 6 && prop.name === name)
}

function attributeValue(node, name) {
  return node.props.find(prop => prop.type === 6 && prop.name === name)?.value?.content
}

function directiveExpression(node, name, argument) {
  return node.props.find(prop => prop.type === 7 && prop.name === name
    && (!argument || prop.arg?.content === argument))?.exp?.content
}

test('默认课程使用普通分页，关键词和多标签使用搜索分页', async () => {
  const calls = []
  const api = {
    list: params => { calls.push(['list', params]); return result({ records: [{ id: 1, title: '默认课程' }], total: 13, current: 1, size: 3 }) },
    searchPage: params => { calls.push(['search', params]); return result({ records: [{ id: 2, title: '搜索课程' }], total: 21, current: 1, size: 3 }) },
  }
  const { app, view } = await mount(api)
  assert.equal(view.courses[0].title, '默认课程')
  assert.equal(view.total, 13)
  view.filters.keyword = ' Java '
  view.filters.tags.push('Java', '架构')
  await view.submitSearch()
  assert.deepEqual(calls[0], ['list', { page: 1, size: 3 }])
  assert.deepEqual(calls.at(-1), ['search', { keyword: 'Java', tags: 'Java,架构', page: 1, size: 3 }])
  assert.equal(view.courses[0].title, '搜索课程')
  assert.equal(view.total, 21)
  app.unmount()
})

test('分类和标签来自公共接口，分类可与关键词标签组合提交', async () => {
  const calls = []
  const api = {
    list: params => result({ records: [], total: 0, current: params.page, size: params.size }),
    searchPage: params => { calls.push(params); return result({ records: [], total: 0, current: 1, size: 3 }) },
    categories: () => result([{ id: '9007199254740993', name: '人工智能', status: 'ENABLED', sortOrder: 1 }]),
    tags: () => result([{ id: '9007199254740995', name: '大模型' }]),
  }
  const { app, view } = await mount(api)
  try {
    assert.deepEqual(view.categories.map(item => item.name), ['人工智能'])
    assert.deepEqual(view.availableTags.map(item => item.name), ['大模型'])
    view.filters.keyword = '应用'
    view.filters.tags.push('大模型')
    await view.selectCategory('9007199254740993')
    assert.deepEqual(calls.at(-1), { keyword: '应用', categoryId: '9007199254740993', tags: '大模型', page: 1, size: 3 })
  } finally { app.unmount() }
})

test('大量标签默认折叠，按需展开并保留标签切换方法契约', async () => {
  const tags = Array.from({ length: 70 }, (_, index) => ({ id: String(9007199254740993n + BigInt(index)), name: `标签${index + 1}` }))
  const { app, view } = await mount({
    list: params => result({ records: [], total: 0, current: params.page, size: params.size }),
    categories: () => result([]),
    tags: () => result(tags),
  })
  try {
    assert.equal(view.availableTags.length, 70)
    assert.equal(view.showTagFilters, false)
    view.toggleTagFilters()
    assert.equal(view.showTagFilters, true)
    view.selectTag('标签70')
    assert.deepEqual(view.filters.tags, ['标签70'])
    view.selectTag('标签70')
    assert.deepEqual(view.filters.tags, [])
  } finally { app.unmount() }
})

test('分类筛选保持雪花ID字符串，清空后请求不携带分类条件', async () => {
  const calls = []
  const categoryId = '9007199254740993'
  const { app, view } = await mount({
    list: params => { calls.push(['list', params]); return result({ records: [], total: 0, current: params.page, size: params.size }) },
    searchPage: params => { calls.push(['search', params]); return result({ records: [], total: 0, current: 1, size: 3 }) },
    categories: () => result([{ id: categoryId, name: '人工智能', status: 'ENABLED', sortOrder: 1 }]),
    tags: () => result([]),
  })
  try {
    await view.selectCategory(categoryId)
    assert.equal(view.filters.categoryId, categoryId)
    assert.equal(typeof view.filters.categoryId, 'string')
    assert.deepEqual(calls.at(-1), ['search', { keyword: '', categoryId, tags: undefined, page: 1, size: 3 }])
    await view.selectCategory(undefined)
    assert.equal(view.filters.categoryId, undefined)
    assert.deepEqual(calls.at(-1), ['list', { page: 1, size: 3 }])
  } finally { app.unmount() }
})

test('课程筛选使用紧凑下拉并将多标签作为渐进式筛选', async () => {
  const template = await courseTemplate()
  const selects = findElements(template, node => node.tag === 'el-select')
  const categorySelect = selects.find(node => attributeValue(node, 'aria-label') === '课程分类筛选')
  const tagSelect = selects.find(node => attributeValue(node, 'aria-label') === '课程标签筛选')
  assert.ok(categorySelect, '分类应由一个紧凑下拉框承载')
  assert.ok(tagSelect, '标签应由一个可搜索的多选框承载')
  assert.ok(hasAttribute(categorySelect, 'clearable'))
  for (const attribute of ['multiple', 'filterable', 'collapse-tags']) assert.ok(hasAttribute(tagSelect, attribute), `标签选择器缺少 ${attribute}`)

  const progressiveContainers = findElements(template, node => directiveExpression(node, 'if')?.includes('showTagFilters'))
  assert.ok(progressiveContainers.some(node => findElements(node, child => child === tagSelect).length), '标签选择器默认应由展开状态控制')
  const toggle = findElements(template, node => directiveExpression(node, 'on', 'click') === 'toggleTagFilters')
  assert.equal(toggle.length, 1)

  const chipLoops = findElements(template, node => node.tag === 'button'
    && /categories|availableTags/.test(directiveExpression(node, 'for') || ''))
  assert.equal(chipLoops.length, 0, '分类和标签不应继续渲染为完整按钮云')
})

test('较早的慢请求不能覆盖用户后发起的搜索', async () => {
  const releases = []
  const { app, view } = await mount({
    list: () => result({ records: [], total: 0, current: 1, size: 12 }),
    searchPage: params => new Promise(resolve => releases.push(() => resolve({ data: { data: { records: [{ id: releases.length, title: params.keyword }], total: 1 } } }))),
  })
  view.filters.keyword = '旧关键词'
  const older = view.submitSearch()
  view.filters.keyword = '新关键词'
  const newer = view.submitSearch()
  releases[1]()
  await newer
  releases[0]()
  await older
  assert.equal(view.courses[0].title, '新关键词')
  assert.equal(view.loading, false)
  app.unmount()
})

test('联想忽略空前缀和过期响应', async () => {
  const releases = []
  let calls = 0
  const { app, view } = await mount({
    list: () => result({ records: [], total: 0, current: 1, size: 12 }),
    suggestions: ({ prefix }) => { calls++; return new Promise(resolve => releases.push(() => resolve({ data: { data: [prefix] } }))) },
  })
  let empty
  await view.fetchSuggestions('   ', items => { empty = items })
  assert.deepEqual(empty, [])
  assert.equal(calls, 0)
  let oldItems
  let newItems
  const oldRequest = view.fetchSuggestions('旧', items => { oldItems = items })
  const newRequest = view.fetchSuggestions('新', items => { newItems = items })
  releases[1]()
  await newRequest
  releases[0]()
  await oldRequest
  assert.deepEqual(newItems, [{ value: '新' }])
  assert.deepEqual(oldItems, [])
  app.unmount()
})

test('加载失败显示服务端真实原因且不注入虚构课程，可重新加载', async () => {
  let fails = true
  const { app, view } = await mount({
    list: () => fails
      ? Promise.reject({ response: { data: { message: '搜索服务暂不可用' } } })
      : result({ records: [{ id: 3, title: '恢复后的课程' }], total: 1, current: 1, size: 3 }),
  })
  assert.equal(view.courses.length, 0)
  assert.equal(view.loadError, '搜索服务暂不可用')
  fails = false
  await view.load()
  assert.equal(view.loadError, '')
  assert.equal(view.courses[0].title, '恢复后的课程')
  app.unmount()
})

test('每三秒自动切换下一页，末页回到首页，卸载停止循环', async (t) => {
  t.mock.timers.enable({ apis: ['setTimeout'] })
  const calls = []
  const { app, view } = await mount({ list: params => {
    calls.push(params.page)
    return result({ records: [{ id: params.page, title: `第${params.page}组` }], total: 6 })
  } })
  t.mock.timers.tick(2999)
  assert.equal(view.filters.current, 1)
  assert.deepEqual(calls, [1])
  t.mock.timers.tick(1)
  await new Promise(resolve => setImmediate(resolve))
  assert.equal(view.filters.current, 2)
  assert.equal(view.courses[0].title, '第2组')
  t.mock.timers.tick(3000)
  await new Promise(resolve => setImmediate(resolve))
  assert.equal(view.filters.current, 1)
  app.unmount()
  t.mock.timers.tick(12000)
  assert.deepEqual(calls, [1, 2, 1])
})

test('悬停暂停、移出恢复轮播，聚焦和页面隐藏暂停且手动切换可用', async (t) => {
  t.mock.timers.enable({ apis: ['setTimeout'] })
  const { app, view } = await mount({ list: params => result({ records: [{ id: params.page, title: '课程' }], total: 9 }) })
  view.setHovered(true)
  t.mock.timers.tick(6000)
  assert.equal(view.filters.current, 1)
  view.setHovered(false)
  t.mock.timers.tick(3000)
  await new Promise(resolve => setImmediate(resolve))
  assert.equal(view.filters.current, 2)
  view.setFocused(true)
  t.mock.timers.tick(12000)
  assert.equal(view.filters.current, 2)
  view.setFocused(false)
  view.setHovered(true)
  t.mock.timers.tick(12000)
  assert.equal(view.filters.current, 2)
  await view.previousPage()
  assert.equal(view.filters.current, 1)
  await view.previousPage()
  assert.equal(view.filters.current, 3)
  await view.nextPage()
  assert.equal(view.filters.current, 1)
  view.setHovered(false)
  document.hidden = true
  document.dispatchEvent(new Event('visibilitychange'))
  t.mock.timers.tick(12000)
  assert.equal(view.filters.current, 1)
  document.hidden = false
  document.dispatchEvent(new Event('visibilitychange'))
  t.mock.timers.tick(3000)
  await new Promise(resolve => setImmediate(resolve))
  assert.equal(view.filters.current, 2)
  app.unmount()
})

test('显式开启的三秒轮播不被系统动态偏好禁用，只有一页时不轮播且保留筛选条件', async (t) => {
  t.mock.timers.enable({ apis: ['setTimeout'] })
  const still = await mount({ list: () => result({ records: [], total: 6 }) }, { reducedMotion: true })
  t.mock.timers.tick(3000)
  await new Promise(resolve => setImmediate(resolve))
  assert.equal(still.view.filters.current, 2)
  still.app.unmount()
  const requests = []
  const { app, view } = await mount({
    list: () => result({ records: [], total: 1 }),
    searchPage: params => { requests.push(params); return result({ records: [], total: 6 }) },
  })
  t.mock.timers.tick(12000)
  assert.equal(view.filters.current, 1)
  view.filters.keyword = 'Java'
  view.filters.tags.push('架构')
  await view.submitSearch()
  t.mock.timers.tick(3000)
  await new Promise(resolve => setImmediate(resolve))
  assert.deepEqual(requests.at(-1), { keyword: 'Java', tags: '架构', page: 2, size: 3 })
  app.unmount()
})

test('会话失效交由全局登录跳转处理，不显示课程加载失败', async () => {
  const expired = Object.assign(new Error('expired'), { code: 'AUTH_SESSION_EXPIRED' })
  const { app, view } = await mount({ list: () => Promise.reject(expired) })
  assert.equal(view.loadError, '')
  assert.equal(view.loading, false)
  app.unmount()
})

test('自动翻页只使用已提交的筛选，不把正在输入的关键词提前提交', async (t) => {
  t.mock.timers.enable({ apis: ['setTimeout'] })
  const calls = []
  const { app, view } = await mount({
    list: params => { calls.push(['list', params]); return result({ records: [], total: 6 }) },
    searchPage: params => { calls.push(['search', params]); return result({ records: [], total: 6 }) },
  })
  view.filters.keyword = '尚未提交'
  t.mock.timers.tick(3000)
  await new Promise(resolve => setImmediate(resolve))
  assert.deepEqual(calls.at(-1), ['list', { page: 2, size: 3 }])
  view.filters.keyword = 'Java'
  await view.submitSearch()
  view.filters.keyword = '新草稿'
  t.mock.timers.tick(3000)
  await new Promise(resolve => setImmediate(resolve))
  assert.equal(calls.at(-1)[1].keyword, 'Java')
  app.unmount()
})

test('鼠标点击后留下的按钮焦点不阻止移出恢复，键盘焦点仍受保护', async (t) => {
  t.mock.timers.enable({ apis: ['setTimeout'] })
  const { app, view } = await mount({ list: () => result({ records: [], total: 6 }) })
  view.handleFocusIn({ target: { matches: () => true } })
  t.mock.timers.tick(10000)
  assert.equal(view.filters.current, 1)
  view.handleFocusIn({ target: { matches: () => false } })
  t.mock.timers.tick(3000)
  await new Promise(resolve => setImmediate(resolve))
  assert.equal(view.filters.current, 2)
  app.unmount()
})

test('自动播放保留可见滑动过渡，页面不再包含暂停按钮', async () => {
  const styles = await readFile(new URL('../src/styles/main.css', import.meta.url), 'utf8')
  const page = await readFile(new URL('../src/views/CourseListView.vue', import.meta.url), 'utf8')
  assert.match(styles, /\.course-carousel\.is-auto-playing[^{}]+\{\s*transition-duration:\.24s!important;/)
  assert.match(styles, /translateX\(100%\)/)
  assert.doesNotMatch(page, /暂停轮播|继续轮播|toggleAutoplay/)
})

test('翻页请求尚未完成时保留当前课程，切换采用同位置并行滑动而非先清空', async () => {
  let finishNextPage
  const { app, view } = await mount({ list: params => params.page === 1
    ? result({ records: [{ id: 1, title: '当前课程' }], total: 6 })
    : new Promise(resolve => { finishNextPage = () => resolve({ data: { data: { records: [{ id: 2, title: '下一组课程' }], total: 6 } } }) }),
  })
  try {
    const next = view.nextPage()
    assert.equal(view.loading, true)
    assert.equal(view.courses[0].title, '当前课程')
    finishNextPage()
    await next
    assert.equal(view.courses[0].title, '下一组课程')
    const page = await readFile(new URL('../src/views/CourseListView.vue', import.meta.url), 'utf8')
    const styles = await readFile(new URL('../src/styles/main.css', import.meta.url), 'utf8')
    assert.doesNotMatch(page, /mode="out-in"/)
    assert.match(page, /v-loading="loading && !courses.length"/)
    assert.match(styles, /\.course-carousel-window > \.course-grid\s*\{\s*grid-area:1 \/ 1;/)
    assert.doesNotMatch(styles, /\.course-next-enter-from[^{}]*\{[^}]*opacity:0/)
  } finally { app.unmount() }
})
