<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { api, ApiError, query } from './api'
import type { Page, RecordData } from './api'
import { sections } from './sections'

const props = defineProps<{ enterpriseId: number }>()
const emit = defineEmits<{ expired: [error: unknown] }>()
const rows = ref<RecordData[]>([])
const total = ref(0)
const page = ref(1)
const loading = ref(false)
const error = ref('')

function fieldLabel(value: unknown) {
  if (typeof value !== 'string') return '字段'
  if (value === 'name_normalized') return '标准化名称'
  const camel = value.replace(/_([a-z])/g, (_, character: string) => character.toUpperCase())
  return sections.enterprises.fields.find(field => field.key === camel)?.label ?? value
}

async function load(next = 1) {
  page.value = next
  loading.value = true
  error.value = ''
  try {
    const result = await api<Page<RecordData>>(
      `/enterprises/${props.enterpriseId}/history` + query({ page: next, size: 10 }),
    )
    rows.value = result.items
    total.value = result.total
  } catch (reason) {
    if (reason instanceof ApiError && reason.status === 401) emit('expired', reason)
    error.value = reason instanceof Error ? reason.message : '无法加载企业变更记录'
  } finally {
    loading.value = false
  }
}

onMounted(() => void load())
</script>

<template>
  <section class="followups" aria-labelledby="history-heading">
    <div class="followup-heading"><h3 id="history-heading">企业字段变更</h3></div>
    <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon class="contact-error" />
    <div v-loading="loading">
      <el-empty v-if="!rows.length && !loading && !error" description="暂无字段变更记录" :image-size="55" />
      <article v-for="row in rows" :key="row.id" class="followup-record">
        <header>
          <strong>{{ fieldLabel(row.fieldName) }}</strong>
          <span>{{ row.changedByUsername || '—' }} · {{ String(row.createdAt || '').replace('T', ' ').slice(0, 19) }}</span>
        </header>
        <small>原值 · {{ row.oldValue || '空' }}</small>
        <small>新值 · {{ row.newValue || '空' }}</small>
        <small>来源 · {{ row.sourceName || '—' }}</small>
      </article>
      <el-pagination v-if="total > 10" small :current-page="page" :total="total"
        :page-size="10" layout="prev, pager, next" @current-change="load" />
    </div>
  </section>
</template>
