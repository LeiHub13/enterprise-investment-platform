<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Refresh, Search } from '@element-plus/icons-vue'
import { api, ApiError, query } from './api'
import type { Page, RecordData } from './api'
import { dateText, money } from './format'

const props = defineProps<{
  canCreate: boolean
  canUpdate: boolean
  canDelete: boolean
  canAssign: boolean
}>()
const emit = defineEmits<{ expired: [error: unknown] }>()

type Option = { value: number; label: string }
type BusinessOption = Option & { customerId: number; projectId: number | null }
type ContractForm = {
  id: number | null
  name: string
  customerId: number | null
  businessId: number | null
  amount: number | null
  signDate: string | null
  startDate: string | null
  endDate: string | null
  status: string
  ownerId: number | null
  remark: string
}

const statuses = [
  { value: 'DRAFT', label: '草稿', tone: 'info' },
  { value: 'SIGNED', label: '已签约', tone: 'success' },
  { value: 'TERMINATED', label: '已终止', tone: 'danger' },
]
const rows = ref<RecordData[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const loading = ref(false)
const error = ref('')
const keyword = ref('')
const statusFilter = ref<string | null>(null)
const customers = ref<Option[]>([])
const businesses = ref<BusinessOption[]>([])
const owners = ref<Option[]>([])
const dialogOpen = ref(false)
const saving = ref(false)
const formError = ref('')
const editingId = ref<number | null>(null)
const form = ref<ContractForm>(emptyForm())

function emptyForm(): ContractForm {
  return {
    id: null, name: '', customerId: null, businessId: null, amount: null,
    signDate: null, startDate: null, endDate: null, status: 'DRAFT', ownerId: null, remark: '',
  }
}

function fail(reason: unknown) {
  if (reason instanceof ApiError && reason.status === 401) emit('expired', reason)
  error.value = reason instanceof Error ? reason.message : '操作失败，请重试'
}

function statusLabel(value: unknown) {
  return statuses.find(status => status.value === value)?.label ?? String(value ?? '—')
}

function statusTone(value: unknown) {
  return (statuses.find(status => status.value === value)?.tone ?? 'info') as 'info' | 'success' | 'danger'
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    const result = await api<Page<RecordData>>('/contracts' + query({
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
    const [customerPage, businessPage] = await Promise.all([
      api<Page<RecordData>>('/enterprises' + query({ page: 1, size: 100 })),
      api<Page<RecordData>>('/businesses' + query({ page: 1, size: 100 })),
    ])
    customers.value = customerPage.items.map(row => ({ value: Number(row.id), label: String(row.name ?? row.id) }))
    businesses.value = businessPage.items.map(row => ({
      value: Number(row.id),
      label: String(row.name ?? row.id),
      customerId: Number(row.customerId),
      projectId: row.projectId == null ? null : Number(row.projectId),
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

function ensureBusinessOption(value: unknown, label: unknown, customerId: unknown, projectId: unknown) {
  const id = Number(value)
  if (!Number.isFinite(id) || id <= 0 || businesses.value.some(item => item.value === id)) return
  businesses.value.unshift({
    value: id, label: String(label ?? id), customerId: Number(customerId ?? 0),
    projectId: projectId == null ? null : Number(projectId),
  })
}

async function openCreate() {
  editingId.value = null
  form.value = emptyForm()
  formError.value = ''
  if (!customers.value.length || !businesses.value.length) await loadOptions()
  dialogOpen.value = true
}

async function openEdit(row: RecordData) {
  try {
    const detail = await api<RecordData>('/contracts/' + row.id)
    ensureOption(customers.value, detail.customerId, detail.customerName)
    ensureBusinessOption(detail.businessId, detail.businessName, detail.customerId, detail.projectId)
    ensureOption(owners.value, detail.ownerUserId, detail.ownerUsername)
    editingId.value = Number(detail.id)
    form.value = {
      id: Number(detail.id),
      name: String(detail.name ?? ''),
      customerId: detail.customerId == null ? null : Number(detail.customerId),
      businessId: detail.businessId == null ? null : Number(detail.businessId),
      amount: detail.amount == null ? null : Number(detail.amount),
      signDate: detail.signDate == null ? null : String(detail.signDate).slice(0, 10),
      startDate: detail.startDate == null ? null : String(detail.startDate).slice(0, 10),
      endDate: detail.endDate == null ? null : String(detail.endDate).slice(0, 10),
      status: String(detail.status ?? 'DRAFT'),
      ownerId: detail.ownerUserId == null ? null : Number(detail.ownerUserId),
      remark: String(detail.remark ?? ''),
    }
    formError.value = ''
    dialogOpen.value = true
  } catch (reason) {
    fail(reason)
  }
}

watch(() => form.value.businessId, value => {
  if (!value) return
  const business = businesses.value.find(item => item.value === value)
  if (business && business.customerId) form.value.customerId = business.customerId
})

async function save() {
  if (saving.value) return
  if (!form.value.name.trim()) {
    formError.value = '请填写合同名称'
    return
  }
  if (!form.value.customerId) {
    formError.value = '请选择客户'
    return
  }
  if (form.value.amount == null || form.value.amount < 0) {
    formError.value = '请填写合同金额'
    return
  }
  if (form.value.startDate && form.value.endDate && form.value.endDate < form.value.startDate) {
    formError.value = '合同结束日期不能早于开始日期'
    return
  }
  saving.value = true
  formError.value = ''
  const payload = {
    name: form.value.name.trim(),
    customerId: form.value.customerId,
    businessId: form.value.businessId,
    amount: form.value.amount,
    signDate: form.value.signDate,
    startDate: form.value.startDate,
    endDate: form.value.endDate,
    status: form.value.status,
    ownerId: form.value.ownerId,
    remark: form.value.remark.trim() || null,
  }
  try {
    if (editingId.value == null) await api('/contracts', 'POST', payload)
    else await api('/contracts/' + editingId.value, 'PUT', payload)
    dialogOpen.value = false
    ElMessage.success(editingId.value == null ? '合同已创建' : '合同已保存')
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
    await ElMessageBox.confirm('确定删除该合同吗？存在回款记录时不能删除。', '删除合同', {
      type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消',
    })
  } catch {
    return
  }
  try {
    await api('/contracts/' + editingId.value, 'DELETE')
    dialogOpen.value = false
    ElMessage.success('合同已删除')
    await load()
  } catch (reason) {
    fail(reason)
  }
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
        <span class="page-header-glyph">合</span>
        <h1>招商合同</h1>
      </div>
      <p>管理签约合同、招商项目与合同金额，签约商机会自动标记为赢单。</p>
    </div>
    <div class="page-actions">
      <el-button :loading="loading" @click="load">
        <el-icon><Refresh /></el-icon>刷新
      </el-button>
      <el-button v-if="canCreate" type="primary" @click="openCreate">
        <el-icon><Plus /></el-icon>新建合同
      </el-button>
    </div>
  </div>

  <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon class="crm-error" />

  <section class="list-card">
    <el-form class="filters" inline @submit.prevent="search">
      <el-form-item>
        <el-input v-model="keyword" placeholder="合同名称、编号或客户" clearable style="width: 230px" />
      </el-form-item>
      <el-form-item>
        <el-select v-model="statusFilter" placeholder="合同状态" clearable :value-on-clear="null" style="width: 140px">
          <el-option v-for="status in statuses" :key="status.value" :value="status.value" :label="status.label" />
        </el-select>
      </el-form-item>
      <el-form-item>
        <el-button type="primary" native-type="submit"><el-icon><Search /></el-icon>查询</el-button>
        <el-button @click="keyword = ''; statusFilter = null; search()">重置</el-button>
      </el-form-item>
    </el-form>

    <el-table v-loading="loading" :data="rows" row-key="id" empty-text="暂无合同">
      <el-table-column prop="contractNo" label="合同编号" min-width="175" show-overflow-tooltip />
      <el-table-column prop="name" label="合同名称" min-width="210" show-overflow-tooltip />
      <el-table-column prop="customerName" label="客户" min-width="180" show-overflow-tooltip />
      <el-table-column label="金额（元）" min-width="130">
        <template #default="{ row }">{{ money(row.amount) }}</template>
      </el-table-column>
      <el-table-column label="签约日期" min-width="110">
        <template #default="{ row }">{{ dateText(row.signDate) }}</template>
      </el-table-column>
      <el-table-column label="状态" min-width="90">
        <template #default="{ row }">
          <el-tag :type="statusTone(row.status)" effect="light">{{ statusLabel(row.status) }}</el-tag>
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
    :title="editingId == null ? '新建合同' : (canUpdate ? '编辑合同' : '查看合同')"
    width="min(720px, 94vw)" :close-on-click-modal="false" :close-on-press-escape="!saving" :show-close="!saving">
    <el-form label-position="top" :disabled="!canUpdate" @submit.prevent="save">
      <div class="form-grid">
        <el-form-item label="合同名称" required class="wide">
          <el-input v-model="form.name" maxlength="255" placeholder="例如：星澜智能招商落地协议" />
        </el-form-item>
        <el-form-item label="客户" required>
          <el-select v-model="form.customerId" filterable placeholder="选择客户" style="width: 100%">
            <el-option v-for="option in customers" :key="option.value" v-bind="option" />
          </el-select>
        </el-form-item>
        <el-form-item label="关联商机">
          <el-select v-model="form.businessId" filterable clearable placeholder="选择商机" style="width: 100%">
            <el-option v-for="option in businesses" :key="option.value" :value="option.value" :label="option.label" />
          </el-select>
        </el-form-item>
        <el-form-item label="合同金额（元）" required>
          <el-input-number v-model="form.amount" :min="0" :precision="2" :controls="false" style="width: 100%" />
        </el-form-item>
        <el-form-item label="合同状态" required>
          <el-select v-model="form.status" style="width: 100%">
            <el-option v-for="status in statuses" :key="status.value" :value="status.value" :label="status.label" />
          </el-select>
        </el-form-item>
        <el-form-item label="签约日期">
          <el-date-picker v-model="form.signDate" type="date" value-format="YYYY-MM-DD" placeholder="选择日期" style="width: 100%" />
        </el-form-item>
        <el-form-item label="开始日期">
          <el-date-picker v-model="form.startDate" type="date" value-format="YYYY-MM-DD" placeholder="选择日期" style="width: 100%" />
        </el-form-item>
        <el-form-item label="结束日期">
          <el-date-picker v-model="form.endDate" type="date" value-format="YYYY-MM-DD" placeholder="选择日期" style="width: 100%" />
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
        <el-button v-if="editingId != null && canDelete" type="danger" plain :disabled="saving" @click="remove">删除合同</el-button>
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
