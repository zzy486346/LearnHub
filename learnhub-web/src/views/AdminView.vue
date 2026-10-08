<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { adminCourseApi } from '@/api'
import type { Chapter, Course, Lesson } from '@/types'

const courses = ref<Course[]>([])
const selected = ref<Course>()
const loading = ref(false)
const courseDialog = ref(false)
const chapterDialog = ref(false)
const lessonDialog = ref(false)
const submitting = ref(false)
const publishing = ref(false)
const selectedChapterId = ref<number>()
const uploadLesson = ref<Lesson>()
const uploadingLessonId = ref<number>()
const uploadProgress = ref(0)
const videoInput = ref<HTMLInputElement>()

const courseForm = reactive({ categoryId: 1, title: '', description: '', instructor: '', price: 0, status: 'DRAFT' as 'DRAFT' | 'PUBLISHED' })
const chapterForm = reactive({ title: '', sortOrder: 1 })
const lessonForm = reactive({ title: '', durationSeconds: 60, freePreview: false, sortOrder: 1 })
const chapters = computed(() => selected.value?.chapters || [])

onMounted(loadCourses)

async function loadCourses(selectId?: number) {
  loading.value = true
  try {
    courses.value = (await adminCourseApi.list()).data.data || []
    const target = selectId ? courses.value.find((item) => item.id === selectId) : selected.value
    if (target) await selectCourse(target)
  } catch {
    ElMessage.error('课程列表加载失败，请确认管理员权限和后端服务状态')
  } finally { loading.value = false }
}

async function selectCourse(course: Course) {
  try { selected.value = (await adminCourseApi.detail(course.id)).data.data }
  catch { ElMessage.error('课程详情加载失败') }
}

async function publishCourse() {
  const course = selected.value
  if (!course || course.status === 'PUBLISHED' || publishing.value) return
  publishing.value = true
  try {
    await adminCourseApi.publishCourse(course.id)
    course.status = 'PUBLISHED'
    const listed = courses.value.find(item => item.id === course.id)
    if (listed) listed.status = 'PUBLISHED'
    ElMessage.success('课程已发布，搜索索引将自动同步')
    await loadCourses(course.id)
  } catch (error) {
    ElMessage.error((error as { response?: { data?: { message?: string } } }).response?.data?.message || '课程发布失败，请稍后重试')
  } finally { publishing.value = false }
}

function openCourseDialog() {
  Object.assign(courseForm, { categoryId: 1, title: '', description: '', instructor: '', price: 0, status: 'DRAFT' })
  courseDialog.value = true
}

async function createCourse() {
  if (!courseForm.title.trim() || !courseForm.instructor.trim()) return ElMessage.warning('请填写课程名称和讲师')
  submitting.value = true
  try {
    const id = (await adminCourseApi.createCourse({ ...courseForm, title: courseForm.title.trim(), instructor: courseForm.instructor.trim() })).data.data
    courseDialog.value = false
    ElMessage.success('课程已创建')
    await loadCourses(id)
  } catch { ElMessage.error('课程创建失败，请检查填写内容') }
  finally { submitting.value = false }
}

function openChapterDialog() {
  if (!selected.value) return
  Object.assign(chapterForm, { title: '', sortOrder: chapters.value.length + 1 })
  chapterDialog.value = true
}

async function createChapter() {
  if (!selected.value || !chapterForm.title.trim()) return ElMessage.warning('请填写章节名称')
  submitting.value = true
  try {
    await adminCourseApi.createChapter(selected.value.id, { ...chapterForm, title: chapterForm.title.trim() })
    chapterDialog.value = false
    await selectCourse(selected.value)
    ElMessage.success('章节已添加')
  } catch { ElMessage.error('章节创建失败') }
  finally { submitting.value = false }
}

function openLessonDialog(chapter: Chapter) {
  selectedChapterId.value = chapter.id
  Object.assign(lessonForm, { title: '', durationSeconds: 60, freePreview: false, sortOrder: chapter.lessons.length + 1 })
  lessonDialog.value = true
}

async function createLesson() {
  if (!selectedChapterId.value || !lessonForm.title.trim()) return ElMessage.warning('请填写课时名称')
  submitting.value = true
  try {
    await adminCourseApi.createLesson(selectedChapterId.value, { ...lessonForm, title: lessonForm.title.trim() })
    lessonDialog.value = false
    if (selected.value) await selectCourse(selected.value)
    ElMessage.success('课时已添加')
  } catch { ElMessage.error('课时创建失败') }
  finally { submitting.value = false }
}

function chooseVideo(lesson: Lesson) {
  uploadLesson.value = lesson
  videoInput.value?.click()
}

async function handleVideo(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (!file || !uploadLesson.value) return
  if (!file.type.startsWith('video/')) return ElMessage.warning('请选择 MP4、WebM 等视频文件')
  const lesson = uploadLesson.value
  uploadingLessonId.value = lesson.id
  uploadProgress.value = 0
  try {
    await adminCourseApi.uploadLessonVideo(lesson.id, file, (value) => { uploadProgress.value = value })
    if (selected.value) await selectCourse(selected.value)
    ElMessage.success(`《${lesson.title}》视频上传完成`)
  } catch { ElMessage.error('视频上传失败，请检查文件大小和 OSS 配置') }
  finally {
    uploadingLessonId.value = undefined
    uploadProgress.value = 0
    uploadLesson.value = undefined
  }
}
</script>

<template>
  <section class="admin-page">
    <div class="page-title admin-title">
      <div><p class="eyebrow">ADMIN CONSOLE</p><h1>课程内容管理</h1><p>创建课程结构，并将课时视频直接上传到阿里云 OSS。</p></div>
      <el-button type="primary" size="large" @click="openCourseDialog">新建课程</el-button>
    </div>

    <div class="admin-workspace" v-loading="loading">
      <aside class="content-card admin-course-list" aria-label="课程列表">
        <div class="admin-panel-heading"><div><span>全部课程</span><small>{{ courses.length }} 门</small></div></div>
        <button v-for="course in courses" :key="course.id" type="button" :disabled="publishing" :class="['admin-course-item', { active: selected?.id === course.id }]" @click="selectCourse(course)">
          <span><strong>{{ course.title }}</strong><small>{{ course.instructor }} · ¥{{ course.price ?? 0 }}</small></span>
          <el-tag :type="course.status === 'PUBLISHED' ? 'success' : 'info'" size="small">{{ course.status === 'PUBLISHED' ? '已发布' : '草稿' }}</el-tag>
        </button>
        <el-empty v-if="!courses.length && !loading" description="暂无课程，先创建第一门课程" :image-size="76" />
      </aside>

      <main class="content-card admin-editor">
        <template v-if="selected">
          <div class="admin-panel-heading admin-detail-heading">
            <div><p class="eyebrow">COURSE STRUCTURE</p><h2>{{ selected.title }}</h2><small>{{ selected.description || '暂未填写课程介绍' }}</small></div>
            <div class="admin-course-actions">
              <el-button v-if="selected.status !== 'PUBLISHED'" type="primary" :loading="publishing" :disabled="loading || uploadingLessonId !== undefined" @click="publishCourse">发布课程</el-button>
              <el-tag v-else type="success">已发布</el-tag>
              <el-button @click="openChapterDialog">添加章节</el-button>
            </div>
          </div>
          <div v-if="chapters.length" class="admin-chapter-list">
            <article v-for="chapter in chapters" :key="chapter.id" class="admin-chapter-card">
              <header><div><small>第 {{ chapter.sortOrder }} 章</small><h3>{{ chapter.title }}</h3></div><el-button text type="primary" @click="openLessonDialog(chapter)">添加课时</el-button></header>
              <div v-if="chapter.lessons.length" class="admin-lesson-list">
                <div v-for="lesson in chapter.lessons" :key="lesson.id" class="admin-lesson-row">
                  <span class="admin-lesson-index">{{ lesson.sortOrder }}</span>
                  <span class="admin-lesson-copy"><strong>{{ lesson.title }}</strong><small>{{ lesson.durationSeconds }} 秒 <template v-if="lesson.freePreview">· 免费试看</template></small></span>
                  <span v-if="uploadingLessonId === lesson.id" class="admin-upload-state" role="status">上传 {{ uploadProgress }}%</span>
                  <el-button :loading="uploadingLessonId === lesson.id" :disabled="uploadingLessonId !== undefined" @click="chooseVideo(lesson)">{{ lesson.mediaUrl ? '替换视频' : '上传视频' }}</el-button>
                </div>
              </div>
              <el-empty v-else description="本章节还没有课时" :image-size="58" />
            </article>
          </div>
          <el-empty v-else description="先添加章节，再创建课时和上传视频" />
        </template>
        <el-empty v-else description="从左侧选择课程，或新建一门课程" />
      </main>
    </div>

    <input ref="videoInput" class="avatar-file-input" type="file" accept="video/mp4,video/webm,video/quicktime" @change="handleVideo" />

    <el-dialog v-model="courseDialog" title="新建课程" width="min(560px, calc(100vw - 32px))">
      <el-form label-position="top" @submit.prevent="createCourse">
        <el-form-item label="课程名称" required><el-input v-model="courseForm.title" maxlength="160" show-word-limit /></el-form-item>
        <div class="admin-form-grid"><el-form-item label="分类"><el-select v-model="courseForm.categoryId"><el-option label="后端开发" :value="1" /><el-option label="人工智能" :value="2" /><el-option label="前端开发" :value="3" /></el-select></el-form-item><el-form-item label="讲师" required><el-input v-model="courseForm.instructor" /></el-form-item></div>
        <el-form-item label="课程介绍"><el-input v-model="courseForm.description" type="textarea" :rows="4" maxlength="5000" /></el-form-item>
        <div class="admin-form-grid"><el-form-item label="价格"><el-input-number v-model="courseForm.price" :min="0" :precision="2" /></el-form-item><el-form-item label="状态"><el-select v-model="courseForm.status"><el-option label="草稿" value="DRAFT" /><el-option label="立即发布" value="PUBLISHED" /></el-select></el-form-item></div>
      </el-form>
      <template #footer><el-button @click="courseDialog = false">取消</el-button><el-button type="primary" :loading="submitting" @click="createCourse">创建课程</el-button></template>
    </el-dialog>

    <el-dialog v-model="chapterDialog" title="添加章节" width="min(480px, calc(100vw - 32px))">
      <el-form label-position="top"><el-form-item label="章节名称" required><el-input v-model="chapterForm.title" maxlength="160" /></el-form-item><el-form-item label="排序"><el-input-number v-model="chapterForm.sortOrder" :min="0" /></el-form-item></el-form>
      <template #footer><el-button @click="chapterDialog = false">取消</el-button><el-button type="primary" :loading="submitting" @click="createChapter">添加章节</el-button></template>
    </el-dialog>

    <el-dialog v-model="lessonDialog" title="添加课时" width="min(500px, calc(100vw - 32px))">
      <el-form label-position="top"><el-form-item label="课时名称" required><el-input v-model="lessonForm.title" maxlength="160" /></el-form-item><div class="admin-form-grid"><el-form-item label="时长（秒）"><el-input-number v-model="lessonForm.durationSeconds" :min="1" /></el-form-item><el-form-item label="排序"><el-input-number v-model="lessonForm.sortOrder" :min="0" /></el-form-item></div><el-form-item><el-checkbox v-model="lessonForm.freePreview">允许未登录用户试看</el-checkbox></el-form-item></el-form>
      <template #footer><el-button @click="lessonDialog = false">取消</el-button><el-button type="primary" :loading="submitting" @click="createLesson">添加课时</el-button></template>
    </el-dialog>
  </section>
</template>
