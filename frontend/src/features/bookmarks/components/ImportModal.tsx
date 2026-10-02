import { Modal, Typography, Upload } from 'antd'
import { InboxOutlined } from '@ant-design/icons'
import { useState } from 'react'
import type { ImportNode } from '../types'
import { importStats, parseBookmarkFile } from '../utils/netscape'

interface Props {
  open: boolean
  saving: boolean
  onClose(): void
  onOk(nodes: ImportNode[]): void
}

/**
 * FR-09 导入浏览器书签：选文件 → DOMParser 本地解析 → 预览体量 → 确认上传结构化树。
 * 解析失败/无书签条目只提示不上传；重复与无效地址由服务端跳过并计数。
 */
export default function ImportModal({ open, saving, onClose, onOk }: Props) {
  const [fileName, setFileName] = useState<string | null>(null)
  const [nodes, setNodes] = useState<ImportNode[] | null>(null)
  const [error, setError] = useState<string | null>(null)

  const stats = nodes ? importStats(nodes) : null

  const readFile = async (file: File) => {
    try {
      const parsed = parseBookmarkFile(await file.text())
      const s = importStats(parsed)
      if (s.bookmarks === 0) {
        throw new Error('文件里没有书签条目')
      }
      setNodes(parsed)
      setFileName(file.name)
      setError(null)
    } catch (e) {
      setNodes(null)
      setFileName(null)
      setError(e instanceof Error ? e.message : '解析失败')
    }
  }

  return (
    <Modal
      title="导入浏览器书签"
      open={open}
      onCancel={onClose}
      okText="开始导入"
      confirmLoading={saving}
      okButtonProps={{ disabled: !nodes }}
      onOk={() => nodes && onOk(nodes)}
      afterClose={() => {
        setNodes(null)
        setFileName(null)
        setError(null)
      }}
    >
      <Typography.Paragraph type="secondary" style={{ fontSize: 12 }}>
        支持 Chrome / Edge / Firefox 导出的书签 HTML（书签管理器 → 导出书签）。文件夹会变成同名分组
        （已有同名分组直接复用），地址重复的收藏自动跳过。
      </Typography.Paragraph>
      <Upload.Dragger
        accept=".html,.htm"
        maxCount={1}
        showUploadList={false}
        beforeUpload={(f) => {
          void readFile(f)
          return false // 不自动上传，确认后才走导入接口
        }}
      >
        <p style={{ fontSize: 28, margin: 0 }}>
          <InboxOutlined />
        </p>
        <p style={{ margin: '8px 0 4px' }}>点击或拖入书签 HTML 文件</p>
      </Upload.Dragger>
      {stats && (
        <Typography.Text style={{ display: 'block', marginTop: 8 }}>
          已解析「{fileName}」：{stats.folders} 个文件夹、{stats.bookmarks} 条书签
        </Typography.Text>
      )}
      {error && (
        <Typography.Text type="danger" style={{ display: 'block', marginTop: 8 }}>
          {error}
        </Typography.Text>
      )}
    </Modal>
  )
}
