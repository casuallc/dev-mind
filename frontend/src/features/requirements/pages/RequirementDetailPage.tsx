// 需求详情页（/projects/:id/requirements/:rid）：单条需求的研发主线。
// 布局：头卡（默认尺寸，标题栏更高；extra 集中全部操作：验收/编辑/属性/Jira 操作/刷新/更多/返回列表）+ 白底 Tabs 卡。
// 不设阶段引导卡：需求状态由工作单元 rollup 自动派生（全部完结 → ACCEPTANCE），验收按钮直接放头卡 extra。
// AI 流程动作（分析/方案/拆分/拆分草稿）收进「更多」下拉保持可达，状态门禁以后端为准。
// 属性面板非常驻：点「属性」按钮开右侧 Drawer；需求描述默认全文展开。
// Jira 来源：托管字段本地只读（表单禁用 + 服务端强制），属性面板显示 Jira key 链接与远端状态。
import { useCallback, useEffect, useState } from 'react'
import {
  Button,
  Card,
  Descriptions,
  Drawer,
  Dropdown,
  Empty,
  Modal,
  Space,
  Spin,
  Tabs,
  Tag,
  Tooltip,
  Typography,
  message,
} from 'antd'
import {
  ApartmentOutlined,
  ArrowLeftOutlined,
  CheckOutlined,
  DownOutlined,
  EditOutlined,
  FileDoneOutlined,
  FileSearchOutlined,
  LockOutlined,
  PlayCircleOutlined,
  ProfileOutlined,
  ReloadOutlined,
} from '@ant-design/icons'
import { useNavigate, useParams } from 'react-router-dom'
import {
  deleteRequirement,
  flowAnalyze,
  flowDesign,
  flowSplit,
  getRequirementOverview,
  listDesigns,
  updateRequirementStatus,
} from '../api'
import JiraActions from '../components/JiraActions'
import JiraDescription from '../components/JiraDescription'
import DesignsTab from '../components/flow/DesignsTab'
import SplitDraftDrawer from '../components/flow/SplitDraftDrawer'
import RelatedRecordsTab from '../components/RelatedRecordsTab'
import RequirementFormDrawer from '../components/RequirementFormDrawer'
import TimelineTab from '../components/TimelineTab'
import WorkItemsTab from '../components/WorkItemsTab'
import { getCurrentProjectId, setCurrentProject } from '../../../app/currentProjectStore'
import { fmtDuration, fmtTime } from '../../../shared/utils/format'
import { pageRootScrollStyle } from '../../../shared/utils/pageLayout'
import {
  requirementStatusColor,
  requirementTypeColor,
  priorityColor,
  sourceTagColor,
  SOURCE_LABEL,
  STATUS_LABEL,
  TYPE_LABEL,
} from '../components/requirementMeta'
import type { Design, RequirementOverview } from '../types'

export default function RequirementDetailPage() {
  const { id: projectId, rid } = useParams<{ id: string; rid: string }>()
  const navigate = useNavigate()
  const [overview, setOverview] = useState<RequirementOverview | null>(null)
  const [loading, setLoading] = useState(true)
  const [editOpen, setEditOpen] = useState(false)
  const [propsOpen, setPropsOpen] = useState(false)
  const [draftOpen, setDraftOpen] = useState(false)
  const [flowBusy, setFlowBusy] = useState(false)
  const [designs, setDesigns] = useState<Design[]>([])
  const [activeTab, setActiveTab] = useState('workItems')

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

  // 方案列表在页面层取一次：方案 Tab 角标（N 待确认）用
  const loadDesigns = useCallback(() => {
    if (!projectId || !rid) return
    listDesigns(projectId, rid).then(setDesigns).catch(() => {})
  }, [projectId, rid])

  useEffect(loadDesigns, [loadDesigns])

  // 流程动作/验收触发的刷新：overview 与 designs 一起重取
  const reloadAll = useCallback(() => {
    reloadOverview()
    loadDesigns()
  }, [reloadOverview, loadDesigns])

  if (loading) {
    return <Card><Spin /></Card>
  }
  if (!overview) {
    return <Card><Empty description="需求不存在或已删除" /></Card>
  }

  const r = overview.requirement
  const isJira = r.source === 'JIRA'
  const terminal = r.status === 'DONE' || r.status === 'CANCELLED'
  const cancellable = !terminal
  const draftDesignCount = designs.filter((d) => d.status === 'DRAFT').length

  // AI 流程动作触发（原 FlowActions）：只做触发与提示，状态门禁以后端报错为准
  const runFlow = async (label: string, fn: () => Promise<{ id: string }>) => {
    if (!projectId) return
    setFlowBusy(true)
    try {
      await fn()
      message.success(`${label}会话已启动，完成后会通知你确认产出`)
      reloadAll()
    } catch (e) {
      message.error((e as Error).message)
    } finally {
      setFlowBusy(false)
    }
  }

  // 人工验收（CAP-13）：ACCEPTANCE→DONE 是终态翻转，直接放头卡主按钮
  const confirmAccept = () => {
    Modal.confirm({
      centered: true,
      title: '验收通过？',
      content: `「${r.code} ${r.title}」将标记为 DONE，工作单元与关联记录保留。`,
      okText: '验收通过',
      cancelText: '返回',
      onOk: async () => {
        if (!projectId) return
        try {
          await updateRequirementStatus(projectId, r.id, 'DONE')
          message.success(`${r.code} → DONE`)
          reloadAll()
        } catch (e) {
          message.error((e as Error).message)
        }
      },
    })
  }

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
    <Space direction="vertical" size={12} style={{ width: '100%', ...pageRootScrollStyle }}>
      <Card
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
            {r.status === 'ACCEPTANCE' && (
              <Button type="primary" icon={<CheckOutlined />} onClick={confirmAccept}>
                验收通过
              </Button>
            )}
            <Button icon={<EditOutlined />} onClick={() => setEditOpen(true)}>编辑</Button>
            <Button icon={<ProfileOutlined />} onClick={() => setPropsOpen(true)}>属性</Button>
            {isJira && <JiraActions requirement={r} onChanged={reloadOverview} />}
            <Button icon={<ReloadOutlined />} onClick={reloadOverview}>刷新</Button>
            <Dropdown
              menu={{
                items: [
                  ...(!terminal ? [
                    { key: 'analyze', label: r.status === 'DRAFT' ? '开始分析' : '重新分析', icon: <FileSearchOutlined />, disabled: flowBusy },
                    { key: 'design', label: '生成方案（AI）', icon: <FileDoneOutlined />, disabled: flowBusy },
                    { key: 'split', label: 'AI 拆分工作单元', icon: <ApartmentOutlined />, disabled: flowBusy },
                    { key: 'draft', label: '拆分草稿', icon: <PlayCircleOutlined /> },
                    { type: 'divider' as const },
                  ] : []),
                  { key: 'cancel', label: '取消需求', danger: true, disabled: !cancellable },
                  { key: 'delete', label: '删除', danger: true },
                ],
                onClick: ({ key }) => {
                  if (!projectId) return
                  if (key === 'cancel') confirmCancel()
                  else if (key === 'delete') confirmDelete()
                  else if (key === 'draft') setDraftOpen(true)
                  else if (key === 'analyze') runFlow('分析', () => flowAnalyze(projectId, r.id))
                  else if (key === 'design') runFlow('方案设计', () => flowDesign(projectId, r.id))
                  else if (key === 'split') runFlow('拆分', () => flowSplit(projectId, r.id))
                },
              }}
            >
              <Button>更多 <DownOutlined /></Button>
            </Dropdown>
            <Button icon={<ArrowLeftOutlined />} onClick={() => navigate('/requirements')}>返回列表</Button>
          </Space>
        }
      >
        {r.description && (
          r.source === 'JIRA' ? (
            <JiraDescription description={r.description} pid={r.projectId} rid={r.id} />
          ) : (
            <Typography.Paragraph style={{ fontSize: 13, marginBottom: 0, whiteSpace: 'pre-wrap' }}>
              {r.description}
            </Typography.Paragraph>
          )
        )}
      </Card>

      <Card size="small">
        <Tabs
          activeKey={activeTab}
          onChange={setActiveTab}
          items={[
            {
              key: 'workItems',
              label: `工作单元（${overview.workItems.length}）`,
              children: (
                <WorkItemsTab
                  projectId={r.projectId}
                  requirementId={r.id}
                  workItems={overview.workItems}
                  locked={terminal}
                  onChanged={reloadOverview}
                />
              ),
            },
            {
              key: 'designs',
              label: draftDesignCount > 0 ? `方案（${draftDesignCount} 待确认）` : '方案',
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
      </Card>

      <Drawer
        title={`属性 · ${r.code}`}
        width={520}
        open={propsOpen}
        onClose={() => setPropsOpen(false)}
      >
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
      </Drawer>

      {projectId && (
        <>
          <RequirementFormDrawer
            projectId={projectId}
            editing={r}
            open={editOpen}
            onClose={() => setEditOpen(false)}
            onSaved={() => reloadOverview()}
          />
          <SplitDraftDrawer
            projectId={projectId}
            requirementId={r.id}
            open={draftOpen}
            onClose={() => setDraftOpen(false)}
            onConfirmed={reloadAll}
          />
        </>
      )}
    </Space>
  )
}
