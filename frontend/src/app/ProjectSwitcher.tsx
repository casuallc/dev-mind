// 项目切换器：像切换租户一样切换当前项目。渲染于 ProjectSubNav 二级页签条右端（仅项目上下文页）——
// 工作台/问答/工作日志等个人域页面没有项目切换语义，且不放顶部导航以保证一级 tab 位置恒定。
// 纯展示组件：列表加载与 currentId 兜底在 useProjectBootstrap（AppLayout 常驻）。
// WORKLOG 空间不进切换选项（工作日志从顶部导航进，会话/知识收在 /worklog 页内视图）。
import { Button, Select, Tag, Typography } from 'antd'
import { useNavigate } from 'react-router-dom'
import { setCurrentProject } from './currentProjectStore'
import { useCurrentProjectId } from './useCurrentProject'
import type { Project } from '../features/projects/types'

interface Props {
  projects: Project[]
  loadError: boolean
  onRetry: () => void
}

export default function ProjectSwitcher({ projects, loadError, onRetry }: Props) {
  const navigate = useNavigate()
  const currentId = useCurrentProjectId()

  // WORKLOG 空间不参与切换（每用户一个、内容与人绑定，切换语义空转）
  const switchable = projects.filter((p) => p.kind !== 'WORKLOG')
  const current = switchable.find((p) => p.id === currentId)

  const switchTo = (id: string) => {
    setCurrentProject(id)
    // 各子页面数据都随项目变，停留在原页面会经历「旧数据→刷新」闪烁，回概览等同切换租户回主页
    navigate('/overview')
  }

  if (loadError) {
    return (
      <div style={{ width: 200 }}>
        <Button size="small" block onClick={onRetry}>
          项目列表加载失败，重试
        </Button>
      </div>
    )
  }

  return (
    <div style={{ width: 200 }}>
      <Select
        size="middle"
        showSearch
        optionFilterProp="label"
        style={{ width: '100%' }}
        placeholder={switchable.length ? '选择项目' : '暂无项目'}
        disabled={!switchable.length}
        // 当前 id 不在可选项（历史残留的 WORKLOG 空间态）时不回显，下拉选普通项目即恢复
        value={current ? currentId : undefined}
        onChange={switchTo}
        options={switchable.map((p) => ({
          value: p.id,
          label: p.name,
          archived: p.status !== 'ACTIVE',
        }))}
        optionRender={(opt) => (
          <span>
            {opt.data.label}
            {opt.data.archived && (
              <Tag style={{ marginLeft: 6 }}>ARCHIVED</Tag>
            )}
          </span>
        )}
        popupRender={(menu) => (
          <>
            {menu}
            <div style={{ padding: '4px 12px 8px', borderTop: '1px solid #f0f0f0' }}>
              <Typography.Link style={{ fontSize: 12 }} onClick={() => navigate('/projects')}>
                查看全部项目
              </Typography.Link>
            </div>
          </>
        )}
      />
    </div>
  )
}
