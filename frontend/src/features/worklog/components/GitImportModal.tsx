import { Button, Checkbox, DatePicker, Empty, Modal, Space, Table, Tag, Typography, message } from 'antd'
import dayjs, { type Dayjs } from 'dayjs'
import { useEffect, useState } from 'react'
import { importGit, previewGit } from '../api'
import type { GitCommit, GitScanRepoDiag } from '../types'
import { fmtTime } from '../../../shared/utils/format'

interface Props {
  open: boolean
  onCancel: () => void
  /** 导入完成后回调（刷新条目列表） */
  onImported: () => void
}

const OUTCOME_TAG: Record<string, { color: string; label: string }> = {
  SCANNED: { color: 'success', label: '已扫描' },
  SKIPPED: { color: 'warning', label: '已跳过' },
  FAILED: { color: 'error', label: '失败' },
}

/** 扫描范围快捷选项：本周=周一至周日 */
const RANGE_PRESETS: { label: string; value: [Dayjs, Dayjs] }[] = (() => {
  const today = dayjs()
  const monday = today.startOf('week').add(1, 'day') // dayjs 周日开头，+1 = 周一
  return [
    { label: '今天', value: [today, today] },
    { label: '昨天', value: [today.subtract(1, 'day'), today.subtract(1, 'day')] },
    { label: '本周', value: [monday, monday.add(6, 'day')] },
    { label: '上周', value: [monday.subtract(7, 'day'), monday.subtract(1, 'day')] },
  ]
})()

/** CAP-28 FR-04 git 提交导入：预览范围内（按本人 git author 过滤）提交，勾选后按提交实际日期落成工作条目。 */
export default function GitImportModal({ open, onCancel, onImported }: Props) {
  const [range, setRange] = useState<[Dayjs, Dayjs]>(RANGE_PRESETS[0].value)
  const [rows, setRows] = useState<GitCommit[]>([])
  const [diags, setDiags] = useState<GitScanRepoDiag[]>([])
  const [loading, setLoading] = useState(false)
  const [importing, setImporting] = useState(false)
  const [selected, setSelected] = useState<Set<string>>(new Set())

  const fromStr = range[0].format('YYYY-MM-DD')
  const toStr = range[1].format('YYYY-MM-DD')

  const load = () => {
    setLoading(true)
    previewGit(fromStr, toStr)
      .then((res) => {
        setRows(res.commits)
        setDiags(res.repos)
        // 默认勾选未导入的
        setSelected(new Set(res.commits.filter((c) => !c.alreadyImported).map((c) => c.sha)))
      })
      .catch((e) => message.error(`扫描失败: ${e.message}`))
      .finally(() => setLoading(false))
  }

  useEffect(() => {
    if (open) load()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open, fromStr, toStr])

  const doImport = async () => {
    const items = rows
      .filter((c) => selected.has(c.sha) && !c.alreadyImported)
      .map((c) => ({
        repoId: c.repoId,
        commitSha: c.sha,
        subject: c.subject,
        // 条目归属日 = 提交实际日期（本机时区），范围导入时跨天各归各日
        date: dayjs(c.committedAt).format('YYYY-MM-DD'),
      }))
    if (items.length === 0) {
      message.info('请先勾选要导入的提交')
      return
    }
    setImporting(true)
    try {
      const r = await importGit(items)
      message.success(`导入完成：新增 ${r.created} 条，跳过已存在 ${r.skipped} 条`)
      onImported()
      onCancel()
    } catch (e) {
      message.error(e instanceof Error ? e.message : '导入失败')
    } finally {
      setImporting(false)
    }
  }

  return (
    <Modal
      title="从 Git 导入"
      open={open}
      onCancel={onCancel}
      width={860}
      destroyOnHidden
      footer={
        <Space>
          <Button onClick={load} disabled={loading}>
            重新扫描
          </Button>
          <Button type="primary" loading={importing} onClick={doImport}>
            导入选中（{selected.size}）
          </Button>
        </Space>
      }
    >
      <Typography.Paragraph type="secondary">
        扫描你勾选过的仓库在所选时间范围内的提交（按你的 git 署名过滤，仅作者为你的提交出现在此），
        导入后条目按提交实际日期落账，工时可逐条编辑补齐。
      </Typography.Paragraph>
      <DatePicker.RangePicker
        style={{ marginBottom: 12 }}
        value={range}
        allowClear={false}
        presets={RANGE_PRESETS}
        disabledDate={(d) => d.isAfter(dayjs(), 'day')}
        onChange={(r) => {
          if (r && r[0] && r[1]) setRange([r[0], r[1]])
        }}
      />
      <Table
        rowKey="sha"
        size="small"
        loading={loading}
        dataSource={rows}
        pagination={false}
        locale={{
          emptyText: (
            <Empty description="该范围内没有扫描到你的提交：确认已在「仓库订阅」勾选参与扫描的仓库，且 git 署名与提交一致（见下方扫描详情）" />
          ),
        }}
        columns={[
          {
            title: '',
            width: 40,
            render: (_, c) => (
              <Checkbox
                checked={selected.has(c.sha)}
                disabled={c.alreadyImported}
                onChange={(e) => {
                  const next = new Set(selected)
                  if (e.target.checked) next.add(c.sha)
                  else next.delete(c.sha)
                  setSelected(next)
                }}
              />
            ),
          },
          { title: '仓库', dataIndex: 'repoName', width: 140 },
          {
            title: '提交',
            dataIndex: 'sha',
            width: 90,
            render: (s: string) => <Typography.Text code>{s.slice(0, 7)}</Typography.Text>,
          },
          { title: '主题', dataIndex: 'subject', ellipsis: true },
          {
            title: '时间',
            dataIndex: 'committedAt',
            width: 160,
            render: (t: string) => fmtTime(t),
          },
          {
            title: '状态',
            width: 90,
            render: (_, c) =>
              c.alreadyImported ? <Tag>已导入</Tag> : <Tag color="blue">可导入</Tag>,
          },
        ]}
      />
      {diags.length > 0 && (
        <div style={{ marginTop: 12 }}>
          <Typography.Text type="secondary">扫描详情（{diags.length} 个勾选仓库）：</Typography.Text>
          <div style={{ marginTop: 4 }}>
            {diags.map((d) => {
              const tag = OUTCOME_TAG[d.outcome] ?? { color: 'default', label: d.outcome }
              return (
                <div key={d.repoId} style={{ lineHeight: '24px' }}>
                  <Tag color={tag.color}>{tag.label}</Tag>
                  <Typography.Text strong>{d.repoName}</Typography.Text>
                  <Typography.Text type="secondary" style={{ marginLeft: 8 }}>
                    {d.outcome === 'SCANNED'
                      ? `${d.commitCount} 条提交${d.authorFilter ? `（署名过滤: ${d.authorFilter}）` : ''}`
                      : ''}
                    {d.detail ? `${d.outcome === 'SCANNED' ? '；' : ''}${d.detail}` : ''}
                  </Typography.Text>
                </div>
              )
            })}
          </div>
        </div>
      )}
    </Modal>
  )
}
