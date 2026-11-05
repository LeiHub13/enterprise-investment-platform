<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { api, ApiError } from './api'
import type { RecordData } from './api'
import { leadStages } from './sections'

type Dashboard = {
  enterpriseCount: number
  projectCount: number | null
  leadCount: number | null
  overdueCount: number | null
  todayCount: number | null
  upcomingCount: number | null
  reminders: RecordData[]
}

const emit = defineEmits<{
  navigate: [section: 'enterprises' | 'projects' | 'leads', due?: string]
  openLead: [id: number]
  expired: [error: unknown]
}>()
const data = ref<Dashboard | null>(null)
const loading = ref(false)
const error = ref('')
let timer: ReturnType<typeof setInterval> | undefined
let currentRequest = 0

async function refresh() {
  const request = ++currentRequest
  loading.value = true
  try {
    const result = await api<Dashboard>('/dashboard')
    if (request !== currentRequest) return
    data.value = result
    error.value = ''
  } catch (reason) {
    if (request !== currentRequest) return
    if (reason instanceof ApiError && reason.status === 401) emit('expired', reason)
    error.value = reason instanceof Error ? reason.message : '工作台加载失败'
  } finally {
    if (request === currentRequest) loading.value = false
  }
}

function stage(value: unknown) {
  return leadStages.find(item => item.value === value)?.label ?? value
}

function displayTime(value: unknown) {
  return typeof value === 'string' ? value.replace('T', ' ').slice(0, 16) : '—'
}

onMounted(() => {
  void refresh()
  timer = setInterval(() => void refresh(), 60_000)
})

onBeforeUnmount(() => {
  currentRequest++
  if (timer) clearInterval(timer)
})
</script>

<template>
  <div class="page-heading">
    <div>
      <h1>招商工作台</h1>
      <p>查看企业资源、招商进展和待跟进线索。</p>
    </div>
    <el-button :loading="loading" @click="refresh">刷新</el-button>
  </div>
  <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon class="dashboard-error" />
  <div v-if="data" class="dashboard">
    <div class="dashboard-cards">
      <button class="dashboard-card" @click="emit('navigate', 'enterprises')">
        <span>企业档案</span><strong>{{ data.enterpriseCount }}</strong><small>查看企业资源</small>
      </button>
      <button v-if="data.projectCount !== null" class="dashboard-card" @click="emit('navigate', 'projects')">
        <span>招商项目</span><strong>{{ data.projectCount }}</strong><small>查看全部项目</small>
      </button>
      <button v-if="data.leadCount !== null" class="dashboard-card" @click="emit('navigate', 'leads')">
        <span>招商线索</span><strong>{{ data.leadCount }}</strong><small>查看可访问的线索</small>
      </button>
      <button v-if="data.overdueCount !== null" class="dashboard-card dashboard-card-alert"
        @click="emit('navigate', 'leads', 'overdue')">
        <span>已逾期</span><strong>{{ data.overdueCount }}</strong><small>需要跟进</small>
      </button>
      <button v-if="data.todayCount !== null" class="dashboard-card"
        @click="emit('navigate', 'leads', 'today')">
        <span>今日待跟进</span><strong>{{ data.todayCount }}</strong><small>今天剩余的提醒</small>
      </button>
      <button v-if="data.upcomingCount !== null" class="dashboard-card"
        @click="emit('navigate', 'leads', 'upcoming')">
        <span>近七天待跟进</span><strong>{{ data.upcomingCount }}</strong><small>包含今天剩余的提醒</small>
      </button>
    </div>
    <section v-if="data.leadCount !== null" class="list-card dashboard-reminders">
      <div class="dashboard-reminders-heading">
        <div>
          <h2>待跟进线索</h2>
          <p>显示已逾期及近七天到期的前 20 条，已落地和已流失线索不计入。</p>
        </div>
        <el-button text type="primary" @click="emit('navigate', 'leads')">查看全部线索</el-button>
      </div>
      <el-table :data="data.reminders" empty-text="暂无待跟进线索">
        <el-table-column label="企业" min-width="170">
          <template #default="{ row }">
            <el-button link type="primary" @click="emit('openLead', row.id)">{{ row.enterpriseName }}</el-button>
          </template>
        </el-table-column>
        <el-table-column prop="projectName" label="招商项目" min-width="150" />
        <el-table-column label="阶段" min-width="100">
          <template #default="{ row }">{{ stage(row.leadStatus) }}</template>
        </el-table-column>
        <el-table-column label="下次跟进" min-width="165">
          <template #default="{ row }">{{ displayTime(row.nextFollowupAt) }}</template>
        </el-table-column>
      </el-table>
    </section>
  </div>
</template>
