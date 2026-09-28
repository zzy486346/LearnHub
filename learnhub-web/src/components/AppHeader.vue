<script setup lang="ts">
import { nextTick, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import LoginDialog from '@/components/LoginDialog.vue'

const auth = useAuthStore()
const router = useRouter()
const mobileOpen = ref(false)
const loginDialogOpen = ref(false)
let lastLoginTrigger: HTMLElement | null = null
onMounted(() => auth.fetchMe().catch(() => undefined))

async function logout() {
  await auth.logout()
  mobileOpen.value = false
  router.push('/courses')
}

function closeMenu() {
  mobileOpen.value = false
}

function openLogin(event?: MouseEvent) {
  mobileOpen.value = false
  lastLoginTrigger = event?.currentTarget as HTMLElement | null
  loginDialogOpen.value = true
}

async function restoreLoginFocus() {
  await nextTick()
  lastLoginTrigger?.focus()
}
</script>

<template>
  <header class="topbar" :class="{ 'menu-open': mobileOpen }">
    <div class="topbar-inner">
      <RouterLink class="brand" to="/courses" aria-label="问课尚学首页" @click="closeMenu"><span>问</span>问课尚学</RouterLink>
      <nav class="desktop-nav" aria-label="主导航">
        <RouterLink to="/courses">课程</RouterLink>
        <RouterLink to="/questions">问答</RouterLink>
        <RouterLink to="/coupons">学习权益</RouterLink>
        <RouterLink v-if="auth.authenticated" to="/profile">个人中心</RouterLink>
        <RouterLink v-if="auth.isAdmin" to="/admin">管理后台</RouterLink>
      </nav>
      <div class="header-actions">
      <template v-if="auth.authenticated">
          <RouterLink class="user-entry" to="/profile" aria-label="进入个人中心">
            <span class="user-avatar">{{ auth.user?.nickname?.slice(0, 1) || '学' }}</span>
            <span>{{ auth.user?.nickname || auth.user?.username || '学习者' }}</span>
          </RouterLink>
          <el-button text @click="logout">退出</el-button>
      </template>
      <template v-else>
          <button type="button" class="login-link" aria-haspopup="dialog" :aria-expanded="loginDialogOpen" @click="openLogin($event)">
            <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M15 3h4a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2h-4M10 17l5-5-5-5M15 12H3" /></svg>
            <span>登录</span>
          </button>
          <el-button class="register-link" round @click="router.push('/register')">免费注册</el-button>
      </template>
      </div>
      <button class="menu-toggle" type="button" :aria-expanded="mobileOpen" aria-controls="mobile-navigation" aria-label="切换导航菜单" @click="mobileOpen = !mobileOpen">
        <svg viewBox="0 0 24 24" aria-hidden="true"><path v-if="!mobileOpen" d="M4 7h16M4 12h16M4 17h16"/><path v-else d="m6 6 12 12M18 6 6 18"/></svg>
      </button>
    </div>
    <nav id="mobile-navigation" class="mobile-nav" aria-label="移动端导航">
      <RouterLink to="/courses" @click="closeMenu">课程</RouterLink>
      <RouterLink to="/questions" @click="closeMenu">问答</RouterLink>
      <RouterLink to="/coupons" @click="closeMenu">学习权益</RouterLink>
      <RouterLink v-if="auth.authenticated" to="/profile" @click="closeMenu">个人中心</RouterLink>
      <RouterLink v-if="auth.isAdmin" to="/admin" @click="closeMenu">管理后台</RouterLink>
      <button v-if="!auth.authenticated" type="button" :aria-expanded="loginDialogOpen" aria-haspopup="dialog" @click="openLogin($event)">登录</button>
    </nav>
    <LoginDialog v-model="loginDialogOpen" @closed="restoreLoginFocus" />
  </header>
</template>
