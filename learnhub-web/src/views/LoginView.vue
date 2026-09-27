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
  <section class="auth-page">
    <div class="auth-copy"><p class="eyebrow">LEARN · ASK · GROW</p><h1>让职业成长<br />从一门好课开始</h1><p>系统课程、真实问答与学习权益，都在问课尚学。</p></div>
    <el-card class="auth-card"><h2>登录问课尚学</h2><p class="muted">继续你的学习旅程</p>
      <el-form label-position="top" @keyup.enter="submit">
        <el-form-item label="用户名"><el-input v-model="form.username" size="large" placeholder="请输入用户名" /></el-form-item>
        <el-form-item label="密码"><el-input v-model="form.password" type="password" show-password size="large" placeholder="请输入密码" /></el-form-item>
        <el-button type="primary" size="large" :loading="submitting" class="full" @click="submit">登录</el-button>
      </el-form><p class="center muted">还没有账号？<RouterLink to="/register">立即注册</RouterLink></p>
    </el-card>
  </section>
</template>
