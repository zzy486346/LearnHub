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

async function mountPurchase(wallet, createOrder, pay = () => result(null), messages = []) {
  const route = reactive({ params: { id: '7' }, fullPath: '/courses/7' })
  globalThis.commerceHarness = {
    courseApi: { detail: () => result({ id: 7, title: '系统课', price: 100, accessLevel: 'PREVIEW', chapters: [] }), likeStatus: () => result({ liked: false, count: 0 }), favoriteStatus: () => result(false) },
    couponApi: { mine: () => result(wallet) },
    orderApi: { createCourseOrder: createOrder, pay },
    useAuthStore: () => reactive({ authenticated: true }), useRoute: () => route, useRouter: () => ({ push: async () => {} }),
    ElMessage: Object.fromEntries(['success', 'info', 'error', 'warning'].map(level => [level, message => messages.push([level, message])])),
  }
  const source = (await readFile(new URL('../src/views/CourseDetailView.vue', import.meta.url), 'utf8'))
    .replace('</script>', 'defineExpose({ openPurchase, createOrder, payOrder, usableCoupons, selectedCouponClaimId, estimatedDiscount, estimatedPayable, activeOrder })\n</script>')
  const compiled = compileScript(parse(source).descriptor, { id: 'commerce-flow-test' }).content
    .replace(/import CourseAssistantPanel from ['"]@\/components\/CourseAssistantPanel.vue['"]/, 'const CourseAssistantPanel = {}')
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

async function mountProfile(order, pay, messages = []) {
  globalThis.commerceHarness = {
    authApi: {},
    orderApi: { mine: () => result([order]), pay },
    profileApi: { overview: () => result({}) },
    clearTokens: () => {},
    useAuthStore: () => reactive({ user: { roles: [] }, fetchMe: async () => {} }),
    useRouter: () => ({ push: async () => {} }),
    ElMessage: Object.fromEntries(['success', 'info', 'error', 'warning'].map(level => [level, message => messages.push([level, message])])),
    ElMessageBox: { confirm: async () => {} },
  }
  const source = (await readFile(new URL('../src/views/ProfileView.vue', import.meta.url), 'utf8'))
    .replace('</script>', 'defineExpose({ payPendingOrder, orders, couponsKey })\n</script>')
  const compiled = compileScript(parse(source).descriptor, { id: 'commerce-profile-test' }).content
    .replace(/from ['"]vue['"]/g, `from ${JSON.stringify(vueUrl)}`)
    .replace(/import \{ useRouter \} from ['"]vue-router['"]/, 'const { useRouter } = globalThis.commerceHarness')
    .replace(/import \{ ElMessage, ElMessageBox \} from ['"]element-plus['"]/, 'const { ElMessage, ElMessageBox } = globalThis.commerceHarness')
    .replace(/import \{ authApi, orderApi, profileApi \} from ['"]@\/api['"]/, 'const { authApi, orderApi, profileApi } = globalThis.commerceHarness')
    .replace(/import \{ clearTokens \} from ['"]@\/api\/http['"]/, 'const { clearTokens } = globalThis.commerceHarness')
    .replace(/import \{ isSessionExpired \} from ['"]@\/api\/session['"]/, "const isSessionExpired = error => error?.code === 'AUTH_SESSION_EXPIRED'")
    .replace(/import \{ useAuthStore \} from ['"]@\/stores\/auth['"]/, 'const { useAuthStore } = globalThis.commerceHarness')
    .replace(/import MyCoupons from ['"]@\/components\/MyCoupons\.vue['"]/, 'const MyCoupons = {}')
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

test('支付时订单已过期只提示关闭，不误报课程解锁', async () => {
  const messages = []
  const closedOrder = { id: 'o-closed', status: 'CLOSED', courseId: '7', paymentMode: 'LOCAL_DEMO' }
  const { app, view } = await mountPurchase([], () => result(closedOrder), () => result(closedOrder), messages)
  try {
    view.activeOrder = closedOrder
    await view.payOrder()
    assert.deepEqual(messages.at(-1), ['warning', '订单已关闭，优惠券已退回，请重新下单'])
    assert.equal(messages.some(([level]) => level === 'success'), false)
  } finally { app.unmount() }
})

test('个人中心支付过期订单同步关闭状态并退回优惠券，不误报课程解锁', async () => {
  const messages = []
  const pendingOrder = { id: 'o-profile', status: 'PENDING', courseId: '7', paymentMode: 'LOCAL_DEMO' }
  const closedOrder = { ...pendingOrder, status: 'CLOSED' }
  const { app, view } = await mountProfile(pendingOrder, () => result(closedOrder), messages)
  try {
    await view.payPendingOrder(view.orders[0])
    assert.equal(view.orders[0].status, 'CLOSED')
    assert.equal(view.couponsKey, 1)
    assert.deepEqual(messages.at(-1), ['warning', '订单已关闭，优惠券已退回，请重新下单'])
    assert.equal(messages.some(([level]) => level === 'success'), false)
  } finally { app.unmount() }
})

test('本机演示付款明确实付零元，应付金额只用于优惠计算', async () => {
  const detail = await readFile(new URL('../src/views/CourseDetailView.vue', import.meta.url), 'utf8')
  const profile = await readFile(new URL('../src/views/ProfileView.vue', import.meta.url), 'utf8')
  assert.match(detail, /优惠后金额（仅计算）/)
  assert.match(detail, /本机演示实付<\/span><strong>¥0\.00/)
  assert.match(detail, /确认演示付款（实付 ¥0\.00）/)
  assert.match(profile, /本机演示支付 ¥0\.00/)
})
