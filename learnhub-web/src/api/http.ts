import axios, { type AxiosError, type InternalAxiosRequestConfig } from 'axios'
import type { ApiResult, TokenPair } from '@/types'
import { SessionExpiredError } from './session'

const ACCESS_KEY = 'learnhub_access_token'
const REFRESH_KEY = 'learnhub_refresh_token'
export const AUTH_SESSION_CLEARED_EVENT = 'learnhub:auth-session-cleared'

export const http = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || '/api',
  timeout: 10_000,
})

let refreshing: Promise<string> | null = null
let redirectingToLogin = false
let loggingOut = false
let sessionGeneration = 0

export function saveTokens(tokens: TokenPair) {
  redirectingToLogin = false
  loggingOut = false
  localStorage.setItem(ACCESS_KEY, tokens.accessToken)
  localStorage.setItem(REFRESH_KEY, tokens.refreshToken)
}

export function clearTokens() {
  sessionGeneration++
  localStorage.removeItem(ACCESS_KEY)
  localStorage.removeItem(REFRESH_KEY)
  window.dispatchEvent(new Event(AUTH_SESSION_CLEARED_EVENT))
}

http.interceptors.request.use((config) => {
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
  saveTokens(data.data)
  loggingOut = logoutPending
  return data.data.accessToken
}

export async function logoutSession() {
  loggingOut = true
  // 已开始的刷新可能轮换令牌；等它结束后撤销最新的一对凭据。
  try { await refreshing } catch { /* 刷新失败仍尝试撤销现有凭据。 */ }
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
  (response) => response,
  async (error: AxiosError) => {
    const original = error.config as (InternalAxiosRequestConfig & { _retry?: boolean }) | undefined
    if (error.response?.status !== 401 || !original || isAuthEndpoint(original.url)) {
      return Promise.reject(error)
    }
    // 退出失败交给调用方提示，退出期间的旧请求不能再创建新会话。
    if (original.url === '/auth/logout' || loggingOut) return Promise.reject(error)
    if (redirectingToLogin || original._retry) return Promise.reject(expireSession())
    original._retry = true
    try {
      const currentToken = localStorage.getItem(ACCESS_KEY)
      // 较晚返回的旧令牌请求复用刚刷新的令牌，避免再次消费旋转后的刷新令牌。
      let token = currentToken
      if (!currentToken || original.headers.Authorization === `Bearer ${currentToken}`) {
        refreshing ??= refreshAccessToken().finally(() => { refreshing = null })
        token = await refreshing
      }
      original.headers.Authorization = `Bearer ${token}`
      return http(original)
    } catch (refreshError) {
      const status = (refreshError as AxiosError).response?.status
      if (refreshError instanceof SessionExpiredError || status === 401 || status === 403 || status === 409) {
        return Promise.reject(expireSession())
      }
      // 网络中断或服务故障不等于会话失效，不清除仍可能有效的凭据。
      return Promise.reject(refreshError)
    }
  },
)
