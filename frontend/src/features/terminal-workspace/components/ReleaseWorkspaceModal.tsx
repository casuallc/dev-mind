// CAP-115 M3 终端工作区释放确认弹窗：丢弃语义（不合并不 push，runner 删 worktree + 本地分支）。
// 脏工作区默认被服务端 409 拒绝（列前 3 个改动文件），勾选「丢弃未提交改动」才强制放行。
import { useState } from 'react'
import { Alert, Checkbox, Modal, Typography, message } from 'antd'
import { releaseTerminalWorkspace } from '../api'
import { showError } from '../../../shared/utils/showError'
import type { TerminalWorkspaceView } from '../types'

interface Props {
  ws: TerminalWorkspaceView
  open: boolean
  onCancel: () => void
  onDone: () => void
}

export default function ReleaseWorkspaceModal({ ws, open, onCancel, onDone }: Props) {
  const [discardChanges, setDiscardChanges] = useState(false)
  const [submitting, setSubmitting] = useState(false)

  const onOk = async () => {
    setSubmitting(true)
    try {
      await releaseTerminalWorkspace(ws.id, discardChanges)
      message.success(`工作区 ${ws.workspaceKey} 已释放`)
      onDone()
    } catch (e) {
      showError(e, '释放失败（工作区已保留，可处理后重试）')
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <Modal
      title={`释放终端工作区 ${ws.workspaceKey}`}
      open={open}
      centered
      okText="确认释放"
      okButtonProps={{ danger: true }}
      cancelText="取消"
      confirmLoading={submitting}
      onOk={() => void onOk()}
      onCancel={onCancel}
      destroyOnHidden
    >
      <Alert
        type="error"
        showIcon
        style={{ marginBottom: 12 }}
        message={
          <>
            释放 = 丢弃：不合并不 push，节点上的 worktree 与本地分支{' '}
            <Typography.Text code>{ws.branch}</Typography.Text> 将被删除，改动不可恢复。
            存在未提交改动时服务端会拒绝，需显式勾选下方「丢弃未提交改动」。
          </>
        }
      />
      <Checkbox checked={discardChanges} onChange={(e) => setDiscardChanges(e.target.checked)}>
        丢弃未提交改动（强制释放）
      </Checkbox>
    </Modal>
  )
}
