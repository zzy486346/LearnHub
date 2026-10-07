<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import type { AxiosError } from 'axios'
import { ElMessage } from 'element-plus'
import { authApi, orderApi, profileApi } from '@/api'
import { clearTokens } from '@/api/http'
import { useAuthStore } from '@/stores/auth'
import type { ApiResult, CourseOrder, ProfileOverview } from '@/types'
import MyCoupons from '@/components/MyCoupons.vue'

const auth = useAuthStore()
const router = useRouter()
const fileInput = ref<HTMLInputElement>()
const previewUrl = ref<string>()
const uploading = ref(false)
const loadingOverview = ref(true)
const overviewError = ref(false)
const overview = ref<ProfileOverview>()
const orders = ref<CourseOrder[]>([])
const loadingOrders = ref(true)
const ordersError = ref(false)
const payingOrderId = ref<string>()
const changingPassword = ref(false)
const passwordDialogVisible = ref(false)
const passwordError = ref('')
const passwordForm = reactive({ currentPassword: '', newPassword: '', confirmPassword: '' })
let passwordEntranceAnimation: Animation | undefined
const displayAvatar = computed(() => previewUrl.value || auth.user?.avatarUrl)
const isAdmin = computed(() => auth.user?.roles?.includes('ADMIN') === true)
const profileLabel = computed(() => isAdmin.value ? 'ADMIN PROFILE' : 'LEARNER PROFILE')
const profileDescription = computed(() => isAdmin.value
  ? '管理员账号 · 负责课程与内容维护'
  : '学习者账号 · 坚持学习，持续进步')
const stats = computed(() => [
  { label: '学习中', value: overview.value?.learningCourseCount ?? 0 },
  { label: '已完成课时', value: overview.value?.completedLessonCount ?? 0 },
  { label: '收藏课程', value: overview.value?.favoriteCourseCount ?? 0 },
  { label: '问答贡献', value: overview.value?.qaContributionCount ?? 0 },
])

onMounted(async () => {
  await Promise.allSettled([auth.fetchMe(), loadOverview(), loadOrders()])
})

async function loadOverview() {
  loadingOverview.value = true
  overviewError.value = false
  try {
    overview.value = (await profileApi.overview()).data.data
  } catch {
    overviewError.value = true
  } finally {
    loadingOverview.value = false
  }
}

async function loadOrders() {
  loadingOrders.value = true
  ordersError.value = false
  try {
    orders.value = (await orderApi.mine()).data.data || []
  } catch {
    ordersError.value = true
  } finally {
    loadingOrders.value = false
  }
}

async function payPendingOrder(order: CourseOrder) {
  payingOrderId.value = order.id
  try {
    const paid = (await orderApi.pay(order.id)).data.data
    const index = orders.value.findIndex((item) => item.id === paid.id)
    if (index >= 0) orders.value[index] = paid
    ElMessage.success('支付成功，课程已解锁')
  } catch {
    ElMessage.error('支付失败，请稍后重试')
  } finally {
    payingOrderId.value = undefined
  }
}

function formatOrderTime(value?: string) {
  if (!value) return '—'
  return new Intl.DateTimeFormat('zh-CN', {
    year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit',
  }).format(new Date(value))
}

async function changePassword() {
  passwordError.value = ''
  if (!passwordForm.currentPassword) {
    passwordError.value = '请输入当前密码'
    return
  }
  if (passwordForm.newPassword.length < 8) {
    passwordError.value = '新密码至少需要 8 个字符'
    return
  }
  if (passwordForm.newPassword === passwordForm.currentPassword) {
    passwordError.value = '新密码不能与当前密码相同'
    return
  }
  if (passwordForm.newPassword !== passwordForm.confirmPassword) {
    passwordError.value = '两次输入的新密码不一致'
    return
  }
  changingPassword.value = true
  try {
    await authApi.changePassword({
      currentPassword: passwordForm.currentPassword,
      newPassword: passwordForm.newPassword,
    })
    Object.assign(passwordForm, { currentPassword: '', newPassword: '', confirmPassword: '' })
    passwordDialogVisible.value = false
    ElMessage.success('密码已修改，请使用新密码重新登录')
    clearTokens()
    await router.replace({ path: '/login', query: { redirect: '/profile' } })
  } catch (error) {
    const response = (error as AxiosError<ApiResult<unknown>>).response?.data
    passwordError.value = response?.message || '密码修改失败，请稍后重试'
  } finally {
    changingPassword.value = false
  }
}

function openPasswordDialog() {
  passwordError.value = ''
  Object.assign(passwordForm, { currentPassword: '', newPassword: '', confirmPassword: '' })
  passwordDialogVisible.value = true
}

function handlePasswordDialogClosed() {
  if (!changingPassword.value) {
    passwordError.value = ''
    Object.assign(passwordForm, { currentPassword: '', newPassword: '', confirmPassword: '' })
  }
}

async function playPasswordEntrance() {
  await nextTick()
  const dialog = document.querySelector<HTMLElement>('.password-dialog')
  if (!dialog) return
  passwordEntranceAnimation?.cancel()
  const animation = dialog.animate(
    [
      { opacity: 0, filter: 'blur(5px)', transform: 'translate3d(180px, -120px, 0) scale(.88)' },
      { opacity: 1, filter: 'blur(0)', transform: 'translate3d(-8px, 5px, 0) scale(1.012)', offset: .66 },
      { opacity: 1, filter: 'blur(0)', transform: 'translate3d(0, 0, 0) scale(1)' },
    ],
    { duration: 780, easing: 'cubic-bezier(.16, 1, .3, 1)', fill: 'both' },
  )
  passwordEntranceAnimation = animation
  try { await animation.finished } catch { /* Reopening cancels the previous animation. */ }
  if (passwordEntranceAnimation === animation) {
    animation.cancel()
    passwordEntranceAnimation = undefined
  }
  document.querySelector<HTMLInputElement>('.password-dialog input')?.focus()
}

function chooseAvatar() {
  fileInput.value?.click()
}

async function handleAvatarChange(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (!file) return
  if (!file.type.startsWith('image/') || file.type === 'image/svg+xml') {
    ElMessage.warning('请选择 PNG、JPG 或 WebP 图片')
    return
  }
  if (file.size > 5 * 1024 * 1024) {
    ElMessage.warning('头像大小不能超过 5 MB')
    return
  }

  if (previewUrl.value) URL.revokeObjectURL(previewUrl.value)
  previewUrl.value = URL.createObjectURL(file)
  uploading.value = true
  try {
    await auth.updateAvatar(file)
    ElMessage.success('头像已更新')
  } catch {
    ElMessage.error('头像上传失败，请稍后重试')
  } finally {
    uploading.value = false
    if (previewUrl.value) URL.revokeObjectURL(previewUrl.value)
    previewUrl.value = undefined
  }
}

onBeforeUnmount(() => {
  passwordEntranceAnimation?.cancel()
  if (previewUrl.value) URL.revokeObjectURL(previewUrl.value)
})
</script>

<template>
  <section>
    <div class="profile-banner">
      <div class="profile-avatar-wrap" :aria-busy="uploading">
        <el-avatar :size="92" :src="displayAvatar" fit="cover">
          {{ auth.user?.nickname?.slice(0, 1) || '学' }}
        </el-avatar>
        <button class="avatar-edit" type="button" :disabled="uploading" aria-label="更换头像" @click="chooseAvatar">
          <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 20h9M16.5 3.5a2.1 2.1 0 0 1 3 3L8 18l-4 1 1-4Z" /></svg>
        </button>
        <span v-if="uploading" class="avatar-uploading">上传中</span>
        <input ref="fileInput" class="avatar-file-input" type="file" accept="image/png,image/jpeg,image/webp" @change="handleAvatarChange" />
      </div>
      <div class="profile-identity">
        <p class="eyebrow">{{ profileLabel }}</p>
        <h1>{{ auth.user?.nickname || '学习者' }}</h1>
        <p>@{{ auth.user?.username }} · {{ profileDescription }}</p>
        <div class="profile-banner-actions">
          <button class="avatar-action" type="button" :disabled="uploading" @click="chooseAvatar">
            {{ uploading ? '正在上传头像…' : '更换头像' }}
          </button>
          <button class="avatar-action password-action" type="button" @click="openPasswordDialog">
            修改密码
          </button>
          <small>支持 PNG、JPG、WebP，最大 5 MB</small>
        </div>
      </div>
    </div>

    <div class="stat-grid" :aria-busy="loadingOverview">
      <div v-for="item in stats" :key="item.label">
        <strong>{{ loadingOverview ? '—' : item.value }}</strong>
        <span>{{ item.label }}</span>
      </div>
    </div>

    <div class="content-card profile-learning-card" :aria-busy="loadingOverview">
      <div class="profile-section-heading">
        <div><h2>最近学习</h2><p>从上次停下的位置继续学习。</p></div>
      </div>
      <div v-if="loadingOverview" class="profile-state" role="status" aria-live="polite"><span class="profile-loading-dot" aria-hidden="true"></span>正在加载学习记录…</div>
      <div v-else-if="overviewError" class="profile-state profile-state-error" role="alert">
        <span>学习数据暂时加载失败。</span>
        <button type="button" @click="loadOverview">重新加载</button>
      </div>
      <div v-else-if="overview?.recentLearning" class="recent-learning">
        <div>
          <span class="recent-course">{{ overview.recentLearning.courseTitle }}</span>
          <h3>{{ overview.recentLearning.lessonTitle }}</h3>
          <p>{{ overview.recentLearning.completed ? '该课时已完成' : `已学习 ${overview.recentLearning.percentage}%` }}</p>
        </div>
        <RouterLink class="recent-learning-action" :to="`/courses/${overview.recentLearning.courseId}/lessons/${overview.recentLearning.lessonId}`">
          {{ overview.recentLearning.completed ? '再次学习' : '继续学习' }}
        </RouterLink>
        <el-progress class="recent-learning-progress" :percentage="overview.recentLearning.percentage" :stroke-width="8" />
      </div>
      <div v-else class="profile-state profile-empty-state">
        <div><strong>还没有学习记录</strong><span>选择一门感兴趣的课程，开始你的第一次学习。</span></div>
        <RouterLink to="/courses">浏览课程</RouterLink>
      </div>
    </div>

    <MyCoupons />

    <div class="profile-management-grid profile-orders-only">
      <div class="content-card profile-orders-card" :aria-busy="loadingOrders">
        <div class="profile-section-heading">
          <div><h2>订单管理</h2><p>查看课程订单和支付状态。</p></div>
          <button v-if="ordersError" type="button" class="profile-text-action" @click="loadOrders">重新加载</button>
        </div>
        <div v-if="loadingOrders" class="profile-state" role="status" aria-live="polite"><span class="profile-loading-dot" aria-hidden="true"></span>正在加载订单…</div>
        <div v-else-if="ordersError" class="profile-state profile-state-error" role="alert">订单暂时加载失败。</div>
        <div v-else-if="orders.length" class="profile-order-list">
          <article v-for="order in orders" :key="order.id" class="profile-order-item">
            <div class="profile-order-main">
              <div class="profile-order-title"><RouterLink :to="`/courses/${order.courseId}`">{{ order.courseTitle }}</RouterLink><el-tag :type="order.status === 'PAID' ? 'success' : 'warning'" size="small">{{ order.status === 'PAID' ? '已支付' : '待支付' }}</el-tag></div>
              <p>订单号 {{ order.orderNo }}</p>
              <small>{{ formatOrderTime(order.createdAt) }}</small>
            </div>
            <div class="profile-order-amount"><span>实付</span><strong>¥{{ Number(order.paidAmount ?? 0).toFixed(2) }}</strong><small>原价 ¥{{ Number(order.originalAmount || 0).toFixed(2) }}</small></div>
            <el-button v-if="order.status === 'PENDING'" type="primary" :loading="payingOrderId === order.id" @click="payPendingOrder(order)">零元支付</el-button>
            <RouterLink v-else class="profile-order-action" :to="`/courses/${order.courseId}`">进入课程</RouterLink>
          </article>
        </div>
        <div v-else class="profile-state profile-empty-state">
          <div><strong>还没有课程订单</strong><span>在课程详情页点击购买后，订单会显示在这里。</span></div>
          <RouterLink to="/courses">浏览课程</RouterLink>
        </div>
      </div>

    </div>

    <el-dialog
      v-model="passwordDialogVisible"
      class="login-dialog password-dialog"
      modal-class="login-dialog-overlay"
      transition="login-corner-pop"
      width="min(440px, calc(100vw - 32px))"
      append-to-body
      destroy-on-close
      :show-close="false"
      :close-on-click-modal="!changingPassword"
      :close-on-press-escape="!changingPassword"
      aria-labelledby="password-dialog-title"
      @open="playPasswordEntrance"
      @closed="handlePasswordDialogClosed"
    >
      <div class="login-dialog-aurora" aria-hidden="true"><span></span><span></span><span></span></div>
      <div class="login-dialog-head">
        <div class="login-dialog-brand"><span><svg viewBox="0 0 24 24" aria-hidden="true"><path d="M7 10V8a5 5 0 0 1 10 0v2m-9 0h8a2 2 0 0 1 2 2v7H6v-7a2 2 0 0 1 2-2Z" /></svg></span><div><small>ACCOUNT SECURITY</small><strong id="password-dialog-title">修改密码</strong></div></div>
        <button type="button" class="dialog-close" aria-label="关闭修改密码窗口" :disabled="changingPassword" @click="passwordDialogVisible = false">
          <svg viewBox="0 0 24 24" aria-hidden="true"><path d="m6 6 12 12M18 6 6 18" /></svg>
        </button>
      </div>
      <p class="login-dialog-copy">输入当前密码并设置新密码，保存后需要重新登录。</p>
      <el-form label-position="top" @submit.prevent="changePassword">
        <el-form-item label="当前密码"><el-input v-model="passwordForm.currentPassword" type="password" show-password size="large" autocomplete="current-password" placeholder="请输入当前密码" /></el-form-item>
        <el-form-item label="新密码"><el-input v-model="passwordForm.newPassword" type="password" show-password size="large" autocomplete="new-password" placeholder="至少 8 个字符" /></el-form-item>
        <el-form-item label="确认新密码"><el-input v-model="passwordForm.confirmPassword" type="password" show-password size="large" autocomplete="new-password" placeholder="请再次输入新密码" /></el-form-item>
        <p v-if="passwordError" class="password-form-error" role="alert">{{ passwordError }}</p>
        <el-button type="primary" native-type="submit" size="large" :loading="changingPassword" class="full dialog-submit">保存新密码</el-button>
      </el-form>
    </el-dialog>
  </section>
</template>
