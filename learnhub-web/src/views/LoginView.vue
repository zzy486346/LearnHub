<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useAuthStore } from '@/stores/auth'

const form = reactive({ username: '', password: '' })
const submitting = ref(false)
const auth = useAuthStore()
const route = useRoute()
const router = useRouter()

async function submit() {
  if (!form.username || !form.password) return ElMessage.warning('请输入用户名和密码')
  submitting.value = true
  try {
    await auth.login(form.username, form.password)
    ElMessage.success('欢迎回来')
    router.push(String(route.query.redirect || '/courses'))
  } catch { ElMessage.error('登录失败，请检查账号或服务状态') } finally { submitting.value = false }
}
</script>

<template>
  <section class="login-page">
    <div class="login-page-orb login-page-orb-one" aria-hidden="true"></div><div class="login-page-orb login-page-orb-two" aria-hidden="true"></div>
    <el-card class="auth-card login-page-card"><div class="login-page-brand"><span>问</span><small>LEARNHUB ACCOUNT</small></div><h2>登录问课尚学</h2><p class="muted">连接课程、问答与学习进度，继续你的学习旅程。</p>
      <el-form label-position="top" @submit.prevent="submit">
        <el-form-item label="用户名"><el-input v-model="form.username" size="large" autocomplete="username" placeholder="请输入用户名" /></el-form-item>
        <el-form-item label="密码"><el-input v-model="form.password" type="password" show-password size="large" autocomplete="current-password" placeholder="请输入密码" /></el-form-item>
        <el-button type="primary" native-type="submit" size="large" :loading="submitting" class="full dialog-submit">登录并继续学习</el-button>
      </el-form><p class="center muted">还没有账号？<RouterLink to="/register">立即注册</RouterLink></p>
    </el-card>
  </section>
</template>
