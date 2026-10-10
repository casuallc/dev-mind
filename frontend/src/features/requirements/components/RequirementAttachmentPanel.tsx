// 需求附件面板（详情页「附件」Tab）：描述文本是附件绑定的唯一事实源——
// 列表从 description 解析本地 Markdown 引用（/api/attachments/{id}/raw）+ Jira wiki 图（!name.png!），
// 与 CAP-40 会话投送同一口径（描述里引用的附件才会物化进 AI 会话工作区）。
// 「上传」= 传附件库（SHARED 项目成员可见）+ 引用行追加进描述（updateRequirement）；
// 「移除」= 描述剔除引用 + 删文件本体（best-effort）。Jira 来源描述由同步托管，整组只读。
import { useCallback, useEffect, useRef, useState } from 'react'
import { Button, Image, List, Modal, Space, Tag, Typography, message } from 'antd'
import { DeleteOutlined, FileOutlined, UploadOutlined } from '@ant-design/icons'
import { updateRequirement } from '../api'
import type { Requirement, RequirementInput } from '../types'
import {
  appendRefLines,
  buildRefLine,
  extractAttachmentRefs,
  extractWikiImageNames,
  stripAttachmentRef,
} from './attachmentRefs'
import { deleteAttachment, getAttachment, uploadAttachment } from '../../../shared/attachments/api'
import type { AttachmentView } from '../../../shared/attachments/api'
import { attachmentRawUrl, withAccessToken } from '../../../shared/attachments/url'
import { fmtBytes, fmtTime } from '../../../shared/utils/format'
import { showError } from '../../../shared/utils/showError'

/** 列表行：本地附件引用（带元数据，文件已删则 meta=null 灰显）/ Jira wiki 图（只读） */
type Row =
  | { kind: 'local'; id: string; name: string; image: boolean; meta: AttachmentView | null }
  | { kind: 'jira'; name: string }

/** 用需求当前值构造更新请求体（只换 description，其余字段原样回带） */
function toInput(r: Requirement, description: string): RequirementInput {
  return {
    title: r.title,
    description,
    ownerId: r.ownerId,
    docId: r.docId,
    type: r.type,
    priority: r.priority,
    assignee: r.assignee,
    reporter: r.reporter,
    labels: r.labels,
    fixVersions: r.fixVersions,
    dueDate: r.dueDate,
  }
}

export default function RequirementAttachmentPanel({ requirement, onCountChange, onChanged }: {
  requirement: Requirement
  /** 附件数变化回调（详情页 Tab 计数用） */
  onCountChange?: (n: number) => void
  /** 描述被改写（上传/移除引用）后回传最新需求，父组件刷新 */
  onChanged?: (updated: Requirement) => void
}) {
  const [rows, setRows] = useState<Row[]>([])
  const [loading, setLoading] = useState(false)
  const [busy, setBusy] = useState(false)
  const fileInputRef = useRef<HTMLInputElement>(null)
  const editable = requirement.source !== 'JIRA'

  const reload = useCallback(async () => {
    const description = requirement.description ?? ''
    const refs = extractAttachmentRefs(description)
    const wikiNames = extractWikiImageNames(description)
    onCountChange?.(refs.length + wikiNames.length)
    setLoading(true)
    try {
      const localRows: Row[] = await Promise.all(
        refs.map(async (ref) => {
          try {
            return { kind: 'local' as const, ...ref, meta: await getAttachment(ref.id) }
          } catch {
            return { kind: 'local' as const, ...ref, meta: null } // 文件已在附件管理页被删，灰显
          }
        }),
      )
      setRows([...localRows, ...wikiNames.map((name): Row => ({ kind: 'jira', name }))])
    } finally {
      setLoading(false)
    }
  }, [requirement.description, onCountChange])

  useEffect(() => {
    reload()
  }, [reload])

  /** 上传附件库（SHARED）后把引用行追加进描述（一次 updateRequirement 带全部新行） */
  const addFiles = async (files: File[]) => {
    setBusy(true)
    try {
      const lines: string[] = []
      let failed = 0
      for (const f of files) {
        try {
          const v = await uploadAttachment(f, f.name, 'SHARED')
          lines.push(buildRefLine(v.originalName, v.attachmentId, v.image))
        } catch {
          failed++
        }
      }
      if (failed > 0) message.warning(`${failed} 个附件上传失败`)
      if (lines.length > 0) {
        const saved = await updateRequirement(
          requirement.projectId,
          requirement.id,
          toInput(requirement, appendRefLines(requirement.description, lines)),
        )
        message.success('已上传')
        onChanged?.(saved)
      }
    } catch (e) {
      showError(e, '上传附件失败')
    } finally {
      setBusy(false)
    }
  }

  const confirmRemove = (row: Extract<Row, { kind: 'local' }>) => {
    Modal.confirm({
      centered: true,
      title: '移除附件？',
      content: `将从需求描述移除引用并删除文件「${row.meta?.originalName ?? row.name}」（附件库中一并删除）。`,
      okText: '移除',
      okButtonProps: { danger: true },
      cancelText: '返回',
      onOk: async () => {
        try {
          const saved = await updateRequirement(
            requirement.projectId,
            requirement.id,
            toInput(requirement, stripAttachmentRef(requirement.description ?? '', row.id)),
          )
          try {
            await deleteAttachment(row.id)
          } catch {
            // 文件删除失败（如非 owner）不阻断：描述引用已剔除，需求上已不再展示
          }
          message.success('已移除')
          onChanged?.(saved)
        } catch (e) {
          showError(e, '移除失败')
        }
      },
    })
  }

  return (
    <div>
      <Space size={8}>
        {editable && (
          <Button icon={<UploadOutlined />} loading={busy} onClick={() => fileInputRef.current?.click()}>
            上传附件
          </Button>
        )}
        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
          {editable
            ? '附件以引用形式写进需求描述，AI 会话可读取；也可在编辑描述时直接粘贴图片'
            : 'Jira 来源需求的描述由同步托管，附件只读'}
        </Typography.Text>
      </Space>
      {editable && (
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
      )}
      <List
        size="small"
        loading={loading}
        style={{ marginTop: 8 }}
        locale={{ emptyText: '暂无附件' }}
        dataSource={rows}
        rowKey={(r) => (r.kind === 'local' ? r.id : `jira-${r.name}`)}
        renderItem={(row) => {
          if (row.kind === 'jira') {
            const src = withAccessToken(
              `/api/projects/${requirement.projectId}/requirements/${requirement.id}/jira/attachments?name=${encodeURIComponent(row.name)}`,
            )
            return (
              <List.Item>
                <Space size={8}>
                  <Image
                    src={src}
                    alt={row.name}
                    width={40}
                    height={40}
                    style={{ objectFit: 'cover', borderRadius: 4 }}
                  />
                  <Typography.Text>{row.name}</Typography.Text>
                  <Tag>Jira</Tag>
                </Space>
              </List.Item>
            )
          }
          const meta = row.meta
          return (
            <List.Item
              actions={
                editable
                  ? [
                      <Button
                        key="del"
                        type="text"
                        size="small"
                        danger
                        icon={<DeleteOutlined />}
                        onClick={() => confirmRemove(row)}
                      />,
                    ]
                  : undefined
              }
            >
              <Space size={8}>
                {row.image || (meta && meta.image) ? (
                  <Image
                    src={attachmentRawUrl(row.id)}
                    alt={meta?.originalName ?? row.name}
                    width={40}
                    height={40}
                    style={{ objectFit: 'cover', borderRadius: 4 }}
                  />
                ) : (
                  <FileOutlined style={{ fontSize: 20 }} />
                )}
                {meta ? (
                  <a href={attachmentRawUrl(row.id)} target="_blank" rel="noreferrer">
                    {meta.originalName}
                  </a>
                ) : (
                  <Typography.Text type="secondary" delete>
                    {row.name}（文件已删除）
                  </Typography.Text>
                )}
                {meta && <Typography.Text type="secondary">{fmtBytes(meta.sizeBytes)}</Typography.Text>}
                {meta && <Typography.Text type="secondary">{fmtTime(meta.createdAt)}</Typography.Text>}
              </Space>
            </List.Item>
          )
        }}
      />
    </div>
  )
}
