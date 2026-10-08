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
let serial = 0
async function mount(file, bindings, api, authenticated = true) {
  const auth = reactive({ authenticated })
  const messages = []
  const navigations = []
  globalThis.couponHarness = {
    couponApi: api, useAuthStore: () => auth,
    useRoute: () => ({ fullPath: '/coupons' }), useRouter: () => ({ push: async route => navigations.push(route) }),
    ElMessage: Object.fromEntries(['success', 'info', 'error', 'warning'].map(level => [level, message => messages.push([level, message])])),
  }
  const source = (await readFile(new URL(file, import.meta.url), 'utf8'))
    .replace('</script>', `defineExpose({ ${bindings} })\n</script>`)
  const compiled = compileScript(parse(source).descriptor, { id: 'coupon-test' }).content
    .replace(/from ['"]vue['"]/g, `from ${JSON.stringify(vueUrl)}`)
    .replace(/import \{ useRoute, useRouter \} from ['"]vue-router['"]/, 'const { useRoute, useRouter } = globalThis.couponHarness')
    .replace(/import \{ ElMessage \} from ['"]element-plus['"]/, 'const { ElMessage } = globalThis.couponHarness')
    .replace(/import \{ couponApi \} from ['"]@\/api['"]/, 'const { couponApi } = globalThis.couponHarness')
    .replace(/import \{ isSessionExpired \} from ['"]@\/api\/session['"]/, "const isSessionExpired = error => error?.code === 'AUTH_SESSION_EXPIRED'")
    .replace(/import \{ useAuthStore \} from ['"]@\/stores\/auth['"]/, 'const { useAuthStore } = globalThis.couponHarness')
  const code = ts.transpileModule(compiled, { compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ESNext } }).outputText
  const module = await import(`data:text/javascript;base64,${Buffer.from(code + `\n// ${++serial}`).toString('base64')}`)
  module.default.render = () => null
  const app = renderer.createApp(module.default)
  const view = app.mount({})
  await new Promise(resolve => setImmediate(resolve))
  return { app, view, messages, navigations }
}
const result = data => Promise.resolve({ data: { data } })
function activity(overrides = {}) {
  return { id: '4002', name: '秒杀券', seckill: true, discountAmount: 30, thresholdAmount: 100, stock: 2,
    startAt: new Date(Date.now() - 10000).toISOString(), endAt: new Date(Date.now() + 60000).toISOString(), status: 'ACTIVE', ...overrides }
}
const bindings = 'coupons, wallet, loadError, load, claim, label, claimed'

test('金额和门槛不被清零，过期活动禁用领取', async () => {
  const item = activity({ endAt: new Date(Date.now() - 1000).toISOString(), status: 'ENDED' })
  let submitted = false
  const { app, view } = await mount('../src/views/CouponView.vue', bindings, {
    available: () => result([item]), mine: () => result([]), seckill: () => { submitted = true },
  })
  assert.equal(view.coupons[0].discountAmount, 30)
  assert.equal(view.coupons[0].thresholdAmount, 100)
  assert.equal(view.label(item), '已结束')
  await view.claim(item)
  assert.equal(submitted, false)
  app.unmount()
})

test('秒杀受理后显示处理中，数据库确认后才显示已领取', async () => {
  const item = activity()
  let wallet = []
  const { app, view, messages } = await mount('../src/views/CouponView.vue', bindings, {
    available: () => result([item]), mine: () => result(wallet),
    seckill: () => { wallet = [{ couponId: item.id, status: 'RESERVED' }]; return result({ status: 'RESERVED' }) },
  })
  await view.claim(item)
  assert.equal(view.label(item), '处理中')
  assert.equal(view.claimed(item), false)
  assert.equal(messages[0][0], 'info')
  wallet = [{ couponId: item.id, status: 'AVAILABLE' }]
  await view.load()
  assert.equal(view.label(item), '已领取')
  app.unmount()
})

test('业务失败显示真实原因，加载失败不显示虚构优惠券', async () => {
  const item = activity()
  const { app, view, messages } = await mount('../src/views/CouponView.vue', bindings, {
    available: () => result([item]), mine: () => result([]),
    seckill: () => Promise.reject({ response: { status: 409, data: { message: '优惠券已抢光' } } }),
  })
  await view.claim(item)
  assert.deepEqual(messages[0], ['error', '优惠券已抢光'])
  assert.equal(view.claimed(item), false)
  app.unmount()
  const failed = await mount('../src/views/CouponView.vue', bindings, {
    available: () => Promise.reject(new Error('offline')), mine: () => result([]),
  })
  assert.equal(failed.view.coupons.length, 0)
  assert.match(failed.view.loadError, /加载失败/)
  failed.app.unmount()
})

test('未登录领取跳转登录并保留返回地址', async () => {
  const item = activity()
  const { app, view, navigations } = await mount('../src/views/CouponView.vue', bindings, { available: () => result([item]) }, false)
  await view.claim(item)
  assert.deepEqual(navigations[0], { path: '/login', query: { redirect: '/coupons' } })
  app.unmount()
})

test('个人中心读取服务端券包，刷新后处理状态变为可用', async () => {
  let wallet = [{ couponId: '4002', name: '秒杀券', discountAmount: 30, thresholdAmount: 100, status: 'RESERVED' }]
  const { app, view } = await mount('../src/components/MyCoupons.vue', 'coupons, load, error', { mine: () => result(wallet) })
  assert.equal(view.coupons[0].status, 'RESERVED')
  wallet = [{ ...wallet[0], status: 'AVAILABLE' }]
  await view.load()
  assert.equal(view.coupons[0].status, 'AVAILABLE')
  assert.equal(view.coupons[0].discountAmount, 30)
  app.unmount()
})
