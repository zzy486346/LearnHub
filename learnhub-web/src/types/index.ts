export interface ApiResult<T> { code: string; message: string; data: T; timestamp?: string }
export interface PageResult<T> { records: T[]; total: number; current: number; size: number }
export interface TokenPair { tokenType: string; accessToken: string; refreshToken: string; accessExpiresIn: number; refreshExpiresIn: number }
export interface User { id: number; username: string; nickname: string; avatarUrl?: string; roles?: string[] }
export interface Course {
  id: number; title: string; subtitle?: string; coverUrl?: string; description?: string
  teacherName?: string; instructor?: string; price?: number; likeCount?: number; favoriteCount?: number
  categoryId?: number; status?: 'DRAFT' | 'PUBLISHED'; tags?: string[]; chapters?: Chapter[]; liked?: boolean; favorited?: boolean
}
export interface Chapter { id: number; title: string; sortOrder?: number; lessons: Lesson[] }
export interface Lesson {
  id: number; title: string; mediaUrl?: string; durationSeconds?: number; freePreview?: boolean; sortOrder?: number
}
export interface LearningProgress {
  id?: number; userId?: number; lessonId: number; positionSeconds: number; completed: boolean; lastLearnedAt?: string
}
export interface Question {
  id: string; userId: string; nickname: string; courseId?: string; title: string; content: string
  status: string; answerCount: number; likeCount: number; createdAt: string
}
export interface Answer {
  id: string; questionId: string; userId: string; nickname: string; content: string
  accepted: boolean; likeCount: number; createdAt: string
}
export interface Coupon { id: number; name: string; stock?: number; seckill?: boolean; discountAmount?: number; thresholdAmount?: number; availableStock?: number; type?: string; claimed?: boolean }
export interface LikeState { liked: boolean; count: number }
