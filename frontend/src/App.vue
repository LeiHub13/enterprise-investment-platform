<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, ApiError, query } from './api'
import type { Page, RecordData, User, Value } from './api'
import EntityFields from './EntityFields.vue'
import Dashboard from './Dashboard.vue'
import ContactPanel from './ContactPanel.vue'
import TagPanel from './TagPanel.vue'
import EnterpriseHistory from './EnterpriseHistory.vue'
import DataManagement from './DataManagement.vue'
import { followupFields, leadStages, projectStatuses, roleLabels, roleOptions, sections } from './sections'
import type { Field, Option, SectionKey } from './sections'

type ImportResult = {
  batchId: number
  totalCount: number
  successCount: number
  failCount: number
  errors: { row: number; message: string }[]
}

const user = ref<User | null>(null)
const starting = ref(true)
const login = ref({ username: '', password: '' })
const loginBusy = ref(false)
const loginError = ref('')
const passwordOpen = ref(false)
const passwordBusy = ref(false)
const passwordError = ref('')
const passwordDraft = ref({ currentPassword: '', newPassword: '', confirmation: '' })
const resetPasswordOpen = ref(false)
const resetPasswordBusy = ref(false)
const resetPasswordError = ref('')
const resetPasswordDraft = ref({ password: '', confirmation: '' })
const resetTarget = ref<RecordData | null>(null)
const active = ref<SectionKey>('enterprises')
const dashboardVisible = ref(true)
const dataManagementVisible = ref(false)
const section = computed(() => sections[active.value])
const visibleSections = computed(() => Object.values(sections).filter(item => {
  if (item.key === 'enterprises') return true
  if (item.key === 'users') return user.value?.role === 'ADMIN'
  return user.value?.role !== 'DATA_STEWARD'
}))
const canAssign = computed(() => ['ADMIN', 'INVESTMENT_MANAGER'].includes(user.value?.role ?? ''))
const canWrite = computed(() => {
  if (active.value === 'enterprises') return ['ADMIN', 'DATA_STEWARD'].includes(user.value?.role ?? '')
  if (active.value === 'leads') return canAssign.value || user.value?.role === 'INVESTMENT_OFFICER'
  if (active.value === 'projects') return canAssign.value
  return user.value?.role === 'ADMIN'
})
const canDelete = computed(() => active.value === 'leads' ? canAssign.value : canWrite.value)

const filters = ref<Record<string, Value>>({})
const rows = ref<RecordData[]>([])
const page = ref(1)
const size = ref(20)
const total = ref(0)
const loading = ref(false)
const listError = ref('')
let listRequest = 0

const editorOpen = ref(false)
const saving = ref(false)
const formError = ref('')
const editor = ref<Record<string, Value>>({})
const editId = ref<number | null>(null)
const originalStage = ref<Value>(null)
const choices = ref<Record<string, Option[]>>({})
const loadingChoice = ref('')
const choiceRequests: Record<string, number> = {}
const staff = ref<User[]>([])
const editorFields = computed(() => section.value.fields
  .filter(field => field.kind !== 'user' || canAssign.value)
  .map(field => {
    if (field.key !== 'leadStatus') return field
    const stage = originalStage.value
    if (!editId.value) return { ...field, required: true, options: leadStages.slice(0, 1) }
    const index = leadStages.findIndex(item => item.value === stage)
    const allowed = index < 0 || index >= 4
      ? [stage]
      : [stage, leadStages[index + 1].value, 'LOST']
    return { ...field, required: true, options: leadStages.filter(item => allowed.includes(item.value)) }
  }))

const detailOpen = ref(false)
const detail = ref<RecordData | null>(null)
const detailLoading = ref(false)
const followups = ref<RecordData[]>([])
const followupPage = ref(1)
const followupTotal = ref(0)
const followupLoading = ref(false)
let followupRequest = 0
const followupOpen = ref(false)
const followupDraft = ref<Record<string, Value>>({})
const followupError = ref('')
const followupSaving = ref(false)
const clearNextFollowup = ref(true)
const contactOptions = ref<Option[]>([])
const contactLoading = ref(false)
let contactRequest = 0

const importOpen = ref(false)
const importFile = ref<File | null>(null)
const importError = ref('')
const importing = ref(false)
const importResult = ref<ImportResult | null>(null)

function errorMessage(error: unknown) {
  if (error instanceof TypeError) return '无法连接服务，请确认后端正在运行'
  return error instanceof Error ? error.message : '操作失败，请重试'
}

function handleExpired(error: unknown) {
  if (error instanceof ApiError && error.status === 401) {
    user.value = null
    detailOpen.value = false
    editorOpen.value = false
    followupOpen.value = false
    importOpen.value = false
    loginError.value = '会话已过期，请重新登录'
  }
}

function showError(error: unknown) {
  handleExpired(error)
  ElMessage.error(errorMessage(error))
}

function signInReady(account: User) {
  user.value = account
  staff.value = []
  showDashboard()
}

onMounted(async () => {
  try {
    signInReady(await api<User>('/auth/me'))
  } catch (error) {
    if (!(error instanceof ApiError && error.status === 401)) loginError.value = errorMessage(error)
  } finally {
    starting.value = false
  }
})

async function signIn() {
  if (loginBusy.value) return
  if (!login.value.username.trim() || !login.value.password) {
    loginError.value = '请输入用户名和密码'
    return
  }
  loginBusy.value = true
  loginError.value = ''
  try {
    const account = await api<User>('/auth/login', 'POST', {
      username: login.value.username.trim(),
      password: login.value.password,
    })
    login.value.password = ''
    signInReady(account)
  } catch (error) {
    loginError.value = errorMessage(error)
  } finally {
    loginBusy.value = false
  }
}

async function signOut() {
  try {
    await api('/auth/logout', 'POST')
    user.value = null
    loginError.value = ''
  } catch (error) {
    showError(error)
  }
}

function openPassword() {
  passwordDraft.value = { currentPassword: '', newPassword: '', confirmation: '' }
  passwordError.value = ''
  passwordOpen.value = true
}

async function changePassword() {
  if (passwordBusy.value) return
  if (!passwordDraft.value.currentPassword || !passwordDraft.value.newPassword) {
    passwordError.value = '请填写原密码和新密码'
    return
  }
  if (passwordDraft.value.newPassword !== passwordDraft.value.confirmation) {
    passwordError.value = '两次输入的新密码不一致'
    return
  }
  passwordBusy.value = true
  passwordError.value = ''
  try {
    await api('/auth/password', 'PUT', {
      currentPassword: passwordDraft.value.currentPassword,
      newPassword: passwordDraft.value.newPassword,
    })
    passwordOpen.value = false
    passwordDraft.value = { currentPassword: '', newPassword: '', confirmation: '' }
    user.value = null
    loginError.value = '密码已修改，请重新登录'
  } catch (error) {
    handleExpired(error)
    passwordError.value = errorMessage(error)
  } finally {
    passwordBusy.value = false
  }
}

function openResetPassword(row: RecordData) {
  resetTarget.value = row
  resetPasswordDraft.value = { password: '', confirmation: '' }
  resetPasswordError.value = ''
  resetPasswordOpen.value = true
}

async function resetUserPassword() {
  if (!resetTarget.value || resetPasswordBusy.value) return
  if (!resetPasswordDraft.value.password) {
    resetPasswordError.value = '请填写新密码'
    return
  }
  if (resetPasswordDraft.value.password !== resetPasswordDraft.value.confirmation) {
    resetPasswordError.value = '两次输入的新密码不一致'
    return
  }
  resetPasswordBusy.value = true
  resetPasswordError.value = ''
  try {
    await api('/users/' + resetTarget.value.id + '/password', 'PUT', { password: resetPasswordDraft.value.password })
    resetPasswordOpen.value = false
    resetPasswordDraft.value = { password: '', confirmation: '' }
    ElMessage.success('密码已重置，对方需要重新登录')
  } catch (error) {
    handleExpired(error)
    resetPasswordError.value = errorMessage(error)
  } finally {
    resetPasswordBusy.value = false
  }
}

async function toggleUser(row: RecordData) {
  const enable = row.enabled === false || row.enabled === 0
  try {
    await ElMessageBox.confirm(`确定${enable ? '启用' : '停用'}账号 ${row.username} 吗？`, '账号状态', {
      type: 'warning', confirmButtonText: enable ? '启用' : '停用', cancelButtonText: '取消',
    })
  } catch {
    return
  }
  try {
    await api('/users/' + row.id + '/enabled', 'PUT', { enabled: enable })
    ElMessage.success(enable ? '账号已启用' : '账号已停用')
    await loadList()
  } catch (error) {
    showError(error)
  }
}

async function loadList() {
  const request = ++listRequest
  loading.value = true
  listError.value = ''
  try {
    const result = active.value === 'users'
      ? await api<RecordData[]>('/users').then(items => ({ items, total: items.length }))
      : await api<Page<RecordData>>('/' + active.value + query({ ...filters.value, page: page.value, size: size.value }))
    if (request !== listRequest) return
    rows.value = result.items
    total.value = result.total
  } catch (error) {
    if (request !== listRequest) return
    handleExpired(error)
    rows.value = []
    total.value = 0
    listError.value = errorMessage(error)
  } finally {
    if (request === listRequest) loading.value = false
  }
}

function showDashboard() {
  listRequest++
  detailOpen.value = false
  dashboardVisible.value = true
  dataManagementVisible.value = false
}

function showDataManagement() {
  listRequest++
  detailOpen.value = false
  dashboardVisible.value = false
  dataManagementVisible.value = true
}

function selectSection(key: SectionKey, initialFilters: Record<string, Value> = {}) {
  dashboardVisible.value = false
  dataManagementVisible.value = false
  active.value = key
  rows.value = []
  total.value = 0
  filters.value = initialFilters
  page.value = 1
  void loadList()
}

function navigateFromDashboard(key: 'enterprises' | 'projects' | 'leads', due?: string) {
  selectSection(key, due ? { due } : {})
}

function openDashboardLead(id: number) {
  selectSection('leads')
  void openDetail({ id })
}

function search() {
  page.value = 1
  void loadList()
}

function resetFilters() {
  filters.value = {}
  search()
}

function changePage(next: number) {
  page.value = next
  void loadList()
}

function changeSize(next: number) {
  size.value = next
  search()
}

function label(value: Value | undefined, options: Option[]) {
  return options.find(item => item.value === value)?.label ?? value
}

function money(value: Value | undefined) {
  if (value == null || value === '') return '—'
  const [integer, fraction] = String(value).split('.')
  const grouped = integer.replace(/\B(?=(\d{3})+(?!\d))/g, ',')
  return fraction === undefined ? grouped : grouped + '.' + fraction
}

function display(row: RecordData, key: string): Value {
  const value = row[key]
  if (key === 'region') return [row.provinceCode, row.cityCode].filter(Boolean).join(' / ') || '—'
  if (key === 'investmentRange') return row.investmentMin == null && row.investmentMax == null
    ? '—' : money(row.investmentMin) + ' 至 ' + money(row.investmentMax)
  if (key === 'registeredCapital' || key === 'investmentMin' || key === 'investmentMax') return money(value)
  if (key === 'enterpriseId') return row.enterpriseName || value || '—'
  if (key === 'projectId') return row.projectName || value || '—'
  if (key === 'ownerId') return row.ownerUsername || staff.value.find(item => item.id === value)?.username || value || '—'
  if (key === 'projectStatus') return label(value, projectStatuses) ?? '—'
  if (key === 'leadStatus') return label(value, leadStages) ?? '—'
  if (key === 'role') return label(value, roleOptions) ?? '—'
  if (key === 'enabled') return value === true || value === 1 ? '启用' : '停用'
  if (key === 'followupType') return label(value, followupFields[0].options ?? []) ?? '—'
  if (key.endsWith('At') && typeof value === 'string') return value.replace('T', ' ').slice(0, 19)
  return value == null || value === '' ? '—' : value
}

function statusTone(value: Value | undefined) {
  if (value === 'WON' || value === 'ACTIVE') return 'success'
  if (value === 'LOST') return 'danger'
  if (value === 'NEGOTIATING' || value === 'PAUSED') return 'warning'
  if (value === 'NEW' || value === 'DRAFT' || value === 'CLOSED') return 'info'
  return 'primary'
}

function sectionPath(id?: number) {
  return '/' + active.value + (id == null ? '' : '/' + id)
}

async function loadStaff() {
  if (!canAssign.value) return
  staff.value = await api<User[]>('/users')
  choices.value.ownerId = staff.value
    .filter(account => account.role !== 'DATA_STEWARD' && account.enabled !== false)
    .map(account => ({ value: account.id, label: account.username + ' · ' + roleLabels[account.role] }))
}

async function openEditor(row?: RecordData) {
  const key = active.value
  try {
    const current = row ? await api<RecordData>(sectionPath(row.id)) : null
    if (active.value !== key) return
    const defaults: Record<string, Value> = {}
    if (active.value === 'projects') defaults.projectStatus = 'ACTIVE'
    if (active.value === 'leads') defaults.leadStatus = 'NEW'
    editId.value = current?.id ?? null
    originalStage.value = current?.leadStatus ?? null
    editor.value = Object.fromEntries(section.value.fields.map(field => {
      const value = current?.[field.key] ?? defaults[field.key] ?? null
      return [field.key, field.kind === 'datetime' && typeof value === 'string' ? value.slice(0, 19) : value]
    }))
    choices.value = {}
    if (typeof current?.enterpriseId === 'number' || typeof current?.enterpriseId === 'string') choices.value.enterpriseId = [
      { value: current.enterpriseId, label: String(current.enterpriseName ?? current.enterpriseId) },
    ]
    if (typeof current?.projectId === 'number' || typeof current?.projectId === 'string') choices.value.projectId = [
      { value: current.projectId, label: String(current.projectName ?? current.projectId) },
    ]
    if (section.value.fields.some(field => field.kind === 'user')) await loadStaff()
    if (active.value !== key) return
    formError.value = ''
    editorOpen.value = true
  } catch (error) {
    showError(error)
  }
}

async function searchChoice(field: Field, keyword: string) {
  const request = (choiceRequests[field.key] ?? 0) + 1
  choiceRequests[field.key] = request
  loadingChoice.value = field.key
  const path = field.kind === 'enterprise' ? '/enterprises' : '/projects'
  try {
    const data = await api<Page<RecordData>>(path + query({ keyword, size: 20, page: 1 }))
    if (choiceRequests[field.key] !== request || !editorOpen.value) return
    const currentId = editor.value[field.key]
    const selected = choices.value[field.key]?.find(item => item.value === currentId)
    const options = data.items.map(item => ({ value: item.id, label: String(item.name ?? item.projectName ?? item.id) }))
    if (selected && !options.some(item => item.value === selected.value)) {
      choices.value[field.key] = [selected, ...options]
    } else {
      choices.value[field.key] = options
    }
  } catch (error) {
    showError(error)
  } finally {
    if (choiceRequests[field.key] === request && loadingChoice.value === field.key) loadingChoice.value = ''
  }
}

function formPayload(form: Record<string, Value>, fields: Field[]) {
  const entries = fields.map(field => {
    const original = form[field.key]
    const value = typeof original === 'string' && field.kind !== 'password' ? original.trim() : original
    if (field.required && (value == null || value === '')) throw new Error('请填写' + field.label)
    if (field.kind === 'decimal' && value != null && value !== '' && !/^\d{1,16}(\.\d{1,2})?$/.test(String(value))) {
      throw new Error(field.label + '须为非负金额，最多 16 位整数和 2 位小数')
    }
    return [field.key, value === '' ? null : value]
  })
  return Object.fromEntries(entries)
}

async function saveEditor() {
  if (saving.value) return
  saving.value = true
  formError.value = ''
  try {
    const payload = formPayload(editor.value, editorFields.value)
    const id = editId.value ?? undefined
    await api(sectionPath(id), id == null ? 'POST' : 'PUT', payload)
    editorOpen.value = false
    ElMessage.success(id == null ? '创建成功' : '保存成功')
    if (detailOpen.value && detail.value) await openDetail(detail.value)
    await loadList()
  } catch (error) {
    handleExpired(error)
    formError.value = errorMessage(error)
  } finally {
    saving.value = false
  }
}

async function removeRecord(row: RecordData) {
  try {
    await ElMessageBox.confirm('确定删除此' + section.value.singular + '吗？', '确认删除', {
      type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消',
    })
  } catch {
    return
  }
  try {
    await api(sectionPath(row.id), 'DELETE')
    ElMessage.success('删除成功')
    if (rows.value.length === 1 && page.value > 1) page.value--
    await loadList()
  } catch (error) {
    showError(error)
  }
}

async function openDetail(row: RecordData) {
  detailOpen.value = true
  detail.value = null
  detailLoading.value = true
  followups.value = []
  followupRequest++
  followupPage.value = 1
  followupTotal.value = 0
  try {
    detail.value = await api<RecordData>(sectionPath(row.id))
    if (active.value === 'leads') await loadFollowups(1)
  } catch (error) {
    detailOpen.value = false
    showError(error)
  } finally {
    detailLoading.value = false
  }
}

async function loadFollowups(next: number) {
  if (!detail.value) return
  const request = ++followupRequest
  followupPage.value = next
  followupLoading.value = true
  try {
    const result = await api<Page<RecordData>>(sectionPath(detail.value.id) + '/followups' + query({ page: next, size: 10 }))
    if (request !== followupRequest) return
    followups.value = result.items
    followupTotal.value = result.total
  } catch (error) {
    if (request === followupRequest) showError(error)
  } finally {
    if (request === followupRequest) followupLoading.value = false
  }
}

function openFollowup() {
  followupDraft.value = { followupType: 'PHONE' }
  clearNextFollowup.value = true
  contactOptions.value = []
  followupError.value = ''
  followupOpen.value = true
  void searchFollowupContacts('')
}

async function searchFollowupContacts(keyword: string) {
  const enterpriseId = detail.value?.enterpriseId
  if (typeof enterpriseId !== 'number') return
  const request = ++contactRequest
  contactLoading.value = true
  try {
    const result = await api<Page<{ id: number; name: string; position: string | null }>>(
      `/enterprises/${enterpriseId}/contacts` + query({ keyword, page: 1, size: 20 }),
    )
    if (request !== contactRequest || !followupOpen.value) return
    const selected = contactOptions.value.find(option => option.value === followupDraft.value.contactId)
    const options = result.items.map(contact => ({
      value: contact.id, label: contact.name + (contact.position ? ' · ' + contact.position : ''),
    }))
    contactOptions.value = selected && !options.some(option => option.value === selected.value)
      ? [selected, ...options] : options
  } catch (error) {
    if (request === contactRequest) {
      handleExpired(error)
      followupError.value = errorMessage(error)
    }
  } finally {
    if (request === contactRequest) contactLoading.value = false
  }
}

async function saveFollowup() {
  if (!detail.value || followupSaving.value) return
  followupSaving.value = true
  followupError.value = ''
  try {
    const payload = formPayload(followupDraft.value, followupFields)
    await api(sectionPath(detail.value.id) + '/followups', 'POST', {
      ...payload,
      contactId: followupDraft.value.contactId ?? null,
      clearNextFollowup: clearNextFollowup.value,
    })
    followupOpen.value = false
    ElMessage.success('跟进已记录')
    await Promise.all([openDetail(detail.value), loadList()])
  } catch (error) {
    handleExpired(error)
    followupError.value = errorMessage(error)
  } finally {
    followupSaving.value = false
  }
}

function openImport() {
  importFile.value = null
  importError.value = ''
  importResult.value = null
  importOpen.value = true
}

function chooseFile(event: Event) {
  importFile.value = (event.target as HTMLInputElement).files?.[0] ?? null
  importError.value = ''
  importResult.value = null
}

async function upload() {
  if (importing.value) return
  const file = importFile.value
  if (!file || !file.name.toLowerCase().endsWith('.xlsx')) {
    importError.value = '请选择 .xlsx 文件'
    return
  }
  if (file.size > 5 * 1024 * 1024) {
    importError.value = '文件大小不能超过 5 MB'
    return
  }
  const form = new FormData()
  form.set('file', file)
  importing.value = true
  importError.value = ''
  try {
    importResult.value = await api<ImportResult>('/enterprises/import', 'POST', form)
    await loadList()
  } catch (error) {
    handleExpired(error)
    importError.value = errorMessage(error)
  } finally {
    importing.value = false
  }
}
</script>

<template>
  <main v-if="starting" class="starting" aria-live="polite">正在连接招商管理平台…</main>
  <main v-else-if="!user" class="login-screen">
    <section class="login-brand">
      <div class="brand-mark">招</div>
      <p class="login-eyebrow">企业内部工作台</p>
      <h1>全国企业资源<br />与招商管理平台</h1>
      <p>在同一个工作台维护企业档案、管理招商项目，并记录线索进展。</p>
    </section>
    <section class="login-panel" aria-labelledby="login-heading">
      <p class="eyebrow">招商工作台</p>
      <h2 id="login-heading">登录</h2>
      <el-form label-position="top" @submit.prevent="signIn">
        <el-form-item label="用户名">
          <el-input v-model="login.username" autocomplete="username" />
        </el-form-item>
        <el-form-item label="密码">
          <el-input v-model="login.password" type="password" autocomplete="current-password" show-password />
        </el-form-item>
        <el-alert v-if="loginError" :title="loginError" type="error" :closable="false" show-icon />
        <el-button type="primary" native-type="submit" :loading="loginBusy" class="login-submit">登录平台</el-button>
      </el-form>
    </section>
  </main>

  <div v-else class="workspace">
    <aside class="sidebar">
      <div class="sidebar-brand">
        <div class="brand-mark">招</div>
        <div><strong>企业招商平台</strong><small>内部管理系统</small></div>
      </div>
      <div class="nav-caption">业务模块</div>
      <nav aria-label="业务模块">
        <button type="button" :class="{ active: dashboardVisible }" :aria-current="dashboardVisible ? 'page' : undefined"
          @click="showDashboard"><span aria-hidden="true">◫</span>招商工作台</button>
        <button
          v-for="item in visibleSections"
          :key="item.key"
          type="button"
          :class="{ active: !dashboardVisible && !dataManagementVisible && active === item.key }"
          :aria-current="!dashboardVisible && !dataManagementVisible && active === item.key ? 'page' : undefined"
          @click="selectSection(item.key)"
        >
          <span aria-hidden="true">{{ item.glyph }}</span>{{ item.label }}
        </button>
        <button v-if="user.role === 'ADMIN' || user.role === 'DATA_STEWARD'" type="button"
          :class="{ active: dataManagementVisible }" :aria-current="dataManagementVisible ? 'page' : undefined"
          @click="showDataManagement"><span aria-hidden="true">数</span>数据管理</button>
      </nav>
    </aside>

    <div class="workspace-content">
      <header class="topbar">
        <span>招商工作台</span>
        <div class="account">
          <strong>{{ user.username }}</strong>
          <el-tag effect="plain" type="info">{{ roleLabels[user.role] }}</el-tag>
          <el-button text @click="openPassword">修改密码</el-button>
          <el-button text @click="signOut">退出</el-button>
        </div>
      </header>

      <main class="main">
        <Dashboard v-if="dashboardVisible" @navigate="navigateFromDashboard"
          @open-lead="openDashboardLead" @expired="handleExpired" />
        <DataManagement v-else-if="dataManagementVisible" @expired="handleExpired" />
        <template v-else>
        <div class="page-heading">
          <div>
            <h1>{{ section.label }}</h1>
            <p>{{ section.summary }}</p>
          </div>
          <div v-if="canWrite" class="page-actions">
            <el-button v-if="active === 'enterprises'" @click="openImport">Excel 批量导入</el-button>
            <el-button type="primary" @click="openEditor()">新建{{ section.singular }}</el-button>
          </div>
        </div>

        <section class="list-card" :aria-label="section.label + '列表'">
          <el-form v-if="section.filters" class="filters" inline @submit.prevent="search">
            <el-form-item v-for="filter in section.filters" :key="filter.key">
              <el-select v-if="filter.options" v-model="filters[filter.key]" :placeholder="filter.label" clearable :value-on-clear="null">
                <el-option v-for="option in filter.options" :key="option.value" v-bind="option" />
              </el-select>
              <el-input
                v-else
                :model-value="filters[filter.key] ?? ''"
                :aria-label="filter.label"
                :placeholder="filter.label"
                clearable
                @update:model-value="filters[filter.key] = $event"
              />
            </el-form-item>
            <el-form-item>
              <el-button type="primary" native-type="submit">查询</el-button>
              <el-button @click="resetFilters">重置</el-button>
            </el-form-item>
          </el-form>
          <el-alert v-if="listError" class="list-error" :title="listError" type="error" :closable="false" show-icon />
          <el-table :key="active" v-loading="loading" :data="rows" row-key="id" empty-text="暂无记录">
            <el-table-column
              v-for="(column, index) in section.columns"
              :key="column.key"
              :label="column.label"
              :min-width="column.width"
              show-overflow-tooltip
            >
              <template #default="{ row }">
                <el-button v-if="index === 0 && active !== 'users'" class="record-link" link type="primary" @click="openDetail(row)">
                  {{ display(row, column.key) }}
                </el-button>
                <el-tag v-else-if="column.key.endsWith('Status') && row[column.key]" :type="statusTone(row[column.key])" effect="light">
                  {{ display(row, column.key) }}
                </el-tag>
                <span v-else>{{ display(row, column.key) }}</span>
              </template>
            </el-table-column>
            <el-table-column v-if="active !== 'users'" label="操作" :min-width="canWrite ? 155 : 70" fixed="right">
              <template #default="{ row }">
                <el-button link type="primary" @click="openDetail(row)">{{ active === 'leads' ? '跟进' : '详情' }}</el-button>
                <el-button v-if="canWrite" link type="primary" @click="openEditor(row)">编辑</el-button>
                <el-button v-if="canDelete" link type="danger" @click="removeRecord(row)">删除</el-button>
              </template>
            </el-table-column>
            <el-table-column v-else label="操作" min-width="160" fixed="right">
              <template #default="{ row }">
                <template v-if="row.id !== user.id">
                  <el-button link type="primary" @click="openResetPassword(row)">重置密码</el-button>
                  <el-button link :type="row.enabled ? 'danger' : 'primary'" @click="toggleUser(row)">
                    {{ row.enabled ? '停用' : '启用' }}
                  </el-button>
                </template>
                <span v-else class="muted">当前账号</span>
              </template>
            </el-table-column>
          </el-table>
          <div v-if="active !== 'users'" class="pagination">
            <el-pagination
              :current-page="page" :page-size="size" :page-sizes="[10, 20, 50, 100]" :total="total"
              layout="total, sizes, prev, pager, next" background
              @current-change="changePage" @size-change="changeSize"
            />
          </div>
        </section>
        </template>
      </main>
    </div>

    <el-dialog
      v-model="editorOpen" :title="(editId == null ? '新建' : '编辑') + section.singular" width="min(680px, 94vw)"
      :close-on-click-modal="false" :close-on-press-escape="!saving" :show-close="!saving"
    >
      <el-form label-position="top" @submit.prevent="saveEditor">
        <EntityFields v-model="editor" :fields="editorFields" :choices="choices" :loading-choice="loadingChoice" @search="searchChoice" />
        <el-alert v-if="formError" :title="formError" type="error" :closable="false" show-icon />
        <div class="dialog-actions">
          <el-button :disabled="saving" @click="editorOpen = false">取消</el-button>
          <el-button type="primary" native-type="submit" :loading="saving">保存</el-button>
        </div>
      </el-form>
    </el-dialog>

    <el-drawer v-model="detailOpen" :title="section.singular + '详情'" size="min(680px, 94vw)">
      <div v-loading="detailLoading" class="detail-body">
        <template v-if="detail">
          <h2>{{ detail.name || detail.projectName || detail.enterpriseName }}</h2>
          <dl class="detail-grid">
            <div v-for="field in section.fields" :key="field.key" :class="{ wide: field.kind === 'text' }">
              <dt>{{ field.label }}</dt><dd>{{ display(detail, field.key) }}</dd>
            </div>
            <div><dt>创建时间</dt><dd>{{ display(detail, 'createdAt') }}</dd></div>
            <div><dt>更新时间</dt><dd>{{ display(detail, 'updatedAt') }}</dd></div>
          </dl>
          <TagPanel v-if="active === 'enterprises'" :key="'tags-' + detail.id"
            :enterprise-id="detail.id" :can-edit="canWrite" @expired="handleExpired" />
          <ContactPanel v-if="user.role !== 'DATA_STEWARD' && (active === 'enterprises' || active === 'leads')"
            :key="String(active === 'enterprises' ? detail.id : detail.enterpriseId)"
            :enterprise-id="Number(active === 'enterprises' ? detail.id : detail.enterpriseId)"
            :can-delete="canAssign" @expired="handleExpired" />
          <section v-if="active === 'leads'" class="followups" aria-labelledby="followup-heading">
            <div class="followup-heading">
              <h3 id="followup-heading">跟进记录</h3>
              <el-button v-if="canWrite" size="small" type="primary" @click="openFollowup">新增跟进</el-button>
            </div>
            <div v-loading="followupLoading">
              <el-empty v-if="!followups.length && !followupLoading" description="暂无跟进记录" :image-size="55" />
              <article v-for="entry in followups" :key="entry.id" class="followup-record">
                <header>
                  <strong>{{ display(entry, 'followupType') }}</strong>
                  <span>{{ display(entry, 'createdByUsername') }} · {{ display(entry, 'createdAt') }}</span>
                </header>
                <p>{{ entry.content }}</p>
                <small v-if="entry.contactName">联系人 · {{ entry.contactName }}</small>
                <small v-if="entry.result">结果 · {{ entry.result }}</small>
                <small v-if="entry.nextAction">下一步 · {{ entry.nextAction }}</small>
                <small v-if="entry.nextFollowupAt">下次跟进 · {{ display(entry, 'nextFollowupAt') }}</small>
              </article>
              <el-pagination v-if="followupTotal > 10" small :current-page="followupPage" :total="followupTotal"
                :page-size="10" layout="prev, pager, next" @current-change="loadFollowups" />
            </div>
          </section>
          <EnterpriseHistory v-if="active === 'enterprises'" :key="'history-' + detail.id"
            :enterprise-id="detail.id" @expired="handleExpired" />
        </template>
      </div>
    </el-drawer>

    <el-dialog v-model="passwordOpen" title="修改密码" width="min(480px, 94vw)"
      :close-on-click-modal="false" :close-on-press-escape="!passwordBusy" :show-close="!passwordBusy">
      <el-form label-position="top" @submit.prevent="changePassword">
        <el-form-item label="原密码" required><el-input v-model="passwordDraft.currentPassword" type="password" show-password autocomplete="current-password" /></el-form-item>
        <el-form-item label="新密码" required><el-input v-model="passwordDraft.newPassword" type="password" show-password autocomplete="new-password" placeholder="至少 12 个字符，最多 72 个 UTF-8 字节" /></el-form-item>
        <el-form-item label="再次输入新密码" required><el-input v-model="passwordDraft.confirmation" type="password" show-password autocomplete="new-password" /></el-form-item>
        <el-alert v-if="passwordError" :title="passwordError" type="error" :closable="false" show-icon />
        <div class="dialog-actions">
          <el-button :disabled="passwordBusy" @click="passwordOpen = false">取消</el-button>
          <el-button type="primary" native-type="submit" :loading="passwordBusy">修改并重新登录</el-button>
        </div>
      </el-form>
    </el-dialog>

    <el-dialog v-model="resetPasswordOpen" title="重置账号密码" width="min(480px, 94vw)"
      :close-on-click-modal="false" :close-on-press-escape="!resetPasswordBusy" :show-close="!resetPasswordBusy">
      <el-form label-position="top" @submit.prevent="resetUserPassword">
        <p>为 {{ resetTarget?.username }} 设置新密码，对方需要重新登录。</p>
        <el-form-item label="新密码" required><el-input v-model="resetPasswordDraft.password" type="password" show-password autocomplete="new-password" placeholder="至少 12 个字符，最多 72 个 UTF-8 字节" /></el-form-item>
        <el-form-item label="再次输入新密码" required><el-input v-model="resetPasswordDraft.confirmation" type="password" show-password autocomplete="new-password" /></el-form-item>
        <el-alert v-if="resetPasswordError" :title="resetPasswordError" type="error" :closable="false" show-icon />
        <div class="dialog-actions">
          <el-button :disabled="resetPasswordBusy" @click="resetPasswordOpen = false">取消</el-button>
          <el-button type="primary" native-type="submit" :loading="resetPasswordBusy">重置密码</el-button>
        </div>
      </el-form>
    </el-dialog>

    <el-dialog v-model="followupOpen" title="新增跟进记录" width="min(620px, 94vw)"
      :close-on-click-modal="false" :close-on-press-escape="!followupSaving" :show-close="!followupSaving">
      <el-form label-position="top" @submit.prevent="saveFollowup">
        <EntityFields v-model="followupDraft" :fields="followupFields" />
        <el-form-item label="联系人">
          <el-select v-model="followupDraft.contactId" filterable remote clearable :value-on-clear="null"
            :remote-method="searchFollowupContacts" :loading="contactLoading" placeholder="输入姓名搜索"
            @visible-change="(visible: boolean) => visible && searchFollowupContacts('')">
            <el-option v-for="option in contactOptions" :key="option.value" v-bind="option" />
          </el-select>
        </el-form-item>
        <el-checkbox v-model="clearNextFollowup">没有填写下次跟进时间时，清除原提醒</el-checkbox>
        <el-alert v-if="followupError" :title="followupError" type="error" :closable="false" show-icon />
        <div class="dialog-actions">
          <el-button :disabled="followupSaving" @click="followupOpen = false">取消</el-button>
          <el-button type="primary" native-type="submit" :loading="followupSaving">保存跟进</el-button>
        </div>
      </el-form>
    </el-dialog>

    <el-dialog v-model="importOpen" title="Excel 批量导入企业" width="min(700px, 94vw)"
      :close-on-click-modal="false" :close-on-press-escape="!importing" :show-close="!importing">
      <div class="import-instructions">
        <p>上传 .xlsx 文件，最大 5 MB。第一张工作表的首行为表头，企业名称为必填列，单次最多 5,000 行数据。</p>
        <p>表头示例</p>
        <code>企业名称 | 统一社会信用代码 | 省份代码 | 城市代码 | 行业代码 | 经营状态 | 注册资本 | 成立日期</code>
        <p>信用代码与行政区划代码按文本填写，注册资本以元填写，成立日期使用 Excel 日期或 YYYY-MM-DD。重复或疑似重复的记录会显示对应行号。</p>
      </div>
      <input type="file" accept=".xlsx" aria-label="选择 Excel 文件" :disabled="importing" @change="chooseFile" />
      <el-alert v-if="importError" class="import-alert" :title="importError" type="error" :closable="false" show-icon />
      <div v-if="importResult" class="import-result">
        <strong>批次 {{ importResult.batchId }}</strong>
        <span>共 {{ importResult.totalCount }} 行，成功 {{ importResult.successCount }} 行，失败 {{ importResult.failCount }} 行</span>
        <el-table v-if="importResult.errors.length" :data="importResult.errors" size="small" max-height="230">
          <el-table-column prop="row" label="Excel 行号" width="110" />
          <el-table-column prop="message" label="失败原因" />
        </el-table>
      </div>
      <div class="dialog-actions">
        <el-button :disabled="importing" @click="importOpen = false">关闭</el-button>
        <el-button type="primary" :disabled="!importFile || !!importResult" :loading="importing" @click="upload">开始导入</el-button>
      </div>
    </el-dialog>
  </div>
</template>
