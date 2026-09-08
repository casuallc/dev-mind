// 长文本省略展示 + 点击弹窗查看全文：复制 / JSON·XML 格式化 / 最大化。
// 移植自 admq-manager CopyableLongText：去掉 i18n（中文直写）、clipboard-copy（改 navigator.clipboard）、
// react-syntax-highlighter（不引大依赖，弹窗统一等宽 pre 渲染）。
import React, { useMemo, useState } from 'react'
import { Button, Modal, Popover, Spin, message } from 'antd'
import { CopyOutlined, AlignLeftOutlined, FullscreenOutlined, FullscreenExitOutlined } from '@ant-design/icons'

export interface CopyableLongTextProps {
  /** 要展示的文本内容 */
  text?: string
  /** 文本最大宽度，超出显示省略号 */
  maxWidth?: number | string
  /** 悬停 Popover 的最大宽度 */
  popoverMaxWidth?: number
  /** 点击后弹窗的标题 */
  modalTitle?: string
  /** 内容类型，决定弹窗内是否可格式化（JSON/XML） */
  fileType?: string
  /** 空值占位 */
  emptyText?: string
  /** 自定义样式类（仅默认触发器生效） */
  className?: string
  /**
   * 自定义触发器。
   * 传入时优先渲染该元素，点击后打开弹窗，不启用悬停预览。
   */
  children?: React.ReactElement
  /**
   * 是否启用悬停预览。
   * 关闭时仅保留点击弹窗，不展示悬浮 Popover。
   * @default true
   */
  showPopover?: boolean
  /**
   * 打开弹窗前执行的异步钩子。
   * 通常用于异步加载弹窗内容，执行期间触发器显示 loading。
   */
  onBeforeOpen?: () => Promise<void>
  /** 弹窗内容是否处于加载中 */
  loading?: boolean
}

const normalizeFileType = (fileType?: string): string => {
  if (!fileType) return 'TEXT'
  return fileType.toUpperCase()
}

// 简单的 XML 美化：在标签之间换行并按层级缩进
const formatXml = (xml: string): string => {
  const PADDING = '  '
  const reg = /(>)(<)(\/*)/g
  const lines = xml.replace(reg, '$1\n$2$3').trim().split('\n')
  let pad = 0
  return lines
    .map((rawLine) => {
      const line = rawLine.trim()
      if (!line) return null
      let indent = 0
      if (/^<\/\w/.test(line)) {
        // 纯闭合标签，先回退一层
        pad = Math.max(pad - 1, 0)
      } else if (/^<\w[^>]*[^/]>.*<\/\w/.test(line)) {
        // 同行开合标签，缩进不变
        indent = 0
      } else if (/^<\w[^>]*[^/]>$/.test(line)) {
        // 开标签，下一行缩进
        indent = 1
      }
      const result = PADDING.repeat(pad) + line
      pad += indent
      return result
    })
    .filter((line) => line !== null)
    .join('\n')
}

// 按内容类型格式化，不支持时返回 null
const formatByFileType = (content: string, fileType: string): string | null => {
  const normalized = normalizeFileType(fileType)
  if (normalized === 'JSON') {
    return JSON.stringify(JSON.parse(content), null, 2)
  }
  if (normalized === 'XML') {
    return formatXml(content)
  }
  return null
}

// 可格式化的内容类型
const isFormattableType = (fileType: string): boolean => {
  const normalized = normalizeFileType(fileType)
  return normalized === 'JSON' || normalized === 'XML'
}

const copyText = async (content: string): Promise<void> => {
  try {
    await navigator.clipboard.writeText(content)
  } catch {
    // 非安全上下文（如 http 局域网访问）clipboard API 不可用，退化为 execCommand
    const ta = document.createElement('textarea')
    ta.value = content
    ta.style.position = 'fixed'
    ta.style.opacity = '0'
    document.body.appendChild(ta)
    ta.select()
    document.execCommand('copy')
    document.body.removeChild(ta)
  }
}

const CopyableLongText: React.FC<CopyableLongTextProps> = ({
  text = '',
  maxWidth = 300,
  popoverMaxWidth = 400,
  modalTitle,
  fileType = 'TEXT',
  emptyText = '-',
  className,
  children,
  showPopover = true,
  onBeforeOpen,
  loading = false,
}) => {
  const resolvedModalTitle = modalTitle ?? '完整内容'
  const [modalVisible, setModalVisible] = useState(false)
  const [internalLoading, setInternalLoading] = useState(false)
  // 格式化后的内容，null 表示展示原始内容（关闭弹窗即还原）
  const [formattedText, setFormattedText] = useState<string | null>(null)
  // 弹窗是否最大化
  const [maximized, setMaximized] = useState(false)

  const isLoading = loading || internalLoading

  const displayText = text || emptyText
  const hasContent = Boolean(text)
  const canFormat = useMemo(() => isFormattableType(fileType), [fileType])
  // 弹窗中展示的内容：已格式化则用格式化结果，否则用原文
  const modalContent = formattedText ?? text

  const closeModal = () => {
    setModalVisible(false)
    setFormattedText(null)
    setMaximized(false)
  }

  const handleCopy = async () => {
    if (!hasContent) {
      message.warning('暂无内容可复制')
      return
    }
    try {
      await copyText(modalContent)
      message.success('复制成功')
    } catch (error) {
      console.error(error)
      message.error('复制失败')
    }
  }

  const handleFormat = () => {
    if (!hasContent) return
    try {
      const result = formatByFileType(text, fileType)
      if (result === null) {
        message.info('当前内容类型不支持格式化')
        return
      }
      setFormattedText(result)
      message.success('格式化成功')
    } catch (error) {
      console.error(error)
      message.error('格式化失败，内容可能不是合法的 ' + normalizeFileType(fileType))
    }
  }

  const handleOpenModal = async () => {
    if (!hasContent && !onBeforeOpen) return
    if (onBeforeOpen) {
      setInternalLoading(true)
      try {
        await onBeforeOpen()
        setModalVisible(true)
      } catch (error) {
        console.error(error)
      } finally {
        setInternalLoading(false)
      }
    } else {
      setModalVisible(true)
    }
  }

  const renderContent = (content: string, isPopover: boolean) => (
    <pre
      style={{
        margin: 0,
        padding: isPopover ? 0 : 16,
        whiteSpace: 'pre-wrap',
        wordBreak: 'break-all',
        fontSize: '13px',
        lineHeight: '1.6',
        background: isPopover ? 'transparent' : '#f5f5f5',
        borderRadius: isPopover ? 0 : 4,
        color: '#333',
      }}
    >
      {content || '暂无内容'}
    </pre>
  )

  const textStyle: React.CSSProperties = {
    maxWidth,
    overflow: 'hidden',
    textOverflow: 'ellipsis',
    whiteSpace: 'nowrap',
    cursor: hasContent ? 'pointer' : 'default',
    display: 'inline-block',
  }

  const defaultTrigger = (
    <span className={className} style={textStyle} onClick={handleOpenModal}>
      {displayText}
    </span>
  )

  const triggerElement = children
    ? React.cloneElement(children, {
        onClick: async (e: React.MouseEvent) => {
          (children.props as { onClick?: (e: React.MouseEvent) => void }).onClick?.(e)
          await handleOpenModal()
        },
      } as Partial<unknown>)
    : defaultTrigger

  return (
    <>
      {!children && hasContent && showPopover ? (
        <Popover
          content={
            <div style={{ maxWidth: popoverMaxWidth }}>
              {renderContent(displayText, true)}
            </div>
          }
          overlayStyle={{ maxWidth: popoverMaxWidth }}
        >
          {triggerElement}
        </Popover>
      ) : (
        triggerElement
      )}

      <Modal
        title={resolvedModalTitle}
        open={modalVisible}
        onCancel={closeModal}
        footer={[
          canFormat ? (
            <Button
              key="format"
              icon={<AlignLeftOutlined />}
              onClick={handleFormat}
              disabled={isLoading || !hasContent}
            >
              格式化
            </Button>
          ) : null,
          <Button
            key="maximize"
            icon={maximized ? <FullscreenExitOutlined /> : <FullscreenOutlined />}
            onClick={() => setMaximized((prev) => !prev)}
          >
            {maximized ? '还原' : '最大化'}
          </Button>,
          <Button
            key="copy"
            icon={<CopyOutlined />}
            onClick={handleCopy}
            disabled={isLoading}
          >
            复制内容
          </Button>,
          <Button key="close" onClick={closeModal}>
            关闭
          </Button>,
        ]}
        width={maximized ? '100%' : 720}
        style={
          maximized
            ? { top: 0, maxWidth: '100vw', margin: 0, paddingBottom: 0, height: '100vh' }
            : undefined
        }
        styles={{
          body: {
            maxHeight: maximized ? 'calc(100vh - 110px)' : 600,
            height: maximized ? 'calc(100vh - 110px)' : undefined,
            overflow: 'auto',
            padding: 0,
          },
        }}
      >
        <Spin spinning={isLoading}>
          <div style={{ padding: 16 }}>{renderContent(modalContent, false)}</div>
        </Spin>
      </Modal>
    </>
  )
}

export default CopyableLongText
