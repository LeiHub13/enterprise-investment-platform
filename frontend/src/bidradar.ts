import type { RecordData } from './api'

export const NOTICE_KIND_LABELS: Record<string, string> = {
  opportunity: '招采机会',
  intent: '采购意向',
  result: '中标或成交',
  correction: '更正公告',
  terminated: '终止公告',
  contract: '合同公告',
  other: '类型待核实',
}

const STAGE_KIND: Record<string, string> = {
  招标公告: 'opportunity',
  采购意向: 'intent',
  更正公告: 'correction',
  中标结果: 'result',
  终止公告: 'terminated',
  合同公告: 'contract',
}

export function noticeKind(project: RecordData): string {
  const stage = String(project.stage ?? '')
  return STAGE_KIND[stage] ?? String(project.notice_kind ?? 'other')
}

export function noticeKindLabel(project: RecordData): string {
  const stage = String(project.stage ?? '')
  if (stage && stage !== '待核实') return stage
  return NOTICE_KIND_LABELS[noticeKind(project)] ?? '类型待核实'
}

export function noticeTone(project: RecordData): 'success' | 'warning' | 'danger' | 'info' | 'primary' {
  const kind = noticeKind(project)
  if (kind === 'opportunity') return 'primary'
  if (kind === 'intent') return 'warning'
  if (kind === 'result') return 'success'
  if (kind === 'terminated') return 'danger'
  return 'info'
}

export function originLabel(value: unknown): string {
  if (value === 'search') return 'DeepSeek 搜索'
  if (value === 'crawl') return '站点采集'
  if (value === 'qcc') return '企查查'
  return String(value ?? '—')
}

export function deadlineValue(project: RecordData): string {
  if (project.deadline) return String(project.deadline).replace(' ', 'T').slice(0, 16)
  if (project.official_deadline_at) return String(project.official_deadline_at).replace(' ', 'T').slice(0, 16)
  if (project.official_deadline) return String(project.official_deadline).slice(0, 10)
  return ''
}

export function deadlineText(project: RecordData): string {
  const value = deadlineValue(project)
  return value ? value.replace('T', ' ').slice(0, 16) : '待核实'
}

export function deadlineState(project: RecordData): 'expired' | 'today' | 'open' | 'unknown' {
  const raw = project.deadline
    ? String(project.deadline) + (String(project.deadline).includes('T') ? ':00+08:00' : 'T00:00:00+08:00')
    : project.official_deadline_at
      ? String(project.official_deadline_at)
      : ''
  if (!raw) return 'unknown'
  const date = new Date(raw)
  if (Number.isNaN(date.getTime())) {
    const day = String(project.official_deadline || '').slice(0, 10)
    if (!day) return 'unknown'
    const today = new Date().toISOString().slice(0, 10)
    return day < today ? 'expired' : day === today ? 'today' : 'open'
  }
  const now = Date.now()
  if (date.getTime() <= now) return 'expired'
  const today = new Date()
  const end = new Date(today.getFullYear(), today.getMonth(), today.getDate() + 1).getTime()
  return date.getTime() < end ? 'today' : 'open'
}

export function deadlineTone(project: RecordData): 'danger' | 'warning' | 'info' {
  const state = deadlineState(project)
  if (state === 'expired') return 'danger'
  if (state === 'today') return 'warning'
  return 'info'
}

export function parseAmount(text: unknown): number | null {
  if (text == null || text === '') return null
  const raw = String(text)
  const match = raw.match(/([\d,]+(?:\.\d+)?)\s*(亿|万)?/)
  if (!match) return null
  const base = Number(match[1].replace(/,/g, ''))
  if (!Number.isFinite(base)) return null
  if (match[2] === '亿') return base * 100000000
  if (match[2] === '万') return base * 10000
  return base
}
