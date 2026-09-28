import { http } from './http'
import type { ApiResult, Coupon, Course, LikeState, PageResult, Question, TokenPair, User } from '@/types'

export const authApi = {
  login: (body: { username: string; password: string }) => http.post<ApiResult<TokenPair>>('/auth/login', body),
  register: (body: { username: string; password: string; nickname: string }) => http.post<ApiResult<void>>('/auth/register', body),
  me: () => http.get<ApiResult<User>>('/auth/me'),
  logout: () => http.post<ApiResult<void>>('/auth/logout'),
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

export const questionApi = {
  list: (params: Record<string, unknown>) => http.get<ApiResult<Question[]>>('/questions', { params }),
  create: (body: { title: string; content: string; courseId?: number }) => http.post<ApiResult<Question>>('/questions', body),
}

export const couponApi = {
  available: () => http.get<ApiResult<Array<{ id: number; name: string; stock: number; seckill: boolean }>>>('/coupons'),
  claim: (id: number) => http.post<ApiResult<unknown>>(`/coupons/${id}/claim`),
  seckill: (id: number) => http.post<ApiResult<{ couponId: number; userId: number; status: string }>>(`/coupons/${id}/seckill`),
}
