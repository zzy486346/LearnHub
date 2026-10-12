import { http, logoutSession } from './http'
import type { Answer, ApiResult, Coupon, CouponClaim, MyCoupon, Course, CourseCategory, CourseOrder, CourseSearchResult, CourseTag, LearningProgress, LikeState, PageResult, ProfileOverview, Question, TokenPair, User } from '@/types'

export const authApi = {
  login: (body: { username: string; password: string }) => http.post<ApiResult<TokenPair>>('/auth/login', body),
  register: (body: { username: string; password: string; nickname: string }) => http.post<ApiResult<void>>('/auth/register', body),
  me: () => http.get<ApiResult<User>>('/auth/me'),
  updateAvatar: (file: File) => {
    const form = new FormData()
    form.append('file', file)
    return http.post<ApiResult<User>>('/auth/me/avatar', form)
  },
  changePassword: (body: { currentPassword: string; newPassword: string }) =>
    http.put<ApiResult<void>>('/auth/me/password', body),
  logout: logoutSession,
}

export const profileApi = {
  overview: () => http.get<ApiResult<ProfileOverview>>('/profile/overview'),
}

export const courseApi = {
  list: (params: Record<string, unknown>) => http.get<ApiResult<PageResult<Course>>>('/courses', { params }),
  detail: (id: string | number) => http.get<ApiResult<Course>>(`/courses/${id}`),
  search: (params: Record<string, unknown>) => http.get<ApiResult<CourseSearchResult[]>>('/search/courses', { params }),
  searchPage: (params: Record<string, unknown>) => http.get<ApiResult<PageResult<CourseSearchResult>>>('/search/courses/page', { params }),
  suggestions: (params: { prefix: string; limit?: number }) => http.get<ApiResult<string[]>>('/search/courses/suggestions', { params }),
  categories: () => http.get<ApiResult<CourseCategory[]>>('/course-categories'),
  tags: () => http.get<ApiResult<CourseTag[]>>('/course-tags'),
  favoriteStatus: (id: number) => http.get<ApiResult<boolean>>(`/favorites/COURSE/${id}`),
  favorite: (id: number) => http.put<ApiResult<boolean>>(`/favorites/COURSE/${id}`),
  unfavorite: (id: number) => http.delete<ApiResult<boolean>>(`/favorites/COURSE/${id}`),
  likeStatus: (id: number) => http.get<ApiResult<LikeState>>(`/likes/COURSE/${id}`),
  like: (id: number) => http.put<ApiResult<LikeState>>(`/likes/COURSE/${id}`),
  unlike: (id: number) => http.delete<ApiResult<LikeState>>(`/likes/COURSE/${id}`),
}

export const learningApi = {
  progress: (courseId: string | number) =>
    http.get<ApiResult<LearningProgress[]>>(`/learning/progress/${courseId}`),
  saveProgress: (courseId: string | number, body: { lessonId: number; positionSeconds: number; completed: boolean }) =>
    http.put<ApiResult<LearningProgress>>(`/learning/progress/${courseId}`, body),
}

export const orderApi = {
  mine: () => http.get<ApiResult<CourseOrder[]>>('/orders/mine'),
  createCourseOrder: (courseId: string | number, couponClaimId?: string) =>
    http.post<ApiResult<CourseOrder>>(`/orders/courses/${courseId}`, { couponClaimId: couponClaimId || null }),
  pay: (orderId: string | number) =>
    http.post<ApiResult<CourseOrder>>(`/orders/${orderId}/pay`),
  cancel: (orderId: string | number) => http.post<ApiResult<CourseOrder>>(`/orders/${orderId}/cancel`),
  refund: (orderId: string | number) => http.post<ApiResult<CourseOrder>>(`/orders/${orderId}/refund`),
}

export const adminCourseApi = {
  list: (page = 1) => http.get<ApiResult<PageResult<Course>>>('/admin/courses', { params: { page } }),
  detail: (courseId: string | number) => http.get<ApiResult<Course>>(`/admin/courses/${courseId}`),
  createCourse: (body: { categoryId: string | number; title: string; description?: string; instructor: string; price: number; status?: 'DRAFT' }) =>
    http.post<ApiResult<number>>('/admin/courses', body),
  updateCourse: (courseId: string | number, body: { categoryId?: string | number; title?: string; subtitle?: string; coverUrl?: string; description?: string; instructor?: string; price?: number }) =>
    http.put<ApiResult<void>>(`/admin/courses/${courseId}`, body),
  publishCourse: (courseId: string | number) =>
    http.post<ApiResult<void>>(`/admin/courses/${courseId}/publish`),
  offlineCourse: (courseId: string | number) => http.post<ApiResult<void>>(`/admin/courses/${courseId}/offline`),
  createChapter: (courseId: string | number, body: { title: string; sortOrder: number }) =>
    http.post<ApiResult<number>>(`/admin/courses/${courseId}/chapters`, body),
  updateChapter: (chapterId: string | number, body: { title: string; sortOrder: number }) =>
    http.put<ApiResult<void>>(`/admin/chapters/${chapterId}`, body),
  createLesson: (chapterId: string | number, body: { title: string; durationSeconds: number; freePreview: boolean; sortOrder: number }) =>
    http.post<ApiResult<number>>(`/admin/chapters/${chapterId}/lessons`, body),
  updateLesson: (lessonId: string | number, body: { title: string; durationSeconds: number; freePreview: boolean; sortOrder: number }) =>
    http.put<ApiResult<void>>(`/admin/lessons/${lessonId}`, body),
  deleteLesson: (lessonId: string | number) => http.delete<ApiResult<void>>(`/admin/lessons/${lessonId}`),
  uploadLessonVideo: (lessonId: string | number, file: File, onProgress?: (percentage: number) => void) => {
    const form = new FormData()
    form.append('file', file)
    return http.post<ApiResult<unknown>>(`/admin/lessons/${lessonId}/video`, form, {
      timeout: 10 * 60_000,
      onUploadProgress: (event) => {
        if (event.total) onProgress?.(Math.round((event.loaded / event.total) * 100))
      },
    })
  },
  uploadCover: (courseId: string | number, file: File) => {
    const form = new FormData()
    form.append('file', file)
    return http.post<ApiResult<unknown>>(`/admin/courses/${courseId}/cover`, form)
  },
  categories: () => http.get<ApiResult<CourseCategory[]>>('/admin/course-categories'),
  createCategory: (body: { name: string; sortOrder: number; status: 'ENABLED' | 'DISABLED' }) => http.post<ApiResult<string>>('/admin/course-categories', body),
  updateCategory: (id: string, body: { name: string; sortOrder: number; status: 'ENABLED' | 'DISABLED' }) => http.put<ApiResult<void>>(`/admin/course-categories/${id}`, body),
  deleteCategory: (id: string) => http.delete<ApiResult<void>>(`/admin/course-categories/${id}`),
  tags: () => http.get<ApiResult<CourseTag[]>>('/admin/course-tags'),
  createTag: (body: { name: string }) => http.post<ApiResult<string>>('/admin/course-tags', body),
  updateTag: (id: string, body: { name: string }) => http.put<ApiResult<void>>(`/admin/course-tags/${id}`, body),
  deleteTag: (id: string) => http.delete<ApiResult<void>>(`/admin/course-tags/${id}`),
  courseTags: (courseId: string | number) => http.get<ApiResult<CourseTag[]>>(`/admin/courses/${courseId}/tags`),
  replaceCourseTags: (courseId: string | number, tagIds: string[]) => http.put<ApiResult<void>>(`/admin/courses/${courseId}/tags`, { tagIds }),
}

export const questionApi = {
  list: (params: Record<string, unknown>) => http.get<ApiResult<PageResult<Question>>>('/questions', { params }),
  detail: (id: string | number) => http.get<ApiResult<Question>>(`/questions/${id}`),
  answers: (id: string | number, params: Record<string, unknown>) =>
    http.get<ApiResult<PageResult<Answer>>>(`/questions/${id}/answers`, { params }),
  create: (body: { title: string; content: string; courseId?: string | number }) => http.post<ApiResult<Question>>('/questions', body),
  answer: (id: string | number, body: { content: string }) =>
    http.post<ApiResult<Question>>(`/questions/${id}/answers`, body),
}

export const likeApi = {
  status: (type: 'QUESTION' | 'ANSWER', id: string) =>
    http.get<ApiResult<LikeState>>(`/likes/${type}/${id}`),
  like: (type: 'QUESTION' | 'ANSWER', id: string) =>
    http.put<ApiResult<LikeState>>(`/likes/${type}/${id}`),
  unlike: (type: 'QUESTION' | 'ANSWER', id: string) =>
    http.delete<ApiResult<LikeState>>(`/likes/${type}/${id}`),
}

export const couponApi = {
  available: () => http.get<ApiResult<Coupon[]>>('/coupons'),
  mine: () => http.get<ApiResult<MyCoupon[]>>('/coupons/me'),
  status: (id: string) => http.get<ApiResult<CouponClaim | null>>(`/coupons/${id}/claims/me`),
  claim: (id: string) => http.post<ApiResult<CouponClaim>>(`/coupons/${id}/claim`),
  seckill: (id: string) => http.post<ApiResult<CouponClaim>>(`/coupons/${id}/seckill`),
  create: (body: { name: string; stock: number; startAt: string; endAt: string; seckill: boolean; discountAmount: number; thresholdAmount: number; useStartAt?: string; useEndAt?: string }) =>
    http.post<ApiResult<Coupon>>('/coupons', body),
}
