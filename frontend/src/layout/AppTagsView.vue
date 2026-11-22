<script setup lang="ts">
import { ArrowDown, Close, Refresh } from '@element-plus/icons-vue'

defineProps<{
  tabs: { key: string; label: string; closable: boolean }[]
  activeKey: string
}>()
const emit = defineEmits<{
  select: [key: string]
  close: [key: string]
  refresh: []
  closeOthers: []
  closeAll: []
}>()

function handleCommand(command: string) {
  if (command === 'others') emit('closeOthers')
  if (command === 'all') emit('closeAll')
}
</script>

<template>
  <div class="app-tags-view">
    <el-scrollbar class="tags-scroll">
      <div class="tags-list">
        <button
          v-for="tab in tabs"
          :key="tab.key"
          type="button"
          class="tag-item"
          :class="{ active: activeKey === tab.key }"
          @click="emit('select', tab.key)"
        >
          <span class="tag-dot" />
          <span class="tag-label">{{ tab.label }}</span>
          <el-icon
            v-if="tab.closable"
            class="tag-close"
            :size="12"
            @click.stop="emit('close', tab.key)"
          >
            <Close />
          </el-icon>
        </button>
      </div>
    </el-scrollbar>

    <div class="tags-actions">
      <el-tooltip content="刷新当前页" placement="bottom" :show-after="300">
        <button type="button" class="tags-action" aria-label="刷新当前页" @click="emit('refresh')">
          <el-icon :size="14"><Refresh /></el-icon>
        </button>
      </el-tooltip>
      <el-dropdown trigger="click" @command="handleCommand">
        <button type="button" class="tags-action" aria-label="标签操作">
          <el-icon :size="14"><ArrowDown /></el-icon>
        </button>
        <template #dropdown>
          <el-dropdown-menu>
            <el-dropdown-item command="others">关闭其他标签</el-dropdown-item>
            <el-dropdown-item command="all" divided>关闭全部标签</el-dropdown-item>
          </el-dropdown-menu>
        </template>
      </el-dropdown>
    </div>
  </div>
</template>

<style scoped>
.app-tags-view {
  position: sticky;
  top: var(--navbar-height);
  z-index: 999;
  display: flex;
  align-items: center;
  height: var(--tags-height);
  background: #fff;
  border-bottom: 1px solid #d8dce5;
  box-shadow: 0 1px 3px rgb(0 0 0 / 4%);
}

.tags-scroll {
  flex: 1;
  min-width: 0;
}

.tags-list {
  display: flex;
  align-items: center;
  gap: 4px;
  height: var(--tags-height);
  padding: 0 8px;
  white-space: nowrap;
}

.tag-item {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  height: 26px;
  padding: 0 9px;
  color: #5a6a7a;
  font-size: 12px;
  border: 1px solid #d8dce5;
  border-radius: 3px;
  background: #fff;
  cursor: pointer;
  transition: all var(--transition-time);
}

.tag-item:hover {
  color: var(--el-color-primary);
  border-color: var(--el-color-primary-light-5);
}

.tag-item.active {
  color: #fff;
  border-color: var(--el-color-primary);
  background: var(--el-color-primary);
}

.tag-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #c0c4cc;
}

.tag-item.active .tag-dot {
  background: #fff;
}

.tag-close {
  margin-left: 2px;
  border-radius: 50%;
  opacity: .65;
  transition: opacity var(--transition-time), background var(--transition-time);
}

.tag-close:hover {
  background: rgb(255 255 255 / 25%);
  opacity: 1;
}

.tag-item:not(.active) .tag-close:hover {
  background: rgb(0 0 0 / 8%);
}

.tags-actions {
  display: flex;
  align-items: center;
  height: 100%;
  padding: 0 6px;
  border-left: 1px solid #e8ebef;
}

.tags-action {
  display: grid;
  place-items: center;
  width: 28px;
  height: 28px;
  color: #8a97a5;
  border: 0;
  border-radius: 4px;
  background: transparent;
  cursor: pointer;
}

.tags-action:hover {
  color: var(--el-color-primary);
  background: var(--top-header-hover-color);
}

@media (max-width: 700px) {
  .tags-actions {
    display: none;
  }
}
</style>
