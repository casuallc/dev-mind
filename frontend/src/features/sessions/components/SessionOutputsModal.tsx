// CAP-39：会话产出弹窗——打开即触发一次 runner 即时回传（collect_output，协议 v4），
// 之后靠「从节点同步」手动刷新；左侧文件列表 + 右侧 Markdown 预览；
// 每文件可「推送为需求文档」（关联需求 + 文档类型 + 新建/更新现有版本）。
import { useCallback, useEffect, useState } from 'react'
import {
  Alert,
  Button,
  Empty,
  Form,
  Input,
  message,
  Modal,
  Radio,
  Select,
  Space,
  Spin,
  Typography,
} from 'antd'
import { CloudSyncOutlined, SendOutlined } from '@ant-design/icons'
import { collectSessionOutputs, getSessionOutput, publishSessionOutput } from '../api'
import type { PublishDocKind, SessionOutputFile, SessionSummary } from '../types'
import { listRequirements } from '../../requirements/api'
import type { Requirement } from '../../requirements/types'
import { listDocs } from '../../docs/api'
import type { DocMeta } from '../../docs/types'
import Markdown from '../../../shared/components/Markdown'
import { fmtTime } from '../../../shared/utils/format'
import { showError } from '../../../shared/utils/showError'

const KIND_OPTIONS: { value: PublishDocKind; label: string }[] = [
  { value: 'analysis', label: '需求分析' },
  { value: 'design', label: '方案设计' },
  { value: 'requirement', label: '需求文档' },
]
const KIND_LABEL: Record<PublishDocKind, string> = {
  analysis: '需求分析',
  design: '方案设计',
  requirement: '需求文档',
}

/** 按文件名猜文档类型（契约文件 analysis.md/design.md；其余默认分析） */
function guessKind(fileName: string): PublishDocKind {
  if (fileName.includes('design')) return 'design'
  if (fileName.includes('requirement')) return 'requirement'
  return 'analysis'
}

function fmtSize(bytes: number): string {
  return bytes >= 1024 ? `${(bytes / 1024).toFixed(1)} KB` : `${bytes} B`
}

interface PublishFormValues {
  requirementId: string
  kind: PublishDocKind
  mode: 'create' | 'update'
  docId?: number
  title?: string
  changeNote?: string
}

/** 推送表单（内层 Modal）：新建文档 或 更新该需求下同类型现有文档（存新版本）。 */
function PublishDocModal({
  fileName,
  session,
  onClose,
}: {
  fileName: string | null
  session: SessionSummary
  onClose: () => void
}) {
  const [form] = Form.useForm<PublishFormValues>()
  const [requirements, setRequirements] = useState<Requirement[]>([])
  const [docs, setDocs] = useState<DocMeta[]>([])
  const [submitting, setSubmitting] = useState(false)
  const kind = Form.useWatch('kind', form)
  const mode = Form.useWatch('mode', form)
  const requirementId = Form.useWatch('requirementId', form)
  // 同 SessionOutputsModal：按字段而非整个 session 做依赖，避免列表轮询重建对象时重跑
  const { id: sessionId, projectId, requirementId: sessionRequirementId } = session

  // 打开：加载可选需求（排除验收/完结/取消，同新建会话的关联需求口径），猜类型给默认值
  useEffect(() => {
    if (!fileName) return
    form.setFieldsValue({
      requirementId: sessionRequirementId,
      kind: guessKind(fileName),
      mode: 'create',
      docId: undefined,
      title: `${KIND_LABEL[guessKind(fileName)]} - ${fileName.replace(/\.[^.]+$/, '')}`,
      changeNote: undefined,
    })
    listRequirements(projectId, { size: 200 })
      .then((data) =>
        setRequirements(data.items.filter((r) => !['ACCEPTANCE', 'DONE', 'CANCELLED'].includes(r.status))),
      )
      .catch(() => setRequirements([]))
  }, [fileName, sessionId, projectId, sessionRequirementId, form])

  // 需求/类型变化：加载该需求下同类型文档（更新模式的可选目标）
  useEffect(() => {
    if (!fileName || !requirementId || !kind) {
      setDocs([])
      return
    }
    listDocs({ kind, projectId })
      .then((list) => setDocs(list.filter((d) => d.requirementId === requirementId)))
      .catch(() => setDocs([]))
  }, [fileName, requirementId, kind, projectId])

  const onKindChange = (k: PublishDocKind) => {
    form.setFieldsValue({
      kind: k,
      docId: undefined,
      title: `${KIND_LABEL[k]} - ${(fileName ?? '').replace(/\.[^.]+$/, '')}`,
    })
  }

  const submit = async () => {
    const v = await form.validateFields()
    setSubmitting(true)
    try {
      const r = await publishSessionOutput(sessionId, {
        fileName: fileName!,
        kind: v.kind,
        requirementId: v.requirementId,
        mode: v.mode,
        docId: v.mode === 'update' ? v.docId : undefined,
        title: v.mode === 'create' ? v.title : undefined,
        changeNote: v.mode === 'update' ? v.changeNote : undefined,
      })
      message.success(
        `已推送：文档 #${r.docId}（v${r.versionNo}）${r.designId ? '，已同步登记方案记录' : ''}`,
      )
      onClose()
    } catch (e) {
      showError(e, '推送失败')
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <Modal
      title={`推送为需求文档：${fileName ?? ''}`}
      open={!!fileName}
      onCancel={onClose}
      onOk={submit}
      okText="推送"
      confirmLoading={submitting}
      width={520}
      destroyOnHidden
    >
      <Form form={form} layout="vertical" style={{ marginTop: 12 }}>
        <Form.Item name="requirementId" label="关联需求" rules={[{ required: true, message: '请选择需求' }]}>
          <Select
            showSearch
            optionFilterProp="label"
            placeholder="选择目标需求"
            options={requirements.map((r) => ({ value: r.id, label: `${r.code} ${r.title}` }))}
          />
        </Form.Item>
        <Form.Item name="kind" label="文档类型" rules={[{ required: true }]}>
          <Select options={KIND_OPTIONS} onChange={onKindChange} />
        </Form.Item>
        <Form.Item name="mode" label="方式" rules={[{ required: true }]}>
          <Radio.Group
            options={[
              { value: 'create', label: '新建文档' },
              { value: 'update', label: '更新现有文档（存为新版本）' },
            ]}
          />
        </Form.Item>
        {mode === 'update' ? (
          <>
            <Form.Item name="docId" label="目标文档" rules={[{ required: true, message: '请选择要更新的文档' }]}>
              <Select
                showSearch
                optionFilterProp="label"
                placeholder={docs.length ? '选择该需求下的同类型文档' : '该需求下暂无此类型文档'}
                notFoundContent="该需求下暂无此类型文档"
                options={docs.map((d) => ({
                  value: d.id,
                  label: `#${d.id} ${d.title}（v${d.currentVersion}·${d.status}）`,
                }))}
              />
            </Form.Item>
            <Form.Item name="changeNote" label="变更说明">
              <Input placeholder={`默认：手动推送自会话 ${session.id}`} />
            </Form.Item>
          </>
        ) : (
          <Form.Item name="title" label="文档标题" rules={[{ required: true, message: '请输入标题' }]}>
            <Input maxLength={120} />
          </Form.Item>
        )}
      </Form>
    </Modal>
  )
}

export default function SessionOutputsModal({
  open,
  onClose,
  session,
}: {
  open: boolean
  onClose: () => void
  session: SessionSummary | null
}) {
  const [files, setFiles] = useState<SessionOutputFile[]>([])
  const [syncing, setSyncing] = useState(false)
  const [syncMessage, setSyncMessage] = useState<string | null>(null)
  const [selected, setSelected] = useState<string | null>(null)
  const [content, setContent] = useState('')
  const [contentLoading, setContentLoading] = useState(false)
  const [publishFile, setPublishFile] = useState<string | null>(null)

  // 依赖会话 id 而非整个 session 对象：列表页每 3s 轮询会重建 session，
  // 按对象引用做依赖会把这里变成定时刷新（每次都重新 collect 回传并清空预览）
  const sessionId = session?.id ?? null

  const sync = useCallback(async () => {
    if (!sessionId) return
    setSyncing(true)
    try {
      const r = await collectSessionOutputs(sessionId)
      setFiles(r.files)
      setSyncMessage(r.message ?? null)
      setSelected((cur) =>
        cur && r.files.some((f) => f.fileName === cur) ? cur : (r.files[0]?.fileName ?? null),
      )
    } catch (e) {
      showError(e, '同步产出失败')
    } finally {
      setSyncing(false)
    }
  }, [sessionId])

  // 打开（或切换会话）时同步一次；此后只由「从节点同步」手动触发
  useEffect(() => {
    if (!open) return
    setFiles([])
    setSelected(null)
    setContent('')
    setSyncMessage(null)
    sync()
  }, [open, sync])

  useEffect(() => {
    if (!open || !sessionId || !selected) {
      setContent('')
      return
    }
    setContentLoading(true)
    getSessionOutput(sessionId, selected)
      .then((r) => setContent(r.content))
      .catch((e) => showError(e, '读取产出失败'))
      .finally(() => setContentLoading(false))
  }, [open, sessionId, selected])

  return (
    <Modal
      title={`会话产出（.devmind/output）${session ? ` - ${session.id}` : ''}`}
      open={open}
      onCancel={onClose}
      footer={null}
      width={920}
      destroyOnHidden
    >
      {syncMessage && (
        <Alert
          type="warning"
          showIcon
          message={syncMessage}
          closable
          onClose={() => setSyncMessage(null)}
          style={{ marginBottom: 12 }}
        />
      )}
      <Space style={{ marginBottom: 12, width: '100%', justifyContent: 'space-between' }}>
        <Typography.Text type="secondary">
          同步即让 runner 立即回传产出（进行中的会话也可）；退出时已自动回传一次。
        </Typography.Text>
        <Button size="small" icon={<CloudSyncOutlined />} loading={syncing} onClick={sync}>
          从节点同步
        </Button>
      </Space>
      {files.length === 0 && !syncing ? (
        <Empty description="暂无产出文件（agent 写入 .devmind/output/ 后可同步查看）" />
      ) : (
        <div style={{ display: 'flex', gap: 12, minHeight: 360 }}>
          <div style={{ width: 280, flexShrink: 0 }}>
            {files.map((f) => (
              <div
                key={f.fileName}
                onClick={() => setSelected(f.fileName)}
                style={{
                  padding: '6px 8px',
                  cursor: 'pointer',
                  borderRadius: 6,
                  marginBottom: 4,
                  background: selected === f.fileName ? 'rgba(22,119,255,0.1)' : undefined,
                }}
              >
                <Space style={{ width: '100%', justifyContent: 'space-between' }}>
                  <Typography.Text code style={{ fontSize: 12 }}>
                    {f.fileName}
                  </Typography.Text>
                  <Button
                    size="small"
                    type="link"
                    icon={<SendOutlined />}
                    onClick={(e) => {
                      e.stopPropagation()
                      setPublishFile(f.fileName)
                    }}
                  >
                    推送
                  </Button>
                </Space>
                <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                  {fmtSize(f.sizeBytes)} · {fmtTime(f.updatedAt)}
                </Typography.Text>
              </div>
            ))}
          </div>
          <div
            style={{
              flex: 1,
              minWidth: 0,
              maxHeight: 480,
              overflow: 'auto',
              border: '1px solid rgba(128,128,128,0.25)',
              borderRadius: 8,
              padding: '8px 16px',
            }}
          >
            {contentLoading ? (
              <Spin style={{ display: 'block', margin: '60px auto' }} />
            ) : selected ? (
              <Markdown content={content} />
            ) : (
              <Empty description="选择左侧文件预览" style={{ marginTop: 60 }} />
            )}
          </div>
        </div>
      )}
      {session && <PublishDocModal fileName={publishFile} session={session} onClose={() => setPublishFile(null)} />}
    </Modal>
  )
}
