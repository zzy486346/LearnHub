import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import axios from 'axios'
import ts from 'typescript'

const accessKey = 'learnhub_access_token'
const refreshKey = 'learnhub_refresh_token'
let serial = 0
const compile = source => ts.transpileModule(source, {
  compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ESNext },
}).outputText
const url = code => `data:text/javascript;base64,${Buffer.from(code).toString('base64')}`
const sessionUrl = url(compile(await readFile(new URL('../src/api/session.ts', import.meta.url), 'utf8')))
const session = await import(sessionUrl)

function response(config, data = {}) { return { config, data, status: 200, statusText: 'OK', headers: {} } }
function failure(config, status) {
  return new axios.AxiosError('request failed', 'ERR_BAD_REQUEST', config, null,
    { ...response(config), status })
}

async function setup({ refresh = true, path = '/profile', search = '?tab=coupons', hash = '#wallet' } = {}) {
  const storage = new Map([[accessKey, 'expired-access'], ...(refresh ? [[refreshKey, 'refresh-token']] : [])])
  const redirects = []
  let clears = 0
  globalThis.localStorage = {
    getItem: key => storage.get(key) ?? null,
    setItem: (key, value) => storage.set(key, value),
    removeItem: key => storage.delete(key),
  }
  globalThis.window = new EventTarget()
  window.addEventListener('learnhub:auth-session-cleared', () => clears++)
  globalThis.location = { pathname: path, search, hash, assign: target => redirects.push(target) }
  globalThis.authHarness = { axios }
  const source = (await readFile(new URL('../src/api/http.ts', import.meta.url), 'utf8'))
    .replace('import.meta.env.VITE_API_BASE_URL', 'undefined')
    .replace("from './session'", `from '${sessionUrl}'`)
  const code = compile(source).replace(/import axios from ['"]axios['"];?/, 'const { axios } = globalThis.authHarness;')
  const module = await import(url(code + `\n// ${++serial}`))
  return { ...module, storage, redirects, clears: () => clears }
}

test('无刷新令牌时清理登录态并保留路径、查询参数和锚点，只跳转一次', async () => {
  const app = await setup({ refresh: false })
  app.http.defaults.adapter = async config => { throw failure(config, 401) }
  const errors = await Promise.allSettled([app.http.get('/profile/overview'), app.http.post('/coupons/1/claim')])
  assert.ok(errors.every(result => result.status === 'rejected' && session.isSessionExpired(result.reason)))
  assert.equal(app.clears(), 1)
  assert.equal(app.storage.size, 0)
  assert.deepEqual(app.redirects, ['/login?redirect=%2Fprofile%3Ftab%3Dcoupons%23wallet'])
})

test('并发401只刷新一次，刷新成功后重放操作，不跳登录', async () => {
  const app = await setup()
  let refreshCalls = 0
  axios.defaults.adapter = async config => {
    refreshCalls++
    await new Promise(resolve => setImmediate(resolve))
    return response(config, { data: { accessToken: 'new-access', refreshToken: 'new-refresh' } })
  }
  app.http.defaults.adapter = async config => {
    if (config.headers.Authorization !== 'Bearer new-access') throw failure(config, 401)
    return response(config, { success: true })
  }
  await Promise.all([app.http.get('/profile/overview'), app.http.post('/coupons/1/claim')])
  assert.equal(refreshCalls, 1)
  assert.equal(app.storage.get(accessKey), 'new-access')
  assert.equal(app.storage.get(refreshKey), 'new-refresh')
  assert.deepEqual(app.redirects, [])
})

test('刷新被拒绝时所有等待请求获得会话失效错误，不重复跳转', async () => {
  const app = await setup()
  let refreshCalls = 0
  axios.defaults.adapter = async config => {
    refreshCalls++
    await new Promise(resolve => setImmediate(resolve))
    throw failure(config, 409)
  }
  app.http.defaults.adapter = async config => { throw failure(config, 401) }
  const requests = await Promise.allSettled([app.http.get('/profile/overview'), app.http.put('/auth/me/password')])
  assert.ok(requests.every(result => result.status === 'rejected' && session.isSessionExpired(result.reason)))
  assert.equal(refreshCalls, 1)
  assert.equal(app.redirects.length, 1)
  assert.equal(app.clears(), 1)
})

test('刷新成功但重放仍401时直接跳登录，不再次刷新或返回普通加载错误', async () => {
  const app = await setup()
  let refreshCalls = 0
  axios.defaults.adapter = async config => {
    refreshCalls++
    return response(config, { data: { accessToken: 'new-access', refreshToken: 'new-refresh' } })
  }
  app.http.defaults.adapter = async config => { throw failure(config, 401) }
  await assert.rejects(app.http.get('/profile/overview'), session.isSessionExpired)
  assert.equal(refreshCalls, 1)
  assert.equal(app.redirects.length, 1)
  assert.equal(app.storage.size, 0)
})

test('刷新网络故障或500保留凭据，普通403也不跳登录', async () => {
  for (const refreshStatus of [undefined, 500]) {
    const app = await setup()
    axios.defaults.adapter = async config => { throw refreshStatus ? failure(config, refreshStatus) : new Error('offline') }
    app.http.defaults.adapter = async config => { throw failure(config, 401) }
    await assert.rejects(app.http.get('/profile/overview'), error => !session.isSessionExpired(error))
    assert.equal(app.storage.get(accessKey), 'expired-access')
    assert.deepEqual(app.redirects, [])
  }
  const app = await setup()
  app.http.defaults.adapter = async config => { throw failure(config, 403) }
  await assert.rejects(app.http.get('/admin/courses'), error => error.response.status === 403)
  assert.equal(app.storage.size, 2)
  assert.deepEqual(app.redirects, [])
})

test('刷新成功后的权限或业务失败不清理新会话', async () => {
  for (const status of [403, 409]) {
    const app = await setup()
    axios.defaults.adapter = async config => response(config, {
      data: { accessToken: 'new-access', refreshToken: 'new-refresh' },
    })
    app.http.defaults.adapter = async config => {
      throw failure(config, config.headers.Authorization === 'Bearer new-access' ? status : 401)
    }
    await assert.rejects(app.http.post('/coupons/1/claim'), error => error.response?.status === status)
    assert.equal(app.storage.get(accessKey), 'new-access')
    assert.deepEqual(app.redirects, [])
    assert.equal(app.clears(), 0)
  }
})

test('登录凭据错误不刷新或跳转；已在登录页的会话失效不产生跳转循环', async () => {
  const app = await setup({ path: '/login' })
  let refreshCalls = 0
  axios.defaults.adapter = async config => { refreshCalls++; throw failure(config, 401) }
  app.http.defaults.adapter = async config => { throw failure(config, 401) }
  await assert.rejects(app.http.post('/auth/login', { username: 'bad' }), error => error.response.status === 401)
  assert.equal(refreshCalls, 0)
  app.storage.delete(refreshKey)
  await assert.rejects(app.http.get('/auth/me'), session.isSessionExpired)
  assert.deepEqual(app.redirects, [])
})

test('较晚返回的旧请求复用已刷新令牌，不二次刷新', async () => {
  const app = await setup()
  let refreshCalls = 0
  let releaseOldRequest
  axios.defaults.adapter = async config => {
    refreshCalls++
    return response(config, { data: { accessToken: 'new-access', refreshToken: 'new-refresh' } })
  }
  app.http.defaults.adapter = async config => {
    if (config.headers.Authorization === 'Bearer new-access') return response(config)
    if (config.url === '/slow') await new Promise(resolve => { releaseOldRequest = resolve })
    throw failure(config, 401)
  }
  const slow = app.http.get('/slow')
  await new Promise(resolve => setImmediate(resolve))
  await app.http.get('/fast')
  releaseOldRequest()
  await slow
  assert.equal(refreshCalls, 1)
  assert.deepEqual(app.redirects, [])
})
