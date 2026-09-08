// 附件管理页（CAP-32）：平台统一附件（图床+文件）的查看/上传/共享范围/删除。
// 图片类附件内联预览，非图片仅提供下载；其他地方凭 attachmentId 引用。
import { useCallback, useEffect, useMemo, useState } from 'react'
import {
  Button,
  Card,
  Dropdown,
  Image,
  Input,
  message,
  Popconfirm,
  Segmented,
  Space,
  Table,
  Tag,
  Typography,
  Upload,
} from 'antd'
import {
  CopyOutlined,
  DownloadOutlined,
  EyeOutlined,
  MoreOutlined,
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

  const load = useCallback(() => {
    setLoading(true)
    listAttachments({
      scope: scopeFilter === 'ALL' ? undefined : scopeFilter,
      type: typeFilter === 'ALL' ? undefined : (typeFilter as 'image' | 'other'),
      keyword: keyword.trim() || undefined,
    })
      .then(setRows)
      .catch((e) => message.error(`加载失败：${(e as Error).message}`))
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
      .catch((e) => message.error(`操作失败：${(e as Error).message}`))
  }

  const onDelete = (r: AttachmentView) => {
    deleteAttachment(r.attachmentId)
      .then(() => {
        message.success('已删除')
        load()
      })
      .catch((e) => message.error(`删除失败：${(e as Error).message}`))
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
                icon={<EyeOutlined />}
                onClick={() => window.open(attachmentRawUrl(r.attachmentId), '_blank')}
              >
                预览
              </Button>
            ) : (
              <Button
                size="small"
                type="link"
                icon={<DownloadOutlined />}
                href={attachmentRawUrl(r.attachmentId)}
                download={r.originalName}
              >
                下载
              </Button>
            )}
            <Button
              size="small"
              type="link"
              icon={<CopyOutlined />}
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
              <Button size="small" type="text" icon={<MoreOutlined />} />
            </Dropdown>
            <Popconfirm title="删除后不可恢复，确认删除？" onConfirm={() => onDelete(r)}>
              <Button size="small" type="text" danger>
                删除
              </Button>
            </Popconfirm>
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
            placeholder="按文件名搜索"
            style={{ width: 220 }}
            onSearch={(v) => setKeyword(v)}
          />
          <Button icon={<ReloadOutlined />} onClick={load}>
            刷新
          </Button>
          <Upload
            multiple
            showUploadList={false}
            customRequest={({ file, onSuccess, onError }) => {
              uploadAttachment(file as File, (file as File).name)
                .then((v) => {
                  message.success(`已上传：${v.originalName}`)
                  onSuccess?.(v)
                  load()
                })
                .catch((e) => {
                  message.error(`上传失败：${(e as Error).message}`)
                  onError?.(e as Error)
                })
            }}
          >
            <Button type="primary" icon={<UploadOutlined />}>
              上传附件
            </Button>
          </Upload>
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
    </Card>
  )
}
