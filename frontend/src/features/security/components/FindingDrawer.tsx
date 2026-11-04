// 发现详情抽屉：detail_json 分块渲染（CVE/CVSS/CWE/引入链/EPSS·KEV/修复建议/AI 复核与修复记录）
// + 历史出现记录 + 行内抑制弹窗 + AI 复核/AI 修复入口（成功跳会话页）。
import { Alert, Button, DatePicker, Descriptions, Drawer, Form, Input, Modal, Radio, Space, Table, Tag, Typography, message } from 'antd'
import { useEffect, useState } from 'react'
import { RobotOutlined } from '@ant-design/icons'
import { useNavigate } from 'react-router-dom'
import dayjs from 'dayjs'
import { createSuppression, getFinding, startAiFix, startAiReview } from '../api'
import type { FindingDetail, SecurityFinding } from '../types'
import { DRAWER_SCREEN } from '../../../shared/utils/overlay'
import { fmtTime } from '../../../shared/utils/format'
import { showError } from '../../../shared/utils/showError'
import { usePermissions } from '../../auth/usePermissions'
import {
  ENGINE_LABEL,
  FINDING_STATUS_COLOR,
  FINDING_STATUS_LABEL,
  REMEDIATION_ACTION_LABEL,
  SEVERITY_COLOR,
  parseDetail,
} from './common'

const SUPPRESS_TYPES = [
  { value: 'FALSE_POSITIVE', label: '误报' },
  { value: 'ACCEPTED_RISK', label: '风险接受（必须填过期时间）' },
  { value: 'THIRD_PARTY', label: '第三方组件' },
]

interface Props {
  findingId: number | null
  onClose: () => void
  onChanged: () => void
}

export default function FindingDrawer({ findingId, onClose, onChanged }: Props) {
  const navigate = useNavigate()
  const { canAction } = usePermissions()
  const canSuppress = canAction('project.security.action.suppress')
  const canAi = canAction('project.security.action.ai')
  const [detail, setDetail] = useState<FindingDetail | null>(null)
  const [suppressOpen, setSuppressOpen] = useState(false)
  const [suppressForm] = Form.useForm()
  const [submitting, setSubmitting] = useState(false)

  useEffect(() => {
    if (!findingId) {
      setDetail(null)
      return
    }
    getFinding(findingId).then(setDetail).catch(() => {})
  }, [findingId])

  const f = detail?.finding
  const dj = parseDetail(f?.detailJson ?? null)

  const onSuppress = async () => {
    const v = suppressForm.getFieldsValue(true)
    if (!v.reason?.trim()) {
      message.warning('请填写抑制理由')
      return
    }
    if (v.type === 'ACCEPTED_RISK' && !v.expiresAt) {
      message.warning('风险接受必须设置过期时间')
      return
    }
    setSubmitting(true)
    try {
      await createSuppression({
        findingId: f!.id,
        type: v.type,
        reason: v.reason.trim(),
        expiresAt: v.expiresAt ? dayjs(v.expiresAt).format('YYYY-MM-DD HH:mm:ss') : undefined,
      })
      message.success('已抑制')
      setSuppressOpen(false)
      suppressForm.resetFields()
      if (findingId) getFinding(findingId).then(setDetail).catch(() => {})
      onChanged()
    } catch (e) {
      showError(e)
    } finally {
      setSubmitting(false)
    }
  }

  const onAi = async (kind: 'review' | 'fix') => {
    if (!f) return
    try {
      const r = kind === 'review' ? await startAiReview(f.id) : await startAiFix(f.id)
      message.success(`已创建${kind === 'review' ? '复核' : '修复'}会话`)
      navigate(`/sessions?sid=${r.sessionId}`)
    } catch (e) {
      showError(e)
    }
  }

  return (
    <Drawer
      title={f ? `${ENGINE_LABEL[f.engine] ?? f.engine} · ${f.title}` : '发现详情'}
      width={DRAWER_SCREEN}
      open={!!findingId}
      onClose={onClose}
    >
      {f && (
        <>
          <Space size={8} wrap style={{ marginBottom: 12 }}>
            <Tag color={SEVERITY_COLOR[f.severity]}>{f.severity}</Tag>
            <Tag color={FINDING_STATUS_COLOR[f.status]}>{FINDING_STATUS_LABEL[f.status]}</Tag>
            {dj.kev && <Tag color="red">CISA KEV 在野利用</Tag>}
            {dj.epssScore != null && <Tag>EPSS {(dj.epssScore * 100).toFixed(1)}%</Tag>}
            {f.directDep != null && <Tag>{f.directDep ? '直接依赖' : '传递依赖'}</Tag>}
            {f.depScope && <Tag>scope: {f.depScope}</Tag>}
          </Space>

          <Descriptions column={2} size="small" bordered style={{ marginBottom: 12 }}>
            <Descriptions.Item label="规则">{f.ruleId}</Descriptions.Item>
            <Descriptions.Item label="指纹" span={1}>
              <Typography.Text copyable={{ text: f.fingerprint }} style={{ fontFamily: 'monospace' }}>
                {f.fingerprint.slice(0, 16)}…
              </Typography.Text>
            </Descriptions.Item>
            {dj.cve && <Descriptions.Item label="CVE">{dj.cve}</Descriptions.Item>}
            {dj.cvssScore != null && (
              <Descriptions.Item label="CVSS">
                {dj.cvssScore}
                {dj.cvssVector ? `（${dj.cvssVector}）` : ''}
              </Descriptions.Item>
            )}
            {dj.groupId && (
              <Descriptions.Item label="构件" span={2}>
                {dj.groupId}:{dj.artifactId}
                {dj.depVersion ? `:${dj.depVersion}` : ''}
              </Descriptions.Item>
            )}
            {f.filePath && (
              <Descriptions.Item label="位置" span={2}>
                {f.filePath}
                {f.lineNo ? `:${f.lineNo}` : ''}
              </Descriptions.Item>
            )}
            {dj.cwes && dj.cwes.length > 0 && (
              <Descriptions.Item label="CWE" span={2}>{dj.cwes.join('、')}</Descriptions.Item>
            )}
            {dj.kevDueDate && <Descriptions.Item label="KEV 修复期限">{dj.kevDueDate}</Descriptions.Item>}
          </Descriptions>

          {dj.description && (
            <Typography.Paragraph type="secondary" style={{ whiteSpace: 'pre-wrap' }}>
              {dj.description}
            </Typography.Paragraph>
          )}
          {dj.message && (
            <Typography.Paragraph type="secondary" style={{ whiteSpace: 'pre-wrap' }}>
              {dj.message}
            </Typography.Paragraph>
          )}

          {dj.depPath && (
            <Alert
              style={{ marginBottom: 12 }}
              type="info"
              showIcon
              message="依赖引入链"
              description={<Typography.Text style={{ fontFamily: 'monospace' }}>{dj.depPath}</Typography.Text>}
            />
          )}
          {dj.depPathMissing && (
            <Alert
              style={{ marginBottom: 12 }}
              type="warning"
              showIcon
              message="依赖树未能解析引入链"
              description={dj.depPathMissing}
            />
          )}

          {dj.remediation && (
            <Alert
              style={{ marginBottom: 12 }}
              type={dj.remediation.action === 'NONE_AVAILABLE' ? 'warning' : 'success'}
              showIcon
              message={`修复建议：${REMEDIATION_ACTION_LABEL[dj.remediation.action ?? ''] ?? dj.remediation.action}`}
              description={
                <div>
                  {dj.remediation.recommendedVersion && <div>推荐版本：{dj.remediation.recommendedVersion}</div>}
                  {dj.remediation.depPath && <div>作用链：{dj.remediation.depPath}</div>}
                  {dj.remediation.note && <div>{dj.remediation.note}</div>}
                </div>
              }
            />
          )}

          {f.reviewNote && (
            <Alert
              style={{ marginBottom: 12 }}
              type="info"
              showIcon
              message={`AI 复核结论（${fmtTime(f.reviewedAt)}）`}
              description={<Typography.Paragraph style={{ whiteSpace: 'pre-wrap', marginBottom: 0 }}>{f.reviewNote}</Typography.Paragraph>}
            />
          )}
          {f.fixDiffSummary && (
            <Alert
              style={{ marginBottom: 12 }}
              type="success"
              showIcon
              message="AI 修复产出"
              description={f.fixDiffSummary}
            />
          )}

          <Space style={{ marginBottom: 16 }}>
            {canAi && (
              <>
                <Button icon={<RobotOutlined />} onClick={() => onAi('review')}>
                  AI 复核
                </Button>
                <Button icon={<RobotOutlined />} onClick={() => onAi('fix')}>
                  AI 修复
                </Button>
              </>
            )}
            {canSuppress && f.status !== 'SUPPRESSED' && f.status !== 'FIXED' && (
              <Button danger onClick={() => setSuppressOpen(true)}>
                抑制
              </Button>
            )}
            {(f.reviewSessionId || f.fixSessionId) && (
              <Button
                type="link"
                onClick={() => navigate(`/sessions?sid=${f.reviewSessionId ?? f.fixSessionId}`)}
              >
                查看关联会话
              </Button>
            )}
          </Space>

          {detail && detail.history.length > 1 && (
            <>
              <Typography.Title level={5}>历史出现</Typography.Title>
              <Table<SecurityFinding>
                rowKey="id"
                size="small"
                dataSource={detail.history}
                pagination={false}
                columns={[
                  { title: '扫描', dataIndex: 'scanId', width: 90, render: (v: number) => `#${v}` },
                  {
                    title: '状态',
                    dataIndex: 'status',
                    width: 90,
                    render: (v: SecurityFinding['status']) => (
                      <Tag color={FINDING_STATUS_COLOR[v]}>{FINDING_STATUS_LABEL[v]}</Tag>
                    ),
                  },
                  { title: '首次发现', dataIndex: 'createdAt', render: (v: string) => fmtTime(v) },
                ]}
              />
            </>
          )}

          <Modal
            title="抑制该发现"
            open={suppressOpen}
            onCancel={() => setSuppressOpen(false)}
            onOk={onSuppress}
            confirmLoading={submitting}
            destroyOnHidden
          >
            <Form form={suppressForm} layout="vertical" initialValues={{ type: 'FALSE_POSITIVE' }} preserve={false}>
              <Form.Item name="type" label="抑制类型" rules={[{ required: true }]}>
                <Radio.Group options={SUPPRESS_TYPES} optionType="button" />
              </Form.Item>
              <Form.Item name="reason" label="理由" rules={[{ required: true, message: '必填' }]}>
                <Input.TextArea rows={3} placeholder="为什么该发现可忽略（写入审计）" />
              </Form.Item>
              <Form.Item name="expiresAt" label="过期时间（风险接受必填，其余可留空=长期）">
                <DatePicker showTime style={{ width: '100%' }} />
              </Form.Item>
            </Form>
          </Modal>
        </>
      )}
    </Drawer>
  )
}
