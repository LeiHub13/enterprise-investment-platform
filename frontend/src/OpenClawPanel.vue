<script setup lang="ts">
import { computed, nextTick, onMounted, ref } from 'vue'
import { Promotion, Refresh } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, ApiError } from './api'
import ProspectingPanel from './ProspectingPanel.vue'

const props = defineProps<{ username?: string }>()
const emit = defineEmits<{ expired: [error: unknown] }>()

type Status = { ready: boolean; message: string; conversationId: string }
type Source = { id: number; title: string; url: string; date: string; excerpt: string }
type Lead = { company: string; signal: string; evidence: string; sourceId: number; method: 'ai' | 'source' }
type Discovery = { leads: Lead[]; sources: Source[]; warnings: string[]; searchedQueries: number; searchedAt: string }
type Message = { role: 'user' | 'assistant'; text: string; at: string; discovery?: Discovery }

const status = ref<Status | null>(null)
const messages = ref<Message[]>([])
const draft = ref('')
const checking = ref(false)
const sending = ref(false)
const discovering = ref(false)
const error = ref('')
const transcript = ref<HTMLElement | null>(null)
const composerInput = ref<{ focus?: () => void } | null>(null)

const suggestions = [
  '帮我整理一份企业跟进提纲',
  '分析这个招商项目的目标企业',
  '写一段首次沟通话术',
  '根据线索列出需要核实的问题',
]

const userInitial = computed(() => (props.username || '我').slice(0, 1).toUpperCase())
const busy = computed(() => sending.value || discovering.value)
const statusText = computed(() => checking.value
  ? '正在检查连接'
  : status.value?.message || '连接状态未知')

function timeText() {
  return new Date().toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })
}

async function prepareAnalysis(message: string) {
  if (draft.value.trim()) {
    ElMessage.warning('输入框已有内容，请先发送或清空，再生成分析内容。')
    return
  }
  draft.value = message
  ElMessage.success('分析内容已填入输入框，请检查后发送。')
  await nextTick()
  composerInput.value?.focus?.()
}

function useSuggestion(text: string) {
  draft.value = text
  composerInput.value?.focus?.()
}

async function newConversation() {
  if (messages.value.length) {
    try {
      await ElMessageBox.confirm('当前对话记录将被清空，确定新建对话吗？', '新建对话', {
        type: 'warning', confirmButtonText: '新建', cancelButtonText: '取消',
      })
    } catch {
      return
    }
  }
  checking.value = true
  error.value = ''
  try {
    const result = await api<Status>('/openclaw/status')
    status.value = result
    messages.value = []
    draft.value = ''
  } catch (reason) {
    if (reason instanceof ApiError && reason.status === 401) emit('expired', reason)
    error.value = reason instanceof ApiError && reason.status === 404
      ? '后端尚未加载 OpenClaw 功能，请更新并重启项目后端。'
      : reason instanceof Error ? reason.message : '无法检查 OpenClaw 状态'
  } finally {
    checking.value = false
  }
}

async function send() {
  const text = draft.value.trim()
  if (!text || !status.value?.ready || busy.value) return
  const conversationId = status.value.conversationId
  const at = timeText()
  draft.value = ''
  messages.value.push({ role: 'user', text, at })
  sending.value = true
  error.value = ''
  await scrollDown()
  try {
    const result = await api<{ reply: string }>('/openclaw/chat', 'POST', { message: text, conversationId })
    messages.value.push({ role: 'assistant', text: result.reply, at: timeText() })
  } catch (reason) {
    if (reason instanceof ApiError && reason.status === 401) emit('expired', reason)
    error.value = reason instanceof Error ? reason.message : '对话暂时不可用，请稍后再试。'
  } finally {
    sending.value = false
    await scrollDown()
  }
}

async function findLeads(criteria: { projectName: string; projectDetails: string }) {
  if (!status.value?.ready || busy.value) return
  const conversationId = status.value.conversationId
  messages.value.push({ role: 'user', text: `请为${criteria.projectName}寻找南京办公空间招商线索。`, at: timeText() })
  discovering.value = true
  error.value = ''
  await scrollDown()
  try {
    const result = await api<Discovery>('/openclaw/find-leads', 'POST', { ...criteria, conversationId })
    messages.value.push({
      role: 'assistant', at: timeText(), discovery: result,
      text: result.leads.length
        ? `完成 ${result.searchedQueries}/3 项搜索，找到 ${result.leads.length} 条有搜索摘录依据的候选企业线索。需求是否仍然存在，请打开来源核实。`
        : `完成 ${result.searchedQueries}/3 项搜索，本轮没有识别出符合条件且有搜索摘录依据的企业线索。已列出 ${result.sources.length} 条检索来源供核对。`,
    })
  } catch (reason) {
    if (reason instanceof ApiError && reason.status === 401) emit('expired', reason)
    error.value = reason instanceof ApiError && reason.status === 404
      ? '后端尚未加载获客搜索功能，请重新运行后端。'
      : reason instanceof Error ? reason.message : '获客搜索暂时不可用，请稍后再试。'
  } finally {
    discovering.value = false
    await scrollDown()
  }
}

function sourceFor(discovery: Discovery, sourceId: number) {
  return discovery.sources.find(source => source.id === sourceId)
}

function sourceDate(source: Source) {
  return source.date ? source.date.slice(0, 10) : '日期待核实'
}

async function scrollDown() {
  await nextTick()
  if (transcript.value) transcript.value.scrollTop = transcript.value.scrollHeight
}

onMounted(() => { void newConversation() })
</script>

<template>
  <section class="assistant-page">
    <div class="page-header assistant-page-header">
      <div class="page-header-copy">
        <div class="page-header-title">
          <span class="page-header-glyph">助</span>
          <h1>OpenClaw 助手</h1>
        </div>
        <p>结合写字楼获客上下文，分析企业资料、招商项目和沟通话术。</p>
      </div>
      <div class="page-actions">
        <el-tag type="info" effect="plain">管理员专用</el-tag>
        <el-button :icon="Refresh" :loading="checking" :disabled="busy" @click="newConversation">新建对话</el-button>
      </div>
    </div>

    <div class="assistant-grid">
      <aside class="assistant-context">
        <ProspectingPanel :discovering="discovering" :connected="Boolean(status?.ready) && !checking" :busy="busy"
          @expired="emit('expired', $event)" @prepare="prepareAnalysis" @discover="findLeads" />
      </aside>

      <section class="assistant-chat">
        <header class="chat-header">
          <div class="chat-identity">
            <span class="chat-avatar">助</span>
            <div class="chat-identity-copy">
              <strong>OpenClaw 助手</strong>
              <span class="chat-status">
                <i :class="{ ready: status?.ready }" />
                {{ statusText }}
              </span>
            </div>
          </div>
          <span class="chat-model">crm-chat</span>
        </header>

        <div ref="transcript" class="chat-transcript" aria-live="polite">
          <div v-if="!messages.length" class="chat-welcome">
            <span class="welcome-avatar">助</span>
            <h2>你好，我是 OpenClaw 助手</h2>
            <p>可以帮你整理企业资料、分析招商项目、撰写跟进话术。先告诉我你想做什么。</p>
            <div class="suggestion-list">
              <button v-for="item in suggestions" :key="item" type="button" @click="useSuggestion(item)">
                {{ item }}
              </button>
            </div>
          </div>

          <div v-for="(message, index) in messages" :key="index" class="chat-row" :class="message.role">
            <span class="message-avatar">{{ message.role === 'user' ? userInitial : '助' }}</span>
            <div class="message-body">
              <div class="message-meta">
                <strong>{{ message.role === 'user' ? '我' : 'OpenClaw' }}</strong>
                <time>{{ message.at }}</time>
              </div>
              <p class="message-bubble">{{ message.text }}</p>
              <div v-if="message.discovery" class="discovery-results">
                <article v-for="lead in message.discovery.leads" :key="lead.company + lead.sourceId" class="discovery-lead">
                  <strong>{{ lead.company }}</strong>
                  <p>{{ lead.method === 'source' ? '来源字段提取' : '龙虾判断' }}：{{ lead.signal }}</p>
                  <p class="discovery-evidence">搜索摘录：{{ lead.evidence }}</p>
                  <a v-if="sourceFor(message.discovery, lead.sourceId)" :href="sourceFor(message.discovery, lead.sourceId)?.url"
                    target="_blank" rel="noopener noreferrer">查看来源 {{ lead.sourceId }} · {{ sourceDate(sourceFor(message.discovery, lead.sourceId)!) }}</a>
                </article>
                <p v-for="warning in message.discovery.warnings" :key="warning" class="discovery-warning">{{ warning }}</p>
                <details v-if="message.discovery.sources.length" class="discovery-sources">
                  <summary>查看本轮 {{ message.discovery.sources.length }} 条搜索来源</summary>
                  <article v-for="source in message.discovery.sources" :key="source.id" class="discovery-source">
                    <a :href="source.url" target="_blank" rel="noopener noreferrer">{{ source.id }}. {{ source.title }}</a>
                    <span>{{ sourceDate(source) }}</span>
                    <p>{{ source.excerpt }}</p>
                  </article>
                </details>
              </div>
            </div>
          </div>

          <div v-if="busy" class="chat-row assistant typing-row">
            <span class="message-avatar">助</span>
            <div class="typing-bubble">
              <i /><i /><i /><span>{{ discovering ? '正在搜索公开信息并分析线索，可能需要一分钟' : '正在思考' }}</span>
            </div>
          </div>
        </div>

        <footer class="chat-composer">
          <div v-if="!status?.ready && !checking" class="offline-banner">
            <span>{{ status?.message || 'OpenClaw 尚未连接' }}</span>
            <el-button link type="primary" :loading="checking" @click="newConversation">重新检查</el-button>
          </div>
          <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon class="chat-error" />
          <div class="composer-box">
            <el-input
              ref="composerInput"
              v-model="draft"
              type="textarea"
              :rows="3"
              maxlength="4000"
              resize="none"
              placeholder="输入消息，Enter 发送，Shift + Enter 换行"
              :disabled="!status?.ready || busy"
              @keydown.enter.exact.prevent="send"
            />
            <div class="composer-toolbar">
              <span class="composer-hint">Enter 发送 · Shift + Enter 换行</span>
              <el-button type="primary" :icon="Promotion" :loading="sending"
                :disabled="!status?.ready || !draft.trim() || busy" @click="send">发送</el-button>
            </div>
          </div>
          <p class="privacy-note">手动搜索时会把公开搜索结果和项目条件发送给龙虾；不会自动读取企业库或创建线索。</p>
        </footer>
      </section>
    </div>
  </section>
</template>

<style scoped>
.assistant-page { max-width: 1440px; margin: 0 auto; }
.assistant-page-header { margin-bottom: 16px; }
.assistant-grid {
  display: grid;
  grid-template-columns: 382px minmax(0, 1fr);
  gap: 16px;
  height: calc(100vh - 238px);
  min-height: 560px;
}
.assistant-context { min-width: 0; min-height: 0; }
.assistant-chat {
  display: flex;
  flex-direction: column;
  min-width: 0;
  min-height: 0;
  overflow: hidden;
  border: 1px solid #ebeef5;
  border-radius: var(--card-radius);
  background: #fff;
  box-shadow: var(--card-shadow);
}
.chat-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex: 0 0 auto;
  gap: 12px;
  min-height: 62px;
  padding: 0 20px;
  border-bottom: 1px solid #edf1f5;
}
.chat-identity { display: flex; align-items: center; gap: 11px; min-width: 0; }
.chat-avatar, .message-avatar, .welcome-avatar {
  display: grid;
  flex: 0 0 auto;
  place-items: center;
  border-radius: 50%;
  color: #fff;
  font-weight: 700;
  background: linear-gradient(135deg, #409eff, #36cfc9);
}
.chat-avatar { width: 34px; height: 34px; font-size: 15px; }
.chat-identity-copy { display: flex; flex-direction: column; gap: 3px; min-width: 0; }
.chat-identity-copy strong { color: #1f2d3d; font-size: 14px; font-weight: 600; }
.chat-status { display: inline-flex; align-items: center; gap: 6px; color: #8a97a5; font-size: 11px; }
.chat-status i { width: 7px; height: 7px; border-radius: 50%; background: #e6a23c; }
.chat-status i.ready { background: #67c23a; box-shadow: 0 0 0 3px rgb(103 194 58 / 12%); }
.chat-model {
  flex: 0 0 auto;
  padding: 3px 8px;
  border: 1px solid #e4eaf1;
  border-radius: 4px;
  color: #a0aab5;
  font-size: 10px;
  letter-spacing: .06em;
  background: #f8fafc;
}
.chat-transcript {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 26px 28px;
  background: linear-gradient(180deg, #f7fbff 0, #fff 220px);
}
.chat-welcome {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  max-width: 560px;
  min-height: 100%;
  margin: 0 auto;
  text-align: center;
}
.welcome-avatar { width: 58px; height: 58px; font-size: 25px; box-shadow: 0 10px 24px rgb(64 158 255 / 22%); }
.chat-welcome h2 { margin: 18px 0 8px; color: #1f2d3d; font-size: 20px; font-weight: 600; }
.chat-welcome > p { max-width: 430px; margin: 0; color: #8a97a5; font-size: 13px; line-height: 1.8; }
.suggestion-list { display: flex; flex-wrap: wrap; justify-content: center; gap: 8px; margin-top: 22px; }
.suggestion-list button {
  padding: 8px 13px;
  border: 1px solid #e1e8f0;
  border-radius: 18px;
  color: #5a6a7a;
  font-size: 12px;
  background: #fff;
  cursor: pointer;
  transition: border-color .2s, color .2s, box-shadow .2s;
}
.suggestion-list button:hover {
  color: var(--el-color-primary);
  border-color: var(--el-color-primary-light-5);
  box-shadow: 0 4px 12px rgb(64 158 255 / 10%);
}
.chat-row { display: flex; align-items: flex-start; gap: 10px; max-width: 84%; margin-bottom: 22px; }
.chat-row.user { flex-direction: row-reverse; margin-left: auto; }
.message-avatar { width: 30px; height: 30px; font-size: 12px; }
.chat-row.user .message-avatar { color: #5a6a7a; background: #eaf0f6; }
.message-body { min-width: 0; }
.chat-row.user .message-body { text-align: right; }
.message-meta { display: flex; align-items: center; gap: 8px; margin: 0 0 5px 2px; }
.chat-row.user .message-meta { justify-content: flex-end; margin-right: 2px; }
.message-meta strong { color: #7b8a99; font-size: 11px; font-weight: 600; }
.message-meta time { color: #b3bcc5; font-size: 10px; }
.message-bubble {
  margin: 0;
  padding: 12px 15px;
  color: #2d4055;
  font-size: 13px;
  line-height: 1.75;
  text-align: left;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  border: 1px solid #e9eef5;
  border-radius: 4px 12px 12px;
  background: #f7f9fc;
}
.chat-row.user .message-bubble {
  color: #fff;
  border-color: transparent;
  border-radius: 12px 4px 12px 12px;
  background: linear-gradient(135deg, #409eff, #337ecc);
  box-shadow: 0 6px 16px rgb(64 158 255 / 18%);
}
.discovery-results { display: grid; gap: 9px; margin-top: 10px; }
.discovery-lead { padding: 12px 14px; border: 1px solid #e4edf7; border-radius: 8px; background: #fff; }
.discovery-lead strong { color: #263f5a; font-size: 13px; }
.discovery-lead p, .discovery-source p { margin: 6px 0; color: #52677d; font-size: 12px; line-height: 1.65; overflow-wrap: anywhere; }
.discovery-lead .discovery-evidence { color: #718398; }
.discovery-results a { color: #337ecc; font-size: 12px; overflow-wrap: anywhere; text-decoration: none; }
.discovery-results a:hover { text-decoration: underline; }
.discovery-warning { margin: 0; color: #a47733; font-size: 12px; line-height: 1.6; }
.discovery-sources { color: #607487; font-size: 12px; }
.discovery-sources summary { cursor: pointer; }
.discovery-source { margin-top: 8px; padding: 10px 12px; border: 1px solid #edf1f5; border-radius: 7px; background: #fff; }
.discovery-source span { display: block; margin-top: 4px; color: #97a5b4; font-size: 11px; }
.typing-row { align-items: center; }
.typing-bubble {
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 12px 15px;
  border: 1px solid #e9eef5;
  border-radius: 4px 12px 12px;
  background: #f7f9fc;
}
.typing-bubble i { width: 6px; height: 6px; border-radius: 50%; background: #a0cfff; animation: typing 1.2s infinite ease-in-out; }
.typing-bubble i:nth-child(2) { animation-delay: .15s; }
.typing-bubble i:nth-child(3) { animation-delay: .3s; }
.typing-bubble span { margin-left: 4px; color: #9aa8b6; font-size: 11px; }
@keyframes typing {
  0%, 60%, 100% { transform: translateY(0); opacity: .5; }
  30% { transform: translateY(-4px); opacity: 1; }
}
.chat-composer { flex: 0 0 auto; padding: 14px 18px 16px; border-top: 1px solid #edf1f5; background: #fff; }
.offline-banner {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 10px;
  padding: 9px 12px;
  border: 1px solid #faecd8;
  border-radius: 6px;
  color: #b88230;
  font-size: 12px;
  background: #fdf6ec;
}
.chat-error { margin-bottom: 10px; }
.composer-box {
  padding: 10px 12px 8px;
  border: 1px solid #dfe6ee;
  border-radius: 10px;
  background: #fff;
  transition: border-color .2s, box-shadow .2s;
}
.composer-box:focus-within { border-color: var(--el-color-primary-light-5); box-shadow: 0 0 0 3px rgb(64 158 255 / 8%); }
.composer-box :deep(.el-textarea__inner) {
  min-height: 64px !important;
  padding: 4px 2px;
  border: 0;
  box-shadow: none;
  color: #303133;
  font-size: 13px;
  line-height: 1.7;
  background: transparent;
}
.composer-toolbar { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-top: 6px; }
.composer-hint { color: #b0bac4; font-size: 11px; }
.privacy-note { margin: 8px 2px 0; color: #b8c2cc; font-size: 10px; }
@media (max-width: 1180px) {
  .assistant-grid { grid-template-columns: 340px minmax(0, 1fr); }
}
@media (max-width: 960px) {
  .assistant-grid { height: auto; min-height: 0; grid-template-columns: minmax(0, 1fr); }
  .assistant-chat { order: 1; height: 68vh; min-height: 500px; }
  .assistant-context { order: 2; }
}
@media (max-width: 640px) {
  .assistant-page-header { align-items: flex-start; flex-direction: column; }
  .chat-transcript { padding: 18px 15px; }
  .chat-row { max-width: 94%; }
  .suggestion-list { flex-direction: column; width: 100%; }
  .chat-composer { padding: 12px; }
  .composer-hint { display: none; }
}
</style>
