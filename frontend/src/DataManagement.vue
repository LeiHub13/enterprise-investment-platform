<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { api, ApiError, query } from './api'
import type { Page, RecordData } from './api'

const emit = defineEmits<{ expired: [error: unknown] }>()
const batches = ref<RecordData[]>([])
const batchPage = ref(1)
const batchTotal = ref(0)
const batchLoading = ref(false)
const batchError = ref('')
const selectedBatch = ref<RecordData | null>(null)
const rows = ref<RecordData[]>([])
const rowPage = ref(1)
const rowTotal = ref(0)
const rowLoading = ref(false)
const rowError = ref('')
const failuresOnly = ref(false)
let batchRequest = 0
let rowRequest = 0

function message(reason: unknown) {
  if (reason instanceof ApiError && reason.status === 401) emit('expired', reason)
  return reason instanceof Error ? reason.message : '加载失败，请重试'
}

function batchStatus(value: unknown) {
  const labels: Record<string, string> = {
    PROCESSING: '处理中', COMPLETED: '已完成', COMPLETED_WITH_ERRORS: '部分失败', FAILED: '失败',
  }
  return labels[String(value)] ?? value
}

function rowStatus(value: unknown) {
  const labels: Record<string, string> = { PARSED: '成功', FAILED: '失败', PENDING: '待处理' }
  return labels[String(value)] ?? value
}

function displayTime(value: unknown) {
  return typeof value === 'string' ? value.replace('T', ' ').slice(0, 19) : '—'
}

async function loadBatches(next = 1) {
  const request = ++batchRequest
  batchPage.value = next
  batchLoading.value = true
  batchError.value = ''
  try {
    const result = await api<Page<RecordData>>('/import-batches' + query({ page: next, size: 10 }))
    if (request !== batchRequest) return
    batches.value = result.items
    batchTotal.value = result.total
  } catch (reason) {
    if (request === batchRequest) batchError.value = message(reason)
  } finally {
    if (request === batchRequest) batchLoading.value = false
  }
}

async function loadRows(next = 1) {
  if (!selectedBatch.value) return
  const request = ++rowRequest
  rowPage.value = next
  rowLoading.value = true
  rowError.value = ''
  try {
    const result = await api<Page<RecordData>>(
      `/import-batches/${selectedBatch.value.id}/rows` +
      query({ page: next, size: 20, failuresOnly: String(failuresOnly.value) }),
    )
    if (request !== rowRequest) return
    rows.value = result.items
    rowTotal.value = result.total
  } catch (reason) {
    if (request === rowRequest) rowError.value = message(reason)
  } finally {
    if (request === rowRequest) rowLoading.value = false
  }
}

function selectBatch(batch: RecordData) {
  selectedBatch.value = batch
  failuresOnly.value = false
  rows.value = []
  void loadRows(1)
}

onMounted(() => void loadBatches())
</script>

<template>
  <div class="page-heading">
    <div><h1>数据管理</h1><p>查看企业导入批次和逐行处理结果。</p></div>
    <el-button :loading="batchLoading" @click="loadBatches(batchPage)">刷新</el-button>
  </div>
  <section class="list-card">
    <el-alert v-if="batchError" :title="batchError" type="error" :closable="false" show-icon class="list-error" />
    <el-table v-loading="batchLoading" :data="batches" empty-text="暂无导入批次">
      <el-table-column prop="fileName" label="文件名" min-width="190" show-overflow-tooltip />
      <el-table-column label="状态" min-width="95">
        <template #default="{ row }">{{ batchStatus(row.status) }}</template>
      </el-table-column>
      <el-table-column label="成功 / 总数" min-width="110">
        <template #default="{ row }">{{ row.successCount }} / {{ row.totalCount }}</template>
      </el-table-column>
      <el-table-column prop="failCount" label="失败" min-width="70" />
      <el-table-column prop="createdByUsername" label="导入人" min-width="100" />
      <el-table-column label="导入时间" min-width="165">
        <template #default="{ row }">{{ displayTime(row.createdAt) }}</template>
      </el-table-column>
      <el-table-column label="操作" min-width="75" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="selectBatch(row)">查看</el-button>
        </template>
      </el-table-column>
    </el-table>
    <div class="pagination">
      <el-pagination :current-page="batchPage" :total="batchTotal" :page-size="10"
        layout="total, prev, pager, next" background @current-change="loadBatches" />
    </div>
  </section>
  <section v-if="selectedBatch" class="list-card batch-detail">
    <div class="dashboard-reminders-heading">
      <div><h2>批次明细</h2><p>{{ selectedBatch.fileName }}</p></div>
      <el-checkbox v-model="failuresOnly" @change="loadRows(1)">只看失败</el-checkbox>
    </div>
    <el-alert v-if="rowError" :title="rowError" type="error" :closable="false" show-icon class="list-error" />
    <el-table v-loading="rowLoading" :data="rows" empty-text="暂无明细">
      <el-table-column prop="rowNumber" label="Excel 行号" min-width="95" />
      <el-table-column prop="enterpriseName" label="企业名称" min-width="180" show-overflow-tooltip />
      <el-table-column prop="uscc" label="统一社会信用代码" min-width="175" />
      <el-table-column label="结果" min-width="80">
        <template #default="{ row }">{{ rowStatus(row.parseStatus) }}</template>
      </el-table-column>
      <el-table-column prop="parseError" label="原因" min-width="200" show-overflow-tooltip />
    </el-table>
    <div class="pagination">
      <el-pagination :current-page="rowPage" :total="rowTotal" :page-size="20"
        layout="total, prev, pager, next" background @current-change="loadRows" />
    </div>
  </section>
</template>
