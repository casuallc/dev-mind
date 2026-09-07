// 会话 Diff 弹窗：CAP-31 起按库分组（每库独立 stat/文件清单/错误行）。
// 受控组件，SessionsBoard 操作条与 SessionDetail 共用。
import { Alert, Empty, Modal, Space, Tag, Typography } from 'antd'
import type { RepoDiffView } from '../types'

export default function SessionDiffModal({
  open,
  diff,
  onClose,
}: {
  open: boolean
  diff: RepoDiffView[] | null
  onClose: () => void
}) {
  return (
    <Modal title="会话 Diff" open={open} onCancel={onClose} footer={null} width={760}>
      {diff && diff.length === 0 && (
        <Empty description="无工作区可 diff（会话未关联仓库或 worktree 已清理）" />
      )}
      {diff?.map((d, i) => (
        <div key={`${d.repoName}-${i}`} style={{ marginBottom: i < diff.length - 1 ? 16 : 0 }}>
          <Space size={8} style={{ marginBottom: 8 }}>
            <Typography.Text strong>{d.repoName}</Typography.Text>
            {d.primary && <Tag color="blue">主库</Tag>}
            {diff.length > 1 && <Tag>{`${i + 1}/${diff.length}`}</Tag>}
          </Space>
          {d.error ? (
            <Alert type="warning" showIcon message={d.error} />
          ) : !d.hasChanges ? (
            <Empty description="无变更" image={Empty.PRESENTED_IMAGE_SIMPLE} />
          ) : (
            <pre
              style={{
                whiteSpace: 'pre-wrap',
                background: '#f6f6f6',
                padding: 8,
                borderRadius: 4,
                fontSize: 12,
                margin: 0,
              }}
            >
              {d.stat || d.files.join('\n')}
            </pre>
          )}
        </div>
      ))}
    </Modal>
  )
}
