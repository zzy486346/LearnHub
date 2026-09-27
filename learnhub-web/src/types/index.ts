export interface ApiResult<T> { code: string; message: string; data: T; timestamp?: string }
export interface PageResult<T> { records: T[]; total: number; current: number; size: number }
export interface TokenPair { tokenType: string; accessToken: string; refreshToken: string; accessExpiresIn: number; refreshExpiresIn: number }
export interface User { id?: number; userId?: number; username: string; nickname?: string; avatarUrl?: string; roles?: string[] }
export interface Course {
  id: number; title: string; subtitle?: string; coverUrl?: string; description?: string
  teacherName?: string; instructor?: string; price?: number; likeCount?: number; favoriteCount?: number
  tags?: string[]; chapters?: Chapter[]; liked?: boolean; favorited?: boolean
}
export interface Chapter { id: number; title: string; lessons: Lesson[] }
export interface Lesson { id: number; title: string; durationSeconds?: number; freePreview?: boolean }
export interface Question { id: number; title: string; content?: string; nickname?: string; answerCount?: number; answers?: unknown[]; likeCount?: number; createdAt?: string }
export interface Coupon { id: number; name: string; stock?: number; seckill?: boolean; discountAmount?: number; thresholdAmount?: number; availableStock?: number; type?: string; claimed?: boolean }
