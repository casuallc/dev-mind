// CAP-37 FR-04：文档内容预览抽屉（Markdown 渲染，DesignsTab/FlowTab 共用）。
import { Drawer } from 'antd'
import Markdown from '../../../../shared/components/Markdown'
import { DRAWER_WIDE } from '../../../../shared/utils/overlay'
import type { DocPreview } from './useDesignActions'

export default function DocPreviewDrawer({ preview, onClose }: {
  preview: DocPreview | null
  onClose: () => void
}) {
  return (
    <Drawer title={preview?.title} open={!!preview} onClose={onClose} width={DRAWER_WIDE}>
      <Markdown content={preview?.content || ''} />
    </Drawer>
  )
}
