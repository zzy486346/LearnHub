import type { AxiosRequestConfig } from 'axios'
import type { ApiResult } from '@/types'
import { http } from './http'

export interface AssistantAnswerSegment {
  text: string
  evidenceIds: string[]
  kind: string
}

export interface AssistantCitation {
  evidenceId: string
  kind: string
  courseId?: string | null
  lessonId?: string | null
  documentId?: string | null
  sourceVersion: number
  chunkId?: string | null
  title: string
  heading: string
  excerpt: string
  snapshot?: unknown
}

export interface AssistantUsage {
  requestId: string
  modelCalls: number
  toolDecisionRounds: number
  toolCalls: number
  reservedTokens: number
  actualPromptTokens: number
  actualCompletionTokens: number
  actualTotalTokens: number
  usageComplete: boolean
  status: string
  elapsedMillis: number
}

export interface CourseQaAnswer {
  requestId: string
  answerSegments: AssistantAnswerSegment[]
  citations: AssistantCitation[]
  insufficientEvidence: boolean
  modelMode: string
  retrievalMode: string
  promptVersion: string
  trace: string[]
  usage: AssistantUsage
}

export const assistantApi = {
  answer: (courseId: string | number, question: string, requestId: string, signal?: AbortSignal) =>
    http.post<ApiResult<CourseQaAnswer>>('/assistant/course-qa', { courseId: String(courseId), question }, {
      headers: { 'X-Assistant-Request-Id': requestId },
      signal,
      timeout: 60_000,
    } satisfies AxiosRequestConfig),
  agent: (courseId: string | number, goal: string, requestId: string, signal?: AbortSignal) =>
    http.post<ApiResult<CourseQaAnswer>>('/assistant/agent', { courseId: String(courseId), goal }, {
      headers: { 'X-Assistant-Request-Id': requestId }, signal, timeout: 60_000,
    } satisfies AxiosRequestConfig),
  cancel: (requestId: string) => http.delete<ApiResult<boolean>>(`/assistant/runs/${encodeURIComponent(requestId)}`),
}
