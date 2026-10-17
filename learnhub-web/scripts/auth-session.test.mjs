import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import axios from 'axios'
import ts from 'typescript'
import { createRequire } from 'node:module'
import { pathToFileURL } from 'node:url'
import { createPinia } from 'pinia'
import { compileScript, parse } from '@vue/compiler-sfc'

const require = createRequire(import.meta.url)
const moduleUrl = name => pathToFileURL(require.resolve(name)).href

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

test('助理生成401只刷新会话但不重放，保留登录并避免重复付费', async () => {
  const app = await setup()
  let refreshCalls = 0
  let generated = 0
  axios.defaults.adapter = async config => {
    refreshCalls++
    return response(config, { data: { accessToken: 'new-access', refreshToken: 'new-refresh' } })
  }
  app.http.defaults.adapter = async config => { generated++; throw failure(config, 401) }
  await assert.rejects(app.http.post('/assistant/course-qa', { courseId: '1', question: 'Lua' }),
    error => session.isAssistantRetryRequired(error))
  assert.equal(refreshCalls, 1)
  assert.equal(generated, 1)
  assert.equal(app.storage.get(accessKey), 'new-access')
  assert.equal(app.storage.get(refreshKey), 'new-refresh')
  assert.deepEqual(app.redirects, [])
  assert.equal(app.clears(), 0)
})

test('助理生成401后刷新被拒绝才清理会话并跳转登录', async () => {
  const app = await setup()
  let generated = 0
  axios.defaults.adapter = async config => { throw failure(config, 409) }
  app.http.defaults.adapter = async config => { generated++; throw failure(config, 401) }
  await assert.rejects(app.http.post('/assistant/agent', { courseId: '1', goal: '学习建议' }), session.isSessionExpired)
  assert.equal(generated, 1)
  assert.equal(app.storage.size, 0)
  assert.equal(app.clears(), 1)
  assert.equal(app.redirects.length, 1)
})

test('助理生成401后刷新网络故障或500保留现有会话', async () => {
  for (const refreshStatus of [undefined, 500]) {
    const app = await setup()
    axios.defaults.adapter = async config => {
      throw refreshStatus ? failure(config, refreshStatus) : new Error('offline')
    }
    app.http.defaults.adapter = async config => { throw failure(config, 401) }
    await assert.rejects(app.http.post('/assistant/course-qa', { courseId: '1', question: 'Lua' }),
      error => !session.isSessionExpired(error) && !session.isAssistantRetryRequired(error))
    assert.equal(app.storage.get(accessKey), 'expired-access')
    assert.equal(app.storage.get(refreshKey), 'refresh-token')
    assert.deepEqual(app.redirects, [])
    assert.equal(app.clears(), 0)
  }
})

test('助理与普通请求并发401只刷新一次，仅普通请求自动重放', async () => {
  const app = await setup()
  let refreshCalls = 0
  let assistantCalls = 0
  let ordinaryCalls = 0
  axios.defaults.adapter = async config => {
    refreshCalls++
    await new Promise(resolve => setImmediate(resolve))
    return response(config, { data: { accessToken: 'new-access', refreshToken: 'new-refresh' } })
  }
  app.http.defaults.adapter = async config => {
    if (config.url.startsWith('/assistant/')) {
      assistantCalls++
      throw failure(config, 401)
    }
    ordinaryCalls++
    if (config.headers.Authorization !== 'Bearer new-access') throw failure(config, 401)
    return response(config)
  }
  const [assistant, ordinary] = await Promise.allSettled([
    app.http.post('/assistant/course-qa', { courseId: '1', question: 'Lua' }),
    app.http.get('/profile/overview'),
  ])
  assert.equal(assistant.status, 'rejected')
  assert.ok(session.isAssistantRetryRequired(assistant.reason))
  assert.equal(ordinary.status, 'fulfilled')
  assert.equal(refreshCalls, 1)
  assert.equal(assistantCalls, 1)
  assert.equal(ordinaryCalls, 2)
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

async function authStore(app, overrides = {}) {
  globalThis.storeHarness = {
    authApi: { logout: app.logoutSession, ...overrides },
    SessionExpiredError: session.SessionExpiredError,
    ...app,
  }
  const source = (await readFile(new URL('../src/stores/auth.ts', import.meta.url), 'utf8'))
    .replace("from 'vue'", `from '${moduleUrl('vue')}'`)
    .replace("from 'pinia'", `from '${moduleUrl('pinia')}'`)
    .replace("import { authApi } from '@/api'", 'const { authApi } = globalThis.storeHarness')
    .replace(/import \{\s*AUTH_SESSION_CLEARED_EVENT,[\s\S]*?\} from '@\/api\/http'/,
      'const { AUTH_SESSION_CLEARED_EVENT, AUTH_SESSION_REPLACED_EVENT, clearTokens, getSessionGeneration, isCurrentSession, replaceSessionFromStorage, saveTokens } = globalThis.storeHarness')
    .replace("import { SessionExpiredError } from '@/api/session'", 'const { SessionExpiredError } = globalThis.storeHarness')
  const module = await import(url(compile(source) + `\n// ${++serial}`))
  const pinia = createPinia()
  return module.useAuthStore(pinia)
}

test('退出提交双令牌，成功后清理本地会话和用户状态', async () => {
  const app = await setup()
  const store = await authStore(app)
  store.user = { id: 1, username: 'test' }
  app.http.defaults.adapter = async config => {
    assert.equal(config.url, '/auth/logout')
    assert.equal(config.headers.Authorization, 'Bearer expired-access')
    assert.deepEqual(JSON.parse(config.data), { refreshToken: 'refresh-token' })
    return response(config, { data: null })
  }
  await store.logout()
  assert.equal(app.storage.size, 0)
  assert.equal(store.user, null)
  assert.equal(store.authenticated, false)
  store.$dispose()
})

test('退出401、500或断网均清理本地，保留失败供界面反馈且不触发刷新', async () => {
  for (const status of [401, 500, undefined]) {
    const app = await setup()
    const store = await authStore(app)
    let refreshCalls = 0
    axios.defaults.adapter = async config => { refreshCalls++; return response(config) }
    app.http.defaults.adapter = async config => { throw status ? failure(config, status) : new Error('offline') }
    await assert.rejects(store.logout())
    assert.equal(app.storage.size, 0)
    assert.equal(refreshCalls, 0)
    assert.deepEqual(app.redirects, [])
    store.$dispose()
  }
})

test('没有Refresh Token时仍提交退出并清理Access Token', async () => {
  const app = await setup({ refresh: false })
  const store = await authStore(app)
  app.http.defaults.adapter = async config => {
    assert.deepEqual(JSON.parse(config.data), {})
    return response(config)
  }
  await store.logout()
  assert.equal(app.storage.size, 0)
  store.$dispose()
})

test('退出等待进行中的刷新，并撤销轮换后的最新双令牌', async () => {
  const app = await setup()
  const store = await authStore(app)
  let releaseRefresh
  let logoutCalls = 0
  axios.defaults.adapter = config => new Promise(resolve => {
    releaseRefresh = () => resolve(response(config, { data: { accessToken: 'new-access', refreshToken: 'new-refresh' } }))
  })
  app.http.defaults.adapter = async config => {
    if (config.url === '/auth/logout') {
      logoutCalls++
      assert.equal(config.headers.Authorization, 'Bearer new-access')
      assert.deepEqual(JSON.parse(config.data), { refreshToken: 'new-refresh' })
      return response(config)
    }
    if (config.headers.Authorization === 'Bearer expired-access') throw failure(config, 401)
    return response(config)
  }
  const request = app.http.get('/profile/overview')
  await new Promise(resolve => setImmediate(resolve))
  const logout = store.logout()
  await new Promise(resolve => setImmediate(resolve))
  assert.equal(logoutCalls, 0)
  releaseRefresh()
  await Promise.all([request, logout])
  assert.equal(logoutCalls, 1)
  assert.equal(app.storage.size, 0)
  store.$dispose()
})

test('退出期间迟到的401不刷新，清理后迟到的刷新结果不能恢复会话', async () => {
  const app = await setup()
  const store = await authStore(app)
  let releaseOld
  let releaseLogout
  let refreshCalls = 0
  axios.defaults.adapter = async config => { refreshCalls++; return response(config) }
  app.http.defaults.adapter = config => new Promise((resolve, reject) => {
    if (config.url === '/auth/logout') releaseLogout = () => resolve(response(config))
    else releaseOld = () => reject(failure(config, 401))
  })
  const old = app.http.get('/profile/overview')
  await new Promise(resolve => setImmediate(resolve))
  const logout = store.logout()
  await new Promise(resolve => setImmediate(resolve))
  releaseOld()
  await assert.rejects(old)
  releaseLogout()
  await logout
  assert.equal(refreshCalls, 0)
  assert.equal(app.storage.size, 0)
  store.$dispose()

  const second = await setup()
  let releaseRefresh
  axios.defaults.adapter = config => new Promise(resolve => {
    releaseRefresh = () => resolve(response(config, { data: { accessToken: 'late-access', refreshToken: 'late-refresh' } }))
  })
  second.http.defaults.adapter = async config => { throw failure(config, 401) }
  const pending = second.http.get('/profile/overview')
  await new Promise(resolve => setImmediate(resolve))
  second.clearTokens()
  releaseRefresh()
  await assert.rejects(pending, session.isSessionExpired)
  assert.equal(second.storage.size, 0)
})

test('退出界面区分撤销成功与失败，失败仍离开个人页面且重复点击只提交一次', async () => {
  const source = await readFile(new URL('../src/components/AppHeader.vue', import.meta.url), 'utf8')
  const { descriptor } = parse(source)
  const script = compileScript(descriptor, { id: 'header-auth-test' }).content
    .replace(/import \{ nextTick, onMounted, ref \} from 'vue'/,
      "import { nextTick, ref } from 'vue'; const onMounted = () => {}")
    .replace(/from 'vue'/g, `from '${moduleUrl('vue')}'`)
    .replace("import { useRouter } from 'vue-router'", 'const { useRouter } = globalThis.headerHarness')
    .replace("import { useAuthStore } from '@/stores/auth'", 'const { useAuthStore } = globalThis.headerHarness')
    .replace("import { ElMessage } from 'element-plus'", 'const { ElMessage } = globalThis.headerHarness')
    .replace("import LoginDialog from '@/components/LoginDialog.vue'", 'const LoginDialog = {}')
  for (const failed of [false, true]) {
    const messages = []
    const paths = []
    let calls = 0
    let release
    globalThis.headerHarness = {
      useRouter: () => ({ push: async path => paths.push(path) }),
      useAuthStore: () => ({ logout: () => {
        calls++
        return new Promise((resolve, reject) => { release = () => failed ? reject(new Error('offline')) : resolve() })
      } }),
      ElMessage: { success: message => messages.push(['success', message]), warning: message => messages.push(['warning', message]) },
    }
    const module = await import(url(compile(script) + `\n// ${++serial}`))
    const header = module.default.setup({}, { expose() {} })
    const pending = header.logout()
    await header.logout()
    assert.equal(calls, 1)
    assert.equal(header.loggingOut.value, true)
    release()
    await pending
    assert.equal(header.loggingOut.value, false)
    assert.deepEqual(paths, ['/courses'])
    assert.equal(messages[0][0], failed ? 'warning' : 'success')
    if (failed) assert.match(messages[0][1], /未能确认服务端令牌撤销/)
  }
})

test('其他标签页退出后，当前标签页进行中的刷新不能恢复已清除会话', async () => {
  const app = await setup()
  const store = await authStore(app)
  store.user = { id: 1, username: 'test' }
  let releaseRefresh
  axios.defaults.adapter = config => new Promise(resolve => {
    releaseRefresh = () => resolve(response(config, { data: { accessToken: 'late-access', refreshToken: 'late-refresh' } }))
  })
  app.http.defaults.adapter = async config => { throw failure(config, 401) }
  const pending = app.http.get('/profile/overview')
  await new Promise(resolve => setImmediate(resolve))
  app.storage.delete(accessKey)
  window.dispatchEvent(Object.assign(new Event('storage'), { key: accessKey, newValue: null }))
  assert.equal(store.user, null)
  releaseRefresh()
  await assert.rejects(pending, session.isSessionExpired)
  assert.equal(app.storage.size, 0)
  assert.equal(store.authenticated, false)
  store.$dispose()
})

test('退出后重新登录，旧401不重放到新账户，旧刷新响应不清除新会话', async () => {
  const app = await setup()
  let releaseOld
  let calls = 0
  app.http.defaults.adapter = config => {
    calls++
    return new Promise((resolve, reject) => { releaseOld = () => reject(failure(config, 401)) })
  }
  const old = app.http.get('/profile/overview')
  await new Promise(resolve => setImmediate(resolve))
  app.clearTokens()
  app.saveTokens({ accessToken: 'other-user-access', refreshToken: 'other-user-refresh' })
  releaseOld()
  await assert.rejects(old, session.isSessionExpired)
  assert.equal(calls, 1)
  assert.equal(app.storage.get(accessKey), 'other-user-access')
  assert.deepEqual(app.redirects, [])

  for (const rejected of [false, true]) {
    const second = await setup()
    let releaseRefresh
    axios.defaults.adapter = config => new Promise((resolve, reject) => {
      releaseRefresh = () => rejected ? reject(failure(config, 409)) : resolve(response(config, {
        data: { accessToken: 'late-access', refreshToken: 'late-refresh' },
      }))
    })
    second.http.defaults.adapter = async config => { throw failure(config, 401) }
    const pending = second.http.get('/profile/overview')
    await new Promise(resolve => setImmediate(resolve))
    second.clearTokens()
    second.saveTokens({ accessToken: 'other-user-access', refreshToken: 'other-user-refresh' })
    releaseRefresh()
    await assert.rejects(pending, session.isSessionExpired)
    assert.equal(second.storage.get(accessKey), 'other-user-access')
    assert.equal(second.storage.get(refreshKey), 'other-user-refresh')
    assert.deepEqual(second.redirects, [])
  }
})

test('直接切换账号后丢弃旧账号的成功响应和迟到401', async () => {
  const app = await setup()
  const pending = new Map()
  let calls = 0
  app.http.defaults.adapter = config => {
    calls++
    return new Promise((resolve, reject) => pending.set(config.url, { config, resolve, reject }))
  }
  const oldSuccess = app.http.get('/old-success')
  const oldUnauthorized = app.http.get('/old-unauthorized')
  await new Promise(resolve => setImmediate(resolve))

  app.saveTokens({ accessToken: 'other-user-access', refreshToken: 'other-user-refresh' })
  pending.get('/old-success').resolve(response(pending.get('/old-success').config, { owner: 'old-user' }))
  pending.get('/old-unauthorized').reject(failure(pending.get('/old-unauthorized').config, 401))

  await assert.rejects(oldSuccess, session.isSessionExpired)
  await assert.rejects(oldUnauthorized, session.isSessionExpired)
  assert.equal(calls, 2)
  assert.equal(app.storage.get(accessKey), 'other-user-access')
  assert.deepEqual(app.redirects, [])
})

test('刷新轮换令牌不改变当前账号代次，请求仍可正常完成', async () => {
  const app = await setup()
  let releaseSlow
  axios.defaults.adapter = async config => response(config, {
    data: { accessToken: 'new-access', refreshToken: 'new-refresh' },
  })
  app.http.defaults.adapter = config => {
    if (config.url === '/slow') return new Promise(resolve => { releaseSlow = () => resolve(response(config)) })
    if (config.headers.Authorization === 'Bearer expired-access') throw failure(config, 401)
    return Promise.resolve(response(config))
  }
  const slow = app.http.get('/slow')
  await new Promise(resolve => setImmediate(resolve))
  await app.http.get('/refresh-trigger')
  releaseSlow()
  await slow
  assert.equal(app.storage.get(accessKey), 'new-access')
})

test('其他标签页替换账号后清空旧用户并加载新账号资料', async () => {
  const app = await setup()
  const store = await authStore(app)
  store.user = { id: 1, username: 'old-user' }
  let meCalls = 0
  globalThis.storeHarness.authApi.me = async () => {
    meCalls++
    return { data: { data: { id: 2, username: 'new-user', roles: [] } } }
  }
  app.storage.set(accessKey, 'other-user-access')
  app.storage.set(refreshKey, 'other-user-refresh')
  window.dispatchEvent(Object.assign(new Event('storage'), {
    key: accessKey,
    oldValue: 'expired-access',
    newValue: 'other-user-access',
  }))
  await new Promise(resolve => setImmediate(resolve))
  assert.equal(meCalls, 1)
  assert.equal(store.user.username, 'new-user')
  assert.equal(store.authenticated, true)
  store.$dispose()
})

test('旧账号刷新任务不被新账号复用，旧任务结束也不清除新刷新任务', async () => {
  const app = await setup()
  let releaseA
  let releaseB
  axios.defaults.adapter = config => {
    const body = typeof config.data === 'string' ? JSON.parse(config.data) : config.data
    return new Promise(resolve => {
      if (body.refreshToken === 'refresh-token') {
        releaseA = () => resolve(response(config, { data: { accessToken: 'late-a', refreshToken: 'late-a-refresh' } }))
      } else {
        releaseB = () => resolve(response(config, { data: { accessToken: 'fresh-b', refreshToken: 'fresh-b-refresh' } }))
      }
    })
  }
  app.http.defaults.adapter = async config => {
    if (config.headers.Authorization === 'Bearer fresh-b') return response(config)
    throw failure(config, 401)
  }

  const oldRequest = app.http.get('/account-a')
  await new Promise(resolve => setImmediate(resolve))
  app.saveTokens({ accessToken: 'account-b', refreshToken: 'account-b-refresh' })
  const newRequest = app.http.get('/account-b')
  await new Promise(resolve => setImmediate(resolve))
  releaseA()
  await assert.rejects(oldRequest, session.isSessionExpired)
  assert.equal(app.storage.get(accessKey), 'account-b')
  releaseB()
  await newRequest
  assert.equal(app.storage.get(accessKey), 'fresh-b')
})

test('旧登录资料和旧退出迟到后都不能覆盖或清理新账号', async () => {
  const app = await setup()
  let releaseOldProfile
  let profileCalls = 0
  let releaseLogout
  const store = await authStore(app, {
    login: async ({ username }) => response({}, { data: {
      accessToken: `${username}-access`,
      refreshToken: `${username}-refresh`,
    } }),
    me: async () => {
      profileCalls++
      if (profileCalls === 1) {
        return new Promise(resolve => {
          releaseOldProfile = () => resolve(response({}, { data: { id: 1, username: 'account-a', roles: [] } }))
        })
      }
      return response({}, { data: { id: 2, username: 'account-b', roles: [] } })
    },
    logout: () => new Promise(resolve => { releaseLogout = resolve }),
  })
  const oldLogin = store.login('account-a', 'password')
  await new Promise(resolve => setImmediate(resolve))
  await store.login('account-b', 'password')
  releaseOldProfile()
  await oldLogin
  assert.equal(store.user.username, 'account-b')
  assert.equal(app.storage.get(accessKey), 'account-b-access')

  const oldLogout = store.logout()
  await new Promise(resolve => setImmediate(resolve))
  await store.login('account-b', 'password')
  releaseLogout()
  await oldLogout
  assert.equal(store.user.username, 'account-b')
  assert.equal(store.authenticated, true)
  assert.equal(app.storage.get(accessKey), 'account-b-access')
  store.$dispose()
})

test('其他标签页换账号期间迟到的登录响应不能覆盖新账号', async () => {
  const app = await setup()
  let releaseLogin
  const store = await authStore(app, {
    login: () => new Promise(resolve => {
      releaseLogin = () => resolve(response({}, { data: { accessToken: 'late-a', refreshToken: 'late-a-refresh' } }))
    }),
    me: async () => response({}, { data: { id: 2, username: 'account-b', roles: [] } }),
  })
  const oldLogin = store.login('account-a', 'password')
  await new Promise(resolve => setImmediate(resolve))
  app.storage.set(refreshKey, 'account-b-refresh')
  app.storage.set(accessKey, 'account-b-access')
  window.dispatchEvent(Object.assign(new Event('storage'), {
    key: accessKey,
    oldValue: 'expired-access',
    newValue: 'account-b-access',
  }))
  await new Promise(resolve => setImmediate(resolve))
  releaseLogin()
  await assert.rejects(oldLogin, session.isSessionExpired)
  assert.equal(app.storage.get(accessKey), 'account-b-access')
  assert.equal(store.user.username, 'account-b')
  store.$dispose()
})

test('正在刷新的请求超时或500失败，退出仍撤销现有双令牌并清理本地', async () => {
  for (const status of [undefined, 500]) {
    const app = await setup()
    const store = await authStore(app)
    let rejectRefresh
    let logoutCalls = 0
    axios.defaults.adapter = config => new Promise((resolve, reject) => {
      rejectRefresh = () => reject(status ? failure(config, status) : new axios.AxiosError('timeout', 'ECONNABORTED', config))
    })
    app.http.defaults.adapter = async config => {
      if (config.url === '/auth/logout') {
        logoutCalls++
        assert.equal(config.headers.Authorization, 'Bearer expired-access')
        assert.deepEqual(JSON.parse(config.data), { refreshToken: 'refresh-token' })
        return response(config)
      }
      throw failure(config, 401)
    }
    const pending = app.http.get('/profile/overview')
    const rejected = assert.rejects(pending)
    await new Promise(resolve => setImmediate(resolve))
    const logout = store.logout()
    rejectRefresh()
    await Promise.all([rejected, logout])
    assert.equal(logoutCalls, 1)
    assert.equal(app.storage.size, 0)
    assert.deepEqual(app.redirects, [])
    store.$dispose()
  }
})
