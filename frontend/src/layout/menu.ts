import type { Component } from 'vue'
import {
  Aim, Avatar, Briefcase, ChatDotRound, Connection, DataAnalysis, DataLine, Document,
  Files, Lock, MagicStick, Money, Monitor, Odometer, OfficeBuilding, Promotion, Setting,
  Tickets, TrendCharts, Upload, User,
} from '@element-plus/icons-vue'

export type MenuNode = {
  key: string
  label: string
  icon: Component
  children?: MenuNode[]
}

export type FlatMenuItem = { key: string; label: string; group: string }

type MenuBlueprint = {
  key: string
  label: string
  icon: Component
  alwaysShow?: boolean
  children?: MenuBlueprint[]
}

const BLUEPRINT: MenuBlueprint[] = [
  {
    key: 'dir-overview', label: '概览', icon: Odometer,
    children: [
      { key: 'dashboard', label: '招商工作台', icon: Monitor },
      { key: 'statistics', label: '业绩统计', icon: DataLine },
    ],
  },
  {
    key: 'dir-crm', label: 'CRM业务模块', icon: Briefcase,
    children: [
      {
        key: 'dir-crm-customer', label: '客户管理', icon: User,
        children: [
          { key: 'enterprises', label: '企业客户', icon: OfficeBuilding },
          { key: 'leads', label: '招商线索', icon: Aim },
        ],
      },
      {
        key: 'dir-crm-opportunity', label: '项目商机', icon: Connection,
        children: [
          { key: 'projects', label: '招商项目', icon: Files },
          { key: 'businesses', label: '商机管道', icon: TrendCharts },
        ],
      },
      {
        key: 'dir-crm-contract', label: '合同回款', icon: Tickets,
        children: [
          { key: 'contracts', label: '招商合同', icon: Document },
          { key: 'receivables', label: '回款管理', icon: Money },
        ],
      },
    ],
  },
  {
    key: 'dir-bidradar', label: '标讯雷达模块', icon: Promotion,
    children: [
      { key: 'bidradar', label: '标讯雷达', icon: Promotion },
    ],
  },
  {
    key: 'dir-collector', label: '数据采集', icon: Upload, alwaysShow: true,
    children: [
      { key: 'collector', label: '采集器', icon: Upload },
    ],
  },
  {
    key: 'dir-agents', label: '智能招商', icon: MagicStick,
    children: [
      { key: 'agent', label: '招商Agent', icon: MagicStick },
    ],
  },
  {
    key: 'dir-openclaw', label: 'OpenClaw 模块', icon: ChatDotRound,
    children: [
      { key: 'openclaw', label: 'OpenClaw 助手', icon: ChatDotRound },
    ],
  },
  {
    key: 'dir-system', label: '系统管理', icon: Setting,
    children: [
      { key: 'users', label: '用户管理', icon: Avatar },
      { key: 'data', label: '数据管理', icon: DataAnalysis },
      { key: 'roles', label: '角色权限', icon: Lock },
    ],
  },
]

const LEAF_ICONS: Record<string, Component> = Object.fromEntries(
  BLUEPRINT.flatMap(node => collectLeaves(node)).map(node => [node.key, node.icon]),
)

function collectLeaves(node: MenuBlueprint): MenuBlueprint[] {
  if (!node.children?.length) return [node]
  return node.children.flatMap(collectLeaves)
}

function buildNode(blueprint: MenuBlueprint, visible: Map<string, FlatMenuItem>): MenuNode | null {
  if (!blueprint.children?.length) {
    const item = visible.get(blueprint.key)
    if (!item) return null
    return { key: blueprint.key, label: item.label, icon: blueprint.icon }
  }
  const children = blueprint.children
    .map(child => buildNode(child, visible))
    .filter((node): node is MenuNode => node !== null)
  if (!children.length) return null
  // 芋道规则：目录下仅一个可见子级且未设 alwaysShow 时，折叠为单个菜单项
  if (children.length === 1 && !blueprint.alwaysShow && !children[0].children?.length) {
    return children[0]
  }
  return { key: blueprint.key, label: blueprint.label, icon: blueprint.icon, children }
}

export function buildMenuTree(items: FlatMenuItem[]): MenuNode[] {
  const visible = new Map(items.map(item => [item.key, item]))
  const tree = BLUEPRINT
    .map(node => buildNode(node, visible))
    .filter((node): node is MenuNode => node !== null)
  const known = new Set(BLUEPRINT.flatMap(collectLeaves).map(n => n.key))
  const extras = items.filter(item => !known.has(item.key))
  const extraGroups = new Map<string, MenuNode>()
  for (const item of extras) {
    const group = item.group || '其他'
    let dir = extraGroups.get(group)
    if (!dir) {
      dir = { key: `dir-extra-${group}`, label: group, icon: Setting, children: [] }
      extraGroups.set(group, dir)
      tree.push(dir)
    }
    dir.children!.push({ key: item.key, label: item.label, icon: LEAF_ICONS[item.key] ?? Monitor })
  }
  return tree
}

export function findOpenChain(tree: MenuNode[], key: string, parents: string[] = []): string[] {
  for (const node of tree) {
    if (node.key === key) return parents
    if (node.children?.length) {
      const chain = findOpenChain(node.children, key, [...parents, node.key])
      if (chain.length) return chain
    }
  }
  return []
}
