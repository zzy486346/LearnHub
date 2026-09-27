<script setup lang="ts">
import { onMounted } from 'vue'
import { useAuthStore } from '@/stores/auth'
const auth = useAuthStore()
onMounted(() => auth.fetchMe())
</script>

<template><section><div class="profile-banner"><el-avatar :size="76">{{ auth.user?.nickname?.slice(0, 1) || '学' }}</el-avatar><div><h1>{{ auth.user?.nickname || '学习者' }}</h1><p>@{{ auth.user?.username }} · 坚持学习，持续进步</p></div></div><div class="stat-grid"><div><strong>3</strong><span>学习中</span></div><div><strong>12</strong><span>已完成课时</span></div><div><strong>5</strong><span>收藏课程</span></div><div><strong>8</strong><span>问答贡献</span></div></div><div class="content-card"><h2>最近学习</h2><p class="muted">学习进度接口接入后，此处展示最近课程与续学入口。</p><el-progress :percentage="42" /></div></section></template>
