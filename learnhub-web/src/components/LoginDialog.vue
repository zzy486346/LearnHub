<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useAuthStore } from '@/stores/auth'

const visible = defineModel<boolean>({ required: true })
const form = reactive({ username: '', password: '' })
const submitting = ref(false)
const auth = useAuthStore()
const route = useRoute()
const router = useRouter()

async function submit() {
  if (!form.username || !form.password) {
    ElMessage.warning('请输入用户名和密码')
    return
  }
  submitting.value = true
  try {
    await auth.login(form.username, form.password)
    ElMessage.success(`欢迎回来，${auth.user?.nickname || auth.user?.username || '学习者'}`)
    visible.value = false
    Object.assign(form, { username: '', password: '' })
    if (route.path === '/login') {
      await router.replace(String(route.query.redirect || '/courses'))
    }
  } catch {
    ElMessage.error('登录失败，请检查账号或服务状态')
  } finally {
    submitting.value = false
  }
}

function goRegister() {
  visible.value = false
  router.push('/register')
}

function resetForm() {
  if (!submitting.value) Object.assign(form, { username: '', password: '' })
}
</script>

<template>
  <el-dialog
    v-model="visible"
    class="login-dialog"
    width="min(420px, calc(100vw - 32px))"
    align-center
    append-to-body
    destroy-on-close
    :show-close="false"
    aria-label="登录问课尚学"
    @closed="resetForm"
  >
    <div class="login-dialog-head">
      <div class="login-dialog-brand"><span>问</span><strong>欢迎回来</strong></div>
      <button type="button" class="dialog-close" aria-label="关闭登录窗口" @click="visible = false">
        <svg viewBox="0 0 24 24" aria-hidden="true"><path d="m6 6 12 12M18 6 6 18" /></svg>
      </button>
    </div>
    <p class="login-dialog-copy">登录后继续你的学习旅程</p>
    <el-form label-position="top" @keyup.enter="submit">
      <el-form-item label="用户名">
        <el-input v-model="form.username" size="large" autocomplete="username" placeholder="请输入用户名" autofocus />
      </el-form-item>
      <el-form-item label="密码">
        <el-input v-model="form.password" type="password" show-password size="large" autocomplete="current-password" placeholder="请输入密码" />
      </el-form-item>
      <el-button type="primary" size="large" :loading="submitting" class="full dialog-submit" @click="submit">登录</el-button>
    </el-form>
    <p class="login-dialog-footer">还没有账号？<button type="button" @click="goRegister">免费注册</button></p>
  </el-dialog>
</template>
