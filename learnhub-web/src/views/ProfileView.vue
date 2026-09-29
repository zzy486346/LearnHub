<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { profileApi } from '@/api'
import { useAuthStore } from '@/stores/auth'
import type { ProfileOverview } from '@/types'

const auth = useAuthStore()
const fileInput = ref<HTMLInputElement>()
const previewUrl = ref<string>()
const uploading = ref(false)
const loadingOverview = ref(true)
const overviewError = ref(false)
const overview = ref<ProfileOverview>()
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
  await Promise.allSettled([auth.fetchMe(), loadOverview()])
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
        <button class="avatar-action" type="button" :disabled="uploading" @click="chooseAvatar">
          {{ uploading ? '正在上传头像…' : '更换头像' }}
        </button>
        <small>支持 PNG、JPG、WebP，最大 5 MB</small>
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
  </section>
</template>
