export interface ApiResult<T> { code: string; message: string; data: T; timestamp?: string }
export interface PageResult<T> { records: T[]; total: number; current: number; size: number }
export interface TokenPair { tokenType: string; accessToken: string; refreshToken: string; accessExpiresIn: number; refreshExpiresIn: number }
export interface User { id: number; username: string; nickname: string; avatarUrl?: string; roles?: string[] }
export interface Course {
  id: number; title: string; subtitle?: string; coverUrl?: string; description?: string
  teacherName?: string; instructor?: string; price?: number; likeCount?: number; favoriteCount?: number
  categoryId?: string | number; status?: 'DRAFT' | 'PUBLISHED' | 'OFFLINE'; tags?: string[]; chapters?: Chapter[]; liked?: boolean; favorited?: boolean
  accessLevel?: 'LOGIN_REQUIRED' | 'PREVIEW' | 'FULL'
}
export interface CourseSearchResult {
  id: number; title: string; description?: string; instructor?: string; coverUrl?: string
  tags?: string[]; likeCount?: number; score?: number
}
export interface Chapter { id: number; title: string; sortOrder?: number; lessons: Lesson[] }
export interface Lesson {
  id: number; title: string; mediaUrl?: string; durationSeconds?: number; freePreview?: boolean; accessible?: boolean; sortOrder?: number
}
export interface CourseOrder {
  id: string; orderNo: string; courseId: string; courseTitle: string
  originalAmount: number; discountAmount?: number; payableAmount?: number; paidAmount?: number
  couponClaimId?: string; paymentMode?: 'LOCAL_DEMO' | 'LEGACY_DEMO' | 'DISABLED'
  status: 'PENDING' | 'PAID' | 'CANCELLED' | 'CLOSED' | 'REFUNDED'
  createdAt: string; expiresAt?: string; paidAt?: string; cancelledAt?: string; refundedAt?: string
}
export interface LearningProgress {
  id?: number; userId?: number; lessonId: number; positionSeconds: number; completed: boolean; lastLearnedAt?: string
}
export interface ProfileRecentLearning {
  courseId: number; courseTitle: string; lessonId: number; lessonTitle: string
  positionSeconds: number; durationSeconds: number; completed: boolean; percentage: number; lastLearnedAt: string
}
export interface ProfileOverview {
  learningCourseCount: number; completedLessonCount: number; favoriteCourseCount: number; qaContributionCount: number
  recentLearning?: ProfileRecentLearning
}
export interface Question {
  id: string; userId: string; nickname: string; courseId?: string; title: string; content: string
  status: string; answerCount: number; likeCount: number; createdAt: string
}
export interface Answer {
  id: string; questionId: string; userId: string; nickname: string; content: string
  accepted: boolean; likeCount: number; createdAt: string
}
export interface Coupon {
  id: string; name: string; stock: number; seckill: boolean; discountAmount: number; thresholdAmount: number
  startAt: string; endAt: string; useStartAt: string; useEndAt: string
  status: 'ACTIVE' | 'UPCOMING' | 'ENDED' | 'DISABLED' | 'SOLD_OUT' | 'UNAVAILABLE'
}
export interface CouponClaim { couponId: string; userId: string; claimedAt: string; status: 'RESERVED' | 'CONFIRMED' | 'REJECTED' }
export interface MyCoupon {
  claimId: string; couponId: string; name: string; type: 'NORMAL' | 'SECKILL'; discountAmount: number; thresholdAmount: number
  useStartAt: string; useEndAt: string; claimedAt: string
  status: 'AVAILABLE' | 'UPCOMING' | 'USED' | 'EXPIRED' | 'RESERVED' | 'REJECTED'
}
export interface LikeState { liked: boolean; count: number }
export interface CourseCategory {
  id: string; name: string; sortOrder: number; status: 'ENABLED' | 'DISABLED'; createdAt?: string; updatedAt?: string
}
export interface CourseTag { id: string; name: string; createdAt?: string; updatedAt?: string }
