<script setup lang="ts">
import { computed } from 'vue'
import { marked } from 'marked'
import DOMPurify from 'dompurify'

const props = defineProps<{ source: string }>()

const html = computed(() => {
  if (!props.source) return ''
  const raw = marked.parse(props.source, { async: false })
  return DOMPurify.sanitize(raw, { USE_PROFILES: { html: true } })
})
</script>

<template>
  <div class="markdown-body" v-html="html" />
</template>

<style scoped>
.markdown-body {
  color: #303133;
  font-size: 13px;
  line-height: 1.9;
  word-break: break-word;
}

.markdown-body :deep(h1),
.markdown-body :deep(h2),
.markdown-body :deep(h3),
.markdown-body :deep(h4) {
  margin: 18px 0 8px;
  color: #1f2d3d;
  line-height: 1.5;
}

.markdown-body :deep(h1:first-child),
.markdown-body :deep(h2:first-child),
.markdown-body :deep(h3:first-child) {
  margin-top: 0;
}

.markdown-body :deep(h1) {
  font-size: 18px;
  padding-bottom: 8px;
  border-bottom: 1px solid #ebeef5;
}

.markdown-body :deep(h2) {
  padding-left: 10px;
  font-size: 15px;
  border-left: 3px solid var(--el-color-primary);
}

.markdown-body :deep(h3) {
  font-size: 14px;
}

.markdown-body :deep(h4) {
  font-size: 13px;
}

.markdown-body :deep(p) {
  margin: 8px 0;
}

.markdown-body :deep(strong) {
  color: #1f2d3d;
  font-weight: 600;
}

.markdown-body :deep(em) {
  color: #606266;
}

.markdown-body :deep(ul),
.markdown-body :deep(ol) {
  margin: 8px 0;
  padding-left: 22px;
}

.markdown-body :deep(li) {
  margin: 3px 0;
}

.markdown-body :deep(li::marker) {
  color: var(--el-color-primary);
}

.markdown-body :deep(hr) {
  margin: 16px 0;
  border: none;
  border-top: 1px solid #ebeef5;
}

.markdown-body :deep(blockquote) {
  margin: 10px 0;
  padding: 8px 14px;
  border-left: 3px solid #dcdfe6;
  border-radius: 0 6px 6px 0;
  background: #f5f7fa;
  color: #606266;
}

.markdown-body :deep(blockquote p) {
  margin: 2px 0;
}

.markdown-body :deep(code) {
  padding: 1px 6px;
  border-radius: 4px;
  background: #f0f2f5;
  color: #c7254e;
  font-family: ui-monospace, SFMono-Regular, Consolas, "Courier New", monospace;
  font-size: 12px;
}

.markdown-body :deep(pre) {
  margin: 10px 0;
  padding: 12px 14px;
  overflow-x: auto;
  border-radius: 8px;
  background: #0f2438;
  color: #d8e4ef;
  font-size: 12px;
  line-height: 1.7;
}

.markdown-body :deep(pre code) {
  padding: 0;
  background: none;
  color: inherit;
  font-size: inherit;
}

.markdown-body :deep(table) {
  width: 100%;
  margin: 10px 0;
  border-collapse: collapse;
  font-size: 12px;
}

.markdown-body :deep(th),
.markdown-body :deep(td) {
  padding: 7px 10px;
  border: 1px solid #ebeef5;
  text-align: left;
}

.markdown-body :deep(th) {
  background: #f5f7fa;
  color: #1f2d3d;
  font-weight: 600;
}

.markdown-body :deep(a) {
  color: var(--el-color-primary);
  text-decoration: none;
}

.markdown-body :deep(a:hover) {
  text-decoration: underline;
}
</style>