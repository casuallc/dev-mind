import { Button, Checkbox, Empty, Modal, Space, Table, Tag, Typography, message } from 'antd'
import { useEffect, useState } from 'react'
import { importGit, previewGit } from '../api'
import type { GitCommit, GitScanRepoDiag } from '../types'
import { fmtTime } from '../../../shared/utils/format'

interface Props {
  open: boolean
  date: string
  onCancel: () => void
  /** 导入完成后回调（刷新条目列表） */
  onImported: () => void
}

const OUTCOME_TAG: Record<string, { color: string; label: string }> = {
  SCANNED: { color: 'success', label: '已扫描' },
  SKIPPED: { color: 'warning', label: '已跳过' },
  FAILED: { color: 'error', label: '失败' },
}

/** CAP-28 FR-04 git 提交导入：预览当日（按本人 git author 过滤）提交，勾选后落成工作条目。 */
export default function GitImportModal({ open, date, onCancel, onImported }: Props) {
  const [rows, setRows] = useState<GitCommit[]>([])
  const [diags, setDiags] = useState<GitScanRepoDiag[]>([])
  const [loading, setLoading] = useState(false)
  const [importing, setImporting] = useState(false)
  const [selected, setSelected] = useState<Set<string>>(new Set())

  const load = () => {
    setLoading(true)
    previewGit(date)
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
  }, [open, date])

  const doImport = async () => {
    const items = rows
      .filter((c) => selected.has(c.sha) && !c.alreadyImported)
      .map((c) => ({ repoId: c.repoId, commitSha: c.sha, subject: c.subject }))
    if (items.length === 0) {
      message.info('请先勾选要导入的提交')
      return
    }
    setImporting(true)
    try {
      const r = await importGit(date, items)
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
      title={`从 Git 导入（${date}）`}
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
        扫描你勾选过的仓库当日提交（按你的 git 署名过滤，仅作者为你的提交出现在此）。
        工时导入后可逐条编辑补齐。
      </Typography.Paragraph>
      <Table
        rowKey="sha"
        size="small"
        loading={loading}
        dataSource={rows}
        pagination={false}
        locale={{
          emptyText: (
            <Empty description="当日没有扫描到你的提交：确认已在「仓库订阅」勾选参与扫描的仓库，且 git 署名与提交一致（见下方扫描详情）" />
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
