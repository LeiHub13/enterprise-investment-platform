<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  CircleCheck, CircleClose, Connection, Cpu, Document, MagicStick, Promotion, Search, TrendCharts, View, Warning,
} from '@element-plus/icons-vue'
import { api, ApiError, getToken } from './api'
import type { Page, RecordData } from './api'
import MarkdownView from './MarkdownView.vue'

const emit = defineEmits<{ expired: [error: unknown] }>()
const route = useRoute()
const router = useRouter()

type Project = { id: number; name: string }
type TraceNode = { node: string; label: string; status: 'running' | 'done' }
type ToolLog = { node: string; tool: string; status: string; durationMs: number; resultSummary: string }
type Score = {
  enterpriseId: number | null
  enterpriseName: string
  totalScore: number
  fitScore?: number | null
  intentScore?: number | null
  confidenceScore?: number | null
  industryScore: number
  regionScore: number
  scaleScore: number
  signalScore: number
  completenessScore: number
  grade: string
  reasons: string[]
  sourceType?: string
  matchedEnterpriseId?: number | null
  nonConvertible?: boolean
  duplicateLeadId?: number | null
}
type RunSummary = {
  id: string
  message: string
  status: string
  createdAt: string
  leadIds?: string | null
  projectId?: number | null
}
type ReviewCandidate = { enterpriseId: number; enterpriseName: string; totalScore: number; grade: string }

const projects = ref<Project[]>([])
const projectId = ref<number | null>(null)
const source = ref<'INTERNAL' | 'EXTERNAL'>('INTERNAL')
const conversationId = ref(crypto.randomUUID())
const draft = ref('')
const messages = ref<{ role: 'user' | 'agent'; text: string }[]>([])
const trace = ref<TraceNode[]>([])
const tools = ref<ToolLog[]>([])
const scores = ref<Score[]>([])
const report = ref('')
const review = ref<{ candidates: ReviewCandidate[] } | null>(null)
const leadIds = ref<number[]>([])
const runId = ref('')
const runStatus = ref('')
const running = ref(false)
const errorText = ref('')
const history = ref<RunSummary[]>([])
const historyFilter = ref<'ALL' | 'AWAITING_REVIEW'>('ALL')
const historyPage = ref(1)
const loadingHistory = ref(false)
const viewingHistory = ref(false)
const mode = ref<'chat' | 'event'>('chat')
const eventRecords = ref<RecordData[]>([])
const loadingEvents = ref(false)
const observation = ref<{ enterpriseName: string; status: string; reason: string } | null>(null)

const gradeText: Record<string, string> = { PRIORITY: '优先跟进', VERIFY: '待核实', HOLD: '暂缓跟进' }
const gradeType: Record<string, 'success' | 'warning' | 'info'> = { PRIORITY: 'success', VERIFY: 'warning', HOLD: 'info' }
const statusText: Record<string, string> = {
  RUNNING: '执行中', AWAITING_REVIEW: '待审核', DONE: '已完成', REJECTED: '已驳回', ERROR: '异常',
}
const observationStatus: Record<string, string> = {
  WATCHING: '观察中', SUB_THRESHOLD: '未达标', REJECTED: '已驳回', VERIFY_FAILED: '核验失败',
}
const recordTypeText: Record<string, string> = { RECRUITMENT: '招聘信号', RENTAL: '求租信号' }
const nodeIcons: Record<string, typeof Search> = {
  parse_requirements: Cpu,
  validate_requirements: CircleCheck,
  search_companies: Search,
  enrich_company: Document,
  verify_candidates: CircleCheck,
  associate_candidates: Connection,
  analyze_signals: TrendCharts,
  calculate_score: TrendCharts,
  deduplicate: Connection,
  generate_report: Document,
  human_review: Warning,
  create_leads: Promotion,
}

const canSend = computed(() => !running.value && Boolean(projectId.value) && draft.value.trim().length > 0)
const activeProjectName = computed(() => projects.value.find(p => p.id === projectId.value)?.name ?? '')

function resetRun() {
  trace.value = []
  tools.value = []
  scores.value = []
  report.value = ''
  review.value = null
  leadIds.value = []
  runId.value = ''
  runStatus.value = ''
  errorText.value = ''
  observation.value = null
  viewingHistory.value = false
}

function toolsOf(node: string) {
  return tools.value.filter(tool => tool.node === node)
}

function normalizeReasons(value: unknown): string[] {
  if (Array.isArray(value)) return value.map(String)
  if (typeof value === 'string') {
    try {
      const parsed = JSON.parse(value)
      return Array.isArray(parsed) ? parsed.map(String) : [value]
    } catch {
      return [value]
    }
  }
  return []
}

function handleEvent(event: string, data: Record<string, unknown>) {
  if (event === 'meta') {
    runId.value = String(data.runId ?? '')
  } else if (event === 'node') {
    const key = String(data.node ?? '')
    const existing = trace.value.find(item => item.node === key)
    const next = { node: key, label: String(data.label ?? key), status: (data.status === 'running' ? 'running' : 'done') as TraceNode['status'] }
    if (existing) Object.assign(existing, next)
    else trace.value.push(next)
  } else if (event === 'tool') {
    tools.value.push({
      node: String(data.node ?? ''),
      tool: String(data.tool ?? ''),
      status: String(data.status ?? 'OK'),
      durationMs: Number(data.durationMs ?? 0),
      resultSummary: String(data.resultSummary ?? ''),
    })
  } else if (event === 'score') {
    scores.value.push({ ...(data as unknown as Score), reasons: normalizeReasons(data.reasons) })
  } else if (event === 'report') {
    report.value = String(data.text ?? '')
  } else if (event === 'message') {
    messages.value.push({ role: 'agent', text: String(data.text ?? '') })
  } else if (event === 'review_required') {
    review.value = { candidates: (data.candidates as ReviewCandidate[]) ?? [] }
    runStatus.value = 'AWAITING_REVIEW'
  } else if (event === 'lead_created') {
    leadIds.value.push(Number(data.leadId))
  } else if (event === 'observation') {
    observation.value = {
      enterpriseName: String(data.enterpriseName ?? ''),
      status: String(data.status ?? ''),
      reason: String(data.reason ?? ''),
    }
  } else if (event === 'done') {
    runStatus.value = String(data.status ?? 'DONE')
    if (runStatus.value !== 'AWAITING_REVIEW') review.value = null
    running.value = false
    void loadHistory()
  } else if (event === 'error') {
    errorText.value = String(data.message ?? 'Agent 执行失败')
    runStatus.value = 'ERROR'
    running.value = false
  }
}

async function stream(path: string, body: unknown) {
  const token = getToken()
  const response = await fetch('/api' + path, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}) },
    body: JSON.stringify(body),
  })
  if (!response.ok || !response.body) {
    let message = 'Agent 服务请求失败'
    try {
      const payload = await response.json()
      if (payload?.message) message = String(payload.message)
    } catch {
      // 非 JSON 错误体
    }
    if (response.status === 401) emit('expired', new ApiError(401, message))
    throw new ApiError(response.status, message)
  }
  const reader = response.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''
  for (;;) {
    const { value, done } = await reader.read()
    if (done) break
    buffer += decoder.decode(value, { stream: true })
    let index = buffer.indexOf('\n\n')
    while (index >= 0) {
      const chunk = buffer.slice(0, index)
      buffer = buffer.slice(index + 2)
      const eventLine = chunk.split('\n').find(line => line.startsWith('event:'))
      const dataLine = chunk.split('\n').find(line => line.startsWith('data:'))
      if (eventLine && dataLine) {
        try {
          handleEvent(eventLine.slice(6).trim(), JSON.parse(dataLine.slice(5).trim()))
        } catch {
          // 忽略无法解析的事件
        }
      }
      index = buffer.indexOf('\n\n')
    }
  }
}

function pushAgent(text: string) {
  messages.value.push({ role: 'agent', text })
}

async function send() {
  if (!canSend.value) return
  const message = draft.value.trim()
  historyPage.value = 1
  draft.value = ''
  resetRun()
  messages.value.push({ role: 'user', text: message })
  running.value = true
  runStatus.value = 'RUNNING'
  try {
    await stream('/agent/chat', { conversationId: conversationId.value, projectId: projectId.value, message, source: source.value })
  } catch (reason) {
    errorText.value = reason instanceof Error ? reason.message : '请求失败'
    runStatus.value = 'ERROR'
    running.value = false
  }
}

async function decide(approved: boolean) {
  if (!runId.value) return
  running.value = true
  const target = runId.value
  review.value = null
  runStatus.value = approved ? 'RUNNING' : 'REJECTED'
  try {
    pushAgent(approved ? '已确认，正在创建待审核线索…' : '已驳回，本次不创建线索。')
    await stream(`/agent/tasks/${target}/decision`, { approved })
  } catch (reason) {
    errorText.value = reason instanceof Error ? reason.message : '请求失败'
    running.value = false
  }
}

async function loadEventRecords() {
  loadingEvents.value = true
  try {
    eventRecords.value = await api<RecordData[]>('/agent/events/records')
  } catch (reason) {
    if (reason instanceof ApiError && reason.status === 401) emit('expired', reason)
  } finally {
    loadingEvents.value = false
  }
}

async function analyzeEvent(record: RecordData) {
  if (running.value) return
  historyPage.value = 1
  resetRun()
  messages.value.push({ role: 'user', text: `分析数据事件：${record.title ?? record.enterpriseName ?? record.id}` })
  running.value = true
  runStatus.value = 'RUNNING'
  try {
    await stream('/agent/events', { recordId: record.id })
  } catch (reason) {
    errorText.value = reason instanceof Error ? reason.message : '请求失败'
    runStatus.value = 'ERROR'
    running.value = false
  }
}

async function loadProjects() {
  try {
    const result = await api<Page<RecordData>>('/projects' + '?page=1&size=100')
    projects.value = result.items.map(item => ({ id: Number(item.id), name: String(item.projectName ?? item.name ?? `项目 ${item.id}`) }))
    if (!projectId.value) {
      const requestedId = Number(route.query.projectId)
      const requestedName = typeof route.query.project === 'string' ? route.query.project.trim() : ''
      const preferred = Number.isFinite(requestedId) && requestedId > 0
        ? projects.value.find(project => project.id === requestedId)
        : requestedName
          ? projects.value.find(project => project.name.includes(requestedName))
          : undefined
      const defaultProject = projects.value.find(project => project.name.includes('泛悦城市广场'))
      projectId.value = preferred?.id ?? defaultProject?.id ?? projects.value[0]?.id ?? null
    }
    const prompt = typeof route.query.prompt === 'string' ? route.query.prompt.slice(0, 4000) : ''
    if (prompt && !draft.value) draft.value = prompt
  } catch (reason) {
    if (reason instanceof ApiError && reason.status === 401) emit('expired', reason)
  }
}

async function loadHistory() {
  loadingHistory.value = true
  try {
    const params = new URLSearchParams({ page: String(historyPage.value) })
    if (historyFilter.value !== 'ALL') params.set('status', historyFilter.value)
    history.value = await api<RunSummary[]>('/agent/runs?' + params.toString())
  } catch (reason) {
    if (reason instanceof ApiError && reason.status === 401) emit('expired', reason)
  } finally {
    loadingHistory.value = false
  }
}

function changeHistoryFilter(value: 'ALL' | 'AWAITING_REVIEW') {
  historyFilter.value = value
  historyPage.value = 1
  void loadHistory()
}

function changeHistoryPage(delta: number) {
  historyPage.value = Math.max(1, historyPage.value + delta)
  void loadHistory()
}

async function openRun(run: RunSummary) {
  if (running.value) return
  resetRun()
  viewingHistory.value = true
  runId.value = run.id
  runStatus.value = run.status
  projectId.value = run.projectId ?? projectId.value
  messages.value = [{ role: 'user', text: run.message }]
  try {
    const payload = await api<{
      run: Record<string, unknown>
      tools: ToolLog[]
      scores: Array<Record<string, unknown>>
    }>(`/agent/tasks/${run.id}/trace`)
    tools.value = payload.tools
    scores.value = payload.scores.map(item => {
      const score = { ...(item as unknown as Score), reasons: normalizeReasons(item.reasons) }
      if (score.sourceType === 'EXTERNAL' && score.enterpriseId) {
        score.matchedEnterpriseId = Number(score.enterpriseId)
      }
      return score
    })
    report.value = String(payload.run.report ?? '')
    leadIds.value = String(payload.run.leadIds ?? '').split(',').filter(Boolean).map(Number)
    trace.value = Array.from(new Set(payload.tools.map(tool => tool.node)))
      .map(node => ({ node, label: labelOf(node), status: 'done' as const }))
    if (run.status === 'AWAITING_REVIEW') {
      review.value = { candidates: [] }
    }
  } catch (reason) {
    if (reason instanceof ApiError && reason.status === 401) emit('expired', reason)
    else errorText.value = reason instanceof Error ? reason.message : '加载任务详情失败'
  }
}

function labelOf(node: string) {
  return trace.value.find(item => item.node === node)?.label
    ?? ({
      parse_requirements: '理解招商需求', validate_requirements: '校验筛选条件', search_companies: '搜索企业资源库',
      enrich_company: '补充企业档案与信号', analyze_signals: '分析扩张信号', calculate_score: '计算匹配评分',
      deduplicate: '线索查重', generate_report: '生成推荐报告', human_review: '等待人工审核', create_leads: '创建待审核线索',
      load_event: '载入数据事件', analyze_recruitment: '招聘扩张分析', analyze_rental: '办公求租分析',
      verify_enterprise: '企业核验', associate_entity: '企业实体关联', match_building: '楼宇匹配',
      lead_condition: '线索条件判定', save_observation: '生成观察结果',
    } as Record<string, string>)[node] ?? node
}

function newTask() {
  if (running.value) return
  resetRun()
  conversationId.value = crypto.randomUUID()
  messages.value = []
  draft.value = ''
}

function formatTime(value: unknown) {
  return typeof value === 'string' ? value.replace('T', ' ').slice(0, 16) : '—'
}

onMounted(() => {
  void loadProjects()
  void loadHistory()
  void loadEventRecords()
})
</script>

<template>
  <div class="agent-page">
    <div class="page-header">
      <div class="page-header-copy">
        <span class="page-glyph"><el-icon :size="20"><MagicStick /></el-icon></span>
        <div>
          <h2>招商 Agent</h2>
          <p>自然语言发起获客：搜索企业 → 信号分析 → 匹配评分 → 推荐报告 → 人工批准转线索</p>
        </div>
      </div>
      <div class="page-header-actions">
        <el-radio-group v-model="mode" :disabled="running">
          <el-radio-button value="chat">对话获客</el-radio-button>
          <el-radio-button value="event">事件获客</el-radio-button>
        </el-radio-group>
        <template v-if="mode === 'chat'">
          <el-radio-group v-model="source" :disabled="running">
            <el-radio-button value="INTERNAL">企业数据库</el-radio-button>
            <el-radio-button value="EXTERNAL">外部企业</el-radio-button>
          </el-radio-group>
          <el-select v-model="projectId" placeholder="选择招商项目" style="width: 220px" filterable :disabled="running">
            <el-option v-for="project in projects" :key="project.id" :label="project.name" :value="project.id" />
          </el-select>
        </template>
        <el-button :disabled="running" @click="newTask">新建任务</el-button>
      </div>
    </div>

    <div class="agent-grid">
      <section class="chat-card">
        <header class="chat-header">
          <span class="chat-avatar"><el-icon :size="18"><Cpu /></el-icon></span>
          <div class="chat-meta">
            <strong>{{ mode === 'event' ? '事件驱动流水线' : '获客工作流' }}</strong>
            <span v-if="mode === 'event'">数据事件 → 来源分析 → 核验 → 楼宇匹配 → 评分 → 人工审核 → 待审核线索或观察结果</span>
            <span v-else>{{ activeProjectName || '请选择招商项目' }} · {{ source === 'EXTERNAL' ? '外部企业' : '企业数据库' }} · 招商总控与专业分析</span>
          </div>
          <el-tag v-if="runStatus" size="small" :type="runStatus === 'ERROR' ? 'danger' : runStatus === 'AWAITING_REVIEW' ? 'warning' : runStatus === 'DONE' ? 'success' : 'info'">
            {{ statusText[runStatus] ?? runStatus }}
          </el-tag>
        </header>

        <div class="chat-scroll">
          <div v-if="!messages.length && mode === 'event'" class="chat-empty">
            <p>事件获客：从下方采集记录中选择一条数据事件，点击「分析」运行完整流水线。</p>
            <p class="chat-empty-note">满足条件后可人工确认创建待审核线索；其他情况只显示观察结果。</p>
          </div>
          <div v-if="!messages.length && mode === 'chat'" class="chat-empty">
            <p>{{ source === 'EXTERNAL' ? '输入外部企业名称与已知信息，例如：' : '描述要找的企业，例如：' }}</p>
            <button
              type="button"
              class="hint-chip"
              @click="draft = source === 'EXTERNAL'
                ? '【示例】某科技企业，软件行业，约80人，近期在招聘，想了解是否适合入驻泛悦城市广场'
                : '帮我寻找南京鼓楼区适合办公面积 100～300㎡ 的科技类企业，优先近期有扩张需求的'"
            >
              {{ source === 'EXTERNAL' ? '外部企业 · 软件行业 · 约80人 · 近期招聘' : '南京鼓楼区 · 科技类 · 100～300㎡ · 优先扩张' }}
            </button>
            <p class="chat-empty-note">
              {{ source === 'EXTERNAL'
                ? '外部模式依据你提供的信息评估：企业全称唯一匹配时读取对应档案；未匹配企业只出报告，需先建档并补齐证据。'
                : 'Agent 只调用系统接口取数，不编造企业与需求；创建线索前必须人工批准。' }}
            </p>
          </div>
          <div v-for="(message, index) in messages" :key="index" class="chat-row" :class="message.role">
            <span class="chat-role">{{ message.role === 'user' ? '我' : 'Agent' }}</span>
            <div class="chat-bubble">{{ message.text }}</div>
          </div>
          <div v-if="running" class="chat-row agent">
            <span class="chat-role">Agent</span>
            <div class="chat-bubble typing"><span /><span /><span /></div>
          </div>
          <div v-if="errorText" class="chat-error"><el-icon><Warning /></el-icon>{{ errorText }}</div>
        </div>

        <div v-if="mode === 'chat'" class="composer">
          <el-input v-model="draft" type="textarea" :rows="3" resize="none" :disabled="running"
                    placeholder="例如：帮我寻找南京鼓楼区适合泛悦城市广场的科技类企业，办公面积 100～300㎡"
                    @keydown.enter.exact.prevent="send" />
          <div class="composer-bar">
            <span class="composer-hint">Enter 发送 · Shift+Enter 换行 · 写操作需人工批准</span>
            <el-button type="primary" :loading="running" :disabled="!canSend" @click="send">发起获客任务</el-button>
          </div>
        </div>
        <div v-else class="event-picker" v-loading="loadingEvents">
          <header class="event-picker-head">
            <span>数据事件 · 采集记录</span>
            <button type="button" class="history-refresh" :disabled="loadingEvents" @click="loadEventRecords">刷新</button>
          </header>
          <div v-if="!eventRecords.length" class="history-empty">暂无采集记录，请先用采集器写入招聘/求租数据</div>
          <div v-for="rec in eventRecords" :key="rec.id" class="event-row">
            <div class="event-row-copy">
              <strong>{{ rec.enterpriseName }}</strong>
              <span class="event-row-title">{{ rec.title }}</span>
              <span class="event-row-tags">
                <el-tag size="small" effect="plain">{{ recordTypeText[String(rec.recordType)] ?? rec.recordType }}</el-tag>
                <el-tag v-if="Number(rec.isDemo) === 1" size="small" type="warning">演示</el-tag>
                <el-tag v-if="rec.validityStatus === 'EXPIRED'" size="small" type="info">已失效</el-tag>
                <el-tag v-if="rec.validityStatus === 'SUPERSEDED'" size="small" type="info">历史版本</el-tag>
                <small>{{ rec.source }}</small>
              </span>
            </div>
            <el-button size="small" type="primary" :disabled="running || rec.validityStatus !== 'VALID'" @click="analyzeEvent(rec)">分析</el-button>
          </div>
        </div>

        <div class="history-box">
          <header>
            <span>历史任务 · {{ historyFilter === 'ALL' ? '全部' : '待确认' }}</span>
            <button type="button" class="history-refresh" :disabled="loadingHistory" @click="loadHistory">刷新</button>
          </header>
          <div class="history-filter">
            <el-button size="small" :type="historyFilter === 'ALL' ? 'primary' : 'default'" @click="changeHistoryFilter('ALL')">全部</el-button>
            <el-button size="small" :type="historyFilter === 'AWAITING_REVIEW' ? 'primary' : 'default'" @click="changeHistoryFilter('AWAITING_REVIEW')">待确认</el-button>
          </div>
          <div v-if="!history.length" class="history-empty">暂无任务记录</div>
          <button v-for="item in history" :key="item.id" type="button" class="history-item"
                  :class="{ active: item.id === runId }" @click="openRun(item)">
            <span class="history-message">{{ item.message }}</span>
            <span class="history-side">
              <el-tag size="small" :type="item.status === 'DONE' ? 'success' : item.status === 'ERROR' ? 'danger' : item.status === 'AWAITING_REVIEW' ? 'warning' : 'info'">
                {{ statusText[item.status] ?? item.status }}
              </el-tag>
              <small>{{ formatTime(item.createdAt) }}</small>
            </span>
          </button>
          <div class="history-pages"><el-button size="small" :disabled="loadingHistory || historyPage === 1" @click="changeHistoryPage(-1)">上一页</el-button>
            <span>{{ historyPage }}</span><el-button size="small" :disabled="loadingHistory || history.length < 20" @click="changeHistoryPage(1)">下一页</el-button></div>
        </div>
      </section>

      <section class="result-card">
        <div v-if="!trace.length && !report && !errorText" class="result-empty">
          <el-icon :size="42"><MagicStick /></el-icon>
          <h3>发起任务后，这里会实时显示执行过程</h3>
          <ol>
            <li>理解需求并校验条件（LLM 解析，不做猜测）</li>
            <li>搜索企业资源库并补充档案与扩张信号</li>
            <li>分别计算楼宇匹配、租赁意向与数据可信度</li>
            <li>线索查重、生成推荐报告，人工确认后创建待审核线索</li>
          </ol>
        </div>

        <template v-else>
          <div class="result-section">
            <h3 class="section-title">执行轨迹 <small v-if="runId">任务 {{ runId.slice(0, 8) }}</small></h3>
            <ul class="trace-list">
              <li v-for="node in trace" :key="node.node" class="trace-item" :class="node.status">
                <span class="trace-dot">
                  <el-icon v-if="node.status === 'done'"><CircleCheck /></el-icon>
                  <span v-else class="spinner" />
                </span>
                <div class="trace-body">
                  <strong>{{ node.label }}</strong>
                  <div v-if="toolsOf(node.node).length" class="trace-tools">
                    <span v-for="(tool, index) in toolsOf(node.node)" :key="index" class="trace-tool" :class="{ error: tool.status === 'ERROR' }">
                      {{ tool.tool }} · {{ tool.durationMs }}ms
                    </span>
                  </div>
                </div>
              </li>
            </ul>
          </div>

          <div v-if="review" class="review-bar">
            <div class="review-copy">
              <strong>待人工审核</strong>
              <p v-if="review.candidates.length">
                将通过人工确认创建 {{ review.candidates.length }} 条待审核线索：
                {{ review.candidates.map(item => `${item.enterpriseName}（${item.totalScore}分）`).join('、') }}
              </p>
              <p v-else>任务处于待确认状态，确认后创建待审核线索；审核通过后可添加跟进。</p>
            </div>
            <div class="review-actions">
              <el-button :disabled="running" @click="decide(false)">驳回</el-button>
              <el-button type="primary" :disabled="running" @click="decide(true)">批准创建线索</el-button>
            </div>
          </div>

          <div v-if="scores.length" class="result-section">
            <h3 class="section-title">企业匹配评分</h3>
            <div class="score-grid">
              <article v-for="score in scores" :key="score.enterpriseId ?? score.enterpriseName" class="score-card">
                <header>
                  <div>
                    <strong>{{ score.enterpriseName }}</strong>
                    <div class="tag-row">
                      <el-tag v-if="score.duplicateLeadId" size="small" type="info">已有线索 #{{ score.duplicateLeadId }}</el-tag>
                      <el-tag v-if="score.sourceType === 'EXTERNAL'" size="small" type="primary" effect="plain">外部企业</el-tag>
                      <el-tag v-if="score.sourceType === 'EXTERNAL' && score.matchedEnterpriseId" size="small" type="success" effect="plain">
                        已匹配档案 #{{ score.matchedEnterpriseId }}
                      </el-tag>
                      <el-tag v-if="score.sourceType === 'EXTERNAL' && !score.matchedEnterpriseId" size="small" type="warning" effect="plain">
                        未入库 · 需先建档
                      </el-tag>
                    </div>
                  </div>
                  <el-tag size="small" :type="gradeType[score.grade]">{{ gradeText[score.grade] ?? score.grade }}</el-tag>
                </header>
                <div class="score-main">
                  <span class="score-number">{{ score.totalScore }}</span><span class="score-unit">/ 100</span>
                  <span v-if="score.fitScore != null && score.intentScore != null && score.confidenceScore != null" class="score-bars">
                    <i><em>楼宇匹配</em>{{ score.fitScore }}/100</i>
                    <i><em>租赁意向</em>{{ score.intentScore }}/100</i>
                    <i><em>数据可信</em>{{ score.confidenceScore }}/100</i>
                  </span>
                  <span v-else class="score-bars">
                    <i><em>行业</em>{{ score.industryScore }}/25</i>
                    <i><em>区域</em>{{ score.regionScore }}/20</i>
                    <i><em>规模</em>{{ score.scaleScore }}/20</i>
                    <i><em>信号</em>{{ score.signalScore }}/25</i>
                    <i><em>完整</em>{{ score.completenessScore }}/10</i>
                  </span>
                </div>
                <ul class="score-reasons">
                  <li v-for="(reason, index) in score.reasons" :key="index" :class="{ pending: reason.startsWith('待核实') }">{{ reason }}</li>
                </ul>
              </article>
            </div>
          </div>

          <div v-if="report" class="result-section">
            <h3 class="section-title">推荐报告</h3>
            <div class="report-text"><MarkdownView :source="report" /></div>
          </div>

          <div v-if="leadIds.length" class="lead-bar">
            <el-icon><CircleCheck /></el-icon>
            <span>已创建待审核线索：{{ leadIds.map(id => '#' + id).join('、') }}</span>
            <el-button size="small" type="primary" @click="router.push('/leads')">查看待审核线索</el-button>
          </div>

          <div v-if="observation" class="observation-bar">
            <el-icon><View /></el-icon>
            <span>观察结果（未写入记录）：{{ observation.enterpriseName }} · {{ observationStatus[observation.status] ?? observation.status }} — {{ observation.reason }}</span>
          </div>

          <div v-if="viewingHistory && !running" class="history-note">
            这是历史任务的只读视图；新建任务可重新执行完整工作流。
          </div>
        </template>
      </section>
    </div>
  </div>
</template>

<style scoped>
.agent-page {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.page-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 18px;
  padding: 18px 22px;
  border: 1px solid #ebeef5;
  border-radius: var(--card-radius);
  background: #fff;
  box-shadow: var(--card-shadow);
}

.page-header-copy {
  display: flex;
  align-items: center;
  gap: 14px;
}

.page-glyph {
  display: grid;
  place-items: center;
  width: 42px;
  height: 42px;
  border-radius: 10px;
  color: #fff;
  background: linear-gradient(135deg, #409eff, #79bbff);
  box-shadow: 0 6px 14px rgb(64 158 255 / 30%);
}

.page-header h2 {
  margin: 0;
  font-size: 18px;
  color: #1f2d3d;
}

.page-header p {
  margin: 4px 0 0;
  font-size: 12px;
  color: #909399;
}

.page-header-actions {
  display: flex;
  align-items: center;
  gap: 10px;
}

.agent-grid {
  display: grid;
  grid-template-columns: 430px minmax(0, 1fr);
  gap: 14px;
  align-items: start;
}

.chat-card,
.result-card {
  display: flex;
  flex-direction: column;
  border: 1px solid #ebeef5;
  border-radius: var(--card-radius);
  background: #fff;
  box-shadow: var(--card-shadow);
}

.chat-card {
  position: sticky;
  top: calc(var(--navbar-height) + var(--tags-height) + 14px);
  max-height: calc(100vh - 210px);
}

.chat-header {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 14px 16px;
  border-bottom: 1px solid #f0f2f5;
}

.chat-avatar {
  display: grid;
  place-items: center;
  width: 36px;
  height: 36px;
  border-radius: 10px;
  color: #fff;
  background: linear-gradient(135deg, #409eff, #79bbff);
}

.chat-meta {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 2px;
}

.chat-meta strong {
  font-size: 14px;
  color: #1f2d3d;
}

.chat-meta span {
  font-size: 12px;
  color: #909399;
}

.chat-scroll {
  flex: 1;
  min-height: 260px;
  overflow-y: auto;
  padding: 16px;
  background: #fafbfc;
}

.chat-empty {
  text-align: center;
  padding: 26px 10px;
  color: #909399;
  font-size: 13px;
}

.hint-chip {
  width: 100%;
  margin: 10px 0;
  padding: 10px 12px;
  border: 1px dashed #c6e2ff;
  border-radius: 8px;
  background: #ecf5ff;
  color: #337ecc;
  font-size: 12px;
  line-height: 1.6;
  cursor: pointer;
  transition: border-color .2s;
}

.hint-chip:hover {
  border-color: #409eff;
}

.chat-empty-note {
  margin-top: 8px;
  font-size: 11px;
  color: #b1b3b8;
}

.chat-row {
  display: flex;
  gap: 10px;
  margin-bottom: 14px;
}

.chat-role {
  display: grid;
  place-items: center;
  flex: 0 0 auto;
  width: 34px;
  height: 34px;
  border-radius: 9px;
  color: #fff;
  font-size: 12px;
  font-weight: 600;
  background: #409eff;
}

.chat-row.agent .chat-role {
  background: #0f2438;
}

.chat-bubble {
  max-width: 88%;
  padding: 10px 13px;
  border-radius: 10px;
  font-size: 13px;
  line-height: 1.7;
  white-space: pre-wrap;
  word-break: break-word;
  background: #fff;
  border: 1px solid #ebeef5;
  color: #303133;
}

.chat-row.user {
  flex-direction: row-reverse;
}

.chat-row.user .chat-bubble {
  background: #409eff;
  border-color: #409eff;
  color: #fff;
}

.typing {
  display: flex;
  gap: 5px;
  align-items: center;
  min-height: 20px;
}

.typing span {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: #a0cfff;
  animation: typing-bounce 1.1s infinite ease-in-out;
}

.typing span:nth-child(2) { animation-delay: .15s; }
.typing span:nth-child(3) { animation-delay: .3s; }

@keyframes typing-bounce {
  0%, 80%, 100% { transform: translateY(0); opacity: .5; }
  40% { transform: translateY(-5px); opacity: 1; }
}

.chat-error {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 8px;
  padding: 10px 12px;
  border: 1px solid #fde2e2;
  border-radius: 8px;
  background: #fef0f0;
  color: #f56c6c;
  font-size: 12px;
}

.composer {
  padding: 12px 14px 14px;
  border-top: 1px solid #f0f2f5;
}

.composer-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  margin-top: 10px;
}

.composer-hint {
  font-size: 11px;
  color: #b1b3b8;
}

.history-box {
  border-top: 1px solid #f0f2f5;
  padding: 12px 14px 14px;
  max-height: 220px;
  overflow-y: auto;
}

.history-box header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 8px;
  font-size: 12px;
  color: #606266;
  font-weight: 600;
}

.history-refresh {
  border: 0;
  background: none;
  color: #409eff;
  font-size: 12px;
  cursor: pointer;
}

.history-filter { display: flex; margin-bottom: 8px; }
.history-pages { display: flex; align-items: center; justify-content: flex-end; gap: 8px; padding-top: 4px; color: #909399; font-size: 12px; }

.history-empty {
  font-size: 12px;
  color: #b1b3b8;
}

.history-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  width: 100%;
  margin-bottom: 6px;
  padding: 8px 10px;
  border: 1px solid #f0f2f5;
  border-radius: 8px;
  background: #fff;
  text-align: left;
  cursor: pointer;
  transition: border-color .2s, background .2s;
}

.history-item:hover {
  border-color: #c6e2ff;
  background: #f5faff;
}

.history-item.active {
  border-color: #409eff;
  background: #ecf5ff;
}

.history-message {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 12px;
  color: #303133;
}

.history-side {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 2px;
}

.history-side small {
  color: #b1b3b8;
  font-size: 11px;
}

.result-card {
  min-height: 560px;
  padding: 18px 20px 22px;
}

.result-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 12px;
  min-height: 520px;
  color: #b1b3b8;
  text-align: center;
}

.result-empty h3 {
  margin: 0;
  font-size: 15px;
  color: #606266;
}

.result-empty ol {
  margin: 0;
  padding-left: 20px;
  font-size: 13px;
  line-height: 2;
  text-align: left;
  color: #909399;
}

.result-section + .result-section {
  margin-top: 20px;
}

.section-title {
  margin: 0 0 12px;
  font-size: 14px;
  color: #1f2d3d;
}

.section-title small {
  margin-left: 8px;
  font-size: 12px;
  font-weight: 400;
  color: #b1b3b8;
}

.trace-list {
  display: grid;
  gap: 4px;
  margin: 0;
  padding: 0;
  list-style: none;
}

.trace-item {
  display: flex;
  gap: 10px;
  padding: 7px 10px;
  border-radius: 8px;
  background: #fafbfc;
}

.trace-item.running {
  background: #ecf5ff;
}

.trace-dot {
  display: grid;
  place-items: center;
  width: 18px;
  height: 18px;
  color: #67c23a;
}

.trace-item.running .trace-dot {
  color: #409eff;
}

.spinner {
  width: 12px;
  height: 12px;
  border: 2px solid #c6e2ff;
  border-top-color: #409eff;
  border-radius: 50%;
  animation: spin .8s linear infinite;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}

.trace-body {
  flex: 1;
}

.trace-body strong {
  font-size: 13px;
  font-weight: 500;
  color: #303133;
}

.trace-tools {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 4px;
}

.trace-tool {
  padding: 1px 8px;
  border-radius: 10px;
  background: #f0f2f5;
  color: #909399;
  font-size: 11px;
}

.trace-tool.error {
  background: #fef0f0;
  color: #f56c6c;
}

.review-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 14px;
  margin: 18px 0;
  padding: 14px 16px;
  border: 1px solid #faecd8;
  border-radius: 10px;
  background: #fdf6ec;
}

.review-copy strong {
  font-size: 13px;
  color: #b88230;
}

.review-copy p {
  margin: 6px 0 0;
  font-size: 12px;
  line-height: 1.7;
  color: #7d5b1f;
}

.review-actions {
  display: flex;
  flex: 0 0 auto;
  gap: 8px;
}

.score-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(300px, 1fr));
  gap: 12px;
}

.score-card {
  padding: 14px;
  border: 1px solid #ebeef5;
  border-radius: 10px;
  background: #fff;
  transition: border-color .2s, box-shadow .2s;
}

.score-card:hover {
  border-color: #c6e2ff;
  box-shadow: 0 4px 14px rgb(64 158 255 / 10%);
}

.score-card header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 8px;
}

.score-card header strong {
  display: block;
  margin-bottom: 4px;
  font-size: 14px;
  color: #1f2d3d;
}

.tag-row {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.score-main {
  display: flex;
  align-items: baseline;
  gap: 6px;
  margin: 10px 0;
}

.score-number {
  font-size: 30px;
  font-weight: 700;
  color: #409eff;
  line-height: 1;
}

.score-unit {
  font-size: 12px;
  color: #b1b3b8;
}

.score-bars {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-left: auto;
}

.score-bars i {
  font-style: normal;
  padding: 2px 7px;
  border-radius: 10px;
  background: #f4f6f9;
  color: #606266;
  font-size: 11px;
}

.score-bars em {
  margin-right: 4px;
  font-style: normal;
  color: #909399;
}

.score-reasons {
  margin: 0;
  padding-left: 16px;
  color: #606266;
  font-size: 12px;
  line-height: 1.8;
}

.score-reasons li.pending {
  color: #e6a23c;
}

.report-text {
  margin: 0;
  padding: 16px 18px;
  border: 1px solid #ebeef5;
  border-radius: 10px;
  background: #fafbfc;
  word-break: break-word;
}

.lead-bar {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 18px;
  padding: 12px 16px;
  border: 1px solid #e1f3d8;
  border-radius: 10px;
  background: #f0f9eb;
  color: #529b2e;
  font-size: 13px;
}

.lead-bar span {
  flex: 1;
}

.history-note {
  margin-top: 16px;
  font-size: 12px;
  color: #b1b3b8;
}

.event-picker {
  border-top: 1px solid #f0f2f5;
  padding: 12px 14px 14px;
  max-height: 320px;
  overflow-y: auto;
}

.event-picker-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 8px;
  font-size: 12px;
  color: #606266;
  font-weight: 600;
}

.event-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  margin-bottom: 6px;
  padding: 8px 10px;
  border: 1px solid #f0f2f5;
  border-radius: 8px;
  background: #fff;
}

.event-row-copy {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 2px;
  overflow: hidden;
}

.event-row-copy strong {
  font-size: 13px;
  color: #303133;
}

.event-row-title {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 12px;
  color: #909399;
}

.event-row-tags {
  display: flex;
  align-items: center;
  gap: 6px;
}

.event-row-tags small {
  color: #b1b3b8;
  font-size: 11px;
}

.observation-bar {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 18px;
  padding: 12px 16px;
  border: 1px solid #e9e9eb;
  border-radius: 10px;
  background: #f4f4f5;
  color: #909399;
  font-size: 13px;
}

.observation-bar span {
  flex: 1;
}

@media (max-width: 1100px) {
  .agent-grid {
    grid-template-columns: 1fr;
  }

  .chat-card {
    position: static;
    max-height: none;
  }
}
</style>
