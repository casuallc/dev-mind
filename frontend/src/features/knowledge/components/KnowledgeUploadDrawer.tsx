// CAP-84 FR-02/FR-15 本地上传抽屉：文件/文件夹/ZIP → 选目标库 + 默认分类/标签 → 上传后轮询批次进度。
// 文件夹用 webkitdirectory 选择，relativePaths 与 files 顺序一致交给服务端还原目录结构。
import { useEffect, useRef, useState } from 'react'
import { Alert, Button, Drawer, Progress, Select, Space, Table, Tag, Typography, message } from 'antd'
import { DeleteOutlined, FolderOpenOutlined, InboxOutlined, UploadOutlined } from '@ant-design/icons'
import type { ColumnsType } from 'antd/es/table'
import { listBases, previewClassification, uploadImport } from '../api'
import type { ClassifyPreviewItem, DocType, ImportSubmitResult, KnowledgeBase } from '../types'
import { DOC_TYPE_LABEL, DOC_TYPE_OPTIONS } from '../types'
import { showError } from '../../../shared/utils/showError'
import { DRAWER_DEFAULT } from '../../../shared/utils/overlay'

const ACCEPT = '.md,.markdown,.txt,.pdf,.docx,.pptx,.xlsx,.html,.htm,.zip'
const MAX_FILES = 200

interface SelectedFile {
  uid: string
  file: File
  relativePath: string
}

function fmtSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`
}

export default function KnowledgeUploadDrawer({
  open,
  presetKbId,
  onClose,
  onSubmitted,
}: {
  open: boolean
  presetKbId?: number
  onClose: () => void
  onSubmitted: (result: ImportSubmitResult) => void
}) {
  const [bases, setBases] = useState<KnowledgeBase[]>([])
  const [kbId, setKbId] = useState<number | undefined>(presetKbId)
  const [docType, setDocType] = useState<DocType>('TECH')
  const [tags, setTags] = useState<string[]>([])
  const [files, setFiles] = useState<SelectedFile[]>([])
  const [preview, setPreview] = useState<Record<string, ClassifyPreviewItem>>({})
  const [dragging, setDragging] = useState(false)
  const [percent, setPercent] = useState(0)
  const [uploading, setUploading] = useState(false)
  const fileInputRef = useRef<HTMLInputElement>(null)
  const folderInputRef = useRef<HTMLInputElement>(null)

  useEffect(() => {
    if (!open) return
    listBases()
      .then((list) => {
        const active = list.filter((b) => b.status === 'active')
        setBases(active)
        if (presetKbId && active.some((b) => b.id === presetKbId)) setKbId(presetKbId)
        else if (!kbId && active.length === 1) setKbId(active[0].id)
      })
      .catch((e) => showError(e, '加载知识库失败'))
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open, presetKbId])

  // webkitdirectory 是宿主 DOM 的非标准属性，React 的 input 类型里没有
  useEffect(() => {
    const el = folderInputRef.current
    if (el) el.setAttribute('webkitdirectory', '')
  }, [open])

  // FR-15 分类预览：与入库共用后端目录规则；切换默认分类时未命中的文件随之变化
  useEffect(() => {
    if (!open || files.length === 0) {
      setPreview({})
      return
    }
    let cancelled = false
    previewClassification(files.map((f) => f.relativePath), docType)
      .then((list) => {
        if (cancelled) return
        const map: Record<string, ClassifyPreviewItem> = {}
        for (const item of list) map[item.relativePath] = item
        setPreview(map)
      })
      .catch(() => undefined)
    return () => {
      cancelled = true
    }
  }, [open, files, docType])

  const inferredCount = files.filter((f) => preview[f.relativePath]?.inferred).length

  const addFiles = (incoming: File[]) => {
    const allowed = /\.(md|markdown|txt|pdf|docx|pptx|xlsx|html?|zip)$/i
    const next: SelectedFile[] = []
    for (const file of incoming) {
      const rel = (file as File & { webkitRelativePath?: string }).webkitRelativePath || file.name
      if (!allowed.test(rel)) {
        message.warning(`已跳过不支持的文件：${rel}`)
        continue
      }
      next.push({ uid: `${rel}:${file.size}:${file.lastModified}`, file, relativePath: rel })
    }
    setFiles((prev) => {
      const seen = new Set(prev.map((f) => f.uid))
      const merged = [...prev]
      for (const f of next) {
        if (!seen.has(f.uid)) {
          merged.push(f)
          seen.add(f.uid)
        }
      }
      if (merged.length > MAX_FILES) {
        message.warning(`单批最多 ${MAX_FILES} 个文件，超出部分已忽略`)
        return merged.slice(0, MAX_FILES)
      }
      return merged
    })
  }

  const reset = () => {
    setFiles([])
    setPercent(0)
    setTags([])
  }

  const onUpload = async () => {
    if (!kbId) {
      message.warning('请先选择目标知识库')
      return
    }
    if (files.length === 0) {
      message.warning('请选择要上传的文件、文件夹或 ZIP')
      return
    }
    setUploading(true)
    setPercent(0)
    try {
      const r = await uploadImport(
        kbId,
        files.map((f) => f.file),
        files.map((f) => f.relativePath),
        docType,
        tags,
        setPercent,
      )
      message.success(`已受理 ${r.totalFiles} 个文件，正在后台解析与索引`)
      reset()
      onSubmitted(r)
    } catch (e) {
      showError(e, '上传失败')
    } finally {
      setUploading(false)
    }
  }

  const columns: ColumnsType<SelectedFile> = [
    {
      title: '文件',
      dataIndex: 'relativePath',
      ellipsis: true,
      render: (p: string) => <Typography.Text style={{ fontSize: 12 }}>{p}</Typography.Text>,
    },
    {
      title: '大小',
      width: 90,
      render: (_, r) => (
        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
          {fmtSize(r.file.size)}
        </Typography.Text>
      ),
    },
    {
      title: '预估分类',
      width: 210,
      render: (_, r) => {
        const p = preview[r.relativePath]
        if (!p) {
          return (
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              识别中…
            </Typography.Text>
          )
        }
        return (
          <Space size={4} wrap>
            <Tag color={p.inferred ? 'blue' : 'default'}>{DOC_TYPE_LABEL[p.docType] ?? p.docType}</Tag>
            {!p.inferred && (
              <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                默认
              </Typography.Text>
            )}
            {p.tags.slice(0, 2).map((t) => (
              <Tag key={t} style={{ fontSize: 11 }}>
                {t}
              </Tag>
            ))}
            {p.tags.length > 2 && (
              <Typography.Text type="secondary" style={{ fontSize: 11 }}>
                +{p.tags.length - 2}
              </Typography.Text>
            )}
          </Space>
        )
      },
    },
    {
      title: '',
      width: 44,
      render: (_, r) => (
        <Button
          size="small"
          type="text"
          danger
          icon={<DeleteOutlined />}
          onClick={() => setFiles((prev) => prev.filter((f) => f.uid !== r.uid))}
        />
      ),
    },
  ]

  return (
    <Drawer
      title="上传知识文档"
      open={open}
      onClose={onClose}
      width={DRAWER_DEFAULT}
      footer={
        <Space style={{ display: 'flex', justifyContent: 'flex-end' }}>
          <Button onClick={onClose}>取消</Button>
          <Button
            type="primary"
            icon={<UploadOutlined />}
            loading={uploading}
            disabled={!kbId || files.length === 0}
            onClick={onUpload}
          >
            上传 {files.length > 0 ? `(${files.length})` : ''}
          </Button>
        </Space>
      }
    >
      <Space direction="vertical" size={14} style={{ width: '100%' }}>
        <Alert
          type="info"
          showIcon
          message="支持 Markdown / 文本 / PDF / Word / PPT / Excel / HTML，以及 ZIP 包；文件夹按目录结构入库。"
          description="同一路径重复上传按内容哈希判重：内容未变跳过，变更则升版本并重建索引。"
        />
        <div>
          <Typography.Text strong>目标知识库</Typography.Text>
          <Select
            style={{ width: '100%', marginTop: 6 }}
            placeholder="选择启用中的知识库"
            value={kbId}
            onChange={setKbId}
            options={bases.map((b) => ({
              value: b.id,
              label: `${b.name}（${b.entryCount} 条）`,
            }))}
            notFoundContent="暂无启用中的知识库，请先到「后台 → 知识库」创建"
          />
        </div>
        <Space size={12} wrap>
          <div>
            <Typography.Text strong>默认分类</Typography.Text>
            <Select
              style={{ width: 200, marginTop: 6, display: 'block' }}
              value={docType}
              onChange={setDocType}
              options={DOC_TYPE_OPTIONS}
            />
          </div>
          <div style={{ minWidth: 240, flex: 1 }}>
            <Typography.Text strong>标签</Typography.Text>
            <Select
              mode="tags"
              style={{ width: '100%', marginTop: 6 }}
              placeholder="回车添加；会与按目录自动识别的标签合并"
              value={tags}
              onChange={setTags}
              open={false}
            />
          </div>
        </Space>
        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
          分类优先按目录/文件名自动识别（如 ops/运维 目录 → 维护文档，faq → 常见问题），识别不出时使用上面的默认分类。
        </Typography.Text>

        <div
          onDragOver={(e) => {
            e.preventDefault()
            setDragging(true)
          }}
          onDragLeave={() => setDragging(false)}
          onDrop={(e) => {
            e.preventDefault()
            setDragging(false)
            addFiles(Array.from(e.dataTransfer.files ?? []))
          }}
          style={{
            border: `1px dashed ${dragging ? '#1677ff' : '#d9d9d9'}`,
            background: dragging ? '#f0f7ff' : '#fafafa',
            borderRadius: 8,
            padding: '28px 16px',
            textAlign: 'center',
          }}
        >
          <InboxOutlined style={{ fontSize: 32, color: '#1677ff' }} />
          <div style={{ marginTop: 8 }}>
            <Typography.Text>拖拽文件或 ZIP 到这里，或</Typography.Text>
          </div>
          <Space style={{ marginTop: 8 }} wrap>
            <Button icon={<UploadOutlined />} onClick={() => fileInputRef.current?.click()}>
              选择文件
            </Button>
            <Button icon={<FolderOpenOutlined />} onClick={() => folderInputRef.current?.click()}>
              选择文件夹
            </Button>
          </Space>
          <input
            ref={fileInputRef}
            type="file"
            multiple
            accept={ACCEPT}
            hidden
            onChange={(e) => {
              addFiles(Array.from(e.target.files ?? []))
              e.target.value = ''
            }}
          />
          <input
            ref={folderInputRef}
            type="file"
            multiple
            hidden
            onChange={(e) => {
              addFiles(Array.from(e.target.files ?? []))
              e.target.value = ''
            }}
          />
        </div>

        {files.length > 0 && (
          <>
            <Table
              rowKey="uid"
              size="small"
              columns={columns}
              dataSource={files}
              pagination={files.length > 10 ? { pageSize: 10, size: 'small' } : false}
              scroll={{ y: 220 }}
            />
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              分类预览：{inferredCount} 个文件按目录识别，{files.length - inferredCount} 个使用默认分类「
              {DOC_TYPE_LABEL[docType]}」；标签会与目录自动标签合并。
            </Typography.Text>
          </>
        )}

        {uploading && (
          <div>
            <Typography.Text type="secondary">上传中…</Typography.Text>
            <Progress percent={percent} size="small" />
          </div>
        )}

        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
          <Tag>提示</Tag>
          上传只负责入库；解析与索引在后台异步执行，可在「上传批次」里查看逐文件结果与失败重试。
        </Typography.Text>
      </Space>
    </Drawer>
  )
}
