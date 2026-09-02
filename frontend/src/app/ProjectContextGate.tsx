// 项目上下文门控：包住所有「当前项目」路由，无可用项目时渲染统一空态。
// 等项目列表加载完（projectsLoaded）再判空，避免首访闪空态。
import { Button, Empty, Spin } from 'antd'
import { Outlet, useNavigate } from 'react-router-dom'
import { useSyncExternalStore } from 'react'
import {
  getCurrentProjectId,
  getProjectsLoaded,
  subscribeCurrentProject,
} from './currentProjectStore'
import { isAdmin } from '../features/auth/authStore'

export default function ProjectContextGate() {
  const navigate = useNavigate()
  // 同时订阅 currentId 与 projectsLoaded（同一 store，任一变化都触发重渲染）
  useSyncExternalStore(subscribeCurrentProject, getCurrentProjectId)

  if (!getProjectsLoaded()) {
    return (
      <div style={{ display: 'flex', justifyContent: 'center', paddingTop: 120 }}>
        <Spin />
      </div>
    )
  }
  if (!getCurrentProjectId()) {
    return (
      <Empty
        style={{ paddingTop: 120 }}
        description="暂无项目，请联系管理员在后台注册项目"
      >
        {isAdmin() && (
          <Button type="primary" onClick={() => navigate('/admin/projects')}>
            去后台注册项目
          </Button>
        )}
      </Empty>
    )
  }
  return <Outlet />
}
