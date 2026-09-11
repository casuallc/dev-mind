// 流程 Tab（CAP-37 FR-04）：把需求主流程四阶段（分析→方案→拆分→执行）聚到一屏，
// 每阶段显示最近会话/产出状态与阶段动作；产出可直接预览，上下游由服务端注入上下文串联。
import { useState } from 'react'
import { Button, Card, Popconfirm, Space, Table, Tag, Typography, message } from 'antd'
import {
  ApartmentOutlined,
  FileDoneOutlined,
  FileSearchOutlined,
  PlayCircleOutlined,
} from '@ant-design/icons'
import type { ColumnsType } from 'antd/es/table'
import { flowAnalyze, flowDesign, flowSplit } from '../../api'
import type { Design, RequirementOverview } from '../../types'
import { fmtTime } from '../../../../shared/utils/format'
import { showError } from '../../../../shared/utils/showError'
import DocPreviewModal from './DocPreviewModal'
import SplitDraftDrawer from './SplitDraftDrawer'
import { designStatusColor, useDesignActions } from './useDesignActions'

/** 会话视为「进行中」的状态（其余为终态） */
const ACTIVE_SESSION_STATES = ['RUNNING', 'WAITING_INPUT', 'WAITING_AUTH', 'QUEUED']

type FlowSessionView = RequirementOverview['sessions'][number]

/** 按标记取最近一次流程会话（createdAt 最新在前） */
function latestFlowSession(sessions: FlowSessionView[], marker: string): FlowSessionView | undefined {
  return sessions
    .filter((s) => s.taskSpec && s.taskSpec.startsWith(marker))
    .sort((a, b) => (a.createdAt < b.createdAt ? 1 : -1))[0]
}

function sessionTag(s?: FlowSessionView) {
  if (!s) return null
  const active = ACTIVE_SESSION_STATES.includes(s.status)
  return (
    <a href={`/sessions/${s.id}`} target="_blank" rel="noreferrer">
      <Tag color={active ? 'processing' : s.status === 'DONE' ? 'success' : 'default'}>
        会话 {active ? '进行中' : s.status}
      </Tag>
    </a>
  )
}

export default function FlowTab({ projectId, requirementId, overview, designs, onChanged, onGotoWorkItems }: {
  projectId: string
  requirementId: string
  overview: RequirementOverview
  designs: Design[]
  onChanged: () => void
  onGotoWorkItems: () => void
}) {
  const [busy, setBusy] = useState(false)
  const [draftOpen, setDraftOpen] = useState(false)
  const { preview, closePreview, setStatus, remove, previewDesign, previewDoc } =
    useDesignActions(projectId, requirementId, onChanged)

  // 阶段动作触发：只做触发与提示，状态门禁以后端报错为准
  const runFlow = async (label: string, fn: () => Promise<{ id: string }>) => {
    setBusy(true)
    try {
      await fn()
      message.success(`${label}会话已启动，完成后会通知你确认产出`)
      onChanged()
    } catch (e) {
      showError(e)
    } finally {
      setBusy(false)
    }
  }

  // ① 需求分析
  const analysisSession = latestFlowSession(overview.sessions, '[flow:analyze]')
  const analysisDoc = overview.docs
    .filter((d) => d.kind === 'analysis')
    .sort((a, b) => b.id - a.id)[0]

  // ③ 工作拆分
  const splitSession = latestFlowSession(overview.sessions, '[flow:split]')

  // ④ 执行（DESIGN 型工作单元是方案载体，不计执行进度）
  const execItems = overview.workItems.filter((w) => w.type !== 'DESIGN')
  const doneItems = execItems.filter((w) => w.status === 'DONE').length

  const designColumns: ColumnsType<Design> = [
    { title: '版本', dataIndex: 'version', width: 60, render: (v: number) => `v${v}` },
    {
      title: '状态', dataIndex: 'status', width: 100,
      render: (s: Design['status']) => <Tag color={designStatusColor(s)}>{s}</Tag>,
    },
    {
      title: '创建', dataIndex: 'createdAt', width: 160,
      render: (v: string) => <span style={{ fontSize: 12 }}>{fmtTime(v)}</span>,
    },
    {
      title: '操作', key: 'ops',
      render: (_, d) => (
        <Space size={4}>
          <Button size="small" type="link" disabled={!d.docId} onClick={() => previewDesign(d)}>查看</Button>
          {d.status === 'DRAFT' && (
            <>
              <Popconfirm title={`确认方案 v${d.version}？`} description="确认后可作为拆分工作单元的依据"
                onConfirm={() => setStatus(d, 'CONFIRMED')}>
                <Button size="small" type="link">确认</Button>
              </Popconfirm>
              <Button size="small" type="link" onClick={() => setStatus(d, 'DISCARDED')}>废弃</Button>
            </>
          )}
          {d.status === 'CONFIRMED' && (
            <Button size="small" type="link" onClick={() => setStatus(d, 'DISCARDED')}>废弃</Button>
          )}
          {d.status === 'DISCARDED' && (
            <Button size="small" type="link" onClick={() => setStatus(d, 'DRAFT')}>恢复</Button>
          )}
          <Button size="small" type="link" danger onClick={() => remove(d)}>删除</Button>
        </Space>
      ),
    },
  ]

  return (
    <Space direction="vertical" size={12} style={{ width: '100%' }}>
      <Card
        size="small"
        title={<Space size={8}><FileSearchOutlined />① 需求分析</Space>}
        extra={
          <Space size={8}>
            {sessionTag(analysisSession)}
            {analysisDoc && (
              <Button size="small" onClick={() => previewDoc(analysisDoc.id, `需求分析 · ${analysisDoc.title}`)}>
                查看分析（v{analysisDoc.currentVersion}）
              </Button>
            )}
            <Button
              size="small"
              type={analysisDoc ? 'default' : 'primary'}
              loading={busy}
              onClick={() => runFlow('分析', () => flowAnalyze(projectId, requirementId))}
            >
              {analysisDoc ? '重新分析' : '开始分析'}
            </Button>
          </Space>
        }
      >
        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
          AI 分析影响面/复杂度/风险，产出落成「需求分析」文档；重新分析在原文档上追加新版本。
          分析结论会自动注入后续方案设计与拆分会话。
        </Typography.Text>
      </Card>

      <Card
        size="small"
        title={<Space size={8}><FileDoneOutlined />② 方案设计</Space>}
        extra={
          <Button size="small" type="primary" loading={busy}
            onClick={() => runFlow('方案设计', () => flowDesign(projectId, requirementId))}>
            生成方案（AI）
          </Button>
        }
      >
        {designs.length === 0 ? (
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            暂无方案。生成方案后自动登记为 DRAFT，确认（CONFIRMED）后即可 AI 拆分；简单需求可跳过方案直接拆分。
          </Typography.Text>
        ) : (
          <Table rowKey="id" size="small" columns={designColumns} dataSource={designs} pagination={false} />
        )}
      </Card>

      <Card
        size="small"
        title={<Space size={8}><ApartmentOutlined />③ 工作拆分</Space>}
        extra={
          <Space size={8}>
            {sessionTag(splitSession)}
            <Button size="small" onClick={() => setDraftOpen(true)}>拆分草稿</Button>
            <Button size="small" type="primary" loading={busy}
              onClick={() => runFlow('拆分', () => flowSplit(projectId, requirementId))}>
              AI 拆分工作单元
            </Button>
          </Space>
        }
      >
        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
          按需求（+已确认方案与分析结论）拆分工作单元，产出草稿由人编辑后确认固化；也可在「工作单元」Tab 手工新建。
        </Typography.Text>
      </Card>

      <Card
        size="small"
        title={<Space size={8}><PlayCircleOutlined />④ 执行</Space>}
        extra={<Button size="small" onClick={onGotoWorkItems}>前往工作单元</Button>}
      >
        <Space size={8}>
          <Tag color={execItems.length > 0 && doneItems === execItems.length ? 'success' : 'default'}>
            {doneItems}/{execItems.length} 工作单元完成
          </Tag>
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            全部完结后需求自动进入验收（ACCEPTANCE）。
          </Typography.Text>
        </Space>
      </Card>

      <DocPreviewModal preview={preview} onClose={closePreview} />
      <SplitDraftDrawer
        projectId={projectId}
        requirementId={requirementId}
        open={draftOpen}
        onClose={() => setDraftOpen(false)}
        onConfirmed={onChanged}
      />
    </Space>
  )
}
