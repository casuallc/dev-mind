// 需求附件面板：新建抽屉（暂存模式：本地暂存，需求保存后 flush 上传+绑定）与
// 编辑抽屉/详情页附件 Tab（直挂模式：即传即绑、即删即清）共用。
// 绑定走 relations 稀疏边（requirement --attached--> attachment，零后端改动）；
// 上传 scope=SHARED 项目成员可见；「移除」= 删边 + 删文件本体（best-effort）。
import { forwardRef, useCallback, useEffect, useImperativeHandle, useRef, useState } from 'react'
import { Button, Image, List, Modal, Space, Typography, message } from 'antd'
import { DeleteOutlined, FileOutlined, UploadOutlined } from '@ant-design/icons'
import {
  detachAndDeleteAttachment,
  listRequirementAttachments,
  uploadAndAttach,
} from '../api'
import type { RequirementAttachment } from '../types'
import { isImageAttachment } from '../../../shared/attachments/api'
import { attachmentRawUrl } from '../../../shared/attachments/url'
import { fmtBytes, fmtTime } from '../../../shared/utils/format'
import { showError } from '../../../shared/utils/showError'

/** 暂存模式句柄：需求创建成功后由父组件调用，逐个上传暂存文件并绑定（返回失败数，不抛） */
export interface RequirementAttachmentPanelHandle {
  flush(requirementId: string): Promise<number>
}

export default forwardRef<RequirementAttachmentPanelHandle, {
  projectId: string
  /** null=暂存模式（新建抽屉）；有值=直挂模式（编辑抽屉 / 详情页 Tab） */
  requirementId: string | null
  /** 直挂模式附件数变化回调（详情页 Tab 计数用） */
  onCountChange?: (n: number) => void
}>(function RequirementAttachmentPanel({ projectId, requirementId, onCountChange }, ref) {
  const direct = !!requirementId
  const [staged, setStaged] = useState<File[]>([])
  const [items, setItems] = useState<RequirementAttachment[]>([])
  const [loading, setLoading] = useState(false)
  const [busy, setBusy] = useState(false)
  const fileInputRef = useRef<HTMLInputElement>(null)

  const reload = useCallback(async () => {
    if (!requirementId) return
    setLoading(true)
    try {
      const rows = await listRequirementAttachments(projectId, requirementId)
      setItems(rows)
      onCountChange?.(rows.length)
    } catch (e) {
      showError(e, '加载附件失败')
    } finally {
      setLoading(false)
    }
  }, [projectId, requirementId, onCountChange])

  useEffect(() => {
    reload()
  }, [reload])

  useImperativeHandle(ref, () => ({
    flush: async (rid: string) => {
      let failed = 0
      for (const f of staged) {
        try {
          await uploadAndAttach(projectId, rid, f)
        } catch {
          failed++
        }
      }
      setStaged([])
      return failed
    },
  }), [staged, projectId])

  const addFiles = async (files: File[]) => {
    if (!direct) {
      setStaged((prev) => [...prev, ...files])
      return
    }
    setBusy(true)
    let failed = 0
    for (const f of files) {
      try {
        await uploadAndAttach(projectId, requirementId, f)
      } catch {
        failed++
      }
    }
    if (failed > 0) message.warning(`${failed} 个附件上传失败`)
    await reload()
    setBusy(false)
  }

  const confirmRemove = (row: RequirementAttachment) => {
    Modal.confirm({
      centered: true,
      title: '移除附件？',
      content: `将从需求移除并删除文件「${row.attachment.originalName}」（附件库中一并删除）。`,
      okText: '移除',
      okButtonProps: { danger: true },
      cancelText: '返回',
      onOk: async () => {
        try {
          await detachAndDeleteAttachment(projectId, row.relationId, row.attachment.attachmentId)
          message.success('已移除')
          await reload()
        } catch (e) {
          showError(e, '移除失败')
        }
      },
    })
  }

  return (
    <div>
      <Space size={8}>
        <Button icon={<UploadOutlined />} loading={busy} onClick={() => fileInputRef.current?.click()}>
          上传附件
        </Button>
        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
          {direct ? '图片可预览，其他类型点击文件名下载；上传即生效' : '保存需求后随之一并上传'}
        </Typography.Text>
      </Space>
      {/* 隐藏 input 即选即传（NewSessionDraft 同款姿势）；value 清空保证同名文件可重复选 */}
      <input
        ref={fileInputRef}
        type="file"
        multiple
        style={{ display: 'none' }}
        onChange={(e) => {
          const files = Array.from(e.target.files ?? [])
          e.target.value = ''
          if (files.length) addFiles(files)
        }}
      />
      {!direct && staged.length > 0 && (
        <List
          size="small"
          style={{ marginTop: 8 }}
          dataSource={staged}
          rowKey={(f) => `${f.name}-${f.size}-${f.lastModified}`}
          renderItem={(f, i) => (
            <List.Item
              actions={[
                <Button
                  key="del"
                  type="text"
                  size="small"
                  danger
                  icon={<DeleteOutlined />}
                  onClick={() => setStaged((prev) => prev.filter((_, j) => j !== i))}
                />,
              ]}
            >
              <Space size={8}>
                <FileOutlined />
                <Typography.Text>{f.name}</Typography.Text>
                <Typography.Text type="secondary">{fmtBytes(f.size)}</Typography.Text>
              </Space>
            </List.Item>
          )}
        />
      )}
      {direct && (
        <List
          size="small"
          loading={loading}
          style={{ marginTop: 8 }}
          locale={{ emptyText: '暂无附件' }}
          dataSource={items}
          rowKey={(r) => r.relationId}
          renderItem={(row) => {
            const a = row.attachment
            return (
              <List.Item
                actions={[
                  <Button
                    key="del"
                    type="text"
                    size="small"
                    danger
                    icon={<DeleteOutlined />}
                    onClick={() => confirmRemove(row)}
                  />,
                ]}
              >
                <Space size={8}>
                  {isImageAttachment(a.contentType) ? (
                    <Image
                      src={attachmentRawUrl(a.attachmentId)}
                      alt={a.originalName}
                      width={40}
                      height={40}
                      style={{ objectFit: 'cover', borderRadius: 4 }}
                    />
                  ) : (
                    <FileOutlined style={{ fontSize: 20 }} />
                  )}
                  <a href={attachmentRawUrl(a.attachmentId)} target="_blank" rel="noreferrer">
                    {a.originalName}
                  </a>
                  <Typography.Text type="secondary">{fmtBytes(a.sizeBytes)}</Typography.Text>
                  <Typography.Text type="secondary">{fmtTime(a.createdAt)}</Typography.Text>
                </Space>
              </List.Item>
            )
          }}
        />
      )}
    </div>
  )
})
