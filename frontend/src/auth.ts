import { reactive } from 'vue'
import { api, ApiError, clearToken, getToken } from './api'
import type { User } from './api'
import { hasPermission, permissionsForRole } from './permissions'

export const auth = reactive<{ user: User | null; loaded: boolean; loading: boolean }>({
  user: null,
  loaded: false,
  loading: false,
})

let pending: Promise<User | null> | null = null

export function setUser(user: User | null) {
  auth.user = user
  auth.loaded = true
}

export function permitted(code: string) {
  const permissions = new Set(auth.user?.permissions?.length
    ? auth.user.permissions
    : permissionsForRole(auth.user?.role))
  return hasPermission(permissions, code)
}

export function loadUser(force = false): Promise<User | null> {
  if (pending) return pending
  if (auth.loaded && !force) return Promise.resolve(auth.user)
  if (!getToken()) {
    setUser(null)
    return Promise.resolve(null)
  }
  pending = (async () => {
    auth.loading = true
    try {
      const user = await api<User>('/auth/me')
      setUser(user)
      return user
    } catch (error) {
      if (error instanceof ApiError && error.status === 401) clearToken()
      setUser(null)
      return null
    } finally {
      auth.loading = false
      pending = null
    }
  })()
  return pending
}
