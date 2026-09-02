// 需求主线共享常量（列表卡与详情页共用）。
import type { RequirementSource, RequirementStatus, RequirementType } from '../types'

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
