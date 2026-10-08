export class SessionExpiredError extends Error {
  readonly code = 'AUTH_SESSION_EXPIRED'

  constructor() {
    super('登录状态已失效，请重新登录')
    this.name = 'SessionExpiredError'
  }
}

export function isSessionExpired(error: unknown): boolean {
  return typeof error === 'object' && error !== null
    && 'code' in error && error.code === 'AUTH_SESSION_EXPIRED'
}
