import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { createRequire } from 'node:module'
import { pathToFileURL } from 'node:url'
import { compileScript, parse } from '@vue/compiler-sfc'
import ts from 'typescript'
import { createRenderer, nextTick, reactive } from 'vue'

// 使用 Vue 自身渲染器执行真实 SFC 的事件与 watch，无需新增 DOM/测试依赖。
const require = createRequire(import.meta.url)
const vueUrl = pathToFileURL(require.resolve('vue')).href
const source = await readFile(new URL('../src/components/VoteButton.vue', import.meta.url), 'utf8')
const { descriptor } = parse(source)
const compiled = compileScript(descriptor, { id: 'vote-test', inlineTemplate: true }).content
  .replace(/from ['"]vue['"]/g, `from ${JSON.stringify(vueUrl)}`)
  .replace(/import \{ useRoute, useRouter \} from ['"]vue-router['"]/, 'const { useRoute, useRouter } = globalThis.voteHarness')
  .replace(/import \{ likeApi \} from ['"]@\/api['"]/, 'const { likeApi } = globalThis.voteHarness')
  .replace(/import \{ useAuthStore \} from ['"]@\/stores\/auth['"]/, 'const { useAuthStore } = globalThis.voteHarness')
const javascript = ts.transpileModule(compiled, {
  compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ESNext },
}).outputText

const renderer = createRenderer({
  createElement: tag => ({ tag, props: {}, children: [] }),
  createText: text => ({ text }),
  createComment: text => ({ comment: text }),
  setText: (node, text) => { node.text = text },
  setElementText: (node, text) => { node.text = text; node.children = [] },
  parentNode: node => node.parent,
  nextSibling: () => null,
  patchProp: (node, key, old, value) => { node.props[key] = value },
  insert: (node, parent, anchor) => {
    node.parent = parent
    const index = anchor ? parent.children.indexOf(anchor) : -1
    if (index < 0) parent.children.push(node)
    else parent.children.splice(index, 0, node)
  },
  remove: node => {
    const index = node.parent?.children.indexOf(node) ?? -1
    if (index >= 0) node.parent.children.splice(index, 1)
  },
})
let serial = 0
async function flush() {
  await new Promise(resolve => setImmediate(resolve))
  await nextTick()
}
async function mount({ authenticated = true, targetType = 'QUESTION', status, like, unlike } = {}) {
  const calls = []
  const auth = reactive({ authenticated, user: authenticated ? { id: 10 } : null })
  globalThis.voteHarness = {
    useAuthStore: () => auth,
    useRoute: () => ({ fullPath: '/questions/6001' }),
    useRouter: () => ({ push: async route => calls.push(['navigate', route]) }),
    likeApi: {
      status: async (...args) => { calls.push(['status', ...args]); return { data: { data: await (status?.() ?? { liked: false, count: 0 }) } } },
      like: async (...args) => { calls.push(['like', ...args]); return { data: { data: await (like?.() ?? { liked: true, count: 1 }) } } },
      unlike: async (...args) => { calls.push(['unlike', ...args]); return { data: { data: await (unlike?.() ?? { liked: false, count: 0 }) } } },
    },
  }
  const module = await import(`data:text/javascript;base64,${Buffer.from(javascript + `\n// ${++serial}`).toString('base64')}`)
  const root = { children: [] }
  const app = renderer.createApp(module.default, { targetType, targetId: '6001' })
  app.mount(root)
  await flush()
  const find = (node, tag) => node.tag === tag ? node : node.children?.map(child => find(child, tag)).find(Boolean)
  return { calls, auth, app, root, button: () => find(root, 'button') }
}

test('问题和回答赞同、取消均使用服务器状态，重复点击受提交锁保护', async () => {
  for (const targetType of ['QUESTION', 'ANSWER']) {
    let release
    const view = await mount({ targetType, like: () => new Promise(resolve => { release = resolve }) })
    assert.equal(view.button().props['aria-pressed'], false)
    const first = view.button().props.onClick()
    await nextTick()
    assert.equal(view.button().props.disabled, true)
    await view.button().props.onClick()
    assert.equal(view.calls.filter(call => call[0] === 'like').length, 1)
    release({ liked: true, count: 1 })
    await first
    await flush()
    assert.equal(view.button().props['aria-pressed'], true)
    assert.match(view.button().props['aria-label'], /1 个赞同/)
    await view.button().props.onClick()
    await flush()
    assert.equal(view.button().props['aria-pressed'], false)
    assert.deepEqual(view.calls.find(call => call[0] === 'unlike'), ['unlike', targetType, '6001'])
    view.app.unmount()
  }
})

test('未登录用户读取实时数，赞同时携带返回地址跳转登录', async () => {
  const view = await mount({ authenticated: false, status: () => ({ liked: false, count: 2 }) })
  assert.match(view.button().props['aria-label'], /2 个赞同/)
  await view.button().props.onClick()
  assert.deepEqual(view.calls.at(-1), ['navigate', { path: '/login', query: { redirect: '/questions/6001' } }])
  assert.equal(view.calls.some(call => call[0] === 'like'), false)
  view.app.unmount()
})

test('重新打开页面回显服务端已赞同状态，退出登录清除个人状态', async () => {
  const view = await mount({ status: () => ({ liked: viewAuth(), count: 1 }) })
  function viewAuth() { return globalThis.voteHarness.useAuthStore().authenticated }
  assert.equal(view.button().props['aria-pressed'], true)
  view.auth.authenticated = false
  view.auth.user = null
  await flush()
  assert.equal(view.button().props['aria-pressed'], false)
  view.app.unmount()
})

test('状态加载失败后先重试读取，不盲目发送点赞', async () => {
  let attempts = 0
  const view = await mount({ status: () => {
    if (++attempts === 1) throw new Error('offline')
    return { liked: true, count: 1 }
  } })
  assert.match(view.button().props['aria-label'], /重新加载/)
  await view.button().props.onClick()
  await flush()
  assert.equal(view.button().props['aria-pressed'], true)
  assert.equal(view.calls.some(call => call[0] === 'like'), false)
  view.app.unmount()
})

test('提交未确认后重新读取真实状态，再次点击仅刷新', async () => {
  let voted = false
  const view = await mount({
    status: () => ({ liked: voted, count: voted ? 1 : 0 }),
    like: () => { voted = true; throw new Error('confirm timeout') },
  })
  await view.button().props.onClick()
  await flush()
  assert.equal(view.button().props['aria-pressed'], true)
  await view.button().props.onClick()
  await flush()
  assert.equal(view.calls.filter(call => call[0] === 'like').length, 1)
  assert.equal(view.calls.some(call => call[0] === 'unlike'), false)
  view.app.unmount()
})
