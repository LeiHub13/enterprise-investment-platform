import type { Role } from './api'

export type Permission =
  | 'dashboard:view'
  | 'enterprise:view'
  | 'enterprise:write'
  | 'enterprise:import'
  | 'enterprise:tag:write'
  | 'project:view'
  | 'project:write'
  | 'lead:view'
  | 'lead:create'
  | 'lead:update'
  | 'lead:delete'
  | 'lead:assign'
  | 'lead:view-all'
  | 'contact:view'
  | 'contact:write'
  | 'contact:delete'
  | 'business:view'
  | 'business:create'
  | 'business:update'
  | 'business:delete'
  | 'contract:view'
  | 'contract:create'
  | 'contract:update'
  | 'contract:delete'
  | 'receivable:view'
  | 'receivable:create'
  | 'receivable:update'
  | 'receivable:delete'
  | 'statistics:view'
  | 'bidradar:view'
  | 'agent:use'
  | 'governance:view'
  | 'monitor:manage'
  | 'user:view'
  | 'user:manage'
  | 'system:role:manage'

const ROLE_PERMISSIONS: Record<Role, Permission[]> = {
  ADMIN: [
    'dashboard:view',
    'enterprise:view', 'enterprise:write', 'enterprise:import', 'enterprise:tag:write',
    'project:view', 'project:write',
    'lead:view', 'lead:create', 'lead:update', 'lead:delete', 'lead:assign', 'lead:view-all',
    'contact:view', 'contact:write', 'contact:delete',
    'business:view', 'business:create', 'business:update', 'business:delete',
    'contract:view', 'contract:create', 'contract:update', 'contract:delete',
    'receivable:view', 'receivable:create', 'receivable:update', 'receivable:delete',
    'statistics:view', 'bidradar:view', 'agent:use',
    'governance:view', 'monitor:manage',
    'user:view', 'user:manage', 'system:role:manage',
  ],
  DATA_STEWARD: [
    'dashboard:view',
    'enterprise:view', 'enterprise:write', 'enterprise:import', 'enterprise:tag:write',
    'governance:view',
  ],
  INVESTMENT_MANAGER: [
    'dashboard:view',
    'enterprise:view',
    'project:view', 'project:write',
    'lead:view', 'lead:create', 'lead:update', 'lead:delete', 'lead:assign', 'lead:view-all',
    'contact:view', 'contact:write', 'contact:delete',
    'business:view', 'business:create', 'business:update', 'business:delete',
    'contract:view', 'contract:create', 'contract:update', 'contract:delete',
    'receivable:view', 'receivable:create', 'receivable:update', 'receivable:delete',
    'statistics:view', 'bidradar:view', 'agent:use',
    'user:view',
  ],
  INVESTMENT_OFFICER: [
    'dashboard:view',
    'enterprise:view',
    'project:view',
    'lead:view', 'lead:create', 'lead:update',
    'contact:view', 'contact:write',
    'business:view', 'business:create', 'business:update',
    'contract:view', 'receivable:view',
    'statistics:view', 'bidradar:view', 'agent:use',
  ],
}

export function permissionsForRole(role?: Role | null): Set<string> {
  return new Set(role ? ROLE_PERMISSIONS[role] ?? [] : [])
}

export function hasPermission(permissions: Set<string>, code: string): boolean {
  return permissions.has('*') || permissions.has(code)
}
