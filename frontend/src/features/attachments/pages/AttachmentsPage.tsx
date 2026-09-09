// 附件管理页（CAP-32）：平台统一附件（图床+文件）的查看/上传/共享范围/删除，挂在后台「内容」分组。
// 上传走弹窗：先选文件（可多选）再填描述，逐个上传并展示逐文件进度条。
// 图片类附件内联预览，非图片仅提供下载；其他地方凭 attachmentId 引用。
import { useCallback, useEffect, useMemo, useState } from 'react'
import {
  Button,
  Card,
  Dropdown,
  Image,
  Input,
  message,
  Modal,
  Progress,
  Segmented,
  Space,
  Table,
  Tag,
  Typography,
  Upload,
} from 'antd'
import type { UploadFile } from 'antd'
import {
  DeleteOutlined,
  InboxOutlined,
  PictureOutlined,
  ReloadOutlined,
  UploadOutlined,
} from '@ant-design/icons'
import type { ColumnsType } from 'antd/es/table'
import {
  deleteAttachment,
  isImageAttachment,
  listAttachments,
  updateAttachmentScope,
  uploadAttachment,
  type AttachmentView,
} from '../../../shared/attachments/api'
import { attachmentRawUrl } from '../../../shared/attachments/url'
import { fmtTime } from '../../../shared/utils/format'
import { pageCardStyle, pageCardBodyScrollStyle } from '../../../shared/utils/pageLayout'
import { showError } from '../../../shared/utils/showError'

function fmtSize(n: number): string {
  if (n < 1024) return `${n} B`
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)} KB`
  return `${(n / 1024 / 1024).toFixed(1)} MB`
}

export default function AttachmentsPage() {
  const [rows, setRows] = useState<AttachmentView[]>([])
  const [loading, setLoading] = useState(false)
  const [scopeFilter, setScopeFilter] = useState<string>('ALL')
  const [typeFilter, setTypeFilter] = useState<string>('ALL')
  const [keyword, setKeyword] = useState('')
  // 上传弹窗：暂存文件 + 描述 + 逐文件进度/失败信息
  const [uploadOpen, setUploadOpen] = useState(false)
  const [fileList, setFileList] = useState<UploadFile[]>([])
  const [description, setDescription] = useState('')
  const [uploading, setUploading] = useState(false)
  const [progress, setProgress] = useState<Record<string, number>>({})
  const [failed, setFailed] = useState<Record<string, string>>({})

  const load = useCallback(() => {
    setLoading(true)
    listAttachments({
      scope: scopeFilter === 'ALL' ? undefined : scopeFilter,
      type: typeFilter === 'ALL' ? undefined : (typeFilter as 'image' | 'other'),
      keyword: keyword.trim() || undefined,
    })
      .then(setRows)
      .catch((e) => showError(e, '加载失败'))
      .finally(() => setLoading(false))
  }, [scopeFilter, typeFilter, keyword])

  useEffect(load, [load])

  const copyText = (text: string, tip: string) => {
    navigator.clipboard
      .writeText(text)
      .then(() => message.success(tip))
      .catch(() => message.error('复制失败'))
  }

  const onToggleScope = (r: AttachmentView) => {
    const next = r.scope === 'PRIVATE' ? 'SHARED' : 'PRIVATE'
    updateAttachmentScope(r.attachmentId, next)
      .then(() => {
        message.success(next === 'SHARED' ? '已设为全员共享' : '已设为仅自己可见')
        load()
      })
      .catch((e) => showError(e, '操作失败'))
  }

  const onDelete = (r: AttachmentView) => {
    // 确认弹窗统一走平台通用的居中 Modal.confirm，不用贴按钮的 Popconfirm
    Modal.confirm({
      centered: true,
      title: '删除后不可恢复，确认删除？',
      okText: '删除',
      okButtonProps: { danger: true },
      onOk: () =>
        deleteAttachment(r.attachmentId)
          .then(() => {
            message.success('已删除')
            load()
          })
          .catch((e) => showError(e, '删除失败')),
    })
  }

  const resetUpload = () => {
    setFileList([])
    setDescription('')
    setProgress({})
    setFailed({})
  }

  // 逐个上传暂存文件；成功的从列表移除，失败的保留并标错，可修正后点「开始上传」重试
  const startUpload = async () => {
    const files = [...fileList]
    setUploading(true)
    setFailed({})
    let okCount = 0
    for (const f of files) {
      try {
        await uploadAttachment(f as unknown as File, f.name, 'PRIVATE', description, (p) =>
          setProgress((prev) => ({ ...prev, [f.uid]: p })),
        )
        okCount++
        setFileList((prev) => prev.filter((x) => x.uid !== f.uid))
      } catch (e) {
        setFailed((prev) => ({ ...prev, [f.uid]: (e as Error).message }))
      }
    }
    setUploading(false)
    if (okCount > 0) {
      message.success(`已上传 ${okCount} 个附件`)
      load()
    }
    if (okCount === files.length) {
      setUploadOpen(false)
      resetUpload()
    }
  }

  const columns = useMemo<ColumnsType<AttachmentView>>(
    () => [
      {
        title: '附件',
        key: 'name',
        render: (_, r) => (
          <Space>
            {isImageAttachment(r.contentType) ? (
              <Image
                src={attachmentRawUrl(r.attachmentId)}
                alt={r.originalName}
                width={48}
                height={48}
                style={{ objectFit: 'cover', borderRadius: 4, border: '1px solid #f0f0f0' }}
              />
            ) : (
              <span
                style={{
                  display: 'inline-flex',
                  width: 48,
                  height: 48,
                  alignItems: 'center',
                  justifyContent: 'center',
                  background: '#fafafa',
                  border: '1px solid #f0f0f0',
                  borderRadius: 4,
                  color: '#8c8c8c',
                }}
              >
                <PictureOutlined />
              </span>
            )}
            <Typography.Text style={{ maxWidth: 260 }} ellipsis={{ tooltip: r.originalName }}>
              {r.originalName}
            </Typography.Text>
          </Space>
        ),
      },
      {
        title: '描述',
        dataIndex: 'description',
        render: (v?: string) =>
          v ? (
            <Typography.Text style={{ maxWidth: 220 }} ellipsis={{ tooltip: v }} type="secondary">
              {v}
            </Typography.Text>
          ) : (
            <Typography.Text type="secondary">-</Typography.Text>
          ),
      },
      {
        title: '类型',
        dataIndex: 'contentType',
        width: 160,
        render: (v: string) => <Typography.Text type="secondary">{v}</Typography.Text>,
      },
      { title: '大小', dataIndex: 'sizeBytes', width: 100, render: (v: number) => fmtSize(v) },
      {
        title: '可见范围',
        dataIndex: 'scope',
        width: 100,
        render: (v: string) =>
          v === 'SHARED' ? <Tag color="green">共享</Tag> : <Tag>私有</Tag>,
      },
      { title: '上传者', dataIndex: 'uploadedBy', width: 120 },
      {
        title: '上传时间',
        dataIndex: 'createdAt',
        width: 170,
        render: (v: string) => fmtTime(v),
      },
      {
        title: '操作',
        key: 'ops',
        width: 220,
        render: (_, r) => (
          <Space size={4}>
            {isImageAttachment(r.contentType) ? (
              <Button
                size="small"
                type="link"
                onClick={() => window.open(attachmentRawUrl(r.attachmentId), '_blank')}
              >
                预览
              </Button>
            ) : (
              <Button
                size="small"
                type="link"
                href={attachmentRawUrl(r.attachmentId)}
                download={r.originalName}
              >
                下载
              </Button>
            )}
            <Button
              size="small"
              type="link"
              onClick={() => copyText(r.attachmentId, '附件 id 已复制')}
            >
              id
            </Button>
            <Dropdown
              menu={{
                items: [
                  { key: 'copy-url', label: '复制访问 URL' },
                  { key: 'scope', label: r.scope === 'PRIVATE' ? '设为共享' : '设为私有' },
                ],
                onClick: ({ key }) => {
                  if (key === 'copy-url')
                    copyText(`${location.origin}${attachmentRawUrl(r.attachmentId)}`, 'URL 已复制')
                  else if (key === 'scope') onToggleScope(r)
                },
              }}
            >
              <Button size="small" type="text">
                更多
              </Button>
            </Dropdown>
            <Button size="small" type="text" danger onClick={() => onDelete(r)}>
              删除
            </Button>
          </Space>
        ),
      },
    ],
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [],
  )

  return (
    <Card
      style={pageCardStyle}
      styles={{ body: pageCardBodyScrollStyle }}
      title={
        <Space size={12}>
          <span>附件管理</span>
          <Segmented
            value={scopeFilter}
            onChange={(v) => setScopeFilter(v as string)}
            options={[
              { label: '全部', value: 'ALL' },
              { label: '私有', value: 'PRIVATE' },
              { label: '共享', value: 'SHARED' },
            ]}
          />
          <Segmented
            value={typeFilter}
            onChange={(v) => setTypeFilter(v as string)}
            options={[
              { label: '全部类型', value: 'ALL' },
              { label: '图片', value: 'image' },
              { label: '文件', value: 'other' },
            ]}
          />
        </Space>
      }
      extra={
        <Space>
          <Input.Search
            allowClear
            placeholder="按文件名/描述搜索"
            style={{ width: 220 }}
            onSearch={(v) => setKeyword(v)}
          />
          <Button icon={<ReloadOutlined />} onClick={load}>
            刷新
          </Button>
          <Button type="primary" icon={<UploadOutlined />} onClick={() => setUploadOpen(true)}>
            上传附件
          </Button>
        </Space>
      }
    >
      <Typography.Paragraph type="secondary">
        平台统一附件库（图床 + 文件）：图片可内联引用，其他类型仅下载；复制附件 id 即可在问答、文档等处引用。
      </Typography.Paragraph>
      <Table
        rowKey="attachmentId"
        loading={loading}
        columns={columns}
        dataSource={rows}
        pagination={false}
        locale={{
          emptyText: (
            <span>
              暂无附件，点击右上角「上传附件」或在 AI 问答/文档编辑器中粘贴图片试试。
            </span>
          ),
        }}
      />
      <Modal
        title="上传附件"
        open={uploadOpen}
        okText={uploading ? '上传中…' : '开始上传'}
        okButtonProps={{ disabled: fileList.length === 0 || uploading }}
        cancelButtonProps={{ disabled: uploading }}
        maskClosable={!uploading}
        onOk={startUpload}
        onCancel={() => {
          setUploadOpen(false)
          resetUpload()
        }}
      >
        <Space direction="vertical" size={12} style={{ width: '100%' }}>
          <Input.TextArea
            rows={2}
            maxLength={512}
            showCount
            placeholder="描述信息（可选，本批文件共用）"
            value={description}
            disabled={uploading}
            onChange={(e) => setDescription(e.target.value)}
          />
          <Upload.Dragger
            multiple
            fileList={fileList}
            showUploadList={false}
            disabled={uploading}
            beforeUpload={() => false}
            onChange={({ fileList: next }) => setFileList(next)}
          >
            <p style={{ fontSize: 32, color: '#1677ff', margin: '8px 0' }}>
              <InboxOutlined />
            </p>
            <p style={{ margin: 0 }}>点击或拖拽文件到此处，可多选</p>
          </Upload.Dragger>
          {fileList.map((f) => (
            <div key={f.uid}>
              <Space size={4}>
                <Typography.Text style={{ maxWidth: 360 }} ellipsis={{ tooltip: f.name }}>
                  {f.name}
                </Typography.Text>
                {!uploading && (
                  <Button
                    type="text"
                    size="small"
                    icon={<DeleteOutlined />}
                    onClick={() => setFileList((prev) => prev.filter((x) => x.uid !== f.uid))}
                  />
                )}
              </Space>
              {(progress[f.uid] !== undefined || failed[f.uid]) && (
                <Progress
                  percent={progress[f.uid] ?? 0}
                  size="small"
                  status={
                    failed[f.uid] ? 'exception' : progress[f.uid] === 100 ? 'success' : 'active'
                  }
                />
              )}
              {failed[f.uid] && (
                <Typography.Text type="danger" style={{ fontSize: 12 }}>
                  上传失败：{failed[f.uid]}
                </Typography.Text>
              )}
            </div>
          ))}
        </Space>
      </Modal>
    </Card>
  )
}
