<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import {
  Aim, ArrowRight, Briefcase, Calendar, Clock, OfficeBuilding, Refresh, Warning,
} from '@element-plus/icons-vue'
import { api, ApiError } from './api'
import type { RecordData } from './api'
import { leadStages } from './sections'

defineProps<{ username: string }>()

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
  <section class="welcome-card">
    <div class="welcome-copy">
      <p class="welcome-eyebrow">招商工作台</p>
      <h1>你好，{{ username }}，欢迎回来</h1>
      <p>查看企业资源、招商进展和待跟进线索，今天也一起把线索推进下去。</p>
    </div>
    <div class="welcome-actions">
      <el-button class="welcome-primary" @click="emit('navigate', 'enterprises')">
        浏览企业资源
      </el-button>
      <el-button class="welcome-ghost" :icon="Refresh" :loading="loading" @click="refresh">
        刷新数据
      </el-button>
    </div>
    <div class="welcome-orbit" aria-hidden="true"><span /><span /><span /></div>
  </section>

  <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon class="dashboard-error" />

  <div v-if="data" class="dashboard">
    <div class="stat-grid">
      <button class="stat-card" @click="emit('navigate', 'enterprises')">
        <span class="stat-icon stat-blue"><el-icon :size="23"><OfficeBuilding /></el-icon></span>
        <span class="stat-content">
          <small>企业档案</small>
          <strong>{{ data.enterpriseCount }}</strong>
          <em>查看企业资源</em>
        </span>
      </button>

      <button v-if="data.projectCount !== null" class="stat-card" @click="emit('navigate', 'projects')">
        <span class="stat-icon stat-cyan"><el-icon :size="23"><Briefcase /></el-icon></span>
        <span class="stat-content">
          <small>招商项目</small>
          <strong>{{ data.projectCount }}</strong>
          <em>查看全部项目</em>
        </span>
      </button>

      <button v-if="data.leadCount !== null" class="stat-card" @click="emit('navigate', 'leads')">
        <span class="stat-icon stat-indigo"><el-icon :size="23"><Aim /></el-icon></span>
        <span class="stat-content">
          <small>招商线索</small>
          <strong>{{ data.leadCount }}</strong>
          <em>查看线索</em>
        </span>
      </button>

      <button v-if="data.overdueCount !== null" class="stat-card stat-card-alert"
        @click="emit('navigate', 'leads', 'overdue')">
        <span class="stat-icon stat-red"><el-icon :size="23"><Warning /></el-icon></span>
        <span class="stat-content">
          <small>已逾期</small>
          <strong>{{ data.overdueCount }}</strong>
          <em>需要尽快跟进</em>
        </span>
      </button>

      <button v-if="data.todayCount !== null" class="stat-card" @click="emit('navigate', 'leads', 'today')">
        <span class="stat-icon stat-orange"><el-icon :size="23"><Clock /></el-icon></span>
        <span class="stat-content">
          <small>今日待跟进</small>
          <strong>{{ data.todayCount }}</strong>
          <em>今天剩余提醒</em>
        </span>
      </button>

      <button v-if="data.upcomingCount !== null" class="stat-card"
        @click="emit('navigate', 'leads', 'upcoming')">
        <span class="stat-icon stat-green"><el-icon :size="23"><Calendar /></el-icon></span>
        <span class="stat-content">
          <small>近七天待跟进</small>
          <strong>{{ data.upcomingCount }}</strong>
          <em>含今日提醒</em>
        </span>
      </button>
    </div>

    <section v-if="data.leadCount !== null" class="list-card reminders-card">
      <div class="card-heading">
        <div>
          <h2>待跟进线索</h2>
          <p>显示已逾期及近七天到期的前 20 条，已落地和已流失线索不计入。</p>
        </div>
        <el-button text type="primary" @click="emit('navigate', 'leads')">
          查看全部线索<el-icon class="heading-arrow"><ArrowRight /></el-icon>
        </el-button>
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

<style scoped>
.welcome-card {
  position: relative;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 24px;
  overflow: hidden;
  margin-bottom: 18px;
  padding: 28px 30px;
  border-radius: var(--card-radius);
  background:
    radial-gradient(circle at 88% 20%, rgb(121 187 255 / 28%), transparent 30%),
    linear-gradient(120deg, #1b3b5f 0%, #24547f 52%, #1c3d61 100%);
  color: #fff;
  box-shadow: 0 8px 24px rgb(27 59 95 / 18%);
}

.welcome-copy,
.welcome-actions {
  position: relative;
  z-index: 1;
}

.welcome-eyebrow {
  margin: 0 0 9px;
  color: #9ecbff;
  font-size: 11px;
  font-weight: 600;
  letter-spacing: .18em;
}

.welcome-copy h1 {
  margin: 0;
  font-size: clamp(21px, 2vw, 27px);
  font-weight: 700;
  letter-spacing: .01em;
}

.welcome-copy p:last-child {
  margin: 9px 0 0;
  color: rgb(255 255 255 / 62%);
  font-size: 13px;
}

.welcome-actions {
  display: flex;
  flex: 0 0 auto;
  gap: 9px;
}

.welcome-actions .el-button {
  margin: 0;
  border-radius: 6px;
}

.welcome-primary {
  color: #1b3b5f;
  border-color: #fff;
  background: #fff;
  font-weight: 600;
}

.welcome-primary:hover {
  color: #1b3b5f;
  border-color: #eaf4ff;
  background: #eaf4ff;
}

.welcome-ghost {
  color: #fff;
  border-color: rgb(255 255 255 / 28%);
  background: rgb(255 255 255 / 10%);
}

.welcome-ghost:hover {
  color: #fff;
  border-color: rgb(255 255 255 / 45%);
  background: rgb(255 255 255 / 18%);
}

.welcome-orbit {
  position: absolute;
  top: -90px;
  right: -40px;
  width: 320px;
  height: 320px;
  border: 1px solid rgb(255 255 255 / 12%);
  border-radius: 50%;
  pointer-events: none;
}

.welcome-orbit span {
  position: absolute;
  border: 1px solid rgb(255 255 255 / 9%);
  border-radius: 50%;
}

.welcome-orbit span:nth-child(1) { inset: 32px; }
.welcome-orbit span:nth-child(2) { inset: 74px; }

.welcome-orbit span:nth-child(3) {
  inset: 118px;
  background: rgb(121 187 255 / 10%);
}

.dashboard {
  display: grid;
  gap: 18px;
}

.dashboard-error {
  margin-bottom: 16px;
}

.stat-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 14px;
}

@media (min-width: 1280px) {
  .stat-grid {
    grid-template-columns: repeat(6, minmax(0, 1fr));
  }
}

.stat-card {
  display: flex;
  align-items: center;
  gap: 15px;
  min-height: 104px;
  padding: 19px 20px;
  text-align: left;
  border: 1px solid #ebeef5;
  border-radius: var(--card-radius);
  background: #fff;
  box-shadow: var(--card-shadow);
  cursor: pointer;
  transition: transform var(--transition-time), box-shadow var(--transition-time), border-color var(--transition-time);
}

.stat-card:hover {
  transform: translateY(-2px);
  border-color: var(--el-color-primary-light-7);
  box-shadow: 0 8px 22px rgb(0 21 41 / 8%);
}

.stat-card-alert strong {
  color: #f56c6c;
}

.stat-icon {
  display: grid;
  flex: 0 0 auto;
  place-items: center;
  width: 48px;
  height: 48px;
  border-radius: 12px;
}

.stat-blue { color: #409eff; background: #ecf5ff; }
.stat-cyan { color: #13c2c2; background: #e6fffb; }
.stat-indigo { color: #5b6cf9; background: #eef0ff; }
.stat-red { color: #f56c6c; background: #fef0f0; }
.stat-orange { color: #e6a23c; background: #fdf6ec; }
.stat-green { color: #67c23a; background: #f0f9eb; }

.stat-content {
  display: flex;
  flex-direction: column;
  min-width: 0;
}

.stat-content small {
  color: #8a97a5;
  font-size: 12px;
}

.stat-content strong {
  margin-top: 2px;
  color: #1f2d3d;
  font-size: 28px;
  font-weight: 700;
  line-height: 1.15;
}

.stat-content em {
  margin-top: 3px;
  overflow: hidden;
  color: #b0bac4;
  font-size: 11px;
  font-style: normal;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.reminders-card {
  overflow: hidden;
}

.card-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 14px;
  padding: 17px 20px;
  border-bottom: 1px solid #ebeef5;
}

.card-heading h2 {
  margin: 0;
  color: #1f2d3d;
  font-size: 16px;
  font-weight: 600;
}

.card-heading p {
  margin: 6px 0 0;
  color: #909399;
  font-size: 12px;
}

.heading-arrow {
  margin-left: 4px;
}

@media (max-width: 900px) {
  .welcome-card {
    align-items: flex-start;
    flex-direction: column;
  }

  .welcome-actions {
    flex-wrap: wrap;
  }
}

@media (max-width: 640px) {
  .welcome-card {
    padding: 22px 20px;
  }

  .stat-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .stat-card {
    min-height: 92px;
    padding: 15px;
    gap: 11px;
  }

  .stat-icon {
    width: 40px;
    height: 40px;
    border-radius: 10px;
  }

  .stat-content strong {
    font-size: 23px;
  }

  .stat-content em {
    display: none;
  }

  .card-heading {
    align-items: flex-start;
    flex-direction: column;
  }
}
</style>
