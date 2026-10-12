// CAP-03 文档编辑页：只读渲染 / Markdown 编辑+实时预览 / 保存新版本 / 版本历史 / diff / 回退 / 状态机。
// CAP-32：编辑态支持粘贴/拖拽图片，上传附件库后在光标处插入 ![](附件URL)。
import { useCallback, useEffect, useRef, useState } from 'react'
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
  SaveOutlined,
} from '@ant-design/icons'
import type { ColumnsType } from 'antd/es/table'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { deleteDoc, docDiff, getDoc, listDocVersions, revertDoc, saveDocVersion, transitionDoc } from '../api'
import { KIND_LABEL, STATUS_LABEL } from '../types'
import type { DiffResult, DocDetail, DocVersion } from '../types'
import { getProject } from '../../projects/api'
import { getRequirement } from '../../requirements/api'
import Markdown from '../../../shared/components/Markdown'
import { fmtTime } from '../../../shared/utils/format'
import { uploadAttachment } from '../../../shared/attachments/api'
import { attachmentRawUrl } from '../../../shared/attachments/url'
import { pageCardBodyFlexStyle, pageCardStyle, pagePaneScrollStyle } from '../../../shared/utils/pageLayout'
import { showError } from '../../../shared/utils/showError'
import { DRAWER_WIDE } from '../../../shared/utils/overlay'

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
  // 归属名称解析：详情只带 projectId/requirementId，另查名称便于阅读关联（查不到回退显示 id）
  const [projectName, setProjectName] = useState<string | null>(null)
  const [requirementTitle, setRequirementTitle] = useState<string | null>(null)

  const [mode, setMode] = useState<'view' | 'edit'>('view')
  const [editText, setEditText] = useState('')
  const [dirty, setDirty] = useState(false)
  const editorWrapRef = useRef<HTMLDivElement>(null)
  const uploadSeqRef = useRef(0)

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
        setProjectName(null)
        setRequirementTitle(null)
        if (d.projectId) getProject(d.projectId).then((p) => setProjectName(p.name)).catch(() => {})
        if (d.projectId && d.requirementId) {
          getRequirement(d.projectId, d.requirementId).then((r) => setRequirementTitle(r.title)).catch(() => {})
        }
      }
      setVersions(vs)
    } catch (e) {
      showError(e, '加载文档失败')
    } finally {
      setLoading(false)
    }
  }, [docId])

  useEffect(() => {
    setLoading(true)
    load()
  }, [load])

  const frozen = doc?.status === 'frozen'

  // CAP-32：光标处插入文本（无焦点时追加到文末），保持 dirty 判定一致
  const insertAtCursor = (snippet: string) => {
    const ta = editorWrapRef.current?.querySelector('textarea')
    setEditText((prev) => {
      const p = ta?.selectionStart ?? prev.length
      const next = prev.slice(0, p) + snippet + prev.slice(p)
      setDirty(next !== doc?.contentMd)
      requestAnimationFrame(() => {
        if (ta) {
          ta.focus()
          const np = p + snippet.length
          ta.setSelectionRange(np, np)
        }
      })
      return next
    })
  }

  // CAP-32：粘贴/拖拽图片 → 上传附件库 → 占位符替换为 ![](附件URL)（上传中先占位，防并发错位）
  const insertImages = (files: Iterable<File>) => {
    for (const file of files) {
      if (!file.type.startsWith('image/')) {
        message.warning(`仅支持插入图片，已忽略：${file.name}`)
        continue
      }
      const seq = ++uploadSeqRef.current
      const placeholder = `![${file.name}](uploading://${seq})`
      insertAtCursor(`\n${placeholder}\n`)
      uploadAttachment(file, file.name)
        .then((v) => {
          setEditText((prev) => {
            const next = prev.replace(placeholder, `![${v.originalName}](${attachmentRawUrl(v.attachmentId)})`)
            setDirty(next !== doc?.contentMd)
            return next
          })
        })
        .catch((e) => {
          showError(e, '图片上传失败')
          setEditText((prev) => prev.replace(placeholder, ''))
        })
    }
  }

  const onEditorPaste = (e: React.ClipboardEvent) => {
    const files = Array.from(e.clipboardData?.files ?? [])
    if (files.length > 0) {
      e.preventDefault()
      insertImages(files)
    }
  }

  const onEditorDrop = (e: React.DragEvent) => {
    const files = Array.from(e.dataTransfer?.files ?? [])
    if (files.length > 0) {
      e.preventDefault()
      insertImages(files)
    }
  }

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
      showError(e, '保存失败')
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
          showError(e, '操作失败')
        }
      },
    })
  }

  const onDelete = () => {
    Modal.confirm({
      centered: true,
      title: '删除该文档？',
      content: '会删除文档与全部历史版本记录，不可恢复。',
      okText: '删除',
      okButtonProps: { danger: true },
      cancelText: '取消',
      onOk: async () => {
        try {
          await deleteDoc(docId)
          message.success('已删除')
          navigate('/admin/docs')
        } catch (e) {
          showError(e, '删除失败')
        }
      },
    })
  }

  const onDiff = async (v: number) => {
    try {
      setDiff(await docDiff(docId, v))
      setDiffFor(v)
    } catch (e) {
      showError(e, '获取 diff 失败')
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
          showError(e, '回退失败')
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
    { title: '时间', dataIndex: 'createdAt', width: 170, render: (t) => fmtTime(t) },
    {
      title: '操作',
      width: 300,
      render: (_, r) => (
        <Space size={4}>
          <Button onClick={() => load(r.versionNo)}>
            查看
          </Button>
          <Button onClick={() => onDiff(r.versionNo)}>
            与当前 diff
          </Button>
          <Button
            disabled={r.versionNo === doc?.currentVersion}
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

  // 内容操作（预览/编辑、保存、状态机流转、删除）均针对内容生效，集中放在内容卡片标题栏；顶部只留「返回列表」
  const contentActions = viewing ? (
    <Button type="primary" onClick={() => load()}>
      回到当前版本
    </Button>
  ) : (
    <Space>
      <Segmented
        value={mode}
        onChange={(v) => {
          setMode(v as 'view' | 'edit')
          if (v === 'edit' && doc) setEditText(doc.contentMd)
        }}
        options={[
          { label: '预览', value: 'view' },
          { label: '编辑', value: 'edit' },
        ]}
      />
      {mode === 'edit' && (
        <Button
          type="primary"
          icon={<SaveOutlined />}
          disabled={!dirty}
          onClick={() => setSaveOpen(true)}
        >
          保存新版本
        </Button>
      )}
      {doc?.status === 'draft' && (
        <Button onClick={() => onTransition('submit', '提交确认', '进入待确认状态，可进一步冻结为基线。')}>
          提交确认
        </Button>
      )}
      {doc?.status === 'pending_confirm' && (
        <Button onClick={() => onTransition('freeze', '冻结', '冻结后为基线，后续变更必须生成新版本并填写变更说明。')}>
          冻结
        </Button>
      )}
      {doc?.status === 'frozen' && (
        <Button onClick={() => onTransition('unfreeze', '解除冻结', '回到草稿态，可随意编辑。')}>
          解除冻结
        </Button>
      )}
      <Button danger onClick={onDelete}>
        删除
      </Button>
    </Space>
  )

  return (
    <div style={{ flex: 1, minHeight: 0, display: 'flex', flexDirection: 'column', gap: 12 }}>
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
            title={
              <Space wrap size={8}>
                <Typography.Text strong>{displayTitle}</Typography.Text>
                <Tag color={doc.kind === 'requirement' ? 'blue' : doc.kind === 'design' ? 'geekblue' : doc.kind === 'analysis' ? 'gold' : doc.kind === 'api-suite' ? 'purple' : 'cyan'}>
                  {KIND_LABEL[doc.kind]}
                </Tag>
                {statusTag(doc.status)}
                <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                  v{doc.currentVersion}
                  {viewing && <Badge status="processing" text={`查看历史 v${viewing.versionNo}`} />}
                </Typography.Text>
                {doc.projectId && (
                  <Link to={`/projects/${doc.projectId}`}>
                    <Tag style={{ cursor: 'pointer' }}>项目: {projectName ?? doc.projectId}</Tag>
                  </Link>
                )}
                {doc.requirementId && (
                  <Link to={doc.projectId ? `/projects/${doc.projectId}/requirements/${doc.requirementId}` : '#'}>
                    <Tag style={{ cursor: doc.projectId ? 'pointer' : undefined }}>需求: {requirementTitle ?? doc.requirementId}</Tag>
                  </Link>
                )}
                {doc.tags.map((t) => <Tag key={t}>{t}</Tag>)}
              </Space>
            }
            extra={<Button onClick={() => navigate('/admin/docs')}>返回列表</Button>}
          >
            {/* 版本历史（置顶卡正文，无小标题保持紧凑） */}
            <Table
              size="small"
              rowKey="versionNo"
              columns={versionColumns}
              dataSource={versions}
              pagination={{ pageSize: 5, showSizeChanger: false, showTotal: (total) => `共 ${total} 个版本` }}
            />
          </Card>

          {/* 正文：编辑/预览（卡片撑满剩余高度，滚动只发生在卡片内部，整页不出滚动条） */}
          {mode === 'edit' && !viewing ? (
            <Card size="small" title="编辑 + 实时预览" style={pageCardStyle} styles={{ body: pageCardBodyFlexStyle }} extra={contentActions}>
              <div style={{ flex: 1, minHeight: 0, display: 'flex', gap: 12 }}>
                <div
                  ref={editorWrapRef}
                  style={{ flex: 1, minHeight: 0, display: 'flex' }}
                  onPaste={onEditorPaste}
                  onDragOver={(e) => e.preventDefault()}
                  onDrop={onEditorDrop}
                >
                  <Input.TextArea
                    value={editText}
                    onChange={(e) => {
                      setEditText(e.target.value)
                      setDirty(e.target.value !== doc.contentMd)
                    }}
                    style={{ flex: 1, resize: 'none', fontFamily: 'ui-monospace, Consolas, monospace', fontSize: 13 }}
                    placeholder="Markdown…（可直接粘贴/拖拽图片插入）"
                  />
                </div>
                <div style={{ flex: 1, minHeight: 0, overflow: 'auto', padding: '0 4px' }}>
                  <Markdown content={editText} />
                </div>
              </div>
            </Card>
          ) : (
            <Card size="small" title="内容" style={pageCardStyle} styles={{ body: pagePaneScrollStyle }} extra={contentActions}>
              <Markdown content={currentContent} />
            </Card>
          )}

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
        width={DRAWER_WIDE}
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
    </div>
  )
}
