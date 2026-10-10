import { useState } from 'react'
import { Button, Card, Typography } from 'antd'
import { ReloadOutlined } from '@ant-design/icons'
import AuditLogsPanel from '../components/AuditLogsPanel'
import { pageCardStyle, pageCardBodyFlexStyle } from '../../../shared/utils/pageLayout'

/** CAP-71 审计日志独立页：薄壳承载 AuditLogsPanel（extra 刷新；分页语义下面板不轮询）。 */
export default function AuditLogsPage() {
  const [refreshTick, setRefreshTick] = useState(0)
  return (
    <Card
      style={pageCardStyle}
      styles={{ body: pageCardBodyFlexStyle }}
      title="审计日志"
      extra={
        <Button icon={<ReloadOutlined />} onClick={() => setRefreshTick((t) => t + 1)}>
          刷新
        </Button>
      }
    >
      <Typography.Paragraph type="secondary" style={{ marginBottom: 12 }}>
        节点全量交互审计（连接/隧道/指令/代理/上下文/文件中转/规则/包/节点运维）；代理转发逐条记录目标、字节数与耗时。
        默认保留 180 天（devmind.agent.audit-retention-days 可配）。
      </Typography.Paragraph>
      <AuditLogsPanel refreshTick={refreshTick} />
    </Card>
  )
}
