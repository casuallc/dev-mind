// 对话交互面板（CAP-30 由 sessions 上移并 apiBase 参数化）：授权请求条 + ChatStream 消息流 + 底部输入区。
// 项目会话（apiBase=/sessions）与通用问答（/chats）共用；内部自管 WS 实时流（活跃态）与 REST 历史回退（终态）。
// CAP-32：allowImages=true 时输入区支持粘贴/拖拽/选择图片（上传 CAP-32 附件模块后随消息发送）。
// 仅问答传入——项目会话后端链路未接，显式门控防"上传成功但 agent 没收到"。
// effect 依赖只用 summary.id/summary.state 标量——summary 对象可能来自轮询，引用每次变化。
import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { Button, Card, Image, Input, message, Space, Typography } from 'antd'
import { CloseOutlined, PaperClipOutlined, SendOutlined } from '@ant-design/icons'
import { api } from '../api/client'
import { uploadAttachment, type AttachmentView } from '../attachments/api'
import { attachmentRawUrl } from '../attachments/url'
import type { ChatApiBase, ChatEvent, ChatImageAttachment, ChatSummaryBase, StreamMeta } from './types'
import { useChatStream } from './useChatStream'
import { ACTIVE_STATES } from './stateMeta'
import ChatStream from './ChatStream'
import { showError } from '../utils/showError'

export default function ChatPanel({
  summary,
  apiBase,
  maxHeight = '56vh',
  allowImages = false,
  onChanged,
  onStreamMeta,
}: {
  summary: ChatSummaryBase
  /** REST/WS 路径前缀：项目会话 '/sessions'，通用问答 '/chats' */
  apiBase: ChatApiBase
  /** 消息流最大高度；传 null 表示外层是 flex 容器、由面板撑满剩余高度 */
  maxHeight?: number | string | null
  /** CAP-32：是否允许发送图片附件（仅 /chats 后端链路支持） */
  allowImages?: boolean
  /** 授权/发送后通知外部刷新摘要 */
  onChanged?: () => void
  /** 实时流连接状态回传（外层做徽标） */
  onStreamMeta?: (meta: StreamMeta) => void
}) {
  const [pendingReq, setPendingReq] = useState<ChatEvent | null>(null)
  const [inputText, setInputText] = useState('')
  const [pendingImages, setPendingImages] = useState<AttachmentView[]>([])
  const [uploading, setUploading] = useState(0)
  const fileInputRef = useRef<HTMLInputElement>(null)
  const [baseEvents, setBaseEvents] = useState<ChatEvent[]>([])

  const isLive = ACTIVE_STATES.includes(summary.state)
  const { events, connected, fatal, input, authorize: wsAuthorize } = useChatStream(summary.id, apiBase, isLive)

  // 连接状态回传外层（徽标）
  useEffect(() => {
    onStreamMeta?.({ connected, fatal })
  }, [connected, fatal, onStreamMeta])

  // 终态会话（进程已结束/重启恢复）无 WS 运行时，退化为 REST 拉取事件历史
  useEffect(() => {
    if (isLive) return
    let cancelled = false
    api
      .get<ChatEvent[]>(`${apiBase}/${summary.id}/events?afterSeq=-1`)
      .then((evs) => {
        if (!cancelled) setBaseEvents(evs)
      })
      .catch(() => undefined)
    return () => {
      cancelled = true
    }
  }, [summary.id, apiBase, isLive])

  // 捕获最近的授权请求
  useEffect(() => {
    if (summary.state !== 'WAITING_AUTH') {
      setPendingReq(null)
      return
    }
    const req = [...events].reverse().find((e) => e.type === 'permission_request')
    setPendingReq(req ?? null)
  }, [events, summary.state])

  // CAP-32：图片入队即上传（发送时只带附件 id）；非图片提示后忽略（chat 链路仅支持图片）
  const addImageFiles = useCallback(
    (files: Iterable<File>) => {
      for (const file of files) {
        if (!file.type.startsWith('image/')) {
          message.warning(`仅支持图片附件，已忽略：${file.name}`)
          continue
        }
        setUploading((n) => n + 1)
        uploadAttachment(file, file.name)
          .then((v) => setPendingImages((prev) => [...prev, v]))
          .catch((e) => showError(e, '图片上传失败'))
          .finally(() => setUploading((n) => n - 1))
      }
    },
    [],
  )

  const onPaste = useCallback(
    (e: React.ClipboardEvent) => {
      if (!allowImages) return
      const files = Array.from(e.clipboardData?.files ?? [])
      if (files.length > 0) {
        e.preventDefault()
        addImageFiles(files)
      }
    },
    [allowImages, addImageFiles],
  )

  const onSend = useCallback(
    (text: string) => {
      const t = text.trim()
      if (!t && pendingImages.length === 0) return
      const images: ChatImageAttachment[] = pendingImages.map((v) => ({
        attachmentId: v.attachmentId,
        name: v.originalName,
        contentType: v.contentType,
      }))
      input(t, images)
      setInputText('')
      setPendingImages([])
    },
    [input, pendingImages],
  )

  const onAuthorize = useCallback(
    (accepted: boolean, scope: string) => {
      if (!pendingReq) return
      const requestId = pendingReq.payload?.requestId as string | undefined
      api
        .post(`${apiBase}/${summary.id}/authorize`, { accepted, scope, requestId })
        .then(() => wsAuthorize(accepted, scope, requestId))
        .then(() => {
          message.success(accepted ? `已允许（${scope}）` : '已拒绝')
          setPendingReq(null)
          onChanged?.()
        })
        .catch((e) => showError(e, '授权失败'))
    },
    [summary.id, apiBase, pendingReq, wsAuthorize, onChanged],
  )

  // 合并 REST 历史 + WS 实时事件，按 seq 排序去重
  const history = useMemo(() => {
    const merged = new Map<number, ChatEvent>()
    for (const e of baseEvents) merged.set(e.seq, e)
    for (const e of events) merged.set(e.seq, e)
    return Array.from(merged.values()).sort((a, b) => a.seq - b.seq)
  }, [baseEvents, events])

  const canInput = ACTIVE_STATES.includes(summary.state)

  return (
    <div style={maxHeight === null ? { display: 'flex', flexDirection: 'column', flex: 1, minHeight: 0 } : undefined}>
      {/* 授权请求条 */}
      {pendingReq && (
        <Card size="small" style={{ borderColor: '#fa8c16', background: '#fff7e6', marginBottom: 8 }}>
          <Space align="start" style={{ width: '100%', justifyContent: 'space-between' }}>
            <div>
              <Typography.Text strong style={{ color: '#d46b08' }}>
                权限请求
              </Typography.Text>
              <br />
              <Typography.Text>
                工具 <Typography.Text code>{(pendingReq.payload?.toolName as string) || '?'}</Typography.Text>
              </Typography.Text>
              <Typography.Paragraph style={{ margin: '4px 0 0' }}>
                <pre style={{ whiteSpace: 'pre-wrap', margin: 0, fontSize: 12 }}>{pendingReq.content}</pre>
              </Typography.Paragraph>
            </div>
            <Space>
              <Button size="small" type="primary" onClick={() => onAuthorize(true, 'once')}>
                允许一次
              </Button>
              <Button size="small" onClick={() => onAuthorize(true, 'session')}>
                本次会话允许
              </Button>
              <Button size="small" danger onClick={() => onAuthorize(false, 'once')}>
                拒绝
              </Button>
            </Space>
          </Space>
        </Card>
      )}

      <ChatStream
        events={history}
        taskSpec={summary.topic}
        model={summary.model}
        maxHeight={maxHeight}
        emptyText={`等待事件…（${fatal ? '会话已结束' : connected ? '连接正常' : '重连中'}）`}
      />
      <div
        style={{ borderTop: '1px solid #f0f0f0', marginTop: 8, paddingTop: 12, flexShrink: 0 }}
        onDragOver={allowImages ? (e) => e.preventDefault() : undefined}
        onDrop={
          allowImages
            ? (e) => {
                e.preventDefault()
                if (canInput) addImageFiles(Array.from(e.dataTransfer?.files ?? []))
              }
            : undefined
        }
      >
        {/* 待发送图片缩略图横条 */}
        {pendingImages.length > 0 && (
          <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap', marginBottom: 8 }}>
            {pendingImages.map((img) => (
              <div key={img.attachmentId} style={{ position: 'relative' }}>
                <Image
                  src={attachmentRawUrl(img.attachmentId)}
                  alt={img.originalName}
                  width={56}
                  height={56}
                  style={{ objectFit: 'cover', borderRadius: 6, border: '1px solid #f0f0f0' }}
                />
                <Button
                  size="small"
                  type="text"
                  icon={<CloseOutlined />}
                  style={{ position: 'absolute', top: -6, right: -6, background: '#fff', boxShadow: '0 0 2px rgba(0,0,0,0.2)' }}
                  onClick={() =>
                    setPendingImages((prev) => prev.filter((p) => p.attachmentId !== img.attachmentId))
                  }
                />
              </div>
            ))}
          </div>
        )}
        <Space.Compact style={{ width: '100%' }}>
          <Input.TextArea
            autoSize={{ minRows: 1, maxRows: 5 }}
            disabled={!canInput}
            value={inputText}
            placeholder={
              canInput
                ? summary.state === 'WAITING_INPUT'
                  ? `回复 agent 的提问，Enter 发送 / Shift+Enter 换行${allowImages ? '，可粘贴/拖拽图片' : ''}…`
                  : summary.state === 'WAITING_AUTH'
                    ? '（正在等待授权，可在上方允许/拒绝）'
                    : `会话运行中，可注入指令，Enter 发送 / Shift+Enter 换行${allowImages ? '，可粘贴/拖拽图片' : ''}…`
                : '会话已结束，无法输入'
            }
            onChange={(e) => setInputText(e.target.value)}
            onPaste={onPaste}
            onPressEnter={(e) => {
              if (!e.shiftKey) {
                e.preventDefault()
                onSend(inputText)
              }
            }}
          />
          {allowImages && (
            <>
              <input
                ref={fileInputRef}
                type="file"
                accept="image/*"
                multiple
                hidden
                onChange={(e) => {
                  addImageFiles(Array.from(e.target.files ?? []))
                  e.target.value = ''
                }}
              />
              <Button
                icon={<PaperClipOutlined />}
                disabled={!canInput}
                loading={uploading > 0}
                onClick={() => fileInputRef.current?.click()}
              />
            </>
          )}
          <Button
            type="primary"
            icon={<SendOutlined />}
            disabled={!canInput || (!inputText.trim() && pendingImages.length === 0) || uploading > 0}
            onClick={() => onSend(inputText)}
          >
            发送
          </Button>
        </Space.Compact>
      </div>
    </div>
  )
}
