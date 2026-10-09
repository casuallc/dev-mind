// 附件管理页（CAP-32）：平台统一附件（图床+文件）的查看/上传/共享范围/删除，挂在后台「内容」分组。
// 上传走弹窗：先选文件（可多选）再填描述，逐个上传并展示逐文件进度条。
// 图片类附件内联预览，非图片仅提供下载；其他地方凭 attachmentId 引用。
// CAP-68：标签（上传可带/编辑可改/列表过滤）、过期时间（到期定时硬删，不做引用检查）、批量删除。
import { useCallback, useEffect, useMemo, useState } from 'react'
import {
  Button,
  Card,
  DatePicker,
  Dropdown,
  Image,
  Input,
  InputNumber,
  message,
  Modal,
  Progress,
  Segmented,
  Space,
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
import dayjs, { type Dayjs } from 'dayjs'
import {
  batchDeleteAttachments,
  deleteAttachment,
  isImageAttachment,
  listAttachments,
  updateAttachmentMeta,
  updateAttachmentScope,
  uploadAttachment,
  type AttachmentView,
} from '../../../shared/attachments/api'
import { attachmentRawUrl } from '../../../shared/attachments/url'
import { fmtTime } from '../../../shared/utils/format'
import { pageCardStyle, pageCardBodyFlexStyle } from '../../../shared/utils/pageLayout'
import FitTable from '../../../shared/components/FitTable'
import { showError } from '../../../shared/utils/showError'
import { LIST_PAGINATION } from '../../../shared/utils/table'

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
  // CAP-68：标签过滤（tagInput 输入态 / tagFilter 生效态，回车或点标签生效）+ 批量删除选择
  const [tagInput, setTagInput] = useState('')
  const [tagFilter, setTagFilter] = useState('')
  const [selectedKeys, setSelectedKeys] = useState<string[]>([])
  // 上传弹窗：暂存文件 + 描述/标签/保留天数 + 逐文件进度/失败信息
  const [uploadOpen, setUploadOpen] = useState(false)
  const [fileList, setFileList] = useState<UploadFile[]>([])
  const [description, setDescription] = useState('')
  const [uploadTags, setUploadTags] = useState('')
  const [expireDays, setExpireDays] = useState<number | null>(null)
  const [uploading, setUploading] = useState(false)
  const [progress, setProgress] = useState<Record<string, number>>({})
  const [failed, setFailed] = useState<Record<string, string>>({})
  // CAP-68：编辑元数据弹窗（描述/标签/过期时间；空白=清除，留空过期=永久）
  const [metaTarget, setMetaTarget] = useState<AttachmentView | null>(null)
  const [metaDescription, setMetaDescription] = useState('')
  const [metaTags, setMetaTags] = useState('')
  const [metaExpiresAt, setMetaExpiresAt] = useState<Dayjs | null>(null)
  const [metaSaving, setMetaSaving] = useState(false)

  const load = useCallback(() => {
    setLoading(true)
    listAttachments({
      scope: scopeFilter === 'ALL' ? undefined : scopeFilter,
      type: typeFilter === 'ALL' ? undefined : (typeFilter as 'image' | 'other'),
      keyword: keyword.trim() || undefined,
      tag: tagFilter.trim() || undefined,
    })
      .then(setRows)
      .catch((e) => showError(e, '加载失败'))
      .finally(() => setLoading(false))
  }, [scopeFilter, typeFilter, keyword, tagFilter])

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
    setUploadTags('')
    setExpireDays(null)
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
        await uploadAttachment(
          f as unknown as File,
          f.name,
          'PRIVATE',
          description,
          (p) => setProgress((prev) => ({ ...prev, [f.uid]: p })),
          uploadTags,
          expireDays ?? undefined,
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

  // CAP-68：批量删除（逐项服务端校验，部分失败汇总提示）
  const onBatchDelete = () => {
    Modal.confirm({
      centered: true,
      title: `确认删除选中的 ${selectedKeys.length} 个附件？`,
      content: '删除后不可恢复；被消息/文档引用的附件删除后引用处将 404。',
      okText: '删除',
      okButtonProps: { danger: true },
      onOk: () =>
        batchDeleteAttachments(selectedKeys)
          .then((results) => {
            const fails = results.filter((r) => !r.ok)
            if (fails.length === 0) {
              message.success(`已删除 ${results.length} 个附件`)
            } else {
              message.warning(
                `删除完成：成功 ${results.length - fails.length} 个，失败 ${fails.length} 个（${fails[0].message ?? '无权限'}）`,
              )
            }
            setSelectedKeys([])
            load()
          })
          .catch((e) => showError(e, '批量删除失败')),
    })
  }

  const openMetaEdit = (r: AttachmentView) => {
    setMetaTarget(r)
    setMetaDescription(r.description ?? '')
    setMetaTags(r.tags ?? '')
    setMetaExpiresAt(r.expiresAt ? dayjs(r.expiresAt) : null)
  }

  const saveMeta = () => {
    if (!metaTarget) return
    setMetaSaving(true)
    // 整表单提交：空白串=清除该字段（后端语义），过期留空=恢复永久
    updateAttachmentMeta(metaTarget.attachmentId, {
      description: metaDescription,
      tags: metaTags,
      expiresAt: metaExpiresAt ? metaExpiresAt.format('YYYY-MM-DD HH:mm:ss') : ' ',
    })
      .then(() => {
        message.success('已保存')
        setMetaTarget(null)
        load()
      })
      .catch((e) => showError(e, '保存失败'))
      .finally(() => setMetaSaving(false))
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
        title: '标签',
        dataIndex: 'tags',
        width: 160,
        render: (v?: string) =>
          v ? (
            <Space size={2} wrap>
              {v.split(',').map((t) => (
                <Tag key={t} style={{ marginInlineEnd: 0, cursor: 'pointer' }} onClick={() => { setTagInput(t); setTagFilter(t) }}>
                  {t}
                </Tag>
              ))}
            </Space>
          ) : (
            <Typography.Text type="secondary">-</Typography.Text>
          ),
      },
      {
        title: '过期时间',
        dataIndex: 'expiresAt',
        width: 170,
        render: (v?: string) =>
          v ? (
            <Typography.Text type="warning">{fmtTime(v)}</Typography.Text>
          ) : (
            <Typography.Text type="secondary">永久</Typography.Text>
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
                  { key: 'edit-meta', label: '编辑描述/标签/过期' },
                  { key: 'copy-url', label: '复制访问 URL' },
                  { key: 'scope', label: r.scope === 'PRIVATE' ? '设为共享' : '设为私有' },
                ],
                onClick: ({ key }) => {
                  if (key === 'edit-meta') openMetaEdit(r)
                  else if (key === 'copy-url')
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
      styles={{ body: pageCardBodyFlexStyle }}
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
          <Input
            allowClear
            placeholder="按标签过滤（回车生效）"
            style={{ width: 170 }}
            value={tagInput}
            onChange={(e) => setTagInput(e.target.value)}
            onPressEnter={() => setTagFilter(tagInput.trim())}
            onClear={() => {
              setTagInput('')
              setTagFilter('')
            }}
          />
          <Input.Search
            allowClear
            placeholder="按文件名/描述搜索"
            style={{ width: 200 }}
            onSearch={(v) => setKeyword(v)}
          />
          <Button icon={<ReloadOutlined />} onClick={load}>
            刷新
          </Button>
          <Button danger disabled={selectedKeys.length === 0} onClick={onBatchDelete}>
            批量删除{selectedKeys.length > 0 ? `（${selectedKeys.length}）` : ''}
          </Button>
          <Button type="primary" icon={<UploadOutlined />} onClick={() => setUploadOpen(true)}>
            上传附件
          </Button>
        </Space>
      }
    >
      <Typography.Paragraph type="secondary">
        平台统一附件库（图床 + 文件）：图片可内联引用，其他类型仅下载；复制附件 id 即可在问答、文档等处引用。
        设了过期时间的附件到期会被定时任务<strong>直接删除</strong>（不检查是否被消息/文档引用，引用处将 404）。
      </Typography.Paragraph>
      <FitTable
        rowKey="attachmentId"
        loading={loading}
        columns={columns}
        dataSource={rows}
        pagination={LIST_PAGINATION}
        rowSelection={{
          selectedRowKeys: selectedKeys,
          onChange: (keys) => setSelectedKeys(keys as string[]),
        }}
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
          <Space style={{ width: '100%' }}>
            <Input
              style={{ flex: 1, minWidth: 220 }}
              maxLength={512}
              placeholder="标签（可选，逗号分隔，本批共用）"
              value={uploadTags}
              disabled={uploading}
              onChange={(e) => setUploadTags(e.target.value)}
            />
            <InputNumber
              min={1}
              precision={0}
              placeholder="保留天数"
              addonAfter="天"
              value={expireDays}
              disabled={uploading}
              onChange={(v) => setExpireDays(v)}
              style={{ width: 150 }}
            />
          </Space>
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            保留天数留空 = 永久；设了到期会被定时任务直接删除（不检查引用）。
          </Typography.Text>
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
      {/* CAP-68：编辑描述/标签/过期时间 */}
      <Modal
        title={`编辑附件信息：${metaTarget?.originalName ?? ''}`}
        open={metaTarget !== null}
        okText="保存"
        confirmLoading={metaSaving}
        onOk={saveMeta}
        onCancel={() => setMetaTarget(null)}
      >
        <Space direction="vertical" size={12} style={{ width: '100%' }}>
          <div>
            <Typography.Text type="secondary">描述（清空=删除描述）</Typography.Text>
            <Input.TextArea
              rows={2}
              maxLength={512}
              value={metaDescription}
              onChange={(e) => setMetaDescription(e.target.value)}
            />
          </div>
          <div>
            <Typography.Text type="secondary">标签（逗号分隔，清空=删除标签）</Typography.Text>
            <Input
              maxLength={512}
              value={metaTags}
              onChange={(e) => setMetaTags(e.target.value)}
            />
          </div>
          <div>
            <Typography.Text type="secondary">过期时间（留空=永久；到期定时硬删，不检查引用）</Typography.Text>
            <DatePicker
              showTime
              style={{ width: '100%' }}
              value={metaExpiresAt}
              onChange={(v) => setMetaExpiresAt(v)}
              placeholder="选择过期时间"
            />
          </div>
        </Space>
      </Modal>
    </Card>
  )
}
