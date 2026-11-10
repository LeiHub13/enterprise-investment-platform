<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import {
  ArrowDown, Expand, Fold, FullScreen, Lock, Refresh, SwitchButton,
} from '@element-plus/icons-vue'

const props = defineProps<{
  collapsed: boolean
  currentLabel: string
  username: string
  roleLabel: string
}>()
const emit = defineEmits<{
  toggle: []
  refresh: []
  password: []
  logout: []
  dashboard: []
}>()

const isFullscreen = ref(false)
const avatarText = computed(() => (props.username || '?').slice(0, 1).toUpperCase())

function syncFullscreen() {
  isFullscreen.value = Boolean(document.fullscreenElement)
}

async function toggleFullscreen() {
  try {
    if (document.fullscreenElement) await document.exitFullscreen()
    else await document.documentElement.requestFullscreen()
  } catch {
    // 浏览器可能禁止全屏，保持静默
  }
  syncFullscreen()
}

function handleCommand(command: string) {
  if (command === 'password') emit('password')
  if (command === 'logout') emit('logout')
}

onMounted(() => document.addEventListener('fullscreenchange', syncFullscreen))
onBeforeUnmount(() => document.removeEventListener('fullscreenchange', syncFullscreen))
</script>

<template>
  <header class="app-navbar">
    <div class="navbar-left">
      <el-tooltip :content="collapsed ? '展开菜单' : '收起菜单'" placement="bottom" :show-after="300">
        <button type="button" class="navbar-icon" aria-label="切换菜单" @click="emit('toggle')">
          <el-icon :size="19"><Expand v-if="collapsed" /><Fold v-else /></el-icon>
        </button>
      </el-tooltip>
      <nav class="navbar-breadcrumb" aria-label="面包屑">
        <button type="button" class="breadcrumb-link" @click="emit('dashboard')">首页</button>
        <span class="breadcrumb-separator">/</span>
        <span class="breadcrumb-current">{{ currentLabel }}</span>
      </nav>
    </div>

    <div class="navbar-right">
      <el-tooltip content="刷新当前页" placement="bottom" :show-after="300">
        <button type="button" class="navbar-icon" aria-label="刷新当前页" @click="emit('refresh')">
          <el-icon :size="18"><Refresh /></el-icon>
        </button>
      </el-tooltip>
      <el-tooltip :content="isFullscreen ? '退出全屏' : '全屏'" placement="bottom" :show-after="300">
        <button type="button" class="navbar-icon" aria-label="切换全屏" @click="toggleFullscreen">
          <el-icon :size="18"><FullScreen /></el-icon>
        </button>
      </el-tooltip>
      <span class="navbar-divider" />
      <el-dropdown trigger="click" @command="handleCommand">
        <button type="button" class="user-chip">
          <span class="user-avatar">{{ avatarText }}</span>
          <span class="user-meta">
            <strong>{{ username }}</strong>
            <small>{{ roleLabel }}</small>
          </span>
          <el-icon class="user-arrow" :size="12"><ArrowDown /></el-icon>
        </button>
        <template #dropdown>
          <el-dropdown-menu class="user-dropdown">
            <div class="dropdown-profile">
              <span class="user-avatar">{{ avatarText }}</span>
              <div><strong>{{ username }}</strong><small>{{ roleLabel }}</small></div>
            </div>
            <el-dropdown-item command="password" divided>
              <el-icon><Lock /></el-icon>修改密码
            </el-dropdown-item>
            <el-dropdown-item command="logout">
              <el-icon><SwitchButton /></el-icon>退出登录
            </el-dropdown-item>
          </el-dropdown-menu>
        </template>
      </el-dropdown>
    </div>
  </header>
</template>

<style scoped>
.app-navbar {
  position: sticky;
  top: 0;
  z-index: 1000;
  display: flex;
  align-items: center;
  justify-content: space-between;
  height: var(--navbar-height);
  padding: 0 16px;
  background: #fff;
  box-shadow: 0 1px 4px rgb(0 21 41 / 8%);
}

.navbar-left,
.navbar-right {
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
}

.navbar-icon {
  display: grid;
  place-items: center;
  width: 36px;
  height: 36px;
  color: #5a6a7a;
  border: 0;
  border-radius: 6px;
  background: transparent;
  cursor: pointer;
  transition: background var(--transition-time), color var(--transition-time);
}

.navbar-icon:hover {
  color: var(--el-color-primary);
  background: var(--top-header-hover-color);
}

.navbar-breadcrumb {
  display: flex;
  align-items: center;
  gap: 7px;
  margin-left: 6px;
  font-size: 13px;
  white-space: nowrap;
}

.breadcrumb-link {
  padding: 0;
  color: #8a97a5;
  font-size: 13px;
  border: 0;
  background: transparent;
  cursor: pointer;
}

.breadcrumb-link:hover {
  color: var(--el-color-primary);
}

.breadcrumb-separator {
  color: #c5ccd4;
}

.breadcrumb-current {
  overflow: hidden;
  color: #303133;
  font-weight: 600;
  text-overflow: ellipsis;
}

.navbar-divider {
  width: 1px;
  height: 20px;
  margin: 0 6px;
  background: #e8ebef;
}

.user-chip {
  display: flex;
  align-items: center;
  gap: 9px;
  height: 40px;
  padding: 0 8px;
  border: 0;
  border-radius: 6px;
  background: transparent;
  cursor: pointer;
  transition: background var(--transition-time);
}

.user-chip:hover {
  background: var(--top-header-hover-color);
}

.user-avatar {
  display: grid;
  flex: 0 0 auto;
  place-items: center;
  width: 32px;
  height: 32px;
  border-radius: 50%;
  background: linear-gradient(135deg, #409eff, #36cfc9);
  color: #fff;
  font-size: 14px;
  font-weight: 700;
}

.user-meta {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 1px;
  line-height: 1.15;
}

.user-meta strong {
  max-width: 120px;
  overflow: hidden;
  color: #303133;
  font-size: 13px;
  font-weight: 600;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.user-meta small {
  color: #909399;
  font-size: 11px;
}

.user-arrow {
  color: #909399;
}

.dropdown-profile {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 14px 8px;
}

.dropdown-profile strong,
.dropdown-profile small {
  display: block;
}

.dropdown-profile strong {
  color: #303133;
  font-size: 13px;
}

.dropdown-profile small {
  margin-top: 2px;
  color: #909399;
  font-size: 11px;
}

@media (max-width: 700px) {
  .app-navbar {
    padding: 0 10px;
  }

  .navbar-breadcrumb {
    display: none;
  }

  .user-meta {
    display: none;
  }

  .navbar-divider {
    margin: 0 2px;
  }
}
</style>
