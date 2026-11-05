<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { api, ApiError, query } from './api'
import type { Page } from './api'

type Tag = { id: number; tagName: string; tagType: string }
const props = defineProps<{ enterpriseId: number; canEdit: boolean }>()
const emit = defineEmits<{ expired: [error: unknown] }>()
const tags = ref<Tag[]>([])
const options = ref<Tag[]>([])
const selected = ref<number[]>([])
const editing = ref(false)
const saving = ref(false)
const loading = ref(false)
const error = ref('')
const newOpen = ref(false)
const newTag = ref({ tagName: '', tagType: '自定义' })
const newError = ref('')
let request = 0

function message(reason: unknown) {
  if (reason instanceof ApiError && reason.status === 401) emit('expired', reason)
  return reason instanceof Error ? reason.message : '操作失败，请重试'
}

async function load() {
  try {
    tags.value = await api<Tag[]>(`/enterprises/${props.enterpriseId}/tags`)
    selected.value = tags.value.map(tag => tag.id)
    options.value = tags.value
    error.value = ''
  } catch (reason) {
    error.value = message(reason)
  }
}

async function search(keyword: string) {
  const current = ++request
  loading.value = true
  try {
    const result = await api<Page<Tag>>('/tags' + query({ keyword, page: 1, size: 30 }))
    if (current !== request) return
    const retained = options.value.filter(tag => selected.value.includes(tag.id))
    options.value = [...retained, ...result.items.filter(tag => !retained.some(item => item.id === tag.id))]
  } catch (reason) {
    if (current === request) error.value = message(reason)
  } finally {
    if (current === request) loading.value = false
  }
}

function startEditing() {
  selected.value = tags.value.map(tag => tag.id)
  editing.value = true
  void search('')
}

async function save() {
  if (saving.value) return
  saving.value = true
  error.value = ''
  try {
    tags.value = await api<Tag[]>(`/enterprises/${props.enterpriseId}/tags`, 'PUT', { tagIds: selected.value })
    editing.value = false
    ElMessage.success('企业标签已保存')
  } catch (reason) {
    error.value = message(reason)
  } finally {
    saving.value = false
  }
}

async function create() {
  if (saving.value) return
  if (!newTag.value.tagName.trim() || !newTag.value.tagType.trim()) {
    newError.value = '请填写标签名称和类型'
    return
  }
  saving.value = true
  newError.value = ''
  try {
    const created = await api<Tag>('/tags', 'POST', newTag.value)
    options.value = [created, ...options.value.filter(tag => tag.id !== created.id)]
    if (!selected.value.includes(created.id)) selected.value.push(created.id)
    newOpen.value = false
    newTag.value = { tagName: '', tagType: '自定义' }
  } catch (reason) {
    newError.value = message(reason)
  } finally {
    saving.value = false
  }
}

onMounted(() => void load())
</script>

<template>
  <section class="followups" aria-labelledby="tags-heading">
    <div class="followup-heading">
      <h3 id="tags-heading">企业标签</h3>
      <el-button v-if="canEdit && !editing" size="small" @click="startEditing">编辑标签</el-button>
    </div>
    <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon class="contact-error" />
    <div v-if="!editing" class="tag-list">
      <el-tag v-for="tag in tags" :key="tag.id" effect="plain">{{ tag.tagName }} · {{ tag.tagType }}</el-tag>
      <span v-if="!tags.length && !error" class="muted">暂无标签</span>
    </div>
    <div v-else class="tag-editor">
      <el-select v-model="selected" multiple filterable remote reserve-keyword :remote-method="search"
        :loading="loading" placeholder="搜索标签" style="width: 100%"
        @visible-change="(visible: boolean) => visible && search('')">
        <el-option v-for="tag in options" :key="tag.id" :value="tag.id"
          :label="tag.tagName + ' · ' + tag.tagType" />
      </el-select>
      <div class="tag-actions">
        <el-button size="small" :disabled="saving" @click="newOpen = true">新建标签</el-button>
        <el-button size="small" :disabled="saving" @click="editing = false">取消</el-button>
        <el-button size="small" type="primary" :loading="saving" @click="save">保存标签</el-button>
      </div>
    </div>
    <el-dialog v-model="newOpen" title="新建标签" width="min(480px, 94vw)" append-to-body
      :close-on-click-modal="false" :close-on-press-escape="!saving" :show-close="!saving">
      <el-form label-position="top" @submit.prevent="create">
        <el-form-item label="标签名称" required><el-input v-model="newTag.tagName" maxlength="100" /></el-form-item>
        <el-form-item label="标签类型" required><el-input v-model="newTag.tagType" maxlength="50" /></el-form-item>
        <el-alert v-if="newError" :title="newError" type="error" :closable="false" show-icon />
        <div class="dialog-actions">
          <el-button :disabled="saving" @click="newOpen = false">取消</el-button>
          <el-button type="primary" native-type="submit" :loading="saving">创建并选中</el-button>
        </div>
      </el-form>
    </el-dialog>
  </section>
</template>
