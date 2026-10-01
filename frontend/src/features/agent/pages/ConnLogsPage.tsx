import { Card, Typography } from 'antd'
import ConnLogsPanel from '../components/ConnLogsPanel'
import { pageCardStyle, pageCardBodyFlexStyle } from '../../../shared/utils/pageLayout'

/** 连接日志独立页：薄壳承载 ConnLogsPanel（节点接入/拒绝/断线流水）。 */
export default function ConnLogsPage() {
  return (
    <Card style={pageCardStyle} styles={{ body: pageCardBodyFlexStyle }} title="连接日志">
      <Typography.Paragraph type="secondary" style={{ marginBottom: 12 }}>
        节点连接的接入/拒绝/断线流水；拒绝记录没有节点归属，来源地址（IP:端口）是定位陌生 runner 的唯一线索。
      </Typography.Paragraph>
      <ConnLogsPanel />
    </Card>
  )
}
