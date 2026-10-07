import { http } from './http'
import type { Answer, ApiResult, Coupon, CouponClaim, MyCoupon, Course, CourseOrder, LearningProgress, LikeState, PageResult, ProfileOverview, Question, TokenPair, User } from '@/types'

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
  logout: () => http.post<ApiResult<void>>('/auth/logout'),
}

export const profileApi = {
  overview: () => http.get<ApiResult<ProfileOverview>>('/profile/overview'),
}

export const courseApi = {
  list: (params: Record<string, unknown>) => http.get<ApiResult<PageResult<Course>>>('/courses', { params }),
  detail: (id: string | number) => http.get<ApiResult<Course>>(`/courses/${id}`),
  search: (params: Record<string, unknown>) => http.get<ApiResult<Course[]>>('/search/courses', { params }),
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
  createCourseOrder: (courseId: string | number) =>
    http.post<ApiResult<CourseOrder>>(`/orders/courses/${courseId}`),
  pay: (orderId: string | number) =>
    http.post<ApiResult<CourseOrder>>(`/orders/${orderId}/pay`),
}

export const adminCourseApi = {
  list: () => http.get<ApiResult<Course[]>>('/admin/courses'),
  detail: (courseId: string | number) => http.get<ApiResult<Course>>(`/admin/courses/${courseId}`),
  createCourse: (body: { categoryId: number; title: string; description?: string; instructor: string; price: number; status: 'DRAFT' | 'PUBLISHED' }) =>
    http.post<ApiResult<number>>('/admin/courses', body),
  createChapter: (courseId: string | number, body: { title: string; sortOrder: number }) =>
    http.post<ApiResult<number>>(`/admin/courses/${courseId}/chapters`, body),
  createLesson: (chapterId: string | number, body: { title: string; durationSeconds: number; freePreview: boolean; sortOrder: number }) =>
    http.post<ApiResult<number>>(`/admin/chapters/${chapterId}/lessons`, body),
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
}
