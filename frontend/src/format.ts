import type { Value } from './api'

export function money(value: unknown): string {
  if (value == null || value === '') return '—'
  const [integer, fraction] = String(value).split('.')
  const grouped = integer.replace(/\B(?=(\d{3})+(?!\d))/g, ',')
  return fraction === undefined ? grouped : grouped + '.' + fraction
}

export function dateText(value: unknown, withTime = false): string {
  if (typeof value !== 'string' || !value) return '—'
  return value.replace('T', ' ').slice(0, withTime ? 19 : 10)
}

export function text(value: Value | undefined): string {
  return value == null || value === '' ? '—' : String(value)
}

export function isOverdue(status: unknown, planDate: unknown): boolean {
  const now = new Date()
  const today = `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}-${String(now.getDate()).padStart(2, '0')}`
  return status === 'PENDING' && typeof planDate === 'string' && planDate.slice(0, 10) < today
}
