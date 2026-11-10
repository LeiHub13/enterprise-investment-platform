<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import { api, ApiError } from './api'
import type { RecordData } from './api'
import { businessStages, leadStages } from './sections'
import { dateText, isOverdue, money } from './format'

const props = defineProps<{ enterpriseId: number }>()
const emit = defineEmits<{ expired: [error: unknown] }>()

type Customer360 = {
  customer: RecordData
  contacts: RecordData[]
  leads: RecordData[]
  businesses: RecordData[]
  contracts: RecordData[]
  receivables: RecordData[]
  summary: {
    contactCount: number
    leadCount: number
    businessCount: number
    businessAmount: string
    contractCount: number
    contractAmount: string
    receivableCount: number
    receivablePlanAmount: string
    receivableReceivedAmount: string
  }
}

const data = ref<Customer360 | null>(null)
const loading = ref(false)
const error = ref('')

async function load() {
  loading.value = true
  error.value = ''
  try {
    data.value = await api<Customer360>('/customers/' + props.enterpriseId + '/360')
  } catch (reason) {
    if (reason instanceof ApiError && reason.status === 401) emit('expired', reason)
    error.value = reason instanceof Error ? reason.message : '客户 360 加载失败'
  } finally {
    loading.value = false
  }
}

function stageLabel(value: unknown) {
  return leadStages.find(stage => stage.value === value)?.label ?? String(value ?? '—')
}

function businessStageLabel(value: unknown) {
  return businessStages.find(stage => stage.value === value)?.label ?? String(value ?? '—')
}

function stageTone(value: unknown) {
  if (value === 'WON') return 'success'
  if (value === 'LOST') return 'danger'
  if (value === 'NEGOTIATING') return 'warning'
  return 'info'
}

function contractStatus(value: unknown) {
  return ({ DRAFT: '草稿', SIGNED: '已签约', TERMINATED: '已终止' } as Record<string, string>)[String(value)] ?? value
}

function contractTone(value: unknown) {
  if (value === 'SIGNED') return 'success'
  if (value === 'TERMINATED') return 'danger'
  return 'info'
}

function receivableStatus(row: RecordData) {
  if (row.status === 'RECEIVED') return '已回款'
  if (isOverdue(row.status, row.planDate)) return '已逾期'
  return '待回款'
}

function receivableTone(row: RecordData) {
  if (row.status === 'RECEIVED') return 'success'
  if (isOverdue(row.status, row.planDate)) return 'danger'
  return 'warning'
}

onMounted(() => void load())
watch(() => props.enterpriseId, () => void load())
</script>

<template>
  <section class="c360" v-loading="loading">
    <div class="c360-heading">
      <div>
        <h3>客户 360</h3>
        <p>该客户的线索、商机、合同与回款汇总。</p>
      </div>
      <el-button text type="primary" @click="load">刷新</el-button>
    </div>
    <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon class="c360-error" />

    <template v-if="data">
      <div class="c360-summary">
        <div class="c360-stat">
          <small>联系人</small><strong>{{ data.summary.contactCount }}</strong><em>企业联系人</em>
        </div>
        <div class="c360-stat">
          <small>线索</small><strong>{{ data.summary.leadCount }}</strong><em>关联线索</em>
        </div>
        <div class="c360-stat">
          <small>商机</small><strong>{{ data.summary.businessCount }}</strong><em>预计 {{ money(data.summary.businessAmount) }}</em>
        </div>
        <div class="c360-stat">
          <small>合同</small><strong>{{ data.summary.contractCount }}</strong><em>金额 {{ money(data.summary.contractAmount) }}</em>
        </div>
        <div class="c360-stat">
          <small>回款</small><strong>{{ data.summary.receivableCount }}</strong>
          <em>已收 {{ money(data.summary.receivableReceivedAmount) }} / 计划 {{ money(data.summary.receivablePlanAmount) }}</em>
        </div>
      </div>

      <el-tabs class="c360-tabs">
        <el-tab-pane :label="`商机 ${data.businesses.length}`">
          <el-table :data="data.businesses" size="small" empty-text="暂无商机">
            <el-table-column prop="name" label="商机名称" min-width="180" show-overflow-tooltip />
            <el-table-column label="阶段" min-width="90">
              <template #default="{ row }">
                <el-tag :type="stageTone(row.stage)" size="small" effect="light">{{ businessStageLabel(row.stage) }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="金额" min-width="120">
              <template #default="{ row }">{{ money(row.amount) }}</template>
            </el-table-column>
            <el-table-column prop="ownerUsername" label="负责人" min-width="90">
              <template #default="{ row }">{{ row.ownerUsername || '—' }}</template>
            </el-table-column>
            <el-table-column label="预计签约" min-width="105">
              <template #default="{ row }">{{ dateText(row.expectedCloseDate) }}</template>
            </el-table-column>
          </el-table>
        </el-tab-pane>

        <el-tab-pane :label="`合同 ${data.contracts.length}`">
          <el-table :data="data.contracts" size="small" empty-text="暂无合同">
            <el-table-column prop="contractNo" label="合同编号" min-width="160" show-overflow-tooltip />
            <el-table-column prop="name" label="合同名称" min-width="170" show-overflow-tooltip />
            <el-table-column label="金额" min-width="120">
              <template #default="{ row }">{{ money(row.amount) }}</template>
            </el-table-column>
            <el-table-column label="状态" min-width="90">
              <template #default="{ row }">
                <el-tag :type="contractTone(row.status)" size="small" effect="light">{{ contractStatus(row.status) }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="签约日期" min-width="105">
              <template #default="{ row }">{{ dateText(row.signDate) }}</template>
            </el-table-column>
          </el-table>
        </el-tab-pane>

        <el-tab-pane :label="`回款 ${data.receivables.length}`">
          <el-table :data="data.receivables" size="small" empty-text="暂无回款">
            <el-table-column prop="receivableNo" label="回款编号" min-width="160" show-overflow-tooltip />
            <el-table-column label="计划金额" min-width="110">
              <template #default="{ row }">{{ money(row.planAmount) }}</template>
            </el-table-column>
            <el-table-column label="实收金额" min-width="110">
              <template #default="{ row }">{{ money(row.receivedAmount) }}</template>
            </el-table-column>
            <el-table-column label="计划日期" min-width="105">
              <template #default="{ row }">{{ dateText(row.planDate) }}</template>
            </el-table-column>
            <el-table-column label="状态" min-width="90">
              <template #default="{ row }">
                <el-tag :type="receivableTone(row)" size="small" effect="light">{{ receivableStatus(row) }}</el-tag>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>

        <el-tab-pane :label="`线索 ${data.leads.length}`">
          <el-table :data="data.leads" size="small" empty-text="暂无线索">
            <el-table-column prop="projectName" label="招商项目" min-width="170" show-overflow-tooltip>
              <template #default="{ row }">{{ row.projectName || '—' }}</template>
            </el-table-column>
            <el-table-column label="阶段" min-width="90">
              <template #default="{ row }">
                <el-tag :type="stageTone(row.leadStatus)" size="small" effect="light">{{ stageLabel(row.leadStatus) }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="ownerUsername" label="负责人" min-width="90">
              <template #default="{ row }">{{ row.ownerUsername || '—' }}</template>
            </el-table-column>
            <el-table-column label="下次跟进" min-width="130">
              <template #default="{ row }">{{ dateText(row.nextFollowupAt, true) }}</template>
            </el-table-column>
          </el-table>
        </el-tab-pane>
      </el-tabs>
    </template>
  </section>
</template>

<style scoped>
.c360 {
  margin: 0 0 18px;
  padding: 18px 20px;
  border: 1px solid #ebeef5;
  border-radius: 8px;
  background: #fff;
}
.c360-heading { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; margin-bottom: 14px; }
.c360-heading h3 { margin: 0; color: #1f2d3d; font-size: 15px; font-weight: 600; }
.c360-heading p { margin: 5px 0 0; color: #909399; font-size: 12px; }
.c360-error { margin-bottom: 12px; }
.c360-summary { display: grid; grid-template-columns: repeat(auto-fit, minmax(140px, 1fr)); gap: 10px; margin-bottom: 12px; }
.c360-stat { padding: 12px 14px; border: 1px solid #ebeef5; border-radius: 7px; background: #fafbfc; }
.c360-stat small, .c360-stat strong, .c360-stat em { display: block; }
.c360-stat small { color: #8a97a5; font-size: 11px; }
.c360-stat strong { margin-top: 3px; color: #1f2d3d; font-size: 21px; font-weight: 700; }
.c360-stat em { margin-top: 2px; color: #a0aab5; font-size: 11px; font-style: normal; }
.c360-tabs :deep(.el-tabs__header) { margin-bottom: 10px; }
</style>
