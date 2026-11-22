export type Role = 'ADMIN' | 'DATA_STEWARD' | 'INVESTMENT_MANAGER' | 'INVESTMENT_OFFICER'
export type MenuItem = { key: string; label: string; group: string }
export type User = {
  id: number
  username: string
  role: Role
  enabled?: boolean
  permissions?: string[]
  menus?: MenuItem[]
}
export type Value = string | number | boolean | null
export type RecordData = { id: number } & Record<string, Value>
export type Page<T> = { items: T[]; total: number }

const TOKEN_KEY = 'enterprise-investment-token'

export function getToken(): string | null {
  return sessionStorage.getItem(TOKEN_KEY)
}

export function setToken(token: string | null) {
  if (token) sessionStorage.setItem(TOKEN_KEY, token)
  else sessionStorage.removeItem(TOKEN_KEY)
}

export function clearToken() {
  sessionStorage.removeItem(TOKEN_KEY)
}

export class ApiError extends Error {
  constructor(readonly status: number, message: string) {
    super(message)
  }
}

export async function api<T>(path: string, method = 'GET', body?: unknown): Promise<T> {
  const headers = new Headers({ Accept: 'application/json' })
  const token = getToken()
  if (token) {
    headers.set('Authorization', 'Bearer ' + token)
  } else if (method !== 'GET') {
    const csrf = await api<{ headerName: string; token: string }>('/auth/csrf')
    headers.set(csrf.headerName, csrf.token)
  }
  if (body !== undefined && !(body instanceof FormData)) {
    headers.set('Content-Type', 'application/json')
  }
  const response = await fetch(`/api${path}`, {
    method,
    headers,
    credentials: 'same-origin',
    body: body instanceof FormData ? body : JSON.stringify(body),
  })
  if (!response.ok) {
    const error = await response.json().catch(() => ({}))
    const fallback = response.status === 401 ? '请检查账号密码或重新登录' :
      response.status === 403 ? '当前账号没有此操作权限' : `请求失败（${response.status}）`
    throw new ApiError(response.status, error.detail || error.message || error.error || fallback)
  }
  if (response.status === 204) return undefined as T
  return response.json() as Promise<T>
}

export function query(values: Record<string, Value>): string {
  const params = new URLSearchParams()
  for (const [key, value] of Object.entries(values)) {
    if (value !== null && value !== '') params.set(key, String(value))
  }
  return `?${params}`
}
