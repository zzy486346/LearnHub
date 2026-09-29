<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { useAuthStore } from '@/stores/auth'
const auth = useAuthStore()
const fileInput = ref<HTMLInputElement>()
const previewUrl = ref<string>()
const uploading = ref(false)
const displayAvatar = computed(() => previewUrl.value || auth.user?.avatarUrl)

onMounted(() => auth.fetchMe())

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
        <p class="eyebrow">LEARNER PROFILE</p>
        <h1>{{ auth.user?.nickname || '学习者' }}</h1>
        <p>@{{ auth.user?.username }} · 坚持学习，持续进步</p>
        <button class="avatar-action" type="button" :disabled="uploading" @click="chooseAvatar">
          {{ uploading ? '正在上传头像…' : '更换头像' }}
        </button>
        <small>支持 PNG、JPG、WebP，最大 5 MB</small>
      </div>
    </div>
    <div class="stat-grid"><div><strong>3</strong><span>学习中</span></div><div><strong>12</strong><span>已完成课时</span></div><div><strong>5</strong><span>收藏课程</span></div><div><strong>8</strong><span>问答贡献</span></div></div>
    <div class="content-card"><h2>最近学习</h2><p class="muted">学习进度接口接入后，此处展示最近课程与续学入口。</p><el-progress :percentage="42" /></div>
  </section>
</template>
