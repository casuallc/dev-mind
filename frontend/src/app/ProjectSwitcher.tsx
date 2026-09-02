// 项目切换器：像切换租户一样切换当前项目。常驻 AppLayout 侧边栏顶部，
// 是项目列表的唯一加载点——挂载时校验 currentId 有效性并做兜底自动选择。
import { useCallback, useEffect, useState } from 'react'
import { Button, Select, Tag, Typography } from 'antd'
import { useNavigate } from 'react-router-dom'
import { listProjects } from '../features/projects/api'
import {
  getCurrentProjectId,
  setCurrentProject,
  setProjectsLoaded,
} from './currentProjectStore'
import { useCurrentProjectId } from './useCurrentProject'
import type { Project } from '../features/projects/types'

export default function ProjectSwitcher() {
  const navigate = useNavigate()
  const currentId = useCurrentProjectId()
  const [projects, setProjects] = useState<Project[]>([])
  const [loadError, setLoadError] = useState(false)

  const load = useCallback(async () => {
    try {
      const list = await listProjects()
      setProjects(list)
      setLoadError(false)
      // 兜底：无当前项目，或持久化的 id 已失效（项目被删）→ 自动切到第一个 ACTIVE 项目
      const valid = getCurrentProjectId()
      if (!valid || !list.some((p) => p.id === valid)) {
        const first = list.find((p) => p.status === 'ACTIVE') ?? null
        setCurrentProject(first?.id ?? null)
      }
    } catch {
      // 加载失败不清空已持久化的 currentId，避免一次网络抖动冲掉用户上下文
      setLoadError(true)
    } finally {
      setProjectsLoaded(true)
    }
  }, [])

  useEffect(() => {
    load()
  }, [load])

  const switchTo = (id: string) => {
    setCurrentProject(id)
    // 各子页面数据都随项目变，停留在原页面会经历「旧数据→刷新」闪烁，回概览等同切换租户回主页
    navigate('/overview')
  }

  if (loadError) {
    return (
      <div style={{ padding: '0 12px 12px' }}>
        <Button size="small" block onClick={load}>
          项目列表加载失败，重试
        </Button>
      </div>
    )
  }

  return (
    <div style={{ padding: '0 12px 12px' }}>
      <Select
        size="middle"
        showSearch
        optionFilterProp="label"
        style={{ width: '100%' }}
        placeholder={projects.length ? '选择项目' : '暂无项目'}
        disabled={!projects.length}
        value={currentId ?? undefined}
        onChange={switchTo}
        options={projects.map((p) => ({
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
