import { computed, onScopeDispose, ref } from 'vue'
import { defineStore } from 'pinia'
import { authApi } from '@/api'
import { AUTH_SESSION_CLEARED_EVENT, clearTokens, saveTokens } from '@/api/http'
import type { User } from '@/types'

export const useAuthStore = defineStore('auth', () => {
  const user = ref<User | null>(null)
  const accessToken = ref<string | null>(localStorage.getItem('learnhub_access_token'))
  const loading = ref(false)
  const authenticated = computed(() => Boolean(accessToken.value && user.value))
  const isAdmin = computed(() => user.value?.roles?.includes('ADMIN') ?? false)

  function resetSession() {
    accessToken.value = null
    user.value = null
  }

  function handleStorageChange(event: StorageEvent) {
    if (event.key === 'learnhub_access_token' && !event.newValue) resetSession()
  }

  window.addEventListener(AUTH_SESSION_CLEARED_EVENT, resetSession)
  window.addEventListener('storage', handleStorageChange)
  onScopeDispose(() => {
    window.removeEventListener(AUTH_SESSION_CLEARED_EVENT, resetSession)
    window.removeEventListener('storage', handleStorageChange)
  })

  async function login(username: string, password: string) {
    const { data } = await authApi.login({ username, password })
    saveTokens(data.data)
    accessToken.value = data.data.accessToken
    try {
      await fetchMe()
    } catch (error) {
      clearTokens()
      resetSession()
      throw error
    }
  }

  async function register(username: string, password: string, nickname: string) {
    await authApi.register({ username, password, nickname })
    await login(username, password)
  }

  async function fetchMe() {
    if (!accessToken.value) return
    loading.value = true
    try {
      user.value = (await authApi.me()).data.data
    } catch (error) {
      if (!localStorage.getItem('learnhub_access_token')) resetSession()
      throw error
    } finally {
      loading.value = false
    }
  }

  async function logout() {
    try { await authApi.logout() } finally { clearTokens(); resetSession() }
  }

  return { user, loading, authenticated, isAdmin, login, register, fetchMe, logout }
})
