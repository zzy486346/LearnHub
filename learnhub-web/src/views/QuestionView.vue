<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { questionApi } from '@/api'
import type { Question } from '@/types'

const questions = ref<Question[]>([])
const loading = ref(false)
const loadError = ref('')
const dialog = ref(false)
const submitting = ref(false)
const page = ref(1)
const total = ref(0)
const pageSize = 10
const form = reactive({ title: '', content: '' })

function formatDate(value: string) {
  return value ? new Date(value).toLocaleString('zh-CN', { dateStyle: 'medium', timeStyle: 'short' }) : ''
}

async function load() {
  loading.value = true
  loadError.value = ''
  try {
    const result = (await questionApi.list({ page: page.value, size: pageSize })).data.data
    questions.value = result.records
    total.value = result.total
  } catch (error) {
    questions.value = []
    loadError.value = (error as { response?: { data?: { message?: string } } }).response?.data?.message || '问题加载失败，请稍后重试'
  } finally {
    loading.value = false
  }
}

async function submit() {
  if (!form.title.trim() || !form.content.trim()) return ElMessage.warning('请填写问题标题与描述')
  submitting.value = true
  try {
    await questionApi.create({ title: form.title.trim(), content: form.content.trim() })
    dialog.value = false
    Object.assign(form, { title: '', content: '' })
    page.value = 1
    await load()
    ElMessage.success('问题已发布')
  } catch (error) {
    const response = (error as { response?: { status?: number; data?: { message?: string } } }).response
    ElMessage.error(response?.status === 401 ? '请登录后发布问题' : response?.data?.message || '发布失败，请稍后重试')
  } finally {
    submitting.value = false
  }
}

onMounted(load)
</script>

<template>
  <section>
    <div class="page-title">
      <div><p class="eyebrow">COMMUNITY Q&A</p><h1>问答社区</h1><p>提出真实问题，分享可靠答案。</p></div>
      <el-button type="primary" size="large" @click="dialog = true">我要提问</el-button>
    </div>
    <div class="question-layout">
      <div v-loading="loading">
        <div v-if="loadError" class="content-card state-panel" role="alert">
          <h3>暂时无法加载问题</h3><p>{{ loadError }}</p><el-button @click="load">重新加载</el-button>
        </div>
        <el-empty v-else-if="!loading && !questions.length" description="还没有问题，来发布第一个问题吧" />
        <RouterLink v-for="item in questions" :key="item.id" :to="`/questions/${item.id}`" class="question-card question-link">
          <div class="vote">{{ item.likeCount || 0 }}<small>赞同</small></div>
          <div>
            <h3>{{ item.title }}</h3><p>{{ item.content }}</p>
            <span class="muted">{{ item.nickname }} · {{ formatDate(item.createdAt) }} · {{ item.answerCount }} 个回答</span>
          </div>
        </RouterLink>
        <el-pagination v-if="total > pageSize" v-model:current-page="page" :page-size="pageSize" :total="total" layout="prev, pager, next" @current-change="load" />
      </div>
      <aside class="content-card"><h3>提问小贴士</h3><p>描述你的目标、环境和已尝试的方法，通常会更快获得好答案。</p></aside>
    </div>
    <el-dialog v-model="dialog" title="发布问题" width="min(560px, 90vw)">
      <el-form label-position="top">
        <el-form-item label="问题标题"><el-input v-model="form.title" maxlength="200" show-word-limit /></el-form-item>
        <el-form-item label="详细描述"><el-input v-model="form.content" type="textarea" :rows="6" maxlength="10000" show-word-limit /></el-form-item>
      </el-form>
      <template #footer><el-button @click="dialog = false">取消</el-button><el-button type="primary" :loading="submitting" @click="submit">发布</el-button></template>
    </el-dialog>
  </section>
</template>
