import axios, { type AxiosError, type InternalAxiosRequestConfig } from 'axios'
import type { ApiResult, TokenPair } from '@/types'

const ACCESS_KEY = 'learnhub_access_token'
const REFRESH_KEY = 'learnhub_refresh_token'
export const AUTH_SESSION_CLEARED_EVENT = 'learnhub:auth-session-cleared'

export const http = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || '/api',
  timeout: 10_000,
})

let refreshing: Promise<string> | null = null

export function saveTokens(tokens: TokenPair) {
  localStorage.setItem(ACCESS_KEY, tokens.accessToken)
  localStorage.setItem(REFRESH_KEY, tokens.refreshToken)
}

export function clearTokens() {
  localStorage.removeItem(ACCESS_KEY)
  localStorage.removeItem(REFRESH_KEY)
  window.dispatchEvent(new Event(AUTH_SESSION_CLEARED_EVENT))
}

http.interceptors.request.use((config) => {
  const token = localStorage.getItem(ACCESS_KEY)
  if (token) config.headers.Authorization = `Bearer ${token}`
  return config
})

async function refreshAccessToken() {
  const refreshToken = localStorage.getItem(REFRESH_KEY)
  if (!refreshToken) throw new Error('缺少刷新令牌')
  const { data } = await axios.post<ApiResult<TokenPair>>(
    `${http.defaults.baseURL}/auth/refresh`,
    { refreshToken },
  )
  saveTokens(data.data)
  return data.data.accessToken
}

http.interceptors.response.use(
  (response) => response,
  async (error: AxiosError) => {
    const original = error.config as (InternalAxiosRequestConfig & { _retry?: boolean }) | undefined
    if (error.response?.status !== 401 || !original || original._retry || original.url?.includes('/auth/refresh')) {
      return Promise.reject(error)
    }
    original._retry = true
    try {
      refreshing ??= refreshAccessToken().finally(() => { refreshing = null })
      const token = await refreshing
      original.headers.Authorization = `Bearer ${token}`
      return http(original)
    } catch (refreshError) {
      clearTokens()
      if (location.pathname !== '/login') location.assign(`/login?redirect=${encodeURIComponent(location.pathname)}`)
      return Promise.reject(refreshError)
    }
  },
)
