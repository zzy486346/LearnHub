export class SessionExpiredError extends Error {
  readonly code = 'AUTH_SESSION_EXPIRED'

  constructor() {
    super('登录状态已失效，请重新登录')
    this.name = 'SessionExpiredError'
  }
}

export class AssistantRetryRequiredError extends Error {
  readonly code = 'ASSISTANT_AUTH_REFRESHED'

  constructor() {
    super('登录状态已更新，请再次提交本次问答')
    this.name = 'AssistantRetryRequiredError'
  }
}

export function isSessionExpired(error: unknown): boolean {
  return typeof error === 'object' && error !== null
    && 'code' in error && error.code === 'AUTH_SESSION_EXPIRED'
}

export function isAssistantRetryRequired(error: unknown): boolean {
  return typeof error === 'object' && error !== null
    && 'code' in error && error.code === 'ASSISTANT_AUTH_REFRESHED'
}
