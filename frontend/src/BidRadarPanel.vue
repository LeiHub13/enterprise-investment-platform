<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Link, Plus, Refresh, Search } from '@element-plus/icons-vue'
import { api, ApiError } from './api'
import type { Page, RecordData } from './api'
import BidNoticeTable from './BidNoticeTable.vue'
import RegionSelect from './RegionSelect.vue'
import { loadRegions, regionCode } from './regions'
import type { Value } from './api'
import { dateText } from './format'
import {
  NOTICE_KIND_LABELS, deadlineState, deadlineValue, noticeKind, originLabel, parseAmount,
} from './bidradar'

const props = defineProps<{
  canCreateLead: boolean
  canCreateBusiness: boolean
  canAssign: boolean
  canUseQcc: boolean
}>()
const emit = defineEmits<{ expired: [error: unknown] }>()

type Option = { value: number; label: string }
type BidTopic = { id: number; name: string; keywords: string; excludes: string; region: string; created_at: string }
type BidBuyer = { id: number; name: string; followed: boolean; project_count: number }
type BidMatch = { topic_id: number; project_id: number; reason: string; score: number }
type BidState = {
  topics: BidTopic[]
  projects: RecordData[]
  buyers: BidBuyer[]
  matches: BidMatch[]
  config: { has_key: boolean; has_qcc_key?: boolean; interval_hours?: number; official_only?: boolean }
}
type TabKey = 'projects' | 'intents' | 'favorites' | 'topics' | 'buyers'

const STAGES = ['待核实', '招标公告', '采购意向', '更正公告', '中标结果', '终止公告', '合同公告']
const FOLLOWUPS = ['待研究', '准备参与', '已参与', '不感兴趣']
const kindOptions = Object.entries(NOTICE_KIND_LABELS).map(([value, label]) => ({ value, label }))
const deadlineOptions = [
  { value: 'open', label: '截止未到' },
  { value: 'today', label: '今日截止' },
  { value: 'unknown', label: '截止待核实' },
  { value: 'expired', label: '已截止' },
]
const originOptions = [
  { value: 'all', label: '全部来源' },
  { value: 'search', label: 'DeepSeek 搜索' },
  { value: 'crawl', label: '站点采集' },
  { value: 'qcc', label: '企查查' },
]

const state = ref<BidState | null>(null)
const loading = ref(false)
const error = ref('')
const activeTab = ref<TabKey>('projects')
const keyword = ref('')
const topicFilter = ref<number | null>(null)
const kindFilter = ref<string | null>(null)
const deadlineFilter = ref<string | null>(null)
const originFilter = ref('all')
const showNoise = ref(false)
const sortBy = ref<'recent' | 'published' | 'deadline' | 'score'>('recent')
const sortOrder = ref<'desc' | 'asc'>('desc')
const page = ref(1)
const pageSize = 20
const scanningId = ref<number | null>(null)
const crawlingId = ref<number | null>(null)
const intentScanning = ref(false)
const intentTopicId = ref<number | null>(null)
const qccOpen = ref(false)
const qccBusy = ref(false)
const qccError = ref('')
const qccResult = ref('')
const qccForm = ref<{ topicId: number | null; keyword: string; province: Value; city: Value; msgType: string; page: number }>(
  { topicId: null, keyword: '', province: null, city: null, msgType: '', page: 1 },
)

const topicDialog = ref(false)
const topicSaving = ref(false)
const topicError = ref('')
const topicEditingId = ref<number | null>(null)
const topicForm = ref({ name: '', keywords: '', excludes: '', region: '' })

const detailOpen = ref(false)
const detailProject = ref<RecordData | null>(null)
const detailSaving = ref(false)
const detailFetching = ref(false)
const detailError = ref('')
const detailForm = ref({ amount: '', deadline: null as string | null, stage: '待核实', followup: '待研究', notes: '', buyerName: '' })

const convertOpen = ref(false)
const convertTarget = ref<'lead' | 'business'>('lead')
const convertProject = ref<RecordData | null>(null)
const convertSaving = ref(false)
const convertError = ref('')
const convertForm = ref({ customerId: null as number | null, projectId: null as number | null, ownerId: null as number | null, amount: null as number | null, nextFollowupAt: null as string | null, note: '' })
const customers = ref<Option[]>([])
const platformProjects = ref<Option[]>([])
const owners = ref<Option[]>([])
const convertOptionsLoaded = ref(false)

const buyerDraft = ref('')
const buyerSaving = ref(false)
const buyerProjectsOpen = ref(false)
const buyerProjectsLoading = ref(false)
const buyerProjects = ref<RecordData[]>([])
const buyerSelected = ref<BidBuyer | null>(null)

const topics = computed(() => state.value?.topics ?? [])
const projects = computed(() => state.value?.projects ?? [])
const buyers = computed(() => state.value?.buyers ?? [])
const matches = computed(() => state.value?.matches ?? [])
const busy = computed(() => scanningId.value !== null || crawlingId.value !== null || intentScanning.value || qccBusy.value)
const hasKey = computed(() => Boolean(state.value?.config?.has_key))

const topicNames = computed(() => {
  const names = new Map(topics.value.map(topic => [Number(topic.id), topic.name]))
  const result: Record<number, string[]> = {}
  for (const match of matches.value) {
    const name = names.get(Number(match.topic_id))
    if (!name) continue
    ;(result[Number(match.project_id)] ??= []).push(name)
  }
  return result
})

const topicProjectIds = computed(() => {
  if (topicFilter.value == null) return null
  return new Set(matches.value.filter(match => Number(match.topic_id) === topicFilter.value)
    .map(match => Number(match.project_id)))
})

const projectScores = computed(() => {
  const scores = new Map<number, number>()
  for (const match of matches.value) {
    const projectId = Number(match.project_id)
    scores.set(projectId, Math.max(scores.get(projectId) ?? 0, Number(match.score ?? 0)))
  }
  return scores
})

function timestamp(value: unknown): number {
  if (typeof value !== 'string' || !value) return 0
  const parsed = Date.parse(value)
  return Number.isNaN(parsed) ? 0 : parsed
}

function compareNullable(a: number, b: number, direction: number): number {
  if (!a && !b) return 0
  if (!a) return 1
  if (!b) return -1
  return (a - b) * direction
}

function sortProjects(list: RecordData[]): RecordData[] {
  const direction = sortOrder.value === 'asc' ? 1 : -1
  return list.slice().sort((a, b) => {
    if (sortBy.value === 'score') {
      return (((projectScores.value.get(Number(a.id)) ?? 0) - (projectScores.value.get(Number(b.id)) ?? 0)) * direction)
    }
    if (sortBy.value === 'deadline') {
      return compareNullable(timestamp(deadlineValue(a)), timestamp(deadlineValue(b)), direction)
    }
    if (sortBy.value === 'published') {
      return compareNullable(
        timestamp(a.published) || timestamp(a.first_seen_at) || timestamp(a.fetched_at),
        timestamp(b.published) || timestamp(b.first_seen_at) || timestamp(b.fetched_at),
        direction,
      )
    }
    return compareNullable(
      timestamp(a.last_seen_at) || timestamp(a.first_seen_at) || timestamp(a.fetched_at),
      timestamp(b.last_seen_at) || timestamp(b.first_seen_at) || timestamp(b.fetched_at),
      direction,
    )
  })
}

const filteredProjects = computed(() => {
  const word = keyword.value.trim().toLowerCase()
  const items = projects.value
    .filter(project => showNoise.value || (project.is_notice !== 0 && project.demo !== 1))
    .filter(project => {
      if (activeTab.value === 'intents' && noticeKind(project) !== 'intent') return false
      if (activeTab.value === 'favorites' && !project.favorite) return false
      if (topicProjectIds.value && !topicProjectIds.value.has(Number(project.id))) return false
      if (activeTab.value === 'projects' && kindFilter.value && noticeKind(project) !== kindFilter.value) return false
      if (deadlineFilter.value && deadlineState(project) !== deadlineFilter.value) return false
      if (originFilter.value === 'qcc' && !project.qcc_available && project.origin !== 'qcc') return false
      if (originFilter.value !== 'all' && originFilter.value !== 'qcc' && project.origin !== originFilter.value) return false
      if (word) {
        const haystack = [project.title, project.buyer_name, project.source, project.notes, project.amount, project.stage]
          .map(value => String(value ?? '').toLowerCase())
        if (!haystack.some(value => value.includes(word))) return false
      }
      return true
    })
  return sortProjects(items)
})

const totalPages = computed(() => Math.max(1, Math.ceil(filteredProjects.value.length / pageSize)))
const pagedProjects = computed(() => filteredProjects.value.slice((page.value - 1) * pageSize, page.value * pageSize))

const stats = computed(() => {
  const total = projects.value.length
  const opportunities = projects.value.filter(project => project.is_notice !== 0 && noticeKind(project) === 'opportunity').length
  const intents = projects.value.filter(project => project.is_notice !== 0 && noticeKind(project) === 'intent').length
  const favorites = projects.value.filter(project => project.favorite).length
  return { total, opportunities, intents, favorites, topics: topics.value.length }
})

watch([keyword, topicFilter, kindFilter, deadlineFilter, originFilter, showNoise, activeTab, sortBy, sortOrder], () => {
  page.value = 1
})

watch(sortBy, value => {
  sortOrder.value = value === 'deadline' ? 'asc' : 'desc'
})

function reasonText(reason: unknown) {
  if (reason instanceof ApiError && reason.status === 401) emit('expired', reason)
  if (reason instanceof ApiError && reason.status === 502) return '标讯雷达服务未启动，请先运行 BidRadar 的 app.py'
  return reason instanceof Error ? reason.message : '操作失败，请重试'
}

function showActionError(reason: unknown) {
  ElMessage.error(reasonText(reason))
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    state.value = await api<BidState>('/bid-radar/state')
  } catch (reason) {
    error.value = reasonText(reason)
  } finally {
    loading.value = false
  }
}

function topicProjectCount(topicId: number) {
  return matches.value.filter(match => Number(match.topic_id) === topicId).length
}

function changePage(value: number) {
  page.value = value
}

function resetFilters() {
  keyword.value = ''
  topicFilter.value = null
  kindFilter.value = null
  deadlineFilter.value = null
  originFilter.value = 'all'
  showNoise.value = false
}

function openTopicCreate() {
  topicEditingId.value = null
  topicForm.value = { name: '', keywords: '', excludes: '', region: '' }
  topicError.value = ''
  topicDialog.value = true
}

function openTopicEdit(topic: BidTopic) {
  topicEditingId.value = topic.id
  topicForm.value = {
    name: topic.name, keywords: topic.keywords, excludes: topic.excludes, region: topic.region,
  }
  topicError.value = ''
  topicDialog.value = true
}

async function saveTopic() {
  if (topicSaving.value) return
  if (!topicForm.value.name.trim() || !topicForm.value.keywords.trim()) {
    topicError.value = '请填写主题名称和至少一个关键词'
    return
  }
  topicSaving.value = true
  topicError.value = ''
  try {
    await api('/bid-radar/topics', 'POST', {
      ...(topicEditingId.value == null ? {} : { id: topicEditingId.value }),
      name: topicForm.value.name.trim(),
      keywords: topicForm.value.keywords.trim(),
      excludes: topicForm.value.excludes.trim(),
      region: topicForm.value.region.trim(),
    })
    topicDialog.value = false
    ElMessage.success('主题已保存')
    await load()
  } catch (reason) {
    if (reason instanceof ApiError && reason.status === 401) emit('expired', reason)
    topicError.value = reasonText(reason)
  } finally {
    topicSaving.value = false
  }
}

async function removeTopic(topic: BidTopic) {
  try {
    await ElMessageBox.confirm(`确定删除主题「${topic.name}」吗？公告和收藏不会被删除。`, '删除主题', {
      type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消',
    })
  } catch {
    return
  }
  try {
    await api('/bid-radar/topics/delete', 'POST', { id: topic.id })
    ElMessage.success('主题已删除')
    await load()
  } catch (reason) {
    showActionError(reason)
  }
}

async function scan(topic: BidTopic) {
  if (busy.value) return
  scanningId.value = topic.id
  try {
    await api('/bid-radar/scan', 'POST', { id: topic.id })
    ElMessage.success(`主题「${topic.name}」搜索完成`)
    await load()
  } catch (reason) {
    showActionError(reason)
  } finally {
    scanningId.value = null
  }
}

async function crawl(topic: BidTopic) {
  if (busy.value) return
  crawlingId.value = topic.id
  try {
    await api('/bid-radar/crawl', 'POST', { id: topic.id })
    ElMessage.success(`主题「${topic.name}」站点采集完成`)
    await load()
  } catch (reason) {
    showActionError(reason)
  } finally {
    crawlingId.value = null
  }
}

function openQcc(topic: BidTopic) {
  qccForm.value = { topicId: topic.id, keyword: topic.keywords.split(/[,，;；\n]/)[0]?.trim() ?? '',
    province: null, city: null, msgType: '', page: 1 }
  qccError.value = ''
  qccResult.value = ''
  qccOpen.value = true
  void loadRegions().catch(() => { qccError.value = '地区选项加载失败，请刷新后重试。' })
}

async function searchQcc() {
  if (qccBusy.value || !qccForm.value.topicId) return
  const form = qccForm.value
  const area = form.city ? regionCode(form.city, 'city', form.province) : regionCode(form.province, 'province')
  if (area && !/^\d{6}$/.test(area)) {
    qccError.value = '请从列表中选择有效的省份或城市。'
    return
  }
  qccBusy.value = true
  qccError.value = ''
  qccResult.value = ''
  try {
    const result = await api<{ returned: number; count: number; new: number; total: number; page: number; missing_url: number; unmatched: number }>(
      '/bid-radar/qcc/search', 'POST', { id: form.topicId, keyword: form.keyword.trim(),
        area_code: area ?? '', msg_type: form.msgType, page_index: form.page },
    )
    qccResult.value = `第 ${result.page} 页返回 ${result.returned} 条，匹配主题 ${result.count} 条，新增 ${result.new} 条；无原文链接 ${result.missing_url} 条，未匹配主题 ${result.unmatched} 条。`
    await load()
  } catch (reason) {
    if (reason instanceof ApiError && reason.status === 401) emit('expired', reason)
    qccError.value = reason instanceof Error ? reason.message : '企查查查询失败，请稍后重试。'
  } finally {
    qccBusy.value = false
  }
}

async function scanIntents() {
  if (!intentTopicId.value || intentScanning.value) return
  intentScanning.value = true
  try {
    await api('/bid-radar/scan/intents', 'POST', { id: intentTopicId.value })
    ElMessage.success('采购意向搜索完成')
    await load()
  } catch (reason) {
    showActionError(reason)
  } finally {
    intentScanning.value = false
  }
}

function openDetail(project: RecordData) {
  detailProject.value = { ...project }
  detailForm.value = {
    amount: String(project.amount ?? ''),
    deadline: deadlineValue(project) || null,
    stage: String(project.stage ?? '待核实'),
    followup: String(project.followup ?? '待研究'),
    notes: String(project.notes ?? ''),
    buyerName: String(project.buyer_name ?? ''),
  }
  detailError.value = ''
  detailOpen.value = true
}

async function saveDetail() {
  if (!detailProject.value || detailSaving.value) return
  detailSaving.value = true
  detailError.value = ''
  try {
    const payload: Record<string, unknown> = {
      id: detailProject.value.id,
      amount: detailForm.value.amount,
      deadline: detailForm.value.deadline || '',
      stage: detailForm.value.stage,
      followup: detailForm.value.followup,
      notes: detailForm.value.notes,
    }
    if (detailForm.value.buyerName.trim() !== String(detailProject.value.buyer_name ?? '')) {
      payload.buyer_name = detailForm.value.buyerName.trim()
    }
    await api('/bid-radar/projects', 'POST', payload)
    Object.assign(detailProject.value, payload, { buyer_name: detailForm.value.buyerName.trim() })
    const row = projects.value.find(item => item.id === detailProject.value?.id)
    if (row) Object.assign(row, detailProject.value)
    detailOpen.value = false
    ElMessage.success('核验与跟进信息已保存')
  } catch (reason) {
    if (reason instanceof ApiError && reason.status === 401) emit('expired', reason)
    detailError.value = reasonText(reason)
  } finally {
    detailSaving.value = false
  }
}

async function fetchContent() {
  if (!detailProject.value || detailFetching.value) return
  detailFetching.value = true
  try {
    const id = detailProject.value.id
    await api('/bid-radar/fetch', 'POST', { id })
    await load()
    const updated = projects.value.find(item => item.id === id)
    if (updated) detailProject.value = { ...updated }
    ElMessage.success('正文已更新，金额和截止时间仍需核验')
  } catch (reason) {
    showActionError(reason)
  } finally {
    detailFetching.value = false
  }
}

async function toggleFavorite(project: RecordData) {
  try {
    const next = project.favorite ? 0 : 1
    await api('/bid-radar/projects', 'POST', { id: project.id, favorite: next })
    project.favorite = next
    if (detailProject.value?.id === project.id) detailProject.value.favorite = next
  } catch (reason) {
    showActionError(reason)
  }
}

function openOriginal(url: unknown) {
  if (typeof url !== 'string' || !url) {
    ElMessage.warning('该公告没有可用原文链接')
    return
  }
  window.open(url, '_blank', 'noopener,noreferrer')
}

async function loadConvertOptions() {
  if (convertOptionsLoaded.value) return
  const [customerPage, projectPage] = await Promise.all([
    api<Page<RecordData>>('/enterprises?page=1&size=100'),
    api<Page<RecordData>>('/projects?page=1&size=100'),
  ])
  customers.value = customerPage.items.map(row => ({ value: Number(row.id), label: String(row.name ?? row.id) }))
  platformProjects.value = projectPage.items.map(row => ({ value: Number(row.id), label: String(row.projectName ?? row.id) }))
  if (props.canAssign) {
    const users = await api<RecordData[]>('/users')
    owners.value = users.filter(user => user.enabled !== false && user.role !== 'DATA_STEWARD')
      .map(user => ({ value: Number(user.id), label: String(user.username) }))
  }
  convertOptionsLoaded.value = true
}

function defaultFollowup(project: RecordData): string | null {
  const raw = deadlineValue(project)
  if (!raw) return null
  const date = new Date(raw)
  if (Number.isNaN(date.getTime())) return null
  date.setDate(date.getDate() - 2)
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}T09:00`
}

async function openConvert(project: RecordData, target: 'lead' | 'business') {
  convertProject.value = { ...project }
  convertTarget.value = target
  convertError.value = ''
  try {
    await loadConvertOptions()
  } catch (reason) {
    showActionError(reason)
    return
  }
  const buyer = String(project.buyer_name ?? '').trim()
  const exact = customers.value.find(option => option.label === buyer)
  const fuzzy = !exact && buyer
    ? customers.value.find(option => option.label.includes(buyer) || buyer.includes(option.label))
    : null
  convertForm.value = {
    customerId: (exact ?? fuzzy)?.value ?? null,
    projectId: null,
    ownerId: null,
    amount: target === 'business' ? parseAmount(project.amount) : null,
    nextFollowupAt: target === 'lead' ? defaultFollowup(project) : null,
    note: '',
  }
  convertOpen.value = true
}

async function saveConvert() {
  if (!convertProject.value || convertSaving.value) return
  if (!convertForm.value.customerId) {
    convertError.value = '请选择客户'
    return
  }
  convertSaving.value = true
  convertError.value = ''
  const base = {
    customerId: convertForm.value.customerId,
    projectId: convertForm.value.projectId,
    ownerId: convertForm.value.ownerId,
    sourceUrl: convertProject.value.url,
    sourceTitle: convertProject.value.title,
  }
  try {
    if (convertTarget.value === 'lead') {
      await api('/bid-radar/convert/lead', 'POST', {
        ...base, nextFollowupAt: convertForm.value.nextFollowupAt,
      })
      ElMessage.success('已转为招商线索')
    } else {
      await api('/bid-radar/convert/business', 'POST', {
        ...base, amount: convertForm.value.amount, note: convertForm.value.note.trim() || null,
      })
      ElMessage.success('已转为商机')
    }
    convertOpen.value = false
    detailOpen.value = false
  } catch (reason) {
    if (reason instanceof ApiError && reason.status === 401) emit('expired', reason)
    convertError.value = reasonText(reason)
  } finally {
    convertSaving.value = false
  }
}

async function addBuyer() {
  const name = buyerDraft.value.trim()
  if (!name || buyerSaving.value) return
  buyerSaving.value = true
  try {
    await api('/bid-radar/buyers', 'POST', { name })
    buyerDraft.value = ''
    ElMessage.success('已关注采购人')
    await load()
  } catch (reason) {
    showActionError(reason)
  } finally {
    buyerSaving.value = false
  }
}

async function toggleBuyer(buyer: BidBuyer) {
  try {
    await api('/bid-radar/buyers', 'POST', { id: buyer.id, followed: !buyer.followed })
    buyer.followed = !buyer.followed
  } catch (reason) {
    showActionError(reason)
  }
}

function openBuyerProjectDetail(project: RecordData) {
  buyerProjectsOpen.value = false
  openDetail(project)
}

async function openBuyerProjects(buyer: BidBuyer) {
  buyerSelected.value = buyer
  buyerProjects.value = []
  buyerProjectsOpen.value = true
  buyerProjectsLoading.value = true
  try {
    const result = await api<{ projects: RecordData[]; next_before: number | null }>(
      '/bid-radar/buyers/projects?buyer=' + buyer.id,
    )
    buyerProjects.value = result.projects ?? []
  } catch (reason) {
    showActionError(reason)
    buyerProjectsOpen.value = false
  } finally {
    buyerProjectsLoading.value = false
  }
}

onMounted(() => void load())
</script>

<template>
  <div class="page-header">
    <div class="page-header-copy">
      <div class="page-header-title">
        <span class="page-header-glyph">标</span>
        <h1>标讯雷达</h1>
      </div>
      <p>按关注主题发现招标公告、采购意向和采购人，一键转为 CRM 线索或商机。</p>
    </div>
    <div class="page-actions">
      <el-tag v-if="state" :type="hasKey ? 'success' : 'warning'" effect="plain">
        {{ hasKey ? 'DeepSeek 已配置' : '未配置 DeepSeek，仅站点采集可用' }}
      </el-tag>
      <el-button :loading="loading" @click="load"><el-icon><Refresh /></el-icon>刷新</el-button>
    </div>
  </div>

  <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon class="bid-error" />

  <div v-loading="loading" class="bid-body">
    <template v-if="state">
      <div class="bid-stats">
        <article class="bid-stat"><small>公告总数</small><strong>{{ stats.total }}</strong><em>已收录公告</em></article>
        <article class="bid-stat"><small>招采机会</small><strong>{{ stats.opportunities }}</strong><em>可跟进的采购机会</em></article>
        <article class="bid-stat"><small>采购意向</small><strong>{{ stats.intents }}</strong><em>提前触达采购人</em></article>
        <article class="bid-stat"><small>收藏</small><strong>{{ stats.favorites }}</strong><em>已收藏的公告</em></article>
        <article class="bid-stat"><small>关注主题</small><strong>{{ stats.topics }}</strong><em>主题规则</em></article>
      </div>

      <el-tabs v-model="activeTab" class="bid-tabs">
        <el-tab-pane label="发现项目" name="projects">
          <section class="list-card topic-quick">
            <div class="topic-quick-heading">
              <div><strong>关注主题</strong><p>搜索消耗 DeepSeek 额度，采集直接读取官方栏目页。</p></div>
              <el-button size="small" @click="openTopicCreate"><el-icon><Plus /></el-icon>新建主题</el-button>
            </div>
            <div v-if="topics.length" class="topic-chips">
              <div v-for="topic in topics" :key="topic.id" class="topic-chip">
                <button type="button" class="topic-chip-name" @click="topicFilter = topic.id">
                  {{ topic.name }} <em>{{ topicProjectCount(topic.id) }}</em>
                </button>
                <el-button link type="primary" size="small" :loading="scanningId === topic.id"
                  :disabled="busy && scanningId !== topic.id" @click="scan(topic)">搜索</el-button>
                <el-button link size="small" :loading="crawlingId === topic.id"
                  :disabled="busy && crawlingId !== topic.id" @click="crawl(topic)">采集</el-button>
                <el-button v-if="canUseQcc" link size="small" :disabled="busy" @click="openQcc(topic)">企查查</el-button>
              </div>
            </div>
            <p v-else class="muted">还没有关注主题，先新建一个主题再搜索。</p>
          </section>

          <section class="list-card bid-list">
            <el-form class="filters" inline @submit.prevent>
              <el-form-item>
                <el-input v-model="keyword" placeholder="搜索标题、采购人、备注" clearable style="width: 220px" />
              </el-form-item>
              <el-form-item>
                <el-select v-model="topicFilter" placeholder="全部主题" clearable :value-on-clear="null" style="width: 150px">
                  <el-option v-for="topic in topics" :key="topic.id" :value="topic.id" :label="topic.name" />
                </el-select>
              </el-form-item>
              <el-form-item>
                <el-select v-model="kindFilter" placeholder="公告类型" clearable :value-on-clear="null" style="width: 130px">
                  <el-option v-for="option in kindOptions" :key="option.value" v-bind="option" />
                </el-select>
              </el-form-item>
              <el-form-item>
                <el-select v-model="deadlineFilter" placeholder="截止状态" clearable :value-on-clear="null" style="width: 120px">
                  <el-option v-for="option in deadlineOptions" :key="option.value" v-bind="option" />
                </el-select>
              </el-form-item>
              <el-form-item>
                <el-select v-model="originFilter" style="width: 130px">
                  <el-option v-for="option in originOptions" :key="option.value" v-bind="option" />
                </el-select>
              </el-form-item>
              <el-form-item>
                <el-select v-model="sortBy" style="width: 125px">
                  <el-option value="recent" label="最近收录" />
                  <el-option value="published" label="公告日期" />
                  <el-option value="deadline" label="截止时间" />
                  <el-option value="score" label="相关度" />
                </el-select>
              </el-form-item>
              <el-form-item>
                <el-button @click="sortOrder = sortOrder === 'desc' ? 'asc' : 'desc'">
                  {{ sortOrder === 'desc' ? '倒序' : '正序' }}
                </el-button>
              </el-form-item>
              <el-form-item>
                <el-checkbox v-model="showNoise">显示疑似非公告/演示</el-checkbox>
              </el-form-item>
              <el-form-item>
                <el-button @click="resetFilters">重置</el-button>
              </el-form-item>
            </el-form>
            <BidNoticeTable :projects="pagedProjects" :topic-names="topicNames" :loading="loading"
              @detail="openDetail" @favorite="toggleFavorite" />
            <div class="pagination">
              <el-pagination :current-page="page" :page-size="pageSize" :total="filteredProjects.length"
                layout="total, prev, pager, next" background @current-change="changePage" />
            </div>
          </section>
        </el-tab-pane>

        <el-tab-pane label="采购意向" name="intents">
          <section class="list-card intent-search">
            <div>
              <strong>采购意向搜索</strong>
              <p>按主题搜索中国政府采购网意向原文；正式招采安排以随后发布的公告为准。</p>
            </div>
            <div class="intent-actions">
              <el-select v-model="intentTopicId" placeholder="选择主题" style="width: 180px">
                <el-option v-for="topic in topics" :key="topic.id" :value="topic.id" :label="topic.name" />
              </el-select>
              <el-button type="primary" :loading="intentScanning" :disabled="!hasKey || !intentTopicId || intentScanning"
                @click="scanIntents"><el-icon><Search /></el-icon>搜索采购意向</el-button>
            </div>
          </section>
          <section class="list-card bid-list">
            <BidNoticeTable :projects="pagedProjects" :topic-names="topicNames" :loading="loading"
              empty-text="暂无采购意向" @detail="openDetail" @favorite="toggleFavorite" />
            <div class="pagination">
              <el-pagination :current-page="page" :page-size="pageSize" :total="filteredProjects.length"
                layout="total, prev, pager, next" background @current-change="changePage" />
            </div>
          </section>
        </el-tab-pane>

        <el-tab-pane label="收藏与跟进" name="favorites">
          <section class="list-card bid-list">
            <BidNoticeTable :projects="pagedProjects" :topic-names="topicNames" :loading="loading"
              empty-text="暂无收藏，打开公告详情后点收藏" @detail="openDetail" @favorite="toggleFavorite" />
            <div class="pagination">
              <el-pagination :current-page="page" :page-size="pageSize" :total="filteredProjects.length"
                layout="total, prev, pager, next" background @current-change="changePage" />
            </div>
          </section>
        </el-tab-pane>

        <el-tab-pane label="关注主题" name="topics">
          <section class="list-card">
            <div class="topic-quick-heading">
              <div><strong>关注主题</strong><p>主题规则用于搜索和采集后的匹配，修改后会重新计算已有公告的命中。</p></div>
              <el-button size="small" type="primary" @click="openTopicCreate"><el-icon><Plus /></el-icon>新建主题</el-button>
            </div>
            <el-table :data="topics" row-key="id" empty-text="暂无主题">
              <el-table-column prop="name" label="主题" min-width="150" />
              <el-table-column prop="keywords" label="关键词" min-width="220" show-overflow-tooltip />
              <el-table-column prop="region" label="地区加分" min-width="120">
                <template #default="{ row }">{{ row.region || '—' }}</template>
              </el-table-column>
              <el-table-column label="命中公告" min-width="100">
                <template #default="{ row }">{{ topicProjectCount(row.id) }}</template>
              </el-table-column>
              <el-table-column label="操作" min-width="260" fixed="right">
                <template #default="{ row }">
                  <el-button link type="primary" :loading="scanningId === row.id" :disabled="busy"
                    @click="scan(row)">搜索</el-button>
                  <el-button link :loading="crawlingId === row.id" :disabled="busy" @click="crawl(row)">采集</el-button>
                  <el-button v-if="canUseQcc" link :disabled="busy" @click="openQcc(row)">企查查</el-button>
                  <el-button link type="primary" @click="openTopicEdit(row)">编辑</el-button>
                  <el-button link type="danger" @click="removeTopic(row)">删除</el-button>
                </template>
              </el-table-column>
            </el-table>
          </section>
        </el-tab-pane>

        <el-tab-pane label="关注采购人" name="buyers">
          <section class="list-card buyer-card">
            <div class="topic-quick-heading">
              <div><strong>关注采购人</strong><p>关注后可按采购人汇总已收录公告；目前没有采购人时可先添加。</p></div>
              <div class="buyer-add">
                <el-input v-model="buyerDraft" placeholder="采购人名称" clearable style="width: 220px" @keyup.enter="addBuyer" />
                <el-button type="primary" :loading="buyerSaving" @click="addBuyer">关注</el-button>
              </div>
            </div>
            <el-table :data="buyers" row-key="id" empty-text="暂无关注采购人">
              <el-table-column prop="name" label="采购人" min-width="220" />
              <el-table-column label="收录公告" min-width="110">
                <template #default="{ row }">{{ row.project_count ?? 0 }}</template>
              </el-table-column>
              <el-table-column label="关注" min-width="90">
                <template #default="{ row }">
                  <el-tag :type="row.followed ? 'success' : 'info'" effect="plain">{{ row.followed ? '已关注' : '未关注' }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column label="操作" min-width="170" fixed="right">
                <template #default="{ row }">
                  <el-button link type="primary" @click="openBuyerProjects(row)">查看公告</el-button>
                  <el-button link @click="toggleBuyer(row)">{{ row.followed ? '取消关注' : '关注' }}</el-button>
                </template>
              </el-table-column>
            </el-table>
          </section>
        </el-tab-pane>
      </el-tabs>
    </template>
  </div>

  <el-dialog v-model="qccOpen" title="企查查招投标查询" width="min(620px, 94vw)"
    :close-on-click-modal="false" :close-on-press-escape="!qccBusy" :show-close="!qccBusy">
    <el-alert title="每次点击查询会消耗 1 次企查查接口额度；不会自动查询或翻页。" type="info" :closable="false" show-icon />
    <el-alert v-if="!state?.config.has_qcc_key" title="请先配置企查查 AppKey 和 SecretKey，并重启标讯雷达。" type="warning" :closable="false" show-icon />
    <el-form label-position="top" style="margin-top: 16px" @submit.prevent="searchQcc">
      <el-form-item label="关键词" required><el-input v-model="qccForm.keyword" maxlength="200" /></el-form-item>
      <el-form-item label="省份 / 城市">
        <div style="display: flex; gap: 10px; width: 100%">
          <RegionSelect v-model="qccForm.province" level="province" placeholder="全国" @update:model-value="qccForm.city = null" />
          <RegionSelect v-model="qccForm.city" level="city" :parent="qccForm.province" placeholder="全部城市" />
        </div>
      </el-form-item>
      <el-form-item label="信息类型">
        <el-select v-model="qccForm.msgType" style="width: 100%">
          <el-option value="" label="全部" /><el-option value="3" label="招标公告" /><el-option value="4" label="中标公告" />
        </el-select>
      </el-form-item>
      <el-form-item label="页码（每页最多 20 条）"><el-input-number v-model="qccForm.page" :min="1" :max="100" /></el-form-item>
    </el-form>
    <el-alert v-if="qccError" :title="qccError" type="error" :closable="false" show-icon />
    <el-alert v-if="qccResult" :title="qccResult" type="success" :closable="false" show-icon />
    <template #footer>
      <el-button :disabled="qccBusy" @click="qccOpen = false">关闭</el-button>
      <el-button type="primary" :loading="qccBusy" :disabled="!state?.config.has_qcc_key || !qccForm.keyword.trim()"
        @click="searchQcc">查询并导入（1 次）</el-button>
    </template>
  </el-dialog>

  <el-dialog v-model="detailOpen" title="标讯详情" width="min(860px, 94vw)" top="4vh"
    :close-on-click-modal="false" :close-on-press-escape="!detailSaving" :show-close="!detailSaving">
    <div v-if="detailProject" class="notice-detail">
      <h2>{{ detailProject.title }}</h2>
      <div class="notice-meta">
        <el-tag :type="detailProject.is_notice === 0 ? 'info' : 'primary'" effect="plain">
          {{ detailProject.is_notice === 0 ? '疑似非公告' : '疑似公告' }}
        </el-tag>
        <span>来源：{{ detailProject.source || '—' }}</span>
        <span>渠道：{{ originLabel(detailProject.origin) }}</span>
        <span>发布：{{ dateText(detailProject.published) }}</span>
        <span>采购人：{{ detailProject.buyer_name || '待核实' }}</span>
      </div>
      <div class="notice-link-row">
        <el-button link type="primary" @click="openOriginal(detailProject.url)">
          <el-icon><Link /></el-icon>打开原文
        </el-button>
        <el-button link :loading="detailFetching" @click="fetchContent">获取正文</el-button>
        <el-button link :type="detailProject.favorite ? 'warning' : 'info'" @click="toggleFavorite(detailProject)">
          {{ detailProject.favorite ? '取消收藏' : '收藏' }}
        </el-button>
      </div>
      <pre class="notice-content">{{ detailProject.content || '暂无正文，可点击“获取正文”尝试抓取。' }}</pre>
      <p v-if="detailProject.deadline_evidence" class="deadline-evidence">
        截止时间依据：{{ detailProject.deadline_evidence }}
      </p>
      <p v-if="detailProject.qcc_available" class="deadline-evidence">
        企查查信息（待核实）：编号 {{ detailProject.qcc_project_no || '—' }}；
        采购人 {{ detailProject.qcc_buyer || '—' }}；预算 {{ detailProject.qcc_budget || '—' }}；
        投标截止 {{ detailProject.qcc_bid_end_date || '—' }}。
      </p>

      <el-form label-position="top" class="detail-form">
        <div class="form-grid">
          <el-form-item label="公告阶段">
            <el-select v-model="detailForm.stage" style="width: 100%">
              <el-option v-for="stage in STAGES" :key="stage" :value="stage" :label="stage" />
            </el-select>
          </el-form-item>
          <el-form-item label="跟进状态">
            <el-select v-model="detailForm.followup" style="width: 100%">
              <el-option v-for="followup in FOLLOWUPS" :key="followup" :value="followup" :label="followup" />
            </el-select>
          </el-form-item>
          <el-form-item label="预算/金额">
            <el-input v-model="detailForm.amount" maxlength="5000" placeholder="人工核验后填写" />
          </el-form-item>
          <el-form-item label="截止时间">
            <el-date-picker v-model="detailForm.deadline" type="datetime" value-format="YYYY-MM-DDTHH:mm"
              placeholder="待核实" style="width: 100%" />
          </el-form-item>
          <el-form-item label="采购人">
            <el-input v-model="detailForm.buyerName" maxlength="200" />
          </el-form-item>
          <el-form-item label="跟进备注" class="wide">
            <el-input v-model="detailForm.notes" type="textarea" :rows="3" maxlength="5000" />
          </el-form-item>
        </div>
        <el-alert v-if="detailError" :title="detailError" type="error" :closable="false" show-icon />
      </el-form>
    </div>
    <template #footer>
      <div class="detail-footer">
        <div>
          <el-button v-if="canCreateLead" @click="detailProject && openConvert(detailProject, 'lead')">转为线索</el-button>
          <el-button v-if="canCreateBusiness" type="primary" plain
            @click="detailProject && openConvert(detailProject, 'business')">转为商机</el-button>
        </div>
        <div>
          <el-button :disabled="detailSaving" @click="detailOpen = false">关闭</el-button>
          <el-button type="primary" :loading="detailSaving" @click="saveDetail">保存核验与跟进</el-button>
        </div>
      </div>
    </template>
  </el-dialog>

  <el-dialog v-model="topicDialog" :title="topicEditingId == null ? '新建关注主题' : '编辑关注主题'"
    width="min(560px, 94vw)" :close-on-click-modal="false" :close-on-press-escape="!topicSaving" :show-close="!topicSaving">
    <el-form label-position="top" @submit.prevent="saveTopic">
      <el-form-item label="主题名称" required>
        <el-input v-model="topicForm.name" maxlength="1000" />
      </el-form-item>
      <el-form-item label="关键词" required>
        <el-input v-model="topicForm.keywords" type="textarea" :rows="2" maxlength="1000"
          placeholder="逗号分隔，例如：OPC,一人公司,创业社区运营" />
      </el-form-item>
      <el-form-item label="排除词">
        <el-input v-model="topicForm.excludes" type="textarea" :rows="2" maxlength="1000"
          placeholder="逗号分隔，例如：OPC协议,工业通信" />
      </el-form-item>
      <el-form-item label="地区加分">
        <el-input v-model="topicForm.region" maxlength="1000" placeholder="例如：江苏,南京,苏州,盐城" />
      </el-form-item>
      <el-alert v-if="topicError" :title="topicError" type="error" :closable="false" show-icon />
      <div class="dialog-actions">
        <el-button :disabled="topicSaving" @click="topicDialog = false">取消</el-button>
        <el-button type="primary" native-type="submit" :loading="topicSaving">保存主题</el-button>
      </div>
    </el-form>
  </el-dialog>

  <el-dialog v-model="convertOpen" :title="convertTarget === 'lead' ? '标讯转线索' : '标讯转商机'"
    width="min(680px, 94vw)" top="7vh" :close-on-click-modal="false" :close-on-press-escape="!convertSaving" :show-close="!convertSaving">
    <template v-if="convertProject">
      <el-alert type="info" :closable="false" show-icon class="convert-source">
        <template #title>{{ convertProject.title }}</template>
      </el-alert>
      <el-form label-position="top" @submit.prevent="saveConvert">
        <div class="form-grid">
          <el-form-item label="客户" required>
            <el-select v-model="convertForm.customerId" filterable placeholder="选择客户" style="width: 100%">
              <el-option v-for="option in customers" :key="option.value" v-bind="option" />
            </el-select>
          </el-form-item>
          <el-form-item label="招商项目">
            <el-select v-model="convertForm.projectId" filterable clearable placeholder="选择项目" style="width: 100%">
              <el-option v-for="option in platformProjects" :key="option.value" v-bind="option" />
            </el-select>
          </el-form-item>
          <el-form-item v-if="canAssign" label="负责人">
            <el-select v-model="convertForm.ownerId" filterable clearable placeholder="默认当前账号" style="width: 100%">
              <el-option v-for="option in owners" :key="option.value" v-bind="option" />
            </el-select>
          </el-form-item>
          <el-form-item v-if="convertTarget === 'business'" label="预计金额（元）">
            <el-input-number v-model="convertForm.amount" :min="0" :precision="2" :controls="false" style="width: 100%" />
          </el-form-item>
          <el-form-item v-if="convertTarget === 'lead'" label="下次跟进时间">
            <el-date-picker v-model="convertForm.nextFollowupAt" type="datetime" value-format="YYYY-MM-DDTHH:mm"
              placeholder="默认截止前 2 天" style="width: 100%" />
          </el-form-item>
          <el-form-item v-if="convertTarget === 'business'" label="备注" class="wide">
            <el-input v-model="convertForm.note" type="textarea" :rows="3" maxlength="5000" />
          </el-form-item>
        </div>
        <el-alert v-if="convertError" :title="convertError" type="error" :closable="false" show-icon />
        <div class="dialog-actions">
          <el-button :disabled="convertSaving" @click="convertOpen = false">取消</el-button>
          <el-button type="primary" native-type="submit" :loading="convertSaving">
            {{ convertTarget === 'lead' ? '创建线索' : '创建商机' }}
          </el-button>
        </div>
      </el-form>
    </template>
  </el-dialog>

  <el-dialog v-model="buyerProjectsOpen" :title="'采购人公告 · ' + (buyerSelected?.name ?? '')"
    width="min(1000px, 96vw)" :close-on-click-modal="false">
    <BidNoticeTable :projects="buyerProjects" :topic-names="topicNames" :loading="buyerProjectsLoading"
      empty-text="该采购人暂无收录公告" @detail="openBuyerProjectDetail"
      @favorite="toggleFavorite" />
  </el-dialog>
</template>

<style scoped>
.bid-error { margin-bottom: 14px; }
.bid-body { min-height: 240px; }
.bid-stats { display: grid; grid-template-columns: repeat(5, minmax(0, 1fr)); gap: 14px; margin-bottom: 16px; }
.bid-stat { min-height: 96px; padding: 18px 20px; border: 1px solid #ebeef5; border-radius: var(--card-radius); background: #fff; box-shadow: var(--card-shadow); }
.bid-stat small, .bid-stat strong, .bid-stat em { display: block; }
.bid-stat small { color: #8a97a5; font-size: 12px; }
.bid-stat strong { margin-top: 3px; color: #1f2d3d; font-size: 27px; font-weight: 700; line-height: 1.2; }
.bid-stat em { margin-top: 3px; color: #b0bac4; font-size: 11px; font-style: normal; }
.bid-tabs :deep(.el-tabs__header) { margin-bottom: 14px; }
.topic-quick { margin-bottom: 14px; padding: 16px 18px; }
.topic-quick-heading { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; margin-bottom: 12px; }
.topic-quick-heading strong { color: #1f2d3d; font-size: 14px; }
.topic-quick-heading p { margin: 5px 0 0; color: #909399; font-size: 12px; }
.topic-chips { display: flex; flex-wrap: wrap; gap: 8px; }
.topic-chip { display: flex; align-items: center; gap: 2px; padding: 4px 8px 4px 10px; border: 1px solid #ebeef5; border-radius: 6px; background: #fafbfc; }
.topic-chip-name { padding: 0; color: #303133; font-size: 12px; border: 0; background: transparent; cursor: pointer; }
.topic-chip-name em { color: #a0aab5; font-style: normal; }
.bid-list { overflow: hidden; }
.intent-search { display: flex; align-items: center; justify-content: space-between; gap: 15px; margin-bottom: 14px; padding: 16px 18px; }
.intent-search strong { color: #1f2d3d; font-size: 14px; }
.intent-search p { margin: 5px 0 0; color: #909399; font-size: 12px; }
.intent-actions { display: flex; gap: 8px; flex-shrink: 0; }
.notice-detail h2 { margin: 0 0 10px; color: #1f2d3d; font-size: 18px; line-height: 1.45; }
.notice-meta { display: flex; flex-wrap: wrap; align-items: center; gap: 10px; color: #8a97a5; font-size: 12px; }
.notice-link-row { display: flex; gap: 16px; margin: 12px 0; }
.notice-content { max-height: 170px; margin: 0; padding: 12px 14px; overflow: auto; border-radius: 6px; background: #f7f8fa; color: #5a6a7a; font-family: inherit; font-size: 12px; line-height: 1.7; white-space: pre-wrap; overflow-wrap: anywhere; }
.deadline-evidence { margin: 8px 0 0; color: #a0aab5; font-size: 11px; }
.detail-form { margin-top: 18px; }
.detail-footer { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.buyer-add { display: flex; gap: 8px; }
.convert-source { margin-bottom: 14px; }
@media (max-width: 1180px) {
  .bid-stats { grid-template-columns: repeat(3, minmax(0, 1fr)); }
  .intent-search { align-items: flex-start; flex-direction: column; }
}
@media (max-width: 640px) {
  .bid-stats { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .topic-quick-heading, .detail-footer { align-items: flex-start; flex-direction: column; }
  .buyer-add { width: 100%; }
  .intent-actions { width: 100%; flex-wrap: wrap; }
}
</style>
