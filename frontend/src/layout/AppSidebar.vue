<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import type { MenuInstance } from 'element-plus'
import SidebarItem from './SidebarItem.vue'
import { buildMenuTree, findOpenChain } from './menu'
import type { FlatMenuItem } from './menu'

const props = defineProps<{
  items: FlatMenuItem[]
  collapsed: boolean
  mobileOpen: boolean
  active: string
}>()
const emit = defineEmits<{ select: [key: string] }>()

const menuRef = ref<MenuInstance>()
const tree = computed(() => buildMenuTree(props.items))
const openChain = computed(() => findOpenChain(tree.value, props.active))

watch(
  () => [props.active, props.collapsed, tree.value] as const,
  () => {
    if (props.collapsed) return
    nextTick(() => {
      openChain.value.forEach(key => {
        try {
          menuRef.value?.open(key)
        } catch {
          // 菜单尚未挂载时忽略
        }
      })
    })
  },
  { immediate: true, flush: 'post' },
)

function onSelect(index: string) {
  emit('select', index)
}
</script>

<template>
  <aside class="app-sidebar" :class="{ collapsed, 'mobile-open': mobileOpen }">
    <div class="sidebar-logo" @click="emit('select', 'dashboard')">
      <span class="sidebar-logo-mark">招</span>
      <transition name="logo-fade">
        <span v-if="!collapsed" class="sidebar-logo-title">
          企业招商平台
          <small>INVESTMENT PLATFORM</small>
        </span>
      </transition>
    </div>

    <el-scrollbar class="sidebar-scroll">
      <el-menu
        ref="menuRef"
        class="sidebar-menu"
        :default-active="active"
        :default-openeds="openChain"
        :collapse="collapsed"
        :collapse-transition="false"
        :unique-opened="false"
        background-color="#001529"
        text-color="#bfcbd9"
        active-text-color="#ffffff"
        @select="onSelect"
      >
        <SidebarItem v-for="node in tree" :key="node.key" :node="node" />
      </el-menu>
    </el-scrollbar>

    <div v-if="!collapsed" class="sidebar-footer">
      <span class="footer-dot" />
      内部系统 · v1.0
    </div>
  </aside>
</template>

<style scoped>
.app-sidebar {
  position: fixed;
  inset: 0 auto 0 0;
  z-index: 1001;
  display: flex;
  flex-direction: column;
  width: var(--sidebar-width);
  background: var(--left-menu-bg);
  box-shadow: 2px 0 8px rgb(0 21 41 / 18%);
  transition: width var(--transition-time), transform var(--transition-time);
}

.app-sidebar.collapsed {
  width: var(--sidebar-collapsed-width);
}

.sidebar-logo {
  display: flex;
  align-items: center;
  gap: 10px;
  height: var(--navbar-height);
  padding: 0 14px;
  overflow: hidden;
  cursor: pointer;
  background: #002140;
  flex: 0 0 auto;
}

.sidebar-logo-mark {
  display: grid;
  flex: 0 0 auto;
  place-items: center;
  width: 32px;
  height: 32px;
  border-radius: 8px;
  background: linear-gradient(135deg, #409eff, #79bbff);
  color: #fff;
  font-size: 17px;
  font-weight: 700;
  box-shadow: 0 4px 10px rgb(64 158 255 / 35%);
}

.sidebar-logo-title {
  display: flex;
  flex-direction: column;
  gap: 2px;
  color: #fff;
  font-size: 15px;
  font-weight: 700;
  white-space: nowrap;
  letter-spacing: .02em;
}

.sidebar-logo-title small {
  color: rgb(191 203 217 / 55%);
  font-size: 8px;
  font-weight: 500;
  letter-spacing: .12em;
}

.sidebar-scroll {
  flex: 1;
  min-height: 0;
}

.sidebar-footer {
  display: flex;
  align-items: center;
  gap: 7px;
  flex: 0 0 auto;
  padding: 14px 18px;
  color: rgb(191 203 217 / 40%);
  font-size: 11px;
  border-top: 1px solid rgb(255 255 255 / 7%);
  white-space: nowrap;
}

.footer-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #67c23a;
}

.logo-fade-enter-active,
.logo-fade-leave-active {
  transition: opacity .15s ease;
}

.logo-fade-enter-from,
.logo-fade-leave-to {
  opacity: 0;
}

@media (max-width: 900px) {
  .app-sidebar {
    width: var(--sidebar-width);
    transform: translateX(-100%);
  }

  .app-sidebar.mobile-open {
    transform: translateX(0);
  }

  .app-sidebar.collapsed {
    width: var(--sidebar-width);
  }
}
</style>

<style scoped>
.sidebar-scroll {
  padding: 0;
}

.sidebar-scroll :deep(.el-scrollbar__view) {
  padding: 10px 8px 18px;
}

.sidebar-menu {
  border-right: none;
  background: transparent;
}

.sidebar-menu :deep(.el-sub-menu__title) {
  height: 42px;
  margin: 2px 0;
  color: var(--left-menu-text);
  font-size: 13px;
  border-radius: 6px;
  transition: background var(--transition-time), color var(--transition-time);
}

.sidebar-menu :deep(.el-sub-menu__title:hover) {
  color: #fff;
  background: var(--left-menu-bg-light);
}

.sidebar-menu :deep(.el-sub-menu.is-active > .el-sub-menu__title) {
  color: #fff;
}

.sidebar-menu :deep(.el-sub-menu .el-menu) {
  background: transparent !important;
}

.sidebar-menu :deep(.el-menu-item) {
  height: 40px;
  min-width: 0;
  margin: 2px 0;
  color: var(--left-menu-text);
  font-size: 13px;
  border-radius: 6px;
  transition: background var(--transition-time), color var(--transition-time);
}

.sidebar-menu :deep(.el-menu-item:hover) {
  color: #fff;
  background: var(--left-menu-bg-light);
}

.sidebar-menu :deep(.el-menu-item.is-active) {
  color: #fff;
  background: var(--el-color-primary) !important;
  box-shadow: 0 3px 10px rgb(64 158 255 / 35%);
}

.sidebar-menu :deep(.el-menu-item .el-icon),
.sidebar-menu :deep(.el-sub-menu__title .el-icon) {
  margin-right: 8px;
  font-size: 17px;
}

.sidebar-menu :deep(.el-sub-menu__icon-arrow) {
  right: 12px;
  color: rgb(191 203 217 / 55%);
}

.sidebar-menu :deep(.el-menu--collapse) {
  width: var(--sidebar-collapsed-width);
}

.sidebar-menu :deep(.el-menu--collapse .el-sub-menu__title) {
  justify-content: center;
  padding: 0 !important;
}

.sidebar-menu :deep(.el-menu--collapse > .el-menu-item) {
  justify-content: center;
  padding: 0 !important;
}

.sidebar-menu :deep(.el-menu--collapse .el-sub-menu__title .el-icon),
.sidebar-menu :deep(.el-menu--collapse > .el-menu-item .el-icon) {
  margin-right: 0;
}

@media (max-width: 900px) {
  .sidebar-menu :deep(.el-menu--collapse) {
    width: var(--sidebar-width);
  }
}
</style>