<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { api, ApiError } from './api'

type Role = { id: number; code: string; name: string; builtIn: boolean; enabled: boolean; userCount: number }
type MenuOption = {
  id: number
  parentId: number
  menuType: 'DIR' | 'MENU' | 'BUTTON'
  menuKey: string | null
  menuGroup: string | null
  label: string
  permissionCode: string | null
  sort: number
  visible: boolean
  enabled: boolean
}
type RoleDetail = {
  id: number
  code: string
  name: string
  builtIn: boolean
  enabled: boolean
  editable: boolean
  menuIds: number[]
  dataScopes: Record<string, string>
}

const emit = defineEmits<{ expired: [error: unknown] }>()

const roles = ref<Role[]>([])
const menus = ref<MenuOption[]>([])
const loading = ref(false)
const error = ref('')
const dialogOpen = ref(false)
const saving = ref(false)
const formError = ref('')
const detail = ref<RoleDetail | null>(null)
const selectedMenus = ref<number[]>([])
const scopeResources = ['lead', 'project', 'contact', 'business', 'contract', 'receivable']
const scopes = ref<Record<string, string>>({
  lead: 'ALL', project: 'ALL', contact: 'ALL', business: 'ALL', contract: 'ALL', receivable: 'ALL',
})

const scopeOptions: Record<string, { value: string; label: string }[]> = {
  lead: [
    { value: 'ALL', label: '全部线索' },
    { value: 'SELF', label: '仅本人负责的线索' },
  ],
  project: [
    { value: 'ALL', label: '全部项目' },
    { value: 'SELF', label: '仅本人负责的项目' },
  ],
  contact: [
    { value: 'ALL', label: '全部联系人' },
    { value: 'OWN_LEADS', label: '本人线索关联企业的联系人' },
  ],
  business: [
    { value: 'ALL', label: '全部商机' },
    { value: 'SELF', label: '仅本人负责的商机' },
  ],
  contract: [
    { value: 'ALL', label: '全部合同' },
    { value: 'SELF', label: '仅本人负责的合同' },
  ],
  receivable: [
    { value: 'ALL', label: '全部回款' },
    { value: 'SELF', label: '仅本人负责的回款' },
  ],
}

const resourceLabels: Record<string, string> = {
  lead: '招商线索',
  project: '招商项目',
  contact: '企业联系人',
  business: '商机',
  contract: '合同',
  receivable: '回款',
}

const menuGroups = computed(() => {
  const groups = new Map<string, MenuOption[]>()
  for (const menu of menus.value) {
    if (menu.menuType === 'DIR') continue
    const group = menu.menuGroup || '其他'
    const list = groups.get(group) ?? []
    list.push(menu)
    groups.set(group, list)
  }
  return [...groups.entries()].map(([name, items]) => ({
    name,
    items: items.sort((a, b) => a.sort - b.sort),
  }))
})

function message(reason: unknown) {
  if (reason instanceof ApiError && reason.status === 401) emit('expired', reason)
  if (reason instanceof ApiError && reason.status === 403) return '当前账号没有权限配置角色'
  return reason instanceof Error ? reason.message : '操作失败，请重试'
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    const [roleList, menuList] = await Promise.all([
      api<Role[]>('/roles'),
      api<MenuOption[]>('/roles/menu-options'),
    ])
    roles.value = roleList
    menus.value = menuList
  } catch (reason) {
    error.value = message(reason)
  } finally {
    loading.value = false
  }
}

async function openRole(role: Role) {
  try {
    const result = await api<RoleDetail>('/roles/' + role.id)
    detail.value = result
    selectedMenus.value = [...result.menuIds]
    scopes.value = {
      lead: result.dataScopes.lead ?? 'ALL',
      project: result.dataScopes.project ?? 'ALL',
      contact: result.dataScopes.contact ?? 'ALL',
      business: result.dataScopes.business ?? 'ALL',
      contract: result.dataScopes.contract ?? 'ALL',
      receivable: result.dataScopes.receivable ?? 'ALL',
    }
    formError.value = ''
    dialogOpen.value = true
  } catch (reason) {
    error.value = message(reason)
  }
}

async function save() {
  if (!detail.value || saving.value || !detail.value.editable) return
  saving.value = true
  formError.value = ''
  try {
    await api<RoleDetail>('/roles/' + detail.value.id + '/permissions', 'PUT', {
      menuIds: selectedMenus.value,
    })
    await api<RoleDetail>('/roles/' + detail.value.id + '/data-scopes', 'PUT', {
      scopes: Object.entries(scopes.value).map(([resource, scope]) => ({ resource, scope })),
    })
    dialogOpen.value = false
    ElMessage.success('角色权限已保存，权限将在对方下一次请求时生效')
    await load()
  } catch (reason) {
    formError.value = message(reason)
  } finally {
    saving.value = false
  }
}

function menuTypeLabel(menu: MenuOption) {
  return menu.menuType === 'MENU' ? '页面' : '操作'
}

onMounted(() => void load())
</script>

<template>
  <div class="page-header">
    <div class="page-header-copy">
      <div class="page-header-title">
        <span class="page-header-glyph">权</span>
        <h1>角色权限</h1>
      </div>
      <p>配置各角色的功能权限与数据范围，权限变更在下一次请求时生效。</p>
    </div>
    <div class="page-actions">
      <el-button :loading="loading" @click="load">刷新</el-button>
    </div>
  </div>

  <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon class="role-error" />

  <section class="list-card">
    <el-table v-loading="loading" :data="roles" empty-text="暂无角色">
      <el-table-column prop="name" label="角色名称" min-width="150" />
      <el-table-column prop="code" label="角色编码" min-width="190" />
      <el-table-column label="账号数" min-width="90">
        <template #default="{ row }">{{ row.userCount ?? 0 }}</template>
      </el-table-column>
      <el-table-column label="类型" min-width="110">
        <template #default="{ row }">
          <el-tag v-if="row.code === 'ADMIN'" type="warning" effect="plain">内置保护</el-tag>
          <el-tag v-else type="info" effect="plain">可配置</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" min-width="120" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openRole(row)">
            {{ row.code === 'ADMIN' ? '查看权限' : '配置权限' }}
          </el-button>
        </template>
      </el-table-column>
    </el-table>
  </section>

  <el-dialog v-model="dialogOpen" :title="'角色权限 · ' + (detail?.name ?? '')" width="min(760px, 94vw)"
    top="6vh" :close-on-click-modal="false" :close-on-press-escape="!saving" :show-close="!saving">
    <div v-if="detail" class="role-detail">
      <el-alert v-if="!detail.editable" title="管理员角色为内置保护角色，权限不可修改。" type="info"
        :closable="false" show-icon class="role-protected" />

      <h3>功能权限</h3>
      <div v-for="group in menuGroups" :key="group.name" class="permission-group">
        <h4>{{ group.name }}</h4>
        <el-checkbox-group v-model="selectedMenus" :disabled="!detail.editable" class="permission-options">
          <el-checkbox v-for="menu in group.items" :key="menu.id" :value="menu.id">
            <span class="permission-label">{{ menu.label }}</span>
            <el-tag size="small" effect="plain" class="permission-type">{{ menuTypeLabel(menu) }}</el-tag>
            <code v-if="menu.permissionCode" class="permission-code">{{ menu.permissionCode }}</code>
          </el-checkbox>
        </el-checkbox-group>
      </div>

      <h3>数据范围</h3>
      <div class="scope-grid">
        <el-form-item v-for="resource in scopeResources" :key="resource" :label="resourceLabels[resource]">
          <el-select v-model="scopes[resource]" :disabled="!detail.editable" style="width: 100%">
            <el-option v-for="option in scopeOptions[resource]" :key="option.value"
              :value="option.value" :label="option.label" />
          </el-select>
        </el-form-item>
      </div>

      <el-alert v-if="formError" :title="formError" type="error" :closable="false" show-icon />
    </div>
    <template #footer>
      <div class="dialog-actions">
        <el-button :disabled="saving" @click="dialogOpen = false">关闭</el-button>
        <el-button v-if="detail?.editable" type="primary" :loading="saving" @click="save">保存权限</el-button>
      </div>
    </template>
  </el-dialog>
</template>

<style scoped>
.role-error {
  margin-bottom: 16px;
}

.role-detail h3 {
  margin: 4px 0 12px;
  color: #1f2d3d;
  font-size: 15px;
  font-weight: 600;
}

.role-detail h3:not(:first-of-type) {
  margin-top: 22px;
}

.role-protected {
  margin-bottom: 16px;
}

.permission-group {
  margin-bottom: 14px;
  padding: 14px 16px;
  border: 1px solid #ebeef5;
  border-radius: 8px;
  background: #fafbfc;
}

.permission-group h4 {
  margin: 0 0 10px;
  color: #5a6a7a;
  font-size: 12px;
  font-weight: 600;
  letter-spacing: .06em;
}

.permission-options {
  display: flex;
  flex-wrap: wrap;
  gap: 6px 18px;
}

.permission-options :deep(.el-checkbox) {
  height: 28px;
  margin-right: 0;
}

.permission-label {
  color: #303133;
  font-size: 13px;
}

.permission-type {
  margin-left: 6px;
}

.permission-code {
  margin-left: 6px;
  padding: 1px 5px;
  border-radius: 3px;
  background: #f0f2f5;
  color: #909399;
  font-size: 10px;
}

.scope-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
}

@media (max-width: 700px) {
  .scope-grid {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
