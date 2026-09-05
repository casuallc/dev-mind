// 需求详情页（/projects/:id/requirements/:rid）：单条需求的研发主线。
// 布局参考 Jira issue 页 + SessionDetail 工具条风格：左主（头卡 + 裸 Tabs）右栏（属性卡）。
// 头卡 title 放 code/标题/类型/状态（标题允许换行防截断），extra 集中放本地操作：FlowActions 主按钮 + 编辑/刷新 + 更多（取消/删除）。
// Jira 远端操作（JiraActions）收在右侧属性卡，与本地流程按钮隔离防误点。
// 引导式流转——状态仅由 FlowActions 流程按钮与验收/取消隐式推进，FlowProgress 只读。
// Jira 来源：托管字段本地只读（表单禁用 + 服务端强制），属性面板显示 Jira key 链接与远端状态。
import { useCallback, useEffect, useState } from 'react'
import {
  Breadcrumb,
  Button,
  Card,
  Col,
  Descriptions,
  Dropdown,
  Empty,
  Modal,
  Row,
  Space,
  Spin,
  Tabs,
  Tag,
  Tooltip,
  Typography,
  message,
} from 'antd'
import { DownOutlined, EditOutlined, LockOutlined, ReloadOutlined } from '@ant-design/icons'
import { useNavigate, useParams } from 'react-router-dom'
import { deleteRequirement, getRequirementOverview, updateRequirementStatus } from '../api'
import FlowActions from '../components/flow/FlowActions'
import FlowProgress from '../components/flow/FlowProgress'
import DesignsTab from '../components/flow/DesignsTab'
import JiraActions from '../components/JiraActions'
import RelatedRecordsTab from '../components/RelatedRecordsTab'
import RequirementFormDrawer from '../components/RequirementFormDrawer'
import TimelineTab from '../components/TimelineTab'
import WorkItemsTab from '../components/WorkItemsTab'
import { useProject } from '../../projects/hooks/useProject'
import { getCurrentProjectId, setCurrentProject } from '../../../app/currentProjectStore'
import { fmtDuration, fmtTime } from '../../../shared/utils/format'
import {
  requirementStatusColor,
  requirementTypeColor,
  priorityColor,
  sourceTagColor,
  SOURCE_LABEL,
  STATUS_LABEL,
  TYPE_LABEL,
} from '../components/requirementMeta'
import type { RequirementOverview } from '../types'

export default function RequirementDetailPage() {
  const { id: projectId, rid } = useParams<{ id: string; rid: string }>()
  const navigate = useNavigate()
  const { project } = useProject(projectId)
  const [overview, setOverview] = useState<RequirementOverview | null>(null)
  const [loading, setLoading] = useState(true)
  const [editOpen, setEditOpen] = useState(false)

  // URL 自含项目身份：从分享链接进入时把当前项目切到该需求所属项目
  useEffect(() => {
    if (projectId && projectId !== getCurrentProjectId()) {
      setCurrentProject(projectId)
    }
  }, [projectId])

  const reloadOverview = useCallback(async () => {
    if (!projectId || !rid) return
    try {
      setOverview(await getRequirementOverview(projectId, rid))
    } catch (e) {
      message.error(`加载需求主线失败：${(e as Error).message}`)
      setOverview(null)
    } finally {
      setLoading(false)
    }
  }, [projectId, rid])

  useEffect(() => {
    setLoading(true)
    reloadOverview()
  }, [reloadOverview])

  if (loading) {
    return <Card><Spin /></Card>
  }
  if (!overview) {
    return <Card><Empty description="需求不存在或已删除" /></Card>
  }

  const r = overview.requirement
  const isJira = r.source === 'JIRA'
  const cancellable = r.status !== 'DONE' && r.status !== 'CANCELLED'

  const confirmCancel = () => {
    Modal.confirm({
      centered: true,
      title: '取消需求？',
      content: `「${r.code} ${r.title}」将标记为 CANCELLED，工作单元与关联记录保留。`,
      okText: '取消需求',
      okButtonProps: { danger: true },
      cancelText: '返回',
      onOk: async () => {
        if (!projectId) return
        try {
          await updateRequirementStatus(projectId, r.id, 'CANCELLED')
          await reloadOverview()
          message.success(`${r.code} → CANCELLED`)
        } catch (e) {
          message.error((e as Error).message)
        }
      },
    })
  }

  const confirmDelete = () => {
    Modal.confirm({
      centered: true,
      title: '删除需求？',
      content: `将删除需求「${r.code} ${r.title}」及其工作单元/方案（关联的文档/构建/部署记录保留，仅解除主线）。`,
      okText: '删除',
      okButtonProps: { danger: true },
      cancelText: '取消',
      onOk: async () => {
        if (!projectId) return
        await deleteRequirement(projectId, r.id)
        message.success('已删除')
        navigate('/requirements', { replace: true })
      },
    })
  }

  /** Jira 托管属性行的 label 带锁标（本地只读，由同步维护） */
  const managedLabel = (text: string) => (
    <Space size={4}>
      {text}
      {isJira && (
        <Tooltip title="Jira 来源字段由同步维护，本地只读">
          <LockOutlined style={{ fontSize: 11, color: '#1677ff' }} />
        </Tooltip>
      )}
    </Space>
  )

  return (
    <Space direction="vertical" size={12} style={{ width: '100%' }}>
      <Breadcrumb
        items={[
          { title: <a onClick={() => navigate('/overview')}>{project?.name ?? projectId}</a> },
          { title: <a onClick={() => navigate('/requirements')}>需求</a> },
          { title: r.code },
        ]}
      />

      <Row gutter={12}>
        <Col xs={24} xl={17}>
          <Card
            size="small"
            title={
              <Space size={8} wrap style={{ whiteSpace: 'normal' }}>
                <Typography.Text code>{r.code}</Typography.Text>
                <Typography.Text strong>{r.title}</Typography.Text>
                <Tag color={requirementTypeColor(r.type ?? 'FEATURE')}>{TYPE_LABEL[r.type ?? 'FEATURE']}</Tag>
                <Tag color={requirementStatusColor(r.status)}>{STATUS_LABEL[r.status]}</Tag>
                {r.status === 'CANCELLED' && (
                  <Typography.Text type="secondary" style={{ fontSize: 12 }}>该需求已取消</Typography.Text>
                )}
              </Space>
            }
            extra={
              <Space size={8} wrap>
                <FlowActions requirement={r} onChanged={reloadOverview} />
                <Button icon={<EditOutlined />} onClick={() => setEditOpen(true)}>编辑</Button>
                <Button icon={<ReloadOutlined />} onClick={reloadOverview}>刷新</Button>
                <Dropdown
                  menu={{
                    items: [
                      { key: 'cancel', label: '取消需求', danger: true, disabled: !cancellable },
                      { key: 'delete', label: '删除', danger: true },
                    ],
                    onClick: ({ key }) => (key === 'cancel' ? confirmCancel() : confirmDelete()),
                  }}
                >
                  <Button>更多 <DownOutlined /></Button>
                </Dropdown>
              </Space>
            }
          >
            {r.status !== 'CANCELLED' && <FlowProgress status={r.status} />}
            {r.description && (
              <Typography.Paragraph
                style={{ fontSize: 13, marginBottom: 0, whiteSpace: 'pre-wrap' }}
                ellipsis={{ rows: 3, expandable: true, symbol: '展开' }}
              >
                {r.description}
              </Typography.Paragraph>
            )}
          </Card>

          <Tabs
            items={[
              {
                key: 'workItems',
                label: `工作单元（${overview.workItems.length}）`,
                children: (
                  <WorkItemsTab
                    projectId={r.projectId}
                    requirementId={r.id}
                    workItems={overview.workItems}
                    locked={r.status === 'DONE' || r.status === 'CANCELLED'}
                    onChanged={reloadOverview}
                  />
                ),
              },
              {
                key: 'designs',
                label: '方案',
                children: <DesignsTab projectId={r.projectId} requirementId={r.id} />,
              },
              {
                key: 'timeline',
                label: `时间线（${overview.timeline.length}）`,
                children: <TimelineTab items={overview.timeline} />,
              },
              {
                key: 'records',
                label: '关联记录',
                children: <RelatedRecordsTab overview={overview} />,
              },
            ]}
          />
        </Col>

        <Col xs={24} xl={7}>
          <Card size="small" title="属性">
            <Descriptions size="small" column={1}>
              <Descriptions.Item label="来源">
                <Space size={6}>
                  <Tag color={sourceTagColor(r.source)}>{SOURCE_LABEL[r.source]}</Tag>
                  {isJira && r.externalKey && (
                    <a onClick={() => r.externalUrl && window.open(r.externalUrl, '_blank')}>
                      {r.externalKey}
                    </a>
                  )}
                </Space>
              </Descriptions.Item>
              {isJira && (
                <Descriptions.Item label="Jira 状态">{r.remoteStatus ?? '-'}</Descriptions.Item>
              )}
              <Descriptions.Item label={
                <Tooltip title="需求下所有 agent 会话时长汇总（活跃会话算到当前）">
                  <span>AI 执行耗时</span>
                </Tooltip>
              }>
                {fmtDuration(r.agentSeconds)}
              </Descriptions.Item>
              {isJira && (
                <Descriptions.Item label={managedLabel('预估工时')}>
                  {fmtDuration(r.estimatedSeconds)}
                </Descriptions.Item>
              )}
              {isJira && (
                <Descriptions.Item label={managedLabel('已用工时')}>
                  {fmtDuration(r.spentSeconds)}
                </Descriptions.Item>
              )}
              <Descriptions.Item label={managedLabel('优先级')}>
                {r.priority ? <Tag color={priorityColor(r.priority)}>{r.priority}</Tag> : '-'}
              </Descriptions.Item>
              <Descriptions.Item label={managedLabel('经办人')}>{r.assignee ?? '-'}</Descriptions.Item>
              <Descriptions.Item label={managedLabel('报告人')}>{r.reporter ?? '-'}</Descriptions.Item>
              <Descriptions.Item label={managedLabel('标签')}>
                {r.labels?.length
                  ? r.labels.map((l) => <Tag key={l} style={{ fontSize: 11 }}>{l}</Tag>)
                  : '-'}
              </Descriptions.Item>
              <Descriptions.Item label={managedLabel('修复版本')}>
                {r.fixVersions?.length
                  ? r.fixVersions.map((v) => <Tag key={v} style={{ fontSize: 11 }}>{v}</Tag>)
                  : '-'}
              </Descriptions.Item>
              <Descriptions.Item label={managedLabel('截止日期')}>{r.dueDate ?? '-'}</Descriptions.Item>
              <Descriptions.Item label="本地负责人">
                <Tooltip title="平台侧流程负责人，与 Jira 经办人相互独立">
                  <span>{r.ownerId || '-'}</span>
                </Tooltip>
              </Descriptions.Item>
              <Descriptions.Item label="创建">{fmtTime(r.createdAt)}</Descriptions.Item>
              <Descriptions.Item label="更新">{fmtTime(r.updatedAt)}</Descriptions.Item>
            </Descriptions>
            {isJira && (
              <div style={{ marginTop: 12 }}>
                <JiraActions requirement={r} onChanged={reloadOverview} />
              </div>
            )}
          </Card>
        </Col>
      </Row>

      {projectId && (
        <RequirementFormDrawer
          projectId={projectId}
          editing={r}
          open={editOpen}
          onClose={() => setEditOpen(false)}
          onSaved={() => reloadOverview()}
        />
      )}
    </Space>
  )
}
