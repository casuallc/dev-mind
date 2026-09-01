// 上下文摘要 Tab：重新扫描生成 + 人工编辑保存。
import { useEffect, useState } from 'react'
import { Button, Input, Space, Typography, message } from 'antd'
import { DiffOutlined, ReloadOutlined } from '@ant-design/icons'
import { refreshSummary, saveSummary } from '../../api'
import type { ContextSummary } from '../../types'

export default function SummaryTab({ id, summary, onChanged, readOnly }: {
  id: string
  summary: ContextSummary
  onChanged: (s: ContextSummary) => void
  /** 只读模式（工作台 /settings 对 VIEWER）：隐藏生成/保存，文本框只读 */
  readOnly?: boolean
}) {
  const [text, setText] = useState(summary.summary)
  const [busy, setBusy] = useState(false)

  useEffect(() => setText(summary.summary), [summary.summary])

  const doRefresh = async () => {
    setBusy(true)
    try {
      const s = await refreshSummary(id)
      onChanged(s)
      message.success('已重新扫描生成摘要')
    } catch (e) {
      message.error(`生成失败：${(e as Error).message}`)
    } finally {
      setBusy(false)
    }
  }

  const doSave = async () => {
    setBusy(true)
    try {
      const s = await saveSummary(id, text)
      onChanged(s)
      message.success('已保存（人工修正）')
    } catch (e) {
      message.error(`保存失败：${(e as Error).message}`)
    } finally {
      setBusy(false)
    }
  }

  return (
    <Space direction="vertical" size={8} style={{ width: '100%' }}>
      <Space>
        {!readOnly && (
          <>
            <Button size="small" type="primary" icon={<ReloadOutlined />} loading={busy} onClick={doRefresh}>
              重新扫描生成
            </Button>
            <Button size="small" type="primary" ghost icon={<DiffOutlined />} loading={busy} onClick={doSave}>
              保存修改
            </Button>
          </>
        )}
        {summary.generatedAt && (
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            生成于 {new Date(summary.generatedAt).toLocaleString()}
          </Typography.Text>
        )}
      </Space>
      <Input.TextArea
        rows={18}
        value={text}
        readOnly={readOnly}
        onChange={(e) => setText(e.target.value)}
        placeholder="点击「重新扫描生成」自动扫描仓库结构；也可直接编辑此摘要作为项目上下文（供需求对话/方案/会话注入）。"
        style={{ fontFamily: 'monospace', fontSize: 12 }}
      />
    </Space>
  )
}
