// CAP-109 FR-06 代码库提交历史抽屉：分支选择（实体分支列表，默认默认分支）+ 抓取 + 切换分支 + 提交表格。
// CLONE+READY 行才可抓取/切换；LOCAL 行只读看提交（平台不替用户切本地工作区分支）。
import { useEffect, useState } from 'react'
import { Button, Drawer, Select, Space, Table, Typography, message } from 'antd'
import type { ColumnsType } from 'antd/es/table'
import { checkoutRepo, fetchRepo, listRepoCommits } from '../api'
import type { GitRepo, RepoCommitView } from '../types'
import { fmtTime } from '../../../shared/utils/format'
import { showError } from '../../../shared/utils/showError'
import { DRAWER_DEFAULT } from '../../../shared/utils/overlay'
import { usePermissions } from '../../auth/usePermissions'

interface Props {
  open: boolean
  repo: GitRepo | null
  onCancel: () => void
  /** 抓取后回调外层刷新列表（lastFetchAt/分支列表可能变化） */
  onFetched?: () => void
}

export default function RepoCommitsDrawer({ open, repo, onCancel, onFetched }: Props) {
  const { canAction } = usePermissions()
  const canManage = canAction('admin.projects.action.manage-repo')
  const [branch, setBranch] = useState<string | undefined>(undefined)
  const [rows, setRows] = useState<RepoCommitView[]>([])
  const [loading, setLoading] = useState(false)
  const [acting, setActing] = useState(false)

  const cloneReady = repo?.sourceType === 'CLONE' && repo?.cloneStatus === 'READY'

  const load = (b?: string) => {
    if (!repo) return
    setLoading(true)
    listRepoCommits(repo.id, b ?? branch)
      .then(setRows)
      .catch((e) => showError(e, '加载提交历史失败'))
      .finally(() => setLoading(false))
  }

  // 打开时重置分支为该仓库默认分支并加载
  useEffect(() => {
    if (open && repo) {
      setBranch(repo.defaultBranch || undefined)
      setRows([])
      setLoading(true)
      listRepoCommits(repo.id, repo.defaultBranch || undefined)
        .then(setRows)
        .catch((e) => showError(e, '加载提交历史失败'))
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

  const onCheckout = async () => {
    if (!repo || !branch) return
    setActing(true)
    try {
      await checkoutRepo(repo.id, branch)
      message.success(`已切换到分支「${branch}」`)
      load()
    } catch (e) {
      showError(e, '切换分支失败')
    } finally {
      setActing(false)
    }
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
      title={repo ? `提交历史：${repo.name}` : '提交历史'}
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
            load(v)
          }}
          options={(repo?.branches ?? []).map((b) => ({ value: b, label: b }))}
          style={{ width: 260 }}
          placeholder="默认分支"
          showSearch
        />
        {canManage && cloneReady && (
          <>
            <Button onClick={onFetch} loading={acting}>
              抓取
            </Button>
            <Button
              type="primary"
              onClick={onCheckout}
              loading={acting}
              disabled={!branch}
              title="切换服务端克隆的本地检出分支（对齐远端 origin/<branch>）"
            >
              切换到此分支
            </Button>
          </>
        )}
      </Space>
      {repo?.sourceType === 'LOCAL' && (
        <Typography.Paragraph type="secondary" style={{ marginTop: 0 }}>
          本地路径仓库只读查看；分支切换请在本地工作区自行操作。
        </Typography.Paragraph>
      )}
      <Table
        rowKey="sha"
        size="small"
        loading={loading}
        columns={columns}
        dataSource={rows}
        pagination={false}
        locale={{ emptyText: '该分支暂无提交' }}
      />
    </Drawer>
  )
}
