<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, ApiError, query } from './api'
import type { Page } from './api'

type Contact = {
  id: number
  enterpriseId: number
  name: string
  position: string | null
  phone: string | null
  email: string | null
}

const props = defineProps<{ enterpriseId: number; canDelete: boolean }>()
const emit = defineEmits<{ expired: [error: unknown] }>()
const contacts = ref<Contact[]>([])
const page = ref(1)
const total = ref(0)
const loading = ref(false)
const error = ref('')
const dialogOpen = ref(false)
const saving = ref(false)
const formError = ref('')
const editingId = ref<number | null>(null)
const draft = ref({ name: '', position: '', phone: '', email: '' })
let request = 0

function errorMessage(reason: unknown) {
  if (reason instanceof ApiError && reason.status === 401) emit('expired', reason)
  if (reason instanceof ApiError && reason.status === 404) return '当前账号无法访问该企业的联系人'
  return reason instanceof Error ? reason.message : '操作失败，请重试'
}

async function load(next = 1) {
  const current = ++request
  page.value = next
  loading.value = true
  error.value = ''
  try {
    const result = await api<Page<Contact>>(
      `/enterprises/${props.enterpriseId}/contacts` + query({ page: next, size: 10 }),
    )
    if (current !== request) return
    contacts.value = result.items
    total.value = result.total
  } catch (reason) {
    if (current !== request) return
    contacts.value = []
    error.value = errorMessage(reason)
  } finally {
    if (current === request) loading.value = false
  }
}

function edit(contact?: Contact) {
  editingId.value = contact?.id ?? null
  draft.value = {
    name: contact?.name ?? '',
    position: contact?.position ?? '',
    phone: contact?.phone ?? '',
    email: contact?.email ?? '',
  }
  formError.value = ''
  dialogOpen.value = true
}

async function save() {
  if (saving.value) return
  if (!draft.value.name.trim()) {
    formError.value = '请填写联系人姓名'
    return
  }
  saving.value = true
  formError.value = ''
  const id = editingId.value
  try {
    await api(`/enterprises/${props.enterpriseId}/contacts${id === null ? '' : '/' + id}`,
      id === null ? 'POST' : 'PUT', draft.value)
    dialogOpen.value = false
    ElMessage.success('联系人已保存')
    await load(id === null ? 1 : page.value)
  } catch (reason) {
    formError.value = errorMessage(reason)
  } finally {
    saving.value = false
  }
}

async function remove(contact: Contact) {
  try {
    await ElMessageBox.confirm('确定删除该联系人吗？', '删除联系人', {
      type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消',
    })
  } catch {
    return
  }
  try {
    await api(`/enterprises/${props.enterpriseId}/contacts/${contact.id}`, 'DELETE')
    ElMessage.success('联系人已删除')
    await load(contacts.value.length === 1 && page.value > 1 ? page.value - 1 : page.value)
  } catch (reason) {
    ElMessage.error(errorMessage(reason))
  }
}

onMounted(() => void load())
watch(() => props.enterpriseId, () => void load())
</script>

<template>
  <section class="followups" aria-labelledby="contacts-heading">
    <div class="followup-heading">
      <h3 id="contacts-heading">企业联系人</h3>
      <el-button v-if="!error" size="small" @click="edit()">新增联系人</el-button>
    </div>
    <el-alert v-if="error" :title="error" type="warning" :closable="false" show-icon class="contact-error" />
    <div v-loading="loading">
      <el-empty v-if="!contacts.length && !loading && !error" description="暂无联系人" :image-size="55" />
      <article v-for="contact in contacts" :key="contact.id" class="contact-record">
        <div>
          <strong>{{ contact.name }}</strong>
          <span v-if="contact.position"> · {{ contact.position }}</span>
          <small v-if="contact.phone">电话 · {{ contact.phone }}</small>
          <small v-if="contact.email">邮箱 · {{ contact.email }}</small>
        </div>
        <div>
          <el-button link type="primary" @click="edit(contact)">编辑</el-button>
          <el-button v-if="canDelete" link type="danger" @click="remove(contact)">删除</el-button>
        </div>
      </article>
      <el-pagination v-if="total > 10" small :current-page="page" :total="total"
        :page-size="10" layout="prev, pager, next" @current-change="load" />
    </div>
    <el-dialog v-model="dialogOpen" :title="editingId === null ? '新增联系人' : '编辑联系人'"
      width="min(560px, 94vw)" append-to-body :close-on-click-modal="false"
      :close-on-press-escape="!saving" :show-close="!saving">
      <el-form label-position="top" @submit.prevent="save">
        <div class="form-grid">
          <el-form-item label="姓名" required><el-input v-model="draft.name" maxlength="100" /></el-form-item>
          <el-form-item label="职位"><el-input v-model="draft.position" maxlength="100" /></el-form-item>
          <el-form-item label="电话"><el-input v-model="draft.phone" maxlength="60" /></el-form-item>
          <el-form-item label="邮箱"><el-input v-model="draft.email" maxlength="254" /></el-form-item>
        </div>
        <el-alert v-if="formError" :title="formError" type="error" :closable="false" show-icon />
        <div class="dialog-actions">
          <el-button :disabled="saving" @click="dialogOpen = false">取消</el-button>
          <el-button type="primary" native-type="submit" :loading="saving">保存联系人</el-button>
        </div>
      </el-form>
    </el-dialog>
  </section>
</template>
