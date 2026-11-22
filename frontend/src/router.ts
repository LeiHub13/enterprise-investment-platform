import { defineComponent } from 'vue'
import { ElMessage } from 'element-plus'
import { createRouter, createWebHistory } from 'vue-router'
import { getToken } from './api'
import { auth, loadUser, permitted, setUser } from './auth'

declare module 'vue-router' {
  interface RouteMeta {
    public?: boolean
    permission?: string
    adminOnly?: boolean
  }
}

const RouteView = defineComponent({ render: () => null })

const routes = [
  { path: '/', redirect: '/dashboard' },
  { path: '/login', name: 'login', component: RouteView, meta: { public: true } },
  { path: '/dashboard', name: 'dashboard', component: RouteView, meta: { permission: 'dashboard:view' } },
  { path: '/enterprises', name: 'enterprises', component: RouteView, meta: { permission: 'enterprise:view' } },
  { path: '/projects', name: 'projects', component: RouteView, meta: { permission: 'project:view' } },
  { path: '/leads', name: 'leads', component: RouteView, meta: { permission: 'lead:view' } },
  { path: '/observations', name: 'observations', component: RouteView, meta: { permission: 'lead:view' } },
  { path: '/users', name: 'users', component: RouteView, meta: { permission: 'user:manage' } },
  { path: '/data', name: 'data', component: RouteView, meta: { permission: 'governance:view' } },
  { path: '/collector', name: 'collector', component: RouteView, meta: { permission: 'governance:view' } },
  { path: '/roles', name: 'roles', component: RouteView, meta: { permission: 'system:role:manage' } },
  { path: '/businesses', name: 'businesses', component: RouteView, meta: { permission: 'business:view' } },
  { path: '/contracts', name: 'contracts', component: RouteView, meta: { permission: 'contract:view' } },
  { path: '/receivables', name: 'receivables', component: RouteView, meta: { permission: 'receivable:view' } },
  { path: '/statistics', name: 'statistics', component: RouteView, meta: { permission: 'statistics:view' } },
  { path: '/bidradar', name: 'bidradar', component: RouteView, meta: { permission: 'bidradar:view' } },
  { path: '/agent', name: 'agent', component: RouteView, meta: { permission: 'agent:use' } },
  { path: '/openclaw', name: 'openclaw', component: RouteView, meta: { adminOnly: true } },
  { path: '/:pathMatch(.*)*', redirect: '/dashboard' },
]

const router = createRouter({
  history: createWebHistory(),
  routes,
})

router.beforeEach(async (to) => {
  if (to.meta.public) {
    if (to.name === 'login' && !getToken()) setUser(null)
    return true
  }
  if (!getToken()) {
    setUser(null)
    return { name: 'login', query: { redirect: to.fullPath } }
  }
  if (!auth.loaded) await loadUser()
  if (!auth.user) {
    setUser(null)
    return { name: 'login', query: { redirect: to.fullPath } }
  }
  if (to.meta.adminOnly && auth.user.role !== 'ADMIN') {
    ElMessage.warning('没有访问权限')
    return { name: 'dashboard' }
  }
  if (to.meta.permission && !permitted(to.meta.permission)) {
    ElMessage.warning('没有访问权限')
    return { name: 'dashboard' }
  }
  return true
})

export default router
