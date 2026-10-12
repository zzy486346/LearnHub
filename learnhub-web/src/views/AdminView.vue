<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { adminCourseApi, couponApi } from '@/api'
import { isSessionExpired } from '@/api/session'
import type { Chapter, Course, CourseCategory, CourseTag, Lesson } from '@/types'

const courses = ref<Course[]>([])
const coursePage = ref(1)
const courseTotal = ref(0)
const pageSize = 10
const selected = ref<Course>()
const loading = ref(false)
const detailLoading = ref(false)
const courseDialog = ref(false)
const chapterDialog = ref(false)
const lessonDialog = ref(false)
const submitting = ref(false)
const publishing = ref(false)
const offlining = ref(false)
const taxonomyLoading = ref(false)
const taxonomyDialog = ref(false)
const couponDialog = ref(false)
const editingCourse = ref(false)
const editingChapterId = ref<number>()
const editingLessonId = ref<number>()
const savingTags = ref(false)
const uploadingCover = ref(false)
const deletingLessonId = ref<number>()
const contentBusy = computed(() => submitting.value || publishing.value || offlining.value || savingTags.value || uploadingCover.value || deletingLessonId.value !== undefined || uploadingLessonId.value !== undefined)
let listRequestId = 0
let detailRequestId = 0
const selectedChapterId = ref<number>()
const uploadLesson = ref<Lesson>()
const uploadingLessonId = ref<number>()
const uploadProgress = ref(0)
const videoInput = ref<HTMLInputElement>()
const coverInput = ref<HTMLInputElement>()
const categories = ref<CourseCategory[]>([])
const tags = ref<CourseTag[]>([])
const selectedTagIds = ref<number[]>([])

const courseForm = reactive({ categoryId: 0, title: '', subtitle: '', description: '', instructor: '', price: 0 })
const chapterForm = reactive({ title: '', sortOrder: 1 })
const lessonForm = reactive({ title: '', durationSeconds: 60, freePreview: false, sortOrder: 1 })
const categoryForm = reactive({ name: '', sortOrder: 1, status: 'ENABLED' as 'ENABLED' | 'DISABLED' })
const tagForm = reactive({ name: '' })
const couponForm = reactive({ name: '', stock: 100, seckill: false, discountAmount: 10, thresholdAmount: 0, startAt: new Date(), endAt: new Date(Date.now() + 7 * 86400000), useStartAt: new Date(), useEndAt: new Date(Date.now() + 30 * 86400000) })
const chapters = computed(() => selected.value?.chapters || [])
const enabledCategories = computed(() => categories.value.filter(item => item.status === 'ENABLED' || item.id === selected.value?.categoryId))

onMounted(() => { void Promise.allSettled([loadCourses(), loadTaxonomy()]) })

function apiMessage(error: unknown, fallback: string) {
  return (error as { response?: { data?: { message?: string } } }).response?.data?.message || fallback
}

async function loadTaxonomy() {
  taxonomyLoading.value = true
  try {
    const [categoryResult, tagResult] = await Promise.all([adminCourseApi.categories(), adminCourseApi.tags()])
    categories.value = categoryResult.data.data || []
    tags.value = tagResult.data.data || []
  } catch (error) { if (!isSessionExpired(error)) ElMessage.error('课程分类和标签加载失败') }
  finally { taxonomyLoading.value = false }
}

async function loadCourses(selectId?: number, page = coursePage.value) {
  const requestId = ++listRequestId
  loading.value = true
  try {
    let data = (await adminCourseApi.list(page)).data.data
    const lastPage = Math.max(1, Math.ceil(data.total / pageSize))
    if (page > lastPage) data = (await adminCourseApi.list(lastPage)).data.data
    if (requestId !== listRequestId) return
    courses.value = data.records
    courseTotal.value = data.total
    coursePage.value = data.current
    const target = courses.value.find(item => item.id === (selectId ?? selected.value?.id))
    if (target) {
      if (selected.value?.id !== target.id) selected.value = undefined
      await selectCourse(target)
    }
    else { ++detailRequestId; selected.value = undefined; detailLoading.value = false }
  } catch (error) {
    if (requestId !== listRequestId || isSessionExpired(error)) return
    ElMessage.error('课程列表加载失败，请确认管理员权限和后端服务状态')
  } finally { if (requestId === listRequestId) loading.value = false }
}

async function changeCoursePage(page: number) {
  if (loading.value || contentBusy.value || page === coursePage.value) return
  await loadCourses(undefined, page)
}

async function selectCourse(course: Course) {
  const requestId = ++detailRequestId
  detailLoading.value = true
  try {
    const [detail, courseTags] = await Promise.all([adminCourseApi.detail(course.id), adminCourseApi.courseTags(course.id)])
    if (requestId === detailRequestId) {
      selected.value = detail.data.data
      selectedTagIds.value = (courseTags.data.data || []).map(item => item.id)
    }
  } catch (error) { if (requestId === detailRequestId && !isSessionExpired(error)) ElMessage.error('课程详情加载失败') }
  finally { if (requestId === detailRequestId) detailLoading.value = false }
}

async function offlineCourse() {
  const course = selected.value
  if (!course || course.status !== 'PUBLISHED' || offlining.value) return
  try { await ElMessageBox.confirm('下架后将停止新购买，已购用户仍可继续学习。', '下架课程', { type: 'warning', confirmButtonText: '确认下架', cancelButtonText: '取消' }) }
  catch { return }
  offlining.value = true
  try {
    await adminCourseApi.offlineCourse(course.id)
    await loadCourses(course.id)
    ElMessage.success('课程已下架，已购学习权益不受影响')
  } catch (error) { if (!isSessionExpired(error)) ElMessage.error(apiMessage(error, '课程下架失败')) }
  finally { offlining.value = false }
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
    if (isSessionExpired(error)) return
    ElMessage.error((error as { response?: { data?: { message?: string } } }).response?.data?.message || '课程发布失败，请稍后重试')
  } finally { publishing.value = false }
}

function openCourseDialog() {
  editingCourse.value = false
  Object.assign(courseForm, { categoryId: enabledCategories.value[0]?.id || 0, title: '', subtitle: '', description: '', instructor: '', price: 0 })
  courseDialog.value = true
}

function openEditCourseDialog() {
  if (!selected.value) return
  editingCourse.value = true
  Object.assign(courseForm, { categoryId: selected.value.categoryId || 0, title: selected.value.title, subtitle: selected.value.subtitle || '', description: selected.value.description || '', instructor: selected.value.instructor || selected.value.teacherName || '', price: Number(selected.value.price || 0) })
  courseDialog.value = true
}

async function saveCourse() {
  if (submitting.value) return
  if (!courseForm.categoryId) return ElMessage.warning('请先创建并选择课程分类')
  if (!courseForm.title.trim() || !courseForm.instructor.trim()) return ElMessage.warning('请填写课程名称和讲师')
  submitting.value = true
  try {
    const payload = { ...courseForm, title: courseForm.title.trim(), subtitle: courseForm.subtitle.trim(), description: courseForm.description.trim(), instructor: courseForm.instructor.trim() }
    if (editingCourse.value && selected.value) {
      const id = selected.value.id
      await adminCourseApi.updateCourse(id, payload)
      courseDialog.value = false
      ElMessage.success('课程信息已保存')
      await loadCourses(id)
      return
    }
    const id = (await adminCourseApi.createCourse({ ...payload, status: 'DRAFT' })).data.data
    courseDialog.value = false
    ElMessage.success('课程草稿已创建，请完善内容后发布')
    await loadCourses(id, 1)
  } catch (error) { if (!isSessionExpired(error)) ElMessage.error(apiMessage(error, '课程保存失败，请检查填写内容')) }
  finally { submitting.value = false }
}

async function createCourse() { editingCourse.value = false; await saveCourse() }

function openChapterDialog() {
  if (!selected.value) return
  editingChapterId.value = undefined
  Object.assign(chapterForm, { title: '', sortOrder: chapters.value.length + 1 })
  chapterDialog.value = true
}

function openEditChapterDialog(chapter: Chapter) {
  editingChapterId.value = chapter.id
  Object.assign(chapterForm, { title: chapter.title, sortOrder: chapter.sortOrder || 0 })
  chapterDialog.value = true
}

async function saveChapter() {
  if (submitting.value) return
  if (!selected.value || !chapterForm.title.trim()) return ElMessage.warning('请填写章节名称')
  submitting.value = true
  try {
    const payload = { ...chapterForm, title: chapterForm.title.trim() }
    if (editingChapterId.value) await adminCourseApi.updateChapter(editingChapterId.value, payload)
    else await adminCourseApi.createChapter(selected.value.id, payload)
    chapterDialog.value = false
    await selectCourse(selected.value)
    ElMessage.success(editingChapterId.value ? '章节已保存' : '章节已添加')
  } catch (error) { if (!isSessionExpired(error)) ElMessage.error(apiMessage(error, '章节保存失败')) }
  finally { submitting.value = false }
}

async function createChapter() { editingChapterId.value = undefined; await saveChapter() }

function openLessonDialog(chapter: Chapter) {
  selectedChapterId.value = chapter.id
  editingLessonId.value = undefined
  Object.assign(lessonForm, { title: '', durationSeconds: 60, freePreview: false, sortOrder: Math.max(0, ...chapter.lessons.map(lesson => lesson.sortOrder ?? 0)) + 1 })
  lessonDialog.value = true
}

function openEditLessonDialog(chapter: Chapter, lesson: Lesson) {
  selectedChapterId.value = chapter.id
  editingLessonId.value = lesson.id
  Object.assign(lessonForm, { title: lesson.title, durationSeconds: lesson.durationSeconds || 1, freePreview: !!lesson.freePreview, sortOrder: lesson.sortOrder || 0 })
  lessonDialog.value = true
}

async function saveLesson() {
  if (submitting.value) return
  if (!selectedChapterId.value || !lessonForm.title.trim()) return ElMessage.warning('请填写课时名称')
  submitting.value = true
  try {
    const payload = { ...lessonForm, title: lessonForm.title.trim() }
    if (editingLessonId.value) await adminCourseApi.updateLesson(editingLessonId.value, payload)
    else await adminCourseApi.createLesson(selectedChapterId.value, payload)
    lessonDialog.value = false
    if (selected.value) await selectCourse(selected.value)
    ElMessage.success(editingLessonId.value ? '课时已保存' : '课时已添加')
  } catch (error) { if (!isSessionExpired(error)) ElMessage.error(apiMessage(error, '课时保存失败')) }
  finally { submitting.value = false }
}

async function createLesson() { editingLessonId.value = undefined; await saveLesson() }

async function deleteLesson(lesson: Lesson) {
  const course = selected.value
  if (!course || contentBusy.value || detailLoading.value) return
  deletingLessonId.value = lesson.id
  try {
    await ElMessageBox.confirm(`确认删除课时《${lesson.title}》？该课时将从课程目录中移除。`, '删除课时', {
      type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消', distinguishCancelAndClose: true,
    })
    await adminCourseApi.deleteLesson(lesson.id)
    course.chapters?.forEach(chapter => { chapter.lessons = chapter.lessons.filter(item => item.id !== lesson.id) })
    await selectCourse(course)
    ElMessage.success('课时已删除')
  } catch (error) {
    if (error === 'cancel' || error === 'close' || isSessionExpired(error)) return
    ElMessage.error((error as { response?: { data?: { message?: string } } }).response?.data?.message || '课时删除失败，请稍后重试')
  } finally { deletingLessonId.value = undefined }
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
  } catch (error) { if (!isSessionExpired(error)) ElMessage.error('视频上传失败，请检查文件大小和 OSS 配置') }
  finally {
    uploadingLessonId.value = undefined
    uploadProgress.value = 0
    uploadLesson.value = undefined
  }
}

function chooseCover() { coverInput.value?.click() }
async function handleCover(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (!file || !selected.value) return
  if (!file.type.startsWith('image/') || file.type === 'image/svg+xml') return ElMessage.warning('请选择 PNG、JPG 或 WebP 图片')
  if (file.size > 8 * 1024 * 1024) return ElMessage.warning('课程封面不能超过 8 MB')
  const id = selected.value.id
  uploadingCover.value = true
  try { await adminCourseApi.uploadCover(id, file); await loadCourses(id); ElMessage.success('课程封面已更新') }
  catch (error) { if (!isSessionExpired(error)) ElMessage.error(apiMessage(error, '封面上传失败')) }
  finally { uploadingCover.value = false }
}

async function saveCourseTags() {
  if (!selected.value || savingTags.value) return
  savingTags.value = true
  try { await adminCourseApi.replaceCourseTags(selected.value.id, selectedTagIds.value); ElMessage.success('课程标签已保存') }
  catch (error) { if (!isSessionExpired(error)) ElMessage.error(apiMessage(error, '标签保存失败')) }
  finally { savingTags.value = false }
}

async function createCategory() {
  if (!categoryForm.name.trim() || submitting.value) return ElMessage.warning('请填写分类名称')
  submitting.value = true
  try { await adminCourseApi.createCategory({ ...categoryForm, name: categoryForm.name.trim() }); categoryForm.name = ''; categoryForm.sortOrder++; await loadTaxonomy(); ElMessage.success('分类已创建') }
  catch (error) { if (!isSessionExpired(error)) ElMessage.error(apiMessage(error, '分类创建失败')) }
  finally { submitting.value = false }
}
async function updateCategory(item: CourseCategory) {
  try { await adminCourseApi.updateCategory(item.id, { name: item.name.trim(), sortOrder: item.sortOrder, status: item.status }); ElMessage.success('分类已保存') }
  catch (error) { if (!isSessionExpired(error)) ElMessage.error(apiMessage(error, '分类保存失败')); await loadTaxonomy() }
}
async function deleteCategory(item: CourseCategory) {
  try { await ElMessageBox.confirm(`确认删除分类“${item.name}”？被课程引用时系统会拒绝删除。`, '删除分类', { type: 'warning' }); await adminCourseApi.deleteCategory(item.id); await loadTaxonomy(); ElMessage.success('分类已删除') }
  catch (error) { if (error !== 'cancel' && error !== 'close' && !isSessionExpired(error)) ElMessage.error(apiMessage(error, '分类删除失败')) }
}
async function createTag() {
  if (!tagForm.name.trim() || submitting.value) return ElMessage.warning('请填写标签名称')
  submitting.value = true
  try { await adminCourseApi.createTag({ name: tagForm.name.trim() }); tagForm.name = ''; await loadTaxonomy(); ElMessage.success('标签已创建') }
  catch (error) { if (!isSessionExpired(error)) ElMessage.error(apiMessage(error, '标签创建失败')) }
  finally { submitting.value = false }
}
async function renameTag(item: CourseTag) {
  try {
    const result = await ElMessageBox.prompt('输入新的标签名称', '编辑标签', { inputValue: item.name, inputPattern: /\S+/, inputErrorMessage: '标签名称不能为空' })
    await adminCourseApi.updateTag(item.id, { name: result.value.trim() }); await loadTaxonomy(); ElMessage.success('标签已保存')
  } catch (error) { if (error !== 'cancel' && error !== 'close' && !isSessionExpired(error)) ElMessage.error(apiMessage(error, '标签保存失败')) }
}
async function deleteTag(item: CourseTag) {
  try { await ElMessageBox.confirm(`确认删除标签“${item.name}”？被课程引用时系统会拒绝删除。`, '删除标签', { type: 'warning' }); await adminCourseApi.deleteTag(item.id); await loadTaxonomy(); ElMessage.success('标签已删除') }
  catch (error) { if (error !== 'cancel' && error !== 'close' && !isSessionExpired(error)) ElMessage.error(apiMessage(error, '标签删除失败')) }
}

async function createCoupon() {
  if (!couponForm.name.trim()) return ElMessage.warning('请填写优惠券名称')
  if (couponForm.stock < 1) return ElMessage.warning('发行数量必须大于 0')
  if (couponForm.discountAmount <= 0) return ElMessage.warning('优惠金额必须大于 0')
  if (couponForm.thresholdAmount < 0) return ElMessage.warning('使用门槛不能小于 0')
  if (![couponForm.startAt, couponForm.endAt, couponForm.useStartAt, couponForm.useEndAt].every(value => value instanceof Date && !Number.isNaN(value.getTime()))) return ElMessage.warning('请完整填写领取和使用时间')
  if (couponForm.endAt <= couponForm.startAt || couponForm.useEndAt <= couponForm.useStartAt) return ElMessage.warning('结束时间必须晚于开始时间')
  submitting.value = true
  try {
    await couponApi.create({ ...couponForm, name: couponForm.name.trim(), startAt: couponForm.startAt.toISOString(), endAt: couponForm.endAt.toISOString(), useStartAt: couponForm.useStartAt.toISOString(), useEndAt: couponForm.useEndAt.toISOString() })
    couponDialog.value = false
    ElMessage.success('优惠券活动已创建')
  } catch (error) { if (!isSessionExpired(error)) ElMessage.error(apiMessage(error, '优惠券创建失败')) }
  finally { submitting.value = false }
}
</script>

<template>
  <section class="admin-page">
    <div class="page-title admin-title">
      <div><p class="eyebrow">ADMIN CONSOLE</p><h1>课程内容管理</h1><p>课程先保存为草稿，完善章节、课时与视频后再发布。</p></div>
      <div class="admin-title-actions"><el-button size="large" @click="couponDialog = true">创建优惠券</el-button><el-button size="large" @click="taxonomyDialog = true">分类与标签</el-button><el-button type="primary" size="large" :disabled="contentBusy || loading || detailLoading" @click="openCourseDialog">新建课程</el-button></div>
    </div>

    <div class="admin-workspace" v-loading="loading">
      <aside class="content-card admin-course-list" aria-label="课程列表">
        <div class="admin-panel-heading"><div><span>全部课程</span><small>共 {{ courseTotal }} 门 · 每页 {{ pageSize }} 条</small></div></div>
        <button v-for="course in courses" :key="course.id" type="button" :disabled="contentBusy || loading" :class="['admin-course-item', { active: selected?.id === course.id }]" @click="selectCourse(course)">
          <span><strong>{{ course.title }}</strong><small>{{ course.instructor }} · ¥{{ course.price ?? 0 }}</small></span>
          <el-tag :type="course.status === 'PUBLISHED' ? 'success' : course.status === 'OFFLINE' ? 'warning' : 'info'" size="small">{{ course.status === 'PUBLISHED' ? '已发布' : course.status === 'OFFLINE' ? '已下架' : '草稿' }}</el-tag>
        </button>
        <el-empty v-if="!courses.length && !loading" description="暂无课程，先创建第一门课程" :image-size="76" />
        <nav v-if="courseTotal > pageSize" class="admin-pagination" aria-label="课程分页">
          <el-pagination small background layout="prev, pager, next" :pager-count="5" :total="courseTotal" :page-size="pageSize" :current-page="coursePage" :disabled="loading || contentBusy" @current-change="changeCoursePage" />
        </nav>
      </aside>

      <main class="content-card admin-editor" v-loading="detailLoading">
        <template v-if="selected">
          <div class="admin-panel-heading admin-detail-heading">
            <div><p class="eyebrow">COURSE STRUCTURE</p><h2>{{ selected.title }}</h2><small>{{ selected.description || '暂未填写课程介绍' }}</small></div>
            <div class="admin-course-actions">
              <el-button :disabled="contentBusy || detailLoading" @click="openEditCourseDialog">编辑课程</el-button>
              <el-button :loading="uploadingCover" :disabled="contentBusy || detailLoading" @click="chooseCover">上传封面</el-button>
              <el-button v-if="selected.status !== 'PUBLISHED'" type="primary" :loading="publishing" :disabled="loading || detailLoading || contentBusy" @click="publishCourse">发布课程</el-button>
              <el-button v-else type="warning" plain :loading="offlining" :disabled="contentBusy || detailLoading" @click="offlineCourse">下架课程</el-button>
              <el-button :disabled="contentBusy || detailLoading" @click="openChapterDialog">添加章节</el-button>
            </div>
          </div>
          <div class="admin-tag-binding"><div><strong>课程标签</strong><small>用于课程检索和筛选</small></div><el-select v-model="selectedTagIds" multiple filterable collapse-tags placeholder="选择标签"><el-option v-for="tag in tags" :key="tag.id" :label="tag.name" :value="tag.id" /></el-select><el-button :loading="savingTags" :disabled="contentBusy" @click="saveCourseTags">保存标签</el-button></div>
          <div v-if="chapters.length" class="admin-chapter-list">
            <article v-for="chapter in chapters" :key="chapter.id" class="admin-chapter-card">
              <header><div><small>第 {{ chapter.sortOrder }} 章</small><h3>{{ chapter.title }}</h3></div><div class="admin-inline-actions"><el-button text @click="openEditChapterDialog(chapter)">编辑章节</el-button><el-button text type="primary" :disabled="contentBusy || detailLoading" @click="openLessonDialog(chapter)">添加课时</el-button></div></header>
              <div v-if="chapter.lessons.length" class="admin-lesson-list">
                <div v-for="lesson in chapter.lessons" :key="lesson.id" class="admin-lesson-row">
                  <span class="admin-lesson-index">{{ lesson.sortOrder }}</span>
                  <span class="admin-lesson-copy"><strong>{{ lesson.title }}</strong><small>{{ lesson.durationSeconds }} 秒 <template v-if="lesson.freePreview">· 免费试看</template></small></span>
                  <span v-if="uploadingLessonId === lesson.id" class="admin-upload-state" role="status">上传 {{ uploadProgress }}%</span>
                  <div class="admin-lesson-actions">
                    <el-button :disabled="contentBusy" @click="openEditLessonDialog(chapter, lesson)">编辑</el-button>
                    <el-button :loading="uploadingLessonId === lesson.id" :disabled="contentBusy || detailLoading" @click="chooseVideo(lesson)">{{ lesson.mediaUrl ? '替换视频' : '上传视频' }}</el-button>
                    <el-button type="danger" plain :loading="deletingLessonId === lesson.id" :disabled="contentBusy || detailLoading" :aria-label="`删除课时 ${lesson.title}`" @click="deleteLesson(lesson)">删除</el-button>
                  </div>
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
    <input ref="coverInput" class="avatar-file-input" type="file" accept="image/png,image/jpeg,image/webp" @change="handleCover" />

    <el-dialog v-model="courseDialog" :title="editingCourse ? '编辑课程' : '新建课程草稿'" width="min(620px, calc(100vw - 32px))">
      <el-form label-position="top" @submit.prevent="saveCourse">
        <el-form-item label="课程名称" required><el-input v-model="courseForm.title" maxlength="160" show-word-limit /></el-form-item>
        <el-form-item label="副标题"><el-input v-model="courseForm.subtitle" maxlength="255" /></el-form-item>
        <div class="admin-form-grid"><el-form-item label="分类" required><el-select v-model="courseForm.categoryId"><el-option v-for="item in enabledCategories" :key="item.id" :label="item.name" :value="item.id" /></el-select></el-form-item><el-form-item label="讲师" required><el-input v-model="courseForm.instructor" /></el-form-item></div>
        <el-form-item label="课程介绍"><el-input v-model="courseForm.description" type="textarea" :rows="4" maxlength="5000" /></el-form-item>
        <el-form-item label="价格"><el-input-number v-model="courseForm.price" :min="0" :precision="2" /></el-form-item>
        <p v-if="!editingCourse" class="admin-form-help">新课程统一保存为草稿；至少包含章节、课时和可用视频后才能发布。</p>
      </el-form>
      <template #footer><el-button @click="courseDialog = false">取消</el-button><el-button type="primary" :loading="submitting" @click="saveCourse">{{ editingCourse ? '保存修改' : '创建草稿' }}</el-button></template>
    </el-dialog>

    <el-dialog v-model="chapterDialog" :title="editingChapterId ? '编辑章节' : '添加章节'" width="min(480px, calc(100vw - 32px))">
      <el-form label-position="top"><el-form-item label="章节名称" required><el-input v-model="chapterForm.title" maxlength="160" /></el-form-item><el-form-item label="排序"><el-input-number v-model="chapterForm.sortOrder" :min="0" /></el-form-item></el-form>
      <template #footer><el-button @click="chapterDialog = false">取消</el-button><el-button type="primary" :loading="submitting" @click="saveChapter">{{ editingChapterId ? '保存修改' : '添加章节' }}</el-button></template>
    </el-dialog>

    <el-dialog v-model="lessonDialog" :title="editingLessonId ? '编辑课时' : '添加课时'" width="min(500px, calc(100vw - 32px))">
      <el-form label-position="top"><el-form-item label="课时名称" required><el-input v-model="lessonForm.title" maxlength="160" /></el-form-item><div class="admin-form-grid"><el-form-item label="时长（秒）"><el-input-number v-model="lessonForm.durationSeconds" :min="1" /></el-form-item><el-form-item label="排序"><el-input-number v-model="lessonForm.sortOrder" :min="0" /></el-form-item></div><el-form-item><el-checkbox v-model="lessonForm.freePreview">允许未登录用户试看</el-checkbox></el-form-item></el-form>
      <template #footer><el-button @click="lessonDialog = false">取消</el-button><el-button type="primary" :loading="submitting" @click="saveLesson">{{ editingLessonId ? '保存修改' : '添加课时' }}</el-button></template>
    </el-dialog>

    <el-dialog v-model="taxonomyDialog" title="课程分类与标签" width="min(760px, calc(100vw - 32px))">
      <div class="taxonomy-grid" v-loading="taxonomyLoading">
        <section><h3>课程分类</h3><div class="taxonomy-create"><el-input v-model="categoryForm.name" placeholder="新分类名称" /><el-input-number v-model="categoryForm.sortOrder" :min="0" /><el-button type="primary" :loading="submitting" @click="createCategory">添加</el-button></div><div class="taxonomy-list"><div v-for="item in categories" :key="item.id"><el-input v-model="item.name" /><el-input-number v-model="item.sortOrder" :min="0" /><el-select v-model="item.status"><el-option label="启用" value="ENABLED" /><el-option label="停用" value="DISABLED" /></el-select><el-button @click="updateCategory(item)">保存</el-button><el-button plain type="danger" @click="deleteCategory(item)">删除</el-button></div></div></section>
        <section><h3>课程标签</h3><div class="taxonomy-create taxonomy-tag-create"><el-input v-model="tagForm.name" placeholder="新标签名称" @keyup.enter="createTag" /><el-button type="primary" :loading="submitting" @click="createTag">添加</el-button></div><div class="taxonomy-tag-list"><el-tag v-for="item in tags" :key="item.id" closable @close="deleteTag(item)" @click="renameTag(item)">{{ item.name }}</el-tag></div><p class="admin-form-help">点击标签可重命名，关闭图标用于删除。</p></section>
      </div>
    </el-dialog>

    <el-dialog v-model="couponDialog" title="创建优惠券活动" width="min(620px, calc(100vw - 32px))">
      <el-form label-position="top">
        <el-form-item label="优惠券名称" required><el-input v-model="couponForm.name" maxlength="120" /></el-form-item>
        <div class="admin-form-grid"><el-form-item label="优惠金额" required><el-input-number v-model="couponForm.discountAmount" :min="0.01" :precision="2" /></el-form-item><el-form-item label="使用门槛"><el-input-number v-model="couponForm.thresholdAmount" :min="0" :precision="2" /></el-form-item></div>
        <div class="admin-form-grid"><el-form-item label="发行数量" required><el-input-number v-model="couponForm.stock" :min="1" /></el-form-item><el-form-item label="活动类型"><el-switch v-model="couponForm.seckill" active-text="秒杀券" inactive-text="普通券" /></el-form-item></div>
        <div class="admin-form-grid"><el-form-item label="领取开始" required><el-date-picker v-model="couponForm.startAt" type="datetime" /></el-form-item><el-form-item label="领取结束" required><el-date-picker v-model="couponForm.endAt" type="datetime" /></el-form-item></div>
        <div class="admin-form-grid"><el-form-item label="使用开始" required><el-date-picker v-model="couponForm.useStartAt" type="datetime" /></el-form-item><el-form-item label="使用结束" required><el-date-picker v-model="couponForm.useEndAt" type="datetime" /></el-form-item></div>
        <p class="admin-form-help">优惠券创建后可在课程购买页按金额门槛选择使用。秒杀券库存会优先读取 Redis 实时快照。</p>
      </el-form>
      <template #footer><el-button @click="couponDialog = false">取消</el-button><el-button type="primary" :loading="submitting" @click="createCoupon">创建活动</el-button></template>
    </el-dialog>
  </section>
</template>
