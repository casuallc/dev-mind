// CAP-03 文档编辑页：只读渲染 / Markdown 编辑+实时预览 / 保存新版本 / 版本历史 / diff / 回退 / 状态机。
import { useCallback, useEffect, useState } from 'react'
import {
  Badge,
  Button,
  Card,
  Drawer,
  Empty,
  Form,
  Input,
  message,
  Modal,
  Segmented,
  Space,
  Spin,
  Table,
  Tag,
  Typography,
} from 'antd'
import {
  ArrowLeftOutlined,
  DiffOutlined,
  RollbackOutlined,
  SaveOutlined,
} from '@ant-design/icons'
import type { ColumnsType } from 'antd/es/table'
import { useNavigate, useParams } from 'react-router-dom'
import { deleteDoc, docDiff, getDoc, listDocVersions, revertDoc, saveDocVersion, transitionDoc } from '../api'
import { KIND_LABEL, STATUS_LABEL } from '../types'
import type { DiffResult, DocDetail, DocVersion } from '../types'
import Markdown from '../components/Markdown'

const statusTag = (s: string) => (
  <Tag color={s === 'draft' ? 'default' : s === 'pending_confirm' ? 'gold' : 'green'}>{STATUS_LABEL[s as keyof typeof STATUS_LABEL] ?? s}</Tag>
)

export default function DocEditorPage() {
  const { id } = useParams<{ id: string }>()
  const docId = Number(id)
  const navigate = useNavigate()

  const [doc, setDoc] = useState<DocDetail | null>(null)
  const [versions, setVersions] = useState<DocVersion[]>([])
  const [loading, setLoading] = useState(true)

  const [mode, setMode] = useState<'view' | 'edit'>('view')
  const [editText, setEditText] = useState('')
  const [dirty, setDirty] = useState(false)

  const [viewing, setViewing] = useState<DocDetail | null>(null) // 历史版本只读视图
  const [diff, setDiff] = useState<DiffResult | null>(null)
  const [diffFor, setDiffFor] = useState<number | null>(null)

  const [saveOpen, setSaveOpen] = useState(false)
  const [saveForm] = Form.useForm<{ changeNote?: string }>()

  const load = useCallback(async (version?: number) => {
    if (!docId) return
    try {
      const [d, vs] = await Promise.all([getDoc(docId, version), listDocVersions(docId)])
      if (version) {
        setViewing(d)
      } else {
        setDoc(d)
        setEditText(d.contentMd)
        setViewing(null)
      }
      setVersions(vs)
    } catch (e) {
      message.error(`加载文档失败：${(e as Error).message}`)
    } finally {
      setLoading(false)
    }
  }, [docId])

  useEffect(() => {
    setLoading(true)
    load()
  }, [load])

  const frozen = doc?.status === 'frozen'

  const onSave = async () => {
    if (!doc) return
    if (!dirty && !frozen) {
      message.info('内容未变更')
      return
    }
    const v = await saveForm.validateFields()
    try {
      const updated = await saveDocVersion(docId, { contentMd: editText, changeNote: v.changeNote })
      message.success(`已保存为 v${updated.versionNo}`)
      setSaveOpen(false)
      saveForm.resetFields()
      setDirty(false)
      setMode('view')
      await load()
    } catch (e) {
      message.error(`保存失败：${(e as Error).message}`)
    }
  }

  const onTransition = async (action: string, okText: string, tip: string) => {
    Modal.confirm({
      centered: true,
      title: okText + '？',
      content: tip,
      okText,
      cancelText: '取消',
      onOk: async () => {
        try {
          await transitionDoc(docId, action)
          message.success(`已${okText}`)
          await load()
        } catch (e) {
          message.error(`操作失败：${(e as Error).message}`)
        }
      },
    })
  }

  const onDelete = () => {
    Modal.confirm({
      centered: true,
      title: '删除该文档？',
      content: '会删除数据库记录与 docs-repo 中的文件（git 历史保留）。',
      okText: '删除',
      okButtonProps: { danger: true },
      cancelText: '取消',
      onOk: async () => {
        try {
          await deleteDoc(docId)
          message.success('已删除')
          navigate('/admin/docs')
        } catch (e) {
          message.error(`删除失败：${(e as Error).message}`)
        }
      },
    })
  }

  const onDiff = async (v: number) => {
    try {
      setDiff(await docDiff(docId, v))
      setDiffFor(v)
    } catch (e) {
      message.error(`获取 diff 失败：${(e as Error).message}`)
    }
  }

  const onRevert = (v: number) => {
    Modal.confirm({
      centered: true,
      title: `回退到 v${v}？`,
      content: '将 v' + v + ' 的内容保存为新版本（当前内容进入历史，不丢失）。',
      okText: '回退',
      cancelText: '取消',
      onOk: async () => {
        try {
          const r = await revertDoc(docId, v)
          message.success(`已回退并生成 v${r.versionNo}`)
          await load()
        } catch (e) {
          message.error(`回退失败：${(e as Error).message}`)
        }
      },
    })
  }

  const versionColumns: ColumnsType<DocVersion> = [
    {
      title: '版本',
      dataIndex: 'versionNo',
      width: 70,
      render: (v) => <Typography.Text strong>v{v}</Typography.Text>,
    },
    { title: '变更说明', dataIndex: 'changeNote', ellipsis: true, render: (n) => n || <Typography.Text type="secondary">-</Typography.Text> },
    {
      title: 'Commit',
      dataIndex: 'commitSha',
      width: 100,
      render: (s) => (s ? <Typography.Text code style={{ fontSize: 11 }}>{s.slice(0, 7)}</Typography.Text> : '-'),
    },
    { title: '时间', dataIndex: 'createdAt', width: 170, render: (t) => new Date(t).toLocaleString() },
    {
      title: '操作',
      width: 200,
      render: (_, r) => (
        <Space size={4}>
          <Button size="small" onClick={() => load(r.versionNo)}>
            查看
          </Button>
          <Button size="small" icon={<DiffOutlined />} onClick={() => onDiff(r.versionNo)}>
            与当前 diff
          </Button>
          <Button
            size="small"
            disabled={r.versionNo === doc?.currentVersion}
            icon={<RollbackOutlined />}
            onClick={() => onRevert(r.versionNo)}
          >
            回退
          </Button>
        </Space>
      ),
    },
  ]

  const currentContent = viewing ? viewing.contentMd : doc?.contentMd ?? ''
  const displayTitle = viewing ? `${doc?.title ?? '文档'}（历史 v${viewing.versionNo}）` : doc?.title

  return (
    <Space direction="vertical" size={12} style={{ width: '100%' }}>
      {loading ? (
        <Card>
          <Spin />
        </Card>
      ) : !doc ? (
        <Card>
          <Empty description="文档不存在" />
        </Card>
      ) : (
        <>
          <Card
            size="small"
            title={
              <Space>
                <Button type="text" size="small" icon={<ArrowLeftOutlined />} onClick={() => navigate('/admin/docs')} />
                <Typography.Text strong>{displayTitle}</Typography.Text>
                <Tag color={doc.kind === 'requirement' ? 'blue' : doc.kind === 'design' ? 'geekblue' : doc.kind === 'api-suite' ? 'purple' : 'cyan'}>
                  {KIND_LABEL[doc.kind]}
                </Tag>
                {statusTag(doc.status)}
                <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                  v{doc.currentVersion}
                  {viewing && <Badge status="processing" text={`查看历史 v${viewing.versionNo}`} />}
                </Typography.Text>
              </Space>
            }
            extra={
              <Space>
                {viewing ? (
                  <Button size="small" type="primary" onClick={() => load()}>
                    回到当前版本
                  </Button>
                ) : (
                  <>
                    <Segmented
                      size="small"
                      value={mode}
                      onChange={(v) => {
                        setMode(v as 'view' | 'edit')
                        if (v === 'edit') setEditText(doc.contentMd)
                      }}
                      options={[
                        { label: '预览', value: 'view' },
                        { label: '编辑', value: 'edit' },
                      ]}
                    />
                    {mode === 'edit' && (
                      <Button
                        size="small"
                        type="primary"
                        icon={<SaveOutlined />}
                        disabled={!dirty}
                        onClick={() => setSaveOpen(true)}
                      >
                        保存新版本
                      </Button>
                    )}
                    {doc.status === 'draft' && (
                      <Button size="small" onClick={() => onTransition('submit', '提交确认', '进入待确认状态，可进一步冻结为基线。')}>
                        提交确认
                      </Button>
                    )}
                    {doc.status === 'pending_confirm' && (
                      <Button size="small" onClick={() => onTransition('freeze', '冻结', '冻结后为基线，后续变更必须生成新版本并填写变更说明。')}>
                        冻结
                      </Button>
                    )}
                    {doc.status === 'frozen' && (
                      <Button size="small" onClick={() => onTransition('unfreeze', '解除冻结', '回到草稿态，可随意编辑。')}>
                        解除冻结
                      </Button>
                    )}
                    <Button size="small" danger onClick={onDelete}>
                      删除
                    </Button>
                  </>
                )}
              </Space>
            }
          >
            <Space style={{ marginBottom: 8 }}>
              {doc.projectId && <Tag>项目: {doc.projectId}</Tag>}
              {doc.requirementId && <Tag>需求: {doc.requirementId}</Tag>}
              {doc.tags.map((t) => <Tag key={t}>{t}</Tag>)}
              <Typography.Text type="secondary" style={{ fontSize: 12 }}>{doc.filePath}</Typography.Text>
            </Space>
          </Card>

          {/* 正文：编辑/预览 */}
          {mode === 'edit' && !viewing ? (
            <Card size="small" title="编辑 + 实时预览">
              <div style={{ display: 'flex', gap: 12 }}>
                <Input.TextArea
                  value={editText}
                  onChange={(e) => {
                    setEditText(e.target.value)
                    setDirty(e.target.value !== doc.contentMd)
                  }}
                  autoSize={{ minRows: 18, maxRows: 40 }}
                  style={{ flex: 1, fontFamily: 'ui-monospace, Consolas, monospace', fontSize: 13 }}
                  placeholder="Markdown…"
                />
                <div style={{ flex: 1, maxHeight: 560, overflow: 'auto', padding: '0 4px' }}>
                  <Markdown content={editText} />
                </div>
              </div>
            </Card>
          ) : (
            <Card size="small" title="内容">
              <div style={{ maxHeight: 600, overflow: 'auto' }}>
                <Markdown content={currentContent} />
              </div>
            </Card>
          )}

          {/* 版本历史 */}
          <Card size="small" title="版本历史">
            <Table
              size="small"
              rowKey="versionNo"
              columns={versionColumns}
              dataSource={versions}
              pagination={false}
            />
          </Card>
        </>
      )}

      {/* 保存新版本弹窗（frozen 必填变更说明） */}
      <Modal
        title={`保存新版本 v${(doc?.currentVersion ?? 0) + 1}`}
        open={saveOpen}
        onCancel={() => setSaveOpen(false)}
        onOk={onSave}
        okText="保存"
        width={520}
      >
        <Form form={saveForm} layout="vertical">
          <Form.Item
            name="changeNote"
            label="变更说明"
            required={frozen}
            rules={frozen ? [{ required: true, message: '文档已冻结，必须填写变更说明' }] : []}
            extra={frozen ? '文档已冻结（FR-04），必须说明本次变更' : '可选'}
          >
            <Input.TextArea rows={3} placeholder="本次改了什么" />
          </Form.Item>
        </Form>
      </Modal>

      {/* diff 弹窗 */}
      <Drawer
        title={diffFor ? `v${diffFor} → 当前版本的差异` : '版本差异'}
        open={!!diff}
        onClose={() => setDiff(null)}
        width={640}
      >
        {diff && (
          <>
            {!diff.hasChanges ? (
              <Empty description="两版本内容一致" />
            ) : (
              <>
                <Typography.Paragraph type="secondary">
                  新增 {diff.additions} 行 / 删除 {diff.deletions} 行
                </Typography.Paragraph>
                <pre style={{ whiteSpace: 'pre-wrap', fontSize: 12, background: '#f6f6f6', padding: 12, borderRadius: 4, maxHeight: 560, overflow: 'auto' }}>
                  {diff.lines.map((l, i) => (
                    <div key={i} style={{ color: l.startsWith('+') ? '#237804' : l.startsWith('-') ? '#a8071a' : '#595959', background: l.startsWith('+') ? '#f6ffed' : l.startsWith('-') ? '#fff1f0' : 'transparent' }}>
                      {l}
                    </div>
                  ))}
                </pre>
              </>
            )}
          </>
        )}
      </Drawer>
    </Space>
  )
}
