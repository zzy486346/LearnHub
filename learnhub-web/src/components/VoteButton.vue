<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { likeApi } from '@/api'
import { useAuthStore } from '@/stores/auth'

const props = defineProps<{
  targetType: 'QUESTION' | 'ANSWER'
  targetId: string
  compact?: boolean
}>()
const auth = useAuthStore()
const route = useRoute()
const router = useRouter()
const liked = ref(false)
const count = ref(0)
const loading = ref(true)
const submitting = ref(false)
const error = ref('')
const announcement = ref('')
let generation = 0
const targetLabel = computed(() => props.targetType === 'QUESTION' ? '问题' : '回答')

async function load() {
  const current = ++generation
  loading.value = true
  error.value = ''
  try {
    const result = (await likeApi.status(props.targetType, props.targetId)).data.data
    if (current !== generation) return
    liked.value = result.liked
    count.value = result.count
  } catch {
    if (current === generation) error.value = '赞同状态加载失败，请重试'
  } finally {
    if (current === generation) loading.value = false
  }
}

async function toggle() {
  if (loading.value || submitting.value) return
  if (!auth.authenticated) {
    await router.push({ path: '/login', query: { redirect: route.fullPath } })
    return
  }
  if (error.value) {
    await load()
    return
  }
  const current = generation
  submitting.value = true
  try {
    const result = (await (liked.value
      ? likeApi.unlike(props.targetType, props.targetId)
      : likeApi.like(props.targetType, props.targetId))).data.data
    if (current !== generation) return
    liked.value = result.liked
    count.value = result.count
    announcement.value = `${result.liked ? '已赞同' : '已取消赞同'}${targetLabel.value}，当前 ${result.count} 个赞同`
  } catch {
    // 请求超时可能已变更服务端状态，重新读取后再允许下一次操作。
    if (current === generation) {
      await load()
      error.value = '赞同提交未确认，请重试刷新状态'
    }
  } finally {
    submitting.value = false
  }
}

watch(() => [props.targetType, props.targetId, auth.authenticated, auth.user?.id], () => {
  liked.value = false
  count.value = 0
  announcement.value = ''
  void load()
}, { immediate: true })
</script>

<template>
  <div class="vote-control" :class="{ 'vote-control--compact': compact }">
    <button type="button" class="vote-button" :class="{ 'is-liked': liked }"
      :aria-pressed="liked" :aria-busy="loading || submitting"
      :aria-label="error ? `重新加载${targetLabel}赞同状态` : `${liked ? '取消赞同' : '赞同'}${targetLabel}，${count} 个赞同`"
      :disabled="loading || submitting" @click="toggle">
      <svg viewBox="0 0 24 24" aria-hidden="true"><path d="m6 14 6-6 6 6" /></svg>
      <span>{{ loading || submitting ? '…' : count }}</span>
      <small>{{ error ? '重试' : submitting ? '提交中' : liked ? '已赞同' : '赞同' }}</small>
    </button>
    <span v-if="error" class="vote-error" role="alert">{{ error }}</span>
    <span class="vote-announcement" role="status" aria-live="polite">{{ announcement }}</span>
  </div>
</template>

<style scoped>
.vote-control { display: inline-flex; flex-direction: column; align-items: flex-start; gap: 8px; margin-top: 20px; }
.vote-control--compact { margin-top: 0; }
.vote-button { display: inline-flex; align-items: center; justify-content: center; gap: 8px; min-height: 44px; min-width: 65px; padding: 10px 14px; border: 1px solid var(--primary); border-radius: 12px; color: var(--primary); background: var(--primary-soft); cursor: pointer; font: inherit; font-weight: 700; }
.vote-button svg { width: 18px; height: 18px; fill: none; stroke: currentColor; stroke-width: 2; }
.vote-button small { font-size: 12px; font-weight: 600; white-space: nowrap; }
.vote-button:hover { box-shadow: var(--shadow-sm); }
.vote-button.is-liked { color: white; background: var(--primary); }
.vote-button:focus-visible { outline: 3px solid var(--primary); outline-offset: 3px; }
.vote-button:disabled { cursor: wait; opacity: .65; }
.vote-control--compact .vote-button { width: 100%; flex-direction: column; gap: 4px; padding: 10px 6px; }
.vote-control--compact .vote-button span { font-size: 22px; }
.vote-error { max-width: 240px; color: var(--muted); font-size: 12px; line-height: 1.5; }
.vote-announcement { position: absolute; width: 1px; height: 1px; overflow: hidden; clip-path: inset(50%); }
</style>
