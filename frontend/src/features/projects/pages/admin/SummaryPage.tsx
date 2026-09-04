// 上下文摘要页（/admin/projects/:id/summary）：重新扫描生成 + 人工编辑保存。
import { useCallback, useEffect, useState } from 'react'
import { Button, Card, Input, Space, Typography, message } from 'antd'
import { DiffOutlined, ReloadOutlined } from '@ant-design/icons'
import { useParams } from 'react-router-dom'
import { getSummary, refreshSummary, saveSummary } from '../../api'
import type { ContextSummary } from '../../types'
import { fmtTime } from '../../../../shared/utils/format'

export default function SummaryPage() {
  const { id = '' } = useParams<{ id: string }>()
  const [summary, setSummary] = useState<ContextSummary>({ projectId: id, summary: '' })
  const [text, setText] = useState('')
  const [busy, setBusy] = useState(false)

  const load = useCallback(() => {
    getSummary(id)
      .then((s) => {
        setSummary(s)
        setText(s.summary)
      })
      .catch(() => {})
  }, [id])

  useEffect(() => {
    load()
  }, [load])

  const doRefresh = async () => {
    setBusy(true)
    try {
      const s = await refreshSummary(id)
      setSummary(s)
      setText(s.summary)
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
      setSummary(s)
      message.success('已保存（人工修正）')
    } catch (e) {
      message.error(`保存失败：${(e as Error).message}`)
    } finally {
      setBusy(false)
    }
  }

  return (
    <Card
      title="上下文摘要"
      extra={
        <Space>
          <Button icon={<ReloadOutlined />} loading={busy} onClick={doRefresh}>
            重新扫描生成
          </Button>
          <Button type="primary" icon={<DiffOutlined />} loading={busy} onClick={doSave}>
            保存修改
          </Button>
        </Space>
      }
    >
      <Typography.Paragraph type="secondary" style={{ marginBottom: 12 }}>
        项目上下文摘要供需求对话/方案/会话注入；可「重新扫描生成」自动扫描仓库结构，也可直接编辑后保存。
        {summary.generatedAt && ` 生成于 ${fmtTime(summary.generatedAt)}。`}
      </Typography.Paragraph>
      <Input.TextArea
        rows={18}
        value={text}
        onChange={(e) => setText(e.target.value)}
        placeholder="点击「重新扫描生成」自动扫描仓库结构；也可直接编辑此摘要作为项目上下文（供需求对话/方案/会话注入）。"
        style={{ fontFamily: 'monospace', fontSize: 12 }}
      />
    </Card>
  )
}
