<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Refresh } from '@element-plus/icons-vue'
import { api, ApiError, query } from './api'
import type { Page, RecordData } from './api'
import { businessStages } from './sections'
import { money } from './format'

const props = defineProps<{
  canCreate: boolean
  canUpdate: boolean
  canDelete: boolean
  canAssign: boolean
}>()
const emit = defineEmits<{ expired: [error: unknown] }>()

type Option = { value: number; label: string }
type LeadOption = Option & {
  enterpriseId: number
  projectId: number | null
  enterpriseName: string
  projectName: string | null
}
type BusinessForm = {
  id: number | null
  name: string
  customerId: number | null
  projectId: number | null
  leadId: number | null
  ownerId: number | null
  stage: string
  amount: number | null
  probability: number | null
  expectedCloseDate: string | null
  source: string
  remark: string
}

const stages = businessStages
const rows = ref<RecordData[]>([])
const total = ref(0)
const loading = ref(false)
const error = ref('')
const keyword = ref('')
const customers = ref<Option[]>([])
const projects = ref<Option[]>([])
const leads = ref<LeadOption[]>([])
const owners = ref<Option[]>([])
const dialogOpen = ref(false)
const saving = ref(false)
const formError = ref('')
const editingId = ref<number | null>(null)
const form = ref<BusinessForm>(emptyForm())

function emptyForm(): BusinessForm {
  return {
    id: null, name: '', customerId: null, projectId: null, leadId: null, ownerId: null,
    stage: 'NEW', amount: null, probability: null, expectedCloseDate: null, source: '', remark: '',
  }
}

function fail(reason: unknown) {
  if (reason instanceof ApiError && reason.status === 401) emit('expired', reason)
  error.value = reason instanceof Error ? reason.message : '操作失败，请重试'
}

function stageClass(value: unknown) {
  if (value === 'WON') return 'stage-won'
  if (value === 'LOST') return 'stage-lost'
  if (value === 'NEGOTIATING') return 'stage-negotiating'
  if (value === 'CONTACTED') return 'stage-contacted'
  return 'stage-new'
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    const result = await api<Page<RecordData>>('/businesses' + query({ page: 1, size: 100 }))
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
    const [customerPage, projectPage, leadPage] = await Promise.all([
      api<Page<RecordData>>('/enterprises' + query({ page: 1, size: 100 })),
      api<Page<RecordData>>('/projects' + query({ page: 1, size: 100 })),
      api<Page<RecordData>>('/leads' + query({ page: 1, size: 100 })),
    ])
    customers.value = customerPage.items.map(row => ({ value: Number(row.id), label: String(row.name ?? row.id) }))
    projects.value = projectPage.items.map(row => ({ value: Number(row.id), label: String(row.projectName ?? row.id) }))
    leads.value = leadPage.items.map(row => ({
      value: Number(row.id),
      label: String(row.enterpriseName ?? row.id) + (row.projectName ? ' · ' + row.projectName : ''),
      enterpriseId: Number(row.enterpriseId),
      projectId: row.projectId == null ? null : Number(row.projectId),
      enterpriseName: String(row.enterpriseName ?? ''),
      projectName: row.projectName == null ? null : String(row.projectName),
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

const filtered = computed(() => {
  const word = keyword.value.trim()
  if (!word) return rows.value
  return rows.value.filter(row => [row.name, row.customerName, row.projectName, row.ownerUsername]
    .some(value => String(value ?? '').includes(word)))
})

const grouped = computed(() => {
  const map: Record<string, RecordData[]> = {}
  for (const stage of stages) map[stage.value] = []
  for (const row of filtered.value) {
    const stage = String(row.stage ?? 'NEW')
    ;(map[stage] ??= []).push(row)
  }
  return map
})

function ensureOption(list: Option[], value: unknown, label: unknown) {
  const id = Number(value)
  if (!Number.isFinite(id) || id <= 0) return
  if (!list.some(item => item.value === id)) {
    list.unshift({ value: id, label: String(label ?? id) })
  }
}

async function openCreate() {
  editingId.value = null
  form.value = emptyForm()
  formError.value = ''
  if (!customers.value.length || !projects.value.length || !leads.value.length) await loadOptions()
  dialogOpen.value = true
}

async function openEdit(row: RecordData) {
  try {
    const detail = await api<RecordData>('/businesses/' + row.id)
    ensureOption(customers.value, detail.customerId, detail.customerName)
    ensureOption(projects.value, detail.projectId, detail.projectName)
    ensureOption(leads.value, detail.leadId, detail.enterpriseName)
    ensureOption(owners.value, detail.ownerUserId, detail.ownerUsername)
    editingId.value = Number(detail.id)
    form.value = {
      id: Number(detail.id),
      name: String(detail.name ?? ''),
      customerId: detail.customerId == null ? null : Number(detail.customerId),
      projectId: detail.projectId == null ? null : Number(detail.projectId),
      leadId: detail.leadId == null ? null : Number(detail.leadId),
      ownerId: detail.ownerUserId == null ? null : Number(detail.ownerUserId),
      stage: String(detail.stage ?? 'NEW'),
      amount: detail.amount == null ? null : Number(detail.amount),
      probability: detail.probability == null ? null : Number(detail.probability),
      expectedCloseDate: detail.expectedCloseDate == null ? null : String(detail.expectedCloseDate).slice(0, 10),
      source: String(detail.source ?? ''),
      remark: String(detail.remark ?? ''),
    }
    formError.value = ''
    dialogOpen.value = true
  } catch (reason) {
    fail(reason)
  }
}

watch(() => form.value.leadId, value => {
  if (editingId.value != null || !value) return
  const lead = leads.value.find(item => item.value === value)
  if (!lead) return
  form.value.customerId = lead.enterpriseId
  form.value.projectId = lead.projectId
  if (!form.value.name) {
    form.value.name = (lead.enterpriseName + ' · ' + (lead.projectName ?? '招商商机')).slice(0, 255)
  }
})

async function save() {
  if (saving.value) return
  if (!form.value.name.trim()) {
    formError.value = '请填写商机名称'
    return
  }
  if (!form.value.customerId) {
    formError.value = '请选择客户'
    return
  }
  saving.value = true
  formError.value = ''
  const payload = {
    name: form.value.name.trim(),
    customerId: form.value.customerId,
    projectId: form.value.projectId,
    leadId: form.value.leadId,
    ownerId: form.value.ownerId,
    stage: form.value.stage,
    amount: form.value.amount,
    probability: form.value.probability,
    expectedCloseDate: form.value.expectedCloseDate,
    source: form.value.source.trim() || null,
    remark: form.value.remark.trim() || null,
  }
  try {
    if (editingId.value == null) await api('/businesses', 'POST', payload)
    else await api('/businesses/' + editingId.value, 'PUT', payload)
    dialogOpen.value = false
    ElMessage.success(editingId.value == null ? '商机已创建' : '商机已保存')
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
    await ElMessageBox.confirm('确定删除该商机吗？', '删除商机', {
      type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消',
    })
  } catch {
    return
  }
  try {
    await api('/businesses/' + editingId.value, 'DELETE')
    dialogOpen.value = false
    ElMessage.success('商机已删除')
    await load()
  } catch (reason) {
    fail(reason)
  }
}

onMounted(async () => {
  await Promise.all([load(), loadOptions()])
})
</script>

<template>
  <div class="page-header">
    <div class="page-header-copy">
      <div class="page-header-title">
        <span class="page-header-glyph">商</span>
        <h1>商机管道</h1>
      </div>
      <p>按阶段推进客户与招商项目的商机，签约后自动进入合同与回款。</p>
    </div>
    <div class="page-actions">
      <el-button :loading="loading" @click="load">
        <el-icon><Refresh /></el-icon>刷新
      </el-button>
      <el-button v-if="canCreate" type="primary" @click="openCreate">
        <el-icon><Plus /></el-icon>新建商机
      </el-button>
    </div>
  </div>

  <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon class="crm-error" />

  <div class="pipeline-toolbar">
    <el-input v-model="keyword" placeholder="搜索商机、客户或项目" clearable style="width: 260px" />
    <span class="muted">共 {{ total }} 条{{ total > filtered.length ? '，当前展示前 100 条' : '' }}</span>
  </div>

  <div v-loading="loading" class="pipeline">
    <section v-for="stage in stages" :key="stage.value" class="pipeline-column">
      <header>
        <span class="stage-dot" :class="stageClass(stage.value)" />
        <strong>{{ stage.label }}</strong>
        <em>{{ grouped[stage.value]?.length ?? 0 }}</em>
      </header>
      <div class="pipeline-cards">
        <article v-for="item in grouped[stage.value]" :key="item.id" class="business-card"
          :class="{ clickable: canUpdate }" @click="canUpdate && openEdit(item)">
          <div class="business-card-top">
            <strong>{{ item.name }}</strong>
            <span class="business-amount">{{ money(item.amount) }}</span>
          </div>
          <p class="business-customer">{{ item.customerName }}</p>
          <p v-if="item.projectName" class="business-project">{{ item.projectName }}</p>
          <footer>
            <span>{{ item.ownerUsername || '未分配' }}</span>
            <span v-if="item.probability != null">{{ item.probability }}%</span>
          </footer>
        </article>
        <p v-if="!(grouped[stage.value]?.length)" class="pipeline-empty">暂无商机</p>
      </div>
    </section>
  </div>

  <el-dialog v-model="dialogOpen" :title="editingId == null ? '新建商机' : '编辑商机'"
    width="min(720px, 94vw)" :close-on-click-modal="false" :close-on-press-escape="!saving" :show-close="!saving">
    <el-form label-position="top" @submit.prevent="save">
      <div class="form-grid">
        <el-form-item label="商机名称" required class="wide">
          <el-input v-model="form.name" maxlength="255" placeholder="例如：星澜智能 · 南京智能制造产业园" />
        </el-form-item>
        <el-form-item label="客户" required>
          <el-select v-model="form.customerId" filterable placeholder="选择客户" style="width: 100%">
            <el-option v-for="option in customers" :key="option.value" v-bind="option" />
          </el-select>
        </el-form-item>
        <el-form-item label="招商项目">
          <el-select v-model="form.projectId" filterable clearable placeholder="选择招商项目" style="width: 100%">
            <el-option v-for="option in projects" :key="option.value" v-bind="option" />
          </el-select>
        </el-form-item>
        <el-form-item label="来源线索">
          <el-select v-model="form.leadId" filterable clearable placeholder="可选，从线索转化" style="width: 100%">
            <el-option v-for="option in leads" :key="option.value" :value="option.value" :label="option.label" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="canAssign" label="负责人">
          <el-select v-model="form.ownerId" filterable clearable placeholder="未分配" style="width: 100%">
            <el-option v-for="option in owners" :key="option.value" v-bind="option" />
          </el-select>
        </el-form-item>
        <el-form-item label="阶段" required>
          <el-select v-model="form.stage" style="width: 100%">
            <el-option v-for="stage in stages" :key="stage.value" :value="stage.value" :label="stage.label" />
          </el-select>
        </el-form-item>
        <el-form-item label="预计金额（元）">
          <el-input-number v-model="form.amount" :min="0" :precision="2" :controls="false" style="width: 100%" />
        </el-form-item>
        <el-form-item label="成交概率（%）">
          <el-input-number v-model="form.probability" :min="0" :max="100" :controls="false" style="width: 100%" />
        </el-form-item>
        <el-form-item label="预计签约日期">
          <el-date-picker v-model="form.expectedCloseDate" type="date" value-format="YYYY-MM-DD"
            placeholder="选择日期" style="width: 100%" />
        </el-form-item>
        <el-form-item label="来源渠道">
          <el-input v-model="form.source" maxlength="50" placeholder="例如：线索转化、活动、转介绍" />
        </el-form-item>
        <el-form-item label="备注" class="wide">
          <el-input v-model="form.remark" type="textarea" :rows="3" maxlength="5000" />
        </el-form-item>
      </div>
      <el-alert v-if="formError" :title="formError" type="error" :closable="false" show-icon />
      <div class="dialog-actions dialog-actions-split">
        <el-button v-if="editingId != null && canDelete" type="danger" plain :disabled="saving" @click="remove">删除商机</el-button>
        <span class="dialog-actions-right">
          <el-button :disabled="saving" @click="dialogOpen = false">取消</el-button>
          <el-button type="primary" native-type="submit" :loading="saving">保存</el-button>
        </span>
      </div>
    </el-form>
  </el-dialog>
</template>

<style scoped>
.crm-error { margin-bottom: 14px; }
.pipeline-toolbar { display: flex; align-items: center; gap: 12px; margin-bottom: 14px; }
.pipeline { display: flex; gap: 14px; overflow-x: auto; padding-bottom: 8px; }
.pipeline-column { flex: 0 0 248px; min-height: 300px; padding: 12px; border: 1px solid #ebeef5; border-radius: 10px; background: #f7f8fa; }
.pipeline-column > header { display: flex; align-items: center; gap: 7px; padding: 2px 4px 11px; }
.pipeline-column > header strong { color: #303133; font-size: 13px; }
.pipeline-column > header em { margin-left: auto; color: #a0aab5; font-size: 12px; font-style: normal; }
.stage-dot { width: 7px; height: 7px; border-radius: 50%; background: #409eff; }
.stage-new { background: #909399; }
.stage-contacted { background: #36cfc9; }
.stage-negotiating { background: #e6a23c; }
.stage-won { background: #67c23a; }
.stage-lost { background: #f56c6c; }
.pipeline-cards { display: grid; gap: 9px; }
.business-card { padding: 12px; border: 1px solid #ebeef5; border-radius: 8px; background: #fff; box-shadow: 0 1px 3px rgb(0 21 41 / 4%); transition: border-color .2s, transform .2s; }
.business-card.clickable { cursor: pointer; }
.business-card.clickable:hover { transform: translateY(-1px); border-color: var(--el-color-primary-light-5); }
.business-card-top { display: flex; align-items: flex-start; justify-content: space-between; gap: 8px; }
.business-card-top strong { color: #1f2d3d; font-size: 13px; line-height: 1.45; }
.business-amount { flex: 0 0 auto; color: #e6a23c; font-size: 12px; font-weight: 600; }
.business-customer { margin: 7px 0 0; color: #5a6a7a; font-size: 12px; }
.business-project { margin: 3px 0 0; color: #a0aab5; font-size: 11px; }
.business-card footer { display: flex; justify-content: space-between; gap: 8px; margin-top: 10px; color: #a0aab5; font-size: 11px; }
.pipeline-empty { margin: 18px 0; color: #c0c4cc; font-size: 12px; text-align: center; }
.dialog-actions-split { justify-content: space-between; }
.dialog-actions-right { display: flex; gap: 8px; }
@media (max-width: 640px) {
  .pipeline-toolbar { align-items: flex-start; flex-direction: column; }
  .pipeline-column { flex-basis: 220px; }
}
</style>
