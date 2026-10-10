// CAP-73 需求开发中验证 Tab：验证历史 + 人工「立即验证」入口 + run 详情（阶段耗时/失败摘要/日志）。
// 与 agent 自主触发同链同限额同互斥；未绑定验证环境/项目开关关闭时给出引导而非静默置灰。
import { useCallback, useEffect, useState } from 'react'
import {
  Alert,
  Button,
  Descriptions,
  Drawer,
  Form,
  Input,
  Modal,
  Space,
  Switch,
  Table,
  Tag,
  Tooltip,
  Typography,
  message,
} from 'antd'
import type { ColumnsType } from 'antd/es/table'
import { PlayCircleOutlined, ReloadOutlined } from '@ant-design/icons'
import { getVerifyRun, listVerifyRuns, triggerVerify } from '../api'
import type { Requirement, VerifyRun, VerifyRunStatus } from '../types'
import { listEnvironments } from '../../projects/api'
import type { ProjectEnvironment } from '../../projects/types'
import { fmtTime } from '../../../shared/utils/format'
import { showError } from '../../../shared/utils/showError'
import { LIST_PAGINATION } from '../../../shared/utils/table'

const STATUS_COLOR: Record<VerifyRunStatus, string> = {
  RUNNING: 'processing',
  SUCCESS: 'success',
  FAILED: 'error',
}

const STATUS_LABEL: Record<VerifyRunStatus, string> = {
  RUNNING: '进行中',
  SUCCESS: '成功',
  FAILED: '失败',
}

const STAGE_LABEL: Record<string, string> = {
  PUSHING: '推送',
  BUILDING: '构建',
  PATCHING: '补丁',
  TESTING: '冒烟',
}

function fmtDurationMs(ms?: number): string {
  if (ms == null) return '-'
  if (ms < 1000) return `${ms}ms`
  const s = Math.round(ms / 1000)
  if (s < 60) return `${s}s`
  return `${Math.floor(s / 60)}m${s % 60}s`
}

/** 阶段耗时 map（stage_timings_json）→ 可读文案 */
function fmtStageTimings(json?: string): string {
  if (!json) return '-'
  try {
    const m = JSON.parse(json) as Record<string, number>
    return Object.entries(m)
      .map(([k, v]) => `${STAGE_LABEL[k] ?? k} ${fmtDurationMs(v)}`)
      .join(' / ')
  } catch {
    return '-'
  }
}

export default function VerifyTab({ requirement }: { requirement: Requirement }) {
  const projectId = requirement.projectId
  const [runs, setRuns] = useState<VerifyRun[]>([])
  const [loading, setLoading] = useState(false)
  const [environments, setEnvironments] = useState<ProjectEnvironment[]>([])
  const [triggerOpen, setTriggerOpen] = useState(false)
  const [triggering, setTriggering] = useState(false)
  const [detail, setDetail] = useState<VerifyRun | null>(null)
  const [detailLoading, setDetailLoading] = useState(false)
  const [form] = Form.useForm<{ note?: string; modules?: string; force?: boolean }>()

  const boundEnv = requirement.verifyEnvironmentId != null
    ? environments.find((e) => e.id === requirement.verifyEnvironmentId)
    : undefined
  const unbound = requirement.verifyEnvironmentId == null

  const reload = useCallback(async () => {
    setLoading(true)
    try {
      setRuns(await listVerifyRuns(projectId, requirement.id))
    } catch (e) {
      showError(e, '加载验证历史失败')
    } finally {
      setLoading(false)
    }
  }, [projectId, requirement.id])

  useEffect(() => {
    reload()
    listEnvironments(projectId).then(setEnvironments).catch(() => {})
  }, [reload, projectId])

  // 有进行中的 run 时轮询（3s），终态即停
  const hasRunning = runs.some((r) => r.status === 'RUNNING')
  useEffect(() => {
    if (!hasRunning) return
    const t = setInterval(reload, 3000)
    return () => clearInterval(t)
  }, [hasRunning, reload])

  const openDetail = async (r: VerifyRun) => {
    setDetail(r)
    setDetailLoading(true)
    try {
      setDetail(await getVerifyRun(r.id))
    } catch (e) {
      showError(e, '加载验证详情失败')
    } finally {
      setDetailLoading(false)
    }
  }

  const onTrigger = async (v: { note?: string; modules?: string; force?: boolean }) => {
    setTriggering(true)
    try {
      const run = await triggerVerify(projectId, requirement.id, v)
      message.success(`验证 #${run.id} 已受理（推送→构建→补丁→冒烟，结果自动回灌开发会话）`)
      setTriggerOpen(false)
      form.resetFields()
      reload()
    } catch (e) {
      showError(e, '触发验证失败')
    } finally {
      setTriggering(false)
    }
  }

  const columns: ColumnsType<VerifyRun> = [
    { title: 'ID', dataIndex: 'id', width: 70, render: (v: number) => `#${v}` },
    {
      title: '状态', dataIndex: 'status', width: 90,
      render: (v: VerifyRunStatus, r) => (
        <Space size={4}>
          <Tag color={STATUS_COLOR[v]}>{STATUS_LABEL[v]}</Tag>
          {r.stage && v !== 'SUCCESS' && <Tag>{STAGE_LABEL[r.stage] ?? r.stage}</Tag>}
        </Space>
      ),
    },
    {
      title: '触发', dataIndex: 'triggerType', width: 110,
      render: (v: string, r) => (
        <Tooltip title={r.triggeredBy}>
          <Tag color={v === 'AGENT' ? 'purple' : 'blue'}>{v === 'AGENT' ? 'AI' : '人工'}</Tag>
        </Tooltip>
      ),
    },
    {
      title: '提交', dataIndex: 'commitSha', width: 110,
      render: (v?: string, r?) => (
        <Space size={4}>
          {v ? <Typography.Text code>{v.slice(0, 8)}</Typography.Text> : '-'}
          {r?.pushStatus === 'SKIPPED' && (
            <Tooltip title="该需求无会话工作区（纯远端开发），验的是远端 HEAD">
              <Tag>未推送</Tag>
            </Tooltip>
          )}
        </Space>
      ),
    },
    {
      title: '变更文件', dataIndex: 'filesChanged', width: 90,
      render: (v?: number) => (v != null ? v : '-'),
    },
    { title: '耗时', dataIndex: 'durationMs', width: 90, render: fmtDurationMs },
    { title: '备注', dataIndex: 'note', ellipsis: true, render: (v?: string) => v || '-' },
    {
      title: '失败摘要', dataIndex: 'failureSummary', ellipsis: true,
      render: (v?: string) => v || '-',
    },
    { title: '发起时间', dataIndex: 'createdAt', width: 160, render: (v: string) => fmtTime(v) },
    {
      title: '', key: 'act', width: 70,
      render: (_, r) => <Button size="small" onClick={() => openDetail(r)}>详情</Button>,
    },
  ]

  return (
    <div>
      <Space style={{ marginBottom: 12, display: 'flex', justifyContent: 'space-between', width: '100%' }}>
        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
          开发中验证：需求分支推送 → 增量构建 → 环境补丁更新 → 冒烟，结果回灌开发会话
          {boundEnv && <>（验证环境：<Tag color="blue">{boundEnv.name}</Tag>）</>}
        </Typography.Text>
        <Space>
          <Button icon={<ReloadOutlined />} onClick={reload}>刷新</Button>
          <Tooltip title={unbound ? '先在「编辑」里绑定验证环境（PROD 除外）' : undefined}>
            <span>
              <Button
                type="primary"
                icon={<PlayCircleOutlined />}
                disabled={unbound}
                onClick={() => setTriggerOpen(true)}
              >
                立即验证
              </Button>
            </span>
          </Tooltip>
        </Space>
      </Space>
      {unbound && (
        <Alert
          type="info"
          showIcon
          style={{ marginBottom: 12 }}
          message="未绑定验证环境"
          description="绑定后 AI 在开发会话里 commit 改动即可自主触发验证（结果自动回灌会话）；也可在此人工触发。前置：环境已配补丁目录（项目设置-环境）、构建配置已配产物目录（构建-构建配置）、项目已开「开发中验证」开关（编辑项目）。"
        />
      )}
      <Table
        rowKey="id"
        size="small"
        columns={columns}
        dataSource={runs}
        loading={loading}
        pagination={LIST_PAGINATION}
        locale={{ emptyText: unbound ? '绑定验证环境后发起第一次验证' : '暂无验证记录' }}
      />

      <Modal
        title="立即验证"
        open={triggerOpen}
        onCancel={() => setTriggerOpen(false)}
        onOk={() => form.submit()}
        okText="触发验证"
        confirmLoading={triggering}
        width={520}
        destroyOnHidden
      >
        <Form form={form} layout="vertical" onFinish={onTrigger} preserve={false}>
          <Form.Item label="验证备注" name="note">
            <Input placeholder="如 修了登录态过期后的跳转（可选）" maxLength={200} />
          </Form.Item>
          <Form.Item
            label="编译范围（可选）"
            name="modules"
            extra="按本次改动判断，如 user-service,common；原样透传 VERIFY_MODULES 给构建步骤。留空 = 全量构建，永远正确"
          >
            <Input placeholder="留空 = 全量构建" />
          </Form.Item>
          <Form.Item
            label="跳过无新提交校验"
            name="force"
            valuePropName="checked"
            extra="远端分支无新提交时仍强制验证（用于失败修复环境后重试同一 commit）"
          >
            <Switch />
          </Form.Item>
        </Form>
      </Modal>

      <Drawer
        title={detail ? `验证详情 #${detail.id}` : '验证详情'}
        width={640}
        open={detail != null}
        onClose={() => setDetail(null)}
      >
        {detail && (
          <Space direction="vertical" size={12} style={{ width: '100%' }}>
            <Descriptions size="small" column={2} bordered>
              <Descriptions.Item label="状态">
                <Tag color={STATUS_COLOR[detail.status]}>{STATUS_LABEL[detail.status]}</Tag>
              </Descriptions.Item>
              <Descriptions.Item label="阶段">
                {detail.stage ? (STAGE_LABEL[detail.stage] ?? detail.stage) : '-'}
              </Descriptions.Item>
              <Descriptions.Item label="触发">
                <Tag color={detail.triggerType === 'AGENT' ? 'purple' : 'blue'}>
                  {detail.triggerType === 'AGENT' ? 'AI' : '人工'}
                </Tag>
                {detail.triggeredBy ?? '-'}
              </Descriptions.Item>
              <Descriptions.Item label="验证环境">
                {environments.find((e) => e.id === detail.environmentId)?.name ?? detail.environmentId}
              </Descriptions.Item>
              <Descriptions.Item label="提交">
                {detail.commitSha ? <Typography.Text code>{detail.commitSha.slice(0, 10)}</Typography.Text> : '-'}
                {detail.pushStatus === 'SKIPPED' && <Tag style={{ marginLeft: 4 }}>未推送</Tag>}
              </Descriptions.Item>
              <Descriptions.Item label="变更文件数">{detail.filesChanged ?? '-'}</Descriptions.Item>
              <Descriptions.Item label="总耗时">{fmtDurationMs(detail.durationMs)}</Descriptions.Item>
              <Descriptions.Item label="阶段耗时">{fmtStageTimings(detail.stageTimingsJson)}</Descriptions.Item>
              <Descriptions.Item label="编译范围">{detail.modulesHint || '全量'}</Descriptions.Item>
              <Descriptions.Item label="冒烟">{detail.testRunId ? `测试运行 #${detail.testRunId}` : '未执行'}</Descriptions.Item>
              <Descriptions.Item label="发起时间" span={2}>{fmtTime(detail.createdAt)}</Descriptions.Item>
              {detail.note && (
                <Descriptions.Item label="备注" span={2}>{detail.note}</Descriptions.Item>
              )}
            </Descriptions>
            {detail.failureSummary && (
              <Alert type="error" showIcon message="失败摘要"
                description={<pre style={{ whiteSpace: 'pre-wrap', margin: 0 }}>{detail.failureSummary}</pre>} />
            )}
            <Typography.Text strong>全链日志</Typography.Text>
            {detailLoading ? (
              <Typography.Text type="secondary">加载中…</Typography.Text>
            ) : detail.logsText ? (
              <pre style={{
                background: '#0d1117', color: '#c9d1d9', padding: 12, borderRadius: 6,
                fontSize: 12, maxHeight: 420, overflow: 'auto', whiteSpace: 'pre-wrap', margin: 0,
              }}>
                {detail.logsText}
              </pre>
            ) : (
              <Typography.Text type="secondary">（无日志）</Typography.Text>
            )}
          </Space>
        )}
      </Drawer>
    </div>
  )
}
