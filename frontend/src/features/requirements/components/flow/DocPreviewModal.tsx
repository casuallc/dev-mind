// CAP-37 FR-04：文档内容预览弹窗（Markdown 渲染，DesignsTab/FlowTab 共用）。
import { Modal } from 'antd'
import Markdown from '../../../../shared/components/Markdown'
import type { DocPreview } from './useDesignActions'

export default function DocPreviewModal({ preview, onClose }: {
  preview: DocPreview | null
  onClose: () => void
}) {
  return (
    <Modal title={preview?.title} open={!!preview} onCancel={onClose} footer={null} width={860}>
      <div style={{ maxHeight: '65vh', overflow: 'auto' }}>
        <Markdown content={preview?.content || ''} />
      </div>
    </Modal>
  )
}
