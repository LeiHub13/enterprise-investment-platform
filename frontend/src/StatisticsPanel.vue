<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { Aim, Briefcase, Document, Money, OfficeBuilding, Refresh, Trophy } from '@element-plus/icons-vue'
import { api, ApiError } from './api'
import type { RecordData } from './api'
import { businessStages } from './sections'
import { money } from './format'

const emit = defineEmits<{ expired: [error: unknown] }>()

type Statistics = {
  enterpriseCount: number
  leadCount: number
  businessCount: number
  wonBusinessCount: number
  contractCount: number
  contractAmount: string
  receivablePlanAmount: string
  receivableReceivedAmount: string
  stages: { stage: string; count: number }[]
  owners: RecordData[]
}

const data = ref<Statistics | null>(null)
const loading = ref(false)
const error = ref('')

async function load() {
  loading.value = true
  error.value = ''
  try {
    data.value = await api<Statistics>('/statistics')
  } catch (reason) {
    if (reason instanceof ApiError && reason.status === 401) emit('expired', reason)
    error.value = reason instanceof Error ? reason.message : '业绩统计加载失败'
  } finally {
    loading.value = false
  }
}

const stageBars = computed(() => businessStages.map(stage => ({
  ...stage,
  count: data.value?.stages.find(item => item.stage === stage.value)?.count ?? 0,
})))

const maxStage = computed(() => Math.max(1, ...stageBars.value.map(stage => stage.count)))

const winRate = computed(() => {
  const total = data.value?.businessCount ?? 0
  return total === 0 ? 0 : Math.round(((data.value?.wonBusinessCount ?? 0) / total) * 100)
})

function stageColor(stage: string | number) {
  const colors: Record<string, string> = {
    NEW: '#909399', QUALIFIED: '#409eff', CONTACTED: '#36cfc9',
    NEGOTIATING: '#e6a23c', WON: '#67c23a', LOST: '#f56c6c',
  }
  return colors[String(stage)] ?? '#409eff'
}

function display(value: unknown) {
  return value == null || value === '' ? '—' : String(value)
}

onMounted(() => void load())
</script>

<template>
  <div class="page-header">
    <div class="page-header-copy">
      <div class="page-header-title">
        <span class="page-header-glyph">绩</span>
        <h1>业绩统计</h1>
      </div>
      <p>查看线索、商机、合同与回款的整体转化和负责人业绩。</p>
    </div>
    <div class="page-actions">
      <el-button :loading="loading" @click="load">
        <el-icon><Refresh /></el-icon>刷新
      </el-button>
    </div>
  </div>

  <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon class="crm-error" />

  <template v-if="data">
    <div class="stat-grid">
      <article class="stat-card">
        <span class="stat-icon stat-blue"><el-icon :size="22"><OfficeBuilding /></el-icon></span>
        <div><small>客户总数</small><strong>{{ data.enterpriseCount }}</strong><em>企业档案</em></div>
      </article>
      <article class="stat-card">
        <span class="stat-icon stat-cyan"><el-icon :size="22"><Aim /></el-icon></span>
        <div><small>线索总数</small><strong>{{ data.leadCount }}</strong><em>当前可见范围</em></div>
      </article>
      <article class="stat-card">
        <span class="stat-icon stat-indigo"><el-icon :size="22"><Briefcase /></el-icon></span>
        <div><small>商机总数</small><strong>{{ data.businessCount }}</strong><em>赢单率 {{ winRate }}%</em></div>
      </article>
      <article class="stat-card">
        <span class="stat-icon stat-green"><el-icon :size="22"><Trophy /></el-icon></span>
        <div><small>赢单商机</small><strong>{{ data.wonBusinessCount }}</strong><em>已签约商机</em></div>
      </article>
      <article class="stat-card">
        <span class="stat-icon stat-orange"><el-icon :size="22"><Document /></el-icon></span>
        <div><small>合同金额（元）</small><strong class="stat-money">{{ money(data.contractAmount) }}</strong><em>共 {{ data.contractCount }} 份合同</em></div>
      </article>
      <article class="stat-card">
        <span class="stat-icon stat-red"><el-icon :size="22"><Money /></el-icon></span>
        <div><small>已回款（元）</small><strong class="stat-money">{{ money(data.receivableReceivedAmount) }}</strong><em>计划 {{ money(data.receivablePlanAmount) }}</em></div>
      </article>
    </div>

    <div class="stats-columns">
      <section class="list-card stats-card">
        <div class="card-heading">
          <div><h2>商机阶段分布</h2><p>当前可见商机在各阶段的分布</p></div>
        </div>
        <div class="stage-bars">
          <div v-for="stage in stageBars" :key="stage.value" class="stage-row">
            <span class="stage-name">{{ stage.label }}</span>
            <div class="stage-track">
              <div class="stage-fill" :style="{ width: (stage.count / maxStage * 100) + '%', background: stageColor(stage.value) }" />
            </div>
            <strong>{{ stage.count }}</strong>
          </div>
        </div>
      </section>

      <section class="list-card stats-card">
        <div class="card-heading">
          <div><h2>回款进度</h2><p>合同金额与回款完成情况</p></div>
        </div>
        <div class="receivable-summary">
          <div><span>合同金额</span><strong>{{ money(data.contractAmount) }}</strong></div>
          <div><span>计划回款</span><strong>{{ money(data.receivablePlanAmount) }}</strong></div>
          <div><span>已回款</span><strong class="received">{{ money(data.receivableReceivedAmount) }}</strong></div>
          <div class="progress-wrap">
            <el-progress :percentage="Number(data.receivablePlanAmount) === 0 ? 0 :
              Math.min(100, Math.round(Number(data.receivableReceivedAmount) / Number(data.receivablePlanAmount) * 100))"
              :stroke-width="10" />
          </div>
        </div>
      </section>
    </div>

    <section class="list-card owner-card">
      <div class="card-heading">
        <div><h2>负责人业绩</h2><p>按负责人汇总线索、商机、合同和回款</p></div>
      </div>
      <el-table :data="data.owners" empty-text="暂无业绩数据">
        <el-table-column label="负责人" min-width="120">
          <template #default="{ row }">{{ display(row.owner) }}</template>
        </el-table-column>
        <el-table-column label="线索" min-width="80">
          <template #default="{ row }">{{ row.leadCount ?? 0 }}</template>
        </el-table-column>
        <el-table-column label="商机" min-width="80">
          <template #default="{ row }">{{ row.businessCount ?? 0 }}</template>
        </el-table-column>
        <el-table-column label="赢单" min-width="80">
          <template #default="{ row }">{{ row.wonCount ?? 0 }}</template>
        </el-table-column>
        <el-table-column label="合同金额（元）" min-width="140">
          <template #default="{ row }">{{ money(row.contractAmount) }}</template>
        </el-table-column>
        <el-table-column label="已回款（元）" min-width="140">
          <template #default="{ row }">{{ money(row.receivedAmount) }}</template>
        </el-table-column>
      </el-table>
    </section>
  </template>
</template>

<style scoped>
.crm-error { margin-bottom: 14px; }
.stat-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 14px; margin-bottom: 16px; }
.stat-card { display: flex; align-items: center; gap: 14px; min-height: 96px; padding: 18px 20px; border: 1px solid #ebeef5; border-radius: var(--card-radius); background: #fff; box-shadow: var(--card-shadow); }
.stat-icon { display: grid; flex: 0 0 auto; place-items: center; width: 44px; height: 44px; border-radius: 11px; }
.stat-blue { color: #409eff; background: #ecf5ff; }
.stat-cyan { color: #13c2c2; background: #e6fffb; }
.stat-indigo { color: #5b6cf9; background: #eef0ff; }
.stat-green { color: #67c23a; background: #f0f9eb; }
.stat-orange { color: #e6a23c; background: #fdf6ec; }
.stat-red { color: #f56c6c; background: #fef0f0; }
.stat-card small, .stat-card strong, .stat-card em { display: block; }
.stat-card small { color: #8a97a5; font-size: 12px; }
.stat-card strong { margin-top: 3px; color: #1f2d3d; font-size: 25px; font-weight: 700; line-height: 1.2; }
.stat-card .stat-money { font-size: 20px; }
.stat-card em { margin-top: 3px; color: #b0bac4; font-size: 11px; font-style: normal; }
.stats-columns { display: grid; grid-template-columns: minmax(0, 1.15fr) minmax(280px, .85fr); gap: 14px; margin-bottom: 16px; }
.stats-card { overflow: hidden; }
.card-heading { padding: 16px 20px; border-bottom: 1px solid #ebeef5; }
.card-heading h2 { margin: 0; color: #1f2d3d; font-size: 15px; font-weight: 600; }
.card-heading p { margin: 5px 0 0; color: #909399; font-size: 12px; }
.stage-bars { display: grid; gap: 15px; padding: 20px; }
.stage-row { display: grid; grid-template-columns: 78px minmax(0, 1fr) 40px; align-items: center; gap: 10px; }
.stage-name { color: #5a6a7a; font-size: 12px; }
.stage-track { height: 9px; overflow: hidden; border-radius: 5px; background: #f0f2f5; }
.stage-fill { height: 100%; border-radius: 5px; transition: width .4s; }
.stage-row strong { color: #303133; font-size: 13px; text-align: right; }
.receivable-summary { display: grid; gap: 14px; padding: 20px; }
.receivable-summary > div:not(.progress-wrap) { display: flex; align-items: center; justify-content: space-between; gap: 10px; }
.receivable-summary span { color: #8a97a5; font-size: 12px; }
.receivable-summary strong { color: #1f2d3d; font-size: 16px; }
.receivable-summary .received { color: #67c23a; }
.progress-wrap { margin-top: 4px; }
.owner-card { overflow: hidden; }
@media (max-width: 1180px) {
  .stat-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .stats-columns { grid-template-columns: minmax(0, 1fr); }
}
@media (max-width: 640px) {
  .stat-grid { grid-template-columns: minmax(0, 1fr); }
}
</style>
