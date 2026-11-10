<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { api, ApiError, query } from './api'
import type { Page, RecordData } from './api'
import { sections } from './sections'
import { regionName } from './regions'
import type { Value } from './api'
import type { RegionLevel } from './regions'

const props = defineProps<{ enterpriseId: number; verificationStatus?: Value; verificationReason?: Value; verificationAt?: Value }>()
const emit = defineEmits<{ expired: [error: unknown] }>()
const rows = ref<RecordData[]>([])
const total = ref(0)
const page = ref(1)
const loading = ref(false)
const error = ref('')
const verificationRows = ref<RecordData[]>([])
const verificationLoading = ref(false)
const verificationError = ref('')

function verificationLabel(status: Value | undefined) {
  const labels: Record<string, string> = {
    VERIFIED: '有效核验', PENDING: '待核验', INACTIVE: '登记状态异常', NOT_FOUND: '来源未找到', MISMATCH: '主体不一致',
  }
  return labels[String(status || 'PENDING')] || '待核验'
}

async function loadVerifications() {
  verificationLoading.value = true
  try {
    verificationRows.value = await api<RecordData[]>(`/enterprises/${props.enterpriseId}/verifications`)
  } catch (reason) {
    if (reason instanceof ApiError && reason.status === 401) emit('expired', reason)
    verificationError.value = reason instanceof Error ? reason.message : '无法加载主体核验记录'
  } finally {
    verificationLoading.value = false
  }
}

function fieldLabel(value: unknown) {
  if (typeof value !== 'string') return '字段'
  if (value === 'name_normalized') return '标准化名称'
  const camel = value.replace(/_([a-z])/g, (_, character: string) => character.toUpperCase())
  return sections.enterprises.fields.find(field => field.key === camel)?.label ?? value
}

function historyValue(field: Value | undefined, value: Value | undefined) {
  if (value == null || value === '') return '空'
  const levels: Record<string, RegionLevel> = {
    province_code: 'province', city_code: 'city', district_code: 'district',
  }
  const level = levels[String(field)]
  return level ? regionName(value, level) : value
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

onMounted(() => { void load(); void loadVerifications() })
</script>

<template>
  <section class="followups" aria-labelledby="verification-heading">
    <div class="followup-heading"><h3 id="verification-heading">企业主体核验</h3></div>
    <p>当前状态：<strong>{{ verificationLabel(verificationStatus) }}</strong></p>
    <small>{{ verificationReason || '暂无有效的授权核验结果' }}<template v-if="verificationAt"> · 最近核验 {{ String(verificationAt).replace('T', ' ').slice(0, 19) }}</template></small>
    <el-alert v-if="verificationError" :title="verificationError" type="error" :closable="false" show-icon class="contact-error" />
    <div v-loading="verificationLoading">
      <el-empty v-if="!verificationRows.length && !verificationLoading && !verificationError" description="暂无核验记录" :image-size="55" />
      <article v-for="row in verificationRows" :key="row.id" class="followup-record">
        <header>
          <strong>{{ row.isDemo ? '演示记录' : '企查查 2001' }} · 当时结果：{{ verificationLabel(row.resultStatus) }}</strong>
          <span>{{ String(row.verifiedAt || '').replace('T', ' ').slice(0, 19) }}</span>
        </header>
        <small>{{ row.reason || '原因待核实' }}</small>
        <small v-if="row.verifiedName">主体 · {{ row.verifiedName }}</small>
        <small v-if="row.businessStatus">登记状态 · {{ row.businessStatus }}</small>
      </article>
    </div>
  </section>
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
        <small>原值 · {{ historyValue(row.fieldName, row.oldValue) }}</small>
        <small>新值 · {{ historyValue(row.fieldName, row.newValue) }}</small>
        <small>来源 · {{ row.sourceName || '—' }}</small>
      </article>
      <el-pagination v-if="total > 10" small :current-page="page" :total="total"
        :page-size="10" layout="prev, pager, next" @current-change="load" />
    </div>
  </section>
</template>
