<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Search, Upload } from '@element-plus/icons-vue'
import { api, ApiError, clearToken, query, setToken } from './api'
import type { Page, RecordData, User, Value } from './api'
import { auth, loadUser, setUser } from './auth'
import LoginView from './LoginView.vue'
import AppSidebar from './layout/AppSidebar.vue'
import AppNavbar from './layout/AppNavbar.vue'
import AppTagsView from './layout/AppTagsView.vue'
import { hasPermission, permissionsForRole } from './permissions'
import EntityFields from './EntityFields.vue'
import Dashboard from './Dashboard.vue'
import ContactPanel from './ContactPanel.vue'
import TagPanel from './TagPanel.vue'
import EnterpriseHistory from './EnterpriseHistory.vue'
import DataManagement from './DataManagement.vue'
import CollectorPanel from './CollectorPanel.vue'
import ObservationPanel from './ObservationPanel.vue'
import RolePanel from './RolePanel.vue'
import BusinessPanel from './BusinessPanel.vue'
import ContractPanel from './ContractPanel.vue'
import ReceivablePanel from './ReceivablePanel.vue'
import StatisticsPanel from './StatisticsPanel.vue'
import Customer360Panel from './Customer360Panel.vue'
import BidRadarPanel from './BidRadarPanel.vue'
import AgentPanel from './AgentPanel.vue'
import OpenClawPanel from './OpenClawPanel.vue'
import RegionSelect from './RegionSelect.vue'
import { loadRegions, regionCode, regionName, regionValue } from './regions'
import type { RegionLevel } from './regions'
import { followupFields, leadStages, projectStatuses, roleLabels, roleOptions, sections } from './sections'
import type { Field, Option, SectionKey } from './sections'

type ImportResult = {
  batchId: number
  totalCount: number
  successCount: number
  failCount: number
  errors: { row: number; message: string }[]
}

type LoginResult = User & { token?: string }
type CustomView = 'businesses' | 'contracts' | 'receivables' | 'statistics' | 'bidradar' | 'openclaw' | 'agent' | 'collector' | 'observations'
type ViewKey = 'dashboard' | 'data' | 'roles' | CustomView | SectionKey
type TabItem = { key: ViewKey; label: string; closable: boolean }
type RoleSummary = { id: number; code: string; name: string; builtIn?: boolean; enabled?: boolean; userCount?: number }

const REMEMBER_KEY = 'enterprise-investment-username'
const COLLAPSE_KEY = 'enterprise-investment-collapsed'
const rememberedUsername = localStorage.getItem(REMEMBER_KEY) ?? ''

const route = useRoute()
const router = useRouter()
const user = computed(() => auth.user)
const starting = ref(true)
const login = ref({ username: rememberedUsername, password: '' })
const remember = ref(Boolean(rememberedUsername))
const collapsed = ref(localStorage.getItem(COLLAPSE_KEY) === '1')
const mobileMenuOpen = ref(false)
const refreshKey = ref(0)
const openTabs = ref<TabItem[]>([{ key: 'dashboard', label: '招商工作台', closable: false }])
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
const roleChangeOpen = ref(false)
const roleChangeBusy = ref(false)
const roleChangeError = ref('')
const roleChangeTarget = ref<RecordData | null>(null)
const roleChangeValue = ref('')
const SECTION_KEYS: SectionKey[] = ['enterprises', 'projects', 'leads', 'users']
const CUSTOM_VIEWS: CustomView[] = ['businesses', 'contracts', 'receivables', 'statistics', 'bidradar', 'openclaw', 'agent', 'collector', 'observations']
const routeName = computed(() => String(route.name ?? ''))
const active = computed<SectionKey>(() => SECTION_KEYS.includes(routeName.value as SectionKey)
  ? routeName.value as SectionKey : 'enterprises')
const dashboardVisible = computed(() => routeName.value === 'dashboard')
const dataManagementVisible = computed(() => routeName.value === 'data')
const rolesVisible = computed(() => routeName.value === 'roles')
const customView = computed<CustomView | null>(() => CUSTOM_VIEWS.includes(routeName.value as CustomView)
  ? routeName.value as CustomView : null)
let preparedSection: SectionKey | null = null
let sessionReady = false
const roleList = ref<RoleSummary[]>([])
const section = computed(() => sections[active.value])
const permissionSet = computed(() => new Set(user.value?.permissions?.length
  ? user.value.permissions
  : permissionsForRole(user.value?.role)))

function can(code: string) {
  return hasPermission(permissionSet.value, code)
}

const visibleSections = computed(() => Object.values(sections).filter(item => {
  if (item.key === 'enterprises') return can('enterprise:view')
  if (item.key === 'projects') return can('project:view')
  if (item.key === 'leads') return can('lead:view')
  if (item.key === 'users') return can('user:manage')
  return false
}))
const canAssign = computed(() => can('lead:assign'))
const canWrite = computed(() => {
  if (active.value === 'enterprises') return can('enterprise:write')
  if (active.value === 'projects') return can('project:write')
  if (active.value === 'leads') return can('lead:create') || can('lead:update')
  return can('user:manage')
})
const canDelete = computed(() => {
  if (active.value === 'leads') return can('lead:delete')
  if (active.value === 'projects') return can('project:write')
  if (active.value === 'enterprises') return can('enterprise:write')
  return can('user:manage')
})

const viewLabels: Record<ViewKey, string> = {
  dashboard: '招商工作台',
  data: '数据管理',
  roles: '角色权限',
  businesses: '商机管道',
  contracts: '招商合同',
  receivables: '回款管理',
  statistics: '业绩统计',
  bidradar: '标讯雷达',
  agent: '招商Agent',
  openclaw: 'OpenClaw 助手',
  collector: '采集器',
  observations: '观察记录',
  enterprises: sections.enterprises.label,
  projects: sections.projects.label,
  leads: sections.leads.label,
  users: sections.users.label,
}
const currentKey = computed<ViewKey>(() => {
  const name = routeName.value as ViewKey
  return name in viewLabels ? name : 'dashboard'
})
const currentLabel = computed(() => viewLabels[currentKey.value])
const documentTitle = computed(() => {
  if (starting.value) return '全国企业资源与招商管理平台'
  return user.value ? `${currentLabel.value} - 企业招商平台` : '登录 - 企业招商平台'
})
watch(documentTitle, title => {
  document.title = title
}, { immediate: true })
const roleNameByCode = computed(() => new Map(roleList.value.map(role => [role.code, role.name])))
const roleChoices = computed(() => roleList.value.length
  ? roleList.value.map(role => ({ value: role.code, label: role.name, disabled: role.enabled === false }))
  : roleOptions.map(option => ({ value: option.value, label: option.label, disabled: false })))
const menuItems = computed(() => {
  if (user.value?.menus?.length) {
    const items = user.value.menus.filter(item => item.key !== 'panyue-agent' && (item.key !== 'openclaw' || user.value?.role === 'ADMIN'))
      .map(item => ({ key: item.key, label: item.label, group: item.group }))
    if (user.value.role === 'ADMIN' && !items.some(item => item.key === 'openclaw')) {
      items.push({ key: 'openclaw', label: viewLabels.openclaw, group: 'OpenClaw 模块' })
    }
    return items
  }
  const items: { key: string; label: string; group: string }[] = []
  if (can('dashboard:view')) items.push({ key: 'dashboard', label: viewLabels.dashboard, group: '概览' })
  if (can('statistics:view')) items.push({ key: 'statistics', label: viewLabels.statistics, group: '概览' })
  visibleSections.value.forEach(item => {
    if (item.key !== 'users') items.push({ key: item.key, label: item.label, group: 'CRM业务模块' })
  })
  if (can('lead:view')) items.push({ key: 'observations', label: viewLabels.observations, group: 'CRM业务模块' })
  if (can('bidradar:view')) items.push({ key: 'bidradar', label: viewLabels.bidradar, group: '标讯雷达模块' })
  if (can('agent:use')) items.push({ key: 'agent', label: viewLabels.agent, group: '智能招商' })
  if (user.value?.role === 'ADMIN') items.push({ key: 'openclaw', label: viewLabels.openclaw, group: 'OpenClaw 模块' })
  if (can('business:view')) items.push({ key: 'businesses', label: viewLabels.businesses, group: 'CRM业务模块' })
  if (can('contract:view')) items.push({ key: 'contracts', label: viewLabels.contracts, group: 'CRM业务模块' })
  if (can('receivable:view')) items.push({ key: 'receivables', label: viewLabels.receivables, group: 'CRM业务模块' })
  if (visibleSections.value.some(item => item.key === 'users')) {
    items.push({ key: 'users', label: viewLabels.users, group: '系统管理' })
  }
  if (can('governance:view')) items.push({ key: 'data', label: viewLabels.data, group: '系统管理' })
  if (can('governance:view')) items.push({ key: 'collector', label: viewLabels.collector, group: '数据采集' })
  if (can('system:role:manage')) items.push({ key: 'roles', label: viewLabels.roles, group: '系统管理' })
  return items
})

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
const originalRegions = ref<Record<string, Value>>({})
const editId = ref<number | null>(null)
const originalStage = ref<Value>(null)
const choices = ref<Record<string, Option[]>>({})
const loadingChoice = ref('')
const choiceRequests: Record<string, number> = {}
const staff = ref<User[]>([])
const editorFields = computed(() => section.value.fields
  .filter(field => field.kind !== 'user' || canAssign.value)
  .map(field => {
    if (section.value.key === 'users' && field.key === 'role' && roleList.value.length) {
      return { ...field, options: roleList.value.map(role => ({ value: role.code, label: role.name })) }
    }
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
const leadEvidence = ref<RecordData[]>([])
const leadAnalysis = ref<Record<string, unknown> | null>(null)
const leadReasons = computed<string[]>(() => {
  const value = leadAnalysis.value?.reasons
  if (Array.isArray(value)) return value.map(String)
  if (typeof value !== 'string') return []
  try {
    const parsed: unknown = JSON.parse(value)
    return Array.isArray(parsed) ? parsed.map(String) : []
  } catch {
    return []
  }
})
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
    clearToken()
    setUser(null)
    preparedSection = null
    sessionReady = false
    detailOpen.value = false
    editorOpen.value = false
    followupOpen.value = false
    importOpen.value = false
    mobileMenuOpen.value = false
    openTabs.value = [{ key: 'dashboard', label: viewLabels.dashboard, closable: false }]
    loginError.value = '登录已过期，请重新登录'
    if (route.name !== 'login') {
      void router.replace({ name: 'login', query: { redirect: route.fullPath } })
    }
  }
}

function showError(error: unknown) {
  handleExpired(error)
  ElMessage.error(errorMessage(error))
}

function signInReady(account: User) {
  setUser(account)
  staff.value = []
  roleList.value = []
  mobileMenuOpen.value = false
  preparedSection = null
  sessionReady = true
  openTabs.value = [{ key: 'dashboard', label: viewLabels.dashboard, closable: false }]
  if (can('user:view')) void loadRoles()
  void loadRegions().catch(showError)
}

async function loadRoles() {
  if (!can('user:view')) return
  try {
    roleList.value = await api<RoleSummary[]>('/roles')
  } catch (error) {
    handleExpired(error)
  }
}

onMounted(async () => {
  window.addEventListener('resize', handleResize)
  try {
    await router.isReady()
    const account = await loadUser()
    if (account) {
      signInReady(account)
      starting.value = false
      await syncRoute()
    } else if (route.name !== 'login') {
      await router.replace({ name: 'login', query: { redirect: route.fullPath } })
    }
  } catch (error) {
    if (error instanceof ApiError && error.status === 401) clearToken()
    else loginError.value = errorMessage(error)
  } finally {
    starting.value = false
  }
})

onBeforeUnmount(() => window.removeEventListener('resize', handleResize))

async function signIn() {
  if (loginBusy.value) return
  if (!login.value.username.trim() || !login.value.password) {
    loginError.value = '请输入用户名和密码'
    return
  }
  loginBusy.value = true
  loginError.value = ''
  try {
    const result = await api<LoginResult>('/auth/login', 'POST', {
      username: login.value.username.trim(),
      password: login.value.password,
    })
    const { token, ...account } = result
    setToken(token ?? null)
    if (remember.value) localStorage.setItem(REMEMBER_KEY, login.value.username.trim())
    else localStorage.removeItem(REMEMBER_KEY)
    login.value.password = ''
    signInReady(account)
    await router.replace(redirectTarget())
  } catch (error) {
    loginError.value = errorMessage(error)
  } finally {
    loginBusy.value = false
  }
}

async function signOut() {
  try {
    await api('/auth/logout', 'POST')
  } catch {
    // 本地登录态仍需清理
  } finally {
    clearToken()
    setUser(null)
    preparedSection = null
    sessionReady = false
    loginError.value = ''
    login.value.password = ''
    mobileMenuOpen.value = false
    openTabs.value = [{ key: 'dashboard', label: viewLabels.dashboard, closable: false }]
    void router.replace({ name: 'login' })
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
    clearToken()
    setUser(null)
    preparedSection = null
    sessionReady = false
    mobileMenuOpen.value = false
    openTabs.value = [{ key: 'dashboard', label: viewLabels.dashboard, closable: false }]
    loginError.value = '密码已修改，请重新登录'
    void router.replace({ name: 'login' })
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

function openRoleChange(row: RecordData) {
  if (!roleList.value.length) void loadRoles()
  roleChangeTarget.value = row
  roleChangeValue.value = String(row.role ?? '')
  roleChangeError.value = ''
  roleChangeOpen.value = true
}

async function saveRoleChange() {
  if (!roleChangeTarget.value || roleChangeBusy.value) return
  if (!roleChangeValue.value) {
    roleChangeError.value = '请选择角色'
    return
  }
  roleChangeBusy.value = true
  roleChangeError.value = ''
  try {
    await api('/users/' + roleChangeTarget.value.id + '/role', 'PUT', { role: roleChangeValue.value })
    roleChangeOpen.value = false
    ElMessage.success('角色已调整，对方需要重新登录')
    await loadList()
  } catch (error) {
    handleExpired(error)
    roleChangeError.value = errorMessage(error)
  } finally {
    roleChangeBusy.value = false
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

function addTab(key: ViewKey) {
  if (openTabs.value.some(tab => tab.key === key)) return
  openTabs.value.push({ key, label: viewLabels[key], closable: key !== 'dashboard' })
}

function selectView(key: string) {
  mobileMenuOpen.value = false
  const view = key as ViewKey
  if (SECTION_KEYS.includes(view as SectionKey)) {
    selectSection(view as SectionKey)
  } else if (view in viewLabels) {
    void router.push({ name: view })
  }
}

function closeTab(key: string) {
  const index = openTabs.value.findIndex(tab => tab.key === key)
  if (index < 0 || !openTabs.value[index].closable) return
  openTabs.value.splice(index, 1)
  if (currentKey.value !== key) return
  const next = openTabs.value[openTabs.value.length - 1]
  selectView(next ? next.key : 'dashboard')
}

function closeOtherTabs() {
  openTabs.value = openTabs.value.filter(tab => tab.key === 'dashboard' || tab.key === currentKey.value)
}

function closeAllTabs() {
  openTabs.value = openTabs.value.filter(tab => tab.key === 'dashboard')
  selectView('dashboard')
}

function refreshCurrent() {
  refreshKey.value++
  if (SECTION_KEYS.includes(routeName.value as SectionKey)) void loadList()
}

function toggleSidebar() {
  if (window.innerWidth <= 900) {
    mobileMenuOpen.value = !mobileMenuOpen.value
    return
  }
  collapsed.value = !collapsed.value
  localStorage.setItem(COLLAPSE_KEY, collapsed.value ? '1' : '0')
}

function handleResize() {
  if (window.innerWidth > 900) mobileMenuOpen.value = false
}

function selectSection(key: SectionKey, initialFilters: Record<string, Value> = {}) {
  listRequest++
  detailOpen.value = false
  rows.value = []
  total.value = 0
  filters.value = initialFilters
  page.value = 1
  if (key === 'users' && !roleList.value.length) void loadRoles()
  preparedSection = key
  if (routeName.value === key) {
    preparedSection = null
    addTab(key)
    void loadList()
  } else {
    void router.push({ name: key })
  }
}

function redirectTarget() {
  const redirect = route.query.redirect
  if (typeof redirect === 'string' && redirect.startsWith('/') && !redirect.startsWith('/login')) {
    return redirect
  }
  return '/dashboard'
}

async function syncRoute() {
  if (!user.value) return
  if (route.name === 'login') {
    await router.replace(redirectTarget())
    return
  }
  const name = routeName.value as ViewKey
  if (!(name in viewLabels)) {
    await router.replace({ name: 'dashboard' })
    return
  }
  addTab(name)
  if (!SECTION_KEYS.includes(name as SectionKey)) {
    preparedSection = null
    return
  }
  if (name === 'users' && !roleList.value.length) void loadRoles()
  if (preparedSection !== name) {
    filters.value = {}
    page.value = 1
    rows.value = []
    total.value = 0
  }
  preparedSection = null
  await loadList()
}

watch(() => route.fullPath, () => {
  if (sessionReady) void syncRoute()
})

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

function setRegionFilter(field: Field, value: Value) {
  if (field.kind === 'province' && filters.value[field.key] !== value) {
    filters.value.cityCode = null
  }
  filters.value[field.key] = value
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
  if (key === 'region') {
    const province = regionName(row.provinceCode, 'province')
    const city = regionName(row.cityCode, 'city', row.provinceCode)
    return [...new Set([province, city].filter(name => name !== '—'))].join(' / ') || '—'
  }
  if (key === 'provinceCode') return regionName(value, 'province')
  if (key === 'cityCode') return regionName(value, 'city', row.provinceCode)
  if (key === 'districtCode') return regionName(value, 'district', row.cityCode)
  if (key === 'investmentRange') return row.investmentMin == null && row.investmentMax == null
    ? '—' : money(row.investmentMin) + ' 至 ' + money(row.investmentMax)
  if (key === 'registeredCapital' || key === 'investmentMin' || key === 'investmentMax') return money(value)
  if (key === 'enterpriseId') return row.enterpriseName || value || '—'
  if (key === 'projectId') return row.projectName || value || '—'
  if (key === 'ownerId') return row.ownerUsername || staff.value.find(item => item.id === value)?.username || value || '—'
  if (key === 'projectStatus') return label(value, projectStatuses) ?? '—'
  if (key === 'leadStatus') return label(value, leadStages) ?? '—'
  if (key === 'role') return roleNameByCode.value.get(String(value)) ?? label(value, roleOptions) ?? '—'
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
    if (key === 'enterprises' || key === 'projects') await loadRegions()
    const current = row ? await api<RecordData>(sectionPath(row.id)) : null
    if (active.value !== key) return
    const defaults: Record<string, Value> = {}
    if (active.value === 'projects') defaults.projectStatus = 'ACTIVE'
    if (active.value === 'leads') defaults.leadStatus = 'NEW'
    editId.value = current?.id ?? null
    originalStage.value = current?.leadStatus ?? null
    originalRegions.value = {
      provinceCode: current?.provinceCode ?? null,
      cityCode: current?.cityCode ?? null,
      districtCode: current?.districtCode ?? null,
    }
    editor.value = Object.fromEntries(section.value.fields.map(field => {
      const value = current?.[field.key] ?? defaults[field.key] ?? null
      if (field.kind === 'province' || field.kind === 'city' || field.kind === 'district') {
        const parent = field.kind === 'city' ? current?.provinceCode : current?.cityCode
        return [field.key, regionValue(value, field.kind, parent)]
      }
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
    if (editId.value != null) {
      for (const field of editorFields.value) {
        if (field.kind !== 'province' && field.kind !== 'city' && field.kind !== 'district') continue
        const original = originalRegions.value[field.key]
        const parent = field.kind === 'city' ? originalRegions.value.provinceCode : originalRegions.value.cityCode
        if (original != null && regionCode(original, field.kind, parent) === regionCode(payload[field.key], field.kind, parent)) {
          payload[field.key] = original
        }
      }
    }
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

async function reviewLead(row: RecordData, approve: boolean) {
  const action = approve ? '通过' : '驳回'
  try {
    await ElMessageBox.confirm(`确定${action}这条待审核线索吗？`, '线索审核', {
      type: 'warning', confirmButtonText: action, cancelButtonText: '取消',
    })
  } catch {
    return
  }
  try {
    const updated = await api<RecordData>(`/leads/${row.id}/${approve ? 'approve' : 'reject'}`, 'POST')
    if (detail.value?.id === row.id) detail.value = updated
    ElMessage.success(`线索已${action}`)
    await loadList()
  } catch (error) {
    showError(error)
  }
}

async function openDetail(row: RecordData) {
  detailOpen.value = true
  detail.value = null
  detailLoading.value = true
  leadEvidence.value = []
  leadAnalysis.value = null
  followups.value = []
  followupRequest++
  followupPage.value = 1
  followupTotal.value = 0
  try {
    detail.value = await api<RecordData>(sectionPath(row.id))
    if (active.value === 'leads') {
      leadEvidence.value = await api<RecordData[]>(`/leads/${row.id}/evidence`)
      leadAnalysis.value = await api<Record<string, unknown>>(`/leads/${row.id}/analysis`)
      await loadFollowups(1)
    }
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
  <div v-if="starting" class="app-boot" aria-live="polite">
    <div class="boot-card">
      <span class="boot-mark">招</span>
      <p>正在连接招商管理平台…</p>
    </div>
  </div>

  <LoginView
    v-else-if="!user"
    v-model:username="login.username"
    v-model:password="login.password"
    v-model:remember="remember"
    :busy="loginBusy"
    :error="loginError"
    @submit="signIn"
  />

  <div v-else class="app-wrapper">
    <AppSidebar
      :items="menuItems"
      :collapsed="collapsed && !mobileMenuOpen"
      :mobile-open="mobileMenuOpen"
      :active="currentKey"
      @select="selectView"
    />
    <div v-if="mobileMenuOpen" class="sidebar-mask" @click="mobileMenuOpen = false" />

    <div class="main-container" :class="{ 'sidebar-collapsed': collapsed }">
      <AppNavbar
        :collapsed="collapsed"
        :current-label="currentLabel"
        :username="user.username"
        :role-label="roleLabels[user.role]"
        @toggle="toggleSidebar"
        @refresh="refreshCurrent"
        @password="openPassword"
        @logout="signOut"
        @dashboard="selectView('dashboard')"
      />
      <AppTagsView
        :tabs="openTabs"
        :active-key="currentKey"
        @select="selectView"
        @close="closeTab"
        @refresh="refreshCurrent"
        @close-others="closeOtherTabs"
        @close-all="closeAllTabs"
      />

      <main class="app-main">
        <Dashboard
          v-if="dashboardVisible"
          :key="'dashboard-' + refreshKey"
          :username="user.username"
          @navigate="navigateFromDashboard"
          @open-lead="openDashboardLead"
          @expired="handleExpired"
        />
        <DataManagement
          v-else-if="dataManagementVisible"
          :key="'data-' + refreshKey"
          @expired="handleExpired"
        />
        <CollectorPanel
          v-else-if="customView === 'collector'"
          :key="'collector-' + refreshKey"
          :can-manage="can('monitor:manage')"
          @expired="handleExpired"
        />
        <ObservationPanel
          v-else-if="customView === 'observations'"
          :key="'observations-' + refreshKey"
          @expired="handleExpired"
        />
        <RolePanel
          v-else-if="rolesVisible"
          :key="'roles-' + refreshKey"
          @expired="handleExpired"
        />
        <BusinessPanel
          v-else-if="customView === 'businesses'"
          :key="'businesses-' + refreshKey"
          :can-create="can('business:create')"
          :can-update="can('business:update')"
          :can-delete="can('business:delete')"
          :can-assign="canAssign"
          @expired="handleExpired"
        />
        <ContractPanel
          v-else-if="customView === 'contracts'"
          :key="'contracts-' + refreshKey"
          :can-create="can('contract:create')"
          :can-update="can('contract:update')"
          :can-delete="can('contract:delete')"
          :can-assign="canAssign"
          @expired="handleExpired"
        />
        <ReceivablePanel
          v-else-if="customView === 'receivables'"
          :key="'receivables-' + refreshKey"
          :can-create="can('receivable:create')"
          :can-update="can('receivable:update')"
          :can-delete="can('receivable:delete')"
          :can-assign="canAssign"
          @expired="handleExpired"
        />
        <StatisticsPanel
          v-else-if="customView === 'statistics'"
          :key="'statistics-' + refreshKey"
          @expired="handleExpired"
        />
        <BidRadarPanel
          v-else-if="customView === 'bidradar'"
          :key="'bidradar-' + refreshKey"
          :can-create-lead="can('lead:create')"
          :can-create-business="can('business:create')"
          :can-assign="canAssign"
          :can-use-qcc="user.role === 'ADMIN'"
          @expired="handleExpired"
        />
        <AgentPanel
          v-else-if="customView === 'agent'"
          :key="'agent-' + refreshKey"
          @expired="handleExpired"
        />
        <OpenClawPanel
          v-else-if="customView === 'openclaw'"
          :key="'openclaw-' + refreshKey"
          :username="user.username"
          @expired="handleExpired"
        />
        <template v-else>
        <div class="page-header">
          <div class="page-header-copy">
            <div class="page-header-title">
              <span class="page-header-glyph">{{ section.glyph }}</span>
              <h1>{{ section.label }}</h1>
            </div>
            <p>{{ section.summary }}</p>
          </div>
          <div v-if="canWrite" class="page-actions">
            <el-button v-if="active === 'enterprises' && can('enterprise:import')" @click="openImport">
              <el-icon><Upload /></el-icon>Excel 批量导入
            </el-button>
            <el-button type="primary" @click="openEditor()">
              <el-icon><Plus /></el-icon>新建{{ section.singular }}
            </el-button>
          </div>
        </div>

        <section class="list-card" :aria-label="section.label + '列表'">
          <el-form v-if="section.filters" class="filters" inline @submit.prevent="search">
            <el-form-item v-for="filter in section.filters" :key="filter.key">
              <RegionSelect v-if="filter.kind === 'province' || filter.kind === 'city' || filter.kind === 'district'"
                :model-value="filters[filter.key]" :level="filter.kind"
                :parent="filter.kind === 'city' ? filters.provinceCode : filters.cityCode"
                :placeholder="filter.label" @update:model-value="value => setRegionFilter(filter, value)" />
              <el-select v-else-if="filter.options" v-model="filters[filter.key]" :placeholder="filter.label" clearable :value-on-clear="null">
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
            <el-form-item class="filter-actions">
              <el-button type="primary" native-type="submit">
                <el-icon><Search /></el-icon>查询
              </el-button>
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
                <el-button v-if="active === 'leads' && row.leadStatus === 'PENDING_REVIEW' && can('lead:update')" link type="success" @click="reviewLead(row, true)">通过</el-button>
                <el-button v-if="active === 'leads' && row.leadStatus === 'PENDING_REVIEW' && can('lead:update')" link type="danger" @click="reviewLead(row, false)">驳回</el-button>
                <el-button v-if="canWrite && !(active === 'leads' && ['PENDING_REVIEW', 'REJECTED'].includes(String(row.leadStatus)))" link type="primary" @click="openEditor(row)">编辑</el-button>
                <el-button v-if="canDelete" link type="danger" @click="removeRecord(row)">删除</el-button>
              </template>
            </el-table-column>
            <el-table-column v-else label="操作" min-width="230" fixed="right">
              <template #default="{ row }">
                <template v-if="row.id !== user.id">
                  <el-button link type="primary" @click="openRoleChange(row)">调整角色</el-button>
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
          <Customer360Panel v-if="active === 'enterprises'" :key="'c360-' + detail.id"
            :enterprise-id="detail.id" @expired="handleExpired" />
          <el-alert v-if="detail.sourceUrl" type="info" :closable="false" show-icon class="source-alert">
            <template #title>
              来源：<a :href="String(detail.sourceUrl)" target="_blank" rel="noopener noreferrer">
                {{ detail.sourceTitle || detail.sourceUrl }}
              </a>
            </template>
          </el-alert>
          <dl class="detail-grid">
            <div v-for="field in section.fields" :key="field.key" :class="{ wide: field.kind === 'text' }">
              <dt>{{ field.label }}</dt><dd>{{ display(detail, field.key) }}</dd>
            </div>
            <div><dt>创建时间</dt><dd>{{ display(detail, 'createdAt') }}</dd></div>
            <div><dt>更新时间</dt><dd>{{ display(detail, 'updatedAt') }}</dd></div>
          </dl>
          <TagPanel v-if="active === 'enterprises'" :key="'tags-' + detail.id"
            :enterprise-id="detail.id" :can-edit="can('enterprise:tag:write')" @expired="handleExpired" />
          <ContactPanel v-if="can('contact:view') && (active === 'enterprises' || active === 'leads')"
            :key="String(active === 'enterprises' ? detail.id : detail.enterpriseId)"
            :enterprise-id="Number(active === 'enterprises' ? detail.id : detail.enterpriseId)"
            :can-delete="can('contact:delete')" @expired="handleExpired" />
          <section v-if="active === 'leads' && leadAnalysis?.available" class="followups">
            <div class="followup-heading"><h3>招商分析</h3></div>
            <dl class="detail-grid">
              <div><dt>优先级</dt><dd>{{ leadAnalysis.totalScore ?? '—' }} / 100</dd></div>
              <div><dt>楼宇匹配</dt><dd>{{ leadAnalysis.fitScore ?? '—' }} / 100</dd></div>
              <div><dt>租赁意向</dt><dd>{{ leadAnalysis.intentScore ?? '—' }} / 100</dd></div>
              <div><dt>数据可信</dt><dd>{{ leadAnalysis.confidenceScore ?? '—' }} / 100</dd></div>
              <div><dt>统一社会信用代码</dt><dd>{{ leadAnalysis.uscc || '未知' }}</dd></div>
              <div><dt>经营状态</dt><dd>{{ leadAnalysis.businessStatus || '待核实' }}</dd></div>
              <div><dt>行业</dt><dd>{{ leadAnalysis.industryName || '未知' }}</dd></div>
              <div><dt>注册地址</dt><dd>{{ leadAnalysis.registeredAddress || '未知' }}</dd></div>
            </dl>
            <ul class="score-reasons"><li v-for="(reason, index) in leadReasons" :key="index">{{ reason }}</li></ul>
          </section>
          <section v-if="active === 'leads' && leadEvidence.length" class="followups">
            <div class="followup-heading"><h3>线索证据</h3></div>
            <article v-for="evidence in leadEvidence" :key="evidence.id" class="followup-record">
              <header><strong>{{ evidence.title }}</strong><span>{{ evidence.source || '来源待核实' }} · {{ evidence.publishedAt || '发布时间未知' }}</span></header>
              <p>{{ evidence.summary || '无摘要' }}</p>
              <small>采集时间：{{ evidence.fetchedAt || '未知' }}；状态：{{ evidence.validityStatus || '未知' }}</small>
            </article>
          </section>
          <section v-if="active === 'leads'" class="followups" aria-labelledby="followup-heading">
            <div class="followup-heading">
              <h3 id="followup-heading">跟进记录</h3>
              <el-button v-if="can('lead:update') && !['PENDING_REVIEW', 'REJECTED'].includes(String(detail.leadStatus))" size="small" type="primary" @click="openFollowup">新增跟进</el-button>
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
            :enterprise-id="detail.id" :verification-status="detail.verificationStatus"
            :verification-reason="detail.verificationReason" :verification-at="detail.verificationAt" @expired="handleExpired" />
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

    <el-dialog v-model="roleChangeOpen" title="调整账号角色" width="min(480px, 94vw)"
      :close-on-click-modal="false" :close-on-press-escape="!roleChangeBusy" :show-close="!roleChangeBusy">
      <el-form label-position="top" @submit.prevent="saveRoleChange">
        <p>为 {{ roleChangeTarget?.username }} 调整角色，保存后对方需要重新登录。</p>
        <el-form-item label="角色" required>
          <el-select v-model="roleChangeValue" placeholder="请选择角色" style="width: 100%">
            <el-option v-for="role in roleChoices" :key="role.value" :value="role.value"
              :label="role.label" :disabled="role.disabled" />
          </el-select>
        </el-form-item>
        <el-alert v-if="roleChangeError" :title="roleChangeError" type="error" :closable="false" show-icon />
        <div class="dialog-actions">
          <el-button :disabled="roleChangeBusy" @click="roleChangeOpen = false">取消</el-button>
          <el-button type="primary" native-type="submit" :loading="roleChangeBusy">保存角色</el-button>
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
        <code>企业名称 | 统一社会信用代码 | 省份 | 城市 | 区县 | 行业代码 | 经营状态 | 注册资本 | 成立日期</code>
        <p>省市区可填写名称，例如江苏省、南京市、玄武区。信用代码按文本填写，注册资本以元填写，成立日期使用 Excel 日期或 YYYY-MM-DD。重复或疑似重复的记录会显示对应行号。</p>
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
