<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { Refresh } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { api, ApiError, query } from './api'
import type { Page, RecordData } from './api'

const emit = defineEmits<{ expired: [error: unknown] }>()
defineProps<{ canManage: boolean }>()

type Summary = {
  recordCount: number
  signalCount: number
  fetched24h: number
  lastFetchedAt: string | null
  demoRecordCount: number
}

type SourceStatus = {
  source: string
  recordType: string
  recordCount: number
  fetched24h: number
  lastFetchedAt: string | null
  demoCount: number
}

type MonitorTask = {
  id: number; enterpriseId: number; enterpriseName: string; mode: 'DEMO' | 'LIVE'
  intervalMinutes: number; maxPages: number; enabled: boolean | number
  nextRunAt: string | null; lastStatus: string | null; lastErrorCode: string | null
  lastFinishedAt: string | null; lastSuccessAt: string | null; possiblyTruncated: boolean | number
  consecutiveFailures: number; running: boolean | number
}

const summary = ref<Summary | null>(null)
const sources = ref<SourceStatus[]>([])
const activeTab = ref<'records' | 'signals' | 'monitor'>('records')
const errorText = ref('')
const overviewLoading = ref(false)
const recordsLoading = ref(false)
const signalsLoading = ref(false)
const records = ref<RecordData[]>([])
const signals = ref<RecordData[]>([])
const recordTotal = ref(0)
const signalTotal = ref(0)
const recordPage = ref(1)
const signalPage = ref(1)
const pageSize = 20
const recordFilters = ref<{ source: string | null; recordType: string | null; keyword: string }>({
  source: null,
  recordType: null,
  keyword: '',
})
const signalKeyword = ref('')
const monitors = ref<MonitorTask[]>([])
const monitorLoading = ref(false)
const monitorPage = ref(1)
const monitorTotal = ref(0)
const companyOptions = ref<{ id: number; name: string }[]>([])
const companyLoading = ref(false)
const monitorSaving = ref(false)
const monitorForm = ref<{ enterpriseId: number | null; mode: 'DEMO' | 'LIVE'; intervalMinutes: number; maxPages: number }>({
  enterpriseId: null, mode: 'DEMO', intervalMinutes: 1440, maxPages: 1,
})
const runDialog = ref(false)
const monitorRuns = ref<RecordData[]>([])

const sourceOptions = [
  { value: 'QCC', label: '企查查' },
  { value: '58', label: '58 同城' },
  { value: 'MANUAL', label: '人工录入' },
]
const recordTypeOptions = [
  { value: 'RECRUITMENT', label: '招聘信号' },
  { value: 'RENTAL', label: '求租信号' },
]

function fail(error: unknown) {
  if (error instanceof ApiError && error.status === 401) emit('expired', error)
  errorText.value = error instanceof Error ? error.message : '无法加载采集器数据'
}

function labelSource(value: unknown) {
  return sourceOptions.find(option => option.value === value)?.label ?? String(value ?? '—')
}

function labelRecordType(value: unknown) {
  return recordTypeOptions.find(option => option.value === value)?.label ?? String(value ?? '—')
}

function labelValidity(value: unknown) {
  const labels: Record<string, string> = { VALID: '时效内', EXPIRED: '已失效', SUPERSEDED: '历史版本' }
  return labels[String(value)] ?? '待核实'
}

function labelSignalType(value: unknown) {
  const labels: Record<string, string> = {
    RECRUITMENT: '招聘扩张',
    RENTAL: '租赁需求',
    FUNDING: '融资',
    BRANCH: '分支机构',
    SCOPE_CHANGE: '经营范围变更',
    RELOCATION: '迁址/扩租',
    DEPARTMENT: '部门设立',
  }
  return labels[String(value)] ?? String(value ?? '—')
}

function labelStrength(value: unknown) {
  return Number(value) === 3 ? '强' : Number(value) === 2 ? '中' : '弱'
}

function strengthType(value: unknown) {
  return Number(value) === 3 ? 'danger' : Number(value) === 2 ? 'warning' : 'info'
}

function when(value: unknown) {
  if (!value) return '—'
  return String(value).replace('T', ' ').slice(0, 19)
}

function percent(value: unknown) {
  const number = Number(value)
  return Number.isFinite(number) ? Math.round(number * 100) + '%' : '—'
}

function enabled(value: unknown) { return value === true || Number(value) === 1 }

function monitorStatus(task: MonitorTask) {
  if (enabled(task.running)) return '执行中'
  if (task.lastStatus === 'FAILED') return `失败${enabled(task.enabled) ? '' : ' · 已暂停'}`
  if (!enabled(task.enabled)) return '已暂停'
  return ({ SUCCESS: '上次成功', EMPTY: '上次无记录', ABANDONED: '上次中断' } as Record<string, string>)[task.lastStatus ?? ''] ?? '等待执行'
}

function monitorError(value: unknown) {
  return ({ QCC_CONFIG: '企查查配置缺失', QCC_FAILURE: '企查查查询失败', UPLOAD_FAILURE: '业务服务写入失败', UNKNOWN: '执行异常' } as Record<string, string>)[String(value)] ?? '—'
}

async function loadMonitors() {
  monitorLoading.value = true
  try {
    const result = await api<Page<MonitorTask>>('/monitor/tasks' + query({ page: monitorPage.value, size: pageSize }))
    monitors.value = result.items
    monitorTotal.value = result.total
  } catch (error) { fail(error) } finally { monitorLoading.value = false }
}

async function searchCompanies(keyword: string) {
  if (!keyword.trim()) { companyOptions.value = []; return }
  companyLoading.value = true
  try {
    const result = await api<Page<RecordData>>('/enterprises' + query({ keyword: keyword.trim(), page: 1, size: 20 }))
    companyOptions.value = result.items.map(item => ({ id: item.id, name: String(item.name ?? '') }))
  } catch (error) { fail(error) } finally { companyLoading.value = false }
}

async function createMonitor() {
  if (!monitorForm.value.enterpriseId) { ElMessage.warning('请选择已入库企业'); return }
  monitorSaving.value = true
  try {
    await api('/monitor/tasks', 'POST', monitorForm.value)
    monitorForm.value.enterpriseId = null
    monitorPage.value = 1
    ElMessage.success('监测任务已创建，默认暂停')
    await loadMonitors()
  } catch (error) { fail(error) } finally { monitorSaving.value = false }
}

async function saveMonitor(task: MonitorTask, nextEnabled: boolean) {
  try {
    await api(`/monitor/tasks/${task.id}`, 'PUT', { intervalMinutes: task.intervalMinutes, maxPages: task.maxPages, enabled: nextEnabled })
    ElMessage.success(nextEnabled ? '监测设置已保存并启用' : '监测设置已保存并暂停')
    await loadMonitors()
  } catch (error) { fail(error); await loadMonitors() }
}

async function showMonitorRuns(task: MonitorTask) {
  try {
    monitorRuns.value = await api<RecordData[]>(`/monitor/tasks/${task.id}/runs`)
    runDialog.value = true
  } catch (error) { fail(error) }
}

async function loadOverview() {
  overviewLoading.value = true
  try {
    const result = await api<{ summary: Summary; sources: SourceStatus[] }>('/governance/collector/summary')
    summary.value = result.summary
    sources.value = result.sources ?? []
  } catch (error) {
    fail(error)
  } finally {
    overviewLoading.value = false
  }
}

async function loadRecords() {
  recordsLoading.value = true
  try {
    const result = await api<Page<RecordData>>('/governance/collector/records' + query({
      source: recordFilters.value.source,
      recordType: recordFilters.value.recordType,
      keyword: recordFilters.value.keyword,
      page: recordPage.value,
      size: pageSize,
    }))
    records.value = result.items
    recordTotal.value = result.total
  } catch (error) {
    fail(error)
  } finally {
    recordsLoading.value = false
  }
}

async function loadSignals() {
  signalsLoading.value = true
  try {
    const result = await api<Page<RecordData>>('/governance/collector/signals' + query({
      keyword: signalKeyword.value,
      page: signalPage.value,
      size: pageSize,
    }))
    signals.value = result.items
    signalTotal.value = result.total
  } catch (error) {
    fail(error)
  } finally {
    signalsLoading.value = false
  }
}

async function refresh() {
  await Promise.all([loadOverview(), loadRecords(), loadSignals(), loadMonitors()])
}

async function searchRecords() {
  recordPage.value = 1
  await loadRecords()
}

async function searchSignals() {
  signalPage.value = 1
  await loadSignals()
}

async function resetRecords() {
  recordFilters.value = { source: null, recordType: null, keyword: '' }
  recordPage.value = 1
  await loadRecords()
}

async function changeRecordPage(page: number) {
  recordPage.value = page
  await loadRecords()
}

async function changeSignalPage(page: number) {
  signalPage.value = page
  await loadSignals()
}

onMounted(() => void refresh())
</script>

<template>
  <div class="page-header">
    <div class="page-header-copy">
      <div class="page-header-title">
        <span class="page-header-glyph">采</span>
        <h1>采集器</h1>
      </div>
      <p>查看企业招聘与求租来源记录，配置按企业执行的招聘监测任务。</p>
    </div>
    <div class="page-actions">
      <el-tag v-if="!canManage" type="info" effect="plain">只读</el-tag>
      <el-button :loading="overviewLoading && recordsLoading && signalsLoading" @click="refresh">
        <el-icon><Refresh /></el-icon>刷新
      </el-button>
    </div>
  </div>

  <el-alert
    title="监测任务由独立采集进程领取执行；新建任务默认暂停，正式模式需要企查查授权和凭证。"
    type="info" :closable="false" show-icon class="collector-alert" />
  <el-alert v-if="errorText" :title="errorText" type="error" :closable="false" show-icon class="collector-alert" />

  <div class="collector-stats">
    <article class="collector-stat"><small>采集记录（含演示）</small><strong>{{ summary?.recordCount ?? 0 }}</strong><em>其中演示 {{ summary?.demoRecordCount ?? 0 }} 条</em></article>
    <article class="collector-stat"><small>企业信号（含演示）</small><strong>{{ summary?.signalCount ?? 0 }}</strong><em>全部信号</em></article>
    <article class="collector-stat"><small>24 小时新增（含演示）</small><strong>{{ summary?.fetched24h ?? 0 }}</strong><em>最近一天采集</em></article>
    <article class="collector-stat"><small>最近采集（含演示）</small><strong class="collector-time">{{ when(summary?.lastFetchedAt) }}</strong><em>最后入库时间</em></article>
  </div>

  <section class="list-card collector-sources" v-loading="overviewLoading">
    <div class="collector-card-heading">
      <div><strong>来源状态</strong><p>按来源和记录类型汇总，便于判断采集器是否在运行。</p></div>
    </div>
    <el-table :data="sources" empty-text="暂无采集记录" size="small">
      <el-table-column label="来源" min-width="110">
        <template #default="{ row }"><el-tag effect="plain">{{ labelSource(row.source) }}</el-tag></template>
      </el-table-column>
      <el-table-column label="类型" min-width="110">
        <template #default="{ row }">{{ labelRecordType(row.recordType) }}</template>
      </el-table-column>
      <el-table-column prop="recordCount" label="记录数" width="90" />
      <el-table-column prop="demoCount" label="演示数" width="90" />
      <el-table-column prop="fetched24h" label="24 小时" width="95" />
      <el-table-column label="最近采集" min-width="165">
        <template #default="{ row }">{{ when(row.lastFetchedAt) }}</template>
      </el-table-column>
    </el-table>
  </section>

  <section class="list-card collector-list">
    <el-tabs v-model="activeTab">
      <el-tab-pane label="监测任务" name="monitor">
        <el-form v-if="canManage" inline @submit.prevent="createMonitor">
          <el-form-item label="企业">
            <el-select v-model="monitorForm.enterpriseId" filterable remote reserve-keyword placeholder="搜索已入库企业"
              :remote-method="searchCompanies" :loading="companyLoading" style="width: 240px">
              <el-option v-for="item in companyOptions" :key="item.id" :label="item.name" :value="item.id" />
            </el-select>
          </el-form-item>
          <el-form-item label="模式">
            <el-select v-model="monitorForm.mode" style="width: 125px">
              <el-option label="演示数据" value="DEMO" /><el-option label="正式企查查" value="LIVE" />
            </el-select>
          </el-form-item>
          <el-form-item label="间隔（分钟）"><el-input-number v-model="monitorForm.intervalMinutes" :min="60" :max="43200" :step="60" /></el-form-item>
          <el-form-item label="最多页数"><el-input-number v-model="monitorForm.maxPages" :min="1" :max="10" /></el-form-item>
          <el-form-item><el-button type="primary" native-type="submit" :loading="monitorSaving">创建暂停任务</el-button></el-form-item>
        </el-form>
        <p class="monitor-hint">正式模式按企查查当前规则计费；启用任务后仍需启动采集进程。连续失败三次会自动暂停。</p>
        <el-table v-loading="monitorLoading" :data="monitors" empty-text="暂无监测任务">
          <el-table-column prop="enterpriseName" label="企业" min-width="180" show-overflow-tooltip />
          <el-table-column label="模式" width="105"><template #default="{ row }"><el-tag :type="row.mode === 'DEMO' ? 'warning' : 'primary'" size="small">{{ row.mode === 'DEMO' ? '演示' : '正式' }}</el-tag></template></el-table-column>
          <el-table-column label="间隔（分）" width="150"><template #default="{ row }"><el-input-number v-if="canManage" v-model="row.intervalMinutes" :min="60" :max="43200" :step="60" size="small" /><span v-else>{{ row.intervalMinutes }}</span></template></el-table-column>
          <el-table-column label="页数" width="135"><template #default="{ row }"><el-input-number v-if="canManage" v-model="row.maxPages" :min="1" :max="10" size="small" /><span v-else>{{ row.maxPages }}</span></template></el-table-column>
          <el-table-column label="状态" width="135"><template #default="{ row }">{{ monitorStatus(row) }}</template></el-table-column>
          <el-table-column label="下次执行" width="165"><template #default="{ row }">{{ enabled(row.enabled) ? when(row.nextRunAt) : '—' }}</template></el-table-column>
          <el-table-column label="最近结果" min-width="150"><template #default="{ row }">{{ row.lastStatus === 'FAILED' ? monitorError(row.lastErrorCode) : when(row.lastFinishedAt) }}<span v-if="enabled(row.possiblyTruncated)"> · 可能截断</span></template></el-table-column>
          <el-table-column label="操作" width="205" fixed="right"><template #default="{ row }">
            <el-button link type="primary" @click="showMonitorRuns(row)">运行记录</el-button>
            <template v-if="canManage"><el-button link type="primary" @click="saveMonitor(row, enabled(row.enabled))">保存</el-button><el-button link :type="enabled(row.enabled) ? 'warning' : 'success'" @click="saveMonitor(row, !enabled(row.enabled))">{{ enabled(row.enabled) ? '暂停' : '启用' }}</el-button></template>
          </template></el-table-column>
        </el-table>
        <div class="collector-pager"><el-pagination :current-page="monitorPage" :page-size="pageSize" :total="monitorTotal"
          layout="total, prev, pager, next" background @current-change="(page: number) => { monitorPage = page; loadMonitors() }" /></div>
      </el-tab-pane>
      <el-tab-pane label="采集记录" name="records">
        <el-form class="filters" inline @submit.prevent="searchRecords">
          <el-form-item>
            <el-select v-model="recordFilters.source" placeholder="全部来源" clearable :value-on-clear="null" style="width: 140px">
              <el-option v-for="option in sourceOptions" :key="option.value" v-bind="option" />
            </el-select>
          </el-form-item>
          <el-form-item>
            <el-select v-model="recordFilters.recordType" placeholder="全部类型" clearable :value-on-clear="null" style="width: 140px">
              <el-option v-for="option in recordTypeOptions" :key="option.value" v-bind="option" />
            </el-select>
          </el-form-item>
          <el-form-item>
            <el-input v-model="recordFilters.keyword" placeholder="企业、标题或摘要" clearable style="width: 220px" />
          </el-form-item>
          <el-form-item>
            <el-button type="primary" native-type="submit">查询</el-button>
            <el-button @click="resetRecords">重置</el-button>
          </el-form-item>
        </el-form>
        <el-table v-loading="recordsLoading" :data="records" empty-text="暂无采集记录">
          <el-table-column prop="enterpriseName" label="企业" min-width="180" show-overflow-tooltip />
          <el-table-column label="来源" width="105">
            <template #default="{ row }"><el-tag size="small" effect="plain">{{ labelSource(row.source) }}</el-tag></template>
          </el-table-column>
          <el-table-column label="类型" width="105">
            <template #default="{ row }">{{ labelRecordType(row.recordType) }}</template>
          </el-table-column>
          <el-table-column prop="title" label="标题" min-width="260" show-overflow-tooltip />
          <el-table-column label="数据性质" width="110"><template #default="{ row }"><el-tag v-if="Number(row.isDemo) === 1" type="warning" size="small">演示</el-tag><span v-else>未标演示</span></template></el-table-column>
          <el-table-column label="版本" width="96"><template #default="{ row }">{{ row.changeType === 'UPDATED' ? '更新' : '新增' }} · {{ row.recordVersion ?? 1 }}</template></el-table-column>
          <el-table-column label="状态" width="105"><template #default="{ row }">{{ labelValidity(row.validityStatus) }}</template></el-table-column>
          <el-table-column label="发布" width="120">
            <template #default="{ row }">{{ when(row.publishedAt) }}</template>
          </el-table-column>
          <el-table-column label="采集时间" width="125">
            <template #default="{ row }">{{ when(row.fetchedAt) }}</template>
          </el-table-column>
        </el-table>
        <div class="collector-pager">
          <el-pagination :current-page="recordPage" :page-size="pageSize" :total="recordTotal"
            layout="total, prev, pager, next" background @current-change="changeRecordPage" />
        </div>
      </el-tab-pane>

      <el-tab-pane label="企业信号" name="signals">
        <el-form class="filters" inline @submit.prevent="searchSignals">
          <el-form-item>
            <el-input v-model="signalKeyword" placeholder="企业、标题或信号内容" clearable style="width: 260px" />
          </el-form-item>
          <el-form-item>
            <el-button type="primary" native-type="submit">查询</el-button>
            <el-button @click="signalKeyword = ''; signalPage = 1; loadSignals()">重置</el-button>
          </el-form-item>
        </el-form>
        <el-table v-loading="signalsLoading" :data="signals" empty-text="暂无企业信号">
          <el-table-column prop="enterpriseName" label="企业" min-width="175" show-overflow-tooltip />
          <el-table-column label="信号" width="105">
            <template #default="{ row }">{{ labelSignalType(row.signalType) }}</template>
          </el-table-column>
          <el-table-column prop="title" label="标题" min-width="240" show-overflow-tooltip />
          <el-table-column label="数据性质" width="110"><template #default="{ row }"><el-tag v-if="Number(row.isDemo) === 1" type="warning" size="small">演示</el-tag><span v-else>未标演示</span></template></el-table-column>
          <el-table-column prop="sourceName" label="来源" min-width="110" show-overflow-tooltip />
          <el-table-column label="状态" width="105"><template #default="{ row }">{{ labelValidity(row.validityStatus) }}</template></el-table-column>
          <el-table-column label="观察日期" width="115">
            <template #default="{ row }">{{ when(row.observedAt) }}</template>
          </el-table-column>
          <el-table-column label="强度" width="80">
            <template #default="{ row }"><el-tag size="small" :type="strengthType(row.strength)" effect="plain">{{ labelStrength(row.strength) }}</el-tag></template>
          </el-table-column>
          <el-table-column label="置信度" width="90">
            <template #default="{ row }">{{ percent(row.confidence) }}</template>
          </el-table-column>
        </el-table>
        <div class="collector-pager">
          <el-pagination :current-page="signalPage" :page-size="pageSize" :total="signalTotal"
            layout="total, prev, pager, next" background @current-change="changeSignalPage" />
        </div>
      </el-tab-pane>
    </el-tabs>
  </section>
  <el-dialog v-model="runDialog" title="监测运行记录" width="min(850px, 90vw)">
    <el-table :data="monitorRuns" empty-text="暂无运行记录">
      <el-table-column prop="status" label="状态" width="110" />
      <el-table-column label="失败原因" min-width="150"><template #default="{ row }">{{ monitorError(row.errorCode) }}</template></el-table-column>
      <el-table-column prop="createdRecords" label="新增" width="70" />
      <el-table-column prop="updatedRecords" label="更新" width="70" />
      <el-table-column prop="pagesFetched" label="查询页数" width="90" />
      <el-table-column label="开始时间" min-width="160"><template #default="{ row }">{{ when(row.startedAt) }}</template></el-table-column>
    </el-table>
  </el-dialog>
</template>

<style scoped>
.collector-alert { margin-bottom: 14px; }
.collector-stats { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 14px; margin-bottom: 14px; }
.collector-stat { min-height: 92px; padding: 16px 18px; border: 1px solid #ebeef5; border-radius: var(--card-radius); background: #fff; box-shadow: var(--card-shadow); }
.collector-stat small, .collector-stat strong, .collector-stat em { display: block; }
.collector-stat small { color: #8a97a5; font-size: 12px; }
.collector-stat strong { margin-top: 4px; color: #1f2d3d; font-size: 24px; line-height: 1.25; }
.collector-stat .collector-time { font-size: 15px; font-weight: 600; }
.collector-stat em { margin-top: 3px; color: #b0bac4; font-size: 11px; font-style: normal; }
.collector-sources { margin-bottom: 14px; padding: 16px 18px; }
.collector-card-heading { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; margin-bottom: 10px; }
.collector-card-heading strong { color: #1f2d3d; font-size: 15px; }
.collector-card-heading p { margin: 4px 0 0; color: #8a97a5; font-size: 12px; }
.collector-list { padding: 6px 18px 18px; }
.monitor-hint { color: #8a97a5; font-size: 12px; margin: 0 0 14px; }
.collector-pager { display: flex; justify-content: flex-end; margin-top: 14px; }
@media (max-width: 900px) { .collector-stats { grid-template-columns: repeat(2, minmax(0, 1fr)); } }
@media (max-width: 560px) { .collector-stats { grid-template-columns: minmax(0, 1fr); } }
</style>
