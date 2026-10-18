import axios, { type AxiosError, type InternalAxiosRequestConfig } from 'axios'
import type { ApiResult, TokenPair } from '@/types'
import { AssistantRetryRequiredError, SessionExpiredError } from './session'

const ACCESS_KEY = 'learnhub_access_token'
const REFRESH_KEY = 'learnhub_refresh_token'
export const AUTH_SESSION_CLEARED_EVENT = 'learnhub:auth-session-cleared'
export const AUTH_SESSION_REPLACED_EVENT = 'learnhub:auth-session-replaced'

export const http = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || '/api',
  timeout: 10_000,
})

type RefreshAttempt = { generation: number; promise: Promise<string> }
let refreshing: RefreshAttempt | null = null
let redirectingToLogin = false
let loggingOut = false
let sessionGeneration = 0
type SessionRequestConfig = InternalAxiosRequestConfig & { _retry?: boolean; _sessionGeneration?: number }

export function saveTokens(tokens: TokenPair) {
  sessionGeneration++
  refreshing = null
  redirectingToLogin = false
  loggingOut = false
  writeTokens(tokens)
  window.dispatchEvent(new Event(AUTH_SESSION_REPLACED_EVENT))
}

function saveRefreshedTokens(tokens: TokenPair) {
  writeTokens(tokens)
}

function writeTokens(tokens: TokenPair) {
  localStorage.setItem(REFRESH_KEY, tokens.refreshToken)
  localStorage.setItem(ACCESS_KEY, tokens.accessToken)
}

export function replaceSessionFromStorage() {
  sessionGeneration++
  refreshing = null
  redirectingToLogin = false
  loggingOut = false
  // 原生 fetch SSE 不经过 Axios 响应拦截器，跨标签替换身份必须立即通知所有私有视图。
  window.dispatchEvent(new Event(AUTH_SESSION_REPLACED_EVENT))
}

export function clearTokens() {
  sessionGeneration++
  refreshing = null
  localStorage.removeItem(ACCESS_KEY)
  localStorage.removeItem(REFRESH_KEY)
  window.dispatchEvent(new Event(AUTH_SESSION_CLEARED_EVENT))
}

http.interceptors.request.use((config) => {
  const request = config as SessionRequestConfig
  request._sessionGeneration ??= sessionGeneration
  if (request._sessionGeneration !== sessionGeneration) throw new SessionExpiredError()
  if (redirectingToLogin && !isAuthEndpoint(config.url)) throw new SessionExpiredError()
  const token = localStorage.getItem(ACCESS_KEY)
  if (token) config.headers.Authorization = `Bearer ${token}`
  return config
})

async function refreshAccessToken() {
  const generation = sessionGeneration
  const refreshToken = localStorage.getItem(REFRESH_KEY)
  if (!refreshToken) throw new SessionExpiredError()
  const { data } = await axios.post<ApiResult<TokenPair>>(
    `${http.defaults.baseURL}/auth/refresh`,
    { refreshToken },
    { timeout: 10_000 },
  )
  if (generation !== sessionGeneration) throw new SessionExpiredError()
  const logoutPending = loggingOut
  saveRefreshedTokens(data.data)
  loggingOut = logoutPending
  return data.data.accessToken
}

function refreshCurrentSession() {
  if (refreshing?.generation === sessionGeneration) return refreshing.promise
  const attempt = { generation: sessionGeneration } as RefreshAttempt
  attempt.promise = refreshAccessToken().finally(() => {
    if (refreshing === attempt) refreshing = null
  })
  refreshing = attempt
  return attempt.promise
}

export function getSessionGeneration() {
  return sessionGeneration
}

export function isCurrentSession(generation: number) {
  return generation === sessionGeneration
}

export async function logoutSession() {
  const generation = sessionGeneration
  loggingOut = true
  // 已开始的刷新可能轮换令牌；等它结束后撤销最新的一对凭据。
  try { await refreshing?.promise } catch { /* 刷新失败仍尝试撤销现有凭据。 */ }
  if (generation !== sessionGeneration) throw new SessionExpiredError()
  loggingOut = true
  const refreshToken = localStorage.getItem(REFRESH_KEY)
  return http.post<ApiResult<void>>('/auth/logout', refreshToken ? { refreshToken } : {})
}

function isAuthEndpoint(url?: string) {
  return /\/auth\/(login|register|refresh)(?:[?#]|$)/.test(url || '')
}

function expireSession() {
  if (!redirectingToLogin) {
    redirectingToLogin = true
    clearTokens()
    if (location.pathname !== '/login') {
      const returnTo = location.pathname + location.search + location.hash
      location.assign(`/login?redirect=${encodeURIComponent(returnTo)}`)
    }
  }
  return new SessionExpiredError()
}

http.interceptors.response.use(
  (response) => {
    const request = response.config as SessionRequestConfig
    if (!isAuthEndpoint(request.url)
      && request._sessionGeneration !== undefined
      && request._sessionGeneration !== sessionGeneration) {
      return Promise.reject(new SessionExpiredError())
    }
    return response
  },
  async (error: AxiosError) => {
    const original = error.config as SessionRequestConfig | undefined
    if (error.response?.status !== 401 || !original || isAuthEndpoint(original.url)) {
      return Promise.reject(error)
    }
    if (original._sessionGeneration !== undefined && original._sessionGeneration !== sessionGeneration) {
      return Promise.reject(new SessionExpiredError())
    }
    // 生成可能已产生费用：可以恢复会话，但绝不自动重放助理请求。
    if (original.method?.toLowerCase() === 'post' && original.url?.startsWith('/assistant/')) {
      if (redirectingToLogin || loggingOut) return Promise.reject(expireSession())
      const generation = sessionGeneration
      try {
        const currentToken = localStorage.getItem(ACCESS_KEY)
        // 其他并发请求可能已刷新会话，此时不再消费旋转后的 Refresh Token。
        if (!currentToken || original.headers.Authorization === `Bearer ${currentToken}`) {
          await refreshCurrentSession()
        }
        if (generation !== sessionGeneration) return Promise.reject(new SessionExpiredError())
        return Promise.reject(new AssistantRetryRequiredError())
      } catch (refreshError) {
        if (generation !== sessionGeneration) return Promise.reject(new SessionExpiredError())
        const status = (refreshError as AxiosError).response?.status
        if (refreshError instanceof SessionExpiredError || status === 401 || status === 403 || status === 409) {
          return Promise.reject(expireSession())
        }
        // 刷新服务暂时不可用时保留凭据，不把网络故障当成退出登录。
        return Promise.reject(refreshError)
      }
    }
    // 退出失败交给调用方提示，退出期间的旧请求不能再创建新会话。
    if (original.url === '/auth/logout' || loggingOut) return Promise.reject(error)
    if (redirectingToLogin || original._retry) return Promise.reject(expireSession())
    original._retry = true
    const generation = sessionGeneration
    try {
      const currentToken = localStorage.getItem(ACCESS_KEY)
      // 较晚返回的旧令牌请求复用刚刷新的令牌，避免再次消费旋转后的刷新令牌。
      let token = currentToken
      if (!currentToken || original.headers.Authorization === `Bearer ${currentToken}`) {
        token = await refreshCurrentSession()
      }
      if (generation !== sessionGeneration) return Promise.reject(new SessionExpiredError())
      original.headers.Authorization = `Bearer ${token}`
      return http(original)
    } catch (refreshError) {
      if (generation !== sessionGeneration) return Promise.reject(new SessionExpiredError())
      const status = (refreshError as AxiosError).response?.status
      if (refreshError instanceof SessionExpiredError || status === 401 || status === 403 || status === 409) {
        return Promise.reject(expireSession())
      }
      // 网络中断或服务故障不等于会话失效，不清除仍可能有效的凭据。
      return Promise.reject(refreshError)
    }
  },
)
