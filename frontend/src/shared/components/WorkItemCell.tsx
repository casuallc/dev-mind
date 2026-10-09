// 执行器列表「需求 / 工作单元」列：按后端补全的 WorkItemBrief 渲染，
// 需求编号可点击回链需求详情页；未关联（项目级记录）显示「-」。
import { Typography } from 'antd'
import { Link } from 'react-router-dom'
import type { ColumnType } from 'antd/es/table'
import type { WorkItemBrief } from '../types'

export default function WorkItemCell({ brief, projectId }: { brief?: WorkItemBrief | null; projectId: string }) {
  if (!brief) return <span>-</span>
  return (
    <div style={{ lineHeight: 1.5 }}>
      {brief.requirementId ? (
        <Link to={`/projects/${projectId}/requirements/${brief.requirementId}`} style={{ fontSize: 12 }}>
          {brief.requirementCode} {brief.requirementTitle ?? ''}
        </Link>
      ) : (
        <Typography.Text style={{ fontSize: 12 }}>{brief.requirementCode ?? '-'}</Typography.Text>
      )}
      <br />
      <Typography.Text type="secondary" style={{ fontSize: 12 }} title={brief.title}>
        {brief.code} {brief.title}
      </Typography.Text>
    </div>
  )
}

/** 表格列定义工厂：行类型需带 workItem 字段（后端补全），ellipsis 防长标题撑破布局 */
export function workItemColumn<T extends { workItem?: WorkItemBrief | null }>(projectId: string): ColumnType<T> {
  return {
    title: '需求 / 工作单元',
    dataIndex: 'workItem',
    width: 220,
    ellipsis: true,
    render: (brief: WorkItemBrief | null | undefined) => <WorkItemCell brief={brief} projectId={projectId} />,
  }
}
