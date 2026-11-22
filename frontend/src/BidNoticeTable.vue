<script setup lang="ts">
import { Star, StarFilled } from '@element-plus/icons-vue'
import type { RecordData } from './api'
import { dateText } from './format'
import { deadlineState, deadlineText, deadlineTone, noticeKindLabel, noticeTone, originLabel } from './bidradar'

defineProps<{
  projects: RecordData[]
  topicNames: Record<number, string[]>
  loading?: boolean
  emptyText?: string
}>()
const emit = defineEmits<{
  detail: [project: RecordData]
  favorite: [project: RecordData]
}>()
</script>

<template>
  <el-table v-loading="loading" :data="projects" row-key="id" :empty-text="emptyText || '暂无标讯'">
    <el-table-column label="公告标题" min-width="290" show-overflow-tooltip>
      <template #default="{ row }">
        <button type="button" class="notice-title" @click="emit('detail', row)">{{ row.title }}</button>
        <div class="notice-flags">
          <el-tag v-if="row.is_notice === 0" size="small" type="info" effect="plain">疑似非公告</el-tag>
          <el-tag v-if="row.demo" size="small" type="warning" effect="plain">演示</el-tag>
        </div>
      </template>
    </el-table-column>
    <el-table-column label="类型" min-width="105">
      <template #default="{ row }">
        <el-tag :type="noticeTone(row)" size="small" effect="light">{{ noticeKindLabel(row) }}</el-tag>
      </template>
    </el-table-column>
    <el-table-column label="命中主题" min-width="150">
      <template #default="{ row }">
        <div class="topic-tags">
          <el-tag v-for="name in topicNames[row.id] ?? []" :key="name" size="small" effect="plain">{{ name }}</el-tag>
          <span v-if="!(topicNames[row.id] ?? []).length" class="muted">—</span>
        </div>
      </template>
    </el-table-column>
    <el-table-column label="采购人" min-width="140" show-overflow-tooltip>
      <template #default="{ row }">{{ row.buyer_name || '—' }}</template>
    </el-table-column>
    <el-table-column label="发布日期" min-width="105">
      <template #default="{ row }">{{ dateText(row.published) }}</template>
    </el-table-column>
    <el-table-column label="收录时间" min-width="105">
      <template #default="{ row }">{{ dateText(row.first_seen_at || row.fetched_at) }}</template>
    </el-table-column>
    <el-table-column label="截止" min-width="135">
      <template #default="{ row }">
        <span :class="{ 'deadline-expired': deadlineState(row) === 'expired' }">{{ deadlineText(row) }}</span>
        <el-tag v-if="deadlineState(row) === 'expired'" size="small" :type="deadlineTone(row)" effect="plain">已截止</el-tag>
      </template>
    </el-table-column>
    <el-table-column label="来源" min-width="128">
      <template #default="{ row }">{{ originLabel(row.origin) }}</template>
    </el-table-column>
    <el-table-column label="收藏" width="62" align="center">
      <template #default="{ row }">
        <el-button link :type="row.favorite ? 'warning' : 'info'" :aria-label="row.favorite ? '取消收藏' : '收藏'"
          @click.stop="emit('favorite', row)">
          <el-icon :size="16"><StarFilled v-if="row.favorite" /><Star v-else /></el-icon>
        </el-button>
      </template>
    </el-table-column>
    <el-table-column label="操作" width="76" fixed="right">
      <template #default="{ row }">
        <el-button link type="primary" @click="emit('detail', row)">详情</el-button>
      </template>
    </el-table-column>
  </el-table>
</template>

<style scoped>
.notice-title {
  display: block;
  max-width: 100%;
  padding: 0;
  color: var(--el-color-primary);
  text-align: left;
  font-size: 13px;
  line-height: 1.5;
  border: 0;
  background: transparent;
  cursor: pointer;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.notice-title:hover { text-decoration: underline; }
.notice-flags { display: flex; gap: 4px; margin-top: 3px; }
.topic-tags { display: flex; flex-wrap: wrap; gap: 3px; }
.muted { color: #c0c4cc; font-size: 12px; }
.deadline-expired { color: #f56c6c; }
</style>
