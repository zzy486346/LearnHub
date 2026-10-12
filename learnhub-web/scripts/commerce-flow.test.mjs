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
  insert() {}, remove() {}, setText() {}, setElementText() {}, patchProp() {}, parentNode: () => null, nextSibling: () => null,
})
const result = data => Promise.resolve({ data: { data } })
let serial = 0

async function mountPurchase(wallet, createOrder) {
  const route = reactive({ params: { id: '7' }, fullPath: '/courses/7' })
  globalThis.commerceHarness = {
    courseApi: { detail: () => result({ id: 7, title: '系统课', price: 100, accessLevel: 'PREVIEW', chapters: [] }), likeStatus: () => result({ liked: false, count: 0 }), favoriteStatus: () => result(false) },
    couponApi: { mine: () => result(wallet) },
    orderApi: { createCourseOrder: createOrder },
    useAuthStore: () => reactive({ authenticated: true }), useRoute: () => route, useRouter: () => ({ push: async () => {} }),
    ElMessage: Object.fromEntries(['success', 'info', 'error', 'warning'].map(level => [level, () => {}])),
  }
  const source = (await readFile(new URL('../src/views/CourseDetailView.vue', import.meta.url), 'utf8'))
    .replace('</script>', 'defineExpose({ openPurchase, createOrder, usableCoupons, selectedCouponClaimId, estimatedDiscount, estimatedPayable, activeOrder })\n</script>')
  const compiled = compileScript(parse(source).descriptor, { id: 'commerce-flow-test' }).content
    .replace(/from ['"]vue['"]/g, `from ${JSON.stringify(vueUrl)}`)
    .replace(/import \{ useRoute, useRouter \} from ['"]vue-router['"]/, 'const { useRoute, useRouter } = globalThis.commerceHarness')
    .replace(/import \{ ElMessage \} from ['"]element-plus['"]/, 'const { ElMessage } = globalThis.commerceHarness')
    .replace(/import \{ couponApi, courseApi, orderApi \} from ['"]@\/api['"]/, 'const { couponApi, courseApi, orderApi } = globalThis.commerceHarness')
    .replace(/import \{ isSessionExpired \} from ['"]@\/api\/session['"]/, "const isSessionExpired = error => error?.code === 'AUTH_SESSION_EXPIRED'")
    .replace(/import \{ useAuthStore \} from ['"]@\/stores\/auth['"]/, 'const { useAuthStore } = globalThis.commerceHarness')
  const code = ts.transpileModule(compiled, { compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ESNext } }).outputText
  const module = await import(`data:text/javascript;base64,${Buffer.from(code + `\n// ${++serial}`).toString('base64')}`)
  module.default.render = () => null
  const app = renderer.createApp(module.default)
  const view = app.mount({})
  await new Promise(resolve => setImmediate(resolve))
  return { app, view }
}

test('购买页只展示本人可用且满足门槛与有效期的优惠券，并提交claimId', async () => {
  const now = Date.now()
  const wallet = [
    { claimId: 'c-ok', status: 'AVAILABLE', thresholdAmount: 100, discountAmount: 30, useStartAt: new Date(now - 1000).toISOString(), useEndAt: new Date(now + 60000).toISOString() },
    { claimId: 'c-high', status: 'AVAILABLE', thresholdAmount: 101, discountAmount: 80, useStartAt: new Date(now - 1000).toISOString(), useEndAt: new Date(now + 60000).toISOString() },
    { claimId: 'c-used', status: 'USED', thresholdAmount: 0, discountAmount: 99, useStartAt: new Date(now - 1000).toISOString(), useEndAt: new Date(now + 60000).toISOString() },
  ]
  const calls = []
  const { app, view } = await mountPurchase(wallet, (courseId, claimId) => { calls.push([courseId, claimId]); return result({ id: 'o1', status: 'PENDING', courseId, payableAmount: 70 }) })
  try {
    await view.openPurchase()
    assert.deepEqual(view.usableCoupons.map(item => item.claimId), ['c-ok'])
    view.selectedCouponClaimId = 'c-ok'
    assert.equal(view.estimatedDiscount, 30)
    assert.equal(view.estimatedPayable, 70)
    await view.createOrder()
    assert.deepEqual(calls, [[7, 'c-ok']])
    assert.equal(view.activeOrder.payableAmount, 70)
  } finally { app.unmount() }
})

test('订单接口携带优惠券领取记录，并提供取消与退款端点', async () => {
  const calls = []
  globalThis.commerceHttp = { post: (...args) => { calls.push(args); return result(null) }, get: () => result([]) }
  const source = (await readFile(new URL('../src/api/index.ts', import.meta.url), 'utf8'))
    .replace(/import \{ http, logoutSession \} from ['"]\.\/http['"]/, 'const http = globalThis.commerceHttp; const logoutSession = () => {}')
  const code = ts.transpileModule(source, { compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ESNext } }).outputText
  const api = await import(`data:text/javascript;base64,${Buffer.from(code + `\n// ${++serial}`).toString('base64')}`)
  await api.orderApi.createCourseOrder(7, 'claim-1')
  await api.orderApi.cancel('order-1')
  await api.orderApi.refund('order-2')
  assert.deepEqual(calls, [
    ['/orders/courses/7', { couponClaimId: 'claim-1' }],
    ['/orders/order-1/cancel'],
    ['/orders/order-2/refund'],
  ])
  const profile = await readFile(new URL('../src/views/ProfileView.vue', import.meta.url), 'utf8')
  assert.match(profile, /退款后将立即撤销该课程的已购学习权益/)
  assert.match(profile, /订单已取消，优惠券已退回/)
  assert.match(profile, /本机演示支付/)
})
