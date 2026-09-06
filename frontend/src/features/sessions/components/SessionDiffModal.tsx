// Worktree Diff 弹窗：受控组件，SessionsBoard 操作条与 SessionDetail 共用。
import { Empty, Modal, Typography } from 'antd'
import type { DiffView } from '../types'

export default function SessionDiffModal({
  open,
  diff,
  onClose,
}: {
  open: boolean
  diff: DiffView | null
  onClose: () => void
}) {
  return (
    <Modal title="Worktree Diff" open={open} onCancel={onClose} footer={null} width={720}>
      {diff && (
        <>
          {!diff.hasChanges && <Empty description="无变更" />}
          {diff.files.length > 0 && (
            <>
              <Typography.Title level={5} style={{ marginTop: 0 }}>
                变更文件
              </Typography.Title>
              <pre style={{ whiteSpace: 'pre-wrap', background: '#f6f6f6', padding: 8, borderRadius: 4, fontSize: 12 }}>
                {diff.stat || diff.files.join('\n')}
              </pre>
            </>
          )}
        </>
      )}
    </Modal>
  )
}
