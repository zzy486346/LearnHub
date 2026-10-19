<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { preferenceApi, preferenceCategoryLabels, preferenceErrorMessage, preferenceStatusLabel, type LearningPreference, type PreferenceCategory } from '@/api/preferences'

const props = defineProps<{ modelValue: boolean }>()
const emit = defineEmits<{ 'update:modelValue': [value: boolean] }>()
const preferences = ref<LearningPreference[]>([])
const loading = ref(false)
const busy = ref(false)
const errorMessage = ref('')
const editing = ref<LearningPreference | null>(null)
const category = ref<PreferenceCategory>('STYLE')
const text = ref('')
let epoch = 0
const canSave = computed(() => !loading.value && !busy.value && text.value.trim().length > 0 && text.value.length <= 200)

function resetForm() { editing.value = null; category.value = 'STYLE'; text.value = '' }
function edit(preference: LearningPreference) {
  editing.value = preference; category.value = preference.category; text.value = preference.text; errorMessage.value = ''
}
async function refresh() {
  const current = ++epoch
  loading.value = true
  errorMessage.value = ''
  try {
    const response = await preferenceApi.list()
    if (current === epoch) { preferences.value = response.data.data || []; resetForm() }
  } catch (error) {
    if (current === epoch) errorMessage.value = preferenceErrorMessage(error)
  } finally { if (current === epoch) loading.value = false }
}
async function save() {
  if (!canSave.value) return
  const current = epoch
  busy.value = true
  errorMessage.value = ''
  try {
    const response = editing.value
      ? await preferenceApi.update(editing.value.id, editing.value.version, category.value, text.value.trim())
      : await preferenceApi.create(category.value, text.value.trim())
    if (current !== epoch) return
    const value = response.data.data
    preferences.value = [value, ...preferences.value.filter(item => item.id !== value.id)]
    resetForm()
    ElMessage.success(value.status === 'ACTIVE' ? '学习偏好已保存' : '偏好已保存，等待同步后使用')
  } catch (error) {
    if (current === epoch) errorMessage.value = preferenceErrorMessage(error)
  } finally { if (current === epoch) busy.value = false }
}
async function forget(preference: LearningPreference) {
  const current = epoch
  try {
    await ElMessageBox.confirm('忘记后将立即停止在后续回答中使用该偏好。', '忘记学习偏好', {
      type: 'warning', confirmButtonText: '确认忘记', cancelButtonText: '取消',
    })
    if (current !== epoch) return
    busy.value = true
    errorMessage.value = ''
    const response = await preferenceApi.forget(preference.id, preference.version)
    if (current !== epoch) return
    const value = response.data.data
    preferences.value = value.status === 'DELETED'
      ? preferences.value.filter(item => item.id !== value.id)
      : preferences.value.map(item => item.id === value.id ? value : item)
    if (editing.value?.id === value.id) resetForm()
    ElMessage.success(value.status === 'DELETED' ? '学习偏好已忘记' : '已停止使用，远端删除等待同步')
  } catch (error) {
    if (current === epoch && error !== 'cancel' && error !== 'close') errorMessage.value = preferenceErrorMessage(error)
  } finally { if (current === epoch) busy.value = false }
}
watch(() => props.modelValue, open => {
  if (open) void refresh()
  else { epoch++; preferences.value = []; errorMessage.value = ''; busy.value = false; loading.value = false; resetForm() }
})
onBeforeUnmount(() => { epoch++ })
</script>

<template>
  <el-dialog :model-value="modelValue" title="学习偏好" width="min(640px, calc(100vw - 28px))"
    :close-on-click-modal="false" @update:model-value="emit('update:modelValue', $event)">
    <p class="preference-note">仅保存你主动填写的学习风格、节奏、程度和目标。请勿填写敏感信息或课程材料。</p>
    <div class="preference-toolbar"><span>本人学习偏好</span><el-button :disabled="busy" :loading="loading" @click="refresh">刷新列表</el-button></div>
    <p v-if="errorMessage" class="preference-error" role="alert">{{ errorMessage }}</p>
    <p v-if="loading" role="status">正在加载偏好…</p>
    <p v-else-if="!preferences.length" class="preference-note">还没有已保存的学习偏好。</p>
    <ul v-else class="preference-list" aria-label="已保存的学习偏好">
      <li v-for="preference in preferences" :key="preference.id">
        <div class="preference-meta"><strong>{{ preferenceCategoryLabels[preference.category] || '学习偏好' }}</strong>
          <el-tag :type="preference.status === 'ACTIVE' ? 'success' : 'warning'">{{ preferenceStatusLabel(preference.status) }}</el-tag></div>
        <p>{{ preference.text || '偏好内容已清除' }}</p>
        <div class="preference-row-actions">
          <span v-if="preference.sourceConversationId">来自历史会话，使用前复核权限</span>
          <el-button :disabled="busy || loading || preference.status === 'DELETING'" @click="edit(preference)">修改</el-button>
          <el-button type="danger" plain :disabled="busy || loading || preference.status === 'DELETING'" @click="forget(preference)">忘记</el-button>
        </div>
      </li>
    </ul>
    <form class="preference-form" @submit.prevent="save">
      <h3>{{ editing ? '修改学习偏好' : '新增学习偏好' }}</h3>
      <label for="preference-category">偏好分类</label>
      <el-select id="preference-category" v-model="category" :disabled="busy" aria-label="偏好分类">
        <el-option v-for="(label, value) in preferenceCategoryLabels" :key="value" :label="label" :value="value" />
      </el-select>
      <label for="preference-text">偏好内容</label>
      <el-input id="preference-text" v-model="text" type="textarea" :rows="3" maxlength="200" show-word-limit :disabled="busy"
        placeholder="例如：我希望先用 Java 示例，再解释原理" />
      <div class="preference-form-actions">
        <el-button v-if="editing" :disabled="busy" @click="resetForm">取消修改</el-button>
        <el-button type="primary" native-type="submit" :disabled="!canSave" :loading="busy">{{ editing ? '保存修改' : '保存偏好' }}</el-button>
      </div>
    </form>
  </el-dialog>
</template>

<style scoped>
.preference-note { color:var(--muted); line-height:1.65; margin:0 0 14px; }
.preference-toolbar,.preference-meta,.preference-row-actions,.preference-form-actions { display:flex; align-items:center; gap:10px; flex-wrap:wrap; }
.preference-toolbar { justify-content:space-between; font-weight:700; margin-bottom:12px; }
.preference-list { max-height:32dvh; overflow:auto; margin:0 0 18px; padding:0; list-style:none; display:grid; gap:12px; }
.preference-list li { border:1px solid var(--line); border-radius:12px; padding:14px; }
.preference-list p { white-space:pre-wrap; overflow-wrap:anywhere; line-height:1.65; margin:12px 0; }
.preference-row-actions { justify-content:flex-end; }.preference-row-actions span { margin-right:auto; color:var(--muted); font-size:12px; }
.preference-error { padding:12px; border-radius:10px; color:#95253a; background:#fff1f2; line-height:1.6; }
.preference-form { border-top:1px solid var(--line); padding-top:4px; }.preference-form h3 { margin:14px 0; font-size:16px; }
.preference-form label { display:block; margin:14px 0 8px; font-weight:600; }.preference-form .el-select { width:100%; }
.preference-form-actions { justify-content:flex-end; margin-top:14px; }.el-button { min-height:44px; }
</style>
