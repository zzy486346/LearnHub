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

async function mount(api) {
  globalThis.searchHarness = { courseApi: api }
  const source = (await readFile(new URL('../src/views/CourseListView.vue', import.meta.url), 'utf8'))
    .replace('</script>', 'defineExpose({ courses, filters, total, loadError, loading, load, submitSearch, selectTag, fetchSuggestions })\n</script>')
  const compiled = compileScript(parse(source).descriptor, { id: 'course-search-test' }).content
    .replace(/from ['"]vue['"]/g, `from ${JSON.stringify(vueUrl)}`)
    .replace(/import \{ courseApi \} from ['"]@\/api['"]/, 'const { courseApi } = globalThis.searchHarness')
  const code = ts.transpileModule(compiled, { compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ESNext } }).outputText
  const module = await import(`data:text/javascript;base64,${Buffer.from(code + `\n// ${++serial}`).toString('base64')}`)
  module.default.render = () => null
  const app = renderer.createApp(module.default)
  const view = app.mount({})
  await new Promise(resolve => setImmediate(resolve))
  return { app, view }
}

test('默认课程使用普通分页，关键词和多标签使用搜索分页', async () => {
  const calls = []
  const api = {
    list: params => { calls.push(['list', params]); return result({ records: [{ id: 1, title: '默认课程' }], total: 13, current: 1, size: 12 }) },
    searchPage: params => { calls.push(['search', params]); return result({ records: [{ id: 2, title: '搜索课程' }], total: 21, current: 1, size: 12 }) },
  }
  const { app, view } = await mount(api)
  assert.equal(view.courses[0].title, '默认课程')
  assert.equal(view.total, 13)
  view.filters.keyword = ' Java '
  view.filters.tags.push('Java', '架构')
  await view.submitSearch()
  assert.deepEqual(calls.at(-1), ['search', { keyword: 'Java', tags: 'Java,架构', page: 1, size: 12 }])
  assert.equal(view.courses[0].title, '搜索课程')
  assert.equal(view.total, 21)
  app.unmount()
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
      : result({ records: [{ id: 3, title: '恢复后的课程' }], total: 1, current: 1, size: 12 }),
  })
  assert.equal(view.courses.length, 0)
  assert.equal(view.loadError, '搜索服务暂不可用')
  fails = false
  await view.load()
  assert.equal(view.loadError, '')
  assert.equal(view.courses[0].title, '恢复后的课程')
  app.unmount()
})
