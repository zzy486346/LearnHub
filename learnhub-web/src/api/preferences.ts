import type { ApiResult } from '@/types'
import { http } from './http'

export type PreferenceCategory = 'STYLE' | 'PACE' | 'LEVEL' | 'GOAL'
export interface LearningPreference {
  id: string
  version: number
  category: PreferenceCategory
  text: string
  status: 'PENDING' | 'ACTIVE' | 'DELETING' | 'DELETED'
  sourceConversationId: string | null
  sourceCourseId: number | null
  updatedAt: string
}
export const preferenceCategoryLabels: Record<PreferenceCategory, string> = {
  STYLE: '讲解风格', PACE: '学习节奏', LEVEL: '当前程度', GOAL: '学习目标',
}
export function preferenceStatusLabel(status: string) {
  return ({ PENDING: '待同步，暂不使用', ACTIVE: '可用于回答', DELETING: '已停止使用，待远端删除', DELETED: '已忘记' } as Record<string, string>)[status] || '状态待确认'
}
export function preferenceErrorMessage(error: unknown) {
  const code = (error as { response?: { data?: { code?: string } }; code?: string })?.response?.data?.code
    || (error as { code?: string })?.code
  if (code === 'MEMORY_DISABLED') return '学习偏好功能暂未启用。'
  if (code === 'MEMORY_LIMIT_EXCEEDED') return '最多保存50条学习偏好，请先忘记不再需要的偏好。'
  if (code === 'MEMORY_VERSION_CONFLICT' || code === 'MEMORY_CHANGED') return '偏好已变化，请刷新列表后再操作。'
  if (code === 'MEMORY_NOT_FOUND') return '该偏好已不可用，请刷新列表。'
  if (code === 'MEMORY_INPUT_INVALID') return '请填写1至200字的学习偏好，移除敏感信息和材料正文。'
  if (code === 'AUTH_SESSION_EXPIRED' || code === 'ASSISTANT_AUTH_REFRESHED') return '登录状态已变化，请重新打开后手动操作。'
  return '学习偏好操作失败，请检查网络后重试。'
}
export const preferenceApi = {
  list: () => http.get<ApiResult<LearningPreference[]>>('/assistant/memories'),
  create: (category: PreferenceCategory, text: string) =>
    http.post<ApiResult<LearningPreference>>('/assistant/memories', { category, text }),
  update: (id: string, version: number, category: PreferenceCategory, text: string) =>
    http.patch<ApiResult<LearningPreference>>(`/assistant/memories/${encodeURIComponent(id)}`, { version, category, text }),
  forget: (id: string, version: number) =>
    http.delete<ApiResult<LearningPreference>>(`/assistant/memories/${encodeURIComponent(id)}`, { params: { version } }),
}
