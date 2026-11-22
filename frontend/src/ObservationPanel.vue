<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { Refresh } from '@element-plus/icons-vue'
import { api, ApiError, query } from './api'
import type { Page, RecordData } from './api'

const emit = defineEmits<{ expired: [error: unknown] }>()

const rows = ref<RecordData[]>([])
const total = ref(0)
const page = ref(1)
const pageSize = 20
const loading = ref(false)
const errorText = ref('')
const filters = ref<{ keyword: string; status: string | null }>({ keyword: '', status: null })

const statusOptions = [
  { value: 'SUB_THRESHOLD', label: '未达标' },
  { value: 'REJECTED', label: '已驳回' },
  { value: 'VERIFY_FAILED', label: '核验失败' },
  { value: 'WATCHING', label: '观察中' },
]
const statusText: Record<string, string> = {
  WATCHING: '观察中', SUB_THRESHOLD: '未达标', REJECTED: '已驳回', VERIFY_FAILED: '核验失败',
}
const gradeText: Record<string, string> = { PRIORITY: '优先跟进', VERIFY: '待核实', HOLD: '暂缓跟进' }

function fail(error: unknown) {
  if (error instanceof ApiError && error.status === 401) emit('expired', error)
  errorText.value = error instanceof Error ? error.message : '无法加载观察记录'
}

function statusType(value: unknown) {
  return value === 'REJECTED' || value === 'VERIFY_FAILED' ? 'danger' : value === 'SUB_THRESHOLD' ? 'warning' : 'info'
}

function when(value: unknown) {
  return value ? String(value).replace('T', ' ').slice(0, 19) : '—'
}

async function load() {
  loading.value = true
  errorText.value = ''
  try {
    const result = await api<Page<RecordData>>('/observations' + query({
      keyword: filters.value.keyword,
      status: filters.value.status,
      page: page.value,
      size: pageSize,
    }))
    rows.value = result.items
    total.value = result.total
  } catch (error) {
    fail(error)
  } finally {
    loading.value = false
  }
}

function search() {
  page.value = 1
  void load()
}

function reset() {
  filters.value = { keyword: '', status: null }
  page.value = 1
  void load()
}

function changePage(next: number) {
  page.value = next
  void load()
}

onMounted(() => void load())
</script>

<template>
  <div class="page-header">
    <div class="page-header-copy">
      <div class="page-header-title">
        <span class="page-header-glyph">观</span>
        <h1>观察记录</h1>
      </div>
      <p>事件流水线中未达线索条件、核验失败或人工驳回的候选企业留痕，便于后续复析。</p>
    </div>
    <div class="page-actions">
      <el-tag type="info" effect="plain">只读</el-tag>
      <el-button :loading="loading" @click="load"><el-icon><Refresh /></el-icon>刷新</el-button>
    </div>
  </div>

  <el-alert v-if="errorText" :title="errorText" type="error" :closable="false" show-icon class="obs-alert" />

  <section class="list-card obs-list">
    <el-form class="filters" inline @submit.prevent="search">
      <el-form-item>
        <el-input v-model="filters.keyword" placeholder="企业或原因" clearable style="width: 240px" />
      </el-form-item>
      <el-form-item>
        <el-select v-model="filters.status" placeholder="全部状态" clearable :value-on-clear="null" style="width: 150px">
          <el-option v-for="option in statusOptions" :key="option.value" v-bind="option" />
        </el-select>
      </el-form-item>
      <el-form-item>
        <el-button type="primary" native-type="submit">查询</el-button>
        <el-button @click="reset">重置</el-button>
      </el-form-item>
    </el-form>
    <el-table v-loading="loading" :data="rows" empty-text="暂无观察记录">
      <el-table-column prop="enterpriseName" label="企业" min-width="180" show-overflow-tooltip />
      <el-table-column label="匹配项目" min-width="160" show-overflow-tooltip>
        <template #default="{ row }">{{ row.projectName || '—' }}</template>
      </el-table-column>
      <el-table-column label="状态" width="110">
        <template #default="{ row }">
          <el-tag size="small" :type="statusType(row.status)" effect="plain">{{ statusText[String(row.status)] ?? row.status }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="评分" width="120">
        <template #default="{ row }">
          {{ row.totalScore ?? '—' }}<span v-if="row.grade" class="obs-grade"> · {{ gradeText[String(row.grade)] ?? row.grade }}</span>
        </template>
      </el-table-column>
      <el-table-column prop="reason" label="原因" min-width="280" show-overflow-tooltip />
      <el-table-column label="时间" width="160">
        <template #default="{ row }">{{ when(row.createdAt) }}</template>
      </el-table-column>
    </el-table>
    <div class="obs-pager">
      <el-pagination :current-page="page" :page-size="pageSize" :total="total"
        layout="total, prev, pager, next" background @current-change="changePage" />
    </div>
  </section>
</template>

<style scoped>
.obs-alert { margin-bottom: 14px; }
.obs-list { padding: 16px 18px 18px; }
.obs-pager { display: flex; justify-content: flex-end; margin-top: 14px; }
.obs-grade { color: #909399; font-size: 12px; }
</style>