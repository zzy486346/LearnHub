<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useAuthStore } from '@/stores/auth'

const form = reactive({ username: '', nickname: '', password: '', confirm: '' })
const submitting = ref(false)
const auth = useAuthStore()
const router = useRouter()
async function submit() {
  if (!form.username || !form.nickname || form.password.length < 8) return ElMessage.warning('请完整填写，密码至少 8 位')
  if (form.password !== form.confirm) return ElMessage.warning('两次输入的密码不一致')
  submitting.value = true
  try { await auth.register(form.username, form.password, form.nickname); ElMessage.success('注册成功'); router.push('/courses') }
  catch { ElMessage.error('注册失败，请稍后重试') } finally { submitting.value = false }
}
</script>

<template><section class="narrow-page"><el-card class="form-card"><p class="eyebrow">JOIN LEARNHUB</p><h1>创建学习账号</h1>
  <el-form label-position="top"><el-form-item label="用户名"><el-input v-model="form.username" /></el-form-item><el-form-item label="昵称"><el-input v-model="form.nickname" /></el-form-item><el-form-item label="密码"><el-input v-model="form.password" type="password" show-password /></el-form-item><el-form-item label="确认密码"><el-input v-model="form.confirm" type="password" show-password /></el-form-item><el-button type="primary" size="large" class="full" :loading="submitting" @click="submit">注册并开始学习</el-button></el-form>
  <p class="center muted">已有账号？<RouterLink to="/login">返回登录</RouterLink></p></el-card></section></template>
