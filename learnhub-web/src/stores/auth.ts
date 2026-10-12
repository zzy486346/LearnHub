import { computed, onScopeDispose, ref } from 'vue'
import { defineStore } from 'pinia'
import { authApi } from '@/api'
import {
  AUTH_SESSION_CLEARED_EVENT,
  AUTH_SESSION_REPLACED_EVENT,
  clearTokens,
  getSessionGeneration,
  isCurrentSession,
  replaceSessionFromStorage,
  saveTokens,
} from '@/api/http'
import { SessionExpiredError } from '@/api/session'
import type { User } from '@/types'

export const useAuthStore = defineStore('auth', () => {
  const user = ref<User | null>(null)
  const accessToken = ref<string | null>(localStorage.getItem('learnhub_access_token'))
  const loading = ref(false)
  let loginAttempt = 0
  const authenticated = computed(() => Boolean(accessToken.value && user.value))
  const isAdmin = computed(() => user.value?.roles?.includes('ADMIN') ?? false)

  function resetSession() {
    accessToken.value = null
    user.value = null
  }

  function handleStorageChange(event: StorageEvent) {
    if (event.key !== 'learnhub_access_token') return
    if (!event.newValue) {
      clearTokens()
      return
    }
    replaceSessionFromStorage()
    const generation = getSessionGeneration()
    accessToken.value = event.newValue
    user.value = null
    void fetchMe(generation).catch(() => undefined)
  }

  window.addEventListener(AUTH_SESSION_CLEARED_EVENT, resetSession)
  window.addEventListener(AUTH_SESSION_REPLACED_EVENT, resetSession)
  window.addEventListener('storage', handleStorageChange)
  onScopeDispose(() => {
    window.removeEventListener(AUTH_SESSION_CLEARED_EVENT, resetSession)
    window.removeEventListener(AUTH_SESSION_REPLACED_EVENT, resetSession)
    window.removeEventListener('storage', handleStorageChange)
  })

  async function login(username: string, password: string) {
    const attempt = ++loginAttempt
    const startingGeneration = getSessionGeneration()
    const { data } = await authApi.login({ username, password })
    if (attempt !== loginAttempt || !isCurrentSession(startingGeneration)) throw new SessionExpiredError()
    saveTokens(data.data)
    const generation = getSessionGeneration()
    accessToken.value = data.data.accessToken
    try {
      await fetchMe(generation)
    } catch (error) {
      if (isCurrentSession(generation)) {
        clearTokens()
        resetSession()
      }
      throw error
    }
  }

  async function register(username: string, password: string, nickname: string) {
    await authApi.register({ username, password, nickname })
    await login(username, password)
  }

  async function fetchMe(generation = getSessionGeneration()) {
    if (!accessToken.value) return
    loading.value = true
    try {
      const currentUser = (await authApi.me()).data.data
      if (isCurrentSession(generation)) user.value = currentUser
    } catch (error) {
      if (!localStorage.getItem('learnhub_access_token')) resetSession()
      throw error
    } finally {
      if (isCurrentSession(generation)) loading.value = false
    }
  }

  async function updateAvatar(file: File) {
    user.value = (await authApi.updateAvatar(file)).data.data
  }

  async function logout() {
    const generation = getSessionGeneration()
    try { await authApi.logout() } finally {
      if (isCurrentSession(generation)) {
        clearTokens()
        resetSession()
      }
    }
  }

  return { user, loading, authenticated, isAdmin, login, register, fetchMe, updateAvatar, logout }
})
