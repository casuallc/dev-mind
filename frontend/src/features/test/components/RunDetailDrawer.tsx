// 测试运行详情 Drawer：运行中走 WS 实时结果流，终态轮询刷新 summary。
// CAP-104：结论/阻塞/错误计数、失败分类、断言 expected/actual、证据链校验与下载、人工复核判定。
import { Alert, Button, Descriptions, Drawer, Input, Modal, Select, Space, Table, Tag, Typography, message } from 'antd'
import { useEffect, useRef, useState } from 'react'
import type { ColumnsType } from 'antd/es/table'
import { BugOutlined, DownloadOutlined, SafetyCertificateOutlined } from '@ant-design/icons'
import {
  createRunWorkItem,
  downloadEvidence,
  downloadRunEvidenceZip,
  getRun,
  listEvidence,
  requestEvidenceToken,
  reviewRunResult,
  verifyEvidence,
} from '../api'
import type {
  AssertionResult,
  CaseResult,
  CaseResultStatus,
  EvidenceChainVerification,
  TestEvidence,
  TestRun,
} from '../types'
import {
  CONCLUSION_COLOR,
  FAILURE_CATEGORY_COLOR,
  RESULT_COLOR,
  REVIEW_COLOR,
  STATUS_COLOR,
} from '../constants'
import { DRAWER_SCREEN } from '../../../shared/utils/overlay'
import { fmtBytes, fmtTime } from '../../../shared/utils/format'
import { showError } from '../../../shared/utils/showError'
import { usePermissions } from '../../auth/usePermissions'

type ManualReview = 'CONFIRMED' | 'REJECTED' | 'PASS' | 'FAIL'

const MANUAL_REVIEW_OPTIONS: { value: ManualReview; label: string; desc: string }[] = [
  { value: 'CONFIRMED', label: '确认结果', desc: '保留自动判定，仅标记已人工确认' },
  { value: 'REJECTED', label: '否决结果', desc: '保留自动判定，标记人工否决（需说明）' },
  { value: 'PASS', label: '改判通过', desc: '将失败/阻塞改判为通过并清除失败分类' },
  { value: 'FAIL', label: '改判失败', desc: '将结果改判为失败（分类为断言失败）' },
]

export default function RunDetailDrawer({ record, onClose, onChanged, onOpenText, onIssues }: {
  record: TestRun | null
  onClose: () => void
  onChanged: (r: TestRun) => void
  onOpenText: (runId: number, title: string) => void
  onIssues: (runId: number) => void
}) {
  const { canAction } = usePermissions()
  const canReview = canAction('project.tests.action.review-case')
  const canManageRun = canAction('project.tests.action.manage-run')
  const canExportEvidence = canAction('project.tests.action.export-evidence')
  const [d, setD] = useState<TestRun | null>(record)
  const [connected, setConnected] = useState(false)
  // CAP-69：脚本套件运行的 exec 实时日志帧（{type:"log",line}）在此累积
  const [logLines, setLogLines] = useState<string[]>([])
  const latestRef = useRef<TestRun | null>(record)
  const logRef = useRef<HTMLPreElement | null>(null)
  // CAP-104 证据链
  const [evidence, setEvidence] = useState<TestEvidence[]>([])
  const [evidenceLoading, setEvidenceLoading] = useState(false)
  const [verification, setVerification] = useState<EvidenceChainVerification | null>(null)
  const [evidenceTick, setEvidenceTick] = useState(0)
  // CAP-104 人工复核
  const [reviewFor, setReviewFor] = useState<CaseResult | null>(null)
  const [reviewKind, setReviewKind] = useState<ManualReview>('CONFIRMED')
  const [reviewNote, setReviewNote] = useState('')
  const [reviewing, setReviewing] = useState(false)
  const [tokenModal, setTokenModal] = useState<{ token: string; uploadUrl: string; expiresAt: string } | null>(null)

  useEffect(() => {
    latestRef.current = d
  }, [d])

  // 日志面板自动滚到底
  useEffect(() => {
    const el = logRef.current
    if (el) el.scrollTop = el.scrollHeight
  }, [logLines])

  // 证据只读列表：打开即拉，链校验按需触发；状态变化（如终态刷新）也重拉
  useEffect(() => {
    const rid = record?.id
    if (!rid) {
      setEvidence([])
      setVerification(null)
      return
    }
    setEvidenceLoading(true)
    listEvidence(rid)
      .then(setEvidence)
      .catch(() => setEvidence([]))
      .finally(() => setEvidenceLoading(false))
  }, [record?.id, record?.status, evidenceTick])

  useEffect(() => {
    setD(record)
    setConnected(false)
    setLogLines([])
    setVerification(null)
    if (!record) return
    latestRef.current = record

    const active = record.status === 'QUEUED' || record.status === 'RUNNING'
    if (active) {
      const proto = location.protocol === 'https:' ? 'wss' : 'ws'
      const ws = new WebSocket(`${proto}://${location.host}/ws/test-runs/${record.id}/stream`)
      ws.onopen = () => setConnected(true)
      ws.onmessage = (msg) => {
        try {
          const f = JSON.parse(msg.data)
          if (f.type === 'snapshot') {
            setD((cur) => (cur ? { ...cur, status: f.status, baseUrl: f.baseUrl ?? cur.baseUrl, results: f.results ?? cur.results } : cur))
          } else if (f.type === 'result') {
            setD((cur) => (cur ? { ...cur, results: [...cur.results, f.result] } : cur))
          } else if (f.type === 'log' && typeof f.line === 'string') {
            // 封顶 3000 行，防长任务把抽屉撑爆
            setLogLines((cur) => (cur.length >= 3000 ? [...cur.slice(-2999), f.line] : [...cur, f.line]))
          } else if (f.type === 'done') {
            setConnected(false)
            setD((cur) => (cur ? { ...cur, status: f.status } : cur))
            setEvidenceTick((t) => t + 1)
            ws.close()
            // done 帧只带状态，汇总/结论需拉一次完整运行，否则详情停留创建时的空汇总
            getRun(record.id).then((latest) => {
              setD(latest)
              latestRef.current = latest
              onChanged(latest)
            }).catch(() => {})
          }
        } catch {
          /* 忽略坏帧 */
        }
      }
      ws.onclose = () => setConnected(false)
      return () => ws.close()
    }

    // 终态：先拉一次完整结果，再定时刷新最终 summary/时间
    getRun(record.id).then((latest) => {
      if (latest.results?.length) {
        setD(latest)
        latestRef.current = latest
      }
    }).catch(() => {})
    const timer = setInterval(() => {
      getRun(record.id).then((latest) => {
        const cur = latestRef.current
        if (!cur || JSON.stringify(latest) !== JSON.stringify(cur)) {
          setD(latest)
          latestRef.current = latest
          onChanged(latest)
        }
      }).catch(() => {})
    }, 4000)
    return () => clearInterval(timer)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [record])

  const openReview = (r: CaseResult) => {
    setReviewFor(r)
    setReviewKind('CONFIRMED')
    setReviewNote('')
  }

  const submitReview = async () => {
    if (!d || !reviewFor) return
    setReviewing(true)
    try {
      await reviewRunResult(d.id, reviewFor.id, reviewKind, reviewNote.trim() || undefined)
      message.success('人工判定已记录')
      setReviewFor(null)
      const latest = await getRun(d.id)
      setD(latest)
      latestRef.current = latest
      onChanged(latest)
      setEvidenceTick((t) => t + 1)
    } catch (e) {
      showError(e, '人工判定失败')
    } finally {
      setReviewing(false)
    }
  }

  const onVerify = async () => {
    if (!d) return
    try {
      const v = await verifyEvidence(d.id)
      setVerification(v)
      if (v.valid) message.success(`证据链完整（${v.count} 条）`)
    } catch (e) {
      showError(e, '证据链校验失败')
    }
  }

  const onDownload = async (ev: TestEvidence) => {
    try {
      await downloadEvidence(ev.id, ev.filename ?? `evidence-${ev.id}`)
    } catch (e) {
      showError(e, '下载失败')
    }
  }

  const onExportZip = async () => {
    if (!d) return
    try {
      await downloadRunEvidenceZip(d.id)
    } catch (e) {
      showError(e, '导出证据包失败')
    }
  }

  const onEvidenceToken = async () => {
    if (!d) return
    try {
      const t = await requestEvidenceToken(d.id, { kind: 'ATTACHMENT' })
      setTokenModal({ token: t.token, uploadUrl: t.uploadUrl, expiresAt: t.expiresAt })
    } catch (e) {
      showError(e, '申请上传凭据失败')
    }
  }

  const onCreateWorkItem = async (r: CaseResult) => {
    if (!d) return
    try {
      await createRunWorkItem(d.id, { resultId: r.id })
      message.success('工作单元已创建')
      const latest = await getRun(d.id)
      setD(latest)
      latestRef.current = latest
      onChanged(latest)
    } catch (e) {
      showError(e, '创建工作单元失败')
    }
  }

  const resultColumns: ColumnsType<CaseResult> = [
    { title: '#', dataIndex: 'sort', width: 44 },
    { title: '用例', dataIndex: 'name', ellipsis: true },
    {
      title: '状态', dataIndex: 'status', width: 80,
      render: (v: CaseResultStatus) => <Tag color={RESULT_COLOR[v]}>{v}</Tag>,
    },
    {
      title: '失败分类', dataIndex: 'failureCategory', width: 110,
      render: (v: keyof typeof FAILURE_CATEGORY_COLOR | null) => (v ? <Tag color={FAILURE_CATEGORY_COLOR[v]}>{v}</Tag> : '-'),
    },
    { title: '节点', dataIndex: 'nodeId', width: 100, ellipsis: true, render: (v: string | null) => v || '-' },
    { title: '请求', dataIndex: 'requestSummary', width: 200, ellipsis: true, render: (v: string | null) => v || '-' },
    { title: '响应', dataIndex: 'responseSummary', width: 200, ellipsis: true, render: (v: string | null) => v || '-' },
    { title: '耗时', dataIndex: 'duration', width: 80, render: (v: number | null) => (v != null ? `${v}ms` : '-') },
    {
      title: '人工', dataIndex: 'manualReview', width: 100,
      render: (v: string | null) => (v
        ? <Tag color={REVIEW_COLOR[v === 'REJECTED' ? 'REJECTED' : 'APPROVED']}>{v === 'PASS' ? '改判通过' : v === 'FAIL' ? '改判失败' : v === 'CONFIRMED' ? '已确认' : '已否决'}</Tag>
        : '-'),
    },
    {
      title: '操作',
      key: 'act',
      width: 190,
      render: (_, r) => {
        const terminal = d != null && d.status !== 'QUEUED' && d.status !== 'RUNNING'
        const failed = ['fail', 'error', 'blocked'].includes(r.status)
        if (!terminal) return null
        return (
          <Space size={4} wrap>
            {canReview && <Button size="small" onClick={() => openReview(r)}>人工判定</Button>}
            {canManageRun && failed && (
              <Button size="small" onClick={() => onCreateWorkItem(r)}>建工作项</Button>
            )}
          </Space>
        )
      },
    },
  ]

  const evidenceColumns: ColumnsType<TestEvidence> = [
    { title: 'Seq', dataIndex: 'seqNo', width: 60 },
    { title: '类型', dataIndex: 'kind', width: 130, render: (v: string) => <Tag>{v}</Tag> },
    { title: '文件', dataIndex: 'filename', ellipsis: true, render: (v: string | null) => v || '-' },
    { title: '节点', dataIndex: 'nodeId', width: 100, ellipsis: true, render: (v: string | null) => v || '-' },
    { title: '大小', dataIndex: 'sizeBytes', width: 90, render: (v: number | null) => (v != null ? fmtBytes(v) : '-') },
    {
      title: 'SHA256', dataIndex: 'sha256', width: 130, ellipsis: true,
      render: (v: string) => <Typography.Text code style={{ fontSize: 11 }}>{v?.slice(0, 12) ?? '-'}</Typography.Text>,
    },
    { title: '时间', dataIndex: 'createdAt', width: 170, render: (v: string) => fmtTime(v) },
    {
      title: '操作', key: 'act', width: 80,
      render: (_, ev) => <Button size="small" icon={<DownloadOutlined />} onClick={() => void onDownload(ev)}>下载</Button>,
    },
  ]

  const renderExpanded = (r: CaseResult) => (
    <Space direction="vertical" size={8} style={{ width: '100%' }}>
      <Descriptions size="small" column={3}>
        <Descriptions.Item label="命令">{r.command ? <Typography.Text code style={{ fontSize: 12 }}>{r.command}</Typography.Text> : '-'}</Descriptions.Item>
        <Descriptions.Item label="退出码">{r.exitCode ?? '-'}</Descriptions.Item>
        <Descriptions.Item label="开始 / 结束">
          {r.startedAt ? fmtTime(r.startedAt) : '-'} → {r.finishedAt ? fmtTime(r.finishedAt) : '-'}
        </Descriptions.Item>
        <Descriptions.Item label="错误" span={3}>{r.error || '-'}</Descriptions.Item>
        {r.manualNote && <Descriptions.Item label="人工备注" span={3}>{r.manualNote}</Descriptions.Item>}
        {r.envSnapshot && Object.keys(r.envSnapshot).length > 0 && (
          <Descriptions.Item label="环境快照" span={3}>
            <Typography.Text code style={{ fontSize: 11 }}>{JSON.stringify(r.envSnapshot)}</Typography.Text>
          </Descriptions.Item>
        )}
      </Descriptions>
      {r.program && (
        <Space direction="vertical" size={6} style={{ width: '100%' }}>
          <Typography.Text strong style={{ fontSize: 12 }}>程序输出（契约 v1）</Typography.Text>
          <Descriptions size="small" column={4}>
            <Descriptions.Item label="程序资产">
              #{r.program.assetId} {r.program.name ?? '-'}@{r.program.version ?? '-'}
            </Descriptions.Item>
            <Descriptions.Item label="运行时">
              {r.program.runtime ?? '-'}{r.program.runtimeVersion ? ` ${r.program.runtimeVersion}` : ''}
            </Descriptions.Item>
            <Descriptions.Item label="入口">{r.program.entrypoint ?? '-'}</Descriptions.Item>
            <Descriptions.Item label="目标">
              {r.program.targetOs ?? 'ANY'}/{r.program.targetArch ?? 'ANY'}
            </Descriptions.Item>
            <Descriptions.Item label="程序判定" span={4}>
              {r.program.status ? <Tag>{r.program.status}</Tag> : '-'}
              {r.program.message || ''}
            </Descriptions.Item>
            {Object.keys(r.program.metrics ?? {}).length > 0 && (
              <Descriptions.Item label="metrics" span={4}>
                <Typography.Text code style={{ fontSize: 11 }}>
                  {JSON.stringify(r.program.metrics)}
                </Typography.Text>
              </Descriptions.Item>
            )}
          </Descriptions>
          {r.program.artifacts?.length > 0 && (
            <Table
              rowKey="path"
              size="small"
              pagination={false}
              dataSource={r.program.artifacts}
              columns={[
                { title: '产物', dataIndex: 'path', ellipsis: true },
                { title: '大小', dataIndex: 'sizeBytes', width: 100, render: (v: number) => fmtBytes(v) },
                {
                  title: 'SHA256', dataIndex: 'sha256', width: 150, ellipsis: true,
                  render: (v: string) => <Typography.Text code style={{ fontSize: 11 }}>{v?.slice(0, 12) ?? '-'}</Typography.Text>,
                },
              ]}
            />
          )}
        </Space>
      )}
      {r.assertions?.length ? (
        <Table<AssertionResult>
          rowKey={(a) => `${a.source}-${a.selector ?? ''}-${a.op}`}
          size="small"
          pagination={false}
          dataSource={r.assertions}
          columns={[
            { title: '结果', dataIndex: 'pass', width: 70, render: (v: boolean) => <Tag color={v ? 'green' : 'red'}>{v ? 'pass' : 'fail'}</Tag> },
            { title: '断言', key: 'a', width: 260, render: (_, a) => <Typography.Text code style={{ fontSize: 12 }}>{a.source}{a.selector ? `:${a.selector}` : ''} {a.op}</Typography.Text> },
            { title: '期望', dataIndex: 'expected', width: 180, ellipsis: true, render: (v: string | null) => v ?? '-' },
            { title: '实际', dataIndex: 'actual', width: 180, ellipsis: true, render: (v: string | null) => v ?? '-' },
            { title: '说明', dataIndex: 'message', ellipsis: true, render: (v: string | null) => v || '-' },
          ]}
        />
      ) : (
        <Typography.Text type="secondary" style={{ fontSize: 12 }}>无自动断言（人工判定或旧用例）</Typography.Text>
      )}
    </Space>
  )

  return (
    <Drawer
      title={d ? (
        <Space wrap>
          <span>测试运行 #{d.id}</span>
          <Tag color={STATUS_COLOR[d.status]}>{d.status}</Tag>
          {d.conclusion && <Tag color={CONCLUSION_COLOR[d.conclusion]}>{d.conclusion}</Tag>}
          {d.runScope && <Tag>{d.runScope}</Tag>}
          {d.planRunId && <Tag color="geekblue">方案运行 #{d.planRunId}</Tag>}
          {d.attempt != null && <Tag>第 {d.attempt} 次</Tag>}
          {d.shardKey && <Tag>{d.shardKey}</Tag>}
          {d.triggeredBy === 'deploy' && <Tag color="purple">自动回归</Tag>}
          {connected && <Tag color="cyan">实时</Tag>}
        </Space>
      ) : '测试详情'}
      width={DRAWER_SCREEN}
      open={!!record}
      onClose={onClose}
    >
      {d && (
        <Space direction="vertical" size={12} style={{ width: '100%' }}>
          {d.errorSummary && <Alert type="error" showIcon message={d.errorSummary} />}
          <Space wrap>
            <Button size="small" onClick={() => onOpenText(d.id, '报告')}>报告</Button>
            <Button size="small" onClick={() => onOpenText(d.id, '日志')}>日志</Button>
            {canExportEvidence && <Button size="small" onClick={onExportZip}>证据包</Button>}
            {canExportEvidence && <Button size="small" onClick={onEvidenceToken}>上传凭据</Button>}
            {d.status === 'FAILED' && (
              <Button size="small" danger icon={<BugOutlined />} onClick={() => onIssues(d.id)}>缺陷线索</Button>
            )}
          </Space>
          <Space wrap size={8}>
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              目标 {d.baseUrl || '-'} · 节点 {d.agentNodeId ?? '-'} · 环境 {d.environmentId ? `#${d.environmentId}` : '-'} · 部署 #{d.deploymentId ?? '-'}
            </Typography.Text>
            {d.summary && (
              <span style={{ fontSize: 12 }}>
                {d.summary.total} 项 · <span style={{ color: '#52c41a' }}>{d.summary.passed} 过</span>{' '}
                <span style={{ color: d.summary.failed ? '#ff4d4f' : undefined }}>{d.summary.failed} 败</span>{' '}
                <span style={{ color: '#fa8c16' }}>{d.summary.skipped} 跳</span>
                {d.summary.blocked > 0 && <span style={{ color: '#d48806' }}> {d.summary.blocked} 阻塞</span>}
                {d.summary.error > 0 && <span style={{ color: '#c41d7f' }}> {d.summary.error} 错误</span>}
              </span>
            )}
            {d.reportDocId && <Tag color="geekblue">报告文档 #{d.reportDocId}</Tag>}
          </Space>
          {(connected || logLines.length > 0) && (
            <>
              <Typography.Text strong style={{ fontSize: 13 }}>实时日志</Typography.Text>
              <pre
                ref={logRef}
                style={{
                  background: '#0f1115', color: '#d0d7de', padding: 12, borderRadius: 6,
                  fontSize: 12, lineHeight: 1.6, maxHeight: 240, overflow: 'auto',
                  whiteSpace: 'pre-wrap', wordBreak: 'break-all', margin: 0,
                }}
              >
                {logLines.join('\n') || '等待输出…'}
              </pre>
            </>
          )}
          <Space>
            <Typography.Text strong style={{ fontSize: 13 }}>用例结果（{d.results?.length ?? 0}）</Typography.Text>
          </Space>
          <Table<CaseResult> rowKey="id" size="small" columns={resultColumns} dataSource={d.results ?? []}
            pagination={false} expandable={{ expandedRowRender: renderExpanded }}
            locale={{ emptyText: '暂无结果（运行中或未配置目标）' }} />

          <Space wrap>
            <Typography.Text strong style={{ fontSize: 13 }}>证据链（{evidence.length}）</Typography.Text>
            <Button size="small" icon={<SafetyCertificateOutlined />} loading={evidenceLoading} onClick={onVerify}>
              校验哈希链
            </Button>
            <Button size="small" onClick={() => setEvidenceTick((t) => t + 1)}>刷新</Button>
          </Space>
          {verification && (
            <Alert
              type={verification.valid ? 'success' : 'error'}
              showIcon
              message={verification.valid
                ? `证据链完整：${verification.count} 条，head=${verification.headHash?.slice(0, 12) ?? '-'}`
                : `证据链校验失败：${verification.message}`}
            />
          )}
          <Table<TestEvidence> rowKey="id" size="small" columns={evidenceColumns} dataSource={evidence}
            loading={evidenceLoading} pagination={false}
            locale={{ emptyText: '暂无证据（运行中或未产生过程文件）' }} />
        </Space>
      )}

      <Modal
        centered
        title="人工判定"
        open={!!reviewFor}
        onCancel={() => setReviewFor(null)}
        onOk={submitReview}
        okText="提交"
        confirmLoading={reviewing}
        width={520}
        destroyOnHidden
      >
        <Space direction="vertical" size={12} style={{ width: '100%' }}>
          <Typography.Text type="secondary">
            用例：{reviewFor?.name}（当前 {reviewFor?.status}）
          </Typography.Text>
          <Select<ManualReview>
            style={{ width: '100%' }}
            value={reviewKind}
            onChange={setReviewKind}
            options={MANUAL_REVIEW_OPTIONS.map((o) => ({
              value: o.value,
              label: `${o.label} — ${o.desc}`,
            }))}
          />
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            {MANUAL_REVIEW_OPTIONS.find((o) => o.value === reviewKind)?.desc}
          </Typography.Text>
          <Input.TextArea
            rows={3}
            placeholder="判定说明（改判失败/否决时建议填写）"
            value={reviewNote}
            onChange={(e) => setReviewNote(e.target.value)}
          />
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            判定会写入证据链（MANUAL_REVIEW）并重算运行结论；不会重新执行用例。
          </Typography.Text>
        </Space>
      </Modal>

      <Modal
        centered
        title="一次性证据上传凭据"
        open={!!tokenModal}
        onCancel={() => setTokenModal(null)}
        footer={<Button onClick={() => setTokenModal(null)}>关闭</Button>}
        width={680}
        destroyOnHidden
      >
        {tokenModal && (
          <Space direction="vertical" size={10} style={{ width: '100%' }}>
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              凭据只显示一次，绑定当前运行与节点，有效期至 {fmtTime(tokenModal.expiresAt)}；请勿写入日志或证据正文。
            </Typography.Text>
            <Typography.Text code copyable>{tokenModal.token}</Typography.Text>
            <Typography.Text strong style={{ fontSize: 12 }}>命令侧上传示例：</Typography.Text>
            <pre style={{
              background: '#0f1115', color: '#d0d7de', padding: 12, borderRadius: 6,
              fontSize: 12, lineHeight: 1.6, whiteSpace: 'pre-wrap', wordBreak: 'break-all', margin: 0,
            }}>
              {`curl -X POST "$DEVMIND_EVIDENCE_URL" \\\n  -H "X-Evidence-Token: ${tokenModal.token}" \\\n  -F "file=@screenshot.png" -F "sha256=$(sha256sum screenshot.png | cut -d' ' -f1)"`}
            </pre>
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              命令/程序执行时平台已注入 DEVMIND_EVIDENCE_TOKEN / DEVMIND_EVIDENCE_URL / DEVMIND_EVIDENCE_EXPIRES_AT / DEVMIND_EVIDENCE_MAX_BYTES。
            </Typography.Text>
          </Space>
        )}
      </Modal>
    </Drawer>
  )
}
