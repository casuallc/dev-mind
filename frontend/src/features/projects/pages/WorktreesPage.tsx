// Worktree 页（/worktrees）：当前项目的活跃 worktree 管理。
import { useCallback, useEffect, useState } from 'react'
import { Card } from 'antd'
import WorktreesTab from '../components/detail/WorktreesTab'
import { listWorktrees } from '../api'
import { useCurrentProjectId } from '../hooks/useCurrentProject'
import type { WorktreeInfo } from '../types'

export default function WorktreesPage() {
  const projectId = useCurrentProjectId()
  const [worktrees, setWorktrees] = useState<WorktreeInfo[]>([])

  const load = useCallback(() => {
    if (!projectId) return
    listWorktrees(projectId)
      .then(setWorktrees)
      .catch(() => setWorktrees([]))
  }, [projectId])

  useEffect(load, [load])

  return (
    <Card size="small" title="Worktree">
      <WorktreesTab worktrees={worktrees} onRefresh={load} />
    </Card>
  )
}
