// CAP-111 提交日志抽屉（CAP-109 FR-06 演进）：分支选择 + 抓取 + 关键字搜索（提交信息/哈希）
// + 服务端分页 + 展开行提交详情（完整提交信息 + 变更文件）。分支切换已随 CAP-111 下线。
import { useEffect, useState } from 'react'
import { Button, Drawer, Input, Select, Space, Spin, Table, Tag, Typography, message } from 'antd'
import type { ColumnsType } from 'antd/es/table'
import { fetchRepo, getRepoCommit, listRepoCommits } from '../api'
import type { GitRepo, RepoCommitDetail, RepoCommitFile, RepoCommitView } from '../types'
import { fmtTime } from '../../../shared/utils/format'
import { showError } from '../../../shared/utils/showError'
import { DRAWER_DEFAULT } from '../../../shared/utils/overlay'
import { usePermissions } from '../../auth/usePermissions'
import RepoFileViewDrawer from './RepoFileViewDrawer'

interface Props {
  open: boolean
  repo: GitRepo | null
  onCancel: () => void
  /** 抓取后回调外层刷新列表（lastFetchAt/分支列表可能变化） */
  onFetched?: () => void
}

/** git name-status 首字符 → 标签（status 可能是 R100 这类带分数值）；CAP-113 文件查看抽屉共用 */
export const FILE_STATUS: Record<string, { color: string; label: string }> = {
  A: { color: 'green', label: '新增' },
  M: { color: 'blue', label: '修改' },
  D: { color: 'red', label: '删除' },
  R: { color: 'orange', label: '改名' },
  C: { color: 'purple', label: '复制' },
  T: { color: 'default', label: '类型' },
}

export default function RepoCommitsDrawer({ open, repo, onCancel, onFetched }: Props) {
  const { canAction } = usePermissions()
  const canManage = canAction('admin.projects.action.manage-repo')
  const [branch, setBranch] = useState<string | undefined>(undefined)
  const [keyword, setKeyword] = useState('')
  const [page, setPage] = useState(0)
  const [size, setSize] = useState(20)
  const [rows, setRows] = useState<RepoCommitView[]>([])
  const [total, setTotal] = useState(0)
  const [loading, setLoading] = useState(false)
  const [acting, setActing] = useState(false)
  // 展开行详情缓存：sha → 详情（undefined = 加载中）
  const [expandedKeys, setExpandedKeys] = useState<string[]>([])
  const [details, setDetails] = useState<Record<string, RepoCommitDetail | undefined>>({})
  // CAP-113 文件查看抽屉（diff / 原文）
  const [fileView, setFileView] = useState<{ sha: string; file: RepoCommitFile } | null>(null)

  const cloneReady = repo?.sourceType === 'CLONE' && repo?.cloneStatus === 'READY'

  const load = (b = branch, kw = keyword, p = page, s = size) => {
    if (!repo) return
    setLoading(true)
    listRepoCommits(repo.id, { branch: b, keyword: kw || undefined, page: p, size: s })
      .then((r) => {
        setRows(r.items)
        setTotal(r.total)
        setPage(r.page)
        setSize(r.size)
      })
      .catch((e) => showError(e, '加载提交日志失败'))
      .finally(() => setLoading(false))
  }

  // 打开时重置为该仓库默认分支第一页并加载
  useEffect(() => {
    if (open && repo) {
      setBranch(repo.defaultBranch || undefined)
      setKeyword('')
      setPage(0)
      setSize(20)
      setRows([])
      setTotal(0)
      setExpandedKeys([])
      setDetails({})
      setFileView(null)
      setLoading(true)
      listRepoCommits(repo.id, { branch: repo.defaultBranch || undefined, page: 0, size: 20 })
        .then((r) => {
          setRows(r.items)
          setTotal(r.total)
        })
        .catch((e) => showError(e, '加载提交日志失败'))
        .finally(() => setLoading(false))
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open, repo?.id])

  const onFetch = async () => {
    if (!repo) return
    setActing(true)
    try {
      await fetchRepo(repo.id)
      message.success(`已触发「${repo.name}」抓取`)
      onFetched?.()
      // 抓取同步执行（fetchOne 同步接口），直接刷新提交列表
      load()
    } catch (e) {
      showError(e, '抓取失败')
    } finally {
      setActing(false)
    }
  }

  const onExpand = (expanded: boolean, record: RepoCommitView) => {
    setExpandedKeys((keys) =>
      expanded ? [...keys, record.sha] : keys.filter((k) => k !== record.sha),
    )
    if (expanded && repo && !(record.sha in details)) {
      setDetails((d) => ({ ...d, [record.sha]: undefined }))
      getRepoCommit(repo.id, record.sha)
        .then((detail) => setDetails((d) => ({ ...d, [record.sha]: detail })))
        .catch((e) => {
          showError(e, '加载提交详情失败')
          setExpandedKeys((keys) => keys.filter((k) => k !== record.sha))
        })
    }
  }

  const renderDetail = (record: RepoCommitView) => {
    const d = details[record.sha]
    if (!d) {
      return <Spin size="small" style={{ margin: '8px 0' }} />
    }
    return (
      <div style={{ padding: '4px 0' }}>
        <Space direction="vertical" size={4} style={{ width: '100%', marginBottom: 8 }}>
          <Typography.Text copyable={{ text: d.sha }}>
            <Typography.Text code>{d.sha}</Typography.Text>
          </Typography.Text>
          <Typography.Text type="secondary">
            {d.authorName} &lt;{d.authorEmail}&gt; 提交于 {fmtTime(d.time)}
          </Typography.Text>
          <pre style={{ margin: '4px 0 0', whiteSpace: 'pre-wrap', wordBreak: 'break-all' }}>
            {d.subject + (d.body ? `\n\n${d.body}` : '')}
          </pre>
        </Space>
        <Table
          rowKey={(f) => `${f.status}:${f.oldPath ?? ''}:${f.path}`}
          size="small"
          dataSource={d.files}
          pagination={false}
          locale={{ emptyText: '无文件变更（如 merge 合并提交）' }}
          columns={[
            {
              title: '状态',
              dataIndex: 'status',
              width: 70,
              render: (s: string) => {
                const meta = FILE_STATUS[s.charAt(0)]
                return <Tag color={meta?.color}>{meta?.label ?? s}</Tag>
              },
            },
            {
              title: `文件（${d.files.length}）`,
              dataIndex: 'path',
              render: (p: string, f) => (
                // CAP-113：点击路径打开文件查看抽屉（变更对比 / 查看原文）
                <Typography.Link
                  style={{ fontFamily: 'monospace' }}
                  onClick={() => setFileView({ sha: d.sha, file: f })}
                >
                  {f.oldPath ? `${f.oldPath} → ${p}` : p}
                </Typography.Link>
              ),
            },
          ]}
        />
      </div>
    )
  }

  const columns: ColumnsType<RepoCommitView> = [
    {
      title: '提交',
      dataIndex: 'sha',
      width: 100,
      render: (sha: string) => <Typography.Text code>{sha.slice(0, 8)}</Typography.Text>,
    },
    {
      title: '提交时间',
      dataIndex: 'time',
      width: 170,
      render: (t: string) => fmtTime(t),
    },
    {
      title: '作者',
      dataIndex: 'authorName',
      width: 140,
      render: (n: string, r) => (
        <Typography.Text title={r.authorEmail}>{n || r.authorEmail}</Typography.Text>
      ),
    },
    { title: '提交信息', dataIndex: 'subject', ellipsis: true },
  ]

  return (
    <Drawer
      title={repo ? `提交日志：${repo.name}` : '提交日志'}
      open={open}
      onClose={onCancel}
      width={DRAWER_DEFAULT}
      destroyOnHidden
    >
      <Space wrap style={{ marginBottom: 12 }}>
        <span>分支</span>
        <Select
          value={branch}
          onChange={(v) => {
            setBranch(v)
            setPage(0)
            setExpandedKeys([])
            load(v, keyword, 0, size)
          }}
          options={(repo?.branches ?? []).map((b) => ({ value: b, label: b }))}
          style={{ width: 220 }}
          placeholder="默认分支"
          showSearch
        />
        <Input.Search
          allowClear
          placeholder="搜索提交信息 / 提交哈希"
          style={{ width: 240 }}
          onSearch={(v) => {
            setKeyword(v)
            setPage(0)
            setExpandedKeys([])
            load(branch, v, 0, size)
          }}
        />
        {canManage && cloneReady && (
          <Button onClick={onFetch} loading={acting}>
            抓取
          </Button>
        )}
      </Space>
      <Table
        rowKey="sha"
        size="small"
        loading={loading}
        columns={columns}
        dataSource={rows}
        expandable={{
          expandedRowKeys: expandedKeys,
          onExpand,
          expandedRowRender: renderDetail,
        }}
        pagination={{
          current: page + 1,
          pageSize: size,
          total,
          showSizeChanger: true,
          pageSizeOptions: [10, 20, 50, 100],
          showTotal: (t) => `共 ${t} 条`,
          onChange: (p, s) => {
            // 改页大小时回第一页（服务端切片口径，避免页码悬空）
            const nextPage = s === size ? p - 1 : 0
            setPage(nextPage)
            setSize(s)
            load(branch, keyword, nextPage, s)
          },
        }}
        locale={{ emptyText: keyword ? '无匹配提交' : '该分支暂无提交' }}
      />
      <RepoFileViewDrawer
        open={fileView != null}
        repoId={repo?.id ?? null}
        sha={fileView?.sha ?? null}
        file={fileView?.file ?? null}
        onClose={() => setFileView(null)}
      />
    </Drawer>
  )
}
