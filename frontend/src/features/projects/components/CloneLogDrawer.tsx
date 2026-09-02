// CAP-23 克隆日志 Drawer：REST 回填历史 + CLONING 时 WS /ws/repo-clones/clone-<repoId> 实时流。
// 结构照 builds/BuildsPage 的 LogDrawer。
import { useEffect, useState } from 'react'
import { Alert, Drawer, Space, Tag } from 'antd'
import { getCloneLogs } from '../api'
import type { ProjectRepo } from '../types'

export const CLONE_STATUS_COLOR: Record<string, string> = {
  NONE: 'default',
  CLONING: 'processing',
  READY: 'success',
  FAILED: 'error',
}

interface Props {
  projectId: string
  /** null 关闭 */
  repo: ProjectRepo | null
  onClose: () => void
  /** 收到 done 帧时通知父组件刷新列表 */
  onDone: () => void
}

export default function CloneLogDrawer({ projectId, repo, onClose, onDone }: Props) {
  const [text, setText] = useState('')
  const [connected, setConnected] = useState(false)

  useEffect(() => {
    if (!repo) {
      setText('')
      setConnected(false)
      return
    }
    setText('')
    setConnected(false)
    getCloneLogs(projectId, repo.id)
      .then((r) => setText(r.logs))
      .catch(() => setText(''))

    if (repo.cloneStatus === 'CLONING') {
      const proto = location.protocol === 'https:' ? 'wss' : 'ws'
      const ws = new WebSocket(
        `${proto}://${location.host}/ws/repo-clones/${`clone-${repo.id}`}`,
      )
      ws.onopen = () => setConnected(true)
      ws.onmessage = (msg) => {
        try {
          const f = JSON.parse(msg.data)
          if (f.type === 'snapshot') {
            setText(f.logs ?? '')
          } else if (f.type === 'log') {
            setText((t) => (t ? `${t}\n${f.line}` : f.line))
          } else if (f.type === 'done') {
            setConnected(false)
            ws.close()
            onDone()
          }
        } catch {
          /* 忽略坏帧 */
        }
      }
      ws.onclose = () => setConnected(false)
      return () => ws.close()
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [repo, projectId])

  return (
    <Drawer
      title={
        repo ? (
          <Space>
            <span>克隆日志 · {repo.name}</span>
            {repo.cloneStatus && (
              <Tag color={CLONE_STATUS_COLOR[repo.cloneStatus]}>{repo.cloneStatus}</Tag>
            )}
            {connected && <Tag color="cyan">实时</Tag>}
          </Space>
        ) : (
          '克隆日志'
        )
      }
      width={720}
      open={!!repo}
      onClose={onClose}
    >
      {repo?.cloneError && (
        <Alert type="error" showIcon style={{ marginBottom: 12 }} message={repo.cloneError} />
      )}
      <pre
        style={{
          background: '#0f1115',
          color: '#d0d7de',
          padding: 12,
          borderRadius: 6,
          fontSize: 12,
          lineHeight: 1.6,
          maxHeight: 'calc(100vh - 200px)',
          overflow: 'auto',
          whiteSpace: 'pre-wrap',
          wordBreak: 'break-all',
        }}
      >
        {text || '（暂无日志）'}
      </pre>
    </Drawer>
  )
}
