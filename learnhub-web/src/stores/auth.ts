import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { authApi } from '@/api'
import { clearTokens, saveTokens } from '@/api/http'
import type { User } from '@/types'

export const useAuthStore = defineStore('auth', () => {
  const user = ref<User | null>(null)
  const accessToken = ref<string | null>(localStorage.getItem('learnhub_access_token'))
  const loading = ref(false)
  const authenticated = computed(() => Boolean(accessToken.value))
  const isAdmin = computed(() => user.value?.roles?.includes('ADMIN') ?? false)

  async function login(username: string, password: string) {
    const { data } = await authApi.login({ username, password })
    saveTokens(data.data)
    accessToken.value = data.data.accessToken
    try {
      await fetchMe()
    } catch (error) {
      clearTokens()
      accessToken.value = null
      throw error
    }
  }

  async function register(username: string, password: string, nickname: string) {
    await authApi.register({ username, password, nickname })
    await login(username, password)
  }

  async function fetchMe() {
    if (!authenticated.value) return
    loading.value = true
    try { user.value = (await authApi.me()).data.data } finally { loading.value = false }
  }

  async function logout() {
    try { await authApi.logout() } finally { clearTokens(); accessToken.value = null; user.value = null }
  }

  return { user, loading, authenticated, isAdmin, login, register, fetchMe, logout }
})
