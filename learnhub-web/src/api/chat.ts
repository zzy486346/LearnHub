import type { ApiResult } from '@/types'
import { getSessionGeneration, http, isCurrentSession } from './http'
import { SessionExpiredError } from './session'
import { consumeSseStream, type ServerSentEvent } from './chat-stream'
import type { AssistantCitation } from './assistant'

export interface ChatConversation {
  id: string
  ownerUserId: number
  courseId: number
  title: string
  nextSeq: number
}

export interface ChatMessage {
  id: string
  seq: number
  role: 'USER' | 'ASSISTANT'
  content: string
  status: string
  runId: string
  sources: AssistantCitation[]
  restricted: boolean
}

export interface ChatRun {
  id: string
  conversationId: string
  status: 'RUNNING' | 'COMPLETED' | 'FAILED' | 'CANCELLED' | 'INTERRUPTED'
}

export interface ChatStreamPayload {
  runId: string
  eventId: string
  seq: number
  modelMode?: string
  status?: string
  text?: string
  sources?: AssistantCitation[]
  messageId?: string
  toolName?: string
  toolCallId?: string
  code?: string
  message?: string
  clearDraft?: boolean
  [key: string]: unknown
}

export interface ChatStreamEvent {
  type: 'accepted' | 'status' | 'tool_start' | 'tool_end' | 'delta' | 'sources' | 'final' | 'error' | 'cancelled'
  payload: ChatStreamPayload
}

export class ChatStreamHttpError extends Error {
  constructor(public readonly status: number, message: string) {
    super(message)
    this.name = 'ChatStreamHttpError'
  }
}

function apiBaseUrl() {
  return String(http.defaults.baseURL || '/api').replace(/\/$/, '')
}

function accessToken() {
  return localStorage.getItem('learnhub_access_token')
}

function parseEvent(event: ServerSentEvent): ChatStreamEvent | null {
  const supported = new Set<ChatStreamEvent['type']>([
    'accepted', 'status', 'tool_start', 'tool_end', 'delta', 'sources', 'final', 'error', 'cancelled',
  ])
  if (!supported.has(event.event as ChatStreamEvent['type'])) return null
  const payload = JSON.parse(event.data) as ChatStreamPayload
  if (!payload || typeof payload !== 'object' || typeof payload.runId !== 'string') {
    throw new Error('CHAT_STREAM_EVENT_INVALID')
  }
  return { type: event.event as ChatStreamEvent['type'], payload }
}

export const chatApi = {
  createConversation: (body: { courseId: string | number; title: string }) =>
    http.post<ApiResult<ChatConversation>>('/assistant/conversations', body),
  conversations: (offset = 0, limit = 50) =>
    http.get<ApiResult<ChatConversation[]>>('/assistant/conversations', { params: { offset, limit } }),
  messages: (conversationId: string, beforeSeq = Number.MAX_SAFE_INTEGER, limit = 100) =>
    http.get<ApiResult<ChatMessage[]>>(`/assistant/conversations/${encodeURIComponent(conversationId)}/messages`, {
      params: { beforeSeq, limit },
    }),
  rename: (conversationId: string, title: string) =>
    http.patch<ApiResult<void>>(`/assistant/conversations/${encodeURIComponent(conversationId)}`, { title }),
  remove: (conversationId: string) =>
    http.delete<ApiResult<void>>(`/assistant/conversations/${encodeURIComponent(conversationId)}`),
  run: (runId: string) => http.get<ApiResult<ChatRun>>(`/assistant/runs/${encodeURIComponent(runId)}`),
  cancel: (runId: string) => http.post<ApiResult<ChatRun>>(`/assistant/runs/${encodeURIComponent(runId)}/cancel`),
  async streamRun(
    conversationId: string,
    body: { message: string; clientRequestId: string },
    onEvent: (event: ChatStreamEvent) => void,
    signal?: AbortSignal,
  ) {
    const sessionGeneration = getSessionGeneration()
    const token = accessToken()
    const response = await fetch(`${apiBaseUrl()}/assistant/conversations/${encodeURIComponent(conversationId)}/runs`, {
      method: 'POST',
      headers: {
        Accept: 'text/event-stream',
        'Content-Type': 'application/json',
        ...(token ? { Authorization: `Bearer ${token}` } : {}),
      },
      body: JSON.stringify(body),
      signal,
      credentials: 'same-origin',
    })
    if (!response.ok) {
      let message = `学习助理连接失败（${response.status}）`
      try {
        const result = await response.json() as { message?: string }
        if (result.message) message = result.message
      } catch { /* 非 JSON 错误响应保留状态码提示。 */ }
      throw new ChatStreamHttpError(response.status, message)
    }
    if (!response.body) throw new Error('CHAT_STREAM_BODY_MISSING')
    let terminal = false
    await consumeSseStream(response.body, (raw) => {
      if (!isCurrentSession(sessionGeneration)) throw new SessionExpiredError()
      if (terminal) return
      const event = parseEvent(raw)
      if (event) {
        onEvent(event)
        terminal = event.type === 'final' || event.type === 'error' || event.type === 'cancelled'
      }
    })
  },
}

export async function cancelRunThenAbort(runId: string, controller: AbortController) {
  try {
    await chatApi.cancel(runId)
  } finally {
    controller.abort()
  }
}
