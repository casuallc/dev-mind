// CAP-86 v3 报表独立页：跨库质量报表（生命周期/近重复/答案反馈/检索评测/自动增强）。
import { Link, useSearchParams } from 'react-router-dom'
import { Button, Card, Segmented, Space, Typography } from 'antd'
import KnowledgeReportsPanel, { REPORT_VIEW_OPTIONS, type ReportsView } from '../components/KnowledgeReportsPanel'
import { pageCardBodyFlexStyle, pageCardStyle } from '../../../shared/utils/pageLayout'

export default function KnowledgeReportsPage() {
  const [searchParams, setSearchParams] = useSearchParams()
  const viewParam = searchParams.get('view')
  const view: ReportsView =
    REPORT_VIEW_OPTIONS.find((o) => o.value === viewParam)?.value ?? 'lifecycle'

  const onViewChange = (next: ReportsView) => {
    const params = new URLSearchParams(searchParams)
    params.set('view', next)
    setSearchParams(params, { replace: true })
  }

  return (
    <Card
      style={pageCardStyle}
      styles={{ body: pageCardBodyFlexStyle }}
      title={
        <Space size={12}>
          <span>报表</span>
          <Segmented
            value={view}
            onChange={(v) => onViewChange(v as ReportsView)}
            options={REPORT_VIEW_OPTIONS}
          />
        </Space>
      }
      extra={
        <Space wrap>
          <Link to="/admin/knowledge/ops?tab=tasks">
            <Button>前往知识运营</Button>
          </Link>
        </Space>
      }
    >
      <Typography.Paragraph type="secondary" style={{ marginBottom: 12, flexShrink: 0 }}>
        跨库质量报表：生命周期、近重复、答案反馈、检索评测与自动增强。同步源在库详情「飞书同步」维护，上传批次在「手动上传」查看。
      </Typography.Paragraph>
      <KnowledgeReportsPanel view={view} />
    </Card>
  )
}
