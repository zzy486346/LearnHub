<script setup lang="ts">
import { onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()
const router = useRouter()
onMounted(() => auth.fetchMe().catch(() => undefined))

async function logout() {
  await auth.logout()
  router.push('/login')
}
</script>

<template>
  <header class="topbar">
    <RouterLink class="brand" to="/courses"><span>问</span>问课尚学</RouterLink>
    <nav>
      <RouterLink to="/courses">课程</RouterLink>
      <RouterLink to="/questions">问答</RouterLink>
      <RouterLink to="/coupons">优惠券</RouterLink>
      <RouterLink v-if="auth.authenticated" to="/profile">个人中心</RouterLink>
      <RouterLink v-if="auth.isAdmin" to="/admin">管理</RouterLink>
    </nav>
    <div class="header-actions">
      <template v-if="auth.authenticated">
        <span>{{ auth.user?.nickname || '学习者' }}</span>
        <el-button text @click="logout">退出</el-button>
      </template>
      <template v-else>
        <RouterLink to="/login">登录</RouterLink>
        <el-button type="primary" round @click="router.push('/register')">免费注册</el-button>
      </template>
    </div>
  </header>
</template>
