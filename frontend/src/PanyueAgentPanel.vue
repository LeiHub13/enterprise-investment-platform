<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { Refresh } from '@element-plus/icons-vue'
import { api, ApiError } from './api'

const emit = defineEmits<{ expired: [error: unknown] }>()
const router = useRouter()

type Probe = { name: string; url: string; up: boolean; status: number; message: string }
type Status = { businessApi: Probe; businessReady: Probe; agentApi: Probe; checkedAt: string }

const status = ref<Status | null>(null)
const loading = ref(false)
const errorText = ref('')

const labels: Record<string, string> = {
  'business-api': 'Spring Boot 业务 API',
  'business-api-ready': '数据库就绪检查',
  'agent-api': 'FastAPI Agent API',
}

const probes = computed(() => status.value
  ? [status.value.businessApi, status.value.businessReady, status.value.agentApi]
  : [])

function when(value: unknown) {
  if (!value) return '—'
  return String(value).replace('T', ' ').slice(0, 19)
}

async function load() {
  loading.value = true
  errorText.value = ''
  try {
    status.value = await api<Status>('/panyue-agent/status')
  } catch (error) {
    if (error instanceof ApiError && error.status === 401) emit('expired', error)
    errorText.value = error instanceof Error ? error.message : '无法检查泛悦 Agent 服务状态'
  } finally {
    loading.value = false
  }
}

function openAgent() {
  void router.push({
    name: 'agent',
    query: {
      projectId: '4',
      project: '泛悦城市广场',
      prompt: '帮我寻找南京鼓楼区适合入驻泛悦城市广场 T2 写字楼的科技类企业，办公面积 100～300㎡，并给出匹配评分和跟进建议。',
    },
  })
}

onMounted(load)
</script>

<template>
  <div class="page-header">
    <div class="page-header-copy">
      <div class="page-header-title">
        <span class="page-header-glyph">泛</span>
        <h1>泛悦Agent</h1>
      </div>
      <p>泛悦城市广场独立招商 Agent 项目的服务状态；本页只读，不承载主平台业务数据。</p>
    </div>
    <div class="page-actions">
      <el-tag type="info" effect="plain">独立项目</el-tag>
      <el-button :loading="loading" @click="load">
        <el-icon><Refresh /></el-icon>刷新
      </el-button>
    </div>
  </div>

  <el-alert
    title="panyue-agent 目前是独立项目骨架：只有健康检查接口，企业、线索、审核、跟进和 Agent 业务接口尚未实现。"
    type="warning" :closable="false" show-icon class="panyue-alert" />
  <el-alert v-if="errorText" :title="errorText" type="error" :closable="false" show-icon class="panyue-alert" />

  <div v-loading="loading" class="panyue-stats">
    <article v-for="probe in probes" :key="probe.name" class="panyue-stat" :class="{ offline: !probe.up }">
      <small>{{ labels[probe.name] ?? probe.name }}</small>
      <strong>{{ probe.up ? '在线' : '离线' }}</strong>
      <el-tag size="small" :type="probe.up ? 'success' : 'danger'" effect="plain">
        {{ probe.up ? 'UP' : probe.status ? 'HTTP ' + probe.status : 'DOWN' }}
      </el-tag>
      <em>{{ probe.url }}</em>
      <span>{{ probe.message }}</span>
    </article>
    <article class="panyue-stat">
      <small>检查时间</small>
      <strong class="panyue-time">{{ when(status?.checkedAt) }}</strong>
      <em>主后端代理探测结果</em>
      <span>服务未启动时显示离线，属预期状态。</span>
    </article>
  </div>

  <section class="list-card panyue-target">
    <div>
      <div class="panyue-target-tags">
        <el-tag type="success" effect="plain">南京 · 鼓楼区</el-tag>
        <el-tag type="info" effect="plain">办公 100～300㎡</el-tag>
      </div>
      <h2>泛悦城市广场 T2 写字楼</h2>
      <p>目标来自 panyue-agent 初始化脚本，表示本次招商的目标范围，不是已核实的可租面积。</p>
    </div>
    <div class="panyue-target-action">
      <el-button type="primary" size="large" @click="openAgent">让招商Agent寻找候选企业</el-button>
      <small>将打开「智能招商 → 招商Agent」，自动选中泛悦项目并预填需求，发送前可修改。</small>
    </div>
  </section>

  <section class="list-card panyue-info">
    <div class="panyue-heading">
      <div>
        <h2>独立项目边界</h2>
        <p>panyue-agent 使用独立的 Compose 项目、数据库和端口，不与主平台 enterprise_db 混用。</p>
      </div>
    </div>

    <div class="panyue-columns">
      <div>
        <h3>服务地址</h3>
        <el-table :data="[
          { service: 'Spring Boot 健康检查', address: 'http://127.0.0.1:18080/api/health' },
          { service: 'Spring Boot 数据库就绪', address: 'http://127.0.0.1:18080/api/health/ready' },
          { service: 'FastAPI Agent 健康检查', address: 'http://127.0.0.1:18081/internal/health' },
          { service: 'MySQL', address: '127.0.0.1:13306' },
          { service: 'Redis', address: '127.0.0.1:16379' },
          { service: 'RabbitMQ / 管理页', address: '127.0.0.1:15673 / 25672' },
        ]" size="small">
          <el-table-column prop="service" label="服务" min-width="180" />
          <el-table-column prop="address" label="地址" min-width="260" />
        </el-table>
      </div>
      <div>
        <h3>本机启动</h3>
        <pre class="panyue-code">cd panyue-agent
Copy-Item .env.example .env
# 按 .env 注释填写三个本机口令
docker compose up -d --build --wait</pre>
        <p class="panyue-note">
          当前 Java 和 Python 服务只提供健康检查；后续业务 API、LangGraph 工作流、审核和跟进能力在后续交付中接入。
          主平台功能完整的 Agent 在「智能招商 → 招商Agent」。
        </p>
      </div>
    </div>
  </section>
</template>

<style scoped>
.panyue-alert { margin-bottom: 14px; }
.panyue-stats { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 14px; margin-bottom: 14px; min-height: 120px; }
.panyue-stat { display: flex; flex-direction: column; align-items: flex-start; gap: 5px; min-height: 150px; padding: 16px 18px; border: 1px solid #ebeef5; border-radius: var(--card-radius); background: #fff; box-shadow: var(--card-shadow); }
.panyue-stat.offline { border-color: #fde2e2; background: #fffafa; }
.panyue-stat small { color: #8a97a5; font-size: 12px; }
.panyue-stat strong { color: #1f2d3d; font-size: 23px; line-height: 1.2; }
.panyue-stat.offline strong { color: #d9534f; }
.panyue-stat .panyue-time { font-size: 15px; }
.panyue-stat em { max-width: 100%; overflow: hidden; color: #8a97a5; font-size: 11px; font-style: normal; text-overflow: ellipsis; white-space: nowrap; }
.panyue-stat span { color: #606c76; font-size: 12px; line-height: 1.5; }
.panyue-target { display: flex; align-items: center; justify-content: space-between; gap: 20px; padding: 18px 20px; margin-bottom: 14px; }
.panyue-target-tags { display: flex; flex-wrap: wrap; gap: 8px; margin-bottom: 8px; }
.panyue-target h2 { margin: 0 0 5px; color: #1f2d3d; font-size: 18px; }
.panyue-target p { margin: 0; color: #8a97a5; font-size: 12px; line-height: 1.6; }
.panyue-target-action { display: flex; flex-direction: column; align-items: flex-end; gap: 6px; }
.panyue-target-action small { max-width: 360px; color: #8a97a5; font-size: 12px; line-height: 1.5; text-align: right; }
.panyue-info { padding: 18px 20px; }
.panyue-heading h2 { margin: 0; color: #1f2d3d; font-size: 16px; }
.panyue-heading p { margin: 5px 0 14px; color: #8a97a5; font-size: 12px; }
.panyue-columns { display: grid; grid-template-columns: minmax(0, 1.15fr) minmax(0, .85fr); gap: 20px; }
.panyue-columns h3 { margin: 0 0 8px; color: #1f2d3d; font-size: 14px; }
.panyue-code { margin: 0 0 10px; padding: 12px 14px; overflow-x: auto; border-radius: 6px; background: #f5f7fa; color: #334155; font-size: 12px; line-height: 1.7; white-space: pre-wrap; }
.panyue-note { margin: 0; color: #8a97a5; font-size: 12px; line-height: 1.7; }
@media (max-width: 1100px) { .panyue-stats { grid-template-columns: repeat(2, minmax(0, 1fr)); } .panyue-columns { grid-template-columns: minmax(0, 1fr); } }
@media (max-width: 900px) { .panyue-target { align-items: flex-start; flex-direction: column; } .panyue-target-action { align-items: flex-start; } .panyue-target-action small { text-align: left; } }
@media (max-width: 560px) { .panyue-stats { grid-template-columns: minmax(0, 1fr); } }
</style>
