import { ref } from 'vue'
import { api } from './api'
import type { Value } from './api'

export type RegionLevel = 'province' | 'city' | 'district'
export type Region = { code: string; name: string; level: RegionLevel; parentCode: string | null }

export const regions = ref<Region[]>([])
let pending: Promise<void> | null = null

export function loadRegions() {
  if (regions.value.length) return Promise.resolve()
  if (!pending) {
    pending = api<Region[]>('/regions').then(data => {
      regions.value = data
    }).finally(() => { pending = null })
  }
  return pending
}

const provinceShortNames: Record<string, string> = {
  内蒙古自治区: '内蒙古',
  广西壮族自治区: '广西',
  西藏自治区: '西藏',
  宁夏回族自治区: '宁夏',
  新疆维吾尔自治区: '新疆',
  香港特别行政区: '香港',
  澳门特别行政区: '澳门',
}

function names(region: Region) {
  const result = [region.name]
  const short = provinceShortNames[region.name]
  if (short) result.push(short)
  else if (region.name.endsWith('地区')) result.push(region.name.slice(0, -2))
  else if (/[市省区县旗盟]$/.test(region.name)) result.push(region.name.slice(0, -1))
  return result
}

export function regionCode(value: Value | undefined, level: RegionLevel, parent?: Value): string | null {
  if (typeof value !== 'string' || !value.trim()) return null
  let code = value.trim()
  if (level === 'province' && /^\d{2}$/.test(code)) code += '0000'
  else if (level === 'city' && /^\d{4}$/.test(code)) code += '00'
  else if (/^(\d{9}|\d{12})$/.test(code)) code = code.slice(0, 6)
  let match = regions.value.find(region => region.code === code && region.level === level)
  if (!match && level === 'city' && /^(11|12|31|50)0000$/.test(code)) {
    match = regions.value.find(region => region.code === code.slice(0, 2) + '0100' && region.level === 'city')
  }
  if (match) return match.code
  const parentLevel = level === 'city' ? 'province' : 'city'
  const parentCode = level === 'province' ? null : regionCode(parent, parentLevel)
  const matches = regions.value.filter(region => region.level === level && names(region).includes(value.trim())
    && (!parentCode || region.parentCode === parentCode))
  return matches.length === 1 ? matches[0].code : value.trim()
}

export function regionName(value: Value | undefined, level: RegionLevel, parent?: Value) {
  if (value == null || value === '') return '—'
  const code = regionCode(value, level, parent)
  const match = regions.value.find(region => region.code === code && region.level === level)
  if (match) return match.name
  return /^\d+$/.test(String(value)) ? '地区待核实' : String(value)
}

export function regionValue(value: Value | undefined, level: RegionLevel, parent?: Value) {
  if (typeof value !== 'string' || !value.trim()) return null
  const code = regionCode(value, level, parent)
  const match = regions.value.find(region => region.code === code && region.level === level)
  if (!match) return value.trim()
  return level === 'district' ? match.code : match.name
}

export function regionOptions(level: RegionLevel, parent?: Value) {
  const parentLevel = level === 'city' ? 'province' : 'city'
  const parentCode = level === 'province' ? null : regionCode(parent, parentLevel)
  if (level === 'district' && !parentCode) return []
  return regions.value.filter(region => region.level === level && (!parentCode || region.parentCode === parentCode))
}
