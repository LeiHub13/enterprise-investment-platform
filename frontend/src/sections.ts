import type { Role } from './api'

export type SectionKey = 'enterprises' | 'projects' | 'leads' | 'users'
export type Option = { value: string | number; label: string }
export type Field = {
  key: string
  label: string
  kind?: 'number' | 'decimal' | 'date' | 'datetime' | 'text' | 'password' | 'select' | 'enterprise' | 'project' | 'user'
  options?: Option[]
  placeholder?: string
  required?: boolean
  max?: number
  min?: number
}
export type Column = { key: string; label: string; width?: number }
export type Section = {
  key: SectionKey
  label: string
  singular: string
  summary: string
  glyph: string
  columns: Column[]
  fields: Field[]
  filters?: Field[]
}

export const roleLabels: Record<Role, string> = {
  ADMIN: '系统管理员',
  DATA_STEWARD: '数据管理员',
  INVESTMENT_MANAGER: '招商经理',
  INVESTMENT_OFFICER: '招商专员',
}

export const roleOptions = Object.entries(roleLabels).map(([value, label]) => ({ value, label }))

export const leadStages: Option[] = [
  { value: 'NEW', label: '新线索' },
  { value: 'QUALIFIED', label: '已核实' },
  { value: 'CONTACTED', label: '已联系' },
  { value: 'NEGOTIATING', label: '洽谈中' },
  { value: 'WON', label: '已落地' },
  { value: 'LOST', label: '已流失' },
]

export const projectStatuses: Option[] = [
  { value: 'DRAFT', label: '筹备中' },
  { value: 'ACTIVE', label: '招商中' },
  { value: 'PAUSED', label: '已暂停' },
  { value: 'CLOSED', label: '已结束' },
]

export const followupFields: Field[] = [
  { key: 'followupType', label: '跟进方式', kind: 'select', options: [
    { value: 'PHONE', label: '电话' },
    { value: 'EMAIL', label: '邮件' },
    { value: 'MEETING', label: '面谈' },
    { value: 'OTHER', label: '其他' },
  ] },
  { key: 'nextFollowupAt', label: '下次跟进', kind: 'datetime' },
  { key: 'content', label: '沟通内容', kind: 'text', required: true, max: 15000 },
  { key: 'result', label: '沟通结果', kind: 'text', max: 15000 },
  { key: 'nextAction', label: '下一步行动', kind: 'text', max: 15000 },
]

export const sections: Record<SectionKey, Section> = {
  enterprises: {
    key: 'enterprises',
    label: '企业资源库',
    singular: '企业',
    summary: '按地区、行业和经营状态筛选企业，查看企业档案并导入招商资源。',
    glyph: '企',
    columns: [
      { key: 'name', label: '企业名称', width: 200 },
      { key: 'uscc', label: '统一社会信用代码', width: 180 },
      { key: 'region', label: '省 / 市', width: 95 },
      { key: 'industryName', label: '行业', width: 110 },
      { key: 'businessStatus', label: '经营状态', width: 95 },
      { key: 'registeredCapital', label: '注册资本（元）', width: 125 },
    ],
    fields: [
      { key: 'name', label: '企业名称', required: true, max: 255 },
      { key: 'uscc', label: '统一社会信用代码', max: 18 },
      { key: 'enterpriseType', label: '企业类型', max: 50 },
      { key: 'businessStatus', label: '经营状态', max: 30 },
      { key: 'provinceCode', label: '省份代码', max: 12 },
      { key: 'cityCode', label: '城市代码', max: 12 },
      { key: 'districtCode', label: '区县代码', max: 12 },
      { key: 'industryCode', label: '行业代码', max: 20 },
      { key: 'industryName', label: '行业名称', max: 100 },
      { key: 'registeredCapital', label: '注册资本（元）', kind: 'decimal', max: 19 },
      { key: 'establishedDate', label: '成立日期', kind: 'date' },
      { key: 'lastVerifiedAt', label: '最近核验', kind: 'datetime' },
      { key: 'website', label: '企业官网', max: 500 },
      { key: 'registeredAddress', label: '注册地址', max: 500 },
      { key: 'businessScope', label: '经营范围', kind: 'text' },
    ],
    filters: [
      { key: 'keyword', label: '企业名称或信用代码' },
      { key: 'provinceCode', label: '省份代码' },
      { key: 'cityCode', label: '城市代码' },
      { key: 'industryCode', label: '行业代码' },
      { key: 'businessStatus', label: '经营状态' },
    ],
  },
  projects: {
    key: 'projects',
    label: '招商项目',
    singular: '项目',
    summary: '维护目标地区、产业方向、投资规模和项目条件。',
    glyph: '项',
    columns: [
      { key: 'projectName', label: '项目名称', width: 200 },
      { key: 'projectStatus', label: '项目状态', width: 100 },
      { key: 'region', label: '省 / 市', width: 115 },
      { key: 'industryCode', label: '目标行业代码', width: 120 },
      { key: 'investmentRange', label: '投资规模（元）', width: 180 },
      { key: 'createdAt', label: '创建时间', width: 155 },
    ],
    fields: [
      { key: 'projectName', label: '项目名称', required: true, max: 255 },
      { key: 'projectStatus', label: '项目状态', kind: 'select', options: projectStatuses },
      { key: 'provinceCode', label: '招商省份代码', max: 12 },
      { key: 'cityCode', label: '招商城市代码', max: 12 },
      { key: 'industryCode', label: '目标行业代码', max: 20 },
      { key: 'ownerId', label: '负责人', kind: 'user' },
      { key: 'investmentMin', label: '最低投资（元）', kind: 'decimal', max: 19 },
      { key: 'investmentMax', label: '最高投资（元）', kind: 'decimal', max: 19 },
      { key: 'requirements', label: '招商条件', kind: 'text' },
    ],
    filters: [
      { key: 'keyword', label: '项目名称' },
      { key: 'projectStatus', label: '项目状态', kind: 'select', options: projectStatuses },
    ],
  },
  leads: {
    key: 'leads',
    label: '招商线索',
    singular: '线索',
    summary: '关联目标企业和招商项目，分配线索并记录每次沟通。',
    glyph: '线',
    columns: [
      { key: 'enterpriseName', label: '企业名称', width: 175 },
      { key: 'projectName', label: '招商项目', width: 145 },
      { key: 'leadStatus', label: '线索阶段', width: 100 },
      { key: 'ownerUsername', label: '负责人', width: 95 },
      { key: 'leadScore', label: '评分', width: 70 },
      { key: 'nextFollowupAt', label: '下次跟进', width: 155 },
    ],
    fields: [
      { key: 'enterpriseId', label: '关联企业', kind: 'enterprise', required: true },
      { key: 'projectId', label: '招商项目', kind: 'project' },
      { key: 'sourceChannel', label: '来源渠道', max: 50 },
      { key: 'leadStatus', label: '线索阶段', kind: 'select', options: leadStages },
      { key: 'ownerId', label: '负责人', kind: 'user' },
      { key: 'leadScore', label: '线索评分', kind: 'number', min: 0, max: 100 },
      { key: 'nextFollowupAt', label: '下次跟进', kind: 'datetime' },
    ],
    filters: [
      { key: 'keyword', label: '企业或项目名称' },
      { key: 'leadStatus', label: '线索阶段', kind: 'select', options: leadStages },
      { key: 'due', label: '跟进时间', kind: 'select', options: [
        { value: 'overdue', label: '已逾期' },
        { value: 'today', label: '今日待跟进' },
        { value: 'upcoming', label: '近七天待跟进' },
      ] },
    ],
  },
  users: {
    key: 'users',
    label: '账号管理',
    singular: '账号',
    summary: '创建内部账号，并按岗位分配企业数据或招商操作权限。',
    glyph: '员',
    columns: [
      { key: 'id', label: '账号编号', width: 100 },
      { key: 'username', label: '用户名', width: 200 },
      { key: 'role', label: '角色', width: 140 },
      { key: 'enabled', label: '状态', width: 100 },
    ],
    fields: [
      { key: 'username', label: '用户名', required: true, max: 64, placeholder: '3 至 64 位字母、数字、点、下划线或短横线' },
      { key: 'password', label: '初始密码', kind: 'password', required: true, placeholder: '至少 12 个字符，最多 72 个 UTF-8 字节' },
      { key: 'role', label: '角色', kind: 'select', options: roleOptions, required: true },
    ],
  },
}
