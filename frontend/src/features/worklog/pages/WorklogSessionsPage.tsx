// CAP-41 工作日志空间会话页：复用会话工作台但锁定 WORKLOG 项目，
// 停留在「工作日志」个人域（顶部导航高亮不变、不进项目上下文页签），不再 setCurrentProject 跳项目。
import { useEffect, useState } from 'react'
import { Button, Result, Spin } from 'antd'
import { useNavigate } from 'react-router-dom'
import SessionsBoard from '../../sessions/pages/SessionsBoard'
import { ensureWorkspace } from '../api'
import { showError } from '../../../shared/utils/showError'

export default function WorklogSessionsPage() {
  const navigate = useNavigate()
  const [projectId, setProjectId] = useState<string | null>(null)
  const [failed, setFailed] = useState(false)

  // ensure 幂等懒创建：直接访问本页而未初始化空间时也能落进来
  useEffect(() => {
    let alive = true
    ensureWorkspace()
      .then((w) => {
        if (alive) setProjectId(w.projectId ?? null)
      })
      .catch((e) => {
        if (!alive) return
        setFailed(true)
        showError(e, '初始化工作日志空间失败')
      })
    return () => {
      alive = false
    }
  }, [])

  if (failed) {
    return (
      <Result
        status="error"
        title="工作日志空间不可用"
        extra={
          <Button type="primary" onClick={() => navigate('/worklog')}>
            返回工作日志
          </Button>
        }
      />
    )
  }
  if (!projectId) {
    return (
      <div style={{ display: 'flex', justifyContent: 'center', paddingTop: 96 }}>
        <Spin size="large" />
      </div>
    )
  }
  return <SessionsBoard projectId={projectId} title="日志空间会话" />
}
