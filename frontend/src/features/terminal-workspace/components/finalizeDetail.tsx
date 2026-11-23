// CAP-115 M3 收口记录展示：与会话侧 FinalizeDetailModal 同口径（feature 自包含约束下
// 不跨 feature 私引 sessions 内部文件，此处保留一份同实现）。
import { Modal, Typography } from 'antd'
import { fmtTime } from '../../../shared/utils/format'

/** 弹窗展示收口明细（标题自带收口时间）。 */
export function showFinalizeDetail(detail: string, finalizedAt?: string | null) {
  Modal.info({
    centered: true,
    title: '收口记录',
    width: 720,
    okText: '关闭',
    content: (
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
    ),
  })
}
