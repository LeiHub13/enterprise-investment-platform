<script setup lang="ts">
import type { MenuNode } from './menu'

defineProps<{ node: MenuNode }>()
</script>

<template>
  <el-sub-menu
    v-if="node.children?.length"
    :index="node.key"
    teleported
    popper-class="sidebar-menu-popper"
  >
    <template #title>
      <el-icon><component :is="node.icon" /></el-icon>
      <span>{{ node.label }}</span>
    </template>
    <SidebarItem v-for="child in node.children" :key="child.key" :node="child" />
  </el-sub-menu>
  <el-menu-item v-else :index="node.key">
    <el-icon><component :is="node.icon" /></el-icon>
    <template #title>{{ node.label }}</template>
  </el-menu-item>
</template>