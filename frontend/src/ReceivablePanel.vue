<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Refresh, Search } from '@element-plus/icons-vue'
import { api, ApiError, query } from './api'
import type { Page, RecordData } from './api'
import { dateText, isOverdue, money } from './format'

const props = defineProps<{
  canCreate: boolean
  canUpdate: boolean
  canDelete: boolean
  canAssign: boolean
}>()
const emit = defineEmits<{ expired: [error: unknown] }>()

type Option = { value: number; label: string }
type ReceivableForm = {
  id: number | null
  contractId: number | null
  planAmount: number | null
  planDate: string | null
  receivedAmount: number | null
  receivedDate: string | null
  status: string
  ownerId: number | null
  remark: string
}

const statuses = [
  { value: 'PENDING', label: '待回款' },
  { value: 'RECEIVED', label: '已回款' },
]
const rows = ref<RecordData[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const loading = ref(false)
const error = ref('')
const keyword = ref('')
const statusFilter = ref<string | null>(null)
const contracts = ref<Option[]>([])
const owners = ref<Option[]>([])
const dialogOpen = ref(false)
const saving = ref(false)
const formError = ref('')
const editingId = ref<number | null>(null)
const form = ref<ReceivableForm>(emptyForm())

function emptyForm(): ReceivableForm {
  return {
    id: null, contractId: null, planAmount: null, planDate: null,
    receivedAmount: null, receivedDate: null, status: 'PENDING', ownerId: null, remark: '',
  }
}

function fail(reason: unknown) {
  if (reason instanceof ApiError && reason.status === 401) emit('expired', reason)
  error.value = reason instanceof Error ? reason.message : '操作失败，请重试'
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    const result = await api<Page<RecordData>>('/receivables' + query({
      page: page.value, size: size.value, keyword: keyword.value.trim() || null, status: statusFilter.value,
    }))
    rows.value = result.items
    total.value = result.total
  } catch (reason) {
    fail(reason)
    rows.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

async function loadOptions() {
  try {
    const contractPage = await api<Page<RecordData>>('/contracts' + query({ page: 1, size: 100 }))
    contracts.value = contractPage.items.map(row => ({
      value: Number(row.id),
      label: String(row.contractNo) + ' · ' + String(row.name),
    }))
    if (props.canAssign) {
      const users = await api<RecordData[]>('/users')
      owners.value = users.filter(user => user.enabled !== false && user.role !== 'DATA_STEWARD')
        .map(user => ({ value: Number(user.id), label: String(user.username) }))
    }
  } catch (reason) {
    fail(reason)
  }
}

function ensureOption(list: Option[], value: unknown, label: unknown) {
  const id = Number(value)
  if (!Number.isFinite(id) || id <= 0 || list.some(item => item.value === id)) return
  list.unshift({ value: id, label: String(label ?? id) })
}

async function openCreate() {
  editingId.value = null
  form.value = emptyForm()
  formError.value = ''
  if (!contracts.value.length) await loadOptions()
  dialogOpen.value = true
}

async function openEdit(row: RecordData) {
  try {
    const detail = await api<RecordData>('/receivables/' + row.id)
    ensureOption(contracts.value, detail.contractId, String(detail.contractNo ?? '') + ' · ' + String(detail.contractName ?? ''))
    ensureOption(owners.value, detail.ownerUserId, detail.ownerUsername)
    editingId.value = Number(detail.id)
    form.value = {
      id: Number(detail.id),
      contractId: Number(detail.contractId),
      planAmount: detail.planAmount == null ? null : Number(detail.planAmount),
      planDate: detail.planDate == null ? null : String(detail.planDate).slice(0, 10),
      receivedAmount: detail.receivedAmount == null ? null : Number(detail.receivedAmount),
      receivedDate: detail.receivedDate == null ? null : String(detail.receivedDate).slice(0, 10),
      status: String(detail.status ?? 'PENDING'),
      ownerId: detail.ownerUserId == null ? null : Number(detail.ownerUserId),
      remark: String(detail.remark ?? ''),
    }
    formError.value = ''
    dialogOpen.value = true
  } catch (reason) {
    fail(reason)
  }
}

async function save() {
  if (saving.value) return
  if (!form.value.contractId) {
    formError.value = '请选择合同'
    return
  }
  if (form.value.planAmount == null || form.value.planAmount < 0) {
    formError.value = '请填写计划回款金额'
    return
  }
  const receivedAmount = form.value.status === 'RECEIVED' && !form.value.receivedAmount
    ? form.value.planAmount
    : (form.value.receivedAmount ?? 0)
  if (receivedAmount > form.value.planAmount) {
    formError.value = '实收金额不能大于计划金额'
    return
  }
  saving.value = true
  formError.value = ''
  const payload = {
    contractId: form.value.contractId,
    planAmount: form.value.planAmount,
    planDate: form.value.planDate,
    receivedAmount,
    receivedDate: form.value.receivedDate,
    status: form.value.status,
    ownerId: form.value.ownerId,
    remark: form.value.remark.trim() || null,
  }
  try {
    if (editingId.value == null) await api('/receivables', 'POST', payload)
    else await api('/receivables/' + editingId.value, 'PUT', payload)
    dialogOpen.value = false
    ElMessage.success(editingId.value == null ? '回款计划已创建' : '回款计划已保存')
    await load()
  } catch (reason) {
    if (reason instanceof ApiError && reason.status === 401) emit('expired', reason)
    formError.value = reason instanceof Error ? reason.message : '保存失败'
  } finally {
    saving.value = false
  }
}

async function remove() {
  if (!editingId.value) return
  try {
    await ElMessageBox.confirm('确定删除该回款记录吗？', '删除回款', {
      type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消',
    })
  } catch {
    return
  }
  try {
    await api('/receivables/' + editingId.value, 'DELETE')
    dialogOpen.value = false
    ElMessage.success('回款记录已删除')
    await load()
  } catch (reason) {
    fail(reason)
  }
}

function rowStatusLabel(row: RecordData) {
  if (isOverdue(row.status, row.planDate)) return '已逾期'
  return statuses.find(status => status.value === row.status)?.label ?? String(row.status ?? '—')
}

function rowStatusTone(row: RecordData) {
  if (isOverdue(row.status, row.planDate)) return 'danger'
  return row.status === 'RECEIVED' ? 'success' : 'warning'
}

function search() {
  page.value = 1
  void load()
}

function changePage(next: number) {
  page.value = next
  void load()
}

onMounted(async () => {
  await Promise.all([load(), loadOptions()])
})
</script>

<template>
  <div class="page-header">
    <div class="page-header-copy">
      <div class="page-header-title">
        <span class="page-header-glyph">回</span>
        <h1>回款管理</h1>
      </div>
      <p>按合同登记回款计划与实收金额，逾期计划会在列表中标记。</p>
    </div>
    <div class="page-actions">
      <el-button :loading="loading" @click="load">
        <el-icon><Refresh /></el-icon>刷新
      </el-button>
      <el-button v-if="canCreate" type="primary" @click="openCreate">
        <el-icon><Plus /></el-icon>新建回款
      </el-button>
    </div>
  </div>

  <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon class="crm-error" />

  <section class="list-card">
    <el-form class="filters" inline @submit.prevent="search">
      <el-form-item>
        <el-input v-model="keyword" placeholder="合同编号、名称或客户" clearable style="width: 230px" />
      </el-form-item>
      <el-form-item>
        <el-select v-model="statusFilter" placeholder="回款状态" clearable :value-on-clear="null" style="width: 140px">
          <el-option v-for="status in statuses" :key="status.value" :value="status.value" :label="status.label" />
        </el-select>
      </el-form-item>
      <el-form-item>
        <el-button type="primary" native-type="submit"><el-icon><Search /></el-icon>查询</el-button>
        <el-button @click="keyword = ''; statusFilter = null; search()">重置</el-button>
      </el-form-item>
    </el-form>

    <el-table v-loading="loading" :data="rows" row-key="id" empty-text="暂无回款记录">
      <el-table-column prop="receivableNo" label="回款编号" min-width="175" show-overflow-tooltip />
      <el-table-column label="合同" min-width="210" show-overflow-tooltip>
        <template #default="{ row }">{{ row.contractNo }} · {{ row.contractName }}</template>
      </el-table-column>
      <el-table-column prop="customerName" label="客户" min-width="180" show-overflow-tooltip />
      <el-table-column label="计划金额（元）" min-width="135">
        <template #default="{ row }">{{ money(row.planAmount) }}</template>
      </el-table-column>
      <el-table-column label="实收金额（元）" min-width="135">
        <template #default="{ row }">{{ money(row.receivedAmount) }}</template>
      </el-table-column>
      <el-table-column label="计划日期" min-width="110">
        <template #default="{ row }">{{ dateText(row.planDate) }}</template>
      </el-table-column>
      <el-table-column label="状态" min-width="90">
        <template #default="{ row }">
          <el-tag :type="rowStatusTone(row)" effect="light">{{ rowStatusLabel(row) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="ownerUsername" label="负责人" min-width="100">
        <template #default="{ row }">{{ row.ownerUsername || '—' }}</template>
      </el-table-column>
      <el-table-column label="操作" min-width="80" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openEdit(row)">{{ canUpdate ? '编辑' : '查看' }}</el-button>
        </template>
      </el-table-column>
    </el-table>

    <div class="pagination">
      <el-pagination :current-page="page" :page-size="size" :total="total" layout="total, prev, pager, next"
        background @current-change="changePage" />
    </div>
  </section>

  <el-dialog v-model="dialogOpen"
    :title="editingId == null ? '新建回款计划' : (canUpdate ? '编辑回款计划' : '查看回款计划')"
    width="min(680px, 94vw)" :close-on-click-modal="false" :close-on-press-escape="!saving" :show-close="!saving">
    <el-form label-position="top" :disabled="!canUpdate" @submit.prevent="save">
      <div class="form-grid">
        <el-form-item label="合同" required class="wide">
          <el-select v-model="form.contractId" filterable placeholder="选择合同" style="width: 100%">
            <el-option v-for="option in contracts" :key="option.value" v-bind="option" />
          </el-select>
        </el-form-item>
        <el-form-item label="计划金额（元）" required>
          <el-input-number v-model="form.planAmount" :min="0" :precision="2" :controls="false" style="width: 100%" />
        </el-form-item>
        <el-form-item label="计划日期">
          <el-date-picker v-model="form.planDate" type="date" value-format="YYYY-MM-DD" placeholder="选择日期" style="width: 100%" />
        </el-form-item>
        <el-form-item label="实收金额（元）">
          <el-input-number v-model="form.receivedAmount" :min="0" :precision="2" :controls="false" style="width: 100%" />
        </el-form-item>
        <el-form-item label="实收日期">
          <el-date-picker v-model="form.receivedDate" type="date" value-format="YYYY-MM-DD" placeholder="选择日期" style="width: 100%" />
        </el-form-item>
        <el-form-item label="状态" required>
          <el-select v-model="form.status" style="width: 100%">
            <el-option v-for="status in statuses" :key="status.value" :value="status.value" :label="status.label" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="canAssign" label="负责人">
          <el-select v-model="form.ownerId" filterable clearable placeholder="未分配" style="width: 100%">
            <el-option v-for="option in owners" :key="option.value" v-bind="option" />
          </el-select>
        </el-form-item>
        <el-form-item label="备注" class="wide">
          <el-input v-model="form.remark" type="textarea" :rows="3" maxlength="5000" />
        </el-form-item>
      </div>
      <el-alert v-if="formError" :title="formError" type="error" :closable="false" show-icon />
      <div class="dialog-actions dialog-actions-split">
        <el-button v-if="editingId != null && canDelete" type="danger" plain :disabled="saving" @click="remove">删除回款</el-button>
        <span class="dialog-actions-right">
          <el-button :disabled="saving" @click="dialogOpen = false">{{ canUpdate ? '取消' : '关闭' }}</el-button>
          <el-button v-if="canUpdate" type="primary" native-type="submit" :loading="saving">保存</el-button>
        </span>
      </div>
    </el-form>
  </el-dialog>
</template>

<style scoped>
.crm-error { margin-bottom: 14px; }
.dialog-actions-split { justify-content: space-between; }
.dialog-actions-right { display: flex; gap: 8px; }
</style>
