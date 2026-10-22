// 经验提案视图（项目管理聚合页的「经验提案」tab）：会话「沉淀经验」/手动提交 → inbox → 采纳/拒绝/删除。
// 注入预览以折叠面板内嵌在本页顶部（不再是聚合页的独立 tab）。
// 外壳 Card / extra 按钮在 AdminProjectsHub（refreshTick/createTick 驱动）。
import { useCallback, useEffect, useRef, useState } from 'react'
import {
  Button,
  Collapse,
  Descriptions,
  Drawer,
  Form,
  Input,
  Modal,
  Select,
  Space,
  Tag,
  Tooltip,
  Typography,
  message,
} from 'antd'
import type { ColumnsType } from 'antd/es/table'
import { listProjects } from '../../projects/api'
import type { Project } from '../../projects/types'
import { adoptProposal, createProposal, deleteProposal, listProposals, rejectProposal } from '../api'
import type { KnowledgeProposal, TriageView } from '../types'
import TriageEvidenceDrawer from '../components/TriageEvidenceDrawer'
import InjectionPreview from '../components/InjectionPreview'
import { fmtTime } from '../../../shared/utils/format'
import FitTable from '../../../shared/components/FitTable'
import { showError } from '../../../shared/utils/showError'
import { LIST_PAGINATION } from '../../../shared/utils/table'
import { DRAWER_DEFAULT } from '../../../shared/utils/overlay'

const scopeTag = (s: string) => (s === 'global' ? <Tag color="blue">global</Tag> : <Tag>project</Tag>)
const statusTag = (s: string) =>
  s === 'active' ? <Tag color="green">active</Tag> : s === 'open' ? <Tag color="gold">open</Tag>
    : s === 'adopted' ? <Tag color="green">adopted</Tag> : s === 'rejected' ? <Tag>rejected</Tag>
    : s === 'deprecated' ? <Tag>deprecated</Tag> : <Tag color="orange">{s}</Tag>

/** CAP-55 FR-04 徽标：层级 / 重复 / 质量三块，未分诊或降级各有对应的"没有建议"形态 */
function TriageBadges({ triage }: { triage: TriageView | null }) {
  if (!triage) return <Typography.Text type="secondary">未分诊</Typography.Text>
  if (triage.degraded) {
    return (
      <Tooltip title={triage.degradedReason || '未拿到建议'}>
        <Tag color="orange">降级</Tag>
      </Tooltip>
    )
  }
  return (
    <Space size={4} wrap>
      {triage.adoptLayer && <Tag color="blue">{triage.adoptLayer.label}</Tag>}
      {triage.duplicate?.duplicate ? (
        <Tooltip title={`与 ${triage.duplicate.similar?.length ?? 0} 条现有条目相似`}>
          <Tag color="red">疑似重复</Tag>
        </Tooltip>
      ) : (
        <Tag>不重复</Tag>
      )}
      {triage.quality && <Tag color="green">质量 {triage.quality.label}</Tag>}
    </Space>
  )
}

export default function ProposalsView({
  refreshTick = 0,
  createTick = 0,
}: {
  refreshTick?: number
  createTick?: number
}) {
  const [projects, setProjects] = useState<Project[]>([])
  const [proposals, setProposals] = useState<KnowledgeProposal[]>([])
  const [proposalsLoading, setProposalsLoading] = useState(false)

  // 提案管理抽屉：引用实时列表数据，状态变化自动反映
  const [manageId, setManageId] = useState<number | null>(null)
  const manageProposal = manageId != null ? proposals.find((p) => p.id === manageId) ?? null : null

  // CAP-57：分诊已与决策引擎解耦，徽标只读展示历史 triage 数据（不再发起新分诊）
  const [evidenceId, setEvidenceId] = useState<number | null>(null)
  const evidenceProposal = evidenceId != null ? proposals.find((p) => p.id === evidenceId) ?? null : null

  // 提案弹窗（手动沉淀）
  const [proposalModalOpen, setProposalModalOpen] = useState(false)
  const [proposalForm] = Form.useForm()

  const loadProposals = useCallback(async () => {
    setProposalsLoading(true)
    try {
      setProposals(await listProposals())
    } catch (e) {
      showError(e, '加载提案失败')
    } finally {
      setProposalsLoading(false)
    }
  }, [])

  useEffect(() => {
    listProjects()
      .then(setProjects)
      .catch(() => undefined)
    loadProposals()
  }, [loadProposals, refreshTick])

  // 外壳 extra「手动沉淀经验」经 createTick 触发；挂载首帧不触发
  const firstCreate = useRef(true)
  useEffect(() => {
    if (firstCreate.current) { firstCreate.current = false; return }
    if (createTick > 0) setProposalModalOpen(true)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [createTick])

  const onAdopt = (p: KnowledgeProposal, target: 'project' | 'global') => {
    Modal.confirm({
      centered: true,
      title: `采纳「${p.title}」${target === 'global' ? '到全局' : '到项目'}`,
      content:
        target === 'global'
          ? '将作为全局经验条目进入经验库，后续所有项目会话都可能注入。'
          : `将进入项目经验库（项目 ${p.targetProjectId ?? '待定'}）。`,
      okText: '采纳',
      cancelText: '取消',
      onOk: async () => {
        try {
          await adoptProposal(p.id, target, p.targetProjectId ?? undefined)
          message.success('已采纳为知识条目')
          setManageId(null)
          loadProposals()
        } catch (err) {
          showError(err, '采纳失败')
        }
      },
    })
  }

  const onReject = (p: KnowledgeProposal) => {
    Modal.confirm({
      centered: true,
      title: `拒绝提案「${p.title}」？`,
      content: '提案将被标记为 rejected，不会进入知识库。',
      okText: '拒绝',
      okButtonProps: { danger: true },
      cancelText: '取消',
      onOk: async () => {
        try {
          await rejectProposal(p.id)
          message.success('已拒绝')
          setManageId(null)
          loadProposals()
        } catch (err) {
          showError(err, '操作失败')
        }
      },
    })
  }

  const onDelete = (p: KnowledgeProposal) => {
    Modal.confirm({
      centered: true,
      title: `删除提案「${p.title}」？`,
      content: '删除后无法恢复；已采纳生成的知识条目不受影响。',
      okText: '删除',
      okButtonProps: { danger: true },
      cancelText: '取消',
      onOk: async () => {
        try {
          await deleteProposal(p.id)
          message.success('已删除')
          setManageId(null)
          loadProposals()
        } catch (err) {
          showError(err, '删除失败')
        }
      },
    })
  }

  const onManualProposal = async () => {
    const v = await proposalForm.validateFields()
    try {
      await createProposal({
        title: v.title,
        contentMd: v.contentMd,
        targetScope: v.targetScope ?? 'project',
        targetProjectId: v.targetProjectId,
      })
      message.success('提案已提交，等待审核')
      setProposalModalOpen(false)
      proposalForm.resetFields()
      loadProposals()
    } catch (e) {
      showError(e, '提交失败')
    }
  }

  const proposalColumns: ColumnsType<KnowledgeProposal> = [
    { title: '标题', dataIndex: 'title', ellipsis: true },
    { title: '去向', dataIndex: 'targetScope', width: 90, render: scopeTag },
    { title: '来源会话', dataIndex: 'sourceSessionId', width: 130, render: (v) => v ?? '-' },
    {
      title: 'AI 建议',
      key: 'triage',
      width: 260,
      render: (_, r) => (
        <Space size={8} wrap>
          <TriageBadges triage={r.triage} />
          {r.triage && !r.triage.degraded && (
            <a onClick={() => setEvidenceId(r.id)}>查看依据</a>
          )}
        </Space>
      ),
    },
    { title: '状态', dataIndex: 'status', width: 100, render: statusTag },
    { title: '创建时间', dataIndex: 'createdAt', width: 170, render: (v) => fmtTime(v) },
    {
      title: '操作',
      width: 90,
      render: (_, r) =>
        r.status === 'open' ? (
          <Button size="small" onClick={() => setManageId(r.id)}>
            管理
          </Button>
        ) : (
          <Button size="small" danger onClick={() => onDelete(r)}>
            删除
          </Button>
        ),
    },
  ]

  const projectOptions = projects.map((p) => ({ value: p.id, label: `${p.name} (${p.id})` }))

  return (
    <>
      <Typography.Paragraph type="secondary" style={{ margin: 0, marginBottom: 12 }}>
        会话中「沉淀经验」或手动提交的经验，审核采纳后进入对应经验库（inbox）。
      </Typography.Paragraph>
      <Collapse
        style={{ marginBottom: 12 }}
        items={[
          {
            key: 'preview',
            label: '注入预览：会话启动时实际注入哪些知识内容',
            children: <InjectionPreview />,
          },
        ]}
      />
      <FitTable
        rowKey="id"
        loading={proposalsLoading}
        columns={proposalColumns}
        dataSource={proposals}
        pagination={LIST_PAGINATION}
        locale={{
          emptyText: '暂无待审核经验——在会话中点「沉淀经验」，或点击右上角「手动沉淀经验」提交一条。',
        }}
      />

      {/* 提案管理抽屉：采纳到项目/晋升全局/拒绝 */}
      {manageProposal && (
        <Drawer
          title={`提案 · ${manageProposal.title}`}
          open
          onClose={() => setManageId(null)}
          width={DRAWER_DEFAULT}
        >
          <Space direction="vertical" size={16} style={{ width: '100%' }}>
            <Descriptions size="small" column={2}>
              <Descriptions.Item label="去向">{scopeTag(manageProposal.targetScope)}</Descriptions.Item>
              <Descriptions.Item label="状态">{statusTag(manageProposal.status)}</Descriptions.Item>
              <Descriptions.Item label="来源会话">{manageProposal.sourceSessionId ?? '-'}</Descriptions.Item>
              <Descriptions.Item label="创建时间">{fmtTime(manageProposal.createdAt)}</Descriptions.Item>
            </Descriptions>
            <pre
              style={{
                whiteSpace: 'pre-wrap',
                background: '#f6f6f6',
                padding: 12,
                borderRadius: 4,
                fontSize: 12,
                maxHeight: 320,
                overflow: 'auto',
                margin: 0,
              }}
            >
              {manageProposal.contentMd}
            </pre>

            {/* CAP-55：AI 建议区块——降级时也要出现（"没给建议"和"没分诊过"是两回事） */}
            <div>
              <Space size={8} style={{ marginBottom: 8 }}>
                <Typography.Text strong>AI 建议</Typography.Text>
                {manageProposal.triage && (
                  <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                    {fmtTime(manageProposal.triage.at)}
                  </Typography.Text>
                )}
              </Space>
              <div style={{ marginBottom: 8 }}>
                <TriageBadges triage={manageProposal.triage} />
                {manageProposal.triage?.degraded && (
                  <Typography.Text type="warning" style={{ marginLeft: 8, fontSize: 12 }}>
                    {manageProposal.triage.degradedReason}
                  </Typography.Text>
                )}
              </div>
              <Space>
                {manageProposal.triage && !manageProposal.triage.degraded && (
                  <Button size="small" onClick={() => setEvidenceId(manageProposal.id)}>
                    查看依据
                  </Button>
                )}
              </Space>
            </div>

            {manageProposal.status === 'open' ? (
              <Space>
                <Button type="primary" onClick={() => onAdopt(manageProposal, 'project')}>
                  采纳到项目
                </Button>
                <Button onClick={() => onAdopt(manageProposal, 'global')}>晋升全局</Button>
                <Button danger onClick={() => onReject(manageProposal)}>
                  拒绝
                </Button>
              </Space>
            ) : (
              <Typography.Text type="secondary">该提案已处理。</Typography.Text>
            )}
            <div>
              <Button size="small" danger onClick={() => onDelete(manageProposal)}>
                删除提案
              </Button>
            </div>
          </Space>
        </Drawer>
      )}

      {/* CAP-55 FR-07「查看依据」：徽标只给结论，这里给来路（含 laya 应答原文） */}
      <TriageEvidenceDrawer proposal={evidenceProposal} onClose={() => setEvidenceId(null)} />

      {/* 手动沉淀经验弹窗 */}
      <Modal
        title="沉淀经验"
        open={proposalModalOpen}
        onCancel={() => setProposalModalOpen(false)}
        onOk={onManualProposal}
      >
        <Form form={proposalForm} labelCol={{ span: 5 }} wrapperCol={{ span: 18 }}>
          <Form.Item name="title" label="标题" rules={[{ required: true, message: '请填写标题' }]}>
            <Input placeholder="如：Maven 多模块增量编译实践" />
          </Form.Item>
          <Form.Item name="targetScope" label="去向" initialValue="project">
            <Select
              options={[
                { value: 'project', label: '项目经验' },
                { value: 'global', label: '全局经验' },
              ]}
            />
          </Form.Item>
          <Form.Item noStyle shouldUpdate>
            {({ getFieldValue }) =>
              getFieldValue('targetScope') === 'project' && (
                <Form.Item name="targetProjectId" label="目标项目">
                  <Select allowClear showSearch optionFilterProp="label" placeholder="选择项目" options={projectOptions} />
                </Form.Item>
              )
            }
          </Form.Item>
          <Form.Item name="contentMd" label="内容" rules={[{ required: true, message: '请填写内容' }]}>
            <Input.TextArea rows={6} placeholder="经验内容（Markdown），审核采纳后成为知识条目" />
          </Form.Item>
        </Form>
      </Modal>
    </>
  )
}
