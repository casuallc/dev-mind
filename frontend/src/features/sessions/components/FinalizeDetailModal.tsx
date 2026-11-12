// 收口明细（收口留痕）展示：收口成功即时回执与「收口记录」查看共用。
// 项目未挂 antd <App> 上下文，一律走静态 Modal。
import { Modal, Typography } from 'antd'
import { fmtTime } from '../../../shared/utils/format'

export function FinalizeDetailContent({ detail, finalizedAt }: { detail: string; finalizedAt?: string | null }) {
  return (
    <>
      {finalizedAt && (
        <Typography.Text type="secondary" style={{ display: 'block', marginBottom: 8 }}>
          收口时间：{fmtTime(finalizedAt)}
        </Typography.Text>
      )}
      <pre
        style={{
          maxHeight: 420,
          overflow: 'auto',
          whiteSpace: 'pre-wrap',
          wordBreak: 'break-all',
          background: 'var(--ant-color-fill-quaternary, rgba(0,0,0,0.02))',
          padding: 12,
          borderRadius: 6,
          fontSize: 12,
          margin: 0,
        }}
      >
        {detail}
      </pre>
    </>
  )
}

/** 弹窗展示收口明细（标题自带收口时间）。 */
export function showFinalizeDetailModal(detail: string, finalizedAt?: string | null) {
  Modal.info({
    centered: true,
    title: '收口记录',
    width: 720,
    content: <FinalizeDetailContent detail={detail} finalizedAt={finalizedAt} />,
    okText: '关闭',
  })
}
