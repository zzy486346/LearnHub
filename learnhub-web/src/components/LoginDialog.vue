<script setup lang="ts">
import { nextTick, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useAuthStore } from '@/stores/auth'

const visible = defineModel<boolean>({ required: true })
const emit = defineEmits<{ closed: [] }>()
const form = reactive({ username: '', password: '' })
const submitting = ref(false)
const auth = useAuthStore()
const route = useRoute()
const router = useRouter()
let entranceAnimation: Animation | undefined

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

function handleClosed() {
  resetForm()
  emit('closed')
}

async function focusUsername() {
  await nextTick()
  document.querySelector<HTMLInputElement>('.login-dialog input')?.focus()
}

async function playEntrance() {
  await nextTick()
  const dialog = document.querySelector<HTMLElement>('.login-dialog')
  if (!dialog) return

  entranceAnimation?.cancel()
  const animation = dialog.animate(
    [
      { opacity: 0, filter: 'blur(5px)', transform: 'translate3d(180px, -120px, 0) scale(.88)' },
      { opacity: 1, filter: 'blur(0)', transform: 'translate3d(-8px, 5px, 0) scale(1.012)', offset: .58 },
      { opacity: 1, filter: 'blur(0)', transform: 'translate3d(0, 0, 0) scale(1)' },
    ],
    { duration: 560, easing: 'cubic-bezier(.16, 1, .3, 1)', fill: 'both' },
  )
  entranceAnimation = animation

  try {
    await animation.finished
  } catch {
    // A repeated open cancels the previous animation intentionally.
  }
  if (entranceAnimation === animation) {
    animation.cancel()
    entranceAnimation = undefined
  }
}
</script>

<template>
  <el-dialog
    v-model="visible"
    class="login-dialog"
    modal-class="login-dialog-overlay"
    transition="login-corner-pop"
    width="min(440px, calc(100vw - 32px))"
    append-to-body
    destroy-on-close
    :show-close="false"
    :close-on-click-modal="!submitting"
    :close-on-press-escape="!submitting"
    aria-labelledby="login-dialog-title"
    @open="playEntrance"
    @opened="focusUsername"
    @closed="handleClosed"
  >
    <div class="login-dialog-aurora" aria-hidden="true"><span></span><span></span><span></span></div>
    <div class="login-dialog-head">
      <div class="login-dialog-brand"><span>问</span><div><small>LEARNHUB ACCOUNT</small><strong id="login-dialog-title">欢迎回来</strong></div></div>
      <button type="button" class="dialog-close" aria-label="关闭登录窗口" @click="visible = false">
        <svg viewBox="0 0 24 24" aria-hidden="true"><path d="m6 6 12 12M18 6 6 18" /></svg>
      </button>
    </div>
    <p class="login-dialog-copy">连接课程、问答与学习进度，继续你的成长旅程。</p>
    <el-form label-position="top" @submit.prevent="submit">
      <el-form-item label="用户名">
        <el-input v-model="form.username" size="large" autocomplete="username" placeholder="请输入用户名">
          <template #prefix><svg class="input-icon" viewBox="0 0 24 24" aria-hidden="true"><path d="M20 21a8 8 0 0 0-16 0M12 13a5 5 0 1 0 0-10 5 5 0 0 0 0 10Z" /></svg></template>
        </el-input>
      </el-form-item>
      <el-form-item label="密码">
        <el-input v-model="form.password" type="password" show-password size="large" autocomplete="current-password" placeholder="请输入密码">
          <template #prefix><svg class="input-icon" viewBox="0 0 24 24" aria-hidden="true"><rect x="4" y="10" width="16" height="11" rx="2"/><path d="M8 10V7a4 4 0 0 1 8 0v3" /></svg></template>
        </el-input>
      </el-form-item>
      <el-button type="primary" native-type="submit" size="large" :loading="submitting" class="full dialog-submit"><span>登录并继续学习</span></el-button>
    </el-form>
    <p class="login-dialog-footer">还没有账号？<button type="button" @click="goRegister">免费注册</button></p>
  </el-dialog>
</template>
