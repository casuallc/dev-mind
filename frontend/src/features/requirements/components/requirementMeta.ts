// 需求主线共享常量（列表卡与详情页共用）。
import type { DesignStatus, RequirementSource, RequirementStatus, RequirementType, WorkItemStatus, WorkItemType } from '../types'

export const STATUS_FLOW: RequirementStatus[] =
  ['DRAFT', 'ANALYZING', 'DESIGNING', 'IN_PROGRESS', 'ACCEPTANCE', 'DONE']
export const ALL_STATUSES: RequirementStatus[] = [...STATUS_FLOW, 'CANCELLED']

export const ALL_TYPES: RequirementType[] = ['FEATURE', 'BUG', 'IMPROVEMENT', 'TASK']

export const TYPE_LABEL: Record<RequirementType, string> = {
  FEATURE: '功能',
  BUG: '缺陷',
  IMPROVEMENT: '优化',
  TASK: '任务',
}

/** 需求状态中文标签（存储值仍为英文枚举，仅显示层映射） */
export const STATUS_LABEL: Record<RequirementStatus, string> = {
  DRAFT: '草稿',
  ANALYZING: '分析中',
  DESIGNING: '方案设计',
  IN_PROGRESS: '实施中',
  ACCEPTANCE: '待验收',
  DONE: '已完成',
  CANCELLED: '已取消',
}

export const SOURCE_LABEL: Record<RequirementSource, string> = {
  JIRA: 'Jira',
  LOCAL: '自建',
}

export function sourceTagColor(s: RequirementSource | string): string {
  return s === 'JIRA' ? 'blue' : 'default'
}

/** 优先级词表（对齐 Jira；存字符串保持开放，表单 Select 用此词表） */
export const ALL_PRIORITIES = ['Highest', 'High', 'Medium', 'Low', 'Lowest'] as const

export function priorityColor(p?: string): string {
  switch (p) {
    case 'Highest': return 'red'
    case 'High': return 'volcano'
    case 'Medium': return 'gold'
    case 'Low': return 'blue'
    case 'Lowest': return 'default'
    default: return 'default'
  }
}

export function requirementTypeColor(t: RequirementType | string): string {
  switch (t) {
    case 'FEATURE': return 'blue'
    case 'BUG': return 'red'
    case 'IMPROVEMENT': return 'green'
    case 'TASK': return 'default'
    default: return 'default'
  }
}

export function requirementStatusColor(s: RequirementStatus | string): string {
  switch (s) {
    case 'DRAFT': return 'default'
    case 'ANALYZING': return 'geekblue'
    case 'DESIGNING': return 'cyan'
    case 'IN_PROGRESS': return 'blue'
    case 'ACCEPTANCE': return 'purple'
    case 'DONE': return 'green'
    case 'CANCELLED': return 'red'
    default: return 'default'
  }
}

/** CAP-38：工作单元状态中文标签（存储值仍为英文枚举，仅显示层映射） */
export const WI_STATUS_LABEL: Record<WorkItemStatus, string> = {
  TODO: '待办',
  IN_PROGRESS: '进行中',
  BLOCKED: '阻塞',
  DONE: '已完成',
  CANCELLED: '已取消',
}

export const WI_TYPE_LABEL: Record<WorkItemType, string> = {
  DESIGN: '方案设计',
  DEVELOPMENT: '开发',
  TEST: '测试',
  DOCUMENT: '文档',
  REVIEW: '评审',
}

/** 方案状态中文标签（CAP-38 起 CONFIRMED 为纯标记，不再门控拆分） */
export const DESIGN_STATUS_LABEL: Record<DesignStatus, string> = {
  DRAFT: '草稿',
  CONFIRMED: '已确认',
  DISCARDED: '已废弃',
}

export function workItemStatusColor(s: WorkItemStatus | string): string {
  switch (s) {
    case 'TODO': return 'default'
    case 'IN_PROGRESS': return 'blue'
    case 'BLOCKED': return 'orange'
    case 'DONE': return 'green'
    case 'CANCELLED': return 'red'
    default: return 'default'
  }
}

export function workItemTypeColor(t: WorkItemType | string): string {
  switch (t) {
    case 'DESIGN': return 'cyan'
    case 'DEVELOPMENT': return 'blue'
    case 'TEST': return 'orange'
    case 'DOCUMENT': return 'green'
    case 'REVIEW': return 'purple'
    default: return 'default'
  }
}
