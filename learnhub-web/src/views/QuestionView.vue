<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { questionApi } from '@/api'
import type { Question } from '@/types'
const questions = ref<Question[]>([])
const dialog = ref(false)
const form = reactive({ title: '', content: '' })
const fallback: Question[] = [{ id: 1, title: 'Redis Lua 脚本如何保证秒杀原子性？', content: '库存校验、扣减与用户判重应该怎样组织？', nickname: '代码学习者', answerCount: 6, likeCount: 28, createdAt: '刚刚' }, { id: 2, title: 'JWT 刷新令牌轮换的最佳实践', content: '怎样识别 refresh token 重放？', nickname: '云端漫步', answerCount: 4, likeCount: 16, createdAt: '2 小时前' }]
async function load() { try { questions.value = (await questionApi.list({})).data.data } catch { questions.value = fallback } }
async function submit() { if (!form.title || !form.content) return ElMessage.warning('请填写问题标题与描述'); try { await questionApi.create(form); dialog.value = false; Object.assign(form, { title: '', content: '' }); await load(); ElMessage.success('问题已发布') } catch { ElMessage.error('发布失败，请先登录') } }
onMounted(load)
</script>

  <template><section><div class="page-title"><div><p class="eyebrow">COMMUNITY Q&A</p><h1>问答社区</h1><p>提出真实问题，分享可靠答案。</p></div><el-button type="primary" size="large" @click="dialog = true">我要提问</el-button></div><div class="question-layout"><div><article v-for="item in questions" :key="item.id" class="question-card"><div class="vote">{{ item.likeCount || 0 }}<small>赞同</small></div><div><h3>{{ item.title }}</h3><p>{{ item.content }}</p><span class="muted">{{ item.nickname || '问课学员' }} · {{ item.createdAt }} · {{ item.answerCount ?? item.answers?.length ?? 0 }} 个回答</span></div></article></div><aside class="content-card"><h3>提问小贴士</h3><p>描述你的目标、环境和已尝试的方法，通常会更快获得好答案。</p></aside></div><el-dialog v-model="dialog" title="发布问题" width="min(560px, 90vw)"><el-form label-position="top"><el-form-item label="问题标题"><el-input v-model="form.title" /></el-form-item><el-form-item label="详细描述"><el-input v-model="form.content" type="textarea" :rows="6" /></el-form-item></el-form><template #footer><el-button @click="dialog = false">取消</el-button><el-button type="primary" @click="submit">发布</el-button></template></el-dialog></section></template>
